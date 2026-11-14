package dev.aaa1115910.biliapi.entity.user

import dev.aaa1115910.biliapi.http.entity.dynamic.DynamicItem as HttpDynamicItem
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class DynamicExtraModuleMappingTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun item(payload: String): DynamicItem =
        DynamicItem.fromDynamicItem(json.decodeFromString<HttpDynamicItem>(payload))

    @Test
    fun `video dynamic keeps its own body text apart from the archive description`() {
        val dynamic = item(
            """{"id_str":"5","type":"DYNAMIC_TYPE_AV","modules":{
            "module_author":{"name":"up","face":"f","mid":1,"pub_time":"3天前","pub_action":"投稿了视频"},
            "module_dynamic":{"desc":{"text":"友友们周五快乐！！！"},
            "major":{"type":"MAJOR_TYPE_ARCHIVE","archive":{
            "aid":"117580786371059","title":"视频标题","desc":"视频简介","cover":"c",
            "duration_text":"00:15","stat":{"play":"1.5万","danmaku":"87"}}}},
            "module_stat":{"like":{"count":0},"comment":{"count":0},"forward":{"count":0}}}}"""
        )

        assertEquals("友友们周五快乐！！！", dynamic.bodyText)
        assertEquals(DynamicMajorType.Archive, dynamic.majorType)
        assertEquals("视频标题", dynamic.video?.title)
        assertEquals("视频简介", dynamic.video?.text)
    }

    @Test
    fun `blocked card keeps its button and background image`() {
        val dynamic = item(
            """{"id_str":"1","type":"DYNAMIC_TYPE_DRAW","modules":{
            "module_author":{"name":"up","face":"f","mid":1,"pub_time":"3天前","pub_action":""},
            "module_dynamic":{"major":{"type":"MAJOR_TYPE_BLOCKED","blocked":{
            "blocked_type":3,
            "bg_img":{"img_day":"https://i0.hdslb.com/day-bg.png","img_dark":"https://i0.hdslb.com/dark-bg.png"},
            "icon":{"img_day":"https://i0.hdslb.com/day.png","img_dark":"https://i0.hdslb.com/dark.png"},
            "title":"开通可看专属自拍专属动态",
            "hint_message":"加入当前UP主的「6元档包月充电」即可解锁观看",
            "button":{"text":"6元充电","jump_url":"https://www.bilibili.com/h5/upower/index"}}}},
            "module_stat":{"like":{"count":0},"comment":{"count":2},"forward":{"count":0}}}}"""
        )

        val blocked = assertNotNull(dynamic.blocked)
        assertEquals(DynamicMajorType.Blocked, dynamic.majorType)
        assertEquals("6元充电", blocked.buttonText)
        assertEquals("https://www.bilibili.com/h5/upower/index", blocked.jumpUrl)
        assertEquals("https://i0.hdslb.com/dark-bg.png", blocked.bgImageUrl)
    }

    @Test
    fun `blocked card falls back to the light background when dark is missing`() {
        val dynamic = item(
            """{"id_str":"2","type":"DYNAMIC_TYPE_DRAW","modules":{
            "module_author":{"name":"up","face":"f","mid":1,"pub_time":"3天前","pub_action":""},
            "module_dynamic":{"major":{"type":"MAJOR_TYPE_BLOCKED","blocked":{
            "bg_img":{"img_day":"https://i0.hdslb.com/day-bg.png"},"hint_message":"充电解锁"}}},
            "module_stat":{"like":{"count":0},"comment":{"count":0},"forward":{"count":0}}}}"""
        )

        val blocked = assertNotNull(dynamic.blocked)
        assertEquals("https://i0.hdslb.com/day-bg.png", blocked.bgImageUrl)
        assertEquals("", blocked.buttonText)
        assertEquals("", blocked.jumpUrl)
    }

    @Test
    fun `live card keeps area popularity status and link`() {
        val content = "{\"type\":1,\"live_play_info\":{\"room_id\":5215238,\"live_status\":1," +
            "\"title\":\"绫华cos~\",\"cover\":\"https://i0.hdslb.com/live.jpg\",\"online\":8606," +
            "\"area_name\":\"舞见\",\"parent_area_name\":\"娱乐\"," +
            "\"link\":\"//live.bilibili.com/5215238?live_from=85002\"," +
            "\"watched_show\":{\"text_large\":\"1630人看过\"}}}"
        val dynamic = item(
            """{"id_str":"3","type":"DYNAMIC_TYPE_LIVE_RCMD","modules":{
            "module_author":{"name":"up","face":"f","mid":1,"pub_time":"","pub_action":"直播了"},
            "module_dynamic":{"major":{"type":"MAJOR_TYPE_LIVE_RCMD","live_rcmd":{"content":"${content.replace("\"", "\\\"")}"}}},
            "module_stat":{"like":{"count":0},"comment":{"count":0},"forward":{"count":0}}}}"""
        )

        val live = assertNotNull(dynamic.liveRcmd)
        assertEquals(8606, live.online)
        assertEquals(1, live.liveStatus)
        assertEquals("舞见", live.areaName)
        assertEquals("娱乐", live.parentAreaName)
        assertEquals("1630人看过", live.watchedText)
        assertEquals("//live.bilibili.com/5215238?live_from=85002", live.link)
    }

    @Test
    fun `legacy live card maps its state and link`() {
        val dynamic = item(
            """{"id_str":"4","type":"DYNAMIC_TYPE_LIVE_RCMD","modules":{
            "module_author":{"name":"up","face":"f","mid":1,"pub_time":"","pub_action":"直播了"},
            "module_dynamic":{"major":{"type":"MAJOR_TYPE_LIVE","live":{
            "id":"748326029026628614","title":"绫华cos~","cover":"https://i0.hdslb.com/live.jpg",
            "desc_first":"舞见","jump_url":"//live.bilibili.com/5215238","live_state":1}}},
            "module_stat":{"like":{"count":0},"comment":{"count":0},"forward":{"count":0}}}}"""
        )

        val live = assertNotNull(dynamic.liveRcmd)
        assertEquals(1, live.liveStatus)
        assertEquals("舞见", live.areaName)
        assertEquals("//live.bilibili.com/5215238", live.link)
        assertEquals(0, live.online)
    }
}
