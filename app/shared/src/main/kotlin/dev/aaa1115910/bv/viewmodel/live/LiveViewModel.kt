package dev.aaa1115910.bv.viewmodel.live

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.aaa1115910.biliapi.entity.live.LiveAreaItem
import dev.aaa1115910.biliapi.entity.live.LiveRoomItem
import dev.aaa1115910.biliapi.repositories.LiveRepository
import dev.aaa1115910.bv.BVApp
import dev.aaa1115910.bv.util.Prefs
import dev.aaa1115910.bv.util.toast
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.annotation.KoinViewModel

/**
 * 直播页面的显示模式
 */
enum class LiveMode {
    /** 推荐直播 */
    RECOMMEND,
    /** 关注的直播 */
    FOLLOWING,
    /** 分区直播 */
    AREA
}

@KoinViewModel
class LiveViewModel(
    private val liveRepository: LiveRepository
) : ViewModel() {
    private val logger = KotlinLogging.logger("LiveViewModel")
    private var roomLoadJob: Job? = null

    /**
     * 当前直播模式
     */
    var currentMode by mutableStateOf(LiveMode.RECOMMEND)
        private set

    /**
     * 主分区列表（父分区组）
     */
    val parentAreaGroups = mutableStateListOf<dev.aaa1115910.biliapi.entity.live.LiveAreaGroup>()

    /**
     * 主分区数据是否已完成加载（无论成功或失败）
     */
    var areaGroupsLoadCompleted by mutableStateOf(false)
        private set

    /**
     * 当前选中的主分区组
     */
    var currentParentGroup by mutableStateOf<dev.aaa1115910.biliapi.entity.live.LiveAreaGroup?>(null)

    /**
     * 当前主分区下的子分区列表
     */
    val subAreaList = mutableStateListOf<LiveAreaItem>()

    /**
     * 当前选中的子分区
     */
    var currentSubArea by mutableStateOf<LiveAreaItem?>(null)

    /**
     * 当前分区的直播间列表
     */
    val roomList = mutableStateListOf<LiveRoomItem>()

    /**
     * 是否正在加载
     */
    var loading by mutableStateOf(false)

    /**
     * 是否有下一页
     */
    var hasMore by mutableStateOf(true)

    /**
     * 当前页码
     */
    private var currentPage = 1

    /**
     * 上次聚焦的直播间索引（用于从播放器返回时恢复焦点）
     */
    var lastFocusedRoomIndex by mutableStateOf(0)

    /** 是否已登录（用于判断是否显示关注tab） */
    val isLoggedIn: Boolean
        get() = !liveRepository.sessionData.isNullOrBlank()

    init {
        loadAreas()
    }

    /** 当前模式已就绪但列表为空时，触发一次首次加载。 */
    fun ensureRoomsLoaded() {
        if (roomList.isEmpty() && !loading) {
            loadRooms(refresh = true)
        }
    }

    /**
     * 切换到推荐模式
     */
    fun switchToRecommend() {
        if (currentMode == LiveMode.RECOMMEND) return
        currentMode = LiveMode.RECOMMEND
        currentParentGroup = null
        subAreaList.clear()
        currentSubArea = null
        loadRooms(refresh = true)
    }

    /**
     * 切换到关注模式
     */
    fun switchToFollowing() {
        if (currentMode == LiveMode.FOLLOWING) return
        currentMode = LiveMode.FOLLOWING
        currentParentGroup = null
        subAreaList.clear()
        currentSubArea = null
        loadRooms(refresh = true)
    }

    /**
     * 加载所有分区
     */
    fun loadAreas() {
        areaGroupsLoadCompleted = false
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val response = liveRepository.getLiveAreaList()
                if (response.code == 0) {
                    withContext(Dispatchers.Main) {
                        parentAreaGroups.clear()
                        parentAreaGroups.addAll(response.data)
                    }
                    // 缓存分区列表供设置页使用
                    val cacheString = response.data.joinToString(",") { "${it.id}:${it.name}" }
                    Prefs.cachedLiveAreaGroups = cacheString
                    logger.info { "Loaded ${response.data.size} parent area groups" }
                } else {
                    withContext(Dispatchers.Main) {
                        "加载直播分区失败: ${response.message}".toast(BVApp.context)
                    }
                }
            }.onFailure { e ->
                logger.error(e) { "Failed to load live areas" }
                withContext(Dispatchers.Main) {
                    "加载直播分区失败: ${e.message}".toast(BVApp.context)
                }
            }
            withContext(Dispatchers.Main) {
                areaGroupsLoadCompleted = true
            }
        }
    }

    /**
     * 切换主分区（进入分区模式）
     */
    fun switchParentArea(group: dev.aaa1115910.biliapi.entity.live.LiveAreaGroup) {
        currentMode = LiveMode.AREA
        if (currentParentGroup?.id == group.id) return
        currentParentGroup = group
        subAreaList.clear()
        // 添加"全部"分区项
        subAreaList.add(LiveAreaItem(id = "0", parentId = group.id.toString(), oldAreaId = "0", name = "全部", pic = "", parentName = group.name, areaType = 0))
        subAreaList.addAll(group.list)
        // 默认选中第一个子分区
        if (subAreaList.isNotEmpty()) {
            currentSubArea = subAreaList[0]
            loadRooms(refresh = true)
        }
    }

    /**
     * 切换子分区
     */
    fun switchSubArea(area: LiveAreaItem) {
        if (currentSubArea?.id == area.id) return
        currentSubArea = area
        loadRooms(refresh = true)
    }

    /**
     * 加载直播间列表
     * @param refresh 是否刷新（清空现有数据）
     */
    fun loadRooms(refresh: Boolean = false) {
        val request = buildRoomLoadRequest(refresh) ?: return
        val currentJob = roomLoadJob
        if (currentJob?.isActive == true) {
            if (!refresh) return
            currentJob.cancel()
        }

        loading = true
        val loadJob = viewModelScope.launch(start = CoroutineStart.LAZY) {
            val activeJob = coroutineContext[Job]
            if (refresh) {
                currentPage = 1
                roomList.clear()
                hasMore = true
            }

            try {
                val result = withContext(Dispatchers.IO) {
                    when (request.mode) {
                        LiveMode.RECOMMEND -> loadRecommendRooms(request.page)
                        LiveMode.FOLLOWING -> loadFollowingRooms(request.page)
                        LiveMode.AREA -> loadAreaRooms(
                            area = request.area ?: return@withContext null,
                            page = request.page
                        )
                    }
                }

                if (roomLoadJob === activeJob && result != null) {
                    applyRoomLoadResult(request, result)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logger.error(e) { "Failed to load live rooms" }
                if (roomLoadJob === activeJob) {
                    "加载直播间列表失败: ${e.message}".toast(BVApp.context)
                }
            } finally {
                if (roomLoadJob === activeJob) {
                    loading = false
                    roomLoadJob = null
                }
            }
        }
        roomLoadJob = loadJob
        loadJob.start()
    }

    private suspend fun loadRecommendRooms(page: Int): RoomLoadResult {
        val response = liveRepository.getLiveRecommendList(page = page)
        if (response.code == 0) {
            return RoomLoadResult(
                rooms = response.data?.recommendRoomList?.map { it.toLiveRoomItem() } ?: emptyList(),
                canAdvancePage = true
            )
        } else {
            error("加载推荐直播失败: ${response.message}")
        }
    }

    private suspend fun loadFollowingRooms(page: Int): RoomLoadResult {
        val response = liveRepository.getLiveFollowingList(page = page, pageSize = 10)
        if (response.code == 0) {
            val data = response.data
            return RoomLoadResult(
                rooms = data?.list?.map { it.toLiveRoomItem() } ?: emptyList(),
                canAdvancePage = data != null && page < data.totalPage
            )
        } else {
            error("加载关注直播失败: ${response.message}")
        }
    }

    private suspend fun loadAreaRooms(area: LiveAreaItem, page: Int): RoomLoadResult {
        val response = liveRepository.getLiveRoomList(
            parentAreaId = area.parentId,
            areaId = area.id,
            page = page,
            pageSize = 30
        )
        if (response.code == 0) {
            return RoomLoadResult(
                rooms = response.data.list,
                canAdvancePage = true
            )
        } else {
            error("加载直播间列表失败: ${response.message}")
        }
    }

    /**
     * 刷新当前分区的直播间列表
     */
    fun refresh() {
        loadRooms(refresh = true)
    }

    /**
     * 加载更多直播间
     */
    fun loadMore() {
        if (hasMore && !loading) {
            loadRooms(refresh = false)
        }
    }

    private fun buildRoomLoadRequest(refresh: Boolean): RoomLoadRequest? {
        if (!areaGroupsLoadCompleted) return null
        val page = if (refresh) 1 else currentPage
        return when (currentMode) {
            LiveMode.RECOMMEND -> RoomLoadRequest(mode = LiveMode.RECOMMEND, page = page)
            LiveMode.FOLLOWING -> RoomLoadRequest(mode = LiveMode.FOLLOWING, page = page)
            LiveMode.AREA -> currentSubArea?.let {
                RoomLoadRequest(mode = LiveMode.AREA, area = it, page = page)
            }
        }
    }

    private fun applyRoomLoadResult(request: RoomLoadRequest, result: RoomLoadResult) {
        val existingIds = roomList.map { it.roomId }.toHashSet()
        val filteredRooms = result.rooms.filter { it.roomId !in existingIds }
        roomList.addAll(filteredRooms)
        hasMore = filteredRooms.isNotEmpty() && result.canAdvancePage
        if (hasMore) {
            currentPage = request.page + 1
        }

        when (request.mode) {
            LiveMode.RECOMMEND -> logger.info { "Loaded ${result.rooms.size} recommend rooms, page ${request.page}" }
            LiveMode.FOLLOWING -> logger.info { "Loaded ${result.rooms.size} following rooms, page ${request.page}" }
            LiveMode.AREA -> logger.info { "Loaded ${result.rooms.size} rooms for area ${request.area?.name}, page ${request.page}" }
        }
    }
}

private data class RoomLoadRequest(
    val mode: LiveMode,
    val area: LiveAreaItem? = null,
    val page: Int
)

private data class RoomLoadResult(
    val rooms: List<LiveRoomItem>,
    val canAdvancePage: Boolean
)
