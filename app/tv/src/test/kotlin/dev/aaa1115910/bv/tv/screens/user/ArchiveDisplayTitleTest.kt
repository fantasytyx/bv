package dev.aaa1115910.bv.tv.screens.user

import dev.aaa1115910.biliapi.entity.user.SpaceCollection
import dev.aaa1115910.biliapi.entity.user.SpaceSeries
import kotlin.test.Test
import kotlin.test.assertEquals

class ArchiveDisplayTitleTest {
    private fun collection(title: String) = SpaceCollection(
        seasonId = 1,
        title = title,
        cover = "",
        videoCount = 0
    )

    private fun series(title: String) = SpaceSeries(
        seriesId = 1,
        title = title,
        cover = "",
        videoCount = 0
    )

    @Test
    fun `collection title gets the collection prefix`() {
        assertEquals("合集·每日一练", archiveDisplayTitle(collection("每日一练"), null))
    }

    @Test
    fun `series title gets the series prefix`() {
        assertEquals("系列·剑术入门", archiveDisplayTitle(null, series("剑术入门")))
    }

    @Test
    fun `collection wins when both are present`() {
        assertEquals(
            "合集·每日一练",
            archiveDisplayTitle(collection("每日一练"), series("剑术入门"))
        )
    }

    @Test
    fun `no selection returns an empty title`() {
        assertEquals("", archiveDisplayTitle(null, null))
    }
}
