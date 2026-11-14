package dev.aaa1115910.biliapi.entity.user

import dev.aaa1115910.biliapi.http.entity.dynamic.DynamicItem as HttpDynamicItem
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ArticleViewDetailMappingTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun dynamicItem(payload: String): DynamicItem =
        DynamicItem.fromDynamicItem(json.decodeFromString<HttpDynamicItem>(payload))

    private fun drawDynamic(
        title: String = "",
        text: String = "",
        pics: String = """[{"url":"https://example.com/a.png"}]""",
        additional: String = ""
    ) = dynamicItem(
        """{"id_str":"1262969506060304391","type":"DYNAMIC_TYPE_DRAW","modules":{
        "module_author":{"name":"up","face":"f","mid":7788,"pub_time":"3天前","pub_action":""},
        "module_dynamic":{"desc":{"text":"$text"},"major":{"type":"MAJOR_TYPE_OPUS",
        "opus":{"title":"$title","summary":{"text":"$text"},"pics":$pics}}$additional},
        "module_stat":{"like":{"count":501,"status":true},"comment":{"count":8},
        "forward":{"count":3},"favorite":{"count":12,"status":true}}}}"""
    )

    @Test
    fun `draw dynamic becomes article title body images and counts`() {
        val article = ArticleViewData.fromDynamic(drawDynamic(title = "全场最忧郁", text = "正文"))

        assertEquals("1262969506060304391", article.id)
        assertEquals("全场最忧郁", article.title)
        assertEquals("正文", article.bodyText)
        assertEquals(listOf("https://example.com/a.png"), article.paragraphs.mapNotNull { it.imageUrl })
        assertEquals("up", article.authorName)
        assertEquals("3天前", article.publishTime)
        assertEquals(501, article.likeCount)
        assertEquals(8, article.commentCount)
        assertEquals(12, article.favoriteCount)
        assertTrue(article.liked)
        assertTrue(article.favorited)
    }

    @Test
    fun `auto generated opus title is dropped`() {
        val article = ArticleViewData.fromDynamic(drawDynamic(title = "up的动态 - 哔哩哔哩", text = "正文"))

        assertEquals("", article.title)
        assertEquals("正文", article.bodyText)
    }

    @Test
    fun `draw dynamic without images and text keeps an empty body`() {
        val article = ArticleViewData.fromDynamic(drawDynamic(text = "", pics = "[]"))

        assertEquals("", article.bodyText)
        assertTrue(article.paragraphs.isEmpty())
    }

    @Test
    fun `attached ugc video is carried into the article`() {
        val article = ArticleViewData.fromDynamic(
            drawDynamic(
                text = "看视频",
                pics = "[]",
                additional = ""","additional":{"ugc":{"title":"视频标题",
                "cover":"https://example.com/cover.png","duration":"02:50",
                "jump_url":"//www.bilibili.com/video/BV1xx411c7mD",
                "stat":{"play":"4.5万","danmaku":"64"}}}"""
            )
        )

        assertEquals("视频标题", article.video?.title)
        assertTrue((article.video?.aid ?: 0L) > 0)
        assertEquals("02:50", article.video?.duration)
        assertEquals("4.5万", article.video?.play)
    }

    @Test
    fun `quoted forward returns its original content and null when empty`() {
        val forward = dynamicItem(
            """{"id_str":"1","type":"DYNAMIC_TYPE_FORWARD","modules":{
            "module_author":{"name":"up","face":"f","mid":1,"pub_time":"1天前","pub_action":""},
            "module_dynamic":{"desc":{"text":"转发理由"}},
            "module_stat":{"like":{"count":1},"comment":{"count":2},"forward":{"count":3}}},
            "orig":{"id_str":"2","type":"DYNAMIC_TYPE_DRAW","modules":{
            "module_author":{"name":"orig","face":"f2","mid":2,"pub_time":"2天前","pub_action":""},
            "module_dynamic":{"desc":{"text":"原动态正文"},"major":{"type":"MAJOR_TYPE_OPUS",
            "opus":{"summary":{"text":"原动态正文"},"pics":[{"url":"https://example.com/q.png"}]}}},
            "module_stat":{"like":{"count":4},"comment":{"count":5},"forward":{"count":6}}}}}"""
        )

        val quoted = forward.orig?.toQuotedArticle()
        assertEquals("orig", quoted?.authorName)
        assertEquals("原动态正文", quoted?.bodyText)
        assertEquals(listOf("https://example.com/q.png"), quoted?.paragraphs?.mapNotNull { it.imageUrl })
    }

    @Test
    fun `empty quoted forward has nothing to embed`() {
        val forward = dynamicItem(
            """{"id_str":"1","type":"DYNAMIC_TYPE_FORWARD","modules":{
            "module_author":{"name":"up","face":"f","mid":1,"pub_time":"1天前","pub_action":""},
            "module_dynamic":{"desc":{"text":"转发理由"}},
            "module_stat":{"like":{"count":1},"comment":{"count":2},"forward":{"count":3}}},
            "orig":{"id_str":"2","type":"DYNAMIC_TYPE_NONE","modules":{
            "module_author":{"name":"orig","face":"f2","mid":2,"pub_time":"2天前","pub_action":""}}}}"""
        )

        assertNull(forward.orig?.toQuotedArticle())
    }

    @Test
    fun `jump url falls back to the opus page`() {
        val article = ArticleViewData.fromDynamic(drawDynamic(text = "正文"))

        assertEquals("https://www.bilibili.com/opus/1262969506060304391", article.displayJumpUrl)
    }
}
