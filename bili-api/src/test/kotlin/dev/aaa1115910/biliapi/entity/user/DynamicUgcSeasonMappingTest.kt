package dev.aaa1115910.biliapi.entity.user

import dev.aaa1115910.biliapi.http.entity.dynamic.DynamicItem as HttpDynamicItem
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DynamicUgcSeasonMappingTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `ugc season dynamic is mapped to a video module`() {
        val webItem = json.decodeFromString<HttpDynamicItem>(
            """{"id_str":"1","type":"DYNAMIC_TYPE_UGC_SEASON","modules":{
            "module_author":{"name":"up","face":"f","mid":1,"pub_time":"1小时前","pub_action":""},
            "module_dynamic":{"major":{"ugc_season":{
            "aid":100,"bvid":"BV1","cover":"c","desc":"d","duration_text":"01:02",
            "stat":{"play":"1.2万","danmaku":"34"},"title":"合集更新"}}},
            "module_stat":{"like":{"count":1},"comment":{"count":2},"forward":{"count":3}}}}"""
        )

        val item = DynamicItem.fromDynamicItem(webItem)

        assertEquals(DynamicType.UgcSeason, item.type)
        assertEquals(100L, item.video?.aid)
        assertEquals("合集更新", item.video?.title)
        assertEquals(12000L, item.video?.playCount)
        assertEquals(34, item.video?.danmakuCount)
        assertEquals(62, item.video?.durationSeconds)
    }

    @Test
    fun `ugc season dynamic without payload does not throw`() {
        val webItem = json.decodeFromString<HttpDynamicItem>(
            """{"id_str":"2","type":"DYNAMIC_TYPE_UGC_SEASON","modules":{
            "module_author":{"name":"up","face":"f","mid":1,"pub_time":"1小时前","pub_action":""},
            "module_dynamic":{"major":{}},
            "module_stat":{"like":{"count":1},"comment":{"count":2},"forward":{"count":3}}}}"""
        )

        val item = DynamicItem.fromDynamicItem(webItem)

        assertNull(item.video)
    }
}
