package dev.aaa1115910.bv.player.tv

import android.os.CountDownTimer
import android.os.SystemClock
import dev.aaa1115910.bv.player.tv.controller.EmptyUserActionContent
import dev.aaa1115910.bv.player.tv.controller.UserActionContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import dev.aaa1115910.bv.player.danmaku.DanmakuConfig
import dev.aaa1115910.bv.player.danmaku.DanmakuView
import dev.aaa1115910.biliapi.http.entity.video.ClipType
import dev.aaa1115910.biliapi.entity.video.Subtitle
import dev.aaa1115910.bv.player.AbstractVideoPlayer
import dev.aaa1115910.bv.player.BvVideoPlayer
import dev.aaa1115910.bv.player.VideoPlayerListener
import dev.aaa1115910.bv.player.entity.Audio
import dev.aaa1115910.bv.player.entity.DanmakuType
import dev.aaa1115910.bv.player.entity.LiveCodec
import dev.aaa1115910.bv.player.entity.LocalVideoPlayerClockState
import dev.aaa1115910.bv.player.entity.LocalVideoPlayerConfigData
import dev.aaa1115910.bv.player.entity.LocalVideoPlayerDanmakuMasksData
import dev.aaa1115910.bv.player.entity.LocalVideoPlayerDebugInfoData
import dev.aaa1115910.bv.player.entity.LocalVideoPlayerHistoryData
import dev.aaa1115910.bv.player.entity.LocalVideoPlayerLoadStateData
import dev.aaa1115910.bv.player.entity.LocalVideoPlayerPaymentData
import dev.aaa1115910.bv.player.entity.LocalVideoPlayerSeekState
import dev.aaa1115910.bv.player.entity.LocalVideoPlayerStateData
import dev.aaa1115910.bv.player.entity.LocalVideoPlayerVideoInfoData
import dev.aaa1115910.bv.player.entity.PlayMode
import dev.aaa1115910.bv.player.entity.RequestState
import dev.aaa1115910.bv.player.entity.Resolution
import dev.aaa1115910.bv.player.entity.VideoAspectRatio
import dev.aaa1115910.bv.player.entity.VideoCodec
import dev.aaa1115910.bv.player.entity.VideoListItem
import dev.aaa1115910.bv.player.entity.VideoRotation
import dev.aaa1115910.bv.player.entity.VideoPlayerClockState
import dev.aaa1115910.bv.player.entity.VideoPlayerDebugInfoData
import dev.aaa1115910.bv.player.entity.VideoPlayerSeekState
import dev.aaa1115910.bv.player.entity.VideoPlayerStateData
import dev.aaa1115910.bv.player.entity.DefaultStartPosition
import dev.aaa1115910.bv.player.tv.controller.SkipEdTip
import dev.aaa1115910.bv.player.tv.controller.SkipOpTip
import dev.aaa1115910.bv.player.tv.controller.VideoPlayerController
import dev.aaa1115910.bv.util.countDownTimer
import dev.aaa1115910.bv.util.fInfo
import dev.aaa1115910.bv.util.formatHourMinSec
import dev.aaa1115910.bv.util.ifElse
import dev.aaa1115910.bv.util.requestFocus
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import kotlin.math.max

private const val HEARTBEAT_MIN_INTERVAL_MS = 14_000L

@Composable
fun BvPlayer(
    modifier: Modifier = Modifier,
    videoPlayer: AbstractVideoPlayer,
    playerSeekForwardStep: Int = 10,
    playerSeekBackwardStep: Int = 5,
    showBottomProgressBar: Boolean = false,
    useTextureViewFixPortraitVideo: Boolean = false,
    onSendHeartbeat: suspend (Int) -> Unit,
    onClearBackToHistoryData: () -> Unit,
    onLoadNextVideo: (Boolean) -> Unit,
    onExit: () -> Unit,
    onLoadNewVideo: (VideoListItem) -> Unit,
    onResolutionChange: (Resolution, afterChange: suspend () -> Unit) -> Unit,
    onCodecChange: (VideoCodec, afterChange: suspend () -> Unit) -> Unit,
    onAspectRatioChange: (VideoAspectRatio) -> Unit,
    onRotationChange: (VideoRotation) -> Unit,
    onPlaySpeedChange: (Float) -> Unit,
    onAudioChange: (Audio, afterChange: suspend () -> Unit) -> Unit,
    onLiveQualityChange: (Int) -> Unit = {},
    onLiveCodecChange: (LiveCodec) -> Unit = {},
    onDanmakuSwitchChange: (List<DanmakuType>) -> Unit,
    onDanmakuSizeChange: (Float) -> Unit,
    onDanmakuOpacityChange: (Float) -> Unit,
    onDanmakuAreaChange: (Float) -> Unit,
    onDanmakuMaskChange: (Boolean) -> Unit,
    onDanmakuRollingDurationFactorChange: (Float) -> Unit,
    onDanmakuFilterLevelChange: (Int) -> Unit = {},
    onSubtitleChange: (Subtitle) -> Unit,
    onSubtitleSizeChange: (TextUnit) -> Unit,
    onSubtitleBackgroundOpacityChange: (Float) -> Unit,
    onSubtitleBottomPadding: (Dp) -> Unit,
    onPlayModeChange: (PlayMode) -> Unit,
    onDebugInfoChange: (Boolean) -> Unit = {},
    onToggleRelatedVideos: (Boolean) -> Unit = {},
    autoOpenPlayListOnVideoEnd: Boolean = false,
    onOpenUpSpace: () -> Unit = {},
    onShowDanmakuChange: (Boolean) -> Unit = {},
    onRefreshVideo: () -> Unit = {},
    onLiveRetry: () -> Unit = {},
    onShowComment: () -> Unit = {},
    onShowDescription: () -> Unit = {},
    userActionContent: UserActionContent = EmptyUserActionContent,
    onViewerCountTipCanShowChanged: (Boolean) -> Unit = {},
    viewerCountText: String = "",
    danmakuView: DanmakuView,
) {
//    // 调试重组次数: AtomicInteger，不被 Compose 追踪，只记录真实由外部状态引起的重组次数。
//    val recomposeCounter = remember { java.util.concurrent.atomic.AtomicInteger(0) }
//    SideEffect {
//        val value = recomposeCounter.incrementAndGet()
//        println("Recompose(BvPlayer): $value")
//    }

    val scope = rememberCoroutineScope()
    val logger = KotlinLogging.logger("BvPlayer")
    //val tvVideoPlayerData = LocalTvVideoPlayerData.current
    val videoPlayerConfigData = LocalVideoPlayerConfigData.current
    val currentConfigData by rememberUpdatedState(videoPlayerConfigData)
    val videoPlayerDanmakuMaskData = LocalVideoPlayerDanmakuMasksData.current
    val videoPlayerHistoryData = LocalVideoPlayerHistoryData.current
    val videoPlayerLoadStateData = LocalVideoPlayerLoadStateData.current
    val videoPlayerPaymentData = LocalVideoPlayerPaymentData.current
    val videoPlayerVideoInfoData = LocalVideoPlayerVideoInfoData.current

    val focusRequester = remember { FocusRequester() }

    var showBackToHistory by remember { mutableStateOf(false) }
    var isPlaying by rememberSaveable { mutableStateOf(false) }
    var isError by remember { mutableStateOf(false) }
    var isBuffering by remember { mutableStateOf(false) }
    var exception by remember { mutableStateOf<Exception?>(null) }
    //var proxyArea by remember { mutableStateOf(ProxyArea.MainLand) }

    var danmakuConfig by remember { mutableStateOf(DanmakuConfig()) }

    val seekState = remember { VideoPlayerSeekState() }
    var currentVideoAspectRatio by remember { mutableStateOf(videoPlayerConfigData.currentVideoAspectRatio) }
    var currentVideoRotation by remember { mutableStateOf(videoPlayerConfigData.currentVideoRotation) }
    var currentPlaySpeed by remember { mutableFloatStateOf(videoPlayerConfigData.currentVideoSpeed) }
    var aspectRatioValue by remember { mutableFloatStateOf(16f / 9f) }
    var lastPlayed by remember { mutableLongStateOf(0L) }
    var openPlayListRequestToken by remember { mutableLongStateOf(0L) }
    var defaultAspectRatio by remember { mutableFloatStateOf(16 / 9f) }
    var showInfoProvider: () -> Boolean by remember { mutableStateOf({ false }) }
    val lastHeartbeatReportAtMs = remember { java.util.concurrent.atomic.AtomicLong(0L) }
    val lastHeartbeatTime = remember { java.util.concurrent.atomic.AtomicInteger(Int.MIN_VALUE) }

    val clockState = remember { VideoPlayerClockState() }

    var clockRefreshTimer: CountDownTimer? by remember { mutableStateOf(null) }
    var hideBackToHistoryTimer: CountDownTimer? by remember { mutableStateOf(null) }

    // 跳过片头片尾相关状态
    var showSkipOpTip by remember { mutableStateOf(false) }
    var showSkipEdTip by remember { mutableStateOf(false) }
    var skipOpTipText by remember { mutableStateOf("即将跳过片头") }
    var skipEdTipText by remember { mutableStateOf("即将跳过片尾") }
    var processedClipIndices by remember { mutableStateOf(setOf<Int>()) }

    // 使用 rememberUpdatedState 来跟踪 clipInfoList 和 skipPgcIntroOutro 的最新值
    // 这样可以在非 Composable 上下文（定时器回调）中读取到最新值
    val currentClipInfoList by rememberUpdatedState(videoPlayerConfigData.clipInfoList)
    val currentSkipPgcIntroOutro by rememberUpdatedState(videoPlayerConfigData.skipPgcIntroOutro)

    // 当 clipInfoList 变化时，重置已处理的 clip 索引
    // 这确保了切换到新视频时，跳过片头/片尾功能能够正常工作
    LaunchedEffect(videoPlayerConfigData.clipInfoList) {
        processedClipIndices = emptySet()
    }

    // 跳过片头片尾检测任务
    val checkSkipTask: (Long) -> Unit = { positionMs ->
        // 使用 rememberUpdatedState 获取最新值
        if (currentSkipPgcIntroOutro && currentClipInfoList.isNotEmpty() && isPlaying) {
            val currentPosition = (positionMs / 1000).toInt()  // 毫秒转秒
            currentClipInfoList.forEachIndexed { index, clipInfo ->
                // 跳过已处理的 clip
                if (index in processedClipIndices) return@forEachIndexed

                when (clipInfo.clipType) {
                    ClipType.CLIP_TYPE_OP -> {
                        // 检测是否到达片头开始时间
                        val inRange = currentPosition >= clipInfo.start && currentPosition < clipInfo.end
                        if (inRange) {
                            scope.launch(Dispatchers.Main) {
                                skipOpTipText = clipInfo.toastText.ifBlank { "即将跳过片头" }
                                showSkipOpTip = true
                                // 显示提示后短暂延迟再跳转
                                delay(1500)
                                videoPlayer.seekTo(clipInfo.end * 1000L)
                                danmakuView.notifySeek(clipInfo.end * 1000L)
                                videoPlayer.start()
                                showSkipOpTip = false
                            }
                            processedClipIndices = processedClipIndices + index
                        }
                    }
                    ClipType.CLIP_TYPE_ED -> {
                        // 检测是否到达片尾开始时间
                        val inRange = currentPosition >= clipInfo.start && currentPosition < clipInfo.end
                        if (inRange) {
                            scope.launch(Dispatchers.Main) {
                                skipEdTipText = clipInfo.toastText.ifBlank { "即将跳过片尾" }
                                showSkipEdTip = true
                                delay(1500)
                                videoPlayer.seekTo(clipInfo.end * 1000L)
                                danmakuView.notifySeek(clipInfo.end * 1000L)
                                videoPlayer.start()
                                showSkipEdTip = false
                            }
                            processedClipIndices = processedClipIndices + index
                        }
                    }
                    else -> {}  // 忽略其他类型
                }
            }
        }
    }


    val applyDanmakuConfig: (DanmakuConfig) -> Unit = { newConfig ->
        danmakuConfig = newConfig
        danmakuView.setConfig(newConfig)
        logger.info { "Update danmaku config: $newConfig" }
    }

    val syncDanmakuConfig: () -> Unit = {
        val danmakuTypes = videoPlayerConfigData.currentDanmakuEnabledList
        val allowAll = danmakuTypes.contains(DanmakuType.All)
        val filterLevel = if (videoPlayerConfigData.isLive) videoPlayerConfigData.currentLiveDanmakuFilterLevel else videoPlayerConfigData.currentDanmakuFilterLevel
        val factor = videoPlayerConfigData.currentDanmakuRollingDurationFactor
        val durationMultiplier = 2f - factor
        applyDanmakuConfig(danmakuConfig.copy(
            enabled = videoPlayerConfigData.showDanmaku,
            textSizeScale = (videoPlayerConfigData.currentDanmakuScale * 100).toInt(),
            allowScroll = allowAll || danmakuTypes.contains(DanmakuType.Rolling),
            allowTop = allowAll || danmakuTypes.contains(DanmakuType.Top),
            allowBottom = allowAll || danmakuTypes.contains(DanmakuType.Bottom),
            minLevel = filterLevel,
            durationMultiplier = durationMultiplier,
            opacity = currentConfigData.currentDanmakuOpacity,
            area = currentConfigData.currentDanmakuArea,
        ))
    }

    val updateDanmakuConfigTypeFilter: () -> Unit = {
        val danmakuTypes = videoPlayerConfigData.currentDanmakuEnabledList
        val allowAll = danmakuTypes.contains(DanmakuType.All)
        applyDanmakuConfig(danmakuConfig.copy(
            allowScroll = allowAll || danmakuTypes.contains(DanmakuType.Rolling),
            allowTop = allowAll || danmakuTypes.contains(DanmakuType.Top),
            allowBottom = allowAll || danmakuTypes.contains(DanmakuType.Bottom),
        ))
    }

    val updateVideoAspectRatio: () -> Unit = {
        aspectRatioValue = currentVideoAspectRatio.resolveAspectRatio(defaultAspectRatio)
        logger.info {
            "Update video player aspectRatio: type=$currentVideoAspectRatio, ratio=$aspectRatioValue"
        }
    }

    val sendHeartbeat: (CoroutineScope, Boolean) -> Unit = heartbeat@{ launchScope, fromPlaybackEnd ->
        // 在主线程直接读取播放器状态，避免 IO→Main→IO 双重分发
        val currentTime = (videoPlayer.currentPosition.coerceAtLeast(0L) / 1000).toInt()
        val totalTime = (videoPlayer.duration.coerceAtLeast(0L) / 1000).toInt()

        val time = if (totalTime == 0) {
            -2 // 无法正常播放
        } else if (currentTime >= totalTime - 1) {
            if (videoPlayerPaymentData.needPay) {
                currentTime // 试看结束不能按完整播放上报 -1
            } else {
                -1 // 播放完后上报的时间应为 -1
            }
        } else {
            currentTime // 播放中上报当前时间
        }
        if (time <= -2) return@heartbeat

        val nowElapsedMs = SystemClock.elapsedRealtime()
        val previousHeartbeatAtMs = lastHeartbeatReportAtMs.get()
        val previousHeartbeatTime = lastHeartbeatTime.get()
        val hasReachedMinInterval =
            previousHeartbeatAtMs == 0L || nowElapsedMs - previousHeartbeatAtMs >= HEARTBEAT_MIN_INTERVAL_MS
        val hasProgressChanged = previousHeartbeatTime != time
        val canReport = if (fromPlaybackEnd) {
            hasProgressChanged
        } else {
            hasReachedMinInterval && hasProgressChanged
        }

        if (!canReport) {
            logger.debug {
                "Skip heartbeat: time=$time, lastTime=$previousHeartbeatTime, elapsed=${nowElapsedMs - previousHeartbeatAtMs}ms, fromPlaybackEnd=$fromPlaybackEnd"
            }
            return@heartbeat
        }

        lastHeartbeatReportAtMs.set(nowElapsedMs)
        lastHeartbeatTime.set(time)
        launchScope.launch(Dispatchers.IO) {
            onSendHeartbeat(time)
        }
    }

    // updateBackToHistory() 中使用 videoPlayerHistoryData.lastPlayed 无法获取到新值
    LaunchedEffect(videoPlayerHistoryData.lastPlayed) {
        lastPlayed = videoPlayerHistoryData.lastPlayed.toLong()
    }

    LaunchedEffect(videoPlayerVideoInfoData.width, videoPlayerVideoInfoData.height) {
        val newAspectRatio =
            videoPlayerVideoInfoData.width / videoPlayerVideoInfoData.height.toFloat()
        defaultAspectRatio = newAspectRatio.takeIf { it > 0 } ?: (16 / 9f)
        updateVideoAspectRatio()
    }

    val updateBackToHistory: () -> Unit = {
        // 此处使用 videoPlayerHistoryData.lastPlayed 无法获取到新值
        //if (videoPlayerHistoryData.lastPlayed > 0 && hideBackToHistoryTimer == null) {
        if (lastPlayed > 0 && hideBackToHistoryTimer == null) {
            logger.info { "show showBackToHistory: ${videoPlayerHistoryData.lastPlayed}" }
            scope.launch(Dispatchers.Main) {
                showBackToHistory = true
                hideBackToHistoryTimer = countDownTimer(5000, 1000, "hideBackToHistoryTimer") {
                    scope.launch(Dispatchers.Main) {
                        showBackToHistory = false
                        hideBackToHistoryTimer = null
                        //playerViewModel.lastPlayed = 0
                        onClearBackToHistoryData()
                    }
                }
            }
        }
    }

    val videoPlayerListener = object : VideoPlayerListener {
        override fun onError(error: Exception) {
            logger.info { "onError: $error" }
            if (videoPlayerConfigData.isLive) {
                // 直播模式：自动重连，不立即显示错误 UI（参考 wiliwili 的 retryRequestData）
                logger.info { "Live mode: triggering auto retry" }
                scope.launch(Dispatchers.Main) {
                    isBuffering = true  // 显示缓冲状态代替错误状态
                }
                onLiveRetry()
            } else {
                scope.launch(Dispatchers.Main) {
                    isError = true
                    exception = error.cause as Exception?
                }
            }
        }

        override fun onReady() {
            logger.info { "onReady" }
            scope.launch(Dispatchers.Main) {
                isError = false
                exception = null
                syncDanmakuConfig()
                updateVideoAspectRatio()

                //reset default play speed
                onPlaySpeedChange(currentPlaySpeed)
                logger.info { "Reset default play speed: $currentPlaySpeed" }
                videoPlayer.speed = currentPlaySpeed
            }
        }

        override fun onPlay() {
            logger.info { "onPlay" }
            scope.launch(Dispatchers.Main) {
                isPlaying = true
                isBuffering = false
                danmakuView.play()
                updateBackToHistory()
            }
        }

        override fun onPause() {
            logger.info { "onPause" }
            scope.launch(Dispatchers.Main) {
                isPlaying = false
            }
        }

        override fun onBuffering() {
            logger.info { "onBuffering" }
            scope.launch(Dispatchers.Main) {
                isBuffering = true
            }
        }

        override fun onEnd() {
            if (videoPlayerConfigData.showRelatedVideos) {
                logger.info { "onEnd: show related videos, skip auto next" }
                scope.launch(Dispatchers.Main) {
                    isPlaying = false
                }
                return
            }

            if (videoPlayerConfigData.currentPlayMode == PlayMode.SingleLoop) {
                logger.info { "onEnd: replay" }
                scope.launch(Dispatchers.Main) {
                    videoPlayer.seekTo(0)
                    danmakuView.notifySeek(0)
                    videoPlayer.start()
                }
                return
            }

            logger.info { "onEnd" }
            scope.launch(Dispatchers.Main) {
                isPlaying = false
                if (!videoPlayerConfigData.incognitoMode && !videoPlayerConfigData.isLive) sendHeartbeat(scope, true)
                if (!showInfoProvider()) {
                    if (autoOpenPlayListOnVideoEnd) {
                        openPlayListRequestToken = System.currentTimeMillis()
                    } else {
                        onLoadNextVideo(false)
                    }
                } else {
                    logger.info { "Skip auto next because info panel visible" }
                }
            }
        }

        override fun onIdle() {
            //TODO("Not yet implemented")
        }

        override fun onSeekBack(seekBackIncrementMs: Long) {
            danmakuView.notifySeek(seekState.position)
        }

        override fun onSeekForward(seekForwardIncrementMs: Long) {
            danmakuView.notifySeek(seekState.position)
        }

        override fun onVideoSizeChanged(width: Int, height: Int) {
            logger.info { "onVideoSizeChanged: ${width}x${height}" }
            if (width > 0 && height > 0) {
                scope.launch(Dispatchers.Main) {
                    val newDefaultAspectRatio =width / height.toFloat()
                    if (newDefaultAspectRatio != defaultAspectRatio ) {
                        defaultAspectRatio = newDefaultAspectRatio
                        updateVideoAspectRatio()
                    }
                }
            }
        }
    }

    // 进度轮询：播放时每 200ms 更新进度、检查跳过片头片尾
    LaunchedEffect(isPlaying, videoPlayerConfigData.isLive) {
        while (isPlaying && !videoPlayerConfigData.isLive) {
            val pos = videoPlayer.currentPosition.coerceAtLeast(0L)
            val dur = videoPlayer.duration.coerceAtLeast(0L)
            val buf = videoPlayer.bufferedPercentage.coerceIn(0, 100)

            if (seekState.position != pos) seekState.position = pos
            if (seekState.duration != dur) seekState.duration = dur
            if (seekState.bufferedPercentage != buf) seekState.bufferedPercentage = buf

            checkSkipTask(pos)

            delay(200)
        }
    }

    LaunchedEffect(videoPlayerConfigData.currentDanmakuMask, danmakuView) {
        danmakuView.setMaskEnabled(videoPlayerConfigData.currentDanmakuMask)
    }

    LaunchedEffect(videoPlayerDanmakuMaskData.danmakuMasks, danmakuView) {
        snapshotFlow { videoPlayerDanmakuMaskData.danmakuMasks.toList() }
            .collect { danmakuView.setMaskSegments(it) }
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus(scope)
    }



    LaunchedEffect(videoPlayerLoadStateData.loadState) {
        when (videoPlayerLoadStateData.loadState) {
            RequestState.Ready -> {}
            RequestState.Doing -> {}
            RequestState.Done -> {}
            RequestState.Success -> {}
            RequestState.Failed -> {
                exception = Exception(videoPlayerLoadStateData.errorMessage)
                isError = true
            }
        }
    }

    // 心跳定时器：使用 LaunchedEffect 替代 java.util.Timer，避免后台线程与主线程双重分发导致 ANR
    if (!videoPlayerConfigData.incognitoMode && !videoPlayerConfigData.isLive) {
        DisposableEffect(Unit) {
            val job = scope.launch {
                delay(5000)
                while (isActive) {
                    if (videoPlayer.isPlaying) sendHeartbeat(scope, false)
                    delay(15000)
                }
            }
            onDispose {
                job.cancel()
                println("心跳定时器 cancel")
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            videoPlayer.release()
            danmakuView.release()
        }
    }

    DisposableEffect(showInfoProvider()) {
        clockRefreshTimer?.cancel()
        if (showInfoProvider()) {
            clockRefreshTimer = countDownTimer(
                millisInFuture = Long.MAX_VALUE,
                countDownInterval = 1000,
                tag = "clockRefreshTimer",
                showLogs = false,
                onTick = {
                    val calendar = Calendar.getInstance()
                    val hour = calendar.get(Calendar.HOUR_OF_DAY)
                    val minute = calendar.get(Calendar.MINUTE)
                    val second = calendar.get(Calendar.SECOND)
                    if (clockState.hour != hour) clockState.hour = hour
                    if (clockState.minute != minute) clockState.minute = minute
                    if (clockState.second != second) clockState.second = second
                }
            )
        }
        onDispose { clockRefreshTimer?.cancel() }
    }

    val animatedAspectRatio by animateFloatAsState(
        targetValue = aspectRatioValue,
        animationSpec = tween(),
        label = "animatedAspectRatio"
    )

    val videoPlayerModifier = when (currentVideoAspectRatio) {
        VideoAspectRatio.EqualWidth -> Modifier
            .fillMaxWidth()
            .aspectRatio(animatedAspectRatio)

        VideoAspectRatio.EqualHeight -> Modifier
            .fillMaxHeight()
            .aspectRatio(animatedAspectRatio, matchHeightConstraintsFirst = true)

        VideoAspectRatio.Stretch -> Modifier.fillMaxSize()

        else -> Modifier.aspectRatio(animatedAspectRatio)
    }

    CompositionLocalProvider(
        LocalVideoPlayerSeekState provides seekState,
        LocalVideoPlayerClockState provides clockState,
        //LocalVideoPlayerHistoryData provides LocalVideoPlayerHistoryData.current.copy(
        //    showBackToHistory = showBackToHistory
        //),
        //LocalVideoPlayerHistoryData provides VideoPlayerHistoryData(
        //    lastPlayed = videoPlayerHistoryData.lastPlayed,
        //    showBackToHistory = showBackToHistory
        //),
        LocalVideoPlayerStateData provides VideoPlayerStateData(
            isPlaying = isPlaying,
            isBuffering = isBuffering,
            isError = isError,
            exception = exception,
            showBackToHistory = showBackToHistory
        ),
        LocalVideoPlayerDebugInfoData provides VideoPlayerDebugInfoData(
            debugInfo = videoPlayer.debugInfo
        ),
    ) {
        VideoPlayerController(
            modifier = modifier
                .focusRequester(focusRequester)
                .fillMaxSize(),
            videoPlayer = videoPlayer,
            playerSeekForwardStep = playerSeekForwardStep,
            playerSeekBackwardStep = playerSeekBackwardStep,
            showBottomProgressBar = showBottomProgressBar,
            showRelatedVideos = videoPlayerConfigData.showRelatedVideos,
            onToggleRelatedVideos = onToggleRelatedVideos,
            registerShowInfoProvider = { provider -> showInfoProvider = provider },
            onViewerCountTipCanShowChanged = onViewerCountTipCanShowChanged,
            viewerCountText = viewerCountText,

            onPlay = { videoPlayer.start() },
            onPause = {
                videoPlayer.pause()
                if (!videoPlayerConfigData.incognitoMode && !videoPlayerConfigData.isLive) sendHeartbeat(scope, false)
            },
            onExit = {
                videoPlayer.pause()
                if (!videoPlayerConfigData.incognitoMode && !videoPlayerConfigData.isLive) sendHeartbeat(scope, true)
                onExit()
            },
            onGoTime = {
                videoPlayer.seekTo(it)
                danmakuView.notifySeek(it)
            },
            onSeekToVideoEnd = {
                videoPlayer.pause()
                scope.launch(Dispatchers.Main) {
                    isPlaying = false
                    if (!videoPlayerConfigData.incognitoMode && !videoPlayerConfigData.isLive) sendHeartbeat(scope, true)
                    if (autoOpenPlayListOnVideoEnd) {
                        openPlayListRequestToken = System.currentTimeMillis()
                    } else {
                        onLoadNextVideo(true)
                    }
                }
            },
            onBackToHistory = {
                val time = if (videoPlayerConfigData.defaultStartPosition == DefaultStartPosition.History) {
                    0L
                } else {
                    videoPlayerHistoryData.lastPlayed.toLong()
                }
                logger.fInfo { "Back to history/beginning: ${time.formatHourMinSec()}" }
                videoPlayer.seekTo(time)
                danmakuView.notifySeek(time)
                //playerViewModel.lastPlayed = 0
                onClearBackToHistoryData()
                showBackToHistory = false
                hideBackToHistoryTimer?.cancel()
                hideBackToHistoryTimer = null
            },
            onPlayNewVideo = {
                if (!videoPlayerConfigData.incognitoMode && !videoPlayerConfigData.isLive) sendHeartbeat(scope, false)
                //playerViewModel.partTitle = it.title
                //playerViewModel.loadPlayUrl(
                //    avid = it.aid,
                //    cid = it.cid,
                //    epid = it.epid,
                //    seasonId = it.seasonId,
                //    continuePlayNext = true
                //)
                onLoadNewVideo(it)
            },
            onResolutionChange = { resolution ->
                videoPlayer.pause()
                val current = videoPlayer.currentPosition
                onResolutionChange(resolution) {
                    //scope.launch(Dispatchers.Default) {
                    //    playerViewModel.updateAvailableCodec()
                    //    playerViewModel.playQuality(qualityId)
                    withContext(Dispatchers.Main) {
                        videoPlayer.seekTo(current)
                        videoPlayer.start()
                    }
                    //}
                }
                //playerViewModel.currentQuality = qualityId
            },
            onCodecChange = { videoCodec ->
                videoPlayer.pause()
                val current = videoPlayer.currentPosition
                onCodecChange(videoCodec) {
                    withContext(Dispatchers.Main) {
                        videoPlayer.seekTo(current)
                        videoPlayer.start()
                    }
                }
            },
            onAspectRatioChange = { aspectRadio ->
                currentVideoAspectRatio = aspectRadio
                onAspectRatioChange(currentVideoAspectRatio)
                updateVideoAspectRatio()
            },
            onRotationChange = { rotation ->
//                if (videoPlayerConfigData.currentResolution > Resolution.R1080P60) {
//                    // 4k及以上的视频旋转后画面很卡、hdr、杜比世界的视频旋转后色彩和对比度不对， 所以先切换到<=R1080P60
//                    val tempList =
//                        videoPlayerConfigData.availableResolutions.sortedByDescending { it.code }
//                    val currentQuality = tempList.firstOrNull { it.code <= Resolution.R1080P60.code }
//                        ?: tempList.last()
//                    if (videoPlayerConfigData.currentResolution != currentQuality) {
//                        videoPlayer.pause()
//                        val current = videoPlayer.currentPosition
//                        onResolutionChange(currentQuality) {
//                            withContext(Dispatchers.Main) {
//                                videoPlayer.seekTo(current)
//                                videoPlayer.start()
//                            }
//                        }
//                    }
//                }

                currentVideoRotation = rotation
                onRotationChange(rotation)
            },
            onPlaySpeedChange = { speed ->
                logger.info { "Set default play speed: $speed" }
                currentPlaySpeed = speed
                onPlaySpeedChange(speed)
                videoPlayer.speed = speed
            },
            onAudioChange = { audio ->
                videoPlayer.pause()
                val current = videoPlayer.currentPosition
                onAudioChange(audio) {
                    withContext(Dispatchers.Main) {
                        videoPlayer.seekTo(current)
                        videoPlayer.start()
                    }
                }
            },
            onLiveQualityChange = onLiveQualityChange,
            onLiveCodecChange = onLiveCodecChange,
            onDanmakuSwitchChange = { enabledDanmakuTypes ->
                logger.info { "On enabled danmaku type change: $enabledDanmakuTypes" }
                onDanmakuSwitchChange(enabledDanmakuTypes)
                updateDanmakuConfigTypeFilter()
            },
            onDanmakuSizeChange = { scale ->
                logger.info { "On danmaku scale change: $scale" }
                onDanmakuSizeChange(scale)
                applyDanmakuConfig(danmakuConfig.copy(textSizeScale = (scale * 100).toInt()))
            },
            onDanmakuOpacityChange = { opacity ->
                logger.info { "On danmaku opacity change: $opacity" }
                onDanmakuOpacityChange(opacity)
                applyDanmakuConfig(danmakuConfig.copy(opacity = opacity))
            },
            onDanmakuAreaChange = { area ->
                logger.info { "On danmaku area change: $area" }
                onDanmakuAreaChange(area)
                applyDanmakuConfig(danmakuConfig.copy(area = area))
            },
            onDanmakuMaskChange = { mask ->
                logger.info { "On danmaku mask change: $mask" }
                onDanmakuMaskChange(mask)
            },
            onDanmakuFilterLevelChange = { filterLevel ->
                logger.info { "On danmaku filter level change: $filterLevel" }
                applyDanmakuConfig(danmakuConfig.copy(minLevel = filterLevel))
                onDanmakuFilterLevelChange(filterLevel)
            },
            onDanmakuRollingDurationFactorChange = { factor ->
                logger.info { "On danmaku rolling duration factor change: $factor" }
                onDanmakuRollingDurationFactorChange(factor)
                val durationMultiplier = 2f - factor
                applyDanmakuConfig(danmakuConfig.copy(durationMultiplier = durationMultiplier))
            },
            onSubtitleChange = { subtitle ->
                onSubtitleChange(subtitle)
            },
            onSubtitleSizeChange = { size ->
                logger.info { "On subtitle font size change: $size" }
                onSubtitleSizeChange(size)
            },
            onSubtitleBackgroundOpacityChange = { opacity ->
                logger.info { "On subtitle background opacity change: $opacity" }
                onSubtitleBackgroundOpacityChange(opacity)
            },
            onSubtitleBottomPadding = { padding ->
                logger.info { "On subtitle bottom padding change: $padding" }
                onSubtitleBottomPadding(padding)
            },
            onPlayModeChange = { playMode ->
                logger.info { "On play mode change: $playMode" }
                onPlayModeChange(playMode)
            },
            onDebugInfoChange = { enabled ->
                logger.info { "On debug info change: $enabled" }
                onDebugInfoChange(enabled)
            },
            onRequestFocus = { focusRequester.requestFocus(scope) },
            onOpenUpSpace = onOpenUpSpace,
            onRefreshVideo = onRefreshVideo,
            onOpenDanmaku = {
                onShowDanmakuChange(true)
                videoPlayerConfigData.showDanmaku = true
                applyDanmakuConfig(danmakuConfig.copy(enabled = true))
            },
            onHideDanmaku = {
                onShowDanmakuChange(false)
                videoPlayerConfigData.showDanmaku = false
                applyDanmakuConfig(danmakuConfig.copy(enabled = false))
            },
            userActionContent = userActionContent,
            onLoadNextVideo = onLoadNextVideo,
            openPlayListRequestToken = openPlayListRequestToken,
            onShowComment = onShowComment,
            onShowDescription = onShowDescription
        ) {
            LaunchedEffect(Unit) {
                videoPlayer.setOptions()
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
            )

            BvVideoPlayer(
                modifier = videoPlayerModifier.align(Alignment.Center),
                videoPlayer = videoPlayer,
                playerListener = videoPlayerListener,
                rotationDegrees = currentVideoRotation.degrees,
                forceUseTextureView = useTextureViewFixPortraitVideo
            )

            // 新弹幕引擎：直接使用 AndroidView wrapping DanmakuView
            androidx.compose.ui.viewinterop.AndroidView(
                modifier = Modifier
                    .fillMaxSize()
                    .align(Alignment.TopCenter),
                factory = { _ ->
                    danmakuView.apply {
                        setPositionProvider { if(currentConfigData.isLive) SystemClock.elapsedRealtime() else videoPlayer.currentPosition.coerceAtLeast(0L) }
                        setIsPlayingProvider { videoPlayer.isPlaying }
                        setPlaybackSpeedProvider { currentPlaySpeed }
                        setMaskEnabled(videoPlayerConfigData.currentDanmakuMask)
                        setMaskSegments(videoPlayerDanmakuMaskData.danmakuMasks.toList())
                        setConfig(danmakuConfig)
                    }
                },
                update = { view ->
                    view.setVideoAspectRatio(aspectRatioValue)
                    view.setVideoAspectRatioType(currentVideoAspectRatio)
                }
            )

            // 跳过片头片尾提示
            if (showSkipOpTip) {
                SkipOpTip(
                    modifier = Modifier.align(Alignment.BottomStart),
                    show = true,
                    text = skipOpTipText
                )
            }
            if (showSkipEdTip) {
                SkipEdTip(
                    modifier = Modifier.align(Alignment.BottomStart),
                    show = true,
                    text = skipEdTipText
                )
            }
        }
    }
}
