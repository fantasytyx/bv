package dev.aaa1115910.biliapi.entity.user

import dev.aaa1115910.biliapi.http.entity.dynamic.DynamicData as HttpDynamicData
import dev.aaa1115910.biliapi.http.entity.dynamic.DynamicItem as HttpDynamicItem
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * 转发的原动态（`orig`）有图文、图文附带视频（`additional.ugc`）、视频三种形态。
 * 空间动态卡片三种都要出内容，所以这里固定住各自的映射结果。
 */
class DynamicForwardQuotedTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun item(payload: String): DynamicItem =
        DynamicItem.fromDynamicItem(json.decodeFromString<HttpDynamicItem>(payload))

    private fun author(name: String = "up") =
        """"module_author":{"name":"$name","face":"f","mid":1,"pub_time":"9月7日","pub_action":""}"""

    private fun stat() =
        """"module_stat":{"forward":{"count":1},"comment":{"count":4},"like":{"count":165}}"""

    private fun forwardPayload(orig: String) = """{"id_str":"100","type":"DYNAMIC_TYPE_FORWARD","modules":{
        ${author()},
        "module_dynamic":{"desc":{"text":"周末快乐哇！"}},
        ${stat()}},
        "orig":$orig}"""

    private fun forward(orig: String) = item(forwardPayload(orig))

    private fun opusOrig(
        text: String = "嘉宾公布｜确认出席异世界嘉年华2.0！",
        pics: String = """[{"url":"https://i0.hdslb.com/1.jpg","width":1080,"height":1440}]""",
        additional: String = ""
    ) = """{"id_str":"101","type":"DYNAMIC_TYPE_DRAW","modules":{
        ${author("上海世界树漫展")},
        "module_dynamic":{"major":{"type":"MAJOR_TYPE_OPUS","opus":{
        "title":"","summary":{"text":"$text"},"pics":$pics}}$additional},
        ${stat()}}}"""

    @Test
    fun `forward of a draw keeps the quoted text and images`() {
        val dynamic = forward(opusOrig())

        val orig = assertNotNull(dynamic.orig)
        assertEquals(DynamicType.Draw, orig.type)
        assertEquals("嘉宾公布｜确认出席异世界嘉年华2.0！", orig.draw?.text)
        assertEquals(listOf("https://i0.hdslb.com/1.jpg"), orig.draw?.images?.map { it.url })
        assertNull(orig.video)
    }

    @Test
    fun `forward of a draw with an attached video keeps both images and the video`() {
        val dynamic = forward(
            opusOrig(
                text = "录舞的间隙我们就这样休息",
                pics = """[
                    {"url":"https://i0.hdslb.com/a.jpg","width":1500,"height":2027},
                    {"url":"https://i0.hdslb.com/b.jpg","width":1500,"height":2000}]""",
                additional = ""","additional":{"type":"ADDITIONAL_TYPE_UGC","ugc":{
                    "id_str":"117602412201527",
                    "title":"挑战全网最还原",
                    "desc_second":"4.1万观看 53弹幕",
                    "jump_url":"//www.bilibili.com/video/BV15hWX6KE7C",
                    "cover":"https://i0.hdslb.com/v.jpg",
                    "duration":"02:50",
                    "stat":{"danmaku":"53","play":"4.1万","vt":""}}}"""
            )
        )

        val orig = assertNotNull(dynamic.orig)
        // 图片来自 opus.pics，视频来自 additional.ugc，两者不能互相顶掉
        assertEquals(2, orig.draw?.images?.size)
        val video = assertNotNull(orig.video)
        assertEquals("挑战全网最还原", video.title)
        assertEquals("https://i0.hdslb.com/v.jpg", video.cover)
        // desc_second 是「4.1万观看 53弹幕」，不是时长；时长要取 duration
        assertEquals("02:50", video.duration)
        assertEquals(170, video.durationSeconds)
        assertEquals(41000L, video.playCount)
        assertEquals(53, video.danmakuCount)
    }

    @Test
    fun `attached video without a duration keeps the card instead of throwing`() {
        val dynamic = forward(
            opusOrig(
                additional = ""","additional":{"type":"ADDITIONAL_TYPE_UGC","ugc":{
                    "title":"挑战全网最还原",
                    "desc_second":"4.1万观看 53弹幕",
                    "jump_url":"//www.bilibili.com/video/BV15hWX6KE7C",
                    "cover":"https://i0.hdslb.com/v.jpg"}}"""
            )
        )

        assertEquals(0, assertNotNull(dynamic.orig?.video).durationSeconds)
    }

    @Test
    fun `malformed archive duration only zeroes the duration`() {
        val dynamic = forward(
            """{"id_str":"102","type":"DYNAMIC_TYPE_AV","modules":{
            ${author("玵木_Anmua-")},
            "module_dynamic":{"major":{"type":"MAJOR_TYPE_ARCHIVE","archive":{
            "aid":"117154779237259","bvid":"BV1K4hg6WE9Z","title":"标题","cover":"c",
            "duration_text":"41.2万","stat":{"play":"41.2万","danmaku":"174"},
            "badge":{"text":"投稿视频"}}}},
            ${stat()}}}"""
        )

        val video = assertNotNull(dynamic.orig?.video)
        assertEquals(0, video.durationSeconds)
        assertEquals(412000L, video.playCount)
    }

    @Test
    fun `quoted opus images fall back to rich text nodes`() {
        val dynamic = forward(
            """{"id_str":"103","type":"DYNAMIC_TYPE_DRAW","modules":{
            ${author("亚特兰Atlan")},
            "module_dynamic":{"major":{"type":"MAJOR_TYPE_OPUS","opus":{
            "title":"","summary":{"text":"正文","rich_text_nodes":[
            {"type":"RICH_TEXT_NODE_TYPE_PIC","text":"","pics":[
            {"url":"https://i0.hdslb.com/inline.jpg","width":100,"height":100}]}]},"pics":[]}}},
            ${stat()}}}"""
        )

        assertEquals(
            listOf("https://i0.hdslb.com/inline.jpg"),
            dynamic.orig?.draw?.images?.map { it.url }
        )
    }

    @Test
    fun `dynamic list keeps every forward card of the page`() {
        val items = listOf(
            forwardPayload(opusOrig()),
            forwardPayload(
                opusOrig(
                    additional = ""","additional":{"type":"ADDITIONAL_TYPE_UGC","ugc":{
                    "title":"附带视频","jump_url":"//www.bilibili.com/video/BV15hWX6KE7C",
                    "cover":"c","duration":"02:50"}}"""
                )
            ),
            """{"id_str":"104","type":"DYNAMIC_TYPE_AV","modules":{
            ${author("玵木_Anmua-")},
            "module_dynamic":{"major":{"type":"MAJOR_TYPE_ARCHIVE","archive":{
            "aid":"117154779237259","title":"视频","cover":"c","duration_text":"01:40"}}},
            ${stat()}}}"""
        ).joinToString(",")
        val response = json.decodeFromString<HttpDynamicData>(
            """{"has_more":false,"offset":"","items":[$items]}"""
        )

        val data = DynamicData.fromDynamicData(response)

        assertEquals(3, data.dynamics.size)
        assertEquals(1, data.dynamics[0].orig?.draw?.images?.size)
        assertEquals(1, data.dynamics[1].orig?.draw?.images?.size)
        assertNotNull(data.dynamics[1].orig?.video)
        assertNotNull(data.dynamics[2].video)
    }
}
