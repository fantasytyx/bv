package dev.aaa1115910.bv.player.danmaku

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.os.Trace
import android.util.AttributeSet
import android.util.TypedValue
import android.graphics.PixelFormat
import android.os.Build
import android.view.SurfaceHolder
import android.view.SurfaceView
import java.util.concurrent.atomic.AtomicBoolean
import com.caverock.androidsvg.SVG
import dev.aaa1115910.biliapi.entity.danmaku.DanmakuMaskFrame
import dev.aaa1115910.biliapi.entity.danmaku.DanmakuMaskSegment
import dev.aaa1115910.biliapi.entity.danmaku.DanmakuMobMaskFrame
import dev.aaa1115910.biliapi.entity.danmaku.DanmakuWebMaskFrame
import dev.aaa1115910.bv.player.entity.VideoAspectRatio
import dev.aaa1115910.bv.player.danmaku.model.Danmaku
import dev.aaa1115910.bv.player.util.DanmakuMaskFinder

class DanmakuView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : SurfaceView(context, attrs), SurfaceHolder.Callback {

    private val player = DanmakuPlayer(this)
    private val renderRequested = AtomicBoolean(false)

    private var positionProvider: (() -> Long)? = null
    private var isPlayingProvider: (() -> Boolean)? = null
    private var playbackSpeedProvider: (() -> Float)? = null
    private var config: DanmakuConfig = DEFAULT_CONFIG
    private var lastRawPositionMs: Long = 0L
    private var lastPositionChangeUptimeMs: Long = 0L

    // Pre-sampled on main thread, consumed on render thread
    @Volatile private var sampledPositionMs: Long = 0L
    @Volatile private var sampledIsPlaying: Boolean = false
    @Volatile private var sampledSpeed: Float = 1f

    private val viewportTopInsetPx: Int = dp(2f)
    private val viewportBottomInsetPx: Int = dp(2f)
    private var lastViewportW: Int = 0
    private var lastViewportH: Int = 0
    private var lastViewportTopInset: Int = 0
    private var lastViewportBottomInset: Int = 0

    @Volatile private var maskEnabled: Boolean = false
    @Volatile private var maskSegments: List<DanmakuMaskSegment> = emptyList()
    @Volatile private var targetMaskFrame: DanmakuMaskFrame? = null
    @Volatile private var maskFrame: DanmakuMaskFrame? = null
    private var cachedMaskFrame: DanmakuMaskFrame? = null
    private var cachedMaskBitmap: Bitmap? = null
    private var failedMaskFrame: DanmakuMaskFrame? = null
    private var pendingMaskFrame: DanmakuMaskFrame? = null
    private var maskDecodeRequestId: Int = 0
    @Volatile private var videoAspectRatio: Float = 0f
    @Volatile private var videoAspectRatioType: VideoAspectRatio = VideoAspectRatio.Default
    @Volatile private var surfaceAvailable: Boolean = false
    @Volatile private var released: Boolean = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private val renderThread = HandlerThread("Danmaku-Render").apply { start() }
    private val renderHandler = Handler(renderThread.looper)
    private val maskDecodeThread = HandlerThread("DanmakuMask-Decode").apply { start() }
    private val maskDecodeHandler = Handler(maskDecodeThread.looper)
    private val renderRunnable = Runnable {
        renderRequested.set(false)
        renderFrameIfPossible()
    }

    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
    }
    private val maskDstRect = Rect()

    init {
        setZOrderMediaOverlay(true)
        holder.setFormat(PixelFormat.TRANSLUCENT)
        holder.addCallback(this)
        setWillNotDraw(true)
    }

    fun setPositionProvider(provider: () -> Long) { positionProvider = provider }
    fun setIsPlayingProvider(provider: () -> Boolean) { isPlayingProvider = provider }
    fun setPlaybackSpeedProvider(provider: () -> Float) { playbackSpeedProvider = provider }
    fun setConfig(config: DanmakuConfig) {
        if (this.config == config) return
        this.config = config
        player.updateConfig(config)
        requestRender()
    }

    fun setDanmakus(list: List<Danmaku>) { player.setDanmakus(list); requestRender() }
    fun appendDanmakus(list: List<Danmaku>, maxItems: Int = 0, alreadySorted: Boolean = false) {
        if (list.isEmpty()) return
        player.appendDanmakus(list, maxItems, alreadySorted); requestRender()
    }
    fun trimToTimeRange(minPositionMs: Long, maxPositionMs: Long) { player.trimToTimeRange(minPositionMs, maxPositionMs); requestRender() }
    fun notifySeek(positionMs: Long) {
        player.seekTo(positionMs)
        lastRawPositionMs = positionMs
        lastPositionChangeUptimeMs = SystemClock.uptimeMillis()
        requestRender()
    }

    fun play() {
        requestRender()
    }

    /** 显式释放资源。可多次调用，幂等。 */
    fun release() {
        if (released) return
        released = true
        cancelPendingRender()
        player.release()
        clearMaskBitmapCache()
        maskDecodeHandler.removeCallbacksAndMessages(null)
        maskDecodeThread.quitSafely()
        renderHandler.removeCallbacksAndMessages(null)
        renderThread.quitSafely()
    }

    fun setMaskEnabled(enabled: Boolean) {
        if (maskEnabled == enabled) return
        maskEnabled = enabled
        if (!enabled) {
            clearMaskFrame(requestRedraw = false)
        }
        requestRender()
    }

    fun setMaskSegments(segments: List<DanmakuMaskSegment>) {
        if (maskSegments == segments) return
        maskSegments = segments
        clearMaskFrame(requestRedraw = false)
        requestRender()
    }

    private fun clearMaskFrame(requestRedraw: Boolean) {
        targetMaskFrame = null
        pendingMaskFrame = null
        maskDecodeRequestId++
        if (maskFrame != null) {
            maskFrame = null
            if (requestRedraw) {
                requestRender()
            }
        }
    }

    private fun updateTargetMaskFrame(frame: DanmakuMaskFrame?, requestRedraw: Boolean) {
        if (frame == null) {
            clearMaskFrame(requestRedraw)
            return
        }
        if (targetMaskFrame == frame) return
        targetMaskFrame = frame
        if (frame == cachedMaskFrame && cachedMaskBitmap != null) {
            if (maskFrame != frame && requestRedraw) {
                maskFrame = frame
                requestRender()
            }
            return
        }
        requestMaskBitmapBuild(frame)
    }

    private fun promoteCachedMaskFrame(frame: DanmakuMaskFrame?, requestRedraw: Boolean) {
        if (maskFrame == frame) return
        maskFrame = frame
        if (requestRedraw) {
            requestRender()
        }
    }

    fun setVideoAspectRatio(ratio: Float) {
        if (videoAspectRatio == ratio) return
        videoAspectRatio = ratio
        requestRender()
    }

    fun setVideoAspectRatioType(type: VideoAspectRatio) {
        if (videoAspectRatioType == type) return
        videoAspectRatioType = type
        requestRender()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateViewportIfNeeded()
        requestRender()
    }

    override fun onDetachedFromWindow() {
        cancelPendingRender()
        super.onDetachedFromWindow()
        release()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateViewportIfNeeded()
        requestRender()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceAvailable = true
        updateViewportIfNeeded()
        requestRender()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        surfaceAvailable = width > 0 && height > 0
        updateViewportIfNeeded()
        requestRender()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceAvailable = false
        cancelPendingRender()
    }

    fun requestRender() {
        if (released || !surfaceAvailable) return
        if (Looper.myLooper() == Looper.getMainLooper()) {
            sampleProviders()
            postRender()
        } else {
            // Sample on main first, then post render
            mainHandler.post(sampleAndRenderRunnable)
        }
    }

    private val sampleAndRenderRunnable = Runnable {
        if (released || !surfaceAvailable) return@Runnable
        sampleProviders()
        postRender()
    }

    private fun postRender() {
        if (renderRequested.compareAndSet(false, true)) {
            renderHandler.post(renderRunnable)
        }
    }

    private fun sampleProviders() {
        val posProvider = positionProvider ?: return
        sampledPositionMs = try { posProvider() } catch (_: Exception) { sampledPositionMs }
        val playingProvider = isPlayingProvider
        if (playingProvider != null) {
            sampledIsPlaying = try { playingProvider() } catch (_: Exception) { sampledIsPlaying }
        }
        val speedProvider = playbackSpeedProvider
        if (speedProvider != null) {
            val s = try { speedProvider() } catch (_: Exception) { Float.NaN }
            if (s.isFinite() && s > 0f) sampledSpeed = s
        }
    }

    private fun cancelPendingRender() {
        renderRequested.set(false)
        renderHandler.removeCallbacksAndMessages(null)
    }

    private fun renderFrameIfPossible() {
        if (released || !surfaceAvailable) return
        val surface = holder.surface
        if (!surface.isValid) return
        val canvas = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                surface.lockHardwareCanvas()
            } else {
                holder.lockCanvas()
            }
        } catch (_: Exception) {
            null
        } ?: return

        try {
            renderFrame(canvas)
        } finally {
            try {
                surface.unlockCanvasAndPost(canvas)
            } catch (_: Exception) {}
        }
    }

    private fun renderFrame(canvas: Canvas) {
        Trace.beginSection("DanmakuView#renderFrame")
        try {
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

            Trace.beginSection("DanmakuView#updateViewport")
            try {
                updateViewportIfNeeded()
            } finally {
                Trace.endSection()
            }
            if (!config.enabled) {
                Trace.beginSection("DanmakuView#playerDraw")
                try {
                    player.draw(canvas, 0L, false, 1f, config)
                } finally {
                    Trace.endSection()
                }
                return
            }

            var rawPos: Long
            var isPlaying: Boolean
            var speed: Float
            Trace.beginSection("DanmakuView#prepareFrame")
            try {
                rawPos = sampledPositionMs
                isPlaying = sampledIsPlaying
                speed = sampledSpeed

                val now = SystemClock.uptimeMillis()
                if (lastPositionChangeUptimeMs == 0L) lastPositionChangeUptimeMs = now
                if (rawPos != lastRawPositionMs) lastPositionChangeUptimeMs = now
                lastRawPositionMs = rawPos

                if (!isPlaying) {
                    val fallbackPlaying = now - lastPositionChangeUptimeMs < STOP_WHEN_IDLE_MS
                    isPlaying = fallbackPlaying
                }
            } finally {
                Trace.endSection()
            }

            val mask = run {
                Trace.beginSection("DanmakuView#resolveMaskFrame")
                try {
                    resolveMaskFrame(rawPos)
                } finally {
                    Trace.endSection()
                }
            }

            // DstIn blending for mask requires an offscreen buffer — use hardware layer only when needed.
            Trace.beginSection("DanmakuView#updateLayerType")
            try {
                // SurfaceView renders into its own transparent buffer, so no extra View layer switch is needed.
            } finally {
                Trace.endSection()
            }

            Trace.beginSection("DanmakuView#playerDraw")
            try {
                player.draw(canvas, rawPos, isPlaying, speed, config)
            } finally {
                Trace.endSection()
            }

            if (mask != null) {
                val maskBitmap = run {
                    Trace.beginSection("DanmakuView#getCachedMaskBitmap")
                    try {
                        getCachedMaskBitmap(mask)
                    } finally {
                        Trace.endSection()
                    }
                }
                if (maskBitmap != null) {
                    Trace.beginSection("DanmakuView#drawMaskBitmap")
                    try {
                        drawMaskBitmap(canvas, maskBitmap, videoAspectRatio, videoAspectRatioType)
                    } finally {
                        Trace.endSection()
                    }
                }
            }
        } finally {
            Trace.endSection()
        }
    }

    private fun resolveMaskFrame(positionMs: Long): DanmakuMaskFrame? {
        if (!maskEnabled) {
            clearMaskFrame(requestRedraw = false)
            return null
        }

        val currentTargetFrame = targetMaskFrame
        if (currentTargetFrame != null && positionMs in currentTargetFrame.range) {
            if (currentTargetFrame == cachedMaskFrame && cachedMaskBitmap != null) {
                promoteCachedMaskFrame(currentTargetFrame, requestRedraw = false)
                return currentTargetFrame
            }
            return maskFrame
        }

        val segments = maskSegments
        val resolvedFrame = if (segments.isEmpty()) {
            null
        } else {
            DanmakuMaskFinder.findMaskFrame(segments, positionMs)
        }
        updateTargetMaskFrame(resolvedFrame, requestRedraw = false)
        return if (resolvedFrame != null && resolvedFrame == cachedMaskFrame && cachedMaskBitmap != null) {
            promoteCachedMaskFrame(resolvedFrame, requestRedraw = false)
            resolvedFrame
        } else {
            maskFrame
        }
    }

    private fun getCachedMaskBitmap(frame: DanmakuMaskFrame): Bitmap? {
        if (frame == cachedMaskFrame) return cachedMaskBitmap
        if (frame != failedMaskFrame && frame != pendingMaskFrame) {
            requestMaskBitmapBuild(frame)
        }
        return null
    }

    private fun requestMaskBitmapBuild(frame: DanmakuMaskFrame) {
        if (frame == cachedMaskFrame || frame == pendingMaskFrame) return
        pendingMaskFrame = frame
        val requestId = ++maskDecodeRequestId
        maskDecodeHandler.removeCallbacksAndMessages(null)
        maskDecodeHandler.post {
            val bitmap = buildMaskBitmap(frame)
            renderHandler.post {
                if (requestId != maskDecodeRequestId || targetMaskFrame != frame) {
                    bitmap?.recycle()
                    return@post
                }
                pendingMaskFrame = null
                failedMaskFrame = frame.takeIf { bitmap == null }
                cachedMaskBitmap?.recycle()
                cachedMaskFrame = frame
                cachedMaskBitmap = bitmap
                maskFrame = frame.takeIf { bitmap != null }
                requestRender()
            }
        }
    }

    private fun clearMaskBitmapCache() {
        targetMaskFrame = null
        maskFrame = null
        pendingMaskFrame = null
        failedMaskFrame = null
        cachedMaskBitmap?.recycle()
        cachedMaskBitmap = null
        cachedMaskFrame = null
    }

    private fun buildMaskBitmap(frame: DanmakuMaskFrame): Bitmap? {
        val traceName = when (frame) {
            is DanmakuWebMaskFrame -> "DanmakuView#buildMaskBitmap:web"
            is DanmakuMobMaskFrame -> "DanmakuView#buildMaskBitmap:mob"
        }
        Trace.beginSection(traceName)
        return try {
            try {
                when (frame) {
                    is DanmakuWebMaskFrame -> buildWebMaskBitmap(frame)
                    is DanmakuMobMaskFrame -> buildMobMaskBitmap(frame)
                }
            } catch (_: Exception) {
                null
            }
        } finally {
            Trace.endSection()
        }
    }

    /** Web 蒙版：使用 androidsvg 库解析完整 SVG，渲染到 Bitmap */
    private fun buildWebMaskBitmap(frame: DanmakuWebMaskFrame): Bitmap? {
        val svg = frame.svg
        if (svg.isBlank()) return null
        val svgObj = SVG.getFromString(svg)
        val svgW = svgObj.documentWidth.toInt()
        val svgH = svgObj.documentHeight.toInt()
        if (svgW <= 0 || svgH <= 0) return null
        val bitmap = Bitmap.createBitmap(svgW, svgH, Bitmap.Config.ARGB_8888)
        svgObj.renderToCanvas(Canvas(bitmap))
        return bitmap
    }

    /** Mob 蒙版：40×180 1bpp 二值图，批量 setPixels 写入 */
    private fun buildMobMaskBitmap(frame: DanmakuMobMaskFrame): Bitmap? {
        val w = frame.width
        val h = frame.height
        if (w <= 0 || h <= 0) return null
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(w * h) { i ->
            val byteIndex = i / 8
            val bitOffset = 7 - (i % 8)
            val bit = (frame.image[byteIndex].toInt() shr bitOffset) and 1
            if (bit == 0) Color.TRANSPARENT else Color.BLACK
        }
        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        return bitmap
    }

    /**
     * 将蒙版 Bitmap 以 DstIn 模式绘制到 canvas 上，正确处理视频 letterbox/pillarbox。
     * 逻辑与 DanmakuMaskModifiers.bitmapMask 一致。
     */
    private fun drawMaskBitmap(
        canvas: Canvas,
        bitmap: Bitmap,
        videoAspect: Float,
        aspectType: VideoAspectRatio,
    ) {
        val screenW = width.toFloat()
        val screenH = height.toFloat()
        if (screenW <= 0f || screenH <= 0f) return
        val screenAspect = screenW / screenH

        val dstW: Float
        val dstH: Float
        val offsetX: Float
        val offsetY: Float

        val ratio = if (videoAspect > 0f) videoAspect else 16f / 9f
        when (aspectType) {
            VideoAspectRatio.Stretch -> {
                dstW = screenW
                dstH = screenH
                offsetX = 0f
                offsetY = 0f
            }

            VideoAspectRatio.EqualWidth -> {
                dstW = screenW
                dstH = dstW / ratio
                offsetX = 0f
                offsetY = (screenH - dstH) / 2f
            }

            VideoAspectRatio.EqualHeight -> {
                dstH = screenH
                dstW = dstH * ratio
                offsetY = 0f
                offsetX = (screenW - dstW) / 2f
            }

            else -> {
                if (ratio > screenAspect) {
                    dstW = screenW
                    dstH = dstW / ratio
                    offsetX = 0f
                    offsetY = (screenH - dstH) / 2f
                } else {
                    dstH = screenH
                    dstW = dstH * ratio
                    offsetY = 0f
                    offsetX = (screenW - dstW) / 2f
                }
            }
        }

        maskDstRect.set(offsetX.toInt(), offsetY.toInt(), (offsetX + dstW).toInt(), (offsetY + dstH).toInt())
        canvas.drawBitmap(bitmap, null, maskDstRect, maskPaint)
    }

    private fun updateViewportIfNeeded() {
        val w = width.coerceAtLeast(0); val h = height.coerceAtLeast(0)
        val top = viewportTopInsetPx; val bottom = viewportBottomInsetPx
        if (w == lastViewportW && h == lastViewportH && top == lastViewportTopInset && bottom == lastViewportBottomInset) return
        lastViewportW = w; lastViewportH = h; lastViewportTopInset = top; lastViewportBottomInset = bottom
        player.onViewportChanged(w, h, top, bottom)
    }

    private fun dp(v: Float): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()

    private companion object {
        private val DEFAULT_CONFIG = DanmakuConfig()
        const val STOP_WHEN_IDLE_MS = 700L
    }
}
