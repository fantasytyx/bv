package dev.aaa1115910.bv.viewmodel.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.aaa1115910.biliapi.entity.user.DynamicItem
import dev.aaa1115910.biliapi.entity.user.DynamicVideo
import dev.aaa1115910.biliapi.http.entity.AuthFailureException
import dev.aaa1115910.biliapi.repositories.UserRepository
import dev.aaa1115910.bv.BVApp
import dev.aaa1115910.bv.util.Prefs
import dev.aaa1115910.bv.util.fInfo
import dev.aaa1115910.bv.util.fWarn
import dev.aaa1115910.bv.util.toast
import dev.aaa1115910.bv.viewmodel.user.PageResult
import dev.aaa1115910.bv.viewmodel.user.PagingState
import dev.aaa1115910.bv.viewmodel.user.newItems
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.annotation.KoinViewModel
import dev.aaa1115910.bv.repository.UserRepository as BvUserRepository

@KoinViewModel
class DynamicViewModel(
    private val bvUserRepository: BvUserRepository,
    private val userRepository: UserRepository
) : ViewModel() {
    companion object {
        private val logger = KotlinLogging.logger {}
    }

    val dynamicVideoList = mutableStateListOf<DynamicVideo>()
    val dynamicAllList = mutableStateListOf<DynamicItem>()

    val videos = PagingState()
    val all = PagingState()

    private var videoUpdateBaseline: String? = null
    private var allUpdateBaseline: String? = null

    private class UpFeed {
        val items = mutableStateListOf<DynamicItem>()
        val paging = PagingState()
    }

    private val upFeeds = mutableMapOf<Long, UpFeed>()

    /** 左侧面板选中的 UP；null = 关注流 */
    var selectedUp by mutableStateOf<Long?>(null)
        private set

    val currentAll: List<DynamicItem>
        get() = selectedUp?.let { upFeeds[it]?.items } ?: dynamicAllList
    val currentVideos: List<DynamicVideo>
        get() = if (selectedUp == null) {
            dynamicVideoList
        } else {
            currentAll.filter { it.video != null }.map { DynamicVideo.fromDynamicItem(it) }
        }
    val currentAllPaging: PagingState
        get() = selectedUp?.let { upFeeds[it]?.paging } ?: all
    val currentVideoPaging: PagingState
        get() = if (selectedUp == null) videos else currentAllPaging

    fun selectUp(mid: Long?) {
        selectedUp = mid
        if (mid == null) {
            // 切回关注流：一直在看某个 UP 时关注流可能一次都没拉过，这里补一次
            ensureCurrentFeedLoaded()
            return
        }
        ensureFeedLoaded(upFeeds.getOrPut(mid) { UpFeed() }, mid)
    }

    /** 切到「全部」tab 时确保当前数据源已有内容；已加载过的 UP 不再发请求 */
    fun ensureCurrentFeedLoaded() {
        val mid = selectedUp
        if (mid == null) {
            if (dynamicAllList.isEmpty() && all.hasMore) {
                viewModelScope.launch { loadMoreAll() }
            }
            return
        }
        ensureFeedLoaded(upFeeds.getOrPut(mid) { UpFeed() }, mid)
    }

    private fun ensureFeedLoaded(feed: UpFeed, mid: Long) {
        if (feed.items.isEmpty()) viewModelScope.launch { loadUpFeed(mid, reset = false) }
    }

    /** 两个 tab 共用同一份分页：哪个 tab 触发都拉该 UP 的下一页 */
    fun loadMoreForSelection() {
        val mid = selectedUp ?: return
        viewModelScope.launch { loadUpFeed(mid, reset = false) }
    }

    fun refreshSelection() {
        val mid = selectedUp ?: return
        viewModelScope.launch { loadUpFeed(mid, reset = true) }
    }

    private suspend fun loadUpFeed(mid: Long, reset: Boolean) {
        val feed = upFeeds.getOrPut(mid) { UpFeed() }
        withContext(Dispatchers.Main) {
            if (reset) {
                feed.paging.reset()
                feed.items.clear()
            }
            if (!bvUserRepository.isLogin) return@withContext
            val ok = feed.paging.load(
                fetch = { _, offset ->
                    val data = userRepository.getDynamicsByUp(mid, offset)
                    val known = feed.items.mapNotNullTo(mutableSetOf()) { it.id }
                    PageResult(
                        items = data.dynamics.newItems(known) { it.id },
                        hasMore = data.hasMore,
                        nextOffset = data.historyOffset
                    )
                },
                append = feed.items::addAll
            )
            if (!ok) reportLoadFailure(feed.paging.lastException)
        }
    }

    val isLogin get() = bvUserRepository.isLogin

    init {
        println("=====init DynamicViewModel")
    }

    // PagingState.load 的 append 在调用方上下文执行，而调用方都在 IO 上，所以这里切主线程改 Compose 状态
    suspend fun loadMoreVideo() {
        if (!bvUserRepository.isLogin) return
        withContext(Dispatchers.Main) {
            val ok = videos.load(
                fetch = { page, offset ->
                    logger.fInfo { "Load more dynamic videos [apiType=${Prefs.apiType}, offset=$offset, page=$page]" }
                    val data = userRepository.getDynamicVideos(
                        page = page,
                        offset = offset,
                        updateBaseline = videoUpdateBaseline ?: "",
                        preferApiType = Prefs.apiType
                    )
                    videoUpdateBaseline = data.updateBaseline
                    val known = dynamicVideoList.mapTo(mutableSetOf()) { it.aid.toString() }
                    PageResult(
                        items = data.videos.newItems(known) { it.aid.toString() },
                        hasMore = data.hasMore,
                        nextPage = page + 1,
                        nextOffset = data.historyOffset
                    )
                },
                append = { dynamicVideoList.addAll(it) }
            )
            if (!ok) reportLoadFailure(videos.lastException)
        }
    }

    suspend fun loadMoreAll() {
        if (!bvUserRepository.isLogin) return
        withContext(Dispatchers.Main) {
            val ok = all.load(
                fetch = { page, offset ->
                    logger.fInfo { "Load more dynamic all [apiType=${Prefs.apiType}, offset=$offset, page=$page]" }
                    val data = userRepository.getDynamics(
                        page = page,
                        offset = offset,
                        updateBaseline = allUpdateBaseline ?: "",
                        preferApiType = Prefs.apiType
                    )
                    allUpdateBaseline = data.updateBaseline
                    val known = dynamicAllList.mapNotNullTo(mutableSetOf()) { it.id }
                    PageResult(
                        items = data.dynamics.newItems(known) { it.id },
                        hasMore = data.hasMore,
                        nextPage = page + 1,
                        nextOffset = data.historyOffset
                    )
                },
                append = { dynamicAllList.addAll(it) }
            )
            if (!ok) reportLoadFailure(all.lastException)
        }
    }

    private fun reportLoadFailure(cause: Throwable?) {
        when (cause) {
            null -> Unit
            // 会话失效的 toast 与登出由 AuthFailureDetection 全局回调统一处理
            is AuthFailureException -> logger.fInfo { "User auth failure" }
            else -> {
                logger.fWarn { "Load dynamic failed: ${cause.stackTraceToString()}" }
                "加载动态失败: ${cause.localizedMessage}".toast(BVApp.context)
            }
        }
    }

    fun clearVideoData() {
        videos.reset()
        dynamicVideoList.clear()
        videoUpdateBaseline = null
    }

    fun clearAllData() {
        all.reset()
        dynamicAllList.clear()
        allUpdateBaseline = null
    }
}
