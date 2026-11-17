package dev.aaa1115910.bv.util

import dev.aaa1115910.biliapi.entity.user.FollowedUser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FollowedUpsTest {
    @Test
    fun `cache survives encode and decode roundtrip`() {
        val ups = listOf(
            FollowedUp(1L, "张三", "http://a/1.jpg", "签名 一"),
            FollowedUp(2L, "up two", "http://a/2.jpg", "")
        )
        assertEquals(ups, decodeFollowedUps(encodeFollowedUps(ups)))
    }

    @Test
    fun `broken cache lines are skipped instead of throwing`() {
        assertEquals(emptyList(), decodeFollowedUps(""))
        assertEquals(listOf(FollowedUp(7L, "ok", "f", "s")), decodeFollowedUps("garbage\n7\tok\tf\ts\n\n1\t2"))
    }

    @Test
    fun `cache is fresh only within its ttl`() {
        val now = 1_000_000_000_000L
        val hour = 60 * 60 * 1000L
        val ttl = CACHE_TTL_MS
        assertTrue(isCacheFresh(savedAtMs = now - (ttl - hour), nowMs = now))
        assertFalse(isCacheFresh(savedAtMs = now - ttl, nowMs = now))
        assertFalse(isCacheFresh(savedAtMs = now + hour, nowMs = now)) // 时钟回拨
        assertFalse(isCacheFresh(savedAtMs = 0L, nowMs = now))
    }

    @Test
    fun `only expired or timestamp-less uids are pruned`() {
        val now = 1_000_000_000_000L
        val hour = 60 * 60 * 1000L
        val savedAt = mapOf(
            1L to now - hour, // 新鲜
            2L to now - CACHE_TTL_MS - hour, // 过期
            3L to 0L // 只有数据键、没有时间戳
        )
        assertEquals(setOf(2L, 3L), staleCacheUids(savedAt, now))
    }

    @Test
    fun `keyword matching ignores whitespace and case`() {
        assertTrue(matchesUpKeyword("up tw", "UP Two", ""))
        assertTrue(matchesUpKeyword("张三", "张三", "签名"))
        assertTrue(matchesUpKeyword("", "任意", ""))
        assertFalse(matchesUpKeyword("abc", "ab", ""))
    }

    @Test
    fun `follow and unfollow update the cached list`() {
        val list = listOf(
            FollowedUp(1L, "a", "", ""),
            FollowedUp(2L, "b", "", "")
        )

        // 新关注追加到末尾
        assertEquals(
            listOf(1L, 2L, 3L),
            list.withFollowedUp(FollowedUp(3L, "c", "", "")).map { it.mid }
        )

        // 重复关注原位更新，不产生第二行
        val updated = list.withFollowedUp(FollowedUp(1L, "a2", "", ""))
        assertEquals(listOf(1L, 2L), updated.map { it.mid })
        assertEquals("a2", updated.first().name)

        // 取消关注移除
        assertEquals(listOf(1L), list.withoutFollowedUp(2L).map { it.mid })
    }

    @Test
    fun `follow order puts ascii names before chinese ones`() {
        val sorted = listOf(
            FollowedUser(1, "阿婆", "", ""),
            FollowedUser(2, "up", "", "")
        ).sortedFollowedUpOrder()
        assertEquals(listOf(2L, 1L), sorted.map { it.mid })
    }
}
