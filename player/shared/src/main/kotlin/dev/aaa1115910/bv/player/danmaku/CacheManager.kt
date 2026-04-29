package dev.aaa1115910.bv.player.danmaku

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Message
import android.os.Process
import dev.aaa1115910.bv.player.danmaku.model.DanmakuCacheState
import dev.aaa1115910.bv.player.danmaku.model.DanmakuItem
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal class CacheStyle(
    val textSizePx: Float,
    val textSizeScale: Int,
    val fontWeight: DanmakuFontWeight,
    val strokeWidthPx: Float,
    val outlinePadPx: Float,
    val generation: Int,
)

internal class CacheManager(
    private val mainLooper: Looper,
    private val onRenderSign: () -> Unit,
) {
    private val mainHandler = Handler(mainLooper)
    private val thread = HandlerThread("Danmaku-Cache").apply {
        start()
        try { Process.setThreadPriority(threadId, Process.THREAD_PRIORITY_BACKGROUND) } catch (_: Exception) {}
    }
    private val handler: Handler = CacheHandler(thread.looper)
    private val pool = BitmapPool(maxBytes = CACHE_POOL_MAX_BYTES, maxCount = CACHE_POOL_MAX_COUNT)
    private val queueDepth = AtomicInteger(0)
    private val releaseQueue = ConcurrentLinkedQueue<PendingRelease>()
    @Volatile private var released = false

    // Cache paint (cache thread only)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT_BOLD
        isSubpixelText = true
    }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT_BOLD
        style = Paint.Style.STROKE
        isSubpixelText = true
    }
    private val fontMetrics = Paint.FontMetrics()
    private var cachedFontMetricsTextSize: Float = Float.NaN
    private var cachedPaintTextSize: Float = Float.NaN
    private var cachedStrokeWidth: Float = Float.NaN
    private val cacheCanvas = Canvas()
    private val renderSignRunnable = Runnable { onRenderSign() }

    fun queueDepth(): Int = queueDepth.get().coerceAtLeast(0)

    fun requestBuildCache(item: DanmakuItem, textWidthPx: Float, style: CacheStyle, releaseAtFrameId: Int) {
        queueDepth.incrementAndGet()
        handler.obtainMessage(MSG_BUILD_CACHE, CacheRequest(item, textWidthPx, style, releaseAtFrameId)).sendToTarget()
    }

    fun enqueueRelease(bitmap: Bitmap?, releaseAtFrameId: Int) {
        if (bitmap == null || bitmap.isRecycled) return
        releaseQueue.add(PendingRelease(bitmap, releaseAtFrameId))
    }

    fun drainReleasedBitmaps(currentFrameId: Int) {
        var drained = 0
        while (drained < MAX_RELEASE_PER_DRAIN) {
            val head = releaseQueue.peek() ?: break
            if (head.releaseAtFrameId > currentFrameId) break
            releaseQueue.poll()
            drained++
            val bmp = head.bitmap
            if (bmp.isRecycled) continue
            if (!pool.tryPut(bmp)) try { bmp.recycle() } catch (_: Exception) {}
        }
    }

    fun clear() {
        handler.removeCallbacksAndMessages(null)
        handler.sendEmptyMessage(MSG_CLEAR)
    }

    fun release() {
        released = true
        // 移除 mainHandler 上所有待处理的 renderSignRunnable，
        // 避免其闭包引用阻止 DanmakuView 的 GC。
        mainHandler.removeCallbacksAndMessages(null)
        handler.removeCallbacksAndMessages(null)
        handler.sendEmptyMessage(MSG_RELEASE)
    }

    /** 排空 releaseQueue 并 recycle 其中的所有 Bitmap */
    private fun drainReleaseQueue() {
        while (true) {
            val head = releaseQueue.poll() ?: break
            val bmp = head.bitmap
            if (!bmp.isRecycled) try { bmp.recycle() } catch (_: Exception) {}
        }
    }

    private inner class CacheHandler(looper: Looper) : Handler(looper) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                MSG_BUILD_CACHE -> {
                    val req = msg.obj as? CacheRequest ?: return
                    queueDepth.decrementAndGet()
                    buildCache(req)
                }
                MSG_CLEAR -> {
                    queueDepth.set(0)
                    pool.clear()
                    drainReleaseQueue()
                }
                MSG_RELEASE -> {
                    removeCallbacksAndMessages(null)
                    queueDepth.set(0)
                    pool.clear()
                    drainReleaseQueue()
                    try { thread.quitSafely() } catch (_: Exception) {}
                }
            }
        }
    }

    private fun buildCache(req: CacheRequest) {
        if (released) return
        val item = req.item
        val style = req.style
        val existing = item.cacheBitmap
        if (existing != null && !existing.isRecycled && item.cacheGeneration == style.generation) {
            item.cacheState = DanmakuCacheState.Rendered
            return
        }
        if (style.textSizePx <= 0f || !style.textSizePx.isFinite()) return

        val outlinePad = style.outlinePadPx.coerceAtLeast(0f)
        val strokeWidth = style.strokeWidthPx.coerceAtLeast(0f)
        val desiredTypeface = style.fontWeight.typeface
        if (fill.typeface != desiredTypeface) {
            fill.typeface = desiredTypeface
            cachedFontMetricsTextSize = Float.NaN
        }
        if (stroke.typeface != desiredTypeface) stroke.typeface = desiredTypeface

        // Compute effective font size: min(danmaku.textSize, 25) * (textSizeScale / 100)
        val clampedSize = min(item.data.textSize, 25)
        val scaleFactor = style.textSizeScale.coerceIn(25, 200) / 100f
        val effectiveTextSizePx = (style.textSizePx * clampedSize / 25f * scaleFactor).coerceAtLeast(1f)
        if (effectiveTextSizePx != cachedPaintTextSize) {
            fill.textSize = effectiveTextSizePx
            stroke.textSize = effectiveTextSizePx
            cachedPaintTextSize = effectiveTextSizePx
        }
        if (strokeWidth != cachedStrokeWidth) {
            stroke.strokeWidth = strokeWidth
            cachedStrokeWidth = strokeWidth
        }

        if (effectiveTextSizePx != cachedFontMetricsTextSize) {
            fill.getFontMetrics(fontMetrics)
            cachedFontMetricsTextSize = effectiveTextSizePx
        }
        val textHeightPx = (fontMetrics.descent - fontMetrics.ascent).coerceAtLeast(1f)
        val boxHeight = ceil(textHeightPx + outlinePad * 2f).toInt().coerceAtLeast(1)
        val boxWidth = ceil(req.textWidthPx.coerceAtLeast(outlinePad * 2f)).toInt().coerceAtLeast(1)

        val bmp = pool.acquire(boxWidth, boxHeight)
            ?: try { Bitmap.createBitmap(boxWidth, boxHeight, Bitmap.Config.ARGB_8888) } catch (_: Exception) { null }
            ?: return
        bmp.eraseColor(0x00000000)

        val canvas = cacheCanvas
        canvas.setBitmap(bmp)
        val rgb = item.data.color and 0xFFFFFF
        stroke.color = 0xCC shl 24
        fill.color = (0xFF shl 24) or rgb

        val baseline = outlinePad - fontMetrics.ascent
        val text = item.data.text
        if (text.isNotBlank()) {
            if (strokeWidth > 0.01f) canvas.drawText(text, outlinePad, baseline, stroke)
            canvas.drawText(text, outlinePad, baseline, fill)
        }

        val old = item.cacheBitmap
        item.cacheBitmap = bmp
        item.cacheGeneration = style.generation
        item.cacheState = DanmakuCacheState.Rendered
        if (old != null && old != bmp) enqueueRelease(old, req.releaseAtFrameId)

        mainHandler.post(renderSignRunnable)
    }

    private class CacheRequest(val item: DanmakuItem, val textWidthPx: Float, val style: CacheStyle, val releaseAtFrameId: Int)
    private class PendingRelease(val bitmap: Bitmap, val releaseAtFrameId: Int)

    private class BitmapPool(private val maxBytes: Long, private val maxCount: Int) {
        private val pool = ArrayDeque<Bitmap>()
        private var pooledBytes: Long = 0L

        @Synchronized
        fun acquire(minWidth: Int, minHeight: Int): Bitmap? {
            val it = pool.iterator()
            while (it.hasNext()) {
                val b = it.next()
                if (b.isRecycled) { it.remove(); continue }
                if (b.width >= minWidth && b.height >= minHeight && b.width - minWidth <= 48 && b.height - minHeight <= 24 && b.config == Bitmap.Config.ARGB_8888) {
                    it.remove()
                    pooledBytes -= b.allocationByteCount.toLong().coerceAtLeast(0L)
                    return b
                }
            }
            return null
        }

        @Synchronized
        fun tryPut(bitmap: Bitmap): Boolean {
            if (bitmap.isRecycled) return true
            val bytes = bitmap.allocationByteCount.toLong().coerceAtLeast(0L)
            if (bytes <= 0L || bytes > maxBytes) return false
            if (pool.size >= maxCount || pooledBytes + bytes > maxBytes) return false
            pool.addLast(bitmap)
            pooledBytes += bytes
            return true
        }

        @Synchronized
        fun clear() {
            val it = pool.iterator()
            while (it.hasNext()) {
                val b = it.next(); it.remove()
                try { if (!b.isRecycled) b.recycle() } catch (_: Exception) {}
            }
            pooledBytes = 0L
        }
    }

    companion object {
        private const val MSG_BUILD_CACHE = 2001
        private const val MSG_CLEAR = 2002
        private const val MSG_RELEASE = 2099
        private const val CACHE_POOL_MAX_BYTES = 50L * 1024L * 1024L
        private const val CACHE_POOL_MAX_COUNT = 72
        private const val MAX_RELEASE_PER_DRAIN = 24
    }
}
