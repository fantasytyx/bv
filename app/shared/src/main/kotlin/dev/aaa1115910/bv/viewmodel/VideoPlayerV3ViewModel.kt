package dev.aaa1115910.bv.viewmodel

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.aaa1115910.bv.player.danmaku.DanmakuView
import dev.aaa1115910.bv.player.danmaku.model.Danmaku
import dev.aaa1115910.biliapi.entity.ApiType
import dev.aaa1115910.biliapi.entity.PlayData
import dev.aaa1115910.biliapi.entity.danmaku.DanmakuMaskSegment
import dev.aaa1115910.biliapi.entity.video.InteractiveNode
import dev.aaa1115910.biliapi.http.entity.video.ClipInfo
import dev.aaa1115910.biliapi.entity.video.HeartbeatVideoType
import dev.aaa1115910.biliapi.entity.video.Subtitle
import dev.aaa1115910.biliapi.entity.video.SubtitleAiStatus
import dev.aaa1115910.biliapi.entity.video.SubtitleAiType
import dev.aaa1115910.biliapi.entity.video.SubtitleType
import dev.aaa1115910.biliapi.entity.video.VideoShot
import dev.aaa1115910.biliapi.http.BiliHttpApi
import dev.aaa1115910.biliapi.http.BiliLiveHttpApi
import dev.aaa1115910.biliapi.http.entity.VVoucherException
import dev.aaa1115910.biliapi.http.entity.video.GaiaVgateRegisterData
import dev.aaa1115910.biliapi.http.entity.live.DanmakuEvent
import dev.aaa1115910.biliapi.http.entity.live.OnlineRankCountEvent
import dev.aaa1115910.biliapi.http.entity.live.WatchedChangeEvent
import dev.aaa1115910.biliapi.http.entity.live.PopularityChangeEvent
import dev.aaa1115910.biliapi.repositories.VideoPlayRepository
import dev.aaa1115910.biliapi.websocket.LiveDataWebSocket
import dev.aaa1115910.bilisubtitle.SubtitleParser
import dev.aaa1115910.bilisubtitle.entity.SubtitleItem
import dev.aaa1115910.bv.BVApp
import dev.aaa1115910.bv.entity.proxy.ProxyArea
import dev.aaa1115910.bv.player.AbstractVideoPlayer
import dev.aaa1115910.bv.player.entity.Audio
import dev.aaa1115910.bv.player.entity.DanmakuType
import dev.aaa1115910.bv.player.entity.DefaultSubtitle
import dev.aaa1115910.bv.player.entity.LiveCodec
import dev.aaa1115910.bv.player.entity.LiveStreamLine
import dev.aaa1115910.bv.player.entity.PlayMode
import dev.aaa1115910.bv.player.entity.PlayerDefaultStartPosition
import dev.aaa1115910.bv.player.entity.PortraitVideoFixMode
import dev.aaa1115910.bv.player.entity.RequestState
import dev.aaa1115910.bv.player.entity.Resolution
import dev.aaa1115910.bv.player.entity.VideoAspectRatio
import dev.aaa1115910.bv.player.entity.VideoCodec
import dev.aaa1115910.bv.player.entity.VideoListInteractiveNode
import dev.aaa1115910.bv.player.entity.VideoListItemData
import dev.aaa1115910.bv.player.entity.VideoRotation
import dev.aaa1115910.bv.repository.VideoInfoRepository
import dev.aaa1115910.bv.util.Prefs
import dev.aaa1115910.bv.util.VVoucherAlreadyAttemptedException
import dev.aaa1115910.bv.util.fError
import dev.aaa1115910.bv.util.fException
import dev.aaa1115910.bv.util.fInfo
import dev.aaa1115910.bv.util.fWarn
import dev.aaa1115910.bv.util.reserveFreshVVoucher
import dev.aaa1115910.bv.util.LiveStreamUrlFetcher
import dev.aaa1115910.bv.util.fDebug
import dev.aaa1115910.bv.util.swapList
import dev.aaa1115910.bv.util.swapListWithMainContext
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.koin.android.annotation.KoinViewModel
import dev.aaa1115910.biliapi.repositories.AuthRepository
import dev.aaa1115910.bv.player.entity.NextVideoStrategy
import java.net.URI

@KoinViewModel
class VideoPlayerV3ViewModel(
    private val videoInfoRepository: VideoInfoRepository,
    private val videoPlayRepository: VideoPlayRepository,
    private val authRepository: AuthRepository,
) : ViewModel() {
    private val logger = KotlinLogging.logger { }

    private var videoPlayerState: AbstractVideoPlayer? by mutableStateOf(null)
    var videoPlayer: AbstractVideoPlayer?
        get() = videoPlayerState
        set(value) {
            value?.onSeek = ::onVideoSeeked
            value?.onDecoderError = ::fallbackToLowerQuality
            videoPlayerState = value
        }
    var danmakuView: DanmakuView? by mutableStateOf(null)
    var show by mutableStateOf(false)

    override fun onCleared() {
        super.onCleared()
        logger.fInfo { "VideoPlayerV3ViewModel onCleared" }
        releasePlayerResources("onCleared")
    }

    /**
     * 释放播放器和弹幕相关资源。幂等，可多次安全调用。
     * 由 Activity.onDestroy（立即释放重资源）和 ViewModel.onCleared 共同调用。
     */
    fun releasePlayerResources(caller: String = "unknown") {
        logger.fInfo { "releasePlayerResources called by $caller" }

        stopDanmakuSegmentLoading()

        // 清理直播重连任务
        liveRetryJob?.cancel()
        liveRetryJob = null

        // 清理直播URL刷新任务
        liveUrlRefreshJob?.cancel()
        liveUrlRefreshJob = null

        // 清理点播CDN URL刷新任务
        cancelPlayUrlAutoRefresh(caller)

        // 清理直播弹幕资源
        stopLiveDanmaku()

        try {
            videoPlayer?.release()
            videoPlayer = null
        } catch (e: Exception) {
            logger.fError { "Error releasing video player: ${e.message}" }
        }

        try {
            danmakuView?.release()
            danmakuView = null
            danmakuMasks.clear()
        } catch (e: Exception) {
            logger.fError { "Error releasing danmaku player: ${e.message}" }
        }

        // 清除可能未被GC回收的资源
        currentSubtitleData.clear()
    }

    var loadState by mutableStateOf(RequestState.Ready)
    var errorMessage by mutableStateOf("")

    private var playData: PlayData? by mutableStateOf(null)
    val danmakuMasks = mutableStateListOf<DanmakuMaskSegment>()
    var videoShot: VideoShot? by mutableStateOf(null)
    var clipInfoList: List<ClipInfo> by mutableStateOf(emptyList())

    var availableQuality = mutableStateListOf<Resolution>()
    var availableVideoCodec = mutableStateListOf<VideoCodec>()
    var availableSubtitle = mutableStateListOf<Subtitle>()
    var availableAudio = mutableStateListOf<Audio>()
    val availableVideoList get() = videoInfoRepository.videoList
    val preloadedVideoList get() = videoInfoRepository.preloadedVideoList
    val relatedVideos get() =  videoInfoRepository.relatedVideos
    val videoDescription get() = videoInfoRepository.description
    val videoTags get() = videoInfoRepository.tags
    val isInteractivePlayback get() = videoInfoRepository.interactivePlaybackContext != null
    val interactiveOptions get() = availableVideoList.filterIsInstance<VideoListInteractiveNode>()
    var showInteractiveOptionDialog by mutableStateOf(false)
    var interactiveOptionsFromQuestions by mutableStateOf(false)
    private var pendingInteractiveOptionDialogRequest by mutableStateOf(false)

    fun resolveLastPreloadedVideoIndex(avid: Long = currentAid): Int {
        return videoInfoRepository.resolveLastPreloadedVideoIndex(avid)
    }

    var currentVideoHeight by mutableIntStateOf(0)
    var currentVideoWidth by mutableIntStateOf(0)

    var currentQuality by mutableStateOf(Prefs.defaultQuality)
    var currentVideoCodec by mutableStateOf(Prefs.defaultVideoCodec)
    var currentPlaySpeed by mutableFloatStateOf(Prefs.currentPlaySpeed)
    var currentVideoAspectRatio by mutableStateOf(VideoAspectRatio.Default)
    var currentVideoRotation by mutableStateOf(VideoRotation.Original)
    var currentAudio by mutableStateOf(Prefs.defaultAudio)
    var currentDanmakuScale by mutableFloatStateOf(Prefs.defaultDanmakuScale)
    var currentDanmakuOpacity by mutableFloatStateOf(Prefs.defaultDanmakuOpacity)
    var currentDanmakuEnabled by mutableStateOf(Prefs.defaultDanmakuEnabled)
    val currentDanmakuTypes = mutableStateListOf<DanmakuType>().apply {
        addAll(Prefs.defaultDanmakuTypes)
    }
    var currentDanmakuArea by mutableFloatStateOf(Prefs.defaultDanmakuArea)
    var currentDanmakuMask by mutableStateOf(Prefs.defaultDanmakuMask)
    var currentDanmakuRollingDurationFactor by mutableFloatStateOf(Prefs.defaultDanmakuRollingDurationFactor)
    var currentDanmakuFilterLevel by mutableIntStateOf(Prefs.defaultDanmakuFilterLevel)
    var currentLiveDanmakuFilterLevel by mutableIntStateOf(Prefs.defaultLiveDanmakuFilterLevel)
    var currentSubtitleId by mutableLongStateOf(-1L)
    var currentSubtitleData = mutableStateListOf<SubtitleItem>()
    var currentSubtitleType by mutableStateOf(SubtitleType.CC)
    var currentSubtitleFontSize by mutableStateOf(Prefs.defaultSubtitleFontSize)
    var currentSubtitleBackgroundOpacity by mutableFloatStateOf(Prefs.defaultSubtitleBackgroundOpacity)
    var currentSubtitleBottomPadding by mutableStateOf(Prefs.defaultSubtitleBottomPadding)

    var currentPlayMode by mutableStateOf(Prefs.defaultPlayMode)

    var title by mutableStateOf("")
    var partTitle by mutableStateOf("")
    var lastPlayed by mutableIntStateOf(0)
    var fromSeason by mutableStateOf(false)
    var subType by mutableIntStateOf(0)
    var epid by mutableIntStateOf(0)
    var seasonId by mutableIntStateOf(0)
    var isVerticalVideo by mutableStateOf(false)
    var proxyArea by mutableStateOf(ProxyArea.MainLand)
    var play by mutableLongStateOf(0)
    var danmaku by mutableStateOf(0)
    var like by mutableStateOf(0)

    // 直播相关属性
    var isLive by mutableStateOf(false)
    var liveRoomId by mutableIntStateOf(0)
    var liveStreamUrl by mutableStateOf("")

    // 直播画质管理
    var availableLiveQualities = mutableStateListOf<Pair<Int, String>>() // qn -> description
    var currentLiveQn by mutableIntStateOf(0)
    var currentLiveQualityDescription by mutableStateOf("")
    private var liveQnDescMap: Map<Int, String> = emptyMap()

    // 直播编码管理
    var currentLiveCodec by mutableStateOf(Prefs.defaultLiveCodec)

    // 直播线路管理
    var availableLiveLines = mutableStateListOf<LiveStreamLine>()
    var currentLiveLineIndex by mutableIntStateOf(0)
    private var preferredLiveLineIndex: Int? = null

    // 直播开播时间
    var liveTime by mutableLongStateOf(0L)

    // 直播流URL过期时间（毫秒时间戳）
    var liveStreamExpiresAt by mutableLongStateOf(0L)

    // 直播自动重连
    private var liveRetryJob: Job? = null

    // 直播URL主动刷新
    private var liveUrlRefreshJob: Job? = null
    private var consecutiveRefreshFailures = 0

    // 点播CDN URL自动刷新（修复CDN有效期2h导致长视频无法播放的问题）
    private var playUrlAutoRefreshJob: Job? = null
    private var playUrlAutoRefreshToken: Int = 0
    private var previewTipJob: Job? = null

    /** 触发 Geetest 验证时的播放请求快照，验证通过后按快照重试原请求 */
    private data class GeetestRetryRequest(
        val avid: Long,
        val cid: Long,
        val epid: Int,
        val initialSeekPositionMs: Long?,
        val proxyArea: ProxyArea,
    )

    companion object {
        // 提前刷新的时间（毫秒），默认60秒
        private const val REFRESH_BEFORE_EXPIRY_MS = 60_000L
        // 最小刷新间隔（毫秒），防止频繁刷新
        private const val MIN_REFRESH_INTERVAL_MS = 30_000L
        // 刷新失败后的重试间隔（毫秒）
        private const val REFRESH_RETRY_INTERVAL_MS = 10_000L
        // 最大连续刷新失败次数
        private const val MAX_REFRESH_FAILURES = 3

        // 点播CDN URL自动刷新常量
        // 在CDN URL过期前提前刷新的时间
        private const val PLAYURL_AUTO_REFRESH_LEAD_MS = 60_000L
        // 当无法从URL解析过期时间时，视频时长超过此值才启用回退刷新
        private const val PLAYURL_AUTO_REFRESH_FALLBACK_MIN_DURATION_MS = 60 * 60_000L
        // 回退刷新延迟（无法解析deadline时使用）
        private const val PLAYURL_AUTO_REFRESH_FALLBACK_DELAY_MS = 100 * 60_000L
        // 两次刷新之间的最小间隔
        private const val PLAYURL_AUTO_REFRESH_MIN_RELOAD_INTERVAL_MS = 30_000L

        private const val DANMAKU_SEGMENT_DURATION_MS = 6 * 60 * 1000L
        private const val DANMAKU_SEGMENT_POLL_INTERVAL_MS = 15_000L
    }

    // 直播人气值与在线人数
    var watchedText by mutableStateOf("")   // "2.5万人气" (人气：POPULARITY_CHANGE 播放量：WATCHED_CHANGE)
    var liveOnlineCount by mutableStateOf("")      // "4333人在线" (ONLINE_RANK_COUNT)

    // 播放量和在线人数更新频率限制（至少间隔 5 秒）
    private var lastWatchedUpdateTime = 0L
    private var lastOnlineCountUpdateTime = 0L

    // 直播弹幕管理
    private var liveWebSocket: Job? = null
    private var liveWebSocketInner: Job? = null
    private var liveDanmakuConsumer: Job? = null
    private var liveDanmakuChannel: Channel<DanmakuEvent>? = null
    private val liveDanmakuBuffer = mutableListOf<Danmaku>()
    private var liveDanmakuFlushJob: Job? = null

    // 点播弹幕管理
    private var danmakuSegmentWatchJob: Job? = null
    private var currentDanmakuSegmentIndex = -1

    // 风控 Geetest 验证状态
    var showGeetestDialog by mutableStateOf(false)
    var geetestGt by mutableStateOf("")
    var geetestChallenge by mutableStateOf("")
    private var pendingGaiaToken: String? = null
    private var pendingRetryRequest: GeetestRetryRequest? = null
    // v_voucher 是一次性凭证，记录已处理过的 voucher，避免重复注册弹窗造成死循环
    private val geetestVoucherRegisterMutex = Mutex()
    private val attemptedGeetestVVouchers = mutableSetOf<String>()
    private val loadedDanmakuSegmentCounts = mutableMapOf<Int, Int>()
    var currentLoadedDanmakuTotal by mutableIntStateOf(0)

    var coin by mutableStateOf(0)
    var favorite by mutableStateOf(0)
    var upName by mutableStateOf("")
    var upFace by mutableStateOf("")
    var pubTime by mutableStateOf("")
    var upId by mutableLongStateOf(0L)
    var showDanmaku by mutableStateOf(Prefs.showDanmaku)
    var showRelatedVideos by mutableStateOf(false)
    var showRelatedRooms by mutableStateOf(false)
    val preloadedLiveRoomList get() = videoInfoRepository.preloadedLiveRoomList
    var lastPreloadedRoomIndex
        get() = videoInfoRepository.lastPreloadedRoomIndex
        set(value) { videoInfoRepository.lastPreloadedRoomIndex = value }
    var isFollowingUp by mutableStateOf(false)

    var needPay by mutableStateOf(false)
    var showPreviewTip by mutableStateOf(false)

    var logs by mutableStateOf("")
    var lastChangedLog by mutableLongStateOf(System.currentTimeMillis())
    var showBuffering by mutableStateOf(false)

    var playerIconIdle by mutableStateOf("")
    var playerIconMoving by mutableStateOf("")

    var lastVideoHost by mutableStateOf("")
    var lastAudioHost by mutableStateOf("")

    var currentAid = 0L
    var currentCid by mutableLongStateOf(0L)
    var currentInteractiveNodeId by mutableLongStateOf(0L)
    var currentInteractiveEdgeId by mutableLongStateOf(0L)
    private var currentEpid = 0
    private var pendingInitialSeekPositionMs: Long? = null

    private suspend fun ensureDanmakuView() {
        // DanmakuView is created by the UI layer, nothing to do here.
        // Kept for call-site compatibility.
        logger.fInfo { "ensureDanmakuView: current=$danmakuView" }
    }

    private fun stopDanmakuSegmentLoading() {
        danmakuSegmentWatchJob?.cancel()
        danmakuSegmentWatchJob = null
        currentDanmakuSegmentIndex = -1
        loadedDanmakuSegmentCounts.clear()
        currentLoadedDanmakuTotal = 0
        danmakuView?.clearDanmakus()
    }

    private fun getDanmakuSegmentIndex(positionMs: Long): Int {
        return (positionMs / DANMAKU_SEGMENT_DURATION_MS).toInt() + 1
    }

    private suspend fun loadDanmakuSegment(cid: Long, positionMs: Long, force: Boolean = false) {
        val safePosition = positionMs.coerceAtLeast(0L)
        val segmentIndex = getDanmakuSegmentIndex(safePosition + DANMAKU_SEGMENT_POLL_INTERVAL_MS)
        if (!force && loadedDanmakuSegmentCounts.containsKey(segmentIndex)) {
            currentDanmakuSegmentIndex = segmentIndex
            return
        }

        loadedDanmakuSegmentCounts[segmentIndex] = 0
        var loadedCount = 0
        runCatching {
            val segmentData = BiliHttpApi.getDanmakuSeg(
                cid = cid,
                avid = currentAid,
                segmentIndex = segmentIndex,
                sessData = Prefs.sessData
            )

            val convertedDanmaku = segmentData.map {
                Danmaku(
                    dmid = it.dmid,
                    positionMs = (it.time * 1000).toInt(),
                    text = it.text,
                    type = it.type,
                    textSize = it.size,
                    color = 0xFF000000.toInt() or (it.color and 0xFFFFFF),
                    level = it.level
                )
            }.sortedWith(compareBy({ it.positionMs }, { it.level }))
            loadedCount = convertedDanmaku.size

            val shouldResume = withContext(Dispatchers.Main) {
                videoPlayer?.isPlaying == true
            }

            withContext(Dispatchers.Main) {
                danmakuView?.appendDanmakus(convertedDanmaku, maxItems = 0, alreadySorted = true)
            }

            currentDanmakuSegmentIndex = segmentIndex
            loadedDanmakuSegmentCounts[segmentIndex] = loadedCount
            currentLoadedDanmakuTotal = loadedDanmakuSegmentCounts.values.sum()
        }.onFailure {
            loadedDanmakuSegmentCounts.remove(segmentIndex)
            addLogs("加载第 $segmentIndex 块弹幕失败：${it.localizedMessage}")
            logger.fWarn { "Load danmaku segment failed: cid=$cid, segment=$segmentIndex, error=${it.stackTraceToString()}" }
        }.onSuccess {
            // 已加载 x 块 x 条弹幕（新追加的第 y 块有 z 条）
            addLogs("已加载 ${loadedDanmakuSegmentCounts.size} 块共 $currentLoadedDanmakuTotal 条弹幕（6分钟/块）", replaceIfContains = "已加载")
            logger.fInfo { "Load danmaku segment success, cid=$cid, segment=$segmentIndex, size=$loadedCount, total=$currentLoadedDanmakuTotal" }
        }
    }

    private fun startDanmakuSegmentWatcher(cid: Long) {
        danmakuSegmentWatchJob?.cancel()
        danmakuSegmentWatchJob = viewModelScope.launch(Dispatchers.Default) {
            while (isActive && currentCid == cid && !isLive) {
                val position = withContext(Dispatchers.Main) {
                    videoPlayer?.currentPosition?.coerceAtLeast(0L) ?: 0L
                }
                loadDanmakuSegment(cid, position)
                delay(DANMAKU_SEGMENT_POLL_INTERVAL_MS)
            }
        }
    }

    fun onVideoSeeked(positionMs: Long) {
        if (isLive || currentCid <= 0L) return

        viewModelScope.launch(Dispatchers.Default) {
            loadDanmakuSegment(currentCid, positionMs)
        }
    }

    fun loadPlayUrl(
        avid: Long,
        cid: Long,
        epid: Int? = null,
        seasonId: Int? = null,
        continuePlayNext: Boolean = false,
        initialSeekPositionMs: Long? = null,
    ) {
        showInteractiveOptionDialog = false
        pendingInitialSeekPositionMs = initialSeekPositionMs
        if (continuePlayNext) {
            lastPlayed = 0
        }
        currentAid = avid
        currentCid = cid
        currentEpid = epid ?: 0
        syncCurrentInteractivePointersFromList()
        if (!isInteractivePlayback && videoInfoRepository.videoList.none { it is VideoListInteractiveNode }) {
            currentInteractiveNodeId = 0L
            currentInteractiveEdgeId = 0L
        }
        epid?.let { this.epid = it }
        seasonId?.let { this.seasonId = it }
        if (fromSeason && currentPlayMode in listOf(PlayMode.ListOrder, PlayMode.ListOrderReverse, PlayMode.RelatedVideo)) {
            currentPlayMode = PlayMode.PartAndEpisode
        }
        if (!fromSeason) {
            if (currentPlayMode in listOf(PlayMode.ListOrder, PlayMode.ListOrderReverse) && preloadedVideoList.isEmpty()) {
                currentPlayMode = PlayMode.PartAndEpisode
            }
            if (currentPlayMode == PlayMode.RelatedVideo && relatedVideos.isEmpty()) {
                currentPlayMode = PlayMode.PartAndEpisode
            }
        }
        cancelPlayUrlAutoRefresh("new_media")
        viewModelScope.launch(Dispatchers.Default) {
            // addLogs("加载视频中")
            ensureDanmakuView()
            // addLogs("弹幕引擎已就绪")
            if (epid != null || seasonId != null) {
                addLogs("avid:$avid，cid:$cid，epid:$epid，seasonId:$seasonId")
            } else {
                addLogs("avid:$avid，cid:$cid")
            }

            val lastPlayEnabledSubtitle = currentSubtitleId != -1L
            val lastSubtitleLang = availableSubtitle.find { it.id == currentSubtitleId }?.langDoc
            if (lastPlayEnabledSubtitle) {
                logger.info { "Subtitle is enabled, next video will enable subtitle automatic" }
            }

            updateSubtitle()
            loadPlayUrl(
                avid,
                cid,
                epid ?: 0,
                preferApi = Prefs.apiType,
                proxyArea = proxyArea,
                initialSeekPositionMs = initialSeekPositionMs,
            )
            if (isInteractivePlayback && (!interactiveOptionsFromQuestions || interactiveOptions.isEmpty())) {
                refreshInteractiveBranches(currentInteractiveEdgeId.takeIf { it > 0L })
            }
            // addLogs("加载弹幕中")
            loadDanmaku(cid)
            updateDanmakuMask()

            updateVideoShot()

            //如果是继续播放下一集，且之前开启了字幕，就自动加载之前选择的语言的字幕
            //否则根据默认字幕设置自动加载
            if (continuePlayNext && lastPlayEnabledSubtitle) {
                autoLoadSubtitle(preferLang = lastSubtitleLang, ccOnly = false)
            } else if (!continuePlayNext) {
                autoLoadSubtitle(preferLang = null, ccOnly = true)
            }
        }
    }

    private suspend fun loadPlayUrl(
        avid: Long,
        cid: Long,
        epid: Int = 0,
        preferApi: ApiType = Prefs.apiType,
        proxyArea: ProxyArea = ProxyArea.MainLand,
        initialSeekPositionMs: Long? = null,
        tryLook: Boolean = false,
    ) {
        if (initialSeekPositionMs != null) {
            pendingInitialSeekPositionMs = initialSeekPositionMs
        }
        logger.fInfo { "Load play url: [av=$avid, cid=$cid, preferApi=$preferApi, proxyArea=$proxyArea, tryLook=$tryLook]" }
        withContext(Dispatchers.Main) { loadState = RequestState.Ready }
        logger.fInfo { "Set request state: ready" }
        logger.fInfo { "fromSeason: $fromSeason" }
        runCatching {
            val playData = if (fromSeason) {
                videoPlayRepository.getPgcPlayData(
                    aid = avid,
                    cid = cid,
                    epid = epid,
                    preferCodec = Prefs.defaultVideoCodec.toBiliApiCodeType(),
                    preferApiType = Prefs.apiType,
                    enableProxy = Prefs.enableProxy,
                    proxyArea = when (proxyArea) {
                        ProxyArea.MainLand -> ""
                        ProxyArea.HongKong -> "hk"
                        ProxyArea.TaiWan -> "tw"
                    },
                    tryLook = tryLook
                )
            } else {
                videoPlayRepository.getPlayData(
                    aid = avid,
                    cid = cid,
                    preferApiType = Prefs.apiType,
                    tryLook = tryLook
                )
            }

            //检查是否需要购买/充电，如果是试看则继续播放试看片段
            withContext(Dispatchers.Main) { needPay = playData.needPay }

            withContext(Dispatchers.Main) { this@VideoPlayerV3ViewModel.playData = playData }
            withContext(Dispatchers.Main) { this@VideoPlayerV3ViewModel.clipInfoList = playData.clipInfoList }
            logger.fInfo { "Load play data response success" }
            //logger.info { "Play data: $playData" }

            //读取清晰度
            val resolutionList = mutableListOf<Resolution>()
            playData.dashVideos.forEach {
                Resolution.fromCode(it.quality)?.let { resolution ->
                    if (!resolutionList.contains(resolution)) resolutionList.add(resolution)
                }
            }

            logger.fInfo { "Video available resolution: $resolutionList" }
            availableQuality.swapListWithMainContext(resolutionList)

            //读取音频
            val audioList = mutableListOf<Audio>()
            playData.dashAudios.forEach {
                Audio.fromCode(it.codecId)?.let { audio ->
                    if (!audioList.contains(audio)) audioList.add(audio)
                }
            }
            playData.dolby?.let {
                Audio.fromCode(it.codecId)?.let { audio ->
                    audioList.add(audio)
                }
            }
            playData.flac?.let {
                Audio.fromCode(it.codecId)?.let { audio ->
                    audioList.add(audio)
                }
            }

            logger.fInfo { "Video available audio: $audioList" }
            availableAudio.swapListWithMainContext(audioList)

            // 确定使用哪个默认分辨率
            val defaultQualityToUse = if (
                isVerticalVideo &&
                Prefs.portraitVideoFixMode == PortraitVideoFixMode.LimitResolution1080P &&
                Prefs.defaultQuality >= Resolution.R4K
            ) {
                // 如果是竖屏视频且用户设置了竖屏视频限制最高使用1080P
                Resolution.R1080P60
            } else {
                // 否则使用普通设置
                Prefs.defaultQuality
            }

            //先确认最终所选清晰度
            val existDefaultResolution =
                availableQuality.find { it == defaultQualityToUse } != null

            if (!existDefaultResolution) {
                val tempList = resolutionList.sortedByDescending { it.code }
                val currentQuality = tempList.firstOrNull { it.code < defaultQualityToUse.code }
                    ?: tempList.last()
                withContext(Dispatchers.Main) {
                    this@VideoPlayerV3ViewModel.currentQuality = currentQuality
                }
            } else {
                // 如果默认清晰度可用，直接使用
                withContext(Dispatchers.Main) { currentQuality = defaultQualityToUse }
            }

            //确认最终所选音质
            val existDefaultAudio = availableAudio.contains(Prefs.defaultAudio)
            if (!existDefaultAudio && availableAudio.isNotEmpty()) {
                val currentAudio = when {
                    Prefs.defaultAudio == Audio.ADolbyAtmos && availableAudio.contains(Audio.ADolbyAtmos) -> Audio.ADolbyAtmos
                    (Prefs.defaultAudio == Audio.ADolbyAtmos || Prefs.defaultAudio == Audio.AHiRes) && availableAudio.contains(Audio.AHiRes) -> Audio.AHiRes
                    availableAudio.contains(Audio.A192K) -> Audio.A192K
                    availableAudio.contains(Audio.A132K) -> Audio.A132K
                    availableAudio.contains(Audio.A64K) -> Audio.A64K
                    else -> availableAudio.first()
                }
                withContext(Dispatchers.Main) {
                    this@VideoPlayerV3ViewModel.currentAudio = currentAudio
                }
            }

            //再确认最终所选视频编码
            updateAvailableCodec()

            playQuality(qn = currentQuality.code, codec = currentVideoCodec)

            // 充电/付费视频预览状态提示
            if (playData.needPay) {
                startShowPreviewTipCountdown()
            }

        }.onFailure {
            if (it is VVoucherException) {
                logger.fWarn { "Risk control v_voucher detected: ${it.vVoucher}" }
                if (tryLook) {
                    // 试看兜底请求也被风控，直接失败，避免重复弹窗
                    addLogs("试看兜底也被风控拦截")
                    errorMessage = "风控拦截，试看请求也失败"
                    loadState = RequestState.Failed
                    return@onFailure
                }
                addLogs("触发风控，正在申请验证…")
                handleVVoucher(
                    vVoucher = it.vVoucher,
                    retryRequest = GeetestRetryRequest(
                        avid = avid,
                        cid = cid,
                        epid = epid,
                        initialSeekPositionMs = initialSeekPositionMs,
                        proxyArea = proxyArea,
                    )
                )
                return@onFailure
            }
            addLogs("加载视频地址失败：${it.localizedMessage}")
            errorMessage = it.localizedMessage ?: "Unknown error"
            loadState = RequestState.Failed
            logger.fException(it) { "Load video failed" }
        }.onSuccess {
            // addLogs("加载视频地址成功")
            loadState = RequestState.Success
            logger.fInfo { "Load play url success" }
        }
    }

    fun refreshInteractiveBranches(edgeId: Long? = null) {
        val interactiveContext = videoInfoRepository.interactivePlaybackContext ?: return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                BiliHttpApi.getInteractiveEdgeInfo(
                    bvid = interactiveContext.bvid,
                    graphVersion = interactiveContext.graphVersion,
                    edgeId = edgeId,
                ).getResponseData().let { response ->
                    val questionNodes = response.edges?.questions
                        ?.flatMap { question -> question.choices }
                        ?.mapIndexed { index, choice ->
                            InteractiveNode.fromChoice(choice, "选项 ${index + 1}")
                        }
                        .orEmpty()
                    Triple(
                        response.edgeId,
                        if (questionNodes.isNotEmpty()) {
                            questionNodes
                        } else {
                            response.storyList.map(InteractiveNode::fromStoryNode)
                        },
                        questionNodes.isNotEmpty(),
                    )
                }
            }.onSuccess { (responseEdgeId, nodes, fromQuestionChoices) ->
                if (nodes.isEmpty()) {
                    logger.fWarn { "Refresh interactive branches returned empty nodes, edgeId=$edgeId" }
                    withContext(Dispatchers.Main) {
                        pendingInteractiveOptionDialogRequest = false
                    }
                    return@onSuccess
                }

                val interactiveVideoList = nodes.mapIndexed { index, node ->
                    VideoListInteractiveNode(
                        aid = currentAid,
                        cid = node.cid,
                        title = title,
                        partTitle = node.title,
                        index = index,
                        nodeId = node.nodeId,
                        edgeId = node.edgeId,
                        startPos = node.startPos,
                        isCurrent = node.isCurrent,
                    )
                }

                withContext(Dispatchers.Main) {
                    interactiveOptionsFromQuestions = fromQuestionChoices
                    videoInfoRepository.videoList.clear()
                    videoInfoRepository.videoList.addAll(interactiveVideoList)
                    currentInteractiveNodeId = nodes.firstOrNull { it.isCurrent }?.nodeId
                        ?: currentInteractiveNodeId
                    currentInteractiveEdgeId = responseEdgeId
                        ?: nodes.firstOrNull { it.isCurrent }?.edgeId
                        ?: edgeId
                        ?: currentInteractiveEdgeId

                    if (pendingInteractiveOptionDialogRequest && fromQuestionChoices) {
                        showInteractiveOptionDialog = true
                    } else if (pendingInteractiveOptionDialogRequest) {
                        showInteractiveOptionDialog = false
                    }
                    pendingInteractiveOptionDialogRequest = false
                }
            }.onFailure {
                pendingInteractiveOptionDialogRequest = false
                logger.fWarn { "Refresh interactive branches failed: ${it.stackTraceToString()}" }
            }
        }
    }

    fun requestInteractiveOptionDialog(): Boolean {
        if (!isInteractivePlayback) return false

        pendingInteractiveOptionDialogRequest = true
        syncCurrentInteractivePointersFromList()
        val hasQuestionOptions = interactiveOptionsFromQuestions && interactiveOptions.isNotEmpty()
        if (hasQuestionOptions) {
            showInteractiveOptionDialog = true
            pendingInteractiveOptionDialogRequest = false
            return true
        }

        refreshInteractiveBranches(currentInteractiveEdgeId.takeIf { it > 0L })
        return false
    }

    fun dismissInteractiveOptionDialog() {
        showInteractiveOptionDialog = false
        pendingInteractiveOptionDialogRequest = false
    }

    fun selectInteractiveNode(nodeId: Long) {
        currentInteractiveNodeId = nodeId
    }

    fun playInteractiveOption(option: VideoListInteractiveNode) {
        showInteractiveOptionDialog = false
        pendingInteractiveOptionDialogRequest = false
        interactiveOptionsFromQuestions = false
        title = option.title
        partTitle = option.partTitle
        currentInteractiveEdgeId = option.edgeId ?: currentInteractiveEdgeId
        selectInteractiveNode(option.nodeId)
        loadPlayUrl(
            avid = option.aid,
            cid = option.cid,
            epid = option.epid,
            seasonId = option.seasonId,
            continuePlayNext = true,
            initialSeekPositionMs = option.startPos?.times(1000L),
        )
    }

    private fun syncCurrentInteractivePointersFromList() {
        val currentInteractiveOption = interactiveOptions.firstOrNull {
            it.isCurrent || it.cid == currentCid || it.nodeId == currentInteractiveNodeId
        } ?: return
        currentInteractiveNodeId = currentInteractiveOption.nodeId
        currentInteractiveEdgeId = currentInteractiveOption.edgeId ?: currentInteractiveEdgeId
    }

    private fun resolveInitialPlaybackPositionMs(): Long? {
        return pendingInitialSeekPositionMs?.takeIf { it >= 0L }
            ?: lastPlayed.takeIf {
                it > 0 && Prefs.playerDefaultStartPosition == PlayerDefaultStartPosition.History
            }?.toLong()
    }

    private suspend fun handleVVoucher(
        vVoucher: String,
        retryRequest: GeetestRetryRequest,
    ) {
        runCatching {
            val (reservedVoucher, registerResponse) = registerGeetestChallengeOnce(vVoucher)
            val token = registerResponse.token
            val gt = registerResponse.geetest.gt
            val challenge = registerResponse.geetest.challenge
            if (token.isBlank() || gt.isBlank() || challenge.isBlank()) {
                error("gaia_vgate_register 返回数据不完整")
            }
            withContext(Dispatchers.Main) {
                pendingGaiaToken = token
                pendingRetryRequest = retryRequest
                geetestGt = gt
                geetestChallenge = challenge
                showGeetestDialog = true
            }
            addLogs("请完成人机验证")
        }.onFailure {
            if (it is VVoucherAlreadyAttemptedException) {
                logger.fWarn { "Skip duplicated Geetest verification: ${it.message}" }
                addLogs("风控验证已申请过，跳过重复弹窗")
                withContext(Dispatchers.Main) {
                    errorMessage = "风控验证申请已提交，请稍后重试"
                    loadState = RequestState.Failed
                }
                return@onFailure
            }
            addLogs("风控验证申请失败：${it.localizedMessage}")
            withContext(Dispatchers.Main) {
                errorMessage = "风控验证申请失败：${it.localizedMessage}"
                loadState = RequestState.Failed
            }
            logger.fException(it) { "gaiaVgateRegister failed" }
        }
    }

    private suspend fun registerGeetestChallengeOnce(
        candidate: String,
    ): Pair<String, GaiaVgateRegisterData> =
        geetestVoucherRegisterMutex.withLock {
            val reservedVoucher = reserveFreshVVoucher(
                attemptedVVouchers = attemptedGeetestVVouchers,
                candidate = candidate,
            )
            reservedVoucher to BiliHttpApi.gaiaVgateRegister(
                vVoucher = reservedVoucher,
                sessData = authRepository.sessionData,
                csrf = authRepository.biliJct
            ).getResponseData()
        }

    fun onGeetestResult(challenge: String, validate: String, seccode: String) {
        val token = pendingGaiaToken ?: return
        val retryRequest = pendingRetryRequest ?: return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                // addLogs("正在提交验证结果…")
                val validateResponse = BiliHttpApi.gaiaVgateValidate(
                    token = token,
                    geetestChallenge = challenge,
                    validate = validate,
                    seccode = seccode,
                    sessData = authRepository.sessionData,
                    csrf = authRepository.biliJct
                ).getResponseData()
                if (validateResponse.isValid != 1) {
                    error("验证未通过")
                }
                val griskId = validateResponse.griskId
                if (griskId.isBlank()) {
                    error("grisk_id 为空")
                }
                authRepository.gaiaVtoken = griskId
                withContext(Dispatchers.Main) {
                    showGeetestDialog = false
                    pendingGaiaToken = null
                    pendingRetryRequest = null
                }
                addLogs("风控验证通过")
                logger.fInfo { "Gaia vgate validate success, retrying play url" }
                if (!isCurrentGeetestPlaybackSession(retryRequest)) {
                    logger.fDebug { "Skip Geetest retry: playback session changed" }
                    return@runCatching
                }
                loadPlayUrl(
                    avid = retryRequest.avid,
                    cid = retryRequest.cid,
                    epid = retryRequest.epid,
                    proxyArea = retryRequest.proxyArea,
                    initialSeekPositionMs = retryRequest.initialSeekPositionMs,
                )
            }.onFailure {
                addLogs("风控验证失败：${it.localizedMessage}")
                withContext(Dispatchers.Main) {
                    errorMessage = "风控验证失败：${it.localizedMessage}"
                    loadState = RequestState.Failed
                    showGeetestDialog = false
                    pendingGaiaToken = null
                    pendingRetryRequest = null
                }
                logger.fException(it) { "gaiaVgateValidate failed" }
            }
        }
    }

    fun onGeetestCancelled() {
        val retryRequest = pendingRetryRequest
        showGeetestDialog = false
        pendingGaiaToken = null
        pendingRetryRequest = null
        viewModelScope.launch {
            addLogs("用户取消了风控验证")
        }
        if (retryRequest != null && isCurrentGeetestPlaybackSession(retryRequest)) {
            // 用户放弃验证，尝试以游客试看流兜底播放
            viewModelScope.launch(Dispatchers.Default) {
                loadPlayUrl(
                    avid = retryRequest.avid,
                    cid = retryRequest.cid,
                    epid = retryRequest.epid,
                    proxyArea = retryRequest.proxyArea,
                    initialSeekPositionMs = retryRequest.initialSeekPositionMs,
                    tryLook = true,
                )
            }
        } else {
            errorMessage = "验证已取消"
            loadState = RequestState.Failed
        }
    }

    /** 校验触发风控的播放会话是否仍是当前会话，避免验证/兜底作用于已切换的视频 */
    private fun isCurrentGeetestPlaybackSession(retryRequest: GeetestRetryRequest): Boolean =
        currentAid == retryRequest.avid && currentCid == retryRequest.cid

    private suspend fun updateAvailableCodec() {
        val supportedCodec = playData!!.codec
        val codecList =
            (supportedCodec[currentQuality.code]?.mapNotNull { VideoCodec.fromCodecString(it) } ?: emptyList()).sortedBy { it.ordinal }

        availableVideoCodec.swapListWithMainContext(codecList)
        logger.fInfo { "Video available codec: ${availableVideoCodec.toList()}" }

        logger.fInfo { "Default codec: $currentVideoCodec" }
        val currentVideoCodec = if (codecList.contains(this@VideoPlayerV3ViewModel.currentVideoCodec)) {
            this@VideoPlayerV3ViewModel.currentVideoCodec
        } else if (codecList.contains(Prefs.defaultVideoCodec)) {
            Prefs.defaultVideoCodec
        } else {
            VideoCodec.findBestCodec(Prefs.defaultVideoCodec, codecList)
                ?: codecList.minByOrNull { it.ordinal }!!
        }
        withContext(Dispatchers.Main) {
            this@VideoPlayerV3ViewModel.currentVideoCodec = currentVideoCodec
        }
        logger.fInfo { "Select codec: $currentVideoCodec" }
    }

    /**
     * 解码器错误时自动降级到更低清晰度
     * @return true 表示已成功降级，false 表示已是最低清晰度无法降级
     */
    private fun fallbackToLowerQuality(): Boolean {
        val sortedQualities = availableQuality.sortedByDescending { it.code }
        val lowerQuality = sortedQualities.firstOrNull { it.code < currentQuality.code }
            ?: return false
        logger.fInfo { "Decoder error, fallback from $currentQuality to $lowerQuality" }
        viewModelScope.launch(Dispatchers.Main) {
            val position = videoPlayer?.currentPosition ?: 0
            playQuality(qn = lowerQuality)
            if (position > 0) videoPlayer?.seekTo(position)
            videoPlayer?.start()
        }
        return true
    }

    suspend fun playQuality(
        qn: Resolution = currentQuality,
        codec: VideoCodec = currentVideoCodec,
        audio: Audio = currentAudio
    ) {
        if (qn != currentQuality) {
            // 更新清晰度后需要先设置清晰度再更新编码列表
            withContext(Dispatchers.Main) { currentQuality = qn }
            updateAvailableCodec()
            playQuality(qn.code, currentVideoCodec, audio)
        } else {
            playQuality(qn.code, codec, audio)
        }
    }

    private suspend fun playQuality(
        qn: Int = currentQuality.code,
        codec: VideoCodec = currentVideoCodec,
        audio: Audio = currentAudio
    ) {
        logger.fInfo { "Select resolution: $qn, codec: $codec, audio: $audio" }
        if(playData == null) {
            return
        }

        val videoItem = playData!!.dashVideos.find {
            when (Prefs.apiType) {
                ApiType.Web -> it.quality == qn && it.codecs?.startsWith(codec.prefix) == true
                ApiType.App -> {
                    if (playData!!.codec.isEmpty()) it.quality == qn
                    else it.quality == qn && it.codecs?.startsWith(codec.prefix) == true
                }
            }
        } ?: playData!!.dashVideos.firstOrNull()
        var videoUrl = videoItem?.baseUrl
        if (videoUrl == null) {
            logger.fError { "Failed to get video URL" }
            errorMessage = "获取视频地址失败"
            loadState = RequestState.Failed
            return
        }
        val videoUrls = mutableListOf<String?>()
        videoUrls.add(videoItem?.baseUrl)
        videoUrls.addAll(videoItem?.backUrl ?: emptyList())

        val audioItem = listOfNotNull(
            playData!!.dashAudios.find { it.codecId == audio.code },
            playData!!.dolby.takeIf { it?.codecId == audio.code },
            playData!!.flac.takeIf { it?.codecId == audio.code },
            playData!!.dashAudios.minByOrNull { it.codecId },
            playData!!.dolby,
            playData!!.flac
        ).firstOrNull()
        var audioUrl = audioItem?.baseUrl
        val audioUrls = mutableListOf<String>()
        audioItem?.baseUrl?.let(audioUrls::add)
        audioUrls.addAll(audioItem?.backUrl ?: emptyList())

        logger.fInfo { "all video hosts: ${videoUrls.filterNotNull().map { with(URI(it)) { "$scheme://$authority" } }}" }
        logger.fInfo { "all audio hosts: ${audioUrls.map { with(URI(it)) { "$scheme://$authority" } }}" }

        //replace cdn
        if (Prefs.enableProxy && proxyArea != ProxyArea.MainLand) {
            videoUrl = videoUrl.replaceUrlDomainWithAliCdn()
            audioUrl = audioUrl?.replaceUrlDomainWithAliCdn()
        } else {
            // 如果未通过网络代理获得播放地址，才判断是否应该替换为官方 cdn
            videoUrl = selectOfficialCdnUrl(videoUrls.filterNotNull())
            audioUrl = audioUrls.takeIf { it.isNotEmpty() }?.let(::selectOfficialCdnUrl)
        }

        if (audioUrl == null) {
            logger.fWarn { "Failed to get audio URL, fallback to video-only playback" }
        }

        addLogs(
            "播放清晰度：${availableQuality.firstOrNull { it.code == qn }}, " +
                    "视频编码：${codec.getDisplayName(BVApp.context)}, " +
                    "音频编码：${(Audio.fromCode(audioItem?.codecId ?: 0))?.getDisplayName(BVApp.context) ?: "未知"}",
            replaceIfContains = "播放清晰度"
        )

        var videoHost = with(URI(videoUrl)) { "$scheme://$authority" }
        var audioHost = audioUrl?.let { with(URI(it)) { "$scheme://$authority" } } ?: "无音频流，纯视频流播放"
        addLogs("video host: $videoHost", replaceIfContains = "video host")
        addLogs("audio host: $audioHost", replaceIfContains = "audio host")

        logger.fInfo { "Select audio: $audioItem" }

        withContext(Dispatchers.Main) {
            currentVideoHeight = videoItem?.height ?: 0
            currentVideoWidth = videoItem?.width ?: 0
            lastVideoHost = videoHost
            lastAudioHost = audioHost
            logger.info { "Video url: $videoUrl" }
            logger.info { "Audio url: $audioUrl" }
            videoPlayer!!.playUrl(videoUrl, audioUrl)
            val initialSeekPosition = resolveInitialPlaybackPositionMs()
            if (initialSeekPosition != null) {
                logger.info { "Set initial seek position: ${initialSeekPosition}ms" }
                videoPlayer!!.setInitialSeekPosition(initialSeekPosition)
            }
            videoPlayer!!.prepare()
            showBuffering = true
        }
        // 为点播内容安排CDN URL自动刷新，防止2小时有效期过期
        if (!isLive) {
            schedulePlayUrlAutoRefresh(
                videoUrl = videoUrl,
                audioUrl = audioUrl,
                reason = "play_quality"
            )
        }
    }

    private fun startShowPreviewTipCountdown() {
        previewTipJob?.cancel()
        previewTipJob = viewModelScope.launch {
            showPreviewTip = true
            delay(5000)
            showPreviewTip = false
        }
    }

    // ==================== 点播CDN URL自动刷新 ====================

    private fun cancelPlayUrlAutoRefresh(reason: String) {
        playUrlAutoRefreshJob?.cancel()
        playUrlAutoRefreshJob = null
        playUrlAutoRefreshToken++
        logger.fInfo { "playurl:autoRefresh:cancel reason=$reason" }
    }

    private fun schedulePlayUrlAutoRefresh(
        videoUrl: String,
        audioUrl: String?,
        reason: String
    ) {
        playUrlAutoRefreshJob?.cancel()
        playUrlAutoRefreshJob = null

        val nowWallMs = System.currentTimeMillis()
        val deadlineEpochSec = pickEarliestDeadlineEpochSec(videoUrl, audioUrl)

        val delayMs = if (deadlineEpochSec != null) {
            val refreshWallMs = deadlineEpochSec * 1000L - PLAYURL_AUTO_REFRESH_LEAD_MS
            (refreshWallMs - nowWallMs).coerceAtLeast(0L)
        } else {
            val durationMs = videoPlayer?.duration?.takeIf { it > 0 }
            if (durationMs != null && durationMs >= PLAYURL_AUTO_REFRESH_FALLBACK_MIN_DURATION_MS) {
                PLAYURL_AUTO_REFRESH_FALLBACK_DELAY_MS
            } else {
                logger.fInfo {
                    "playurl:autoRefresh:skip reason=$reason deadline=none duration=${durationMs ?: -1}ms"
                }
                return
            }
        }

        val token = ++playUrlAutoRefreshToken
        val aid = currentAid
        val cid = currentCid

        logger.fInfo {
            "playurl:autoRefresh:schedule delay=${delayMs}ms deadline=${deadlineEpochSec ?: -1} reason=$reason"
        }

        playUrlAutoRefreshJob = viewModelScope.launch(Dispatchers.Main) {
            delay(delayMs)
            if (token != playUrlAutoRefreshToken) return@launch
            if (videoPlayer == null) return@launch
            if (currentAid != aid || currentCid != cid) return@launch

            logger.fInfo {
                "playurl:autoRefresh:reload token=$token pos=${videoPlayer?.currentPosition ?: 0}ms"
            }

            // 重新加载播放URL（保持当前位置）
            reloadPlayUrl()
        }
    }

    /**
     * 重新加载播放URL，保持当前播放位置和状态
     * 用于CDN URL过期前的主动刷新
     */
    private suspend fun reloadPlayUrl() {
        val aid = currentAid
        val cid = currentCid
        val currentPos = withContext(Dispatchers.Main) {
            videoPlayer?.currentPosition?.coerceAtLeast(0L) ?: 0L
        }
        val wasPlaying = withContext(Dispatchers.Main) {
            videoPlayer?.isPlaying == true
        }

        logger.fInfo { "reloadPlayUrl: aid=$aid, cid=$cid, pos=${currentPos}ms" }

        runCatching {
            val playData = if (fromSeason) {
                videoPlayRepository.getPgcPlayData(
                    aid = aid,
                    cid = cid,
                    epid = currentEpid,
                    preferCodec = Prefs.defaultVideoCodec.toBiliApiCodeType(),
                    preferApiType = Prefs.apiType,
                    enableProxy = Prefs.enableProxy,
                    proxyArea = when (proxyArea) {
                        ProxyArea.MainLand -> ""
                        ProxyArea.HongKong -> "hk"
                        ProxyArea.TaiWan -> "tw"
                    }
                )
            } else {
                videoPlayRepository.getPlayData(
                    aid = aid,
                    cid = cid,
                    preferApiType = Prefs.apiType
                )
            }

            withContext(Dispatchers.Main) { this@VideoPlayerV3ViewModel.playData = playData }

            // 使用当前清晰度和编码重新播放
            val qn = currentQuality
            val codec = currentVideoCodec
            val audio = currentAudio

            // 查找视频项
            val videoItem = playData.dashVideos.find {
                when (Prefs.apiType) {
                    ApiType.Web -> it.quality == qn.code && it.codecs!!.startsWith(codec.prefix)
                    ApiType.App -> {
                        if (playData.codec.isEmpty()) it.quality == qn.code
                        else it.quality == qn.code && it.codecs!!.startsWith(codec.prefix)
                    }
                }
            }
            var videoUrl = videoItem?.baseUrl ?: playData.dashVideos.firstOrNull()?.baseUrl
                ?: return@runCatching

            val videoUrls = mutableListOf<String?>()
            videoUrls.add(videoItem?.baseUrl)
            videoUrls.addAll(videoItem?.backUrl ?: emptyList())

            val audioItem = listOfNotNull(
                playData.dashAudios.find { it.codecId == audio.code },
                playData.dolby.takeIf { it?.codecId == audio.code },
                playData.flac.takeIf { it?.codecId == audio.code },
                playData.dashAudios.minByOrNull { it.codecId },
                playData.dolby,
                playData.flac
            ).firstOrNull()
            var audioUrl = audioItem?.baseUrl
            val audioUrls = mutableListOf<String>()
            audioItem?.baseUrl?.let(audioUrls::add)
            audioUrls.addAll(audioItem?.backUrl ?: emptyList())

            if (Prefs.enableProxy && proxyArea != ProxyArea.MainLand) {
                videoUrl = videoUrl.replaceUrlDomainWithAliCdn()
                audioUrl = audioUrl?.replaceUrlDomainWithAliCdn()
            } else {
                videoUrl = selectOfficialCdnUrl(videoUrls.filterNotNull())
                audioUrl = audioUrls.takeIf { it.isNotEmpty() }?.let(::selectOfficialCdnUrl)
            }

            withContext(Dispatchers.Main) {
                videoPlayer?.let { player ->
                    player.playUrl(videoUrl, audioUrl)
                    player.prepare()
                    player.seekTo(currentPos)
                    if (wasPlaying) player.start()
                }
            }

            // 安排下一次自动刷新
            schedulePlayUrlAutoRefresh(
                videoUrl = videoUrl,
                audioUrl = audioUrl,
                reason = "auto_refresh"
            )

            logger.fInfo { "playurl:autoRefresh:reload:success" }
        }.onFailure {
            logger.fException(it) { "playurl:autoRefresh:reload:failed" }
        }
    }

    /**
     * 从URL中解析CDN过期时间（epoch秒）
     * B站CDN URL通常包含 deadline 或 expires 查询参数
     */
    private fun parseDeadlineEpochSec(url: String): Long? {
        return runCatching {
            val uri = Uri.parse(url)
            val raw = uri.getQueryParameter("deadline")
                ?: uri.getQueryParameter("expires")
                ?: return null
            raw.toLongOrNull()
        }.getOrNull()
    }

    private fun pickEarliestDeadlineEpochSec(videoUrl: String, audioUrl: String?): Long? {
        val videoDeadline = parseDeadlineEpochSec(videoUrl)
        val audioDeadline = audioUrl?.let { parseDeadlineEpochSec(it) }
        return when {
            videoDeadline != null && audioDeadline != null -> minOf(videoDeadline, audioDeadline)
            videoDeadline != null -> videoDeadline
            audioDeadline != null -> audioDeadline
            else -> null
        }
    }

    private suspend fun <T> withPlayerOnMain(block: AbstractVideoPlayer.() -> T): T? {
        return withContext(Dispatchers.Main) {
            videoPlayer?.block()
        }
    }

    suspend fun loadDanmaku(cid: Long) {
        stopDanmakuSegmentLoading()

        val initialPosition = resolveInitialPlaybackPositionMs() ?: run {
            withContext(Dispatchers.Main) {
                videoPlayer?.currentPosition?.coerceAtLeast(0L) ?: 0L
            }
        }

        loadDanmakuSegment(cid, initialPosition, force = true)
        startDanmakuSegmentWatcher(cid)
        pendingInitialSeekPositionMs = null
    }

    private suspend fun updateSubtitle() {
        currentSubtitleId = -1
        currentSubtitleData.clear()

        runCatching {
            val subtitleData = videoPlayRepository.getSubtitle(
                aid = currentAid,
                cid = currentCid,
                preferApiType = Prefs.apiType
            )
            withContext(Dispatchers.Main) {
                availableSubtitle.clear()
                availableSubtitle.add(
                    Subtitle(
                        id = -1,
                        lang = "",
                        langDoc = "关闭",
                        url = "",
                        type = SubtitleType.CC,
                        aiType = SubtitleAiType.Normal,
                        aiStatus = SubtitleAiStatus.None
                    )
                )
                availableSubtitle.addAll(subtitleData)
                availableSubtitle.sortBy { it.id }
            }
            addLogs("获取到 ${subtitleData.size} 条字幕: ${subtitleData.map { it.langDoc }}")
            logger.fInfo { "Update subtitle size: ${subtitleData.size}" }
        }.onFailure {
            addLogs("获取字幕失败：${it.localizedMessage}")
            logger.fWarn { "Update subtitle failed: ${it.stackTraceToString()}" }
        }
    }

    /**
     * 自动加载字幕
     *
     * @param preferLang 优先匹配的语言名称（来自播放器中手动选择的字幕），优先级高于默认字幕设置
     * @param ccOnly 是否仅匹配非AI字幕（type == CC）
     */
    private fun autoLoadSubtitle(preferLang: String?, ccOnly: Boolean) {
        val defaultSubtitle = Prefs.defaultSubtitle
        if (preferLang == null && defaultSubtitle == DefaultSubtitle.Off) return

        val langKeyword = preferLang ?: when (defaultSubtitle) {
            DefaultSubtitle.Chinese -> "中文"
            DefaultSubtitle.English -> "English"
            DefaultSubtitle.Off -> return
        }

        val candidates = availableSubtitle.filter { it.id != -1L && (!ccOnly || it.type == SubtitleType.CC) }
        val match = candidates.firstOrNull { it.langDoc.contains(langKeyword, ignoreCase = true) }

        if (match != null) {
            logger.fInfo { "Auto load subtitle: ${match.langDoc}" }
            loadSubtitle(match.id)
        } else {
            logger.fInfo { "No matching subtitle for lang=\"$langKeyword\", ccOnly=$ccOnly" }
        }
    }

    private suspend fun addLogs(text: String, replaceIfContains: String? = null) {
        logger.fInfo { text }
        val lines = logs.lines().filter { it.isNotEmpty() }.toMutableList()
        if (replaceIfContains != null) {
            val idx = lines.indexOfLast { it.contains(replaceIfContains) }
            if (idx >= 0) lines[idx] = text else lines.add(text)
        } else {
            lines.add(text)
        }
        while (lines.size > 8) {
            lines.removeAt(0)
        }
        val newTip = lines.joinToString("\n")
        withContext(Dispatchers.Main) {
            logs = newTip
            lastChangedLog = System.currentTimeMillis()
            videoPlayer?.extraDebugInfo = newTip
        }
    }

    suspend fun uploadHistory(time: Int) {
        if (!Prefs.isLogin) {
            return@uploadHistory
        }
        runCatching {
            if (!fromSeason) {
                logger.info { "Send heartbeat: [avid=$currentAid, cid=$currentCid, time=$time]" }
                videoPlayRepository.sendHeartbeat(
                    aid = currentAid,
                    cid = currentCid,
                    time = time,
                    preferApiType = Prefs.apiType
                )
            } else {
                logger.info { "Send heartbeat: [avid=$currentAid, cid=$currentCid, epid=$epid, sid=$seasonId, time=$time]" }
                videoPlayRepository.sendHeartbeat(
                    aid = currentAid,
                    cid = currentCid,
                    time = time,
                    type = HeartbeatVideoType.Season,
                    subType = subType,
                    epid = epid,
                    seasonId = seasonId,
                    preferApiType = Prefs.apiType
                )
            }
        }.onSuccess {
            logger.info { "Send heartbeat success" }
        }.onFailure {
            logger.warn { "Send heartbeat failed: ${it.stackTraceToString()}" }
        }
    }

    fun loadSubtitle(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            if (id == -1L) {
                withContext(Dispatchers.Main) {
                    currentSubtitleData.clear()
                    currentSubtitleId = -1
                    currentSubtitleType = SubtitleType.CC
                }
                return@launch
            }
            var subtitleName = ""
            runCatching {
                val subtitle = availableSubtitle.find { it.id == id } ?: return@runCatching
                subtitleName = subtitle.langDoc
                val isAI = subtitle.type == SubtitleType.AI
                logger.info { "Subtitle url: ${subtitle.url}, isAI: $isAI" }
                val client = HttpClient(OkHttp)
                val responseText = client.get(subtitle.url).bodyAsText()
                val subtitleData = SubtitleParser.fromBccString(responseText, isAI)
                withContext(Dispatchers.Main) {
                    currentSubtitleId = id
                    currentSubtitleType = subtitle.type
                    currentSubtitleData.swapList(subtitleData)
                }
            }.onFailure {
                withContext(Dispatchers.Main) {
                    currentSubtitleData.clear()
                    currentSubtitleId = -1
                    currentSubtitleType = SubtitleType.CC
                }
                logger.fInfo { "Load subtitle failed: ${it.stackTraceToString()}" }
                addLogs("加载字幕 $subtitleName 失败: ${it.localizedMessage}")
            }.onSuccess {
                logger.fInfo { "Load subtitle $subtitleName success" }
                addLogs("加载字幕 $subtitleName 成功，数量: ${currentSubtitleData.size}")
            }
        }
    }

    private fun String.replaceUrlDomainWithAliCdn(): String {
        val replaceDomainKeywords = listOf(
            "mirroraliov",
            "mirrorakam"
        )
        if (replaceDomainKeywords.none { this.contains(it) }) return this

        return Uri.parse(this)
            .buildUpon()
            .authority("upos-sz-mirrorali.bilivideo.com")
            .build()
            .toString()
    }

    private fun selectOfficialCdnUrl(urls: List<String>): String {
        if (urls.isEmpty()) {
            logger.fInfo { "doesn't find any url, select a random url" }
            return urls.randomOrNull() ?: ""
        }

        // 判定是否为“官方” CDN 的简单规则，和之前逻辑保持一致
        val isOfficialCdn: (String) -> Boolean = {
            !it.contains(".mcdn.bilivideo.") &&
            !it.contains(".szbdyd.com") &&
            !Regex("^(https?://)?(\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}(:\\d{1,5})?)(/[a-zA-Z0-9_./-]*)?(\\?.*)?$")
                .matches(it)
        }

        if (!Prefs.preferOfficialCdn) {
            // 当用户不偏好官方 CDN 时，使用加权随机：官方权重 0.8，非官方权重 1.2（基准为 1）
            logger.fInfo { "doesn't need to filter official cdn url, select a weighted random url (favor non-official)" }

            val weights = urls.map { url -> if (isOfficialCdn(url)) 1 else 1 }
            val total = weights.sum()
            // 如果权重计算异常，退回随机
            if (total <= 0.0) return urls.randomOrNull() ?: ""

            val r = kotlin.random.Random.Default.nextDouble() * total
            var acc = 0.0
            for (i in urls.indices) {
                acc += weights[i]
                if (r <= acc) return urls[i]
            }
            return urls.randomOrNull() ?: ""
        }

        val filteredUrls = urls.filter{isOfficialCdn(it)}
        if (filteredUrls.isEmpty()) {
            logger.fInfo { "doesn't find any official cdn url, select a random url" }
            return urls.random()
        } else {
            logger.fInfo { "filtered official cdn urls: $filteredUrls" }
            return filteredUrls.random()
        }
    }

    private suspend fun updateDanmakuMask() {
        runCatching {
            val masks = videoPlayRepository.getDanmakuMask(
                aid = currentAid,
                cid = currentCid,
                preferApiType = Prefs.apiType
            )
            danmakuMasks.swapListWithMainContext(masks)
            logger.fInfo { "Load danmaku mask size: ${danmakuMasks.size}" }
        }.onFailure {
            logger.fWarn { "Load danmaku mask failed: ${it.stackTraceToString()}" }
        }
    }

    private suspend fun updateVideoShot() {
        withContext(Dispatchers.Main) { videoShot = null }
        runCatching {
            val videoShot = videoPlayRepository.getVideoShot(
                aid = currentAid,
                cid = currentCid,
                preferApiType = Prefs.apiType
            )
            withContext(Dispatchers.Main) { this@VideoPlayerV3ViewModel.videoShot = videoShot }
            logger.fInfo { "Load video shot success" }
        }.onFailure {
            logger.fWarn { "Load video shot failed: ${it.stackTraceToString()}" }
        }
    }

    // 这个方法当时只适配了移动端逻辑，TV端比较复杂，另外写了一份
    fun playNextVideo() {
        logger.fInfo { "Video finished" }
        when (currentPlayMode) {
            PlayMode.Custom -> {
                logger.info { "Play mode: $currentPlayMode, using strategy order" }
                val validOrdinals = NextVideoStrategy.entries.map { it.ordinalValue }.toSet()
                val strategies = Prefs.playerNextVideoStrategyOrder.split(",")
                    .filter { !it.startsWith("-") }
                    .mapNotNull {
                        val id = it.toIntOrNull() ?: return@mapNotNull null
                        if (id !in validOrdinals) return@mapNotNull null
                        NextVideoStrategy.fromOrdinal(id)
                    }
                for (strategy in strategies) {
                    when (strategy) {
                        NextVideoStrategy.SingleVideo -> {
                            logger.info { "Strategy SingleVideo: stop" }
                            return
                        }
                        NextVideoStrategy.PartAndEpisode,
                        NextVideoStrategy.PreloadedVideoList -> {
                            if (playNextVideoInList()) return
                        }
                        NextVideoStrategy.PartAndEpisodeReverse,
                        NextVideoStrategy.PreloadedVideoListReverse -> {
                            if (playPrevVideoInList()) return
                        }
                        NextVideoStrategy.RelatedVideo -> {
                            // handled by screen
                            logger.info { "Strategy RelatedVideo: handled by screen" }
                        }
                    }
                }
            }

            PlayMode.SingleVideo -> {
                logger.info { "Play mode: $currentPlayMode, no auto next" }
            }

            PlayMode.SingleLoop -> {
                logger.info { "Play mode: $currentPlayMode, replay current video" }
                danmakuView?.notifySeek(0L)
                videoPlayer?.seekTo(0L)
            }

            PlayMode.ListOrder -> {
                logger.info { "Play mode: $currentPlayMode, play next video in list" }
                playNextVideoInList()
            }

            PlayMode.ListOrderReverse -> {
                logger.info { "Play mode: $currentPlayMode, play previous video in list" }
                playPrevVideoInList()
            }

            PlayMode.PartAndEpisode -> {
                logger.info { "Play mode: $currentPlayMode, play next video in list" }
                playNextVideoInList()
            }

            PlayMode.PartAndEpisodeReverse -> {
                logger.info { "Play mode: $currentPlayMode, play previous video in list" }
                playPrevVideoInList()
            }

            PlayMode.RelatedVideo -> {
                logger.info { "Play mode: $currentPlayMode, do nothing (handled by screen)" }
            }
        }
    }

    private fun playNextVideoInList(loop: Boolean = false): Boolean {
        if (isInteractivePlayback || availableVideoList.any { it is VideoListInteractiveNode }) {
            logger.info { "Interactive branches detected, skip auto playing next branch" }
            return false
        }

        val currentIndex = availableVideoList
            .indexOfFirst {
                when (it) {
                    is VideoListItemData -> it.cid == currentCid
                    else -> false
                }
            }
        if (currentIndex + 1 < availableVideoList.size) {
            val nextVideos = availableVideoList.subList(
                currentIndex + 1,
                availableVideoList.size
            )
            val nextVideo =
                nextVideos.firstOrNull { it is VideoListItemData }!! as VideoListItemData
            logger.info { "Play next video: $nextVideo" }
            partTitle = nextVideo.title
            loadPlayUrl(
                avid = nextVideo.aid,
                cid = nextVideo.cid!!,
                epid = nextVideo.epid,
                seasonId = nextVideo.seasonId,
                continuePlayNext = true
            )
            return true
        } else if (loop) {
            //loop to first
            val firstVideo =
                availableVideoList.firstOrNull { it is VideoListItemData }!! as VideoListItemData
            logger.info { "Loop to first video: $firstVideo" }
            partTitle = firstVideo.title
            loadPlayUrl(
                avid = firstVideo.aid,
                cid = firstVideo.cid!!,
                epid = firstVideo.epid,
                seasonId = firstVideo.seasonId,
                continuePlayNext = true
            )
            return true
        }
        return false
    }

    private fun playPrevVideoInList(): Boolean {
        val currentIndex = availableVideoList
            .indexOfFirst {
                when (it) {
                    is VideoListItemData -> it.cid == currentCid
                    else -> false
                }
            }
        if (currentIndex > 0) {
            val prevVideos = availableVideoList.subList(0, currentIndex)
            val prevVideo =
                prevVideos.lastOrNull { it is VideoListItemData } as? VideoListItemData
            if (prevVideo != null) {
                logger.info { "Play previous video: $prevVideo" }
                partTitle = prevVideo.title
                loadPlayUrl(
                    avid = prevVideo.aid,
                    cid = prevVideo.cid!!,
                    epid = prevVideo.epid,
                    seasonId = prevVideo.seasonId,
                    continuePlayNext = true
                )
                return true
            }
        }
        return false
    }

    /**
     * 加载直播流（带画质信息）
     * @param roomId 直播间ID
     * @param qn 请求的画质编号，默认30000（最高值，服务端会自动降级）
     */
    fun loadLiveStreamWithQuality(roomId: Int, qn: Int = 30000) {
        var isSwitchRoom = false
        if (liveRoomId != roomId) {
            // 切换直播间时重置画质和编码为默认值
            currentLiveQn = 30000
            currentLiveCodec = Prefs.defaultLiveCodec
            liveRoomId = roomId
            isSwitchRoom = true
        }
        // 取消之前的重连任务
        liveRetryJob?.cancel()
        liveRetryJob = null
        // 取消之前的URL刷新任务
        liveUrlRefreshJob?.cancel()
        liveUrlRefreshJob = null
        // 重置刷新失败计数
        consecutiveRefreshFailures = 0
        // 标记播放器为直播模式
        videoPlayer?.isLive = true

        viewModelScope.launch(Dispatchers.IO) {
            logger.fInfo { "Load live stream with quality: roomId=$roomId, qn=$qn" }
            withContext(Dispatchers.Main) { loadState = RequestState.Doing }

            // 仅在首次加载时初始化弹幕播放器，画质切换时不重复创建
            if (danmakuView == null) {
                ensureDanmakuView()
            }

            val playInfo = LiveStreamUrlFetcher.fetchLiveStreamUrl(roomId, qn, currentLiveCodec, preferredLiveLineIndex)
            if (playInfo == null) {
                withContext(Dispatchers.Main) {
                    loadState = RequestState.Failed
                    errorMessage = "获取直播流失败"
                }
                return@launch
            }

            withContext(Dispatchers.Main) {
                liveStreamUrl = playInfo.streamUrl
                liveStreamExpiresAt = playInfo.expiresAt
                currentLiveQn = playInfo.currentQn
                liveQnDescMap = playInfo.qnDescMap
                currentLiveLineIndex = playInfo.currentLineIndex
                availableLiveLines.clear()
                availableLiveLines.addAll(playInfo.availableLines)
                liveTime = playInfo.liveTime

                // 更新可用画质列表（按 qn 降序，即最高画质在前）
                val qualities = playInfo.acceptQn
                    .sortedDescending()
                    .map { qualityQn ->
                        qualityQn to (playInfo.qnDescMap[qualityQn] ?: "未知画质 $qualityQn")
                    }
                availableLiveQualities.clear()
                availableLiveQualities.addAll(qualities)

                currentLiveQualityDescription = playInfo.qnDescMap[playInfo.currentQn] ?: "未知画质"
                logger.fInfo { "Live quality: current=${playInfo.currentQn} ($currentLiveQualityDescription), available=$qualities" }
                logger.fDebug { "Live stream URL expires at: ${playInfo.expiresAt}" }
            }

            runCatching {
                withContext(Dispatchers.Main) {
                    videoPlayer?.playUrl(videoUrl = playInfo.streamUrl)
                    videoPlayer?.prepare()
                    videoPlayer?.start()
                    loadState = RequestState.Success
                }
                logger.fInfo { "Live stream loaded successfully with quality ${playInfo.currentQn}" }
                // 播放成功后自动启动直播弹幕（仅首次加载，画质切换时不重启弹幕）
                if (liveWebSocket == null || isSwitchRoom) {
                    startLiveDanmaku(roomId)
                }
                // 调度URL刷新
                scheduleLiveUrlRefresh()
            }.onFailure { e ->
                logger.fError { "Failed to load live stream: ${e.message}" }
                withContext(Dispatchers.Main) {
                    loadState = RequestState.Failed
                    errorMessage = "加载直播流失败: ${e.message}"
                }
            }
        }
    }

    /**
     * 切换直播画质
     * @param qn 目标画质编号
     */
    fun changeLiveQuality(qn: Int) {
        logger.fInfo { "Change live quality to: $qn" }
        loadLiveStreamWithQuality(liveRoomId, qn)
    }

    /**
     * 切换直播编码格式
     * @param codec 目标编码格式
     */
    fun changeLiveCodec(codec: LiveCodec) {
        logger.fInfo { "Change live codec to: $codec" }
        currentLiveCodec = codec
        Prefs.defaultLiveCodec = codec
        loadLiveStreamWithQuality(liveRoomId, currentLiveQn)
    }

    /**
     * 切换直播线路
     * @param lineIndex 目标线路序号
     */
    fun changeLiveLine(lineIndex: Int) {
        logger.fInfo { "Change live line to: $lineIndex" }
        preferredLiveLineIndex = lineIndex
        loadLiveStreamWithQuality(liveRoomId, currentLiveQn)
    }

    /**
     * 直播流错误时自动重连
     * 延迟 2 秒后重新获取直播流 URL 并播放。
     * 使用 liveRetryJob 做防抖：新的重连请求会取消上一次未执行的延迟重试。
     * 当直播间已关闭（liveStatus != 1）时，fetchLiveStreamUrl 返回 null，自动停止重试。
     */
    fun retryLiveStream() {
        if (!isLive) return
        logger.fInfo { "Scheduling live stream retry in 2s for room $liveRoomId" }

        // 防抖：取消上一次待执行的重试
        liveRetryJob?.cancel()
        // 取消URL刷新任务
        liveUrlRefreshJob?.cancel()
        liveUrlRefreshJob = null

        liveRetryJob = viewModelScope.launch(Dispatchers.IO) {
            delay(2000)
            // 仅在播放器未在播放时重试
            val playing = withContext(Dispatchers.Main) { videoPlayer?.isPlaying == true }
            if (playing) {
                logger.fInfo { "Player is already playing, skip retry" }
                return@launch
            }
            logger.fInfo { "Retrying live stream for room $liveRoomId, qn=$currentLiveQn" }
            withContext(Dispatchers.Main) {
                loadState = RequestState.Doing
                // 重连时先清除错误状态，让 UI 不再显示错误
                errorMessage = ""
            }
            val playInfo = LiveStreamUrlFetcher.fetchLiveStreamUrl(liveRoomId, currentLiveQn, currentLiveCodec, preferredLiveLineIndex)
            if (playInfo == null) {
                // fetchLiveStreamUrl 内部已判断 liveStatus != 1 并 Toast "主播未开播"
                // 此时不再继续重试
                logger.fInfo { "Live stream fetch returned null, live may have ended" }
                withContext(Dispatchers.Main) {
                    loadState = RequestState.Failed
                    errorMessage = "直播已结束或获取直播流失败"
                }
                return@launch
            }
            // 成功获取新 URL，重新播放
            withContext(Dispatchers.Main) {
                liveStreamUrl = playInfo.streamUrl
                liveStreamExpiresAt = playInfo.expiresAt
                currentLiveQn = playInfo.currentQn
                currentLiveLineIndex = playInfo.currentLineIndex
                availableLiveLines.clear()
                availableLiveLines.addAll(playInfo.availableLines)
                liveTime = playInfo.liveTime
                videoPlayer?.playUrl(videoUrl = playInfo.streamUrl)
                videoPlayer?.prepare()
                videoPlayer?.start()
                loadState = RequestState.Success
            }
            logger.fInfo { "Live stream retry successful, new URL loaded" }
            // 重置刷新失败计数并重新调度刷新
            consecutiveRefreshFailures = 0
            scheduleLiveUrlRefresh()
        }
    }

    /**
     * 调度直播流URL的主动刷新
     * 在URL过期前REFRESH_BEFORE_EXPIRY_MS毫秒自动刷新
     */
    private fun scheduleLiveUrlRefresh() {
        // 取消之前的刷新任务
        liveUrlRefreshJob?.cancel()

        if (!isLive || liveStreamExpiresAt <= 0) {
            logger.fDebug { "No need to schedule refresh: isLive=$isLive, expiresAt=$liveStreamExpiresAt" }
            return
        }

        val now = System.currentTimeMillis()
        val timeUntilExpiry = liveStreamExpiresAt - now
        val refreshDelay = (timeUntilExpiry - REFRESH_BEFORE_EXPIRY_MS)
            .coerceAtLeast(MIN_REFRESH_INTERVAL_MS)

        logger.fInfo { "Scheduling live URL refresh in ${refreshDelay}ms (expires at $liveStreamExpiresAt)" }

        liveUrlRefreshJob = viewModelScope.launch(Dispatchers.IO) {
            delay(refreshDelay)
            refreshLiveStreamUrl()
        }
    }

    /**
     * 刷新直播流URL（无缝切换）
     */
    private suspend fun refreshLiveStreamUrl() {
        if (!isLive) return

        logger.fInfo { "Refreshing live stream URL for room $liveRoomId" }

        try {
            val playInfo = LiveStreamUrlFetcher.fetchLiveStreamUrl(
                liveRoomId,
                currentLiveQn,
                currentLiveCodec,
                preferredLiveLineIndex
            )

            if (playInfo == null) {
                // 刷新失败，可能是直播已结束
                consecutiveRefreshFailures++
                logger.fWarn { "Failed to refresh live URL (attempt $consecutiveRefreshFailures), live may have ended" }

                if (consecutiveRefreshFailures >= MAX_REFRESH_FAILURES) {
                    // 多次失败，可能直播已结束，停止刷新
                    logger.fWarn { "Max refresh failures reached, stopping refresh" }
                    withContext(Dispatchers.Main) {
                        loadState = RequestState.Failed
                        errorMessage = "直播可能已结束"
                    }
                    return
                }

                // 如果直播未结束但刷新失败，稍后重试
                delay(REFRESH_RETRY_INTERVAL_MS)
                scheduleLiveUrlRefresh()
                return
            }

            // 重置失败计数
            consecutiveRefreshFailures = 0

            // 更新URL和过期时间
            withContext(Dispatchers.Main) {
                liveStreamUrl = playInfo.streamUrl
                liveStreamExpiresAt = playInfo.expiresAt
                currentLiveQn = playInfo.currentQn
                currentLiveLineIndex = playInfo.currentLineIndex
                availableLiveLines.clear()
                availableLiveLines.addAll(playInfo.availableLines)
                liveTime = playInfo.liveTime
            }

            // 无缝切换：更新播放器URL
            withContext(Dispatchers.Main) {
                videoPlayer?.playUrl(videoUrl = playInfo.streamUrl)
            }

            logger.fInfo { "Live URL refreshed successfully, new expiresAt=$liveStreamExpiresAt" }

            // 调度下一次刷新
            scheduleLiveUrlRefresh()
        } catch (e: Exception) {
            logger.fError { "Error refreshing live URL: ${e.message}" }
            consecutiveRefreshFailures++
            if (consecutiveRefreshFailures < MAX_REFRESH_FAILURES) {
                delay(REFRESH_RETRY_INTERVAL_MS)
                scheduleLiveUrlRefresh()
            }
        }
    }

    /**
     * 加载直播流
     */
    fun loadLiveStream(streamUrl: String) {
        viewModelScope.launch(Dispatchers.IO) {
            logger.fInfo { "Load live stream: $streamUrl" }
            withContext(Dispatchers.Main) { loadState = RequestState.Doing }

            // 初始化弹幕播放器
            ensureDanmakuView()
            logger.fInfo { "Danmaku view ready for live stream" }

            runCatching {
                withContext(Dispatchers.Main) {
                    videoPlayer?.playUrl(videoUrl = streamUrl)
                    videoPlayer?.prepare()
                    videoPlayer?.start()
                    loadState = RequestState.Success
                }
                logger.fInfo { "Live stream loaded successfully" }
            }.onFailure { e ->
                logger.fError { "Failed to load live stream: ${e.message}" }
                withContext(Dispatchers.Main) {
                    loadState = RequestState.Failed
                    errorMessage = "加载直播流失败: ${e.message}"
                }
            }
        }
    }

    /**
     * 启动直播弹幕
     */
    fun startLiveDanmaku(roomId: Int) {
        if (roomId <= 0) {
            logger.fWarn { "Invalid room id: $roomId" }
            return
        }

        logger.fInfo { "Starting live danmaku for room $roomId" }
        stopLiveDanmaku()

        // 连接 WebSocket
        liveWebSocket = viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                logger.fInfo { "Getting live danmaku info for room $roomId" }
                val danmuInfo = BiliLiveHttpApi.getLiveDanmuInfo(roomId, sessData = Prefs.sessData)
                logger.fInfo { "Danmaku info response: code=${danmuInfo.code}, message=${danmuInfo.message}" }

                if (danmuInfo.data == null) {
                    logger.fError { "Failed to get danmaku info: data is null" }
                    return@launch
                }

                logger.fInfo { "Getting live room play info for room $roomId" }
                val playInfo = BiliLiveHttpApi.getLiveRoomPlayInfo(roomId)
                logger.fInfo { "Play info response: code=${playInfo.code}, message=${playInfo.message}" }

                val realRoomId = playInfo.data?.roomId
                if (realRoomId == null) {
                    logger.fError { "Failed to get real room id: data.roomId is null" }
                    return@launch
                }

                logger.fInfo { "Real room id: $realRoomId, starting WebSocket connection" }

                // 创建 Channel 和单消费者协程，避免每条弹幕创建一个协程
                val channel = Channel<DanmakuEvent>(capacity = Channel.BUFFERED)
                liveDanmakuChannel = channel
                liveDanmakuConsumer = viewModelScope.launch(Dispatchers.IO) {
                    for (event in channel) {
                        addLiveDanmaku(event)
                    }
                }

                logger.fInfo { "Connecting to live danmaku WebSocket for room $realRoomId" }
                // 使用预取的 token 和 hostList，避免 connectLiveEvent 内部重复调用 API
                liveWebSocketInner = LiveDataWebSocket.connectLiveEvent(
                    realRoomId = realRoomId,
                    token = danmuInfo.data!!.token,
                    hostList = danmuInfo.data!!.hostList,
                    uid = Prefs.uid,
                ) { event ->
                    when (event) {
                        is DanmakuEvent -> channel.trySend(event)
                        is WatchedChangeEvent -> {
                            val now = System.currentTimeMillis()
                            if (now - lastWatchedUpdateTime >= 10_000 && (watchedText.contains("播放") || watchedText.isEmpty())) {
                                watchedText = event.watchedText
                                lastWatchedUpdateTime = now
                            }
                        }
                        is PopularityChangeEvent -> {
                            val now = System.currentTimeMillis()
                            if (now - lastWatchedUpdateTime >= 10_000 && watchedText.contains("人气")) {
                                watchedText = event.popularityText
                                lastWatchedUpdateTime = now
                            }
                        }
                        is OnlineRankCountEvent -> {
                            val now = System.currentTimeMillis()
                            if (now - lastOnlineCountUpdateTime >= 5_000 || lastOnlineCountUpdateTime == 0L) {
                                liveOnlineCount = "${event.count}人在线"
                                lastOnlineCountUpdateTime = now
                            }
                        }
                    }
                }
            }.onFailure { e ->
                logger.fError { "Live danmaku connection failed: ${e.message}\n${e.stackTraceToString()}" }
            }
        }

        // 启动批量发送定时任务
        startLiveDanmakuFlushJob()

        logger.fInfo { "Live danmaku started" }
    }

    /**
     * 停止直播弹幕
     */
    fun stopLiveDanmaku() {
        logger.fInfo { "Stopping live danmaku" }

        // 停止批量发送定时任务
        stopLiveDanmakuFlushJob()

        liveWebSocket?.cancel()
        liveWebSocket = null
        liveWebSocketInner?.cancel()
        liveWebSocketInner = null
        liveDanmakuChannel?.close()
        liveDanmakuChannel = null
        liveDanmakuConsumer?.cancel()
        liveDanmakuConsumer = null

        // 清空缓冲区
        synchronized(liveDanmakuBuffer) {
            liveDanmakuBuffer.clear()
        }

        viewModelScope.launch(Dispatchers.Main) {
            try {
                danmakuMasks.clear()
                danmakuView?.clearDanmakus()
            } catch (e: Exception) {
                logger.fError { "Error releasing danmaku player: ${e.message}" }
            }
        }

        logger.fInfo { "Live danmaku stopped" }
    }

    /**
     * 添加直播弹幕到缓冲区
     */
    private fun addLiveDanmaku(event: DanmakuEvent) {
        // 添加用户等级过滤逻辑
        if (event.userLevel < currentLiveDanmakuFilterLevel) {
            logger.fInfo { "Filtered live danmaku: userLevel=${event.userLevel} < $currentLiveDanmakuFilterLevel" }
            return
        }

        val danmakuItem = Danmaku(
            dmid = System.currentTimeMillis(),
            positionMs = 0,
            text = event.content,
            type = event.mode,
            textSize = event.fontSize,
            color = 0xFF000000.toInt() or (event.color and 0xFFFFFF)
        )

        // 添加到缓冲区
        synchronized(liveDanmakuBuffer) {
            liveDanmakuBuffer.add(danmakuItem)
        }
    }

    /**
     * 启动直播弹幕批量发送定时任务
     */
    private fun startLiveDanmakuFlushJob() {
        liveDanmakuFlushJob?.cancel()
        liveDanmakuFlushJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(1000) // 每秒执行一次
                flushLiveDanmakuBuffer()
            }
        }
    }

    /**
     * 批量发送缓冲区中的弹幕
     */
    private suspend fun flushLiveDanmakuBuffer() {
        val itemsToSend = synchronized(liveDanmakuBuffer) {
            if (liveDanmakuBuffer.isEmpty()) return@synchronized emptyList()
            val items = liveDanmakuBuffer.toList()
            liveDanmakuBuffer.clear()
            items
        }

        if (itemsToSend.isEmpty()) return

        val view = danmakuView ?: return

        // positionMs 必须与 positionProvider 使用同一时钟（SystemClock.elapsedRealtime），
        // 否则 DanmakuEngine 的 dropIfLagging 会丢弃"过时"弹幕
        val nowMs = android.os.SystemClock.elapsedRealtime().toInt()
        val updatedItems = itemsToSend.map {
            it.copy(positionMs = nowMs)
        }

        withContext(Dispatchers.Main) {
            view.appendDanmakus(updatedItems, maxItems = 5000, alreadySorted = true)
        }
    }

    /**
     * 停止直播弹幕批量发送定时任务
     */
    private fun stopLiveDanmakuFlushJob() {
        liveDanmakuFlushJob?.cancel()
        liveDanmakuFlushJob = null
    }
}
