package dev.aaa1115910.bv.player.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import dev.aaa1115910.bv.player.entity.AudioBalanceLevel
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue

private const val SAMPLE_RATE = 48_000
private const val CHANNELS = 2
private const val TONE_HZ = 1_000.0
private const val FLOAT_BYTES = 4
private const val BLOCK_FRAMES = SAMPLE_RATE / 10

/**
 * 用合成立体声正弦驱动处理器，逐 100 ms 统计块观察增益怎么随时间走——这是听感问题的直接来源。
 *
 * 用 1 kHz 是因为 K 加权在该频率约为 0 dB，测量值就等于幅度 dBFS，因此输入幅度直接就是响度，
 * 断言里不用再换算。音色与限制器行为不在单测覆盖范围内，仍按 AGENTS.md 以真机试听为准。
 */
class VolumeBalanceAudioProcessorTest {

    @Test
    fun `quiet prelude below the boost floor is never boosted`() {
        // -40 LUFS 的前奏低于 MIN_BOOST_LUFS，一点提升都不该发生
        val gains = runLevel(AudioBalanceLevel.Medium, segment(-40.0, 20.0))

        assertTrue(gains.max() < 0.1, "quiet prelude was boosted: $gains")
    }

    @Test
    fun `no boost until the measurement window is full`() {
        // 整段偏轻的素材应当被抬起来，但要等 5 秒窗口填满才开始，前 5 秒只观察
        val gains = runLevel(AudioBalanceLevel.Medium, segment(-26.0, 20.0))

        assertTrue(gains.take(50).max() < 0.1, "boosted before the window was full: ${gains.take(50)}")
        assertTrue(gains.drop(50).max() > 1.0, "never started normalising: ${gains.drop(50)}")
    }

    @Test
    fun `a programme that really is quiet is still normalised`() {
        // -26 LUFS 高于提升下限，仍然要按目标响度抬起来
        val gains = runLevel(AudioBalanceLevel.Medium, segment(-26.0, 25.0))

        assertTrue(
            gains.last() >= expectedCeilingDb(-26.0) - 0.5,
            "genuinely quiet programme was not normalised: ${gains.last()}"
        )
    }

    @Test
    fun `prelude boost is revoked once louder content is measured`() {
        val gains = runLevel(
            AudioBalanceLevel.Medium,
            segment(-24.0, 20.0),
            segment(-14.0, 20.0),
        )
        val prelude = gains.take(200)
        val loudPart = gains.drop(200)

        // 前奏高于提升下限，天花板允许提升，所以前奏期确实会被抬起来——这是已知残余
        assertTrue(
            prelude.max() >= expectedCeilingDb(-24.0) - 0.5,
            "prelude was not boosted: ${prelude.max()}"
        )
        // 响的内容出现后要快速退回，而不是按定档速率慢慢降（1.5 秒处应已回到 0 dB 附近）
        assertTrue(abs(loudPart[14]) <= 0.7, "boost was not revoked in time: ${loudPart[14]}")
    }

    @Test
    fun `quiet passage inside a loud programme is not boosted`() {
        val gains = runLevel(
            AudioBalanceLevel.Medium,
            segment(-14.0, 20.0),
            segment(-28.0, 20.0),
        )

        // 参照已经是 -14 LUFS，比它低的段落天花板为 0，因此不该出现提升
        val quietPassage = gains.drop(200)
        assertTrue(quietPassage.max() <= 0.1, "quiet passage was boosted: ${quietPassage.max()}")
    }

    @Test
    fun `a single loud block does not raise the reference`() {
        val gains = runLevel(
            AudioBalanceLevel.Medium,
            segment(-29.0, 10.0),
            segment(-12.0, 0.1),
            segment(-29.0, 20.0),
        )

        // 一记 100 ms 的爆点不该被当成「本节目更响的水平」。参照若被它钉死，天花板会掉到 0，
        // 末段增益就回不到该挡位允许的提升量
        assertTrue(
            gains.last() >= expectedCeilingDb(-29.0) - 0.5,
            "reference was pinned by a single block: ${gains.last()}"
        )
    }

    @Test
    fun `off level passes audio through untouched`() {
        val processor = VolumeBalanceAudioProcessor(AudioBalanceLevel.Off)
        processor.configure(AudioProcessor.AudioFormat(SAMPLE_RATE, CHANNELS, C.ENCODING_PCM_FLOAT))

        val samples = FloatArray(BLOCK_FRAMES * CHANNELS) { (it % 61 - 30) / 30.0f }
        val input = ByteBuffer.allocate(samples.size * FLOAT_BYTES).order(ByteOrder.nativeOrder())
        samples.forEach { input.putFloat(it) }
        input.flip()
        processor.queueInput(input)

        val output = processor.output.order(ByteOrder.nativeOrder())
        val actual = FloatArray(samples.size) { output.float }
        assertTrue(samples.contentEquals(actual), "Off level altered the audio")
    }

    private data class Segment(val amplitudeDb: Double, val seconds: Double)

    private fun segment(amplitudeDb: Double, seconds: Double) = Segment(amplitudeDb, seconds)

    /**
     * 参照为 [anchorLufs] 时该挡位允许的最大提升量：取「目标与参照之差」和挡位自身提升上限
     * 中的较小者。测试据此断言，挡位参数调整时不必跟着改数字。
     */
    private fun expectedCeilingDb(anchorLufs: Double) =
        minOf(AudioBalanceLevel.Medium.targetLufs - anchorLufs, AudioBalanceLevel.Medium.maxGainDb)

    /** 逐 100 ms 统计块返回输出相对输入的增益（dB），正好一格一块以便直接按秒定位 */
    private fun runLevel(level: AudioBalanceLevel, vararg segments: Segment): List<Double> {
        val processor = VolumeBalanceAudioProcessor(level)
        processor.configure(AudioProcessor.AudioFormat(SAMPLE_RATE, CHANNELS, C.ENCODING_PCM_FLOAT))

        val gains = ArrayList<Double>()
        for (piece in segments) {
            val amplitude = 10.0.pow(piece.amplitudeDb / 20.0)
            val totalFrames = (SAMPLE_RATE * piece.seconds).toInt()
            var frame = 0
            while (frame < totalFrames) {
                val frames = minOf(BLOCK_FRAMES, totalFrames - frame)
                val input =
                    ByteBuffer.allocate(frames * CHANNELS * FLOAT_BYTES).order(ByteOrder.nativeOrder())
                for (i in 0 until frames) {
                    val sample = amplitude * sin(2.0 * PI * TONE_HZ * (frame + i) / SAMPLE_RATE)
                    repeat(CHANNELS) { input.putFloat(sample.toFloat()) }
                }
                input.flip()
                processor.queueInput(input)
                gains += 20.0 * log10(rms(processor.output) / (amplitude / sqrt(2.0)))
                frame += frames
            }
        }
        return gains
    }

    private fun rms(buffer: ByteBuffer): Double {
        val floats = buffer.order(ByteOrder.nativeOrder())
        var sum = 0.0
        var count = 0
        while (floats.hasRemaining()) {
            val value = floats.float.toDouble()
            sum += value * value
            count++
        }
        return if (count == 0) 0.0 else sqrt(sum / count)
    }
}
