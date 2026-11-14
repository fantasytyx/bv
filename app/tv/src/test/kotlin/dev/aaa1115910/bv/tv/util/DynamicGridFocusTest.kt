package dev.aaa1115910.bv.tv.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DynamicGridFocusTest {
    private fun box(index: Int, left: Int, top: Int, width: Int = 300, height: Int = 400) =
        GridItemBox(
            index = index,
            left = left,
            top = top,
            right = left + width,
            bottom = top + height
        )

    @Test
    fun `down picks the card directly below in the same column`() {
        val current = box(1, left = 300, top = 0)
        val items = listOf(
            current,
            box(0, left = 0, top = 0),
            box(2, left = 600, top = 0),
            box(3, left = 0, top = 300),
            box(4, left = 300, top = 380),
            box(5, left = 600, top = 520)
        )

        assertEquals(4, sameColumnNeighbor(current, items, GridVertical.Down))
    }

    @Test
    fun `up picks the card directly above in the same column`() {
        val current = box(4, left = 300, top = 380)
        val items = listOf(
            box(0, left = 0, top = 0),
            box(1, left = 300, top = 0),
            box(3, left = 0, top = 300),
            current,
            box(5, left = 600, top = 520)
        )

        assertEquals(1, sameColumnNeighbor(current, items, GridVertical.Up))
    }

    @Test
    fun `neighbouring columns are never chosen`() {
        val current = box(1, left = 300, top = 0)
        val items = listOf(
            current,
            box(3, left = 0, top = 300),
            box(5, left = 600, top = 520)
        )

        assertNull(sameColumnNeighbor(current, items, GridVertical.Down))
    }

    @Test
    fun `no candidate below or above returns null`() {
        val current = box(0, left = 0, top = 0)
        val items = listOf(current)

        assertNull(sameColumnNeighbor(current, items, GridVertical.Down))
        assertNull(sameColumnNeighbor(current, items, GridVertical.Up))
    }

    @Test
    fun `the card landing exactly on the anchor row is picked after scrolling`() {
        // 向下滚过「卡高 + 间距」后，同列下一张卡正好落在锚点那一行
        val anchor = box(4, left = 300, top = 0)
        val items = listOf(
            box(7, left = 300, top = 0),
            box(10, left = 300, top = 300)
        )

        assertEquals(7, sameColumnNeighbor(anchor, items, GridVertical.Down))
    }

    @Test
    fun `scroll step covers the current card and the vertical spacing`() {
        val current = box(2, left = 600, top = 0, height = 260)

        assertEquals(276, verticalScrollStep(current, spacing = 16))
    }

    @Test
    fun `focus stays on a clipped card until it is fully revealed`() {
        val viewport = 540
        val bottomClipped = box(3, left = 0, top = 300, height = 420)
        val topClipped = box(3, left = 0, top = -80, height = 420)
        val fullyVisible = box(3, left = 0, top = 40, height = 400)

        // 下边缘被截：按 DOWN 只滚动，不换焦点
        assertTrue(shouldScrollToReveal(bottomClipped, viewport, GridVertical.Down))
        assertFalse(shouldScrollToReveal(bottomClipped, viewport, GridVertical.Up))
        // 上边缘被截：按 UP 只滚动
        assertTrue(shouldScrollToReveal(topClipped, viewport, GridVertical.Up))
        assertFalse(shouldScrollToReveal(topClipped, viewport, GridVertical.Down))
        // 完整可见：正常换焦点
        assertFalse(shouldScrollToReveal(fullyVisible, viewport, GridVertical.Down))
        assertFalse(shouldScrollToReveal(fullyVisible, viewport, GridVertical.Up))
    }

    @Test
    fun `up from the first row goes back to the tabs`() {
        val viewport = 540
        val firstRow = box(1, left = 300, top = 24, height = 300)
        val items = listOf(box(0, left = 0, top = 24), firstRow, box(2, left = 600, top = 24))

        // 已在顶部、同列上方没有卡：交回默认搜索，焦点才能回到 tab 栏
        assertEquals(
            VerticalMove.Delegate,
            verticalMove(firstRow, items, viewport, GridVertical.Up, canScroll = false)
        )
    }

    @Test
    fun `down focuses the same column neighbour when it is already visible`() {
        val viewport = 540
        val current = box(1, left = 300, top = 24, height = 300)
        val items = listOf(current, box(5, left = 300, top = 340), box(6, left = 0, top = 340))

        assertEquals(
            VerticalMove.Focus(5),
            verticalMove(current, items, viewport, GridVertical.Down, canScroll = true)
        )
    }

    @Test
    fun `missing neighbour scrolls only when the list can move`() {
        val viewport = 540
        val current = box(3, left = 0, top = 24, height = 300)
        val items = listOf(current)

        assertEquals(
            VerticalMove.ScrollToNeighbour,
            verticalMove(current, items, viewport, GridVertical.Down, canScroll = true)
        )
        assertEquals(
            VerticalMove.Delegate,
            verticalMove(current, items, viewport, GridVertical.Down, canScroll = false)
        )
    }

    @Test
    fun `clipped card scrolls before focus moves`() {
        val viewport = 540

        assertEquals(
            VerticalMove.ScrollToReveal,
            verticalMove(
                box(4, left = 0, top = 300, height = 420),
                emptyList(),
                viewport,
                GridVertical.Down,
                canScroll = true
            )
        )
        assertEquals(
            VerticalMove.Delegate,
            verticalMove(
                box(4, left = 0, top = 300, height = 420),
                emptyList(),
                viewport,
                GridVertical.Down,
                canScroll = false
            )
        )
    }
}
