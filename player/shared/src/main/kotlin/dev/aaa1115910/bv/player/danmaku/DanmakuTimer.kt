package dev.aaa1115910.bv.player.danmaku

import kotlin.math.abs

/**
 * 弹幕时钟：把播放器位置转换成每帧的平滑位置。
 *
 * 弹幕位置最终只能以播放器时间为准（否则和画面不同步），但播放器位置本身有抖动，
 * 所以这里做的是"带速率限制的跟随"而不是自由积分：每帧把偏差折算成一个很小的
 * 速率修正（最多 ±30%）。这样
 * - 播放器位置的高频抖动被压低，滚动依旧平滑；
 * - 播放器时间轴与系统时钟之间的长期速率差（跳静音、倍速、音频时钟与系统时钟的
 *   频率差等）会被持续吸收，偏差稳定在几十毫秒量级；
 * 不会像自由积分那样先线性累积到几百毫秒、再一次性追赶（那会造成明显的加速滚动）。
 */
internal class DanmakuTimer {
    private var lastFrameNanos: Long = 0L
    private var smoothPositionMs: Double = 0.0
    private var lastSeekSerial: Int = 0
    private var lastPlaying: Boolean = false

    /** 当前帧偏差（播放器位置 - 平滑位置），负值表示平滑位置跑在播放器前面 */
    var lastDriftMs: Double = 0.0
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

        // ---------- 正常播放：基础步进 + 限速跟随 ----------
        val drift = raw - smoothPositionMs
        lastDriftMs = drift
        val absDrift = abs(drift)
        if (absDrift > maxAbsDriftMs) maxAbsDriftMs = absDrift

        // 真正的跳变（长时间卡顿后恢复等）：直接对齐，否则要几十秒才追得上
        if (absDrift >= HARD_RESYNC_THRESHOLD_MS) {
            hardResyncCount++
            alignTo(raw)
            return smoothPositionMs
        }

        val dtMs = dtNanos / 1_000_000.0
        val rateDeviation = (drift / RESPONSE_TIME_MS).coerceIn(-MAX_RATE_DEVIATION, MAX_RATE_DEVIATION)
        smoothPositionMs += dtMs * speed * (1.0 + rateDeviation)

        // 安全范围保护
        if (!smoothPositionMs.isFinite() || abs(smoothPositionMs) > 1e15) smoothPositionMs = raw
        if (smoothPositionMs < 0.0) smoothPositionMs = 0.0
        return smoothPositionMs
    }

    private fun alignTo(raw: Double) {
        smoothPositionMs = raw
        lastDriftMs = 0.0
    }

    private fun normalizeSpeed(playbackSpeed: Float): Double =
        if (playbackSpeed.isFinite() && playbackSpeed > 0f) playbackSpeed.toDouble() else 1.0

    private companion object {
        /** 超过该偏差说明时间轴真的跳了（例如跳过静音、seek 之外的突变），直接对齐更接近画面 */
        const val HARD_RESYNC_THRESHOLD_MS = 500.0

        /** 偏差折算速率修正的时间常数：偏差达到该值时用满 [MAX_RATE_DEVIATION] 追赶 */
        const val RESPONSE_TIME_MS = 250.0

        /** 速率修正上限：滚动速度最多比正常快/慢 30%，视觉上几乎不可察觉 */
        const val MAX_RATE_DEVIATION = 0.3
    }
}