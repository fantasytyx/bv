package dev.aaa1115910.bv.viewmodel.user

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.aaa1115910.biliapi.entity.FavoriteResourceType
import dev.aaa1115910.biliapi.entity.ugc.toSmartDate
import dev.aaa1115910.biliapi.entity.user.DynamicItem
import dev.aaa1115910.biliapi.entity.user.SpaceArticle
import dev.aaa1115910.biliapi.entity.user.SpaceCollection
import dev.aaa1115910.biliapi.entity.user.SpaceSeries
import dev.aaa1115910.biliapi.entity.user.SpaceVideo
import dev.aaa1115910.biliapi.entity.user.SpaceVideoPage
import dev.aaa1115910.biliapi.repositories.FavoriteRepository
import dev.aaa1115910.biliapi.repositories.UserRepository
import dev.aaa1115910.bv.entity.carddata.VideoCardData
import dev.aaa1115910.bv.util.Prefs
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.annotation.KoinViewModel

@KoinViewModel
class UserSpaceViewModel(
    private val userRepository: UserRepository,
    private val favoriteRepository: FavoriteRepository
) : ViewModel() {
    companion object {
        private val logger = KotlinLogging.logger { }
    }

    var upFace by mutableStateOf("")
    var upName by mutableStateOf("")
    var sign by mutableStateOf("") // 个性签名
    var fans by mutableIntStateOf(0) // 粉丝数
    var friend by mutableIntStateOf(0) // 关注数
    var likeNum by mutableIntStateOf(0) // 获赞数
    var currentLevel by mutableIntStateOf(0) // 当前会员等级
    var spacestaText by mutableStateOf("") // 账号状态提示语，userInfo.card.spacesta -2封禁 0正常
    var upMid by mutableLongStateOf(0L)

    val tvSpaceVideos = mutableStateListOf<VideoCardData>()
    val spaceVideos = mutableStateListOf<SpaceVideo>()
    val tvSpaceArticles = mutableStateListOf<SpaceArticle>()
    val tvSpaceDynamics = mutableStateListOf<DynamicItem>()
    val tvSpaceCollections = mutableStateListOf<SpaceCollection>()
    val tvSpaceSeries = mutableStateListOf<SpaceSeries>()
    val tvSpaceArchives = mutableStateListOf<VideoCardData>()

    val videos = PagingState()
    val articles = PagingState()
    val dynamics = PagingState()
    val collections = PagingState()
    val archives = PagingState()

    var selectedCollection by mutableStateOf<SpaceCollection?>(null)
        private set
    var selectedSeries by mutableStateOf<SpaceSeries?>(null)
        private set

    var collectionSubscribed by mutableStateOf(false)
        private set
    var collectionSubscriptionLoading by mutableStateOf(false)
        private set

    private var collectionSubscriptionUpdating = false
    private var videoPage = SpaceVideoPage()

    fun initialize(mid: Long, name: String, face: String) {
        if (upMid == mid && mid > 0) return

        upMid = mid
        upName = name
        upFace = face

        videoPage = SpaceVideoPage()
        videos.reset()
        tvSpaceVideos.clear()
        spaceVideos.clear()

        articles.reset()
        tvSpaceArticles.clear()

        dynamics.reset()
        tvSpaceDynamics.clear()

        collections.reset()
        tvSpaceCollections.clear()
        tvSpaceSeries.clear()

        closeArchives()
        update()
    }

    fun update() {
        if (upMid <= 0) return
        viewModelScope.launch {
            videos.load(
                fetch = { _, _ ->
                    val data = userRepository.getSpaceVideos(
                        mid = upMid,
                        page = videoPage,
                        preferApiType = Prefs.apiType
                    )
                    videoPage = data.page
                    PageResult(data.videos, hasMore = data.page.hasNext)
                },
                append = { page ->
                    spaceVideos.addAll(page)
                    tvSpaceVideos.addAll(page.map { it.toVideoCardData() })
                }
            )
        }
    }

    /** 首次进入 tab 或滚到末尾时调用；已加载完时是空操作 */
    fun load(tab: UserSpaceTab) {
        when (tab) {
            UserSpaceTab.Videos -> update()
            UserSpaceTab.Articles -> loadArticles()
            UserSpaceTab.Dynamics -> loadDynamics()
            UserSpaceTab.Collections -> loadCollections()
        }
    }

    fun openCollection(collection: SpaceCollection) {
        closeArchives()
        selectedCollection = collection
        loadArchives()
    }

    fun openSeries(series: SpaceSeries) {
        closeArchives()
        selectedSeries = series
        loadArchives()
    }

    fun closeArchives() {
        archives.reset()
        tvSpaceArchives.clear()
        selectedCollection = null
        selectedSeries = null
        clearCollectionSubscriptionState()
    }

    fun loadArchives() {
        val collection = selectedCollection
        val series = selectedSeries
        if (upMid <= 0 || (collection == null && series == null)) return
        val firstPage = archives.currentPage == 1
        viewModelScope.launch {
            val success = archives.load(
                fetch = { page, _ ->
                    val data = if (collection != null) {
                        userRepository.getSeasonArchives(upMid, collection.seasonId, page)
                    } else {
                        userRepository.getSeriesArchives(upMid, series!!.seriesId, page)
                    }
                    val knownIds = tvSpaceArchives.mapTo(mutableSetOf()) { it.avid.toString() }
                    val newVideos = data.videos.newItems(knownIds) { it.aid.toString() }
                    PageResult(newVideos.map { it.toVideoCardData() }, hasMore = data.hasMore, nextPage = page + 1)
                },
                append = tvSpaceArchives::addAll
            )
            // 首屏稿件到位后再校准订阅状态：season_fav 需要一条合集内稿件
            if (success && firstPage && collection != null) {
                syncCollectionSubscriptionState(collection.seasonId)
            }
        }
    }

    /**
     * 订阅或取消订阅当前合集
     *
     * 合集订阅走 `/x/v3/fav/season/fav`，没有收藏夹选择，写入成功后本地直接翻转状态。
     */
    fun toggleCollectionSubscription() {
        val seasonId = selectedCollection?.seasonId ?: return
        if (seasonId <= 0 || collectionSubscriptionUpdating) return
        val target = !collectionSubscribed
        collectionSubscribed = target
        collectionSubscriptionUpdating = true
        viewModelScope.launch {
            val success = runCatching {
                favoriteRepository.subscribeSeason(seasonId = seasonId, subscribe = target)
            }.onFailure { exception ->
                if (exception is CancellationException) throw exception
            }.isSuccess
            collectionSubscriptionUpdating = false
            if (!success) {
                logger.error { "Update collection subscription failed: season=$seasonId" }
                if (selectedCollection?.seasonId == seasonId) collectionSubscribed = !target
            }
        }
    }

    private fun loadArticles() {
        if (upMid <= 0) return
        viewModelScope.launch {
            articles.load(
                fetch = { page, offset ->
                    val data = userRepository.getSpaceArticles(upMid, page, offset)
                    val knownIds = tvSpaceArticles.mapTo(mutableSetOf()) { it.id.toString() }
                    PageResult(
                        items = data.articles.newItems(knownIds) { it.id.toString() },
                        hasMore = data.hasMore,
                        nextPage = page + 1,
                        nextOffset = data.nextOffset
                    )
                },
                append = tvSpaceArticles::addAll
            )
        }
    }

    private fun loadDynamics() {
        if (upMid <= 0) return
        viewModelScope.launch {
            dynamics.load(
                fetch = { _, offset ->
                    val data = userRepository.getDynamicsByUp(upMid, offset)
                    val knownIds = tvSpaceDynamics.mapNotNullTo(mutableSetOf()) { it.id }
                    PageResult(
                        items = data.dynamics.newItems(knownIds) { it.id },
                        hasMore = data.hasMore,
                        nextOffset = data.historyOffset
                    )
                },
                append = tvSpaceDynamics::addAll
            )
        }
    }

    private data class CollectionsPage(
        val seasons: List<SpaceCollection>,
        val series: List<SpaceSeries>
    )

    private fun loadCollections() {
        if (upMid <= 0) return
        viewModelScope.launch {
            collections.load(
                fetch = { page, _ ->
                    val data = userRepository.getSpaceSeasonSeries(upMid, page)
                    val seasonIds = tvSpaceCollections.mapTo(mutableSetOf()) { it.seasonId.toString() }
                    val seriesIds = tvSpaceSeries.mapTo(mutableSetOf()) { it.seriesId.toString() }
                    val newSeasons = data.seasons.newItems(seasonIds) { it.seasonId.toString() }
                    val newSeries = data.series.newItems(seriesIds) { it.seriesId.toString() }
                    PageResult(
                        items = listOf(CollectionsPage(newSeasons, newSeries)),
                        // 一整页都是重复内容说明到底，服务端此时仍会回 hasMore
                        hasMore = data.hasMore && (newSeasons.isNotEmpty() || newSeries.isNotEmpty()),
                        nextPage = page + 1
                    )
                },
                append = { pages ->
                    pages.forEach { page ->
                        tvSpaceCollections.addAll(page.seasons)
                        tvSpaceSeries.addAll(page.series)
                    }
                }
            )
        }
    }

    private fun syncCollectionSubscriptionState(seasonId: Long) {
        if (!Prefs.isLogin || seasonId <= 0) {
            clearCollectionSubscriptionState()
            return
        }
        collectionSubscriptionLoading = true
        viewModelScope.launch {
            try {
                val subscribed = withContext(Dispatchers.IO) {
                    // 合集里已有稿件时优先用稿件互动状态里的 season_fav，失败再退回收藏夹反查
                    val aid = tvSpaceArchives.firstOrNull()?.avid ?: 0L
                    (aid > 0 && favoriteRepository.checkSeasonSubscribed(aid)) ||
                        favoriteRepository.getAllFavoriteFolderMetadataList(
                            mid = Prefs.uid,
                            resourceType = FavoriteResourceType.VideoCollection,
                            rid = seasonId,
                            preferApiType = Prefs.apiType
                        ).any { it.videoInThisFav }
                }
                if (selectedCollection?.seasonId == seasonId) collectionSubscribed = subscribed
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                logger.error(exception) { "Load collection subscription state failed" }
                if (selectedCollection?.seasonId == seasonId) clearCollectionSubscriptionState()
            } finally {
                if (selectedCollection?.seasonId == seasonId) collectionSubscriptionLoading = false
            }
        }
    }

    private fun clearCollectionSubscriptionState() {
        collectionSubscribed = false
        collectionSubscriptionLoading = false
    }

    private fun SpaceVideo.toVideoCardData() = VideoCardData(
        avid = aid,
        title = title,
        cover = cover,
        play = play,
        danmaku = danmaku,
        upName = author,
        upId = authorId,
        time = duration * 1000L,
        pubTime = publishDate.time.toSmartDate(),
        isInteractive = isInteractive,
        isChargingArc = isChargingArc,
        badgeText = chargingArcBadge
    )
}
