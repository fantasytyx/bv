package dev.aaa1115910.bv.player.audio

import dev.aaa1115910.bv.player.entity.AudioBalanceLevel
import dev.aaa1115910.bv.player.entity.AudioLoudness
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 增益策略是元数据驱动方案里唯一的策略点，也是纯函数，因此逐条钉住它的判定顺序。
 *
 * 默认 [loudness] 里的真峰值特意取得很宽（measuredTp=-20、targetTp=-1，夹取上界 19 dB），
 * 使真峰夹取在这些用例里不生效——这样「只考响度」的用例断言的就是响度换算本身；
 * 夹取行为再由 `true peak caps the gain` 等用例单独覆盖。
 */
class AudioBalanceGainTest {

    private fun loudness(
        measuredI: Double = -20.0,
        measuredTp: Double = -20.0,
        targetI: Double = -16.0,
        targetTp: Double = -1.0,
        normalTargetI: Double? = null,
        highDynamicTargetI: Double? = null,
        undersizedTargetI: Double? = null,
    ) = AudioLoudness(
        measuredI = measuredI,
        measuredTp = measuredTp,
        targetI = targetI,
        targetTp = targetTp,
        normalTargetI = normalTargetI,
        highDynamicTargetI = highDynamicTargetI,
        undersizedTargetI = undersizedTargetI,
    )

    @Test
    fun `cinema mode targets a different loudness than standard mode`() {
        // 两个档位必须算出不同增益，否则三挡 UI 就是摆设（评审回归点：服务端的 target_offset
        // 是单值、不随档位变化，一旦采用它就会把档位差异抹平）
        val loudness = loudness(
            measuredI = -20.0,
            targetI = -20.0,
            normalTargetI = -20.0,
            highDynamicTargetI = -16.0,
        )

        assertEquals(0.0, AudioBalanceGain.gainDb(AudioBalanceLevel.Standard, loudness)!!, 1e-6)
        assertEquals(4.0, AudioBalanceGain.gainDb(AudioBalanceLevel.Cinema, loudness)!!, 1e-6)
    }

    @Test
    fun `off level is bypassed`() {
        assertNull(AudioBalanceGain.gainDb(AudioBalanceLevel.Off, loudness()))
    }

    @Test
    fun `nan measured i is bypassed`() {
        assertNull(
            AudioBalanceGain.gainDb(
                AudioBalanceLevel.Standard,
                loudness(measuredI = Double.NaN),
            )
        )
    }

    @Test
    fun `zero measured i is bypassed`() {
        // 0 是 loudnorm 的「未测量」默认值，必须当成无效而不是「响度恰好是 0 LUFS」
        assertNull(
            AudioBalanceGain.gainDb(AudioBalanceLevel.Standard, loudness(measuredI = 0.0))
        )
    }

    @Test
    fun `under the undersized threshold is bypassed`() {
        assertNull(
            AudioBalanceGain.gainDb(
                AudioBalanceLevel.Standard,
                loudness(measuredI = -40.0, undersizedTargetI = -30.0),
            )
        )
    }

    @Test
    fun `above the undersized threshold is processed`() {
        assertEquals(
            4.0,
            AudioBalanceGain.gainDb(
                AudioBalanceLevel.Standard,
                loudness(measuredI = -20.0, undersizedTargetI = -30.0),
            )!!,
            1e-6,
        )
    }

    @Test
    fun `standard mode uses normal target`() {
        assertEquals(
            4.0,
            AudioBalanceGain.gainDb(
                AudioBalanceLevel.Standard,
                loudness(measuredI = -20.0, normalTargetI = -16.0),
            )!!,
            1e-6,
        )
    }

    @Test
    fun `standard mode falls back to target i`() {
        assertEquals(
            4.0,
            AudioBalanceGain.gainDb(
                AudioBalanceLevel.Standard,
                loudness(measuredI = -20.0, targetI = -16.0, normalTargetI = null),
            )!!,
            1e-6,
        )
    }

    @Test
    fun `out of range normal target falls back to target i`() {
        // 0.0 落在 loudnorm 的 I 取值范围 [-70, -5] 之外，必须当作无效而不是照用
        assertEquals(
            4.0,
            AudioBalanceGain.gainDb(
                AudioBalanceLevel.Standard,
                loudness(measuredI = -20.0, targetI = -16.0, normalTargetI = 0.0),
            )!!,
            1e-6,
        )
    }

    @Test
    fun `cinema mode uses high dynamic target`() {
        assertEquals(
            0.0,
            AudioBalanceGain.gainDb(
                AudioBalanceLevel.Cinema,
                loudness(measuredI = -20.0, highDynamicTargetI = -20.0),
            )!!,
            1e-6,
        )
    }

    @Test
    fun `true peak caps the gain`() {
        // 响度要求 +4，但真峰只允许 -2-(-1) = -1
        assertEquals(
            -1.0,
            AudioBalanceGain.gainDb(
                AudioBalanceLevel.Standard,
                loudness(
                    measuredI = -20.0,
                    measuredTp = -1.0,
                    targetI = -16.0,
                    targetTp = -2.0,
                ),
            )!!,
            1e-6,
        )
    }

    @Test
    fun `gain is clamped to twelve db`() {
        // 响度要求 +30、真峰允许 +28，最终仍要被防呆上限截到 +12
        assertEquals(
            12.0,
            AudioBalanceGain.gainDb(
                AudioBalanceLevel.Standard,
                loudness(
                    measuredI = -60.0,
                    measuredTp = -30.0,
                    targetI = -30.0,
                    targetTp = -2.0,
                ),
            )!!,
            1e-6,
        )
    }

    @Test
    fun `default target tp caps when target tp missing`() {
        // targetTp 无效时用内置默认 -1.0 dBTP：-1.0-(-0.5) = -0.5，比响度要求的 +4 更紧
        assertEquals(
            -0.5,
            AudioBalanceGain.gainDb(
                AudioBalanceLevel.Standard,
                loudness(
                    measuredI = -20.0,
                    measuredTp = -0.5,
                    targetI = -16.0,
                    targetTp = Double.NaN,
                ),
            )!!,
            1e-6,
        )
    }

    @Test
    fun `default target tp does not cap when harmless`() {
        // 同一默认值在真峰本来就低时不构成限制：-1.0-(-6.0) = +5 > +4
        assertEquals(
            4.0,
            AudioBalanceGain.gainDb(
                AudioBalanceLevel.Standard,
                loudness(
                    measuredI = -20.0,
                    measuredTp = -6.0,
                    targetI = -16.0,
                    targetTp = Double.NaN,
                ),
            )!!,
            1e-6,
        )
    }
}
