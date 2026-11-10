package dev.aaa1115910.bv.entity.carddata

import dev.aaa1115910.biliapi.entity.FavoriteItem
import dev.aaa1115910.biliapi.entity.FavoriteItemOgv
import dev.aaa1115910.biliapi.entity.FavoriteItemType
import dev.aaa1115910.biliapi.entity.FavoriteResourceType
import dev.aaa1115910.biliapi.entity.Upper
import dev.aaa1115910.biliapi.http.entity.user.favorite.CntInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class FavoriteItemCardDataTest {
    @Test
    fun pgcFavoriteMapsToEpisodeSeasonAndTypeBadge() {
        val card = favoriteItem(
            type = FavoriteItemType.Pgc,
            ogv = FavoriteItemOgv(typeId = 12, typeName = "漫剧", seasonId = 283583)
        ).toVideoCardDataOrNull()

        assertEquals(true, card?.jumpToSeason)
        assertEquals(117086714077138L, card?.avid)
        assertEquals(5441272, card?.epId)
        assertEquals(283583, card?.seasonId)
        assertEquals(FavoriteItemType.Pgc, card?.favoriteItemType)
        assertEquals(FavoriteResourceType.Pgc, card?.favoriteResourceType)
        assertEquals("漫剧", card?.pgcTypeName)
        assertEquals(listOf("漫剧"), card?.coverBadges)
    }

    @Test
    fun videoFavoriteRemainsUgcCard() {
        val card = favoriteItem(type = FavoriteItemType.Video).toVideoCardDataOrNull()

        assertFalse(card!!.jumpToSeason)
        assertEquals(FavoriteItemType.Video, card.favoriteItemType)
        assertEquals(FavoriteResourceType.Video, card.favoriteResourceType)
        assertNull(card.pgcTypeName)
        assertEquals(5441272L, card.avid)
    }

    @Test
    fun pgcWithoutOgvSeasonCannotMapToCard() {
        assertNull(
            favoriteItem(
                type = FavoriteItemType.Pgc,
                ogv = FavoriteItemOgv(typeId = 12, typeName = "漫剧", seasonId = null)
            ).toVideoCardDataOrNull()
        )
    }

    @Test
    fun pgcWithInvalidBvidCannotMapToActionCard() {
        assertNull(
            favoriteItem(
                type = FavoriteItemType.Pgc,
                ogv = FavoriteItemOgv(typeId = 12, typeName = "漫剧", seasonId = 283583),
                bvid = "BV1?????????"
            ).toVideoCardDataOrNull()
        )
    }

    private fun favoriteItem(
        type: FavoriteItemType,
        ogv: FavoriteItemOgv? = null,
        bvid: String = "BV1MBgn6PELJ"
    ) = FavoriteItem(
        id = 5441272,
        type = type,
        title = "孟婆辞职，阎王哭着求我回来上班",
        cover = "https://example.com/episode.jpg",
        intro = "第1集",
        page = 0,
        duration = 468,
        upper = Upper(mid = 0, name = "", face = ""),
        link = "https://www.bilibili.com/bangumi/play/ep5441272?story_mode=1",
        favTime = 1794143811,
        bvid = bvid,
        cntInfo = CntInfo(collect = 0, play = 0),
        ogv = ogv
    )
}
