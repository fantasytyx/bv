package dev.aaa1115910.biliapi.entity

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * gRPC 侧的 proto3 `double` 没有 presence：服务端没填时读出来是 `0.0`，与「真的填了 0.0」
 * 无法区分。JSON 侧靠 NaN 表示未给，所以这里必须把会误导增益策略的影子值归一成 NaN。
 */
class PlayDataLoudnessMappingTest {

    @Test
    fun `proto volume keeps the measurement fields it does have`() {
        val proto = bilibili.playershared.VolumeInfo.newBuilder()
            .setMeasuredI(-23.5)
            .setMeasuredLra(7.2)
            .setMeasuredTp(-1.4)
            .setMeasuredThreshold(-33.1)
            .setTargetOffset(3.5)
            .setTargetI(-20.0)
            .setTargetTp(-2.0)
            .build()

        val volume = proto.toVolumeInfo()

        assertEquals(-23.5, volume.measuredI, 1e-9)
        assertEquals(7.2, volume.measuredLra, 1e-9)
        assertEquals(-1.4, volume.measuredTp, 1e-9)
        assertEquals(-33.1, volume.measuredThreshold, 1e-9)
        assertEquals(3.5, volume.targetOffset, 1e-9)
        assertEquals(-20.0, volume.targetI, 1e-9)
        assertEquals(-2.0, volume.targetTp, 1e-9)
    }
}
