package dev.aaa1115910.biliapi.entity

import dev.aaa1115910.biliapi.http.BiliHttpClient
import dev.aaa1115910.biliapi.http.entity.user.favorite.CollectedFavoriteListData
import kotlinx.serialization.decodeFromString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 收藏的合集/收藏夹：`platform=web` 时 type=21（合集）与 type=11（收藏夹）混排在同一个 list，
 * 失效判据是 `state`（失效的收藏夹仍带原 media_count），这里用实测响应片段守住这两点。
 */
class CollectedFavoriteDataTest {
    private val json = BiliHttpClient.json

    @Test
    fun `season and folder are told apart by type and invalidation by state`() {
        val data = json.decodeFromString<CollectedFavoriteListData>(
            """
            {
              "count": 2,
              "has_more": true,
              "list": [
                {
                  "id": 9465322, "fid": 0, "mid": 3747575452667567, "attr": 0,
                  "title": "电网深处", "cover": "https://archive.biliimg.com/a.jpg",
                  "upper": {"mid": 3747575452667567, "name": "频谱之外", "face": ""},
                  "mtime": 1794746480, "state": 0, "fav_state": 1, "media_count": 13,
                  "type": 21
                },
                {
                  "id": 1291813, "fid": 0, "mid": 0, "attr": 1,
                  "title": "收藏夹失效", "cover": "https://i0.hdslb.com/b.jpg",
                  "upper": {"mid": 0, "name": "", "face": ""},
                  "mtime": 0, "state": 1, "fav_state": 0, "media_count": 25,
                  "type": 11
                }
              ]
            }
            """.trimIndent()
        )

        val page = CollectedFavoritePage.fromData(data)

        assertTrue(page.hasMore)
        val season = page.items[0]
        assertEquals(CollectedFavoriteType.Season, season.type)
        assertEquals("电网深处", season.title)
        assertEquals("频谱之外", season.upperName)
        assertEquals(13, season.mediaCount)
        assertFalse(season.invalid)
        // 合集内容要按「创建者 mid + season id」取
        assertEquals(3747575452667567L, season.ownerMid)

        val folder = page.items[1]
        assertEquals(CollectedFavoriteType.Folder, folder.type)
        // 失效项仍带原 media_count，所以只能靠 state 判失效
        assertTrue(folder.invalid)
        assertEquals(25, folder.mediaCount)
    }

    @Test
    fun `missing list and upper are tolerated`() {
        val data = json.decodeFromString<CollectedFavoriteListData>(
            """
            {"count": 0, "has_more": false, "list": [{"id": 1, "type": 11, "title": "空收藏夹"}]}
            """.trimIndent()
        )

        val page = CollectedFavoritePage.fromData(data)

        assertFalse(page.hasMore)
        assertEquals(1, page.items.single().id)
        assertEquals("", page.items.single().upperName)
    }

    @Test
    fun `unknown types are dropped instead of being shown as folders`() {
        val data = json.decodeFromString<CollectedFavoriteListData>(
            """
            {"count": 2, "has_more": false, "list": [
              {"id": 1, "type": 11, "title": "收藏夹"},
              {"id": 2, "type": 12, "title": "音频收藏"}
            ]}
            """.trimIndent()
        )

        val page = CollectedFavoritePage.fromData(data)

        assertEquals(listOf(1L), page.items.map { it.id })
        assertEquals(CollectedFavoriteType.Folder, page.items.single().type)
    }
}
