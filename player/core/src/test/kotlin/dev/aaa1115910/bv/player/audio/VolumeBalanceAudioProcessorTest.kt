package dev.aaa1115910.bv.player.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import dev.aaa1115910.bv.player.entity.AudioBalanceLevel
import dev.aaa1115910.bv.player.entity.AudioLoudness
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val SAMPLE_RATE = 48_000
private const val CHANNELS = 2

/** +6 dB 对应的线性增益 */
private val GAIN_6DB = 10.0.pow(6.0 / 20.0)

private val FLOAT_FORMAT =
    AudioProcessor.AudioFormat(SAMPLE_RATE, CHANNELS, C.ENCODING_PCM_FLOAT)
private val SHORT_FORMAT =
    AudioProcessor.AudioFormat(SAMPLE_RATE, CHANNELS, C.ENCODING_PCM_16BIT)

/**
 * 处理器只做「乘静态增益 + 斜坡」，因此测试关心四件事：旁路是否真的不动数据、
 * 起播是否即准、换挡是否平滑、两种 PCM 编码与不足一帧的尾巴是否都正确。
 */
class VolumeBalanceAudioProcessorTest {

    /** 目标增益为 [gainDb] 的一份元数据；真峰值取得很宽，避免夹取干扰增益断言 */
    private fun loudnessWith(gainDb: Double) = AudioLoudness(
        measuredI = -20.0,
        measuredTp = -20.0,
        targetI = -20.0 + gainDb,
        targetTp = -1.0,
        normalTargetI = null,
        highDynamicTargetI = null,
        undersizedTargetI = null,
    )

    private fun floatBuffer(value: Float, frames: Int): ByteBuffer {
        val buffer = ByteBuffer.allocate(frames * CHANNELS * 4).order(ByteOrder.nativeOrder())
        repeat(frames * CHANNELS) { buffer.putFloat(value) }
        buffer.flip()
        return buffer
    }

    /** 连续无规律采样，用来验证「原样透传」 */
    private fun variedFloatBuffer(frames: Int): ByteBuffer {
        val buffer = ByteBuffer.allocate(frames * CHANNELS * 4).order(ByteOrder.nativeOrder())
        var sample = 0
        repeat(frames * CHANNELS) {
            sample = (sample * 1103515245 + 12345) and 0x7fffffff
            buffer.putFloat((sample % 2001 - 1000) / 1000.0f)
        }
        buffer.flip()
        return buffer
    }

    private fun floatOutput(processor: VolumeBalanceAudioProcessor): FloatArray {
        val buffer = processor.output.order(ByteOrder.nativeOrder())
        return FloatArray(buffer.remaining() / 4) { buffer.float }
    }

    private fun byteOutput(processor: VolumeBalanceAudioProcessor): ByteArray {
        val buffer = processor.output.order(ByteOrder.nativeOrder())
        return ByteArray(buffer.remaining()) { buffer.get() }
    }

    @Test
    fun `off level passes audio through untouched`() {
        val processor = VolumeBalanceAudioProcessor(AudioBalanceLevel.Off)
        processor.configure(FLOAT_FORMAT)

        val samples = FloatArray(64 * CHANNELS) { (it % 61 - 30) / 30.0f }
        val input = ByteBuffer.allocate(samples.size * 4).order(ByteOrder.nativeOrder())
        samples.forEach { input.putFloat(it) }
        input.flip()

        processor.queueInput(input)

        assertTrue(
            samples.contentEquals(floatOutput(processor)),
            "Off level altered the audio",
        )
    }

    @Test
    fun `null loudness passes audio through untouched`() {
        val processor = VolumeBalanceAudioProcessor(AudioBalanceLevel.Standard)
        processor.setLoudness(null)
        processor.configure(FLOAT_FORMAT)

        val input = variedFloatBuffer(64)
        val expected = ByteArray(input.remaining())
        input.duplicate().get(expected)

        processor.queueInput(input)

        assertTrue(expected.contentEquals(byteOutput(processor)), "no metadata altered the audio")
    }

    @Test
    fun `invalid measured i never produces a gain`() {
        val processor = VolumeBalanceAudioProcessor(AudioBalanceLevel.Standard)
        processor.setLoudness(loudnessWith(6.0).copy(measuredI = Double.NaN))
        processor.configure(FLOAT_FORMAT)

        val input = variedFloatBuffer(64)
        val expected = ByteArray(input.remaining())
        input.duplicate().get(expected)

        processor.queueInput(input)

        assertTrue(expected.contentEquals(byteOutput(processor)), "NaN loudness altered the audio")
    }

    @Test
    fun `applies static gain from the first sample`() {
        val processor = VolumeBalanceAudioProcessor(AudioBalanceLevel.Standard)
        processor.setLoudness(loudnessWith(6.0))
        processor.configure(FLOAT_FORMAT)

        processor.queueInput(floatBuffer(0.25f, 8))
        val output = floatOutput(processor)

        // 起播即准：第一个采样就已经是目标增益，不存在爬升
        assertEquals(0.25 * GAIN_6DB, output[0].toDouble(), 0.003)
        assertEquals(0.25 * GAIN_6DB, output.last().toDouble(), 0.003)
    }

    @Test
    fun `ramps to a new level instead of stepping`() {
        val frames = (SAMPLE_RATE * 0.05).toInt()

        val processor = VolumeBalanceAudioProcessor(AudioBalanceLevel.Standard)
        processor.configure(FLOAT_FORMAT)
        processor.queueInput(floatBuffer(0.25f, 8))
        floatOutput(processor)

        processor.setLoudness(loudnessWith(6.0))
        processor.queueInput(floatBuffer(0.25f, frames))
        val output = floatOutput(processor)

        val gainFirst = output[0] / 0.25
        val gainLast = output.last() / 0.25

        assertTrue(gainFirst < 1.01, "gain jumped on the first frame: $gainFirst")
        assertTrue(gainLast > 1.98, "gain did not arrive within the ramp: $gainLast")

        val maxStep = (1 until output.size).maxOf { i -> output[i] - output[i - 1] }
        assertTrue(maxStep < 0.001, "ramp stepped instead of ramping: $maxStep")
    }

    @Test
    fun `handles pcm 16 bit input`() {
        val processor = VolumeBalanceAudioProcessor(AudioBalanceLevel.Standard)
        processor.setLoudness(loudnessWith(6.0))
        processor.configure(SHORT_FORMAT)

        val frames = 4
        val input = ByteBuffer.allocate(frames * CHANNELS * 2).order(ByteOrder.nativeOrder())
        repeat(frames * CHANNELS) { input.putShort(4000) }
        input.flip()

        processor.queueInput(input)

        val buffer = processor.output.order(ByteOrder.nativeOrder())
        val output = ShortArray(frames * CHANNELS) { buffer.short }
        val expected = (4000 * GAIN_6DB).toInt()
        output.forEach { assertEquals(expected.toDouble(), it.toDouble(), 2.0) }
    }

    @Test
    fun `preserves trailing bytes that do not fill a frame`() {
        val processor = VolumeBalanceAudioProcessor(AudioBalanceLevel.Standard)
        processor.setLoudness(loudnessWith(6.0))
        processor.configure(FLOAT_FORMAT)

        val frameBytes = CHANNELS * 4
        val frames = 2
        val tail = byteArrayOf(0x11, 0x22, 0x33)
        val input = ByteBuffer.allocate(frames * frameBytes + tail.size)
            .order(ByteOrder.nativeOrder())
        repeat(frames * CHANNELS) { input.putFloat(0.25f) }
        input.put(tail)
        input.flip()

        processor.queueInput(input)

        val out = processor.output.order(ByteOrder.nativeOrder())
        assertEquals(frames * frameBytes + tail.size, out.remaining())

        out.position(frames * frameBytes)
        tail.forEach { assertEquals(it, out.get()) }
    }
}
