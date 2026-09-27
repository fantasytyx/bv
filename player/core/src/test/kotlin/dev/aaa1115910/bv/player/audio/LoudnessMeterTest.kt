package dev.aaa1115910.bv.player.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LoudnessMeterTest {

    @Test
    fun `stereo 1kHz sine at -20 dBFS reads about -20 LUFS`() {
        // BS.1770 的 -0.691 偏移正是为了让该信号读数为 -20 LUFS
        val lufs = measure(amplitude = 0.1, channels = 2)!!
        assertTrue(abs(lufs + 20.0) < 0.5, "expected about -20 LUFS, got $lufs")
    }

    @Test
    fun `doubling amplitude adds about 6 LU`() {
        val quiet = measure(amplitude = 0.1, channels = 2)!!
        val loud = measure(amplitude = 0.2, channels = 2)!!
        assertEquals(6.02, loud - quiet, 0.1)
    }

    @Test
    fun `stereo reads about 3 LU higher than mono`() {
        val mono = measure(amplitude = 0.1, channels = 1)!!
        val stereo = measure(amplitude = 0.1, channels = 2)!!
        assertEquals(3.01, stereo - mono, 0.1)
    }

    @Test
    fun `silence is gated out`() {
        assertNull(measure(amplitude = 0.0, channels = 2))
    }

    @Test
    fun `signal below the absolute gate is ignored`() {
        // 约 -100 dBFS，远低于 -70 LUFS 绝对门限
        assertNull(measure(amplitude = 1e-5, channels = 2))
    }

    @Test
    fun `peak block loudness is never below the gated integrated loudness`() {
        val sampleRate = 48_000
        val meter = LoudnessMeter(sampleRate, 2)
        // 前 2 秒安静、后 2 秒响（相差 6 dB，不会被相对门限剔除）
        for (frame in 0 until sampleRate * 4) {
            val amplitude = if (frame < sampleRate * 2) 0.1 else 0.2
            val sample = amplitude * sin(2.0 * PI * 1_000.0 * frame / sampleRate)
            var frameSumSquares = 0.0
            for (channel in 0 until 2) {
                val weighted = meter.weight(channel, sample)
                frameSumSquares += weighted * weighted
            }
            meter.endFrame(frameSumSquares)
        }

        val integrated = meter.loudnessLufs()!!
        val peak = meter.peakBlockLufs()!!
        // 起音阶段用 peak 代替 integrated，靠这条不变量保证交接时增益只会向上微调
        assertTrue(peak > integrated, "peak=$peak integrated=$integrated")
        assertTrue(peak - integrated > 1.0, "peak=$peak integrated=$integrated")
    }

    private fun measure(amplitude: Double, channels: Int, seconds: Double = 6.0): Double? {
        val sampleRate = 48_000
        val meter = LoudnessMeter(sampleRate, channels)
        val frames = (sampleRate * seconds).toInt()
        for (frame in 0 until frames) {
            val sample = amplitude * sin(2.0 * PI * 1_000.0 * frame / sampleRate)
            var frameSumSquares = 0.0
            for (channel in 0 until channels) {
                val weighted = meter.weight(channel, sample)
                frameSumSquares += weighted * weighted
            }
            meter.endFrame(frameSumSquares)
        }
        return meter.loudnessLufs()
    }
}
