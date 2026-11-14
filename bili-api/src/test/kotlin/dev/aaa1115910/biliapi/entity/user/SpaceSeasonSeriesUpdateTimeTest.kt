package dev.aaa1115910.biliapi.entity.user

import dev.aaa1115910.biliapi.http.entity.user.SpaceArchiveItem
import dev.aaa1115910.biliapi.http.entity.user.SpaceSeasonSeriesData
import dev.aaa1115910.biliapi.http.entity.user.SpaceSeasonSeriesEntry
import dev.aaa1115910.biliapi.http.entity.user.SpaceSeasonSeriesMeta
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SpaceSeasonSeriesUpdateTimeTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `mtime from meta wins and is deserialized`() {
        val data = json.decodeFromString<SpaceSeasonSeriesData>(
            """{"items_lists":{"page":{"page_num":1,"page_size":10,"total":1},
            "seasons_list":[{"meta":{"season_id":11,"title":"合集A","cover":"c","description":"d","total":3,"mtime":1700000000},
            "archives":[{"pubdate":1600000000,"ctime":1650000000}]}]}}"""
        )

        val collection = SpaceSeasonSeriesPage.fromWebData(data).seasons.single()

        assertEquals(1700000000L, collection.updateTime)
    }

    @Test
    fun `falls back to newest archive time when meta time is absent`() {
        val entry = SpaceSeasonSeriesEntry(
            meta = SpaceSeasonSeriesMeta(seriesId = 7, name = "系列B", cover = "c", total = 2),
            archives = listOf(
                SpaceArchiveItem(pubdate = 1600000000, ctime = 1500000000),
                SpaceArchiveItem(pubdate = 1700000000, ctime = 1750000000)
            )
        )

        assertEquals(1750000000L, entry.resolveUpdateTime())
    }

    @Test
    fun `null when there is no time information at all`() {
        val entry = SpaceSeasonSeriesEntry(
            meta = SpaceSeasonSeriesMeta(seasonId = 5, title = "空合集")
        )

        assertNull(entry.resolveUpdateTime())
    }
}
