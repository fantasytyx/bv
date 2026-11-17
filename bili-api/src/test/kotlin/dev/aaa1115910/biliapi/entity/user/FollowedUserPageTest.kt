package dev.aaa1115910.biliapi.entity.user

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FollowedUserPageTest {
    @Test
    fun `has more only while page end is below total`() {
        assertTrue(FollowedUserPage.from(emptyList(), page = 1, pageSize = 50, total = 120).hasMore)
        assertTrue(FollowedUserPage.from(emptyList(), page = 2, pageSize = 50, total = 120).hasMore)
        assertFalse(FollowedUserPage.from(emptyList(), page = 3, pageSize = 50, total = 120).hasMore)
    }

    @Test
    fun `cached prefix keeps paging possible`() {
        // 缓存里只有第 1 页时，total 仍然大于 50，必须能继续翻页
        val page = FollowedUserPage.from(emptyList(), page = 1, pageSize = 50, total = 900)
        assertTrue(page.hasMore)
        assertTrue(page.total == 900)
    }
}
