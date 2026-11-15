package dev.aaa1115910.bv.tv.util

import kotlin.test.Test
import kotlin.test.assertEquals

class UpSpaceArchiveNavigationTest {
    private val seasonIds = listOf(11L, 12L)
    private val seriesIds = listOf(21L, 22L)

    @Test
    fun `season index is used as-is`() {
        assertEquals(1, archiveFocusIndex("season-12", seasonIds, seriesIds))
    }

    @Test
    fun `series index is offset by the season count`() {
        assertEquals(2, archiveFocusIndex("series-21", seasonIds, seriesIds))
        assertEquals(3, archiveFocusIndex("series-22", seasonIds, seriesIds))
    }

    @Test
    fun `series index starts at zero when there is no season`() {
        assertEquals(0, archiveFocusIndex("series-21", emptyList(), seriesIds))
    }

    @Test
    fun `unknown keys return minus one`() {
        assertEquals(-1, archiveFocusIndex(null, seasonIds, seriesIds))
        assertEquals(-1, archiveFocusIndex("season-99", seasonIds, seriesIds))
        assertEquals(-1, archiveFocusIndex("series-99", seasonIds, seriesIds))
        assertEquals(-1, archiveFocusIndex("bogus", seasonIds, seriesIds))
        assertEquals(-1, archiveFocusIndex("season-abc", seasonIds, seriesIds))
    }
}
