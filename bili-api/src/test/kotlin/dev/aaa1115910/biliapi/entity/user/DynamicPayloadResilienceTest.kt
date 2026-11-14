package dev.aaa1115910.biliapi.entity.user

import dev.aaa1115910.biliapi.http.entity.dynamic.DynamicItem as HttpDynamicItem
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * 空间动态是「一次返回一页卡片」的接口，任何一条载荷异常都不该让整页失败。
 * 这些用例固定住「单条畸形 → 跳过，其余照常」的行为。
 */
class DynamicPayloadResilienceTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun item(payload: String): DynamicItem =
        DynamicItem.fromDynamicItem(json.decodeFromString<HttpDynamicItem>(payload))

    private fun author(name: String = "up") =
        """"module_author":{"name":"$name","face":"f","mid":1,"pub_time":"1小时前","pub_action":""}"""

    private fun stat() = """"module_stat":{"like":{"count":1},"comment":{"count":2},"forward":{"count":3}}"""

    @Test
    fun `AV dynamic without an archive payload does not throw`() {
        val dynamic = item(
            """{"id_str":"1","type":"DYNAMIC_TYPE_AV","modules":{
            ${author()},
            "module_dynamic":{"desc":{"text":"只有正文"},"major":{"type":"MAJOR_TYPE_ARCHIVE"}},
            ${stat()}}}"""
        )

        assertNull(dynamic.video)
        assertEquals("只有正文", dynamic.word?.text)
    }

    @Test
    fun `LIVE_RCMD backed by the legacy live payload does not throw`() {
        val dynamic = item(
            """{"id_str":"2","type":"DYNAMIC_TYPE_LIVE_RCMD","modules":{
            ${author()},
            "module_dynamic":{"major":{"type":"MAJOR_TYPE_LIVE","live":{
            "id":"748326029026628614","title":"绫华cos~","cover":"https://i0.hdslb.com/live.jpg",
            "jump_url":"//live.bilibili.com/5215238","live_state":1}}},
            ${stat()}}}"""
        )

        val live = assertNotNull(dynamic.liveRcmd)
        assertEquals("绫华cos~", live.title)
        assertEquals("https://i0.hdslb.com/live.jpg", live.cover)
    }

    @Test
    fun `LIVE_RCMD content without live play info does not throw`() {
        val dynamic = item(
            """{"id_str":"3","type":"DYNAMIC_TYPE_LIVE_RCMD","modules":{
            ${author()},
            "module_dynamic":{"major":{"type":"MAJOR_TYPE_LIVE_RCMD","live_rcmd":{
            "content":"{\"type\":2,\"live_record_info\":{\"title\":\"回放\"}}"}}},
            ${stat()}}}"""
        )

        assertNull(dynamic.liveRcmd)
    }

    @Test
    fun `PGC dynamic without a pgc payload does not throw`() {
        val dynamic = item(
            """{"id_str":"4","type":"DYNAMIC_TYPE_PGC_UNION","modules":{
            ${author()},
            "module_dynamic":{"major":{"type":"MAJOR_TYPE_PGC"}},
            ${stat()}}}"""
        )

        assertNull(dynamic.pgc)
    }

    @Test
    fun `dynamic list keeps a degraded card instead of failing the page`() {
        val response = json.decodeFromString<dev.aaa1115910.biliapi.http.entity.dynamic.DynamicData>(
            """{"has_more":false,"offset":"","items":[
            {"id_str":"ok","type":"DYNAMIC_TYPE_DRAW","modules":{
              ${author()},
              "module_dynamic":{"major":{"type":"MAJOR_TYPE_OPUS","opus":{
              "summary":{"text":"正文"},"pics":[]}}},
              ${stat()}}},
            {"id_str":"degraded","type":"DYNAMIC_TYPE_AV","modules":{
              ${author()},
              "module_dynamic":{"major":{"type":"MAJOR_TYPE_ARCHIVE"}},
              ${stat()}}}
            ]}"""
        )

        val data = DynamicData.fromDynamicData(response)

        assertEquals(listOf("ok", "degraded"), data.dynamics.map { it.id })
        assertNull(data.dynamics[1].video)
    }
}
