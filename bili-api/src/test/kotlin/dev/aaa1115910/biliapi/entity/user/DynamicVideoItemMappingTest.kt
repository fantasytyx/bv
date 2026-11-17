package dev.aaa1115910.biliapi.entity.user

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DynamicVideoItemMappingTest {
    private fun item(badgeText: String = "") = DynamicItem(
        id = "1",
        type = DynamicType.Av,
        author = DynamicItem.DynamicAuthorModule(
            author = "up", avatar = "face", mid = 42L, pubTime = "1小时前", pubAction = ""
        ),
        video = DynamicItem.DynamicVideoModule(
            aid = 100L, bvid = "BV1", cid = 0,
            title = "动态视频｜标题", text = "", cover = "cover",
            duration = "01:02", play = "1.2万", danmaku = "34",
            badgeText = badgeText, playCount = 12000L, danmakuCount = 34, durationSeconds = 62
        )
    )

    @Test
    fun `module is mapped to a playable video`() {
        val video = DynamicVideo.fromDynamicItem(item())
        assertEquals(100L, video.aid)
        assertEquals("标题", video.title)
        assertEquals(12000L, video.play)
        assertEquals(34, video.danmaku)
        assertEquals(62, video.duration)
        assertEquals(42L, video.authorId)
        assertEquals("face", video.authorFace)
        assertFalse(video.isChargingArc)
    }

    @Test
    fun `charging badge marks the video as charging arc`() {
        val video = DynamicVideo.fromDynamicItem(item(badgeText = "充电专属"))
        assertTrue(video.isChargingArc)
        assertEquals("充电专属", video.chargingArcBadge)
    }
}
