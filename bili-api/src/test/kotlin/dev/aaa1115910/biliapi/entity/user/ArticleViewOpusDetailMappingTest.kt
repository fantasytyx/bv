package dev.aaa1115910.biliapi.entity.user

import dev.aaa1115910.biliapi.http.BiliHttpClient
import dev.aaa1115910.biliapi.http.entity.user.OpusDetailData
import kotlinx.serialization.decodeFromString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ArticleViewOpusDetailMappingTest {
    private val json = BiliHttpClient.json

    private fun opusDetail(
        basic: String = BASIC,
        top: String? = null,
        title: String? = null,
        content: String = CONTENT_MODULE,
        stat: String = STAT_MODULE
    ): OpusDetailData {
        val modules = listOfNotNull(top ?: NULL_TOP_MODULE, title, AUTHOR_MODULE, content, stat)
            .joinToString(",\n")
        return json.decodeFromString(
            """
            {
              "item": {
                "id_str": "1266038303121997840",
                "type": "DYNAMIC_TYPE_DRAW",
                "basic": $basic,
                "modules": [
                  $modules
                ]
              },
              "fallback": null
            }
            """.trimIndent()
        )
    }

    @Test
    fun `opus detail carries the favorite count and both interaction states`() {
        val article = ArticleViewData.fromOpusDetail(opusDetail())

        assertEquals(123, article.favoriteCount)
        assertTrue(article.favorited)
        assertEquals(663, article.likeCount)
        assertTrue(article.liked)
        assertEquals(23, article.commentCount)
    }

    @Test
    fun `opus detail maps author comment thread and body`() {
        val article = ArticleViewData.fromOpusDetail(opusDetail())

        assertEquals("1266038303121997840", article.id)
        assertEquals("咝小喵MAO", article.authorName)
        assertEquals("2026年11月02日 17:15", article.publishTime)
        assertEquals(414180378L, article.commentId)
        assertEquals(11L, article.commentType)
        // 自动生成的标题不展示，正文按段落拼接（含富文本视频节点）
        assertEquals("", article.title)
        assertEquals("达妮娅的壁纸来了！！！\n视频：\nBV1iRb36KEAd", article.bodyText)
        assertEquals(
            listOf("https://i0.hdslb.com/bfs/new_dyn/a.png"),
            article.paragraphs.mapNotNull { it.imageUrl }
        )
    }

    @Test
    fun `opus detail maps the inline video card`() {
        val video = ArticleViewData.fromOpusDetail(opusDetail()).video

        assertEquals(117093055859601L, video?.aid)
        assertEquals("⚡️ 达 妮 娅 衣 橱 大 公 开 ⚡️", video?.title)
        assertEquals("01:48", video?.duration)
        assertEquals("28.4万", video?.play)
        assertEquals("339", video?.danmaku)
    }

    @Test
    fun `opus detail without content or stat keeps safe defaults`() {
        val article = ArticleViewData.fromOpusDetail(
            opusDetail(
                // opus 详情的标题是网页 <title>，带「 - 哔哩哔哩」后缀
                basic = """{"title": "有标题的图文 - 哔哩哔哩"}""",
                content = """{"module_type": "MODULE_TYPE_CONTENT", "module_content": {"paragraphs": []}}""",
                stat = """{"module_type": "MODULE_TYPE_STAT", "module_stat": null}"""
            )
        )

        assertEquals("有标题的图文", article.title)
        assertTrue(article.paragraphs.isEmpty())
        assertEquals(0, article.favoriteCount)
        assertFalse(article.favorited)
        assertEquals(0, article.likeCount)
        assertFalse(article.liked)
        assertNull(article.video)
        // 没有 comment_id_str / comment_type 时保持 0，由页面回退到默认评论类型
        assertEquals(0L, article.commentId)
        assertEquals(0L, article.commentType)
    }

    @Test
    fun `opus detail maps the top album pictures and module title`() {
        val article = ArticleViewData.fromOpusDetail(
            opusDetail(
                basic = """{"comment_type": 11, "comment_id_str": "396287923", "title": "紫颜-小仙紫的动态 - 哔哩哔哩"}""",
                top = TOP_ALBUM_MODULE,
                title = """{"module_type": "MODULE_TYPE_TITLE", "module_title": {"text": "嘻嘻！"}}""",
                content = TEXT_ONLY_CONTENT_MODULE
            )
        )

        assertEquals("396287923", article.commentId.toString())
        // basic.title 是网页 <title>，不能当标题；真实标题在 MODULE_TYPE_TITLE
        assertEquals("嘻嘻！", article.title)
        assertEquals("在B站营业", article.bodyText)
        assertEquals(
            listOf(
                "http://i0.hdslb.com/bfs/new_dyn/cover.jpg",
                "http://i0.hdslb.com/bfs/new_dyn/second.jpg"
            ),
            article.paragraphs.mapNotNull { it.imageUrl }
        )
    }

    @Test
    fun `opus detail does not repeat album pictures already in content`() {
        val article = ArticleViewData.fromOpusDetail(
            opusDetail(
                top = TOP_ALBUM_MODULE,
                content = """
                {
                  "module_type": "MODULE_TYPE_CONTENT",
                  "module_content": {
                    "paragraphs": [
                      {"para_type": 2, "pic": {"pics": [{"url": "http://i0.hdslb.com/bfs/new_dyn/cover.jpg"}]}}
                    ]
                  }
                }
                """.trimIndent()
            )
        )

        assertEquals(
            listOf(
                "http://i0.hdslb.com/bfs/new_dyn/cover.jpg",
                "http://i0.hdslb.com/bfs/new_dyn/second.jpg"
            ),
            article.paragraphs.mapNotNull { it.imageUrl }
        )
    }

    private companion object {
        val BASIC = """
        {
          "comment_type": 11,
          "comment_id_str": "414180378",
          "title": "咝小喵MAO的动态 - 哔哩哔哩"
        }
        """.trimIndent()

        val NULL_TOP_MODULE = """{"module_type": "MODULE_TYPE_TOP", "module_top": null}"""

        val AUTHOR_MODULE = """
        {
          "module_type": "MODULE_TYPE_AUTHOR",
          "module_author": {
            "name": "咝小喵MAO",
            "mid": 2009929,
            "pub_time": "2026年11月02日 17:15",
            "pub_action": ""
          }
        }
        """.trimIndent()

        val TOP_ALBUM_MODULE = """
        {
          "module_type": "MODULE_TYPE_TOP",
          "module_top": {
            "display": {
              "type": 1,
              "album": {
                "pics": [
                  {"url": "http://i0.hdslb.com/bfs/new_dyn/cover.jpg", "width": 4000, "height": 6000},
                  {"url": "http://i0.hdslb.com/bfs/new_dyn/second.jpg", "width": 1080, "height": 1620}
                ]
              },
              "video": null
            }
          }
        }
        """.trimIndent()

        val TEXT_ONLY_CONTENT_MODULE = """
        {
          "module_type": "MODULE_TYPE_CONTENT",
          "module_content": {
            "paragraphs": [
              {
                "para_type": 1,
                "text": {
                  "nodes": [
                    {"type": "TEXT_NODE_TYPE_WORD", "word": {"words": "在B站营业"}}
                  ]
                }
              }
            ]
          }
        }
        """.trimIndent()

        val CONTENT_MODULE = """
        {
          "module_type": "MODULE_TYPE_CONTENT",
          "module_content": {
            "paragraphs": [
              {
                "para_type": 1,
                "text": {
                  "nodes": [
                    {"type": "TEXT_NODE_TYPE_WORD", "word": {"words": "达妮娅的壁纸来了！！！\n视频：\n"}},
                    {"type": "TEXT_NODE_TYPE_RICH", "rich": {"text": "BV1iRb36KEAd", "orig_text": "BV1iRb36KEAd"}}
                  ]
                }
              },
              {
                "para_type": 2,
                "pic": {"pics": [{"url": "https://i0.hdslb.com/bfs/new_dyn/a.png", "width": 1080, "height": 1620}]}
              },
              {
                "para_type": 6,
                "link_card": {
                  "card": {
                    "type": "LINK_CARD_TYPE_UGC",
                    "ugc": {
                      "id_str": "117093055859601",
                      "title": "⚡️ 达 妮 娅 衣 橱 大 公 开 ⚡️",
                      "cover": "https://i0.hdslb.com/bfs/archive/cover.jpg",
                      "duration": "01:48",
                      "jump_url": "//www.bilibili.com/video/BV1CdgP62EJ1",
                      "stat": {"play": "28.4万", "danmaku": "339"}
                    }
                  }
                }
              }
            ]
          }
        }
        """.trimIndent()

        val STAT_MODULE = """
        {
          "module_type": "MODULE_TYPE_STAT",
          "module_stat": {
            "forward": {"status": false, "count": 15},
            "comment": {"status": false, "count": 23},
            "like": {"status": true, "count": 663},
            "favorite": {"status": true, "count": 123}
          }
        }
        """.trimIndent()
    }
}
