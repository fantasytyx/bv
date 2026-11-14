package dev.aaa1115910.biliapi.entity.user

import dev.aaa1115910.biliapi.entity.Picture
import dev.aaa1115910.biliapi.http.entity.dynamic.DynamicItem as HttpDynamicItem
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DynamicQuotedArticleTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `quoted draw keeps text images and video`() {
        val quoted = DynamicItem(
            id = "126188518503022600",
            type = DynamicType.Draw,
            author = DynamicItem.DynamicAuthorModule(
                author = "up",
                avatar = "a",
                mid = 1,
                pubTime = "3天前",
                pubAction = ""
            ),
            draw = DynamicItem.DynamicDrawModule(
                title = "引用标题",
                text = "引用正文",
                images = listOf(Picture("https://i0.hdslb.com/1.jpg", 10, 10, "k"))
            ),
            video = DynamicItem.DynamicVideoModule(
                aid = 100,
                cid = 0,
                title = "引用视频",
                text = "",
                cover = "c",
                duration = "01:02",
                play = "1.2万",
                danmaku = "34"
            )
        )

        val article = assertNotNull(quoted.toQuotedArticle())

        assertEquals("引用标题", article.title)
        assertEquals("引用正文", article.bodyText)
        assertEquals(listOf("https://i0.hdslb.com/1.jpg"), article.paragraphs.mapNotNull { it.imageUrl })
        assertEquals(100, article.video?.aid)
        assertEquals("1.2万", article.video?.play)
    }

    @Test
    fun `quoted pure word keeps its text without a title`() {
        val quoted = DynamicItem(
            id = "2",
            type = DynamicType.Word,
            author = DynamicItem.DynamicAuthorModule("up", "a", 2, "1天前", ""),
            word = DynamicItem.DynamicWordModule(text = "纯文本引用")
        )

        val article = assertNotNull(quoted.toQuotedArticle())

        assertEquals("", article.title)
        assertEquals("纯文本引用", article.bodyText)
        assertTrue(article.paragraphs.all { it.imageUrl == null })
        assertNull(article.video)
    }

    @Test
    fun `quoted item without displayable content is skipped`() {
        val quoted = DynamicItem(
            id = "3",
            type = DynamicType.None,
            author = DynamicItem.DynamicAuthorModule("up", "a", 3, "1天前", "")
        )

        assertNull(quoted.toQuotedArticle())
    }

    @Test
    fun `web stat carries the like status of the dynamic`() {
        val webItem = json.decodeFromString<HttpDynamicItem>(
            """{"id_str":"4","type":"DYNAMIC_TYPE_DRAW","modules":{
            "module_author":{"name":"up","face":"f","mid":1,"pub_time":"1小时前","pub_action":""},
            "module_dynamic":{"major":{"type":"MAJOR_TYPE_OPUS","opus":{
            "title":"标题","summary":{"text":"正文"},
            "pics":[{"url":"https://i0.hdslb.com/1.jpg","width":10,"height":10}]}}},
            "module_stat":{"like":{"count":12,"status":true},"comment":{"count":2},"forward":{"count":3}}}}"""
        )

        val footer = assertNotNull(DynamicItem.fromDynamicItem(webItem).footer)

        assertTrue(footer.liked)
        assertEquals(12, footer.like)
    }
}
