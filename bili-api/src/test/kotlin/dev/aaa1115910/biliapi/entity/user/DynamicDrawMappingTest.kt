package dev.aaa1115910.biliapi.entity.user

import dev.aaa1115910.biliapi.http.entity.dynamic.DynamicItem as HttpDynamicItem
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class DynamicDrawMappingTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `draw card with an attached ugc video keeps its images and the video`() {
        val webItem = json.decodeFromString<HttpDynamicItem>(
            """{"id_str":"3","type":"DYNAMIC_TYPE_DRAW","modules":{
            "module_author":{"name":"up","face":"f","mid":1,"pub_time":"10月23日","pub_action":""},
            "module_dynamic":{
            "major":{"type":"MAJOR_TYPE_OPUS","opus":{"title":"","summary":{"text":"正文"},
            "pics":[{"url":"https://i0.hdslb.com/1.jpg","width":10,"height":10},
            {"url":"https://i0.hdslb.com/2.jpg","width":10,"height":10}]}},
            "additional":{"type":"ADDITIONAL_TYPE_UGC","ugc":{
            "id_str":"117483411343753",
            "title":"第一眼我就知道，这是我老婆！！！",
            "desc_second":"22万观看 222弹幕",
            "jump_url":"//www.bilibili.com/video/BV1Dony6eEk1",
            "cover":"https://i1.hdslb.com/bfs/archive/cover.jpg",
            "duration":"00:36",
            "stat":{"danmaku":"222","play":"22万","vt":""}}}},
            "module_stat":{"like":{"count":553},"comment":{"count":17},"forward":{"count":4}}}}"""
        )

        val dynamic = DynamicItem.fromDynamicItem(webItem)

        assertEquals(DynamicMajorType.Opus, dynamic.majorType)
        assertEquals(2, dynamic.draw?.images?.size)
        val video = assertNotNull(dynamic.video)
        assertEquals("第一眼我就知道，这是我老婆！！！", video.title)
        assertEquals("00:36", video.duration)
        assertEquals(36, video.durationSeconds)
        assertEquals(220000L, video.playCount)
        assertEquals(222, video.danmakuCount)
    }

    @Test
    fun `opus card keeps its title and summary text`() {
        val webItem = json.decodeFromString<HttpDynamicItem>(
            """{"id_str":"1","type":"DYNAMIC_TYPE_DRAW","modules":{
            "module_author":{"name":"up","face":"f","mid":1,"pub_time":"1小时前","pub_action":""},
            "module_dynamic":{"major":{"type":"MAJOR_TYPE_OPUS","opus":{
            "title":"opus 标题","summary":{"text":"opus 正文"},
            "pics":[{"url":"https://i0.hdslb.com/1.jpg","width":10,"height":10}]}}},
            "module_stat":{"like":{"count":1},"comment":{"count":2},"forward":{"count":3}}}}"""
        )

        val draw = DynamicItem.fromDynamicItem(webItem).draw

        assertEquals("opus 标题", draw?.title)
        assertEquals("opus 正文", draw?.text)
        assertEquals(1, draw?.images?.size)
    }

    @Test
    fun `draw card without any text keeps an empty text instead of a placeholder`() {
        val webItem = json.decodeFromString<HttpDynamicItem>(
            """{"id_str":"2","type":"DYNAMIC_TYPE_DRAW","modules":{
            "module_author":{"name":"up","face":"f","mid":1,"pub_time":"1小时前","pub_action":""},
            "module_dynamic":{"major":{"type":"MAJOR_TYPE_DRAW","draw":{
            "items":[{"src":"https://i0.hdslb.com/2.jpg","width":10,"height":10}]}}},
            "module_stat":{"like":{"count":1},"comment":{"count":2},"forward":{"count":3}}}}"""
        )

        val draw = DynamicItem.fromDynamicItem(webItem).draw

        assertNull(draw?.title)
        assertEquals("", draw?.text)
        assertEquals(1, draw?.images?.size)
    }
}
