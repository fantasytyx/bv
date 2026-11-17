package dev.aaa1115910.bv.viewmodel.home

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.aaa1115910.biliapi.repositories.UserRepository
import dev.aaa1115910.bv.util.FollowedUp
import dev.aaa1115910.bv.util.FollowedUpCache
import dev.aaa1115910.bv.util.Prefs
import dev.aaa1115910.bv.util.matchesUpKeyword
import dev.aaa1115910.bv.util.toFollowedUps
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.annotation.KoinViewModel

/**
 * 动态页左侧「已关注 UP」面板。
 *
 * 数据只有一份**完整列表**：缓存新鲜就直接读，过期/没有缓存就一次拉全量后写回；同步按钮忽略缓存重拉。
 * 关注/取消关注由其它页面写进缓存（[FollowedUpCache.add] / [FollowedUpCache.remove]），本 ViewModel
 * 订阅 [FollowedUpCache.changes] 保持内存列表与缓存一致。关键字只在内存里过滤，不额外请求。
 * 选中 UP 由调用方（`DynamicViewModel`）持有。
 */
@KoinViewModel
class FollowedUpPanelViewModel(
    private val userRepository: UserRepository
) : ViewModel() {
    private val loadedUps = mutableStateListOf<FollowedUp>()

    var keyword by mutableStateOf("")
        private set

    /** 首屏读缓存 / 拉全量进行中，面板末尾显示「加载中…」 */
    var loading by mutableStateOf(false)
        private set

    /** 拉全量失败时给面板显示重试 */
    var loadFailed by mutableStateOf(false)
        private set

    /** 作废进行中的加载：慢请求回来时不能覆盖之后的列表 */
    private var requestId = 0

    private val displayedUps by derivedStateOf {
        val currentKeyword = keyword
        if (currentKeyword.isBlank()) {
            loadedUps.toList()
        } else {
            loadedUps.filter { matchesUpKeyword(currentKeyword, it.name, it.sign) }
        }
    }

    /** 展示列表：服务端顺序，关键字非空时是 [loadedUps] 的过滤结果 */
    val ups: List<FollowedUp> get() = displayedUps

    /** 列表末尾是否有状态行（加载中 / 加载失败重试）；没有时 UI 不生成那一项 */
    val hasStatus: Boolean get() = loading || loadFailed

    init {
        viewModelScope.launch {
            FollowedUpCache.changes.collect { uid ->
                if (uid == Prefs.uid) FollowedUpCache.load(uid)?.let(::replaceUps)
            }
        }
    }

    /** 首屏：缓存新鲜就用缓存，否则拉全量并写缓存。重复调用不会重复加载 */
    fun load() {
        if (loadedUps.isNotEmpty() || loading) return
        startLoad(useCache = true)
    }

    /** 同步按钮：忽略缓存重新拉全量，成功后整条重写缓存（多端操作后的对齐入口） */
    fun refresh(onFinished: (Boolean) -> Unit = {}) {
        if (!Prefs.isLogin) return
        startLoad(useCache = false, onFinished = onFinished)
    }

    /** 面板底部的「重试」：缓存可能已经失效，先试缓存再请求 */
    fun retry() {
        if (!Prefs.isLogin) return
        startLoad(useCache = true)
    }

    /** 关键字只在已加载的完整列表上过滤；列表还没加载就先加载 */
    fun search(keyword: String) {
        this.keyword = keyword.trim()
        load()
    }

    fun clearKeyword() {
        keyword = ""
    }

    /**
     * 加载入口。[useCache] 为 true 时先看缓存，否则直接请求全量。
     * 同一时刻只认最新一次调用：[requestId] 让旧请求的结果与回调都失效。
     */
    private fun startLoad(useCache: Boolean, onFinished: ((Boolean) -> Unit)? = null) {
        if (!Prefs.isLogin) return
        val token = ++requestId
        loading = true
        loadFailed = false
        viewModelScope.launch {
            var success = false
            try {
                val uid = Prefs.uid
                val fetch: suspend () -> List<FollowedUp> = { fetchFollowedUps(uid) }
                // 读：新鲜就用缓存，过期/缺失时拉全量并写回；同步按钮忽略缓存整条重拉
                val ups = if (useCache) {
                    FollowedUpCache.loadOrRefresh(uid, fetch)
                } else {
                    FollowedUpCache.reload(uid, fetch)
                }
                if (token != requestId) return@launch
                replaceUps(ups)
                success = true
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                if (token != requestId) return@launch
                loadFailed = true
            } finally {
                if (token == requestId) loading = false
            }
            if (token == requestId) onFinished?.invoke(success)
        }
    }

    private fun replaceUps(ups: List<FollowedUp>) {
        loadedUps.clear()
        loadedUps.addAll(ups.distinctBy { it.mid })
    }

    private suspend fun fetchFollowedUps(uid: Long): List<FollowedUp> =
        withContext(Dispatchers.IO) {
            userRepository.getFollowedUsers(mid = uid, preferApiType = Prefs.apiType)
        }.toFollowedUps()
}