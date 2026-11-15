package dev.aaa1115910.biliapi.entity.user

import dev.aaa1115910.biliapi.http.BiliHttpClient
import dev.aaa1115910.biliapi.http.entity.user.SpaceSeasonArchivesData
import dev.aaa1115910.biliapi.http.entity.user.SpaceSeasonSeriesData
import dev.aaa1115910.biliapi.http.entity.user.SpaceSeriesArchivesData
import kotlinx.serialization.decodeFromString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpaceSeasonSeriesDataTest {
    private val json = BiliHttpClient.json

    @Test
    fun `seasons and series map their own IDs and metadata`() {
        val data = json.decodeFromString<SpaceSeasonSeriesData>(
            """
            {
              "items_lists": {
                "page": {"page_num": 1, "page_size": 18, "total": 2},
                "seasons_list": [
                  {
                    "meta": {
                      "season_id": 12,
                      "title": "合辑标题",
                      "name": "合集·合辑标题",
                      "cover": "https://example.com/season.jpg",
                      "description": "合集简介",
                      "total": 23
                    },
                    "archives": []
                  }
                ],
                "series_list": [
                  {
                    "meta": {
                      "series_id": 34,
                      "name": "系列标题",
                      "cover": "https://example.com/series.jpg",
                      "description": "系列简介",
                      "total": 7
                    },
                    "archives": []
                  }
                ]
              }
            }
            """.trimIndent()
        )

        val page = SpaceSeasonSeriesPage.fromWebData(data)

        assertEquals(12L, page.seasons.single().seasonId)
        assertEquals("合辑标题", page.seasons.single().title)
        assertEquals(23, page.seasons.single().videoCount)
        assertEquals(34L, page.series.single().seriesId)
        assertEquals("系列标题", page.series.single().title)
        assertEquals(7, page.series.single().videoCount)
        assertFalse(page.hasMore)
    }

    @Test
    fun `empty series list is supported`() {
        val data = json.decodeFromString<SpaceSeasonSeriesData>(
            """
            {"items_lists":{"page":{"page_num":1,"page_size":18,"total":1},
              "seasons_list":[{"meta":{"season_id":12,"title":"合集","total":1}}],
              "series_list":[]}}
            """.trimIndent()
        )

        val page = SpaceSeasonSeriesPage.fromWebData(data)

        assertEquals(1, page.seasons.size)
        assertTrue(page.series.isEmpty())
    }

    @Test
    fun `archive pages tolerate optional stats and map both official response shapes`() {
        val seasons = json.decodeFromString<SpaceSeasonArchivesData>(
            """
            {
              "archives": [
                {"aid": 1001, "bvid": "BV1xx411c7mD", "title": "合集稿件",
                 "pic": "https://example.com/season-video.jpg", "duration": 120,
                 "pubdate": 1700000000, "stat": {"view": 1234, "danmaku": 56}},
                {"aid": 1002, "bvid": "BV1xx411c7mE", "title": "无统计",
                 "pic": "https://example.com/no-stat.jpg", "duration": 60, "pubdate": 1700000100}
              ],
              "page": {"page_num": 1, "page_size": 30, "total": 35}
            }
            """.trimIndent()
        )
        val seasonPage = SpaceArchivePage.fromSeasonArchivesData(seasons, mid = 42)

        assertEquals(1234L, seasonPage.videos[0].play)
        assertEquals(56, seasonPage.videos[0].danmaku)
        assertEquals(0L, seasonPage.videos[1].play)
        assertEquals(0, seasonPage.videos[1].danmaku)
        assertEquals(42L, seasonPage.videos[0].authorId)
        assertTrue(seasonPage.hasMore)

        val series = json.decodeFromString<SpaceSeriesArchivesData>(
            """
            {
              "archives": [
                {"aid": 2001, "bvid": "BV1xx411c7mF", "title": "系列稿件",
                 "pic": "https://example.com/series-video.jpg", "duration": 90,
                 "pubdate": 1700000200, "stat": {"view": 5678}, "upMid": 99}
              ],
              "page": {"num": 1, "size": 30, "total": 1}
            }
            """.trimIndent()
        )
        val seriesPage = SpaceArchivePage.fromSeriesArchivesData(series, mid = 42)

        assertEquals(5678L, seriesPage.videos.single().play)
        assertEquals(0, seriesPage.videos.single().danmaku)
        assertFalse(seriesPage.hasMore)
    }
}
