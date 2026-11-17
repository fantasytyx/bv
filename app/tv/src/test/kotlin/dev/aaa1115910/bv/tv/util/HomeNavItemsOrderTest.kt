package dev.aaa1115910.bv.tv.util

import dev.aaa1115910.bv.tv.component.HomeTopNavItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 首页导航顺序：保存的是枚举 ordinal，默认展示顺序只决定「新增项插在哪」。
 * 老配置里没有「其它收藏」（ordinal 8）时要补到「收藏」后面，隐藏过的不补。
 */
class HomeNavItemsOrderTest {

    @Test
    fun `default order shows other favorite right after favorite`() {
        val order = parseHomeNavItemsOrder("")

        assertEquals(
            HomeTopNavItem.Favorite.ordinal + 1,
            order.indexOf(HomeTopNavItem.OtherFavorite)
        )
        // 追剧仍在稍后再看之前
        assertTrue(
            order.indexOf(HomeTopNavItem.FollowingDrama) < order.indexOf(HomeTopNavItem.ToView)
        )
    }

    @Test
    fun `saved ordinals are read against the enum not the default order`() {
        // 6 = ToView、7 = FollowingDrama：旧实现按默认展示顺序的下标查表，会把两者换掉
        val order = parseHomeNavItemsOrder("0,1,2,3,4,5,6,7")

        assertEquals(
            listOf(
                HomeTopNavItem.Recommend,
                HomeTopNavItem.Popular,
                HomeTopNavItem.Dynamics,
                HomeTopNavItem.History,
                HomeTopNavItem.Favorite,
                HomeTopNavItem.OtherFavorite,
                HomeTopNavItem.FollowingSeason,
                HomeTopNavItem.ToView,
                HomeTopNavItem.FollowingDrama
            ),
            order
        )
    }

    @Test
    fun `missing other favorite is inserted at its default position`() {
        val order = parseHomeNavItemsOrder("0,1,2,3,4,5,6,7")

        assertEquals(
            order.indexOf(HomeTopNavItem.Favorite) + 1,
            order.indexOf(HomeTopNavItem.OtherFavorite)
        )
        assertEquals(HomeTopNavItem.entries.size, order.size)
    }

    @Test
    fun `hidden other favorite stays hidden`() {
        val order = parseHomeNavItemsOrder("0,1,2,3,4,-8,5,6,7")

        assertFalse(order.contains(HomeTopNavItem.OtherFavorite))
        assertEquals(HomeTopNavItem.entries.size - 1, order.size)
    }

    @Test
    fun `settings config keeps the same positions as the tab row`() {
        val configs = parseNavItemsOrderToConfig("0,1,2,3,4,5,6,7")

        val ordinals = configs.map { it.ordinal }
        assertEquals(
            ordinals.indexOf(HomeTopNavItem.Favorite.ordinal) + 1,
            ordinals.indexOf(HomeTopNavItem.OtherFavorite.ordinal)
        )
        assertTrue(configs.none { it.hidden })
    }

    @Test
    fun `settings config keeps hidden entries`() {
        val configs = parseNavItemsOrderToConfig("0,1,2,3,4,-8,5,6,7")

        val other = configs.single { it.ordinal == HomeTopNavItem.OtherFavorite.ordinal }
        assertTrue(other.hidden)
        assertEquals(HomeTopNavItem.entries.size, configs.size)
    }
}
