package dev.aaa1115910.bv.tv.util

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GridFocusTest {
    @Test
    fun `focus can move down from last content row to a focusable footer`() {
        val columns = 4
        val contentCount = 13
        val itemCount = gridFocusItemCount(
            contentItemCount = contentCount,
            footerFocusable = true,
            columnCount = columns
        )

        assertFalse(
            shouldBlockDownFocusExit(
                currentIndex = 12,
                itemCount = itemCount,
                columnCount = columns
            )
        )
    }

    @Test
    fun `focus remains in grid when no footer is focusable`() {
        val columns = 4
        val itemCount = gridFocusItemCount(
            contentItemCount = 13,
            footerFocusable = false,
            columnCount = columns
        )

        assertTrue(
            shouldBlockDownFocusExit(
                currentIndex = 12,
                itemCount = itemCount,
                columnCount = columns
            )
        )
    }

    @Test
    fun `focus can move from archive heading to retry when archive list is empty`() {
        val columns = 4
        val itemCount = gridFocusItemCount(
            contentItemCount = 0,
            footerFocusable = true,
            columnCount = columns,
            leadingItemCount = 1
        )

        assertFalse(
            shouldBlockDownFocusExit(
                currentIndex = 0,
                itemCount = itemCount,
                columnCount = columns
            )
        )
    }
}
