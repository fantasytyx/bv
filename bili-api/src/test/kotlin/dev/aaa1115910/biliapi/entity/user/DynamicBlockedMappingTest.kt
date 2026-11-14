package dev.aaa1115910.biliapi.entity.user

import dev.aaa1115910.biliapi.http.entity.dynamic.DynamicItem as HttpDynamicItem
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class DynamicBlockedMappingTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `blocked charging dynamic is mapped instead of its outer type`() {
        val webItem = json.decodeFromString<HttpDynamicItem>(
            """{"id_str":"1","type":"DYNAMIC_TYPE_DRAW","modules":{
            "module_author":{"name":"up","face":"f","mid":1,"pub_time":"1小时前","pub_action":""},
            "module_dynamic":{"major":{"type":"MAJOR_TYPE_BLOCKED","blocked":{
            "blocked_type":3,
            "icon":{"img_dark":"https://i0.hdslb.com/dark.png","img_day":"https://i0.hdslb.com/day.png"},
            "title":"多谢巫萤哥专属动态",
            "button":{"text":"88元充电"},
            "hint_message":"加入当前UP主的「88元包月充电」即可解锁观看"}}},
            "module_stat":{"like":{"count":1},"comment":{"count":2},"forward":{"count":3}}}}"""
        )

        val item = DynamicItem.fromDynamicItem(webItem)

        val blocked = assertNotNull(item.blocked)
        assertEquals("多谢巫萤哥专属动态", blocked.title)
        assertEquals("加入当前UP主的「88元包月充电」即可解锁观看", blocked.hintMessage)
        assertEquals("https://i0.hdslb.com/dark.png", blocked.iconUrl)
        assertNull(item.draw)
    }

    @Test
    fun `blocked video dynamic with no archive payload does not throw`() {
        val webItem = json.decodeFromString<HttpDynamicItem>(
            """{"id_str":"2","type":"DYNAMIC_TYPE_AV","modules":{
            "module_author":{"name":"up","face":"f","mid":1,"pub_time":"1小时前","pub_action":""},
            "module_dynamic":{"major":{"type":"MAJOR_TYPE_BLOCKED","blocked":{
            "title":"专属视频","hint_message":"充电后可观看"}}},
            "module_stat":{"like":{"count":1},"comment":{"count":2},"forward":{"count":3}}}}"""
        )

        val item = DynamicItem.fromDynamicItem(webItem)

        val blocked = assertNotNull(item.blocked)
        assertEquals("专属视频", blocked.title)
        assertNull(item.video)
    }

    @Test
    fun `blocked icon falls back to the light asset`() {
        val webItem = json.decodeFromString<HttpDynamicItem>(
            """{"id_str":"3","type":"DYNAMIC_TYPE_DRAW","modules":{
            "module_author":{"name":"up","face":"f","mid":1,"pub_time":"1小时前","pub_action":""},
            "module_dynamic":{"major":{"type":"MAJOR_TYPE_BLOCKED","blocked":{
            "icon":{"img_day":"https://i0.hdslb.com/day.png"},"hint_message":"充电解锁"}}},
            "module_stat":{"like":{"count":1},"comment":{"count":2},"forward":{"count":3}}}}"""
        )

        val item = DynamicItem.fromDynamicItem(webItem)

        assertEquals("https://i0.hdslb.com/day.png", assertNotNull(item.blocked).iconUrl)
    }

    @Test
    fun `blocked type without a blocked payload still yields a readable card`() {
        val webItem = json.decodeFromString<HttpDynamicItem>(
            """{"id_str":"4","type":"DYNAMIC_TYPE_DRAW","modules":{
            "module_author":{"name":"up","face":"f","mid":1,"pub_time":"22小时前","pub_action":""},
            "module_dynamic":{"major":{"type":"MAJOR_TYPE_BLOCKED"}},
            "module_stat":{"like":{"count":0},"comment":{"count":0},"forward":{"count":0}}}}"""
        )

        val blocked = assertNotNull(DynamicItem.fromDynamicItem(webItem).blocked)

        assertEquals("充电专属动态", blocked.title)
        assertEquals("", blocked.hintMessage)
    }

    @Test
    fun `blocked hint is kept while the title stays empty so the card shows the hint`() {
        val webItem = json.decodeFromString<HttpDynamicItem>(
            """{"id_str":"5","type":"DYNAMIC_TYPE_DRAW","modules":{
            "module_author":{"name":"up","face":"f","mid":1,"pub_time":"1小时前","pub_action":""},
            "module_dynamic":{"major":{"type":"MAJOR_TYPE_BLOCKED","blocked":{
            "hint_message":"加入当前UP主的「88元包月充电」即可解锁观看"}}},
            "module_stat":{"like":{"count":1},"comment":{"count":2},"forward":{"count":3}}}}"""
        )

        val blocked = assertNotNull(DynamicItem.fromDynamicItem(webItem).blocked)

        assertEquals("", blocked.title)
        assertEquals("加入当前UP主的「88元包月充电」即可解锁观看", blocked.hintMessage)
    }

    @Test
    fun `blocked card falls back to none tips and the opus cover`() {
        val webItem = json.decodeFromString<HttpDynamicItem>(
            """{"id_str":"6","type":"DYNAMIC_TYPE_DRAW","modules":{
            "module_author":{"name":"up","face":"f","mid":1,"pub_time":"1小时前","pub_action":""},
            "module_dynamic":{"major":{"type":"MAJOR_TYPE_BLOCKED","none":{"tips":"充电后可观看"},
            "opus":{"pics":[{"url":"https://i0.hdslb.com/cover.jpg","width":10,"height":10}]}}},
            "module_stat":{"like":{"count":1},"comment":{"count":2},"forward":{"count":3}}}}"""
        )

        val blocked = assertNotNull(DynamicItem.fromDynamicItem(webItem).blocked)

        assertEquals("充电后可观看", blocked.hintMessage)
        assertEquals("https://i0.hdslb.com/cover.jpg", blocked.iconUrl)
    }
}
