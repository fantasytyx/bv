package dev.aaa1115910.biliapi.entity.user

import dev.aaa1115910.biliapi.http.entity.dynamic.DynamicItem as HttpDynamicItem
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class DynamicArticleMappingTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `article dynamic carried by opus maps both the draw body and the article`() {
        val payload = requireNotNull(javaClass.getResourceAsStream("/dynamic-article-opus.json"))
            .bufferedReader()
            .use { it.readText() }
        val webItem = json.decodeFromString<HttpDynamicItem>(payload)

        val dynamic = DynamicItem.fromDynamicItem(webItem)

        assertEquals(DynamicType.Article, dynamic.type)
        assertEquals(DynamicMajorType.Opus, dynamic.majorType)
        assertEquals("1小时前", dynamic.author.pubTime)
        assertEquals("投稿了文章", dynamic.author.pubAction)
        assertEquals(41, dynamic.footer?.like)

        val coverUrl = "https://i0.hdslb.com/bfs/new_dyn/5b449b93edd0dd2495d29f0750bc9145409793037.png"
        val title = "美团/淘宝APP闪购嗖：233233，直接领29-20神券，亲测有效"
        val text = "B站这个密令靠谱，我自己用好久了\n" +
            "现在还时不时发个大红包，经常点外卖的，\n" +
            "用这个密令越用越大！\n" +
            "美团/淘宝APP闪购嗖：233233\n" +
            "[图片]"

        val draw = assertNotNull(dynamic.draw)
        assertEquals(title, draw.title)
        assertEquals(text, draw.text)
        assertEquals(1, draw.images.size)
        assertEquals(coverUrl, draw.images.first().url)
        assertEquals(1078, draw.images.first().width)
        assertEquals(606, draw.images.first().height)

        val article = assertNotNull(dynamic.article)
        assertEquals(title, article.title)
        assertEquals(text, article.text)
        assertEquals("//www.bilibili.com/opus/1270363713172406309", article.url)
        assertEquals(listOf(coverUrl), article.covers)
    }

    @Test
    fun `legacy article dynamic keeps the major article module`() {
        val webItem = json.decodeFromString<HttpDynamicItem>(
            """{"id_str":"3","type":"DYNAMIC_TYPE_ARTICLE","basic":{"comment_type":12,"comment_id_str":"53842372"},
            "modules":{
            "module_author":{"name":"up","face":"f","mid":1,"pub_time":"1小时前","pub_action":"投稿了文章"},
            "module_dynamic":{"major":{"type":"MAJOR_TYPE_ARTICLE","article":{
            "id":53842372,"title":"旧版专栏","desc":"旧版摘要",
            "covers":["https://i0.hdslb.com/cover.jpg"],"label":"专栏",
            "jump_url":"//www.bilibili.com/read/cv53842372"}}},
            "module_stat":{"like":{"count":1},"comment":{"count":2},"forward":{"count":3}}}}"""
        )

        val dynamic = DynamicItem.fromDynamicItem(webItem)

        assertNull(dynamic.draw)
        val article = assertNotNull(dynamic.article)
        assertEquals("旧版专栏", article.title)
        assertEquals("旧版摘要", article.text)
        assertEquals("//www.bilibili.com/read/cv53842372", article.url)
        assertEquals(listOf("https://i0.hdslb.com/cover.jpg"), article.covers)
    }

    @Test
    fun `article dynamic without a content module still exposes its fallback text`() {
        val webItem = json.decodeFromString<HttpDynamicItem>(
            """{"id_str":"4","type":"DYNAMIC_TYPE_ARTICLE","basic":{"comment_type":12,"comment_id_str":"0"},
            "modules":{
            "module_author":{"name":"up","face":"f","mid":1,"pub_time":"1小时前","pub_action":"投稿了文章"},
            "module_dynamic":{"desc":{"text":"只有正文"}},"module_stat":{}}}"""
        )

        val dynamic = DynamicItem.fromDynamicItem(webItem)

        assertEquals("只有正文", dynamic.word?.text)
        assertNull(dynamic.draw)
        // 移动端按 article!! 渲染，article 不能为空
        assertEquals("只有正文", dynamic.article?.text)
    }
}
