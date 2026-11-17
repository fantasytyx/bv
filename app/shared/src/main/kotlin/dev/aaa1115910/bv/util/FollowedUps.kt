package dev.aaa1115910.bv.util

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.edit
import de.schnettler.datastore.manager.PreferenceRequest
import dev.aaa1115910.biliapi.entity.user.FollowedUser
import dev.aaa1115910.bv.BVApp
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.text.Collator
import java.util.Locale

/** 已关注 UP 的轻量表示：面板、缓存与搜索都用它，避免依赖接口 DTO */
data class FollowedUp(
    val mid: Long,
    val name: String,
    val avatar: String,
    val sign: String
)

fun List<FollowedUser>.toFollowedUps(): List<FollowedUp> = map {
    FollowedUp(mid = it.mid, name = it.name, avatar = it.avatar, sign = it.sign)
}

/** 关注成功后写缓存：已在列表里就原位更新，否则追加到末尾；顺序在下次同步时以服务端返回为准 */
fun List<FollowedUp>.withFollowedUp(up: FollowedUp): List<FollowedUp> =
    if (any { it.mid == up.mid }) map { if (it.mid == up.mid) up else it } else this + up

/** 取消关注成功后从缓存里移除 */
fun List<FollowedUp>.withoutFollowedUp(mid: Long): List<FollowedUp> = filterNot { it.mid == mid }

/** 7天缓存有效期 */
const val CACHE_TTL_MS = 7 * 24 * 60 * 60 * 1000L

private const val FIELD_SEPARATOR = '\t'
private const val LINE_SEPARATOR = '\n'

/** 一行一个 UP：`mid\tname\tavatar\tsign`；字段内的制表符/换行替换成空格 */
fun encodeFollowedUps(ups: List<FollowedUp>): String = ups.joinToString(LINE_SEPARATOR.toString()) { up ->
    listOf(up.mid.toString(), up.name, up.avatar, up.sign)
        .joinToString(FIELD_SEPARATOR.toString()) { it.replace(FIELD_SEPARATOR, ' ').replace(LINE_SEPARATOR, ' ') }
}

/** 损坏的行（字段不足、`mid` 非法）跳过，不抛异常 */
fun decodeFollowedUps(raw: String): List<FollowedUp> = raw.lineSequence()
    .mapNotNull { line ->
        if (line.isBlank()) return@mapNotNull null
        val fields = line.split(FIELD_SEPARATOR, limit = 4)
        if (fields.size < 4) return@mapNotNull null
        val mid = fields[0].toLongOrNull() ?: return@mapNotNull null
        FollowedUp(mid = mid, name = fields[1], avatar = fields[2], sign = fields[3])
    }
    .toList()

/** 超过 [CACHE_TTL_MS] 视为失效；未来时间戳（时钟回拨）也视为不新鲜 */
fun isCacheFresh(savedAtMs: Long, nowMs: Long): Boolean =
    savedAtMs > 0 && nowMs >= savedAtMs && nowMs - savedAtMs < CACHE_TTL_MS

/** 各账号缓存时间戳里已经失效（或时间戳缺失）的 uid，用于清理过期缓存 */
fun staleCacheUids(savedAt: Map<Long, Long>, nowMs: Long): Set<Long> =
    savedAt.filterValues { !isCacheFresh(it, nowMs) }.keys

/** 关键字忽略空白与大小写，每个字符都要出现在任一文本中 */
fun matchesUpKeyword(keyword: String, vararg texts: String): Boolean {
    val chars = keyword.filterNot { it.isWhitespace() }
    if (chars.isEmpty()) return true
    return chars.all { char -> texts.any { it.contains(char, ignoreCase = true) } }
}

/** 英文/数字开头优先，两段各自按中文 Collator 排序；同序时按 mid 稳定（关注页沿用） */
fun List<FollowedUser>.sortedFollowedUpOrder(): List<FollowedUser> {
    val collator = Collator.getInstance(Locale.CHINA)
    val comparator = Comparator<FollowedUser> { left, right ->
        collator.compare(left.name, right.name).takeIf { it != 0 } ?: left.mid.compareTo(right.mid)
    }
    val asciiFirst = filter { Regex("^[A-Za-z0-9_-]").containsMatchIn(it.name) }
    val chineseFirst = this - asciiFirst.toSet()
    return asciiFirst.sortedWith(comparator) + chineseFirst.sortedWith(comparator)
}

/**
 * 已关注 UP 列表的本地缓存，**按登录用户（uid）分键**：换账号后不会看到上一个账号的关注列表。
 *
 * 一条缓存就是**完整列表**：
 * - 读：新鲜直接返回；过期/缺失时用 [loadOrRefresh] 重新拉全量，拿到后写回缓存；
 * - 写（关注/取消关注）：新鲜就在现有列表上增量修改；已过期则先拉服务端整条列表做基准
 *   （多端操作以服务端为准），再合并这次的改动；
 * - 整条覆盖：[save] 用于拿到完整列表的地方——面板的同步按钮（[reload]）和每次打开关注列表页，
 *   会连同时间戳一起重写。
 * 同一客户端上缓存因此始终与关注关系一致；多端操作后的偏差由任一「整条覆盖」入口对齐。
 */
object FollowedUpCache {
    /** 读-改-写要串行，否则同一时刻的关注/取消关注会互相覆盖 */
    private val editMutex = Mutex()

    private val _changes = MutableSharedFlow<Long>(extraBufferCapacity = 16)

    /** 内容被增量改动的 uid（关注/取消关注），面板据此刷新已加载的列表 */
    val changes: SharedFlow<Long> = _changes.asSharedFlow()

    /** 缓存新鲜时返回整条列表；过期、缺失或解析为空都当作没有缓存 */
    suspend fun load(uid: Long): List<FollowedUp>? {
        if (uid <= 0) return null
        val manager = BVApp.dataStoreManager
        val savedAt = manager.getPreference(
            PreferenceRequest(PrefKeys.prefFollowedUpsCacheTimeKey(uid), 0L)
        )
        if (!isCacheFresh(savedAt, System.currentTimeMillis())) return null
        return decodeFollowedUps(
            manager.getPreference(PreferenceRequest(PrefKeys.prefFollowedUpsCacheKey(uid), ""))
        ).takeIf { it.isNotEmpty() }
    }

    /** 读全量列表：缓存新鲜直接返回，过期/缺失时请求一次（[fetch]）并整条写回缓存 */
    suspend fun loadOrRefresh(uid: Long, fetch: suspend () -> List<FollowedUp>): List<FollowedUp> {
        load(uid)?.let { return it }
        return reload(uid, fetch)
    }

    /** 忽略缓存重拉（面板的同步按钮）：以服务端列表为准整条重写 */
    suspend fun reload(uid: Long, fetch: suspend () -> List<FollowedUp>): List<FollowedUp> {
        val ups = fetch()
        save(uid, ups)
        return ups
    }

    /**
     * 整条重写缓存（拉到全量关注列表后），并在**同一个事务**里清掉其它过期账号的残留。
     *
     * 清理放在写入侧而不是读取侧：登出（含切账号、鉴权失效自动登出）会 [clear] 当前账号，
     * 残留只来自「登录后长期不登出」的多账号场景；写入时顺带清理不多一次事务，读路径也保持只读。
     */
    suspend fun save(uid: Long, ups: List<FollowedUp>) {
        if (uid <= 0) return
        editMutex.withLock {
            val now = System.currentTimeMillis()
            BVApp.dataStoreManager.dataStore.edit { preferences ->
                removeStaleCaches(preferences, now)
                preferences[PrefKeys.prefFollowedUpsCacheKey(uid)] = encodeFollowedUps(ups)
                preferences[PrefKeys.prefFollowedUpsCacheTimeKey(uid)] = now
            }
        }
        // 通知已加载的面板换成同一份数据。订阅方只读缓存（[load]），不会重新发请求
        _changes.emit(uid)
    }

    /** 关注成功后同步缓存；缓存已过期时先用 [fetch] 拉服务端列表做基准 */
    suspend fun add(uid: Long, up: FollowedUp, fetch: suspend () -> List<FollowedUp>) {
        update(uid, fetch) { it.withFollowedUp(up) }
    }

    /** 取消关注成功后同步缓存；缓存已过期时先用 [fetch] 拉服务端列表做基准 */
    suspend fun remove(uid: Long, mid: Long, fetch: suspend () -> List<FollowedUp>) {
        update(uid, fetch) { it.withoutFollowedUp(mid) }
    }

    suspend fun clear(uid: Long) {
        if (uid <= 0) return
        BVApp.dataStoreManager.editPreference(PrefKeys.prefFollowedUpsCacheKey(uid), "")
        BVApp.dataStoreManager.editPreference(PrefKeys.prefFollowedUpsCacheTimeKey(uid), 0L)
    }

    /**
     * 读-改-写数据键。
     *
     * 缓存新鲜时在现有列表现场应用 [transform]，时间戳不动（缓存年龄仍按上次全量拉取算）；
     * 已过期时先 [fetch] 拉整条服务端列表做基准（多端操作以服务端为准），合并这次的改动后
     * 连同新时间戳一起写回。
     *
     * [fetch] 在持有写锁时执行，保证并发的关注/取消关注按顺序合并；所以 [fetch] 里不能再调用
     * 本对象的写方法（[Mutex] 不可重入）。改动后通过 [changes] 通知面板刷新内存列表。
     */
    private suspend fun update(
        uid: Long,
        fetch: suspend () -> List<FollowedUp>,
        transform: (List<FollowedUp>) -> List<FollowedUp>
    ) {
        if (uid <= 0) return
        val changed = editMutex.withLock {
            val manager = BVApp.dataStoreManager
            val dataKey = PrefKeys.prefFollowedUpsCacheKey(uid)
            val timeKey = PrefKeys.prefFollowedUpsCacheTimeKey(uid)
            val savedAt = manager.getPreference(PreferenceRequest(timeKey, 0L))
            val fresh = isCacheFresh(savedAt, System.currentTimeMillis())
            val current = if (fresh) {
                decodeFollowedUps(manager.getPreference(PreferenceRequest(dataKey, "")))
            } else {
                fetch()
            }
            val next = transform(current)
            if (fresh && next == current) return@withLock false
            manager.dataStore.edit { preferences ->
                preferences[dataKey] = encodeFollowedUps(next)
                if (!fresh) preferences[timeKey] = System.currentTimeMillis()
            }
            true
        }
        if (changed) _changes.emit(uid)
    }

    /** 只按缓存键前缀扫描，其它偏好键不受影响；没有过期项时不改 [preferences] */
    private fun removeStaleCaches(preferences: MutablePreferences, nowMs: Long) {
        val savedAt = mutableMapOf<Long, Long>()
        preferences.asMap().forEach { (key, value) ->
            val name = key.name
            // 时间键名以数据键前缀开头，必须放在前面判断
            when {
                name.startsWith(PrefKeys.FollowedUpsCacheTimeKeyPrefix) ->
                    name.removePrefix(PrefKeys.FollowedUpsCacheTimeKeyPrefix)
                        .toLongOrNull()
                        ?.let { uid -> savedAt[uid] = value as? Long ?: 0L }

                name.startsWith(PrefKeys.FollowedUpsCacheKeyPrefix) ->
                    name.removePrefix(PrefKeys.FollowedUpsCacheKeyPrefix)
                        .toLongOrNull()
                        // 只有数据键、没有时间键的也算过期
                        ?.let { uid -> savedAt.getOrPut(uid) { 0L } }
            }
        }
        staleCacheUids(savedAt, nowMs).forEach { uid ->
            preferences.remove(PrefKeys.prefFollowedUpsCacheKey(uid))
            preferences.remove(PrefKeys.prefFollowedUpsCacheTimeKey(uid))
        }
    }
}
