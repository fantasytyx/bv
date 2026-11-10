package dev.aaa1115910.biliapi.entity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FavoriteFolderChangeTest {
    @Test
    fun subscribingToAnotherFolderOnlyAddsTheNewFolder() {
        val change = FavoriteFolderChange.between(
            currentFolderIds = listOf(1),
            targetFolderIds = listOf(1, 2)
        )

        assertEquals(listOf(2L), change.addMediaIds)
        assertEquals(emptyList<Long>(), change.delMediaIds)
    }

    @Test
    fun unsubscribingOnlyRemovesTheDroppedFolder() {
        val change = FavoriteFolderChange.between(
            currentFolderIds = listOf(1, 2),
            targetFolderIds = listOf(1)
        )

        assertEquals(emptyList<Long>(), change.addMediaIds)
        assertEquals(listOf(2L), change.delMediaIds)
    }

    @Test
    fun switchingFoldersAddsAndRemoves() {
        val change = FavoriteFolderChange.between(
            currentFolderIds = listOf(1),
            targetFolderIds = listOf(3)
        )

        assertEquals(listOf(3L), change.addMediaIds)
        assertEquals(listOf(1L), change.delMediaIds)
    }

    @Test
    fun unchangedSelectionProducesNoRequest() {
        val change = FavoriteFolderChange.between(
            currentFolderIds = listOf(1, 2),
            targetFolderIds = listOf(2, 1)
        )

        assertTrue(change.isEmpty)
    }

    @Test
    fun firstFavoriteAddsEverySelectedFolder() {
        val change = FavoriteFolderChange.between(
            currentFolderIds = emptyList(),
            targetFolderIds = listOf(4, 5)
        )

        assertEquals(listOf(4L, 5L), change.addMediaIds)
        assertEquals(emptyList<Long>(), change.delMediaIds)
    }

    @Test
    fun clearingEveryFolderRemovesAllKnownMemberships() {
        val change = FavoriteFolderChange.between(
            currentFolderIds = listOf(1, 2),
            targetFolderIds = emptyList()
        )

        assertEquals(emptyList<Long>(), change.addMediaIds)
        assertEquals(listOf(1L, 2L), change.delMediaIds)
    }
}
