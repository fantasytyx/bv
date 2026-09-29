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
 * 增益分三段推进：窗口填满前只压不抬（见 [WINDOW_BLOCKS]），填满后按 [GAIN_SETTLE_STEP_DB] 限速定档，
 * 之后才转入慢速维持。这样「不同视频音量不一」在起播几秒内就解决，
 * 播放过程中增益基本不动，而不是全程匀速跟随、听着忽大忽小。
 * 填满前不提升是因为开头几百毫秒可能只是一段安静前奏，据此定档会先抬上去再压回来。
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

    /** 当前增益（线性） */
    private var gain: Double = 1.0

    /** 已累计的统计块数，用于判断测量窗口是否足以代表整段响度 */
    private var measuredBlocks: Int = 0

    /** 首次定档阶段剩余的统计块数 */
    private var settleBlocksRemaining: Int = 0

    /** 峰值限制器包络（线性），各声道联动以免立体声声像漂移 */
    private var limiterEnvelope: Double = 0.0

    /** 峰值限制器释放系数（线性） */
    private var limiterReleaseCoef: Double = 0.0

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
                if (measuredBlocks < WINDOW_BLOCKS) {
                    measuredBlocks++
                    if (measuredBlocks == WINDOW_BLOCKS) settleBlocksRemaining = SETTLE_BLOCKS
                }
                updateGain(currentLevel)
            }

            for (channel in 0 until channels) {
                val amplified = frameSamples[channel] * gain
                // 包络瞬时起音：平滑起音会让窄尖峰直接穿过限制器，只能由后面的硬削兜底
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
                resetMeasurementState()
                return inputAudioFormat
            }

            else -> return AudioProcessor.AudioFormat.NOT_SET
        }
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        // seek/变速/换流都会 flush：只丢测量不丢增益，否则音量每次回低再按 1.2 dB/s 爬回
        resetMeasurementState()
    }

    override fun onReset() {
        resetState()
    }

    private fun updateGain(currentLevel: AudioBalanceLevel) {
        val target = meter?.loudnessLufs() ?: return
        val warmingUp = measuredBlocks < WINDOW_BLOCKS

        val desiredGainDb =
            (currentLevel.targetLufs - target).coerceIn(-MAX_CUT_DB, currentLevel.maxGainDb)
        val desiredGain = 10.0.pow(desiredGainDb / 20.0)

        if (warmingUp) {
            // 窗口未填满就提升，等于按开头几百毫秒的音量给整段定档；只压不抬则最多是
            // 「起播音量偏低但很快恢复」，不会出现抬高了再压回来的来回摆动
            val reachableGain = minOf(desiredGain, gain)
            val alpha = 1.0 - exp(-LOUDNESS_BLOCK_SEC / WARMUP_ATTACK_SEC)
            gain += alpha * (reachableGain - gain)
            return
        }

        if (settleBlocksRemaining > 0) {
            // 匀速推进而非指数逼近：指数在起点斜率最大，听感上就是「突然变了」
            settleBlocksRemaining--
            val currentDb = 20.0 * log10(gain)
            val diffDb = desiredGainDb - currentDb
            if (abs(diffDb) <= GAIN_SETTLE_STEP_DB) {
                settleBlocksRemaining = 0
                gain = 10.0.pow(desiredGainDb / 20.0)
            } else {
                gain = 10.0.pow(
                    (currentDb + diffDb.coerceIn(-GAIN_SETTLE_STEP_DB, GAIN_SETTLE_STEP_DB)) / 20.0
                )
            }
            return
        }

        val tau = if (desiredGain < gain) GAIN_ATTACK_SEC else GAIN_RELEASE_SEC
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

    private fun resetMeasurementState() {
        meter = LoudnessMeter(sampleRateHz, channelCount)
        if (frameSamples.size < channelCount) frameSamples = DoubleArray(channelCount)
        limiterReleaseCoef = exp(-1.0 / (sampleRateHz * LIMITER_RELEASE_SEC))
        limiterEnvelope = 0.0
        measuredBlocks = 0
        settleBlocksRemaining = 0
    }

    private fun resetState() {
        resetMeasurementState()
        gain = 1.0
    }

    private companion object {
        const val DEFAULT_SAMPLE_RATE = 48_000
        const val DEFAULT_CHANNEL_COUNT = 2

        /** 统计块长度，需与 [LoudnessMeter] 保持一致 */
        const val LOUDNESS_BLOCK_SEC = 0.1

        /** 测量窗口长度（统计块数），需与 [LoudnessMeter] 的窗口一致 */
        const val WINDOW_BLOCKS = 40

        /**
         * 窗口填满前的压缩时间常数。
         *
         * 起播直接撞上爆音时不能等，所以这里比稳态快；但提升在这段时间内完全不开放。
         */
        const val WARMUP_ATTACK_SEC = 0.5

        /**
         * 首次定档的推进速率（每个统计块最多变化多少 dB）。
         *
         * 窗口填满后测量值已经能代表整段，此时要把「不同视频音量不一」解决掉。
         * 速率取 0.12 dB/块 = 1.2 dB/s：低于人对渐变响度的察觉速度，
         * 因此整段调整听起来是「音量自己走稳了」，而不是被谁拨了一下。
         */
        const val GAIN_SETTLE_STEP_DB = 0.12

        /** 定档阶段的上限时长，够走完最大提升量；到位后会提前结束 */
        const val SETTLE_BLOCKS = 150

        /**
         * 增益跟随的时间常数要明显大于音乐句法（一句歌词 2~4 秒）。
         *
         * 若缩短到与句子同量级，一句高亢歌词唱到一半增益就被压下来，
         * 听感上就是「前半句大、后半句被压小」；句间又来不及恢复，整段还会飘。
         *
         * 取 6/12（仿真：3 秒 +4 dB 高亢句下句内增益只变化 0.67 dB）：
         * 再快会重新听见句内渐弱（3/6 时约 1.2 dB），
         * 再慢则段落间响度差的收敛要 20 秒以上，均衡本身就变得迟钝。
         *
         * 下调略快于上调：前者只影响一次收敛，后者会决定整段是否长期偏高。
         */
        const val GAIN_ATTACK_SEC = 6.0
        const val GAIN_RELEASE_SEC = 12.0

        /** 允许的最大衰减，避免本身很响的素材被压得过闷 */
        const val MAX_CUT_DB = 12.0

        /**
         * 软拐点起点 -3 dBFS，拐点宽度 2.5 dB，渐近上限约 -0.5 dBFS。
         *
         * 起点定得离满刻度很近，是为了让限制器只在真正顶到上限时兜一下：
         * 现代母带峰值普遍在 -1 dBFS 附近，若把起点压到 -7 dBFS，限制器会常态介入，
         * 把均衡刚提上来的响度又压回去，动态也就没了。
         */
        const val KNEE_START_DB = -3.0
        const val KNEE_DB = 2.5
        val KNEE_START_LINEAR = 10.0.pow(KNEE_START_DB / 20.0)

        /** 峰值限制器释放时间，只影响包络回落速度，不改变瞬时波形 */
        const val LIMITER_RELEASE_SEC = 0.15
    }
}
