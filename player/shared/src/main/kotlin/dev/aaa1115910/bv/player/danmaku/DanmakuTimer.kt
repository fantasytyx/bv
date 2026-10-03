package dev.aaa1115910.bv.player.danmaku

import kotlin.math.abs

/**
 * 弹幕时钟：把播放器位置转换成每帧的平滑位置。
 *
 * 逐帧位移只由系统时钟决定（`dt * 播放速率`），因此和播放器位置的逐帧抖动完全无关，
 * 滚动是匀速的；播放器位置只用来做"慢速纠偏"：
 * - 播放器时间轴与系统时钟存在长期速率差（音频时钟频率偏差、跳静音、倍速等）时偏差会累积，
 *   超过 [TRIM_ENTER_DRIFT_MS] 后进入校正，把偏差折算成一个极小的速率修正
 *   （最多 ±[MAX_RATE_DEVIATION]，即最多快/慢 5%），几个毫秒一帧地慢慢抹平；
 * - 偏差收敛到 [TRIM_EXIT_DRIFT_MS] 以下就退出校正，回到纯匀速，避免在零点附近来回修正；
 * - 只有真正的时间轴跳变（长时间卡顿后恢复等）才直接对齐。
 *
 * 相比早期"累积到 600ms 之后每帧最多修正 80ms"的做法，这里把纠偏提前到 150ms 并且限制
 * 在 5% 速率差以内：既不会出现"隔一段时间快速前进一次"，也不会因为跟随播放器位置抖动
 * 而一抖一抖（校正期间最多 5% 的速度差，肉眼几乎不可察觉）。
 */
internal class DanmakuTimer {
    private var lastFrameNanos: Long = 0L
    private var smoothPositionMs: Double = 0.0
    private var lastSeekSerial: Int = 0
    private var lastPlaying: Boolean = false
    private var isTrimming: Boolean = false

    /** 当前帧偏差（播放器位置 - 平滑位置），负值表示平滑位置跑在播放器前面 */
    var lastDriftMs: Double = 0.0
        private set

    /** 当前帧的速率修正（百分比，正数表示平滑时钟比标称速度快） */
    var lastTrimPercent: Double = 0.0
        private set

    /** 自 [resetDriftStats] 以来偏差的绝对值峰值 */
    var maxAbsDriftMs: Double = 0.0
        private set

    /** 自 [resetDriftStats] 以来因跳变而直接对齐的次数 */
    var hardResyncCount: Int = 0
        private set

    fun reset(positionMs: Long, nowNanos: Long, seekSerial: Int, isPlaying: Boolean) {
        lastFrameNanos = nowNanos
        smoothPositionMs = positionMs.coerceAtLeast(0L).toDouble()
        lastSeekSerial = seekSerial
        lastPlaying = isPlaying
        lastDriftMs = 0.0
        lastTrimPercent = 0.0
        isTrimming = false
    }

    fun resetDriftStats() {
        maxAbsDriftMs = 0.0
        hardResyncCount = 0
    }

    fun step(nowNanos: Long, rawPositionMs: Long, isPlaying: Boolean, playbackSpeed: Float, seekSerial: Int): Double {
        val raw = rawPositionMs.coerceAtLeast(0L).toDouble()
        val speed = normalizeSpeed(playbackSpeed)

        if (lastFrameNanos == 0L || seekSerial != lastSeekSerial) {
            reset(rawPositionMs, nowNanos, seekSerial, isPlaying)
            return smoothPositionMs
        }

        val dtNanos = (nowNanos - lastFrameNanos).coerceAtLeast(0L)
        lastFrameNanos = nowNanos
        lastSeekSerial = seekSerial

        // ---------- 暂停：位置完全由播放器决定 ----------
        if (!isPlaying) {
            alignTo(raw)
            lastPlaying = false
            return smoothPositionMs
        }

        // ---------- 恢复播放 / 首帧：直接对齐 ----------
        if (!lastPlaying) {
            alignTo(raw)
            lastPlaying = true
            return smoothPositionMs
        }

        // ---------- 正常播放：系统时钟匀速步进 + 滞回慢速校正 ----------
        val drift = raw - smoothPositionMs
        val absDrift = abs(drift)
        if (absDrift > maxAbsDriftMs) maxAbsDriftMs = absDrift

        // 真正的跳变（长时间卡顿后恢复等）：直接对齐，否则要几十秒才追得上
        if (absDrift >= HARD_RESYNC_THRESHOLD_MS) {
            hardResyncCount++
            alignTo(raw)
            return smoothPositionMs
        }

        // 滞回：偏差超过进入阈值才开始纠偏，收敛到退出阈值以下就停止，避免在阈值附近反复进出
        if (!isTrimming && absDrift >= TRIM_ENTER_DRIFT_MS) {
            isTrimming = true
        } else if (isTrimming && absDrift <= TRIM_EXIT_DRIFT_MS) {
            isTrimming = false
        }

        val trim = if (isTrimming) {
            (drift / TRIM_RESPONSE_MS).coerceIn(-MAX_RATE_DEVIATION, MAX_RATE_DEVIATION)
        } else {
            0.0
        }
        lastTrimPercent = trim * 100.0

        val dtMs = dtNanos / 1_000_000.0
        smoothPositionMs += dtMs * speed * (1.0 + trim)

        // 安全范围保护
        if (!smoothPositionMs.isFinite() || abs(smoothPositionMs) > 1e15) smoothPositionMs = raw
        if (smoothPositionMs < 0.0) smoothPositionMs = 0.0

        lastDriftMs = raw - smoothPositionMs
        if (abs(lastDriftMs) > maxAbsDriftMs) maxAbsDriftMs = abs(lastDriftMs)
        return smoothPositionMs
    }

    private fun alignTo(raw: Double) {
        smoothPositionMs = raw
        lastDriftMs = 0.0
        lastTrimPercent = 0.0
        isTrimming = false
    }

    private fun normalizeSpeed(playbackSpeed: Float): Double =
        if (playbackSpeed.isFinite() && playbackSpeed > 0f) playbackSpeed.toDouble() else 1.0

    private companion object {
        /** 超过该偏差说明时间轴真的跳了（例如跳过静音、长时间卡顿后恢复），直接对齐更接近画面 */
        const val HARD_RESYNC_THRESHOLD_MS = 500.0

        /** 偏差超过该值才开始纠偏：远小于肉眼可察觉的程度 */
        const val TRIM_ENTER_DRIFT_MS = 300.0

        /** 偏差收敛到该值以下就结束纠偏，滞回下界，避免在阈值附近反复进出校正 */
        const val TRIM_EXIT_DRIFT_MS = 50.0

        /** 偏差折算速率修正的时间常数：偏差达到 5x 该值时用满速率修正上限 */
        const val TRIM_RESPONSE_MS = 5000.0

        /** 速率修正上限：纠偏期间滚动速度最多比正常快/慢 5%，肉眼几乎不可察觉 */
        const val MAX_RATE_DEVIATION = 0.05
    }
}