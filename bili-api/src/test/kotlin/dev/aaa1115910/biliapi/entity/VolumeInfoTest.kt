package dev.aaa1115910.biliapi.entity

import dev.aaa1115910.biliapi.http.entity.video.PlayUrlData
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VolumeInfoTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `parses volume with multi scene args`() {
        val raw = """
        {"measured_i":-23.5,"measured_lra":7.2,"measured_tp":-1.4,
         "measured_threshold":-33.1,"target_offset":3.5,"target_i":-20.0,"target_tp":-2.0,
         "multi_scene_args":{"undersized_target_i":-40.0,"normal_target_i":-20.0,
                             "high_dynamic_target_i":-16.0}}
        """.trimIndent()
        val volume = json.decodeFromString<VolumeInfo>(raw)
        assertEquals(-23.5, volume.measuredI, 1e-9)
        assertEquals(7.2, volume.measuredLra, 1e-9)
        assertEquals(-1.4, volume.measuredTp, 1e-9)
        assertEquals(-33.1, volume.measuredThreshold, 1e-9)
        assertEquals(3.5, volume.targetOffset, 1e-9)
        assertEquals(-20.0, volume.targetI, 1e-9)
        assertEquals(-2.0, volume.targetTp, 1e-9)
        assertEquals(-16.0, volume.multiSceneArgs!!.highDynamicTargetI, 1e-9)
    }

    @Test
    fun `absent fields default to NaN`() {
        val volume = json.decodeFromString<VolumeInfo>("""{"measured_i":-23.5}""")
        assertEquals(-23.5, volume.measuredI, 1e-9)
        assertTrue(volume.targetI.isNaN())
        assertTrue(volume.targetOffset.isNaN())
        assertTrue(volume.measuredTp.isNaN())
        assertNull(volume.multiSceneArgs)
    }

    @Test
    fun `parses the real response shape where multi scene args are strings`() {
        // 实测 B 站 web playurl：multi_scene_args 的值是字符串（"-24"），不是数字。
        // 若按 Double 严格解析会抛异常，导致整份响应解析失败、连带播放失败。
        val raw = """
        {"quality":80,"volume":{"measured_i":-9.8,"measured_lra":10.8,"measured_tp":3.8,
         "measured_threshold":-20.2,"target_offset":-0.6,"target_i":-14,"target_tp":-1,
         "multi_scene_args":{"high_dynamic_target_i":"-24","normal_target_i":"-14",
                             "undersized_target_i":"-28"}}}
        """.trimIndent()

        val data = json.decodeFromString<PlayUrlData>(raw)

        val scene = data.volume!!.multiSceneArgs!!
        assertEquals(-14.0, scene.normalTargetI, 1e-9)
        assertEquals(-24.0, scene.highDynamicTargetI, 1e-9)
        assertEquals(-28.0, scene.undersizedTargetI, 1e-9)
    }

    @Test
    fun `playurl data carries volume`() {
        val raw = """{"quality":80,"volume":{"measured_i":-23.5,"target_i":-20.0}}"""
        val data = json.decodeFromString<PlayUrlData>(raw)
        assertEquals(-23.5, data.volume!!.measuredI, 1e-9)
    }
}
