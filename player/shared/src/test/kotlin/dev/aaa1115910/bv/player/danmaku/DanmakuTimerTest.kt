package dev.aaa1115910.bv.player.danmaku

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// 取整数毫秒的帧间隔：播放器位置以毫秒整数给出，这样可以避免 Long 截断带来的额外误差
private const val FRAME_NANOS = 16_000_000L
private const val FRAME_MS = FRAME_NANOS / 1_000_000.0

/**
 * 弹幕时钟契约：
 * 1. 逐帧位移只由系统时钟决定，播放器位置的逐帧抖动不得影响位移（否则会"一抖一抖"）；
 * 2. 播放器时间轴与系统时钟存在长期速率差时，偏差要被慢慢抹平，纠偏速率不超过 5%，
 *    不允许出现"隔一段时间快速前进一次"；
 * 3. 真正的时间轴跳变（卡顿后恢复等）直接对齐。
 */
class DanmakuTimerTest {

    @Test
    fun `player position jitter never reaches the frame step`() {
        val sim = ClockSim()
        val random = Random(20261002)
        var idealMs = 0.0
        repeat(600) {
            // 模拟真实播放器位置：量化到 32ms 台阶的音频时钟 + ±40ms 抖动，平均速率与系统时钟一致
            val raw = quantize(idealMs, 32.0) + random.nextDouble(-40.0, 40.0)
            sim.step(raw)
            idealMs += FRAME_MS
        }

        assertTrue(sim.timer.maxAbsDriftMs < 150.0, "drift=${sim.timer.maxAbsDriftMs} 不应进入纠偏")
        assertEquals(0.0, sim.trimPercents.max(), 1e-9, "抖动量级不应触发纠偏")
        sim.stepsMs.forEach { assertEquals(FRAME_MS, it, 1e-6, "逐帧位移必须是恒定的 dt") }
    }

    @Test
    fun `long term rate mismatch is trimmed without fast forward`() {
        val sim = ClockSim()
        var mediaMs = 0.0
        var trimStarts = 0
        var trimming = false
        repeat(60 * 600) { // 600 秒
            sim.step(mediaMs)
            mediaMs += FRAME_MS * 1.002 // 播放器时间轴比系统时钟快 0.2%
            val nowTrimming = sim.timer.lastTrimPercent != 0.0
            if (nowTrimming && !trimming) trimStarts++
            trimming = nowTrimming
        }

        // 偏差被限制在纠偏阈值附近，不会像自由积分那样累积到肉眼可察的数百毫秒
        assertTrue(sim.timer.maxAbsDriftMs < 155.0, "drift=${sim.timer.maxAbsDriftMs}")
        // 逐帧位移最多比标称快 5%，不存在"一段时间快速前进一次"
        assertTrue(sim.maxStepRatio() <= 1.05 + 1e-9, "maxStepRatio=${sim.maxStepRatio()}")
        assertTrue(sim.trimPercents.max() <= 5.0 + 1e-9, "trim=${sim.trimPercents.max()}")
        assertEquals(0, sim.timer.hardResyncCount)
        // 纠偏是长周期的小幅修正，不是频繁的快速前进
        assertTrue(trimStarts in 1..20, "trimStarts=$trimStarts")
    }

    @Test
    fun `larger rate mismatch is still absorbed without fast forward`() {
        val sim = ClockSim()
        var mediaMs = 0.0
        repeat(60 * 300) { // 300 秒
            sim.step(mediaMs)
            mediaMs += FRAME_MS * 1.02 // 播放器时间轴比系统时钟快 2%（现实偏差的 10 倍以上）
        }

        assertTrue(sim.timer.maxAbsDriftMs < 155.0, "drift=${sim.timer.maxAbsDriftMs}")
        assertTrue(sim.maxStepRatio() <= 1.05 + 1e-9, "maxStepRatio=${sim.maxStepRatio()}")
        assertEquals(0, sim.timer.hardResyncCount)
    }

    @Test
    fun `timeline jump re-anchors instead of crawling`() {
        val sim = ClockSim()
        var mediaMs = 0.0
        repeat(60) { sim.step(mediaMs); mediaMs += FRAME_MS }

        mediaMs += 2_000.0 // 长时间卡顿后播放器时间轴跳了 2 秒
        val afterJump = sim.step(mediaMs)
        assertEquals(mediaMs, afterJump, 1e-6)
        assertEquals(1, sim.timer.hardResyncCount)

        mediaMs += FRAME_MS
        val next = sim.step(mediaMs)
        assertEquals(FRAME_MS, next - afterJump, 1e-6, "跳变后应立刻回到匀速")
    }

    @Test
    fun `pause aligns to the player and resume continues from it`() {
        val sim = ClockSim()
        var mediaMs = 0.0
        repeat(60) { sim.step(mediaMs); mediaMs += FRAME_MS }

        // 暂停：播放器位置冻结，弹幕时钟必须完全对齐到暂停的位置
        val pausedAt = mediaMs
        repeat(30) { assertEquals(pausedAt, sim.step(pausedAt, isPlaying = false), 1e-6, "暂停时位置由播放器决定") }

        // 恢复播放：直接对齐一次，之后继续匀速
        val resumed = sim.step(pausedAt)
        assertEquals(pausedAt, resumed, 1e-6)
        mediaMs += FRAME_MS
        assertEquals(FRAME_MS, sim.step(mediaMs) - resumed, 1e-6, "恢复播放后应继续匀速")
    }

    @Test
    fun `seek realigns to the new position`() {
        val sim = ClockSim()
        var mediaMs = 0.0
        repeat(60) { sim.step(mediaMs); mediaMs += FRAME_MS }

        mediaMs = 120_000.0
        sim.seekSerial++
        val afterSeek = sim.step(mediaMs)

        assertEquals(mediaMs, afterSeek, 1e-6)
        assertEquals(0.0, sim.timer.lastDriftMs, 1e-9)
        assertEquals(0.0, sim.timer.lastTrimPercent, 1e-9)
    }

    @Test
    fun `playback speed scales the frame step`() {
        val sim = ClockSim()
        var mediaMs = 0.0
        repeat(120) {
            sim.step(mediaMs, speed = 2f)
            mediaMs += FRAME_MS * 2
        }

        assertEquals(0.0, sim.timer.lastTrimPercent, 1e-9)
        sim.stepsMs.forEach { assertEquals(FRAME_MS * 2, it, 1e-6) }
    }

    private fun quantize(valueMs: Double, stepMs: Double): Double = (valueMs / stepMs).toInt() * stepMs
}

/** 以固定帧间隔喂入播放器位置，记录弹幕时钟的逐帧位移与纠偏速率。 */
private class ClockSim {
    val timer = DanmakuTimer()
    val stepsMs = ArrayList<Double>()
    val trimPercents = ArrayList<Double>()
    var seekSerial = 0

    private var nowNanos = 0L
    private var lastPosition = 0.0
    private var primed = false

    fun step(rawPositionMs: Double, isPlaying: Boolean = true, speed: Float = 1f): Double {
        nowNanos += FRAME_NANOS
        val position = timer.step(nowNanos, rawPositionMs.toLong(), isPlaying, speed, seekSerial)
        if (primed) stepsMs.add(position - lastPosition)
        primed = true
        lastPosition = position
        trimPercents.add(timer.lastTrimPercent)
        return position
    }

    /** 逐帧位移相对标称位移（dt * speed）的最大比值 */
    fun maxStepRatio(): Double = stepsMs.maxOf { it / FRAME_MS }
}
