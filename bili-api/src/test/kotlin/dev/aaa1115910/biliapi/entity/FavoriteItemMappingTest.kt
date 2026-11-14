package dev.aaa1115910.biliapi.entity

import dev.aaa1115910.biliapi.http.entity.user.favorite.FavoriteItem as HttpFavoriteItem
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import kotlin.test.Test
import kotlin.test.assertEquals

class FavoriteItemMappingTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val pgcJson = """
        {
          "id": 5441272,
          "type": 24,
          "title": "孟婆辞职，阎王哭着求我回来上班",
          "cover": "https://example.com/episode.jpg",
          "intro": "第1集",
          "page": 0,
          "duration": 468,
          "upper": {"mid": 0, "name": "", "face": "", "jump_link": ""},
          "attr": 0,
          "cnt_info": {
            "collect": 0,
            "play": 0,
            "danmaku": 0,
            "vt": 0,
            "play_switch": 0,
            "reply": 0,
            "view_text_1": ""
          },
          "link": "https://www.bilibili.com/bangumi/play/ep5441272?story_mode=1",
          "ctime": 0,
          "pubtime": 1786672800,
          "fav_time": 1794143811,
          "bv_id": "BV1MBgn6PELJ",
          "bvid": "BV1MBgn6PELJ",
          "season": null,
          "ogv": {"type_name": "漫剧", "type_id": 12, "season_id": 283583},
          "ugc": null
        }
    """.trimIndent()

    @Test
    fun pgcFavoriteOperationUsesResourceType42() {
        assertEquals(24, FavoriteItemType.Pgc.value)
        assertEquals(2, FavoriteResourceType.Video.value)
        assertEquals(42, FavoriteResourceType.Pgc.value)
    }

    @Test
    fun supportedFavoriteItemsMapToTheirOperationResourceTypes() {
        assertEquals(
            FavoriteResourceType.Video,
            FavoriteResourceType.fromFavoriteItemType(FavoriteItemType.Video)
        )
        assertEquals(
            FavoriteResourceType.Pgc,
            FavoriteResourceType.fromFavoriteItemType(FavoriteItemType.Pgc)
        )
    }

    @Test
    fun pgcTypeAndOgvMetadataAreMapped() {
        val httpItem = json.decodeFromString<HttpFavoriteItem>(pgcJson)

        val favoriteItem = FavoriteItem.fromHttpFavoriteItem(httpItem)

        assertEquals(FavoriteItemType.Pgc, favoriteItem.type)
        assertEquals(5441272L, favoriteItem.id)
        assertEquals(
            FavoriteItemOgv(typeId = 12, typeName = "漫剧", seasonId = 283583),
            favoriteItem.ogv
        )
    }

    @Test
    fun pgcTypeNamesArePreservedForAllOgvCategories() {
        val categories = listOf(
            1 to "番剧",
            2 to "电影",
            3 to "纪录片",
            4 to "国创",
            5 to "电视剧",
            7 to "综艺",
            12 to "漫剧"
        )

        categories.forEach { (typeId, typeName) ->
            val categoryJson = pgcJson.replace(
                "\"type_name\": \"漫剧\", \"type_id\": 12",
                "\"type_name\": \"$typeName\", \"type_id\": $typeId"
            )
            val httpItem = json.decodeFromString<HttpFavoriteItem>(categoryJson)

            assertEquals(
                FavoriteItemOgv(typeId = typeId, typeName = typeName, seasonId = 283583),
                FavoriteItem.fromHttpFavoriteItem(httpItem).ogv
            )
        }
    }

    @Test
    fun pgcItemWithMissingOgvSeasonStillDecodes() {
        val item = json.decodeFromString<HttpFavoriteItem>(
            pgcJson.replace(", \"season_id\": 283583", "")
        )

        assertEquals(null, FavoriteItem.fromHttpFavoriteItem(item).ogv?.seasonId)
    }

    @Test
    fun videoCollectionMapsToFavoriteType21() {
        assertEquals(21, FavoriteResourceType.VideoCollection.value)
        assertEquals(
            FavoriteResourceType.VideoCollection,
            FavoriteResourceType.fromFavoriteItemType(FavoriteItemType.VideoCollection)
        )
    }
}
