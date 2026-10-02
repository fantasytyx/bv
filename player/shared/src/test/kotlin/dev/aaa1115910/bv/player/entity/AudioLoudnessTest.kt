package dev.aaa1115910.bv.player.entity

import dev.aaa1115910.biliapi.entity.MultiSceneArgs
import dev.aaa1115910.biliapi.entity.VolumeInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 映射契约：bili-api 用 NaN 表示「服务端未给」，播放器侧用 null 表示同一件事。
 * 可空字段必须在映射时归一，非空字段保留 NaN 由增益函数判定有效性。
 */
class AudioLoudnessTest {

    @Test
    fun `maps every measured and target field`() {
        val volume = VolumeInfo(
            measuredI = -23.5,
            measuredLra = 7.2,
            measuredTp = -1.4,
            measuredThreshold = -33.1,
            targetOffset = 3.5,
            targetI = -20.0,
            targetTp = -2.0,
            multiSceneArgs = MultiSceneArgs(
                undersizedTargetI = -40.0,
                normalTargetI = -20.0,
                highDynamicTargetI = -16.0,
            ),
        )

        val loudness = volume.toAudioLoudness()

        assertEquals(-23.5, loudness.measuredI, 1e-9)
        assertEquals(-1.4, loudness.measuredTp, 1e-9)
        assertEquals(-20.0, loudness.targetI, 1e-9)
        assertEquals(-2.0, loudness.targetTp, 1e-9)
        assertEquals(-20.0, loudness.normalTargetI!!, 1e-9)
        assertEquals(-16.0, loudness.highDynamicTargetI!!, 1e-9)
        assertEquals(-40.0, loudness.undersizedTargetI!!, 1e-9)
    }

    @Test
    fun `NaN optional fields become null`() {
        val volume = VolumeInfo(
            measuredI = -23.5,
            measuredTp = -1.4,
            targetI = -20.0,
            targetTp = -2.0,
        )

        val loudness = volume.toAudioLoudness()

        assertNull(loudness.normalTargetI)
        assertNull(loudness.highDynamicTargetI)
        assertNull(loudness.undersizedTargetI)
    }

    @Test
    fun `missing multi scene args leaves every scene target null`() {
        val volume = VolumeInfo(targetI = -20.0, multiSceneArgs = null)

        val loudness = volume.toAudioLoudness()

        assertEquals(-20.0, loudness.targetI, 1e-9)
        assertNull(loudness.normalTargetI)
        assertNull(loudness.highDynamicTargetI)
        assertNull(loudness.undersizedTargetI)
    }

    @Test
    fun `partially filled multi scene args keeps the others null`() {
        val volume = VolumeInfo(multiSceneArgs = MultiSceneArgs(normalTargetI = -18.0))

        val loudness = volume.toAudioLoudness()

        assertEquals(-18.0, loudness.normalTargetI!!, 1e-9)
        assertNull(loudness.highDynamicTargetI)
        assertNull(loudness.undersizedTargetI)
    }

    @Test
    fun `non-nullable measurements keep NaN so the gain function can reject them`() {
        val loudness = VolumeInfo().toAudioLoudness()

        assertTrue(loudness.measuredI.isNaN())
        assertTrue(loudness.measuredTp.isNaN())
        assertTrue(loudness.targetI.isNaN())
        assertTrue(loudness.targetTp.isNaN())
    }
}
