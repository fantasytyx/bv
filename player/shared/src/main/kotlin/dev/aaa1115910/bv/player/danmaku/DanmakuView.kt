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
import android.os.SystemClock
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import com.caverock.androidsvg.SVG
import dev.aaa1115910.biliapi.entity.danmaku.DanmakuMaskFrame
import dev.aaa1115910.biliapi.entity.danmaku.DanmakuMaskSegment
import dev.aaa1115910.biliapi.entity.danmaku.DanmakuMobMaskFrame
import dev.aaa1115910.biliapi.entity.danmaku.DanmakuWebMaskFrame
import dev.aaa1115910.bv.player.entity.VideoAspectRatio
import dev.aaa1115910.bv.player.danmaku.model.Danmaku
import dev.aaa1115910.bv.player.util.DanmakuMaskFinder

/**
 * 弹幕渲染 View（普通 View，兼容 TextureView）。
 * 渲染在 onDraw 中完成，通过 invalidate() 持续调度帧。
 */
class DanmakuView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val player = DanmakuPlayer(this)
    private var currentLayerType: Int = LAYER_TYPE_NONE

    private var positionProvider: (() -> Long)? = null
    private var isPlayingProvider: (() -> Boolean)? = null
    private var playbackSpeedProvider: (() -> Float)? = null
    private var config: DanmakuConfig = DEFAULT_CONFIG
    private var lastRawPositionMs: Long = 0L
    private var lastPositionChangeUptimeMs: Long = 0L

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

    private val maskDecodeThread = HandlerThread("DanmakuMask-Decode").apply { start() }
    private val maskDecodeHandler = Handler(maskDecodeThread.looper)

    @Volatile private var videoAspectRatio: Float = 0f
    @Volatile private var videoAspectRatioType: VideoAspectRatio = VideoAspectRatio.Default
    @Volatile private var videoRotationDegrees: Float = 0f

    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
    }
    private val maskDstRect = Rect()

    init {
        setLayerType(currentLayerType, null)
    }

    // ---- 公共 API ----
    fun setPositionProvider(provider: () -> Long) { positionProvider = provider }
    fun setIsPlayingProvider(provider: () -> Boolean) { isPlayingProvider = provider }
    fun setPlaybackSpeedProvider(provider: () -> Float) { playbackSpeedProvider = provider }
    fun setConfig(config: DanmakuConfig) {
        if (this.config == config) return
        this.config = config
        player.updateConfig(config)
        postInvalidate()
    }

    fun setDanmakus(list: List<Danmaku>) {
        player.updateConfig(config)
        player.setDanmakus(list)
        postInvalidate()
    }
    fun clearDanmakus() {
        player.clearDanmakus()
        postInvalidate()
    }

    fun appendDanmakus(list: List<Danmaku>, maxItems: Int = 0, alreadySorted: Boolean = false) {
        if (list.isEmpty()) return
        player.updateConfig(config)
        player.appendDanmakus(list, maxItems, alreadySorted)
        postInvalidate()
    }
    fun trimToTimeRange(minPositionMs: Long, maxPositionMs: Long) { player.trimToTimeRange(minPositionMs, maxPositionMs); postInvalidate() }
    fun notifySeek(positionMs: Long) {
        player.seekTo(positionMs)
        lastRawPositionMs = positionMs
        lastPositionChangeUptimeMs = SystemClock.uptimeMillis()
        postInvalidate()
    }

    fun play() {
        postInvalidate()
    }

    /** 显式释放资源。可多次调用，幂等。 */
    fun release() {
        player.release()
        clearMaskBitmapCache()
        maskDecodeHandler.removeCallbacksAndMessages(null)
        maskDecodeThread.quitSafely()
    }

    fun setMaskEnabled(enabled: Boolean) {
        if (maskEnabled == enabled) return
        maskEnabled = enabled
        if (!enabled) {
            clearMaskFrame(requestRedraw = false)
        }
        postInvalidate()
    }

    fun setMaskSegments(segments: List<DanmakuMaskSegment>) {
        if (maskSegments == segments) return
        maskSegments = segments
        clearMaskFrame(requestRedraw = false)
        postInvalidate()
    }

    fun setVideoAspectRatio(ratio: Float) { videoAspectRatio = ratio }
    fun setVideoAspectRatioType(type: VideoAspectRatio) { videoAspectRatioType = type }

    /** 画面旋转角度，蒙版需跟随画面一起旋转（弹幕本身仍保持水平）。 */
    fun setVideoRotation(degrees: Float) {
        if (videoRotationDegrees == degrees) return
        videoRotationDegrees = degrees
        postInvalidate()
    }

    // ---- Mask 内部逻辑 ----

    private fun clearMaskFrame(requestRedraw: Boolean) {
        targetMaskFrame = null
        pendingMaskFrame = null
        maskDecodeRequestId++
        if (maskFrame != null) {
            maskFrame = null
            if (requestRedraw) postInvalidate()
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
                postInvalidate()
            }
            return
        }
        requestMaskBitmapBuild(frame)
    }

    private fun promoteCachedMaskFrame(frame: DanmakuMaskFrame?, requestRedraw: Boolean) {
        if (maskFrame == frame) return
        maskFrame = frame
        if (requestRedraw) postInvalidate()
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
            post {
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
                postInvalidate()
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
        return try {
            when (frame) {
                is DanmakuWebMaskFrame -> buildWebMaskBitmap(frame)
                is DanmakuMobMaskFrame -> buildMobMaskBitmap(frame)
            }
        } catch (_: Exception) {
            null
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
            if (bit == 0) Color.BLACK else Color.TRANSPARENT
        }
        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        return bitmap
    }

    /**
     * 将蒙版 Bitmap 以 DstIn 模式绘制到 canvas 上，正确处理视频 letterbox/pillarbox。
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

        val rotation = videoRotationDegrees
        if (rotation == 0f) {
            canvas.drawBitmap(bitmap, null, maskDstRect, maskPaint)
            return
        }

        // 与 BvVideoPlayer 中 TextureView 的矩阵保持一致：±90° 时按同一比例缩放后，再绕视频中心旋转
        val centerX = screenW / 2f
        val centerY = screenH / 2f
        canvas.save()
        canvas.rotate(rotation, centerX, centerY)
        if (rotation == 90f || rotation == -90f) {
            val scale = minOf(screenH / dstW, screenW / dstH)
            canvas.scale(scale, scale, centerX, centerY)
        }
        canvas.drawBitmap(bitmap, null, maskDstRect, maskPaint)
        canvas.restore()
    }

    // ---- 生命周期 ----

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateViewportIfNeeded()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        release()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateViewportIfNeeded()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        updateViewportIfNeeded()
        if (!config.enabled) {
            player.draw(canvas, 0L, false, 1f, config)
            return
        }

        val posProvider = positionProvider ?: return
        val rawPos = posProvider()
        val now = SystemClock.uptimeMillis()
        if (lastPositionChangeUptimeMs == 0L) lastPositionChangeUptimeMs = now
        if (rawPos != lastRawPositionMs) lastPositionChangeUptimeMs = now
        lastRawPositionMs = rawPos

        val fallbackPlaying = now - lastPositionChangeUptimeMs < STOP_WHEN_IDLE_MS
        val playingProvider = isPlayingProvider
        val isPlaying = if (playingProvider != null) {
            try { playingProvider() } catch (_: Exception) { fallbackPlaying }
        } else {
            fallbackPlaying
        }
        val speedProvider = playbackSpeedProvider
        val speed = if (speedProvider != null) {
            val candidate = try { speedProvider() } catch (_: Exception) { Float.NaN }
            if (candidate.isFinite() && candidate > 0f) candidate else 1f
        } else {
            1f
        }

        // DstIn blending for mask requires an offscreen buffer — use hardware layer only when needed.
        val mask = resolveMaskFrame(rawPos)
        val desiredLayerType = if (mask != null) LAYER_TYPE_HARDWARE else LAYER_TYPE_NONE
        if (desiredLayerType != currentLayerType) {
            setLayerType(desiredLayerType, null)
            currentLayerType = desiredLayerType
        }

        player.draw(canvas, rawPos, isPlaying, speed, config)

        if (mask != null) {
            val maskBitmap = getCachedMaskBitmap(mask)
            if (maskBitmap != null) {
                drawMaskBitmap(canvas, maskBitmap, videoAspectRatio, videoAspectRatioType)
            }
        }
    }

    // ---- Viewport ----

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
        const val STOP_WHEN_IDLE_MS = 500L
    }
}