package dev.aaa1115910.bv.player.impl.exo

import android.content.Context
import android.os.Build
import android.os.Handler
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
import dev.aaa1115910.bv.player.cdn.CdnFailoverDataSourceFactory
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
        val mediaSources = listOfNotNull(createMediaSource(videoUrls), createMediaSource(audioUrls))
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
     */
    @OptIn(UnstableApi::class)
    private fun createMediaSource(candidates: List<String>): MediaSource? {
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
        val sourceFactory: DataSource.Factory =
            if (candidates.size <= 1) {
                dataSourceFactory
            } else {
                CdnFailoverDataSourceFactory(
                    upstreamFactory = dataSourceFactory,
                    candidates = candidates,
                    recorder = options.cdnSpeedRecorder
                )
            }
        return if (isHls) {
            HlsMediaSource.Factory(sourceFactory)
                .createMediaSource(mediaItem)
        } else {
            ProgressiveMediaSource.Factory(sourceFactory)
                .createMediaSource(mediaItem)
        }
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
        onSeek?.invoke(time)
    }

    override fun release() {
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

    override fun onPlaybackStateChanged(playbackState: Int) {
        when (playbackState) {
            Player.STATE_IDLE -> mPlayerEventListener?.onIdle()
            Player.STATE_BUFFERING -> mPlayerEventListener?.onBuffering()
            Player.STATE_READY -> mPlayerEventListener?.onReady()
            Player.STATE_ENDED -> mPlayerEventListener?.onEnd()
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
                video: ${player.videoFormat?.sampleMimeType ?: "null"} (${videoBitrate / 1000}kbps) [${getVideoDecoderName()}]
                audio: ${player.audioFormat?.sampleMimeType ?: "null"} (${audioBitrate / 1000}kbps) [${getAudioRendererName()}]
                frames: rendered=$renderedFrames dropped=$droppedFrames
            """.trimIndent()
            return if (extraDebugInfo.isNotEmpty()) "$base\n$extraDebugInfo" else base
        }

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
        mPlayerEventListener?.onError(error)
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
