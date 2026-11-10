package dev.aaa1115910.biliapi.entity.user

import dev.aaa1115910.biliapi.http.entity.toview.ToViewItem as HttpToViewItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ToViewItemMappingTest {
    @Test
    fun archiveItemKeepsOwnerAndStat() {
        val mapped = ToViewItem.fromToViewItem(
            HttpToViewItem(
                aid = 100,
                bvid = "BV1xx411c7mD",
                cid = 200,
                owner = HttpToViewItem.Owner(name = "author", mid = 300, face = "face"),
                title = "title",
                pic = "pic",
                duration = 60,
                progress = 30,
                stat = HttpToViewItem.Stat(view = 1000, danmaku = 20)
            )
        )

        assertEquals(ToViewItemType.Archive, mapped.type)
        assertEquals(100, mapped.oid)
        assertEquals("author", mapped.author)
        assertEquals(1000, mapped.play)
        assertEquals(20, mapped.danmaku)
        assertNull(mapped.epid)
        assertNull(mapped.seasonId)
    }

    @Test
    fun pgcItemTakesEpisodeAndSeasonIdsFromBangumi() {
        val mapped = ToViewItem.fromToViewItem(
            HttpToViewItem(
                aid = 100,
                title = "title",
                isPgc = true,
                bangumi = HttpToViewItem.Bangumi(
                    epId = 111,
                    season = HttpToViewItem.Season(seasonId = 222, title = "season")
                ),
                pgcLabel = "番剧"
            )
        )

        assertEquals(ToViewItemType.Pgc, mapped.type)
        assertEquals(111, mapped.epid)
        assertEquals(222, mapped.seasonId)
        assertEquals("番剧", mapped.pgcLabel)
    }

    @Test
    fun pgcItemWithoutBangumiFallsBackToRedirectUrl() {
        val mapped = ToViewItem.fromToViewItem(
            HttpToViewItem(
                aid = 100,
                title = "title",
                pgcLabel = "电影",
                redirectUrl = "https://www.bilibili.com/bangumi/play/ep333"
            )
        )

        assertEquals(ToViewItemType.Pgc, mapped.type)
        assertEquals(333, mapped.epid)
    }

    @Test
    fun pgcItemToleratesMissingOwnerAndStat() {
        val mapped = ToViewItem.fromToViewItem(
            HttpToViewItem(
                aid = 100,
                title = "title",
                pgcLabel = "纪录片"
            )
        )

        assertEquals(ToViewItemType.Pgc, mapped.type)
        assertEquals("", mapped.author)
        assertEquals(-1, mapped.play)
        assertEquals(-1, mapped.danmaku)
    }
}
