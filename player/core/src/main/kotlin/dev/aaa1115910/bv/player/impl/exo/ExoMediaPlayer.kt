package dev.aaa1115910.bv.player.impl.exo

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.video.VideoRendererEventListener
import dev.aaa1115910.bv.player.AbstractVideoPlayer
import dev.aaa1115910.bv.player.OkHttpUtil
import dev.aaa1115910.bv.player.VideoPlayerOptions
import dev.aaa1115910.bv.player.audio.VolumeBalanceAudioProcessor
import dev.aaa1115910.bv.player.cdn.BufferingAction
import dev.aaa1115910.bv.player.cdn.CdnFailoverDataSourceFactory
import dev.aaa1115910.bv.player.cdn.FrequentBufferingDetector
import dev.aaa1115910.bv.player.entity.AudioBalanceLevel
import dev.aaa1115910.bv.player.entity.AudioLoudness
import dev.aaa1115910.bv.util.formatHourMinSec

/**
 * 智能缓冲配置
 */
private data class BufferConfig(
    val minBufferMs: Int,
    val maxBufferMs: Int,
    val backBufferMs: Int,
    val targetBufferBytes: Int,
    val prioritizeTime: Boolean // 是否优先考虑时间阈值
)

/** 低于该倍速不启用跳过静音：压缩音频时间轴会让作为主时钟的音频位置快进，画面跟着跳帧 */
private const val SKIP_SILENCE_MIN_SPEED = 2.5f

private const val TAG = "ExoMediaPlayer"

/** 一次播放（[ExoMediaPlayer.playUrl] 起）内，容器解析失败后允许整段重载的次数。 */
private const val MAX_MALFORMED_CONTAINER_RETRIES = 2

/** 频繁缓冲判据一：窗口内进入缓冲的次数。起播与快进/快退引起的那次都不计。 */
private const val FREQUENT_BUFFERING_COUNT = 3

/** 上面那个次数的统计窗口。 */
private const val FREQUENT_BUFFERING_WINDOW_MS = 90_000L

/** 频繁缓冲判据二：单次缓冲持续到这个时长。 */
private const val SINGLE_BUFFERING_TIMEOUT_MS = 12_000L

/** 快进/快退之后多久内的缓冲算「这次 seek 引起的」，不计入。 */
private const val SEEK_BUFFERING_GRACE_MS = 3_000L

@OptIn(UnstableApi::class)
class ExoMediaPlayer(
    private val context: Context,
    private val options: VideoPlayerOptions
) : AbstractVideoPlayer(), Player.Listener {
    var mPlayer: ExoPlayer? = null
    protected var mMediaSource: MediaSource? = null

    // 实时渲染帧率计算
    private var lastRenderedFrames: Int = 0
    private var lastFrameTimestamp: Long = 0L
    private var realTimeFps: Float = 0f

    /** 本次播放已用掉的容器解析失败重载次数，见 [retryMalformedContainer]。 */
    private var malformedContainerRetries: Int = 0

    /**
     * 非直播且有多个候选地址时才会创建，用于「频繁缓冲换地址」。视频与音频各持有一份：
     * 音频没有未试过的地址时保持不动，不影响已经在用的那个。
     */
    private var videoFailoverFactory: CdnFailoverDataSourceFactory? = null
    private var audioFailoverFactory: CdnFailoverDataSourceFactory? = null

    /** 为 null 表示本次播放不做频繁缓冲换地址（直播 / 只有一个地址 / 已轮完一遍）。 */
    private var bufferingDetector: FrequentBufferingDetector? = null

    private val bufferingTimeoutHandler = Handler(Looper.getMainLooper())
    private val bufferingTimeoutCheck = Runnable { onBufferingTimeout() }

    @OptIn(UnstableApi::class)
    private val dataSourceFactory =
        OkHttpDataSource.Factory(OkHttpUtil.generateCustomSslOkHttpClient(context)).apply {
            options.userAgent?.let { setUserAgent(it) }
            options.referer?.let { setDefaultRequestProperties(mapOf("referer" to it)) }
        }

    private val volumeBalanceProcessor = VolumeBalanceAudioProcessor(options.audioBalanceLevel)

    init {
        initPlayer()
    }

    @OptIn(UnstableApi::class)
    override fun initPlayer() {
        val renderersFactory = BvRenderersFactory(context, volumeBalanceProcessor).apply {
            setExtensionRendererMode(
                when (options.enableFfmpegAudioRenderer) {
                    true -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
                    false -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF
                }
            )
            // setMediaCodecSelector(MediaCodecSelector.PREFER_SOFTWARE)
            setEnableDecoderFallback(true)
            // 为 API 23-30 启用异步缓冲队列（API 31+ 已默认启用）
            if (options.enableAsyncQueueing && Build.VERSION.SDK_INT >= 23 && Build.VERSION.SDK_INT < 31) {
                @Suppress("UNCHECKED_CAST")
                forceEnableMediaCodecAsynchronousQueueing()
            }
        }

        // 创建智能缓冲策略，根据设备性能和视频质量动态调整
        val bufferConfig = calculateSmartBufferConfig()
        val loadControl = DefaultLoadControl.Builder()
            // 动态设置缓冲区大小
            .setBufferDurationsMs(
                bufferConfig.minBufferMs, // 最小缓冲时间
                bufferConfig.maxBufferMs, // 最大缓冲时间
                DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS, // 开始播放前的缓冲时间
                DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS // 重新缓冲后的播放缓冲
            )
            // 优先考虑时间阈值还是缓冲大小。true：优先考虑时间阈值
            .setPrioritizeTimeOverSizeThresholds(bufferConfig.prioritizeTime)
            // 根据系统内存计算缓冲区大小
            .setTargetBufferBytes(bufferConfig.targetBufferBytes)
            .setBackBuffer(bufferConfig.backBufferMs, false) // 动态回退缓冲
            .build()

        mPlayer = ExoPlayer
            .Builder(context)
            .setRenderersFactory(renderersFactory)
            .setLoadControl(loadControl)
            .setSeekForwardIncrementMs(1000 * 10)
            .setSeekBackIncrementMs(1000 * 10)
            .setVideoChangeFrameRateStrategy(
                // 启用时切换屏幕刷新率匹配视频帧率；关闭时保持默认以优化滚动弹幕流畅度
                if (options.enableScreenRefreshRateMatching) {
                    C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS
                } else {
                    C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF
                }
            )
            .build()

        initListener()
    }

    private fun initListener() {
        mPlayer?.addListener(this)
    }

    @OptIn(UnstableApi::class)
    override fun setHeader(headers: Map<String, String>) {

    }

    @OptIn(UnstableApi::class)
    override fun playUrl(videoUrls: List<String>, audioUrls: List<String>) {
        malformedContainerRetries = 0
        val video = createMediaSource(videoUrls)
        val audio = createMediaSource(audioUrls)
        videoFailoverFactory = video?.second
        audioFailoverFactory = audio?.second
        resetCdnSwitch()
        val mediaSources = listOfNotNull(video?.first, audio?.first)
        if (mediaSources.isEmpty()) {
            mMediaSource = null
            return
        }
        mMediaSource = MergingMediaSource(*mediaSources.toTypedArray())
    }

    /**
     * 根据候选地址自动选择合适的 MediaSource，取第一个非空地址作为初始地址
     * - .m3u8 URL 使用 HlsMediaSource（支持 HLS 直播/点播）
     * - 其他 URL 使用 ProgressiveMediaSource（支持 FLV/MP4 等逐行下载）
     *
     * 同时返回候选切换器（只有一个候选时为 null），供频繁缓冲时换地址。
     */
    @OptIn(UnstableApi::class)
    private fun createMediaSource(candidates: List<String>): Pair<MediaSource, CdnFailoverDataSourceFactory?>? {
        val url = candidates.firstOrNull { it.isNotBlank() } ?: return null
        val uri = android.net.Uri.parse(url)
        val path = uri.path?.lowercase() ?: ""
        val isHls = path.endsWith(".m3u8")
        val mediaItem = if (isHls && isLive) {
            MediaItem.Builder()
                .setUri(uri)
                .setLiveConfiguration(
                    MediaItem.LiveConfiguration.Builder()
                        .setMinOffsetMs(2000)
                        .setMaxOffsetMs(15000)
                        .setMinPlaybackSpeed(0.95f)
                        .setMaxPlaybackSpeed(1.05f)
                        .build()
                )
                .build()
        } else {
            MediaItem.fromUri(uri)
        }
        // 只有一个候选时无从切换，直接用原始 factory（比如直播就是固定只有一个地址）
        val failoverFactory =
            if (candidates.size <= 1) {
                null
            } else {
                CdnFailoverDataSourceFactory(
                    upstreamFactory = dataSourceFactory,
                    candidates = candidates,
                    recorder = options.cdnSpeedRecorder
                )
            }
        val sourceFactory: DataSource.Factory = failoverFactory ?: dataSourceFactory
        val source =
            if (isHls) {
                HlsMediaSource.Factory(sourceFactory).createMediaSource(mediaItem)
            } else {
                ProgressiveMediaSource.Factory(sourceFactory).createMediaSource(mediaItem)
            }
        return source to failoverFactory
    }

    @OptIn(UnstableApi::class)
    override fun prepare() {
        val mediaSource = mMediaSource ?: return
        mPlayer?.setMediaSource(mediaSource)
        mPlayer?.prepare()
        // 处理初始跳转位置，避免在 onReady 中 seek 导致的状态抖动
        if (pendingSeekPosition > 0) {
            mPlayer?.seekTo(pendingSeekPosition)
            clearPendingSeekPosition()
        }
    }

    override fun start() {
        if (isInBackground) {
            isInBackground = false
            recoverIfNeeded()
        }
        mPlayer?.play()
    }

    override fun pause() {
        mPlayer?.pause()
    }

    override fun stop() {
        mPlayer?.stop()
    }

    override fun reset() {
        TODO("Not yet implemented")
    }

    override val isPlaying: Boolean
        get() = mPlayer?.isPlaying == true

    override fun seekTo(time: Long) {
        mPlayer?.seekTo(time)
        // 快进/快退引起的那次缓冲不算地址的问题
        bufferingDetector?.onSeek(SystemClock.elapsedRealtime())
        onSeek?.invoke(time)
    }

    override fun release() {
        bufferingTimeoutHandler.removeCallbacks(bufferingTimeoutCheck)
        try {
            mPlayer?.release()
            mMediaSource = null
            mPlayer = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override val currentPosition: Long
        get() = mPlayer?.currentPosition ?: 0
    override val duration: Long
        get() = mPlayer?.duration ?: 0
    override val bufferedPercentage: Int
        get() = mPlayer?.bufferedPercentage ?: 0

    override fun setOptions() {
        mPlayer?.playWhenReady = true
    }

    override var speed: Float
        get() = mPlayer?.playbackParameters?.speed ?: 1f
        set(value) {
            mPlayer?.setPlaybackSpeed(value)
            mPlayer?.setSkipSilenceEnabled(
                options.enableSkipSilence && value >= SKIP_SILENCE_MIN_SPEED
            )
        }

    override fun setAudioBalanceLevel(level: AudioBalanceLevel) {
        volumeBalanceProcessor.setLevel(level)
    }

    override fun setAudioLoudness(loudness: AudioLoudness?) {
        volumeBalanceProcessor.setLoudness(loudness)
    }

    override val tcpSpeed: Long
        get() = 0L

    /** 新建频繁缓冲判定；开关关闭、直播、只有一个候选地址时都不启用。 */
    private fun resetCdnSwitch() {
        bufferingTimeoutHandler.removeCallbacks(bufferingTimeoutCheck)
        bufferingDetector =
            if (options.enableSwitchCdnOnBuffering && !isLive && videoFailoverFactory != null) {
                FrequentBufferingDetector(
                    maxBufferingCount = FREQUENT_BUFFERING_COUNT,
                    countWindowMs = FREQUENT_BUFFERING_WINDOW_MS,
                    singleBufferingTimeoutMs = SINGLE_BUFFERING_TIMEOUT_MS,
                    seekGraceMs = SEEK_BUFFERING_GRACE_MS,
                )
            } else {
                null
            }
    }

    private fun trackBufferingStart() {
        val detector = bufferingDetector ?: return
        when (detector.onBufferingStart(SystemClock.elapsedRealtime())) {
            BufferingAction.IGNORED -> Unit
            BufferingAction.COUNTED ->
                bufferingTimeoutHandler.postDelayed(bufferingTimeoutCheck, SINGLE_BUFFERING_TIMEOUT_MS)
            BufferingAction.SWITCH -> switchToNextCdn("缓冲次数")
        }
    }

    private fun trackBufferingEnd() {
        bufferingTimeoutHandler.removeCallbacks(bufferingTimeoutCheck)
        bufferingDetector?.onBufferingEnd()
    }

    private fun onBufferingTimeout() {
        val detector = bufferingDetector ?: return
        if (detector.isTimedOut(SystemClock.elapsedRealtime())) switchToNextCdn("缓冲超时")
    }

    /**
     * 换到下一个还没试过的候选地址。视频与音频各自从现在在用的地址往后跳，各自没有下一个的
     * 时候保持不动。
     *
     * 重载走 `stop` + `prepare` 而不是再调一次 [playUrl]：这样 [MediaSource] 实例不变，
     * [CdnFailoverDataSourceFactory] 和它记住的起点都保留，新一轮 `open` 才会从新地址开始；
     * 顺带拿到一个全新的 extractor，不会踩到「断点续传拼不上」那个坑。
     *
     * @return true 表示已经换地址并开始重载
     */
    private fun switchToNextCdn(reason: String): Boolean {
        val videoFactory = videoFailoverFactory ?: return false
        if (!videoFactory.skipServing()) {
            Log.w(TAG, "frequent buffering but no untried cdn left: $reason")
            bufferingDetector = null
            bufferingTimeoutHandler.removeCallbacks(bufferingTimeoutCheck)
            return false
        }
        audioFailoverFactory?.skipServing()
        bufferingTimeoutHandler.removeCallbacks(bufferingTimeoutCheck)
        // 重载后的那次缓冲相当于新的起播缓冲，不计入
        bufferingDetector?.reset()
        val player = mPlayer ?: return false
        val position = player.currentPosition
        Log.w(TAG, "frequent buffering, switch cdn: $reason, position=${position}ms")
        player.stop()
        player.prepare()
        if (position > 0) player.seekTo(position)
        return true
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        when (playbackState) {
            Player.STATE_IDLE -> mPlayerEventListener?.onIdle()
            Player.STATE_BUFFERING -> {
                mPlayerEventListener?.onBuffering()
                trackBufferingStart()
            }
            Player.STATE_READY -> {
                mPlayerEventListener?.onReady()
                trackBufferingEnd()
            }
            Player.STATE_ENDED -> {
                mPlayerEventListener?.onEnd()
                trackBufferingEnd()
            }
        }
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        if (isPlaying) {
            mPlayerEventListener?.onPlay()
        } else {
            mPlayerEventListener?.onPause()
        }
        onPlayStateChanged?.invoke(isPlaying)
    }

    override fun onSeekBackIncrementChanged(seekBackIncrementMs: Long) {
        mPlayerEventListener?.onSeekBack(seekBackIncrementMs)
    }

    override fun onSeekForwardIncrementChanged(seekForwardIncrementMs: Long) {
        mPlayerEventListener?.onSeekForward(seekForwardIncrementMs)
    }

    override val debugInfo: String
        get() {
            val player = mPlayer ?: return "player: null"
            val playbackState = when (player.playbackState) {
                Player.STATE_IDLE -> "IDLE"
                Player.STATE_BUFFERING -> "BUFFERING"
                Player.STATE_READY -> "READY"
                Player.STATE_ENDED -> "ENDED"
                else -> "UNKNOWN"
            }
            val videoDecoderCounters = getVideoDecoderCounters()
            val droppedFrames = videoDecoderCounters?.droppedBufferCount ?: 0
            val renderedFrames = videoDecoderCounters?.renderedOutputBufferCount ?: 0
            updateRealTimeFps(renderedFrames)
            val fps = realTimeFps
            val videoBitrate = player.videoFormat?.bitrate ?: 0
            val audioBitrate = player.audioFormat?.bitrate ?: 0
            val bufferedMs = player.totalBufferedDuration
            val base = """
                player: ${androidx.media3.common.MediaLibraryInfo.VERSION_SLASHY}
                state: $playbackState | speed: ${player.playbackParameters.speed}x
                time: ${currentPosition.formatHourMinSec()} / ${duration.formatHourMinSec()}
                buffer: ${bufferedMs / 1000}s ($bufferedPercentage%)
                resolution: ${player.videoSize.width} x ${player.videoSize.height} @ ${String.format("%.1f", fps)}fps
                video: ${player.videoFormat?.sampleMimeType ?: "null"} (${formatBitrate(videoBitrate)}) [${getVideoDecoderName()}]
                audio: ${player.audioFormat?.sampleMimeType ?: "null"} (${formatBitrate(audioBitrate)}) [${getAudioRendererName()}]
                frames: rendered=$renderedFrames dropped=$droppedFrames
            """.trimIndent()
            return if (extraDebugInfo.isNotEmpty()) "$base\n$extraDebugInfo" else base
        }

    /**
     * 直播流的 Format 里通常没有 bitrate（值为 C.LENGTH_UNSET），直接除 1000 会显示成 0kbps，
     * 看起来像"码率不对"。取不到时明确显示 unknown。
     */
    private fun formatBitrate(bitrate: Int): String =
        if (bitrate > 0) "${bitrate / 1000}kbps" else "unknown"

    private fun updateRealTimeFps(currentRenderedFrames: Int) {
        val now = System.nanoTime()
        val elapsed = (now - lastFrameTimestamp) / 1_000_000_000.0
        if (lastFrameTimestamp != 0L && elapsed >= 0.5) {
            val deltaFrames = currentRenderedFrames - lastRenderedFrames
            realTimeFps = (deltaFrames / elapsed).toFloat()
            lastRenderedFrames = currentRenderedFrames
            lastFrameTimestamp = now
        } else if (lastFrameTimestamp == 0L || elapsed >= 0.5) {
            lastRenderedFrames = currentRenderedFrames
            lastFrameTimestamp = now
        }
    }

    private fun getVideoDecoderName(): String {
        val rendererCount = mPlayer?.rendererCount ?: return "UnknownRenderer"
        for (i in 0 until rendererCount) {
            val renderer = mPlayer!!.getRenderer(i)
            if (renderer.trackType == C.TRACK_TYPE_VIDEO && renderer.state == Renderer.STATE_STARTED) {
                return renderer.name
            }
        }
        return "UnknownRenderer"
    }

    @OptIn(UnstableApi::class)
    private fun getVideoDecoderCounters(): androidx.media3.exoplayer.DecoderCounters? {
        return try {
            val rendererCount = mPlayer?.rendererCount ?: return null
            for (i in 0 until rendererCount) {
                val renderer = mPlayer!!.getRenderer(i)
                if (renderer.trackType == C.TRACK_TYPE_VIDEO && renderer is androidx.media3.exoplayer.mediacodec.MediaCodecRenderer) {
                    val field = androidx.media3.exoplayer.mediacodec.MediaCodecRenderer::class.java
                        .getDeclaredField("decoderCounters")
                    field.isAccessible = true
                    return field.get(renderer) as? androidx.media3.exoplayer.DecoderCounters
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun getAudioRendererName(): String {
        val rendererCount = mPlayer?.rendererCount ?: return "UnknownRenderer"
        for (i in 0 until rendererCount) {
            val renderer = mPlayer!!.getRenderer(i)
            if (renderer.trackType == C.TRACK_TYPE_AUDIO && renderer.state == Renderer.STATE_STARTED) {
                return renderer.name
            }
        }
        return "UnknownRenderer"
    }

    override val videoWidth: Int
        get() = mPlayer?.videoSize?.width ?: 0
    override val videoHeight: Int
        get() = mPlayer?.videoSize?.height ?: 0

    override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
        mPlayerEventListener?.onVideoSizeChanged(videoSize.width, videoSize.height)
    }

    override fun onRenderedFirstFrame() {
        mPlayerEventListener?.onRenderedFirstFrame()
    }

    override fun onPlayerError(error: PlaybackException) {
        if (isInBackground) return
        // HLS 直播落后于直播窗口时，跳转到直播最新位置而非报错
        if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
            mPlayer?.let { player ->
                player.seekToDefaultPosition()
                player.prepare()
            }
            return
        }
        // 解码器错误：尝试降级清晰度
        if (error.errorCode in PlaybackException.ERROR_CODE_DECODER_INIT_FAILED..PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED) {
            if (onDecoderError?.invoke() == true) return
        }
        if (retryMalformedContainer(error)) return
        mPlayerEventListener?.onError(error)
    }

    /**
     * 容器解析失败时整段重载，返回 true 表示已接管这次错误。
     *
     * 典型来源是字节范围续传拼不上（`Invalid NAL length` 之类）：断流后 ExoPlayer 会从断点
     * 续传并保留已解析的 extractor 状态，一旦续传换到了别的节点，后半段字节和前半段对不齐。
     * extractor 状态已经脏了，只能从干净的 extractor 重新来一遍，等价于用户手动重载。
     *
     * 直播交给原有的重试路径（`BvPlayer` 的 onLiveRetry 会重新取流地址）：直播地址会过期，
     * 重载同一个地址没意义，seekTo 也不适用于直播窗口。
     */
    private fun retryMalformedContainer(error: PlaybackException): Boolean {
        if (isLive) return false
        if (error.errorCode != PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED) return false
        val player = mPlayer ?: return false
        if (malformedContainerRetries >= MAX_MALFORMED_CONTAINER_RETRIES) return false
        malformedContainerRetries++
        val position = player.currentPosition
        val playWhenReady = player.playWhenReady
        Log.w(TAG, "malformed container, reload at ${position}ms (retry $malformedContainerRetries)", error)
        player.prepare()
        if (position > 0) player.seekTo(position)
        player.playWhenReady = playWhenReady
        return true
    }

    /**
     * 从后台恢复时检查播放器状态，若处于错误态则重新 prepare 恢复播放
     */
    fun recoverIfNeeded() {
        val player = mPlayer ?: return
        if (player.playerError != null) {
            println("recoverIfNeeded: ${player.playerError}")
            val pos = player.currentPosition
            player.prepare()
            if (pos > 0) player.seekTo(pos)
            player.play()
        }
    }

    /**
     * 计算智能缓冲配置
     * 根据设备性能、可用内存和预期视频质量动态调整缓冲策略
     */
    private fun calculateSmartBufferConfig(): BufferConfig {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val memoryInfo = android.app.ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)

        // 获取当前可用内存（以字节为单位）
        val availableMemory = memoryInfo.availMem
        val totalMemory = memoryInfo.totalMem
        val isLowRam = activityManager.isLowRamDevice

        // 根据设备性能等级调整策略
        val deviceTier = when {
            isLowRam || totalMemory < 3L * 1024 * 1024 * 1024 -> DeviceTier.LOW // 低端设备：小于3GB RAM
            totalMemory < 6L * 1024 * 1024 * 1024 -> DeviceTier.MID // 中端设备：3-6GB RAM
            else -> DeviceTier.HIGH // 高端设备：6GB+ RAM
        }
        return when (deviceTier) {
            DeviceTier.LOW -> BufferConfig(
                minBufferMs = if(isLive) 2000 else 7000,   // 7秒最小缓冲
                maxBufferMs = 11000,  // 11秒最大缓冲
                backBufferMs = 0, // 0秒回退缓冲
                targetBufferBytes = calculateBufferSize(availableMemory, 0.08, 5, 50), // 8%内存，5-50MB
                prioritizeTime = false // 改为优先大小限制，严格控制内存使用
            )
            DeviceTier.MID -> BufferConfig(
                minBufferMs = if(isLive) 2000 else 11000,  // 11秒最小缓冲
                maxBufferMs = 16000,  // 16秒最大缓冲
                backBufferMs = 0, // 0秒回退缓冲
                targetBufferBytes = calculateBufferSize(availableMemory, 0.13, 5, 150), // 13%内存，5-150MB
                prioritizeTime = false
            )
            DeviceTier.HIGH -> BufferConfig(
                minBufferMs = if(isLive) 2000 else 12000,  // 12秒最小缓冲
                maxBufferMs = 22000,  // 22秒最大缓冲
                backBufferMs = if(isLive) 0 else 11000, // 11秒回退缓冲
                targetBufferBytes = calculateBufferSize(availableMemory, 0.18, 10, 300), // 18%内存，10-300MB
                prioritizeTime = false
            )
        }
    }

    /**
     * 设备性能等级
     */
    private enum class DeviceTier {
        LOW, MID, HIGH
    }

    /**
     * 计算缓冲区大小
     */
    private fun calculateBufferSize(
        availableMemory: Long,
        memoryRatio: Double,
        minMB: Int,
        maxMB: Int
    ): Int {
        val calculatedSize = (availableMemory * memoryRatio).toLong()
        val minSize = minMB * 1024 * 1024L
        val maxSize = maxMB * 1024 * 1024L

        return when {
            calculatedSize < minSize -> minSize.toInt()
            calculatedSize > maxSize -> maxSize.toInt()
            else -> calculatedSize.toInt()
        }
    }
}

/**
 * media3 的视频扩展渲染器一律不启用：ffmpegDecoder AAR 里的
 * ExperimentalFfmpegVideoRenderer 是上游未实现的空壳（supportsFormat 恒返回不支持），
 * 且其调用 RendererCapabilities.create 在未混淆构建下会抛 NoSuchMethodError 导致进播放页崩溃。
 */
@OptIn(UnstableApi::class)
private class BvRenderersFactory(
    context: Context,
    private val volumeBalanceProcessor: VolumeBalanceAudioProcessor
) : DefaultRenderersFactory(context) {
    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean
    ): AudioSink {
        return DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioOutputPlaybackParameters(enableAudioTrackPlaybackParams)
            .setAudioProcessors(arrayOf(volumeBalanceProcessor))
            .build()
    }

    override fun buildVideoRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        eventHandler: Handler,
        eventListener: VideoRendererEventListener,
        allowedVideoJoiningTimeMs: Long,
        out: ArrayList<Renderer>
    ) {
        super.buildVideoRenderers(
            context,
            EXTENSION_RENDERER_MODE_OFF,
            mediaCodecSelector,
            enableDecoderFallback,
            eventHandler,
            eventListener,
            allowedVideoJoiningTimeMs,
            out
        )
    }
}
