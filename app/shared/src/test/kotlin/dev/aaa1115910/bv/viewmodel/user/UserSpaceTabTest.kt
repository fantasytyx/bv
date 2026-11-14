package dev.aaa1115910.bv.viewmodel.user

import kotlin.test.Test
import kotlin.test.assertEquals

class UserSpaceTabTest {
    @Test
    fun `space tabs expose the four requested labels in order`() {
        assertEquals(
            listOf("视频", "图文", "动态", "合集和系列"),
            UserSpaceTab.entries.map { it.label }
        )
    }
}
