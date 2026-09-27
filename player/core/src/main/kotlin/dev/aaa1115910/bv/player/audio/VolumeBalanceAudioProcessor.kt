package dev.aaa1115910.bv.player.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import dev.aaa1115910.bv.player.entity.AudioBalanceLevel
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow

/**
 * 音量均衡处理器。
 *
 * 只做两件事：
 * 1. 按 BS.1770 门控综合响度，缓慢地把整体增益推向挡位对应的目标响度；
 * 2. 用软拐点峰值限制器兜住增益带来的过冲。
 *
 * 起播与 seek 之后会先用少量统计块快速收敛（见 [ACQUIRE_BLOCKS]），避免开头一两秒停留在未处理的音量上。
 *
 * 全程只有标量增益与峰值包络，没有频谱处理、没有时间伸缩，因此不会改变音调与音色。
 * 限制器用指数软拐点逼近上限，而不是像常见做法那样硬削波，避免削波谐波造成的音色发硬。
 */
@UnstableApi
internal class VolumeBalanceAudioProcessor(
    level: AudioBalanceLevel = AudioBalanceLevel.Off
) : BaseAudioProcessor() {

    @Volatile
    private var level: AudioBalanceLevel = level

    private var sampleRateHz: Int = DEFAULT_SAMPLE_RATE
    private var channelCount: Int = DEFAULT_CHANNEL_COUNT
    private var encoding: Int = C.ENCODING_PCM_16BIT

    private var meter: LoudnessMeter? = null
    private var frameSamples: DoubleArray = DoubleArray(DEFAULT_CHANNEL_COUNT)

    /** 峰值限制器释放系数（线性） */
    private var limiterReleaseCoef: Double = 0.0

    /** 当前增益（线性） */
    private var gain: Double = 1.0

    /** 快速起音阶段剩余的统计块数 */
    private var acquireBlocksRemaining: Int = 0

    /** 峰值限制器包络（线性），各声道联动以免立体声声像漂移 */
    private var limiterEnvelope: Double = 0.0

    fun setLevel(level: AudioBalanceLevel) {
        if (this.level == level) return
        this.level = level
        resetState()
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return

        val out = replaceOutputBuffer(inputBuffer.remaining())
        out.order(ByteOrder.nativeOrder())

        val currentLevel = level
        if (currentLevel == AudioBalanceLevel.Off || !isActive) {
            out.put(inputBuffer)
            out.flip()
            return
        }

        val channels = channelCount
        val loudnessMeter = meter
        if (channels <= 0 || loudnessMeter == null) {
            out.put(inputBuffer)
            out.flip()
            return
        }

        val input = inputBuffer.duplicate().order(ByteOrder.nativeOrder())
        val inputLimit = input.limit()
        val bytesPerSample = if (encoding == C.ENCODING_PCM_FLOAT) 4 else 2
        val frameBytes = bytesPerSample * channels
        val frameCount = (inputLimit - input.position()) / frameBytes
        val isFloat = encoding == C.ENCODING_PCM_FLOAT

        for (frame in 0 until frameCount) {
            var frameSumSquares = 0.0
            for (channel in 0 until channels) {
                val sample =
                    if (isFloat) {
                        input.getFloat().toDouble()
                    } else {
                        input.getShort().toDouble() / 32768.0
                    }
                frameSamples[channel] = sample
                val weighted = loudnessMeter.weight(channel, sample)
                frameSumSquares += weighted * weighted
            }

            // 每凑满一个统计块才重新估算目标增益，避免逐帧做窗口运算
            if (loudnessMeter.endFrame(frameSumSquares)) {
                updateGain(currentLevel)
            }

            for (channel in 0 until channels) {
                val amplified = frameSamples[channel] * gain
                limiterEnvelope = max(abs(amplified), limiterEnvelope * limiterReleaseCoef)
                val limited = amplified * softLimitGain(limiterEnvelope)
                if (isFloat) {
                    out.putFloat(limited.coerceIn(-1.0, 1.0).toFloat())
                } else {
                    out.putShort(
                        (limited * 32768.0)
                            .coerceIn(Short.MIN_VALUE.toDouble(), Short.MAX_VALUE.toDouble())
                            .toInt()
                            .toShort()
                    )
                }
            }
        }

        // 不足一帧的尾部字节原样透传
        while (input.position() < inputLimit) {
            out.put(input.get())
        }

        inputBuffer.position(inputLimit)
        out.flip()
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_16BIT,
            C.ENCODING_PCM_FLOAT,
            -> {
                encoding = inputAudioFormat.encoding
                sampleRateHz = inputAudioFormat.sampleRate.takeIf { it > 0 } ?: DEFAULT_SAMPLE_RATE
                channelCount = inputAudioFormat.channelCount.takeIf { it > 0 } ?: DEFAULT_CHANNEL_COUNT
                resetState()
                return inputAudioFormat
            }

            else -> return AudioProcessor.AudioFormat.NOT_SET
        }
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        resetState()
    }

    override fun onReset() {
        resetState()
    }

    private fun updateGain(currentLevel: AudioBalanceLevel) {
        val acquiring = acquireBlocksRemaining > 0
        val measuredLufs =
            if (acquiring) meter?.peakBlockLufs() else meter?.loudnessLufs()
        val target = measuredLufs ?: return
        if (acquiring) acquireBlocksRemaining--

        val desiredGainDb =
            (currentLevel.targetLufs - target).coerceIn(-MAX_CUT_DB, currentLevel.maxGainDb)
        val desiredGain = 10.0.pow(desiredGainDb / 20.0)
        val tau = when {
            acquiring -> GAIN_ACQUIRE_SEC
            desiredGain < gain -> GAIN_ATTACK_SEC
            else -> GAIN_RELEASE_SEC
        }
        val alpha = 1.0 - exp(-LOUDNESS_BLOCK_SEC / tau)
        gain += alpha * (desiredGain - gain)
    }

    /**
     * 指数软拐点：拐点以上渐进逼近上限，曲线在拐点处连续，
     * 因此不会像硬削波那样产生高次谐波。
     */
    private fun softLimitGain(magnitude: Double): Double {
        if (magnitude <= KNEE_START_LINEAR) return 1.0
        val magnitudeDb = 20.0 * log10(magnitude)
        val overDb = magnitudeDb - KNEE_START_DB
        val limitedDb = KNEE_START_DB + KNEE_DB * (1.0 - exp(-overDb / KNEE_DB))
        return 10.0.pow((limitedDb - magnitudeDb) / 20.0)
    }

    private fun resetState() {
        meter = LoudnessMeter(sampleRateHz, channelCount)
        if (frameSamples.size < channelCount) frameSamples = DoubleArray(channelCount)
        limiterReleaseCoef = exp(-1.0 / (sampleRateHz * LIMITER_RELEASE_SEC))
        gain = 1.0
        limiterEnvelope = 0.0
        acquireBlocksRemaining = ACQUIRE_BLOCKS
    }

    private companion object {
        const val DEFAULT_SAMPLE_RATE = 48_000
        const val DEFAULT_CHANNEL_COUNT = 2

        /** 统计块长度，需与 [LoudnessMeter] 保持一致 */
        const val LOUDNESS_BLOCK_SEC = 0.1

        /**
         * 起播（含 seek）后先用少量统计块快速把增益拉到目标附近，
         * 否则从头 1~2 秒都会停留在未处理的音量上。
         */
        const val ACQUIRE_BLOCKS = 3
        const val GAIN_ACQUIRE_SEC = 0.08

        /** 增益下调时稍快，避免持续过响；上调时放慢，避免把段落间隙的底噪抽上来 */
        const val GAIN_ATTACK_SEC = 1.2
        const val GAIN_RELEASE_SEC = 4.0

        /** 允许的最大衰减，避免本身很响的素材被压得过闷 */
        const val MAX_CUT_DB = 12.0

        /** 软拐点起点 -7 dBFS，拐点宽度 6 dB，渐近上限约 -1 dBFS */
        const val KNEE_START_DB = -7.0
        const val KNEE_DB = 6.0
        val KNEE_START_LINEAR = 10.0.pow(KNEE_START_DB / 20.0)

        /** 峰值限制器释放时间，只影响包络回落速度，不改变瞬时波形 */
        const val LIMITER_RELEASE_SEC = 0.15
    }
}
