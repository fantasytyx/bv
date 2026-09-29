package dev.aaa1115910.bv.util

import dev.aaa1115910.bv.BVApp
import dev.aaa1115910.bv.player.cdn.CdnSpeedRecorder
import dev.aaa1115910.bv.player.cdn.cdnHostOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * CDN 实测速度缓存。
 *
 * 播放过程中由 CdnFailoverDataSource 上报实测吞吐，按 CDN 域族聚合；下次起播时用它给候选地址排序。
 * 每个域族保留**最近** [MAX_SAMPLES_PER_FAMILY] 个样本（滑动窗口，分数跟着实际情况走）；
 * 超过 [TTL_MS] 没再采到样本的域族会被丢弃重测。结果落 DataStore。
 */
object CdnSpeedStore : CdnSpeedRecorder {
    /** 滑动窗口保留的样本数。 */
    private const val MAX_SAMPLES_PER_FAMILY = 3

    /** 样本有效期；超过这段时间没再采到样本的域族会被丢弃重测。 */
    private const val TTL_MS = 6 * 60 * 60 * 1000L

    /** 最多缓存的域族数量，超出按最后更新时间淘汰。 */
    private const val MAX_ENTRIES = 100

    private const val FLUSH_DEBOUNCE_MS = 2_000L

    /** 官方 CDN 的域名：B 站自有 + Akamai（海外节点）。 */
    private val OFFICIAL_DOMAINS = listOf("bilivideo.com", "bilivideo.net", "akamaized.com", "akamaized.net")

    private data class Entry(
        val samples: MutableList<Long>,
        var updatedAtMs: Long
    ) {
        val average: Long get() = if (samples.isEmpty()) 0L else samples.sum() / samples.size
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val entries = LinkedHashMap<String, Entry>()

    private var loadDeferred: Deferred<Unit>? = null
    private var flushJob: Job? = null
    private var dirty = false

    /** 本次播放会话的 id（同一视频的视频流与音频流传入相同值）。 */
    private var sessionId: Long = Long.MIN_VALUE

    /** 本次播放会话选定的探索目标厂商；同一会话内视频流与音频流共用它。 */
    private var explorationFamily: String? = null

    /**
     * 给候选播放地址排序。
     *
     * 默认把服务端分配的 PCDN 地址排前面，官方 `upos-*` 只作备选（直连官方 CDN 容易触发风控）；
     * `Prefs.preferOfficialCdn` 打开时两层互换，官方在前、PCDN 兜底。
     * 同层内按实测吞吐降序，失败过的排最后；跨层不比较速度。
     *
     * 每个播放会话会挑一个「还没测出速度」的厂商放到首选去采样 —— 这是发现更快节点的唯一途径
     * （方案：只统计实际播放吞吐，没有独立探测）。同一会话的视频流与音频流共用同一个目标，
     * 避免一次播放里同时出现两个厂商。
     *
     * @param sessionId 播放会话标识，同一次播放的视频流与音频流传入相同值
     * @param advanceSteps 用户手动刷新的累计次数：按域族往后轮换这么多个厂商。
     *   必须由调用方传入而不能在这里自增 —— 一次刷新会分别给视频流和音频流各调一次，
     *   自增就变成每次 +2，遇上只有两个域族的流（`2 % 2 == 0`）永远轮不动
     */
    suspend fun order(urls: List<String>, sessionId: Long, advanceSteps: Int = 0): List<String> {
        val candidates = urls.filter { it.isNotBlank() }.distinct()
        if (candidates.size <= 1) return candidates
        val officialOnly = Prefs.preferOfficialCdn
        ensureLoaded().await()
        synchronized(lock) {
            if (this.sessionId != sessionId) {
                this.sessionId = sessionId
                explorationFamily = null
            }
            val official = candidates.filter(::isOfficialCdn)
            val pcdn = candidates.filterNot(::isOfficialCdn)
            val tiers =
                if (officialOnly) {
                    // 用户偏好官方：官方层提到前面，仍保留 PCDN 作为兜底
                    listOf(official, pcdn)
                } else {
                    // 默认走服务端分配的 PCDN，官方只作备选
                    listOf(pcdn, official)
                }.filter { it.isNotEmpty() }
            if (explorationFamily == null) {
                // 只在最先播放的那一层里挑，否则目标会落在永远轮不到的备选层上
                explorationFamily = tiers.first().firstOrNull(::isExplorable)?.let(::familyOf)
            }
            val ordered = tiers.flatMap { orderWithinTier(it, explorationFamily) }
            // 手动刷新：按域族往后轮换 advanceSteps 个厂商，优先层轮完接备选层
            return rotateFamilies(ordered, advanceSteps)
        }
    }

    /**
     * 按域族把候选整体左移 [steps] 个厂商（族内顺序不变），用于刷新时逐个换厂商重试。
     * 域族数量不同的流（视频 2 个、音频 3 个）用同一步数轮换出来的结果也不同，各自都能换到新厂商。
     */
    private fun rotateFamilies(candidates: List<String>, steps: Int): List<String> {
        if (steps <= 0 || candidates.isEmpty()) return candidates
        val blocks = LinkedHashMap<String, MutableList<String>>()
        for (url in candidates) {
            blocks.getOrPut(familyOf(url) ?: url) { mutableListOf() }.add(url)
        }
        if (blocks.size <= 1) return candidates
        val families = blocks.keys.toList()
        val shift = steps % families.size
        if (shift == 0) return candidates
        return (families.drop(shift) + families.take(shift)).flatMap { blocks.getValue(it) }
    }

    override fun onSample(host: String, bytesPerSecond: Long) {
        if (host.isBlank() || bytesPerSecond <= 0L) return
        val family = familyOfHost(host)
        synchronized(lock) {
            val now = System.currentTimeMillis()
            val entry = entries[family]?.takeIf { now - it.updatedAtMs <= TTL_MS }
            if (entry == null) {
                entries[family] = Entry(mutableListOf(bytesPerSecond), now)
            } else {
                // 滑动窗口：丢弃最旧的样本，让分数跟着实际情况走
                entry.samples.add(bytesPerSecond)
                while (entry.samples.size > MAX_SAMPLES_PER_FAMILY) entry.samples.removeAt(0)
                entry.updatedAtMs = now
            }
            trimLocked(now)
            scheduleFlushLocked()
        }
    }

    override fun onFailure(host: String) {
        if (host.isBlank()) return
        val family = familyOfHost(host)
        synchronized(lock) {
            // 硬失败立即置 0 分，避免下次继续优先选中它
            val now = System.currentTimeMillis()
            entries[family] = Entry(mutableListOf(0L), now)
            trimLocked(now)
            scheduleFlushLocked()
        }
    }

    /**
     * 同层顺序：本次会话的探索目标放首位（仅当它还没测出速度时），其余按
     * 「已测出速度 → 没测过 → 测过但失败」排，已测出速度的按吞吐降序。
     * 没有探索目标时保持服务端顺序作为并列顺序。
     */
    private fun orderWithinTier(tier: List<String>, explorationFamily: String?): List<String> {
        val head =
            explorationFamily
                ?.let { family -> tier.firstOrNull { familyOf(it) == family } }
                ?.takeIf { scoreOf(it) == null }
        val rest =
            tier
                .filterNot { it == head }
                .sortedWith(compareBy<String> { priorityOf(it) }.thenByDescending { scoreOf(it) ?: 0L })
        return listOfNotNull(head) + rest
    }

    /** 是否值得为它付一次起播：还没测出任何速度。 */
    private fun isExplorable(url: String): Boolean = scoreOf(url) == null

    /** 排序优先级：0 = 已测出速度，1 = 没测过，2 = 测过但失败。 */
    private fun priorityOf(url: String): Int =
        when (val score = scoreOf(url)) {
            null -> 1
            else -> if (score > 0L) 0 else 2
        }

    private fun scoreOf(url: String): Long? {
        val family = familyOf(url) ?: return null
        val entry = entries[family] ?: return null
        if (System.currentTimeMillis() - entry.updatedAtMs > TTL_MS) return null
        return entry.average
    }

    private fun familyOf(url: String): String? = cdnHostOf(url)?.let(::familyOfHost)

    /**
     * 域族：官方 host 本身就是稳定标识，原样使用；其余去掉最左侧的节点标识段，
     * 让同一 CDN 在不同请求之间保持稳定（`xy111….mcdn.bilivideo.cn` → `mcdn.bilivideo.cn`）。
     */
    private fun familyOfHost(host: String): String {
        val normalized = host.lowercase()
        if (isOfficialHost(normalized)) return normalized
        val labels = normalized.split('.')
        return if (labels.size > 2) labels.drop(1).joinToString(".") else normalized
    }

    private fun isOfficialCdn(url: String): Boolean = cdnHostOf(url)?.lowercase()?.let(::isOfficialHost) ?: false

    /**
     * 是否官方 CDN：B 站自己的 `upos-*` 服务（含 `upos-tf-*`）与 tf 代理 `proxy-tf-*`，
     * **且域名必须是 B 站自有或 Akamai**。
     *
     * 其余一律算非官方：`mcdn`、`cn-*` 运营商缓存、`edge.mountaintoys.cn` 这类第三方 PCDN、
     * 以及 `upos-` 前缀但落在别的域名上的（如 `upos-lcdn-….solseed.cn`）—— 不需要维护厂商名单，
     * 新厂商自动落入优先层。
     */
    private fun isOfficialHost(host: String): Boolean {
        if (!host.startsWith("upos-") && !host.startsWith("proxy-tf-")) return false
        return OFFICIAL_DOMAINS.any { host.endsWith(".$it") }
    }

    /** 丢弃过期条目；超出上限时淘汰最久未更新的。 */
    private fun trimLocked(nowMs: Long) {
        entries.entries.removeAll { nowMs - it.value.updatedAtMs > TTL_MS }
        if (entries.size <= MAX_ENTRIES) return
        val keep = entries.entries.sortedByDescending { it.value.updatedAtMs }.take(MAX_ENTRIES).map { it.key }.toHashSet()
        entries.keys.retainAll(keep)
    }

    private fun ensureLoaded(): Deferred<Unit> =
        synchronized(lock) {
            loadDeferred
                ?: scope
                    .async {
                        val raw =
                            runCatching {
                                BVApp.dataStoreManager.getPreference(PrefKeys.prefCdnSpeedScoresRequest)
                            }.getOrDefault("")
                        val loaded = parse(raw)
                        synchronized(lock) {
                            // 加载期间可能已经采到样本，以内存里的为准
                            loaded.forEach { (family, entry) ->
                                if (family !in entries) entries[family] = entry
                            }
                            trimLocked(System.currentTimeMillis())
                        }
                    }.also { loadDeferred = it }
        }

    private fun scheduleFlushLocked() {
        dirty = true
        if (flushJob?.isActive == true) return
        flushJob =
            scope.launch {
                while (true) {
                    delay(FLUSH_DEBOUNCE_MS)
                    val payload =
                        synchronized(lock) {
                            if (!dirty) return@launch
                            dirty = false
                            serializeLocked()
                        }
                    runCatching {
                        BVApp.dataStoreManager.editPreference(PrefKeys.prefCdnSpeedScoresKey, payload)
                    }
                }
            }
    }

    private fun serializeLocked(): String =
        entries.entries.joinToString("\n") { (family, entry) ->
            "$family\t${entry.samples.joinToString(",")}\t${entry.updatedAtMs}"
        }

    private fun parse(raw: String): Map<String, Entry> {
        if (raw.isBlank()) return emptyMap()
        return raw
            .lineSequence()
            .mapNotNull { line ->
                val parts = line.split('\t')
                if (parts.size < 3) return@mapNotNull null
                val family = parts[0].trim()
                val samples = parts[1].split(',').mapNotNull { it.trim().toLongOrNull() }.toMutableList()
                val updatedAtMs = parts[2].trim().toLongOrNull() ?: return@mapNotNull null
                if (family.isEmpty() || samples.isEmpty()) return@mapNotNull null
                family to Entry(samples, updatedAtMs)
            }.toMap()
    }
}
