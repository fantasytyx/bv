package dev.aaa1115910.bv.player.danmaku

import android.graphics.Canvas
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Message
import android.util.Log
import android.view.Choreographer
import dev.aaa1115910.bv.player.danmaku.model.Danmaku
import dev.aaa1115910.bv.player.danmaku.model.RenderSnapshot
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicInteger

internal class DanmakuPlayer(private val view: DanmakuView) {

    private val cacheManager = CacheManager(
        mainLooper = Looper.getMainLooper(),
        onRenderSign = { view.requestRender() },
    )
    private val engine = DanmakuEngine(view.resources.displayMetrics, cacheManager)
    private val timer = DanmakuTimer()
    private val drawSemaphore = Semaphore(0)
    private val actionThread = HandlerThread("Danmaku-Action").apply { start() }
    private val actionHandler = ActionHandler(actionThread.looper)
    private val frameCallback = FrameCallback(actionHandler)
    private val seekSerial = AtomicInteger(0)
    private val uiFrameId = AtomicInteger(0)

    // Draw FPS stats (main thread)
    private var drawFrameCount: Int = 0
    private var drawDroppedFrames: Int = 0
    private var drawLastLogNanos: Long = System.nanoTime()
    private var drawLastFrameNanos: Long = 0L
    private val drawFrameDeadlineNanos: Long = 16_666_667L // ~60fps
    private var drawRepeatedSnapshotCount: Int = 0
    private var drawCurrentRepeatedSnapshotStreak: Int = 0
    private var drawMaxRepeatedSnapshotStreak: Int = 0
    private var drawSnapshotAgeTotalMs: Double = 0.0
    private var drawSnapshotAgeMaxMs: Double = 0.0
    private var drawLastSnapshotPositionMs: Double = Double.NEGATIVE_INFINITY

    @Volatile private var started: Boolean = false
    @Volatile private var released: Boolean = false
    @Volatile private var viewportWidth: Int = 0
    @Volatile private var viewportHeight: Int = 0
    @Volatile private var viewportTopInsetPx: Int = 0
    @Volatile private var viewportBottomInsetPx: Int = 0
    @Volatile private var latestConfig: DanmakuConfig? = null
    private var lastEnabled: Boolean = true

    fun startIfNeeded() {
        if (released || started) return
        started = true
        actionHandler.post { postFrameCallback() }
        view.requestRender()
    }

    fun stop() {
        if (!started) return
        started = false
        releaseSemaphoreIfNeeded()
        removeFrameCallback()
    }

    fun release() {
        if (released) return
        released = true; started = false
        releaseSemaphoreIfNeeded()
        try { actionHandler.obtainMessage(MSG_OP_RELEASE).sendToTarget() } catch (_: Exception) {}
    }

    fun onViewportChanged(width: Int, height: Int, topInsetPx: Int, bottomInsetPx: Int) {
        viewportWidth = width.coerceAtLeast(0)
        viewportHeight = height.coerceAtLeast(0)
        viewportTopInsetPx = topInsetPx.coerceAtLeast(0)
        viewportBottomInsetPx = bottomInsetPx.coerceAtLeast(0)
        actionHandler.removeMessages(MSG_OP_VIEWPORT)
        actionHandler.sendEmptyMessage(MSG_OP_VIEWPORT)
    }

    fun updateConfig(config: DanmakuConfig) {
        latestConfig = config
        actionHandler.removeMessages(MSG_OP_CONFIG)
        actionHandler.sendEmptyMessage(MSG_OP_CONFIG)
    }

    fun setDanmakus(list: List<Danmaku>) {
        actionHandler.obtainMessage(MSG_OP_SET, list).sendToTarget()
    }

    fun appendDanmakus(list: List<Danmaku>, maxItems: Int, alreadySorted: Boolean) {
        actionHandler.obtainMessage(MSG_OP_APPEND, AppendPayload(list, maxItems, alreadySorted)).sendToTarget()
    }

    fun trimToTimeRange(minTimeMs: Long, maxTimeMs: Long) {
        actionHandler.obtainMessage(MSG_OP_TRIM_RANGE, TrimRangePayload(minTimeMs, maxTimeMs)).sendToTarget()
    }

    fun seekTo(positionMs: Long) {
        seekSerial.incrementAndGet()
        actionHandler.obtainMessage(MSG_OP_SEEK, positionMs.toDouble()).sendToTarget()
    }

    fun draw(canvas: Canvas, rawPositionMs: Long, isPlaying: Boolean, playbackSpeed: Float, config: DanmakuConfig) {
        if (released) return
        if (!config.enabled) {
            if (lastEnabled || started) stop()
            if (lastEnabled) requestClear()
            lastEnabled = false; return
        }
        lastEnabled = true
        if (isPlaying) startIfNeeded()
        else if (started) stop()

        val frameId = uiFrameId.incrementAndGet()
        engine.drainReleasedBitmaps(frameId)
        val smoothPos = timer.step(System.nanoTime(), rawPositionMs, isPlaying, playbackSpeed, seekSerial.get())
        engine.stepTime(smoothPos, frameId)
        drawSemaphore.tryAcquire()
        val snapshot = engine.renderSnapshot()
        releaseSemaphoreIfNeeded()

        // 日志统计：FPS、丢帧、画重复快照的次数、快照过时程度、内存使用等。开启后可以通过 logcat 观察这些指标的变化，帮助分析性能瓶颈和优化效果。
        if (DanmakuLogStats.logEnabled) {
            val drawNow = System.nanoTime()
            drawFrameCount++
            if (drawLastFrameNanos > 0L) {
                val delta = drawNow - drawLastFrameNanos
                if (delta > drawFrameDeadlineNanos * 2) drawDroppedFrames++
            }
            drawLastFrameNanos = drawNow
            sampleDrawSnapshotStats(snapshot.positionMs, smoothPos)
            if (drawNow - drawLastLogNanos >= 1_000_000_000L) {
                val elapsed = (drawNow - drawLastLogNanos) / 1_000_000_000.0
                val fps = drawFrameCount / elapsed
                val avgSnapshotAgeMs = if (drawFrameCount > 0) drawSnapshotAgeTotalMs / drawFrameCount else 0.0

                // 如果 sameSnap 基本是 0，说明现有 acquire 模式几乎没有重复旧快照。
                Log.d(
                    TAG,
                    "[Draw] fps=%.1f  frames=%d  dropped=%d  sameSnap=%d  maxSameSnapStreak=%d  snapAgeMs(avg/max)=%.1f/%.1f  mem=%s".format(
                        fps,
                        drawFrameCount,
                        drawDroppedFrames,
                        drawRepeatedSnapshotCount, // 这一秒里重复画到同一个 snapshot 的次数
                        drawMaxRepeatedSnapshotStreak, // 连续重复同一个 snapshot 的最长次数
                        avgSnapshotAgeMs, // 平均每一帧画的 snapshot 和当前时间的差距，单位毫秒。这个值越大说明越多帧在画过时的弹幕，可能会有明显的卡顿感。
                        drawSnapshotAgeMaxMs, // 这一秒里画过的 snapshot 中，最过时的那个和当前时间的差距，单位毫秒。这个值越大说明偶尔会有非常过时的弹幕被画出来，可能会有明显的卡顿尖峰。
                        DanmakuLogStats.memoryUsageSummary(),
                    )
                )
                drawFrameCount = 0
                drawDroppedFrames = 0
                drawLastLogNanos = drawNow
                resetDrawSnapshotStats()
            }
        }
        engine.draw(canvas, snapshot, config)
    }

    private fun sampleDrawSnapshotStats(snapshotPositionMs: Double, smoothPos: Double) {
        if (snapshotPositionMs == drawLastSnapshotPositionMs) {
            drawRepeatedSnapshotCount++
            drawCurrentRepeatedSnapshotStreak++
            if (drawCurrentRepeatedSnapshotStreak > drawMaxRepeatedSnapshotStreak) {
                drawMaxRepeatedSnapshotStreak = drawCurrentRepeatedSnapshotStreak
            }
        } else {
            drawCurrentRepeatedSnapshotStreak = 0
            drawLastSnapshotPositionMs = snapshotPositionMs
        }

        val snapshotAgeMs = (smoothPos - snapshotPositionMs).coerceAtLeast(0.0)
        drawSnapshotAgeTotalMs += snapshotAgeMs
        if (snapshotAgeMs > drawSnapshotAgeMaxMs) drawSnapshotAgeMaxMs = snapshotAgeMs
    }

    private fun resetDrawSnapshotStats() {
        drawRepeatedSnapshotCount = 0
        drawCurrentRepeatedSnapshotStreak = 0
        drawMaxRepeatedSnapshotStreak = 0
        drawSnapshotAgeTotalMs = 0.0
        drawSnapshotAgeMaxMs = 0.0
        drawLastSnapshotPositionMs = Double.NEGATIVE_INFINITY
    }

    private fun postFrameCallback() {
        if (released || !started) return
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    private fun requestClear() {
        if (released) return
        actionHandler.removeMessages(MSG_OP_CLEAR)
        actionHandler.sendEmptyMessage(MSG_OP_CLEAR)
    }

    private fun removeFrameCallback() {
        if (released) return
        try {
            actionHandler.post {
                try { Choreographer.getInstance().removeFrameCallback(frameCallback) } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }

    private fun releaseSemaphoreIfNeeded() {
        if (drawSemaphore.availablePermits() == 0) drawSemaphore.release()
    }

    private inner class ActionHandler(looper: Looper) : Handler(looper) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                MSG_FRAME_UPDATE -> {
                    if (released || !started) return
                    postFrameCallback()
                    try {
                        drawSemaphore.acquire()
                        if (released || !started) return
                        engine.act()
                        view.requestRender()
                    } catch (_: InterruptedException) {}
                }
                MSG_OP_SET -> {
                    @Suppress("UNCHECKED_CAST")
                    engine.setDanmakus(msg.obj as? List<Danmaku> ?: emptyList())
                    renderOnceIfPaused()
                }
                MSG_OP_APPEND -> {
                    val p = msg.obj as? AppendPayload ?: return
                    engine.appendDanmakus(p.list, p.maxItems, p.alreadySorted)
                    renderOnceIfPaused()
                }
                MSG_OP_TRIM_RANGE -> {
                    val p = msg.obj as? TrimRangePayload ?: return
                    engine.trimToTimeRange(p.minTimeMs, p.maxTimeMs)
                    renderOnceIfPaused()
                }
                MSG_OP_SEEK -> {
                    val pos = (msg.obj as? Double) ?: 0.0
                    engine.seekTo(pos)
                    renderOnceIfPaused(pos)
                }
                MSG_OP_CLEAR -> engine.clear()
                MSG_OP_VIEWPORT -> {
                    engine.updateViewport(viewportWidth, viewportHeight, viewportTopInsetPx, viewportBottomInsetPx)
                    renderOnceIfPaused()
                }
                MSG_OP_CONFIG -> {
                    latestConfig?.let { newCfg ->
                        val oldCfg = engine.config
                        engine.updateConfig(newCfg)
                        // Only seekTo (clear + re-spawn) if layout-affecting properties changed.
                        // Opacity is appearance-only and should not cause a full reset.
                        val layoutChanged = oldCfg.enabled != newCfg.enabled ||
                            oldCfg.textSizeSp != newCfg.textSizeSp ||
                            oldCfg.textSizeScale != newCfg.textSizeScale ||
                            oldCfg.fontWeight != newCfg.fontWeight ||
                            oldCfg.strokeWidthPx != newCfg.strokeWidthPx ||
                            oldCfg.durationMultiplier != newCfg.durationMultiplier ||
                            oldCfg.area != newCfg.area ||
                            oldCfg.laneDensity != newCfg.laneDensity ||
                            oldCfg.allowScroll != newCfg.allowScroll ||
                            oldCfg.allowTop != newCfg.allowTop ||
                            oldCfg.allowBottom != newCfg.allowBottom ||
                            oldCfg.minLevel != newCfg.minLevel
                        if (layoutChanged) {
                            engine.seekTo(engine.currentPositionMs())
                        }
                        renderOnceIfPaused()
                    }
                }
                MSG_OP_RELEASE -> {
                    removeCallbacksAndMessages(null)
                    Choreographer.getInstance().removeFrameCallback(frameCallback)
                    started = false
                    try { actionThread.quitSafely() } catch (_: Exception) {}
                    engine.release()
                    cacheManager.release()
                }
            }
        }

        private fun renderOnceIfPaused(positionMs: Double? = null) {
            if (released || started) return
            val pos = positionMs ?: engine.currentPositionMs()
            engine.stepTime(pos, uiFrameId.get())
            try { engine.act() } catch (_: Exception) {}
            view.requestRender()
        }
    }

    private class FrameCallback(private val handler: Handler) : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            handler.removeMessages(MSG_FRAME_UPDATE)
            handler.sendEmptyMessage(MSG_FRAME_UPDATE)
        }
    }

    private class AppendPayload(val list: List<Danmaku>, val maxItems: Int, val alreadySorted: Boolean)
    private class TrimRangePayload(val minTimeMs: Long, val maxTimeMs: Long)

    companion object {
        private const val TAG = "DanmakuPlayer"
        private const val MSG_FRAME_UPDATE = 2101
        private const val MSG_OP_SET = 3101
        private const val MSG_OP_APPEND = 3102
        private const val MSG_OP_TRIM_RANGE = 3103
        private const val MSG_OP_SEEK = 3105
        private const val MSG_OP_CLEAR = 3106
        private const val MSG_OP_VIEWPORT = 3201
        private const val MSG_OP_CONFIG = 3202
        private const val MSG_OP_RELEASE = 3999
    }
}
