package dev.aaa1115910.bv.player.mobile

import android.os.CountDownTimer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import dev.aaa1115910.bv.player.danmaku.DanmakuConfig
import dev.aaa1115910.bv.player.danmaku.DanmakuView
import dev.aaa1115910.biliapi.entity.danmaku.DanmakuMaskFrame
import dev.aaa1115910.bv.player.AbstractVideoPlayer
import dev.aaa1115910.bv.player.BvVideoPlayer
import dev.aaa1115910.bv.player.PlayerErrorText
import dev.aaa1115910.bv.player.VideoPlayerListener
import dev.aaa1115910.bv.player.entity.Audio
import dev.aaa1115910.bv.player.entity.AudioBalanceLevel
import dev.aaa1115910.bv.player.entity.DanmakuType
import dev.aaa1115910.bv.player.entity.LocalVideoPlayerClockData
import dev.aaa1115910.bv.player.entity.LocalVideoPlayerConfigData
import dev.aaa1115910.bv.player.entity.LocalVideoPlayerDanmakuMasksData
import dev.aaa1115910.bv.player.entity.LocalVideoPlayerDebugInfoData
import dev.aaa1115910.bv.player.entity.LocalVideoPlayerHistoryData
import dev.aaa1115910.bv.player.entity.LocalVideoPlayerLoadStateData
import dev.aaa1115910.bv.player.entity.LocalVideoPlayerLogsData
import dev.aaa1115910.bv.player.entity.LocalVideoPlayerSeekData
import dev.aaa1115910.bv.player.entity.LocalVideoPlayerStateData
import dev.aaa1115910.bv.player.entity.LocalVideoPlayerVideoInfoData
import dev.aaa1115910.bv.player.entity.PlayMode
import dev.aaa1115910.bv.player.entity.Resolution
import dev.aaa1115910.bv.player.entity.VideoAspectRatio
import dev.aaa1115910.bv.player.entity.VideoCodec
import dev.aaa1115910.bv.player.entity.VideoListItem
import dev.aaa1115910.bv.player.entity.VideoPlayerClockData
import dev.aaa1115910.bv.player.entity.VideoPlayerDebugInfoData
import dev.aaa1115910.bv.player.entity.VideoPlayerSeekData
import dev.aaa1115910.bv.player.entity.VideoPlayerStateData
import dev.aaa1115910.bv.player.mobile.controller.BvPlayerController
import dev.aaa1115910.bv.util.countDownTimer
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Calendar

@Composable
fun BvPlayer(
    modifier: Modifier = Modifier,
    isFullScreen: Boolean,
    onEnterFullScreen: () -> Unit,
    onExitFullScreen: () -> Unit,
    onBack: () -> Unit,
    onClearBackToHistoryData: () -> Unit,
    onChangeResolution: (Resolution, afterChange: suspend () -> Unit) -> Unit,
    onChangeVideoCodec: (VideoCodec, afterChange: suspend () -> Unit) -> Unit,
    onChangeAudio: (Audio, afterChange: suspend () -> Unit) -> Unit,
    onChangeSpeed: (Float) -> Unit,
    onToggleDanmaku: (Boolean) -> Unit,
    onEnabledDanmakuTypesChange: (List<DanmakuType>) -> Unit,
    onDanmakuOpacityChange: (Float) -> Unit,
    onDanmakuScaleChange: (Float) -> Unit,
    onDanmakuAreaChange: (Float) -> Unit,
    onPlayModeChange: (PlayMode) -> Unit,
    onAudioBalanceLevelChange: (AudioBalanceLevel) -> Unit,
    onLoadNextVideo: () -> Unit,
    onLoadNewVideo: (VideoListItem) -> Unit,
    videoPlayer: AbstractVideoPlayer,
    danmakuView: DanmakuView,
) {
    val logger = KotlinLogging.logger("BvPlayer")

    val videoPlayerConfigData = LocalVideoPlayerConfigData.current
    val currentConfigData by rememberUpdatedState(videoPlayerConfigData)
    // val videoPlayerDanmakuMaskData = LocalVideoPlayerDanmakuMasksData.current
    val videoPlayerHistoryData = LocalVideoPlayerHistoryData.current
    // val videoPlayerLoadStateData = LocalVideoPlayerLoadStateData.current
    // val videoPlayerLogsData = LocalVideoPlayerLogsData.current
    // val videoPlayerVideoInfoData = LocalVideoPlayerVideoInfoData.current

    var showLogs by remember { mutableStateOf(false) }
    var showBackToHistory by remember { mutableStateOf(false) }
    var isPlaying by rememberSaveable { mutableStateOf(false) }
    var isError by remember { mutableStateOf(false) }
    var isBuffering by remember { mutableStateOf(false) }
    var exception by remember { mutableStateOf<Exception?>(null) }

    var danmakuConfig by remember { mutableStateOf(DanmakuConfig()) }

    var duration by remember { mutableLongStateOf(0L) }
    var bufferedPercentage by remember { mutableStateOf(0) }
    // var currentVideoAspectRatio by remember { mutableStateOf(VideoAspectRatio.Default) }
    var currentPosition by remember { mutableLongStateOf(0L) }
    //var currentPlaySpeed by remember { mutableFloatStateOf(Prefs.defaultPlaySpeed) }
    var aspectRatio by remember { mutableFloatStateOf(16f / 9f) }
    var lastPlayed by remember { mutableLongStateOf(0L) }

    var clock: Triple<Int, Int, Int> by remember { mutableStateOf(Triple(0, 0, 0)) }

    // var hideLogsTimer: CountDownTimer? by remember { mutableStateOf(null) }
    var clockRefreshTimer: CountDownTimer? by remember { mutableStateOf(null) }
    var hideBackToHistoryTimer: CountDownTimer? by remember { mutableStateOf(null) }

    // var currentDanmakuMaskFrame: DanmakuMaskFrame? by remember { mutableStateOf(null) }


    val updatePosition = {
        currentPosition = videoPlayer.currentPosition
        duration = videoPlayer.duration
        bufferedPercentage = videoPlayer.bufferedPercentage
    }

    val applyDanmakuConfig: (DanmakuConfig) -> Unit = { newConfig ->
        danmakuConfig = newConfig
        danmakuView.setConfig(newConfig)
    }

    val initDanmakuConfig: () -> Unit = {
        val danmakuTypes = videoPlayerConfigData.currentDanmakuEnabledList
        val allowAll = danmakuTypes.contains(DanmakuType.All)
        val filterLevel = videoPlayerConfigData.currentDanmakuFilterLevel
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

    val toggleDanmakuEnabled: (Boolean) -> Unit = { enabled ->
        applyDanmakuConfig(danmakuConfig.copy(enabled = enabled))
    }

    val updateDanmakuConfig: () -> Unit = {
        applyDanmakuConfig(danmakuConfig.copy(
            textSizeScale = (videoPlayerConfigData.currentDanmakuScale * 100).toInt(),
        ))
    }

    val updateVideoAspectRatio: () -> Unit = {
        val aspectRatioValue = videoPlayer.videoWidth / videoPlayer.videoHeight.toFloat()
        aspectRatio = if (aspectRatioValue > 0) aspectRatioValue else 16 / 9f
    }

    val updateBackToHistory: () -> Unit = {
        // 此处使用 videoPlayerHistoryData.lastPlayed 无法获取到新值
        //if (videoPlayerHistoryData.lastPlayed > 0 && hideBackToHistoryTimer == null) {
        if (lastPlayed > 0 && hideBackToHistoryTimer == null) {
            logger.info { "show showBackToHistory: ${videoPlayerHistoryData.lastPlayed}" }
            showBackToHistory = true
            hideBackToHistoryTimer = countDownTimer(5000, 1000, "hideBackToHistoryTimer") {
                showBackToHistory = false
                hideBackToHistoryTimer = null
                //playerViewModel.lastPlayed = 0
                onClearBackToHistoryData()
            }
        }
    }

    val videoPlayerListener = object : VideoPlayerListener {
        override fun onError(error: Exception) {
            println("onError: $error")
            isError = true
            exception = PlayerErrorText.wrap(error)
        }

        override fun onReady() {
            logger.info { "onReady" }
            isError = false
            exception = null
            initDanmakuConfig()

            updateVideoAspectRatio()
            videoPlayer.start()

            //reset default play speed
            logger.info { "Reset default play speed: ${videoPlayerConfigData.currentVideoSpeed}" }
            videoPlayer.speed = videoPlayerConfigData.currentVideoSpeed
        }

        override fun onPlay() {
            logger.info { "onPlay" }
            isPlaying = true
            isBuffering = false
            danmakuView.play()
            updateBackToHistory()
        }

        override fun onPause() {
            logger.info { "onPause" }
            isPlaying = false
        }

        override fun onBuffering() {
            logger.info { "onBuffering" }
            isBuffering = true
        }

        override fun onEnd() {
            logger.info { "onEnd" }
            isPlaying = false
            onLoadNextVideo()
        }

        override fun onIdle() {
            logger.info { "onIdle" }
        }

        override fun onSeekBack(seekBackIncrementMs: Long) {
            danmakuView.notifySeek(currentPosition)
        }

        override fun onSeekForward(seekForwardIncrementMs: Long) {
            danmakuView.notifySeek(currentPosition)
        }

    }

    // 在组合时立即同步弹幕配置，确保在视频 onReady 之前弹幕引擎已使用正确的模式过滤
    LaunchedEffect(Unit) { initDanmakuConfig() }

    LaunchedEffect(Unit) {
        while (true) {
            updatePosition()
            delay(200)
        }
    }

    // 同步 videoPlayerHistoryData.lastPlayed 到本地变量
    LaunchedEffect(videoPlayerHistoryData.lastPlayed) {
        lastPlayed = videoPlayerHistoryData.lastPlayed.toLong()
    }

    DisposableEffect(Unit) {
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
                clock = Triple(hour, minute, second)
            }
        )
        onDispose {
            clockRefreshTimer?.cancel()
        }
    }

    CompositionLocalProvider(
        LocalVideoPlayerSeekData provides VideoPlayerSeekData(
            duration = duration,
            position = currentPosition,
            bufferedPercentage = bufferedPercentage
        ),
        LocalVideoPlayerClockData provides VideoPlayerClockData(
            hour = clock.first,
            minute = clock.second,
            second = clock.third
        ),
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
        BvPlayerController(
            modifier = modifier,
            isFullScreen = isFullScreen,
            onEnterFullScreen = onEnterFullScreen,
            onExitFullScreen = onExitFullScreen,
            onBack = onBack,
            onPlay = { videoPlayer.start() },
            onPause = { videoPlayer.pause() },
            onSeekToPosition = { position ->
                danmakuView.notifySeek(position)
                videoPlayer.seekTo(position)
            },
            onChangeResolution = {
                val currentTime = currentPosition
                onChangeResolution(it) {
                    withContext(Dispatchers.Main) {
                        videoPlayer.seekTo(currentTime)
                        videoPlayer.start()
                    }
                }
            },
            onChangeVideoCodec = {
                val currentTime = currentPosition
                onChangeVideoCodec(it) {
                    withContext(Dispatchers.Main) {
                        videoPlayer.seekTo(currentTime)
                        videoPlayer.start()
                    }
                }
            },
            onChangeAudio = {
                val currentTime = currentPosition
                onChangeAudio(it) {
                    withContext(Dispatchers.Main) {
                        videoPlayer.seekTo(currentTime)
                        videoPlayer.start()
                    }
                }
            },
            onChangeSpeed = { speed ->
                onChangeSpeed(speed)
                videoPlayer.speed = speed
            },
            onToggleDanmaku = { enabled ->
                toggleDanmakuEnabled(enabled)
                onToggleDanmaku(enabled)
            },
            onEnabledDanmakuTypesChange = { enabledDanmakuTypes ->
                onEnabledDanmakuTypesChange(enabledDanmakuTypes)
                updateDanmakuConfigTypeFilter()
            },
            onDanmakuOpacityChange = {
                onDanmakuOpacityChange(it)
                applyDanmakuConfig(danmakuConfig.copy(opacity = it))
            },
            onDanmakuScaleChange = { scale ->
                onDanmakuScaleChange(scale)
                applyDanmakuConfig(danmakuConfig.copy(textSizeScale = (scale * 100).toInt()))
            },
            onDanmakuAreaChange = {
                onDanmakuAreaChange(it)
                applyDanmakuConfig(danmakuConfig.copy(area = it))
            },
            onPlayModeChange = onPlayModeChange,
            onAudioBalanceLevelChange = onAudioBalanceLevelChange,
            onPlayNewVideo = {
                //if (!Prefs.incognitoMode) sendHeartbeat()
                onLoadNewVideo(it)
            }
        ) {
            BvVideoPlayer(
                modifier = Modifier
                    .aspectRatio(aspectRatio)
                    .align(Alignment.Center),
                videoPlayer = videoPlayer, playerListener = videoPlayerListener
            )
            androidx.compose.ui.viewinterop.AndroidView(
                modifier = Modifier
                    .fillMaxHeight(),
                factory = { _ ->
                    danmakuView.apply {
                        setPositionProvider { videoPlayer.currentPosition.coerceAtLeast(0L) }
                        setIsPlayingProvider { videoPlayer.isPlaying }
                        // 用播放器实际速率，而不是配置里的速率：两者会在长按加速等场景下不一致
                        setPlaybackSpeedProvider { videoPlayer.speed }
                        setConfig(danmakuConfig)
                    }
                },
            )
        }
    }
}
