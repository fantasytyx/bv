package dev.aaa1115910.bv.player.danmaku

import kotlin.math.abs

internal class DanmakuTimer {
    private var lastFrameNanos: Long = 0L
    private var smoothPositionMs: Double = 0.0
    private var lastSeekSerial: Int = 0
    private var lastPlaying: Boolean = false
    private var lastPlaybackSpeed: Double = 1.0
    private var pauseStartNanos: Long = 0L
    private var isCorrecting: Boolean = false

    fun reset(positionMs: Long, nowNanos: Long, seekSerial: Int, isPlaying: Boolean, playbackSpeed: Float) {
        lastFrameNanos = nowNanos
        smoothPositionMs = positionMs.coerceAtLeast(0L).toDouble()
        lastSeekSerial = seekSerial
        lastPlaying = isPlaying
        lastPlaybackSpeed = normalizeSpeed(playbackSpeed)
        pauseStartNanos = 0L
        // 任何重置都退出校正状态
        isCorrecting = false
    }

    fun step(nowNanos: Long, rawPositionMs: Long, isPlaying: Boolean, playbackSpeed: Float, seekSerial: Int): Double {
        val raw = rawPositionMs.coerceAtLeast(0L).toDouble()
        val speed = normalizeSpeed(playbackSpeed)

        if (lastFrameNanos == 0L || seekSerial != lastSeekSerial) {
            reset(rawPositionMs, nowNanos, seekSerial, isPlaying, playbackSpeed)
            return smoothPositionMs
        }

        val dtNanos = (nowNanos - lastFrameNanos).coerceAtLeast(0L)
        lastFrameNanos = nowNanos
        lastSeekSerial = seekSerial

        // ---------- 暂停处理 ----------
        if (!isPlaying) {
            if (lastPlaying) {
                pauseStartNanos = nowNanos
            }
            val pausedDurationNanos = nowNanos - pauseStartNanos
            if (pausedDurationNanos >= PAUSE_REANCHOR_DELAY_NANOS &&
                abs(raw - smoothPositionMs) >= IDLE_REANCHOR_THRESHOLD_MS
            ) {
                smoothPositionMs = raw
            }
            lastPlaying = false
            lastPlaybackSpeed = speed
            return smoothPositionMs
        }

        // ---------- 播放状态恢复 / 速度变化， 重新锚定----------
        if (!lastPlaying || abs(speed - lastPlaybackSpeed) >= SPEED_CHANGE_EPSILON) {
            smoothPositionMs = raw
            lastPlaying = true
            lastPlaybackSpeed = speed
            pauseStartNanos = 0L
            isCorrecting = false
            return smoothPositionMs
        }

        // ---------- 正常播放：基于时间步进 ----------
        if (dtNanos > 0L) {
            smoothPositionMs += dtNanos.toDouble() / 1_000_000.0 * speed
        }

        // 安全范围保护
        if (!smoothPositionMs.isFinite() || abs(smoothPositionMs) > 1e15) smoothPositionMs = raw
        if (smoothPositionMs < 0.0) smoothPositionMs = 0.0

        // ---------- 滞回渐进式校正 ----------
        val drift = raw - smoothPositionMs
        val absDrift = abs(drift)

        // 进入校正：偏差超过大阈值
        if (!isCorrecting && absDrift >= EXTREME_DRIFT_REANCHOR_THRESHOLD_MS) {
            isCorrecting = true
        }

        // 处于校正状态时才施加渐进校正
        if (isCorrecting) {
            val correction = drift * CORRECTION_FACTOR
            smoothPositionMs += correction.coerceIn(-MAX_CORRECTION_MS, MAX_CORRECTION_MS)

            // 退出校正：偏差已收敛到一半阈值以下
            if (abs(raw - smoothPositionMs) <= IDLE_REANCHOR_THRESHOLD_MS) {
                isCorrecting = false
            }
        }

        lastPlaying = true
        lastPlaybackSpeed = speed
        return smoothPositionMs
    }

    private fun normalizeSpeed(playbackSpeed: Float): Double =
        if (playbackSpeed.isFinite() && playbackSpeed > 0f) playbackSpeed.toDouble() else 1.0

    private companion object {
        const val IDLE_REANCHOR_THRESHOLD_MS = 120.0
        const val EXTREME_DRIFT_REANCHOR_THRESHOLD_MS = 500.0
        const val CORRECTION_FACTOR = 0.1
        const val MAX_CORRECTION_MS = 33.0
        const val SPEED_CHANGE_EPSILON = 0.0001
        const val PAUSE_REANCHOR_DELAY_NANOS = 500_000_000L
    }
}