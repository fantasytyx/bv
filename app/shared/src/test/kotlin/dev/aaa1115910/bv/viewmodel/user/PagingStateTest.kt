package dev.aaa1115910.bv.viewmodel.user

import kotlin.test.Test
import kotlin.test.assertEquals

class PagingStateTest {
    private data class Item(val id: String?)

    @Test
    fun `new items keep order and drop ids already known`() {
        val known = mutableSetOf("100", "101")

        val newItems = listOf(Item("101"), Item("102"), Item("103"))
            .newItems(known) { it.id }

        assertEquals(listOf("102", "103"), newItems.map { it.id })
        assertEquals(setOf("100", "101", "102", "103"), known)
    }

    @Test
    fun `items without id are always kept`() {
        val known = mutableSetOf<String>()

        val newItems = listOf(Item(null), Item(null), Item("100"))
            .newItems(known) { it.id }

        assertEquals(listOf(null, null, "100"), newItems.map { it.id })
    }

    @Test
    fun `duplicate ids inside one page are dropped`() {
        val known = mutableSetOf<String>()

        val newItems = listOf(Item("1"), Item("1"), Item("2"))
            .newItems(known) { it.id }

        assertEquals(listOf("1", "2"), newItems.map { it.id })
    }
}
