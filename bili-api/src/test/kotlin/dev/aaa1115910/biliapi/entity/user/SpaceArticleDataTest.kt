package dev.aaa1115910.biliapi.entity.user

import dev.aaa1115910.biliapi.http.BiliHttpClient
import dev.aaa1115910.biliapi.http.entity.user.SpaceArticleData
import kotlinx.serialization.decodeFromString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpaceArticleDataTest {
    private val json = BiliHttpClient.json

    @Test
    fun `opus feed maps article cards and uses safe defaults for missing optional data`() {
        val data = json.decodeFromString<SpaceArticleData>(
            """
            {
              "items": [
                {
                  "opus_id": "1262969506060304391",
                  "content": "全场最忧郁",
                  "cover": {"url": "https://example.com/cover.png"},
                  "stat": {"like": "501"}
                },
                {"opus_id": "1262969506060304392", "content": "无封面"}
              ],
              "offset": "994847299188293689",
              "has_more": true
            }
            """.trimIndent()
        )

        val page = SpaceArticlePage.fromWebSpaceArticleData(data)

        assertEquals(2, page.articles.size)
        assertEquals(1262969506060304391L, page.articles[0].id)
        assertEquals("全场最忧郁", page.articles[0].title)
        assertEquals("https://example.com/cover.png", page.articles[0].cover)
        assertEquals(501L, page.articles[0].likeCount)
        assertEquals("", page.articles[1].cover)
        assertEquals(0L, page.articles[1].likeCount)
        assertEquals("994847299188293689", page.nextOffset)
        assertTrue(page.hasMore)
    }

    @Test
    fun `opus feed supports empty pages`() {
        val data = json.decodeFromString<SpaceArticleData>("""{"items":[],"offset":"","has_more":false}""")

        val page = SpaceArticlePage.fromWebSpaceArticleData(data)

        assertTrue(page.articles.isEmpty())
        assertFalse(page.hasMore)
    }
}
