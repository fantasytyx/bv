package dev.aaa1115910.bv.tv.util

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import dev.aaa1115910.biliapi.entity.live.LiveAreaGroup
import dev.aaa1115910.bv.tv.component.HomeTopNavItem
import dev.aaa1115910.bv.tv.component.PgcTopNavItem
import dev.aaa1115910.bv.tv.component.TopNavItem
import dev.aaa1115910.bv.tv.component.UgcTopNavItem
import dev.aaa1115910.bv.tv.screens.main.DrawerItem
import dev.aaa1115910.bv.util.Prefs
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 导航项配置数据类
 */
data class NavItemConfig(
    val ordinal: Int,
    val hidden: Boolean
)

// ======================== 直播导航项配置 ========================

/**
 * 直播导航项配置数据类
 * @param id 标识符："R"=推荐, "F"=关注, 数字字符串=主分区ID
 * @param hidden 是否隐藏
 */
data class LiveNavItemConfig(
    val id: String,
    val hidden: Boolean
)

/**
 * 缓存的直播分区信息
 */
data class CachedLiveAreaInfo(
    val id: Int,
    val name: String
)

/**
 * 序列化直播分区列表为缓存字符串
 * 格式: "id1:name1,id2:name2,..."
 */
fun serializeLiveAreaGroups(areaGroups: List<LiveAreaGroup>): String {
    return areaGroups.joinToString(",") { "${it.id}:${it.name}" }
}

/**
 * 解析缓存的直播分区字符串
 * @return 分区ID和名称的列表
 */
fun parseCachedLiveAreaGroups(cacheString: String): List<CachedLiveAreaInfo> {
    if (cacheString.isBlank()) return emptyList()
    return cacheString.split(",").mapNotNull { part ->
        val colonIndex = part.indexOf(':')
        if (colonIndex < 0) return@mapNotNull null
        val id = part.substring(0, colonIndex).toIntOrNull() ?: return@mapNotNull null
        val name = part.substring(colonIndex + 1)
        CachedLiveAreaInfo(id, name)
    }
}

/**
 * 解析直播导航排序字符串为配置列表（用于设置对话框）
 * @param orderString 逗号分隔的标识符列表，"-"前缀表示隐藏
 * @param cachedAreas 缓存的分区信息列表
 * @param isLoggedIn 是否已登录（决定是否包含关注项）
 * @return 直播导航项配置列表（按显示顺序）
 */
fun parseLiveNavItemsOrderToConfig(
    orderString: String,
    cachedAreas: List<CachedLiveAreaInfo>,
    isLoggedIn: Boolean
): List<LiveNavItemConfig> {
    if (orderString.isBlank()) {
        // 默认配置：推荐 → (关注) → 按缓存顺序的各分区
        return buildList {
            add(LiveNavItemConfig("R", false))
            if (isLoggedIn) add(LiveNavItemConfig("F", false))
            cachedAreas.forEach { add(LiveNavItemConfig(it.id.toString(), false)) }
        }
    }

    val configs = orderString.split(",").mapNotNull { part ->
        val trimmed = part.trim()
        if (trimmed.isEmpty()) return@mapNotNull null
        val isHidden = trimmed.startsWith("-")
        val id = if (isHidden) trimmed.substring(1) else trimmed
        if (id.isEmpty()) return@mapNotNull null
        LiveNavItemConfig(id, isHidden)
    }

    // 收集已有的ID
    val existingIds = configs.map { it.id }.toSet()

    // 追加默认项中不存在的（新分区等）
    val result = configs.toMutableList()
    if ("R" !in existingIds) result.add(0, LiveNavItemConfig("R", false))
    if (isLoggedIn && "F" !in existingIds) {
        val rIndex = result.indexOfFirst { it.id == "R" }
        result.add(rIndex + 1, LiveNavItemConfig("F", false))
    }
    // 追加缓存中有但配置中没有的新分区
    cachedAreas.forEach { area ->
        val areaId = area.id.toString()
        if (areaId !in existingIds) {
            result.add(LiveNavItemConfig(areaId, false))
        }
    }

    // 过滤掉未登录时的"关注"项
    return if (!isLoggedIn) result.filter { it.id != "F" } else result
}

/**
 * 获取直播导航项的显示名称
 */
fun getLiveNavItemDisplayName(id: String, cachedAreas: List<CachedLiveAreaInfo>): String {
    return when (id) {
        "R" -> "推荐"
        "F" -> "关注"
        else -> {
            val areaId = id.toIntOrNull()
            cachedAreas.firstOrNull { it.id == areaId }?.name ?: "未知分区($id)"
        }
    }
}

// 用于 LiveContent 的 TopNavItem 包装
private object LiveRecommendNavItemHolder : TopNavItem {
    override fun getDisplayName(context: Context): String = "推荐"
}

private object LiveFollowingNavItemHolder : TopNavItem {
    override fun getDisplayName(context: Context): String = "关注"
}

private data class LiveParentAreaNavItemHolder(val group: LiveAreaGroup) : TopNavItem {
    override fun getDisplayName(context: Context): String = group.name
}

/**
 * 解析直播导航排序字符串，返回过滤后的 TopNavItem 列表（用于 LiveContent）
 * @param orderString 排序配置字符串
 * @param areaGroups 当前 API 返回的主分区列表
 * @param isLoggedIn 是否已登录
 * @return 过滤和排序后的 TopNavItem 列表
 */
fun parseLiveNavItemsOrder(
    orderString: String,
    areaGroups: List<LiveAreaGroup>,
    isLoggedIn: Boolean
): List<TopNavItem> {
    if (orderString.isBlank()) {
        // 默认：推荐 → (关注) → API 返回顺序的各分区
        return buildList {
            add(LiveRecommendNavItemHolder)
            if (isLoggedIn) add(LiveFollowingNavItemHolder)
            addAll(areaGroups.map { LiveParentAreaNavItemHolder(it) })
        }
    }

    val areaGroupMap = areaGroups.associateBy { it.id }
    val existingIds = mutableSetOf<String>()

    val result = orderString.split(",").mapNotNull { part ->
        val trimmed = part.trim()
        if (trimmed.isEmpty()) return@mapNotNull null
        val isHidden = trimmed.startsWith("-")
        val id = if (isHidden) trimmed.substring(1) else trimmed
        if (id.isEmpty() || isHidden) return@mapNotNull null
        existingIds.add(id)
        when (id) {
            "R" -> LiveRecommendNavItemHolder
            "F" -> if (isLoggedIn) LiveFollowingNavItemHolder else null
            else -> {
                val areaId = id.toIntOrNull() ?: return@mapNotNull null
                areaGroupMap[areaId]?.let { LiveParentAreaNavItemHolder(it) }
            }
        }
    }.toMutableList()

    // 追加配置中没有的新分区（API 新增的）
    areaGroups.forEach { group ->
        if (group.id.toString() !in existingIds) {
            result.add(LiveParentAreaNavItemHolder(group))
        }
    }

    return result
}

/**
 * 从 TopNavItem 获取直播导航项的 ID（用于 LiveContent 恢复选中状态等）
 */
fun getLiveNavItemId(item: TopNavItem): String? {
    return when (item) {
        is LiveRecommendNavItemHolder -> "R"
        is LiveFollowingNavItemHolder -> "F"
        is LiveParentAreaNavItemHolder -> item.group.id.toString()
        else -> null
    }
}

/**
 * 从 TopNavItem 获取 LiveAreaGroup（仅分区项有值）
 */
fun getLiveNavItemAreaGroup(item: TopNavItem): LiveAreaGroup? {
    return (item as? LiveParentAreaNavItemHolder)?.group
}

/**
 * 判断 TopNavItem 是否是直播推荐项
 */
fun isLiveRecommendItem(item: TopNavItem): Boolean = item is LiveRecommendNavItemHolder

/**
 * 判断 TopNavItem 是否是直播关注项
 */
fun isLiveFollowingItem(item: TopNavItem): Boolean = item is LiveFollowingNavItemHolder

/**
 * 判断 TopNavItem 是否是直播分区项
 */
fun isLiveAreaItem(item: TopNavItem): Boolean = item is LiveParentAreaNavItemHolder

/**
 * 获取根据设置过滤和排序后的直播导航项列表（Flow 版本）
 * 需要配合 areaGroups 使用，因此不能像 UGC/PGC 那样纯 Flow
 */
val liveNavItemsOrderFlow: Flow<String>
    get() = Prefs.liveNavItemsOrderFlow

private fun <T> parseTopNavItemsOrder(orderString: String, entries: List<T>): List<T> {
    if (orderString.isBlank()) return entries

    return orderString
        .split(",")
        .mapNotNull { part ->
            val trimmed = part.trim()
            val isHidden = trimmed.startsWith("-")
            val actualOrdinal = trimmed.removePrefix("-").toIntOrNull() ?: return@mapNotNull null
            actualOrdinal to isHidden
        }
        .filter { !it.second }
        .mapNotNull { (ordinal, _) -> entries.getOrNull(ordinal) }
}

/**
 * 获取根据设置过滤和排序后的首页导航项列表
 */
val homeNavItemsFlow: Flow<List<HomeTopNavItem>>
    get() = Prefs.homeNavItemsOrderFlow.map { orderString ->
        parseHomeNavItemsOrder(orderString)
    }

/**
 * 获取根据设置过滤和排序后的 UGC 顶部导航项列表
 */
val ugcNavItemsFlow: Flow<List<UgcTopNavItem>>
    get() = Prefs.ugcNavItemsOrderFlow.map { orderString ->
        parseUgcTopNavItemsOrder(orderString)
    }

/**
 * 获取根据设置过滤和排序后的 PGC 顶部导航项列表
 */
val pgcNavItemsFlow: Flow<List<PgcTopNavItem>>
    get() = Prefs.pgcNavItemsOrderFlow.map { orderString ->
        parsePgcTopNavItemsOrder(orderString)
    }

/**
 * 解析首页导航排序字符串
 *
 * 保存的是 [HomeTopNavItem.ordinal]，所以必须按 `entries` 查表；默认展示顺序只用于
 * 「新增项该插在哪」以及空配置时的初始顺序。
 *
 * @param orderString 逗号分隔的 ordinal 列表，负数表示隐藏
 * @return 过滤和排序后的导航项列表
 */
fun parseHomeNavItemsOrder(orderString: String): List<HomeTopNavItem> {
    if (orderString.isBlank()) return defaultHomeNavOrder
    return mergeHomeNavOrder(orderString)
        .filterNot { it.hidden }
        .mapNotNull { HomeTopNavItem.entries.getOrNull(it.ordinal) }
}

/**
 * 首页导航默认显示顺序：[FollowingDrama] 放在 [ToView] 之前、[OtherFavorite] 紧跟 [Favorite]
 *
 * 枚举声明顺序保持 ordinal 兼容旧配置，UI 上换个展示次序即可。
 */
private val defaultHomeNavOrder: List<HomeTopNavItem>
    get() = HomeTopNavItem.entries.toMutableList().apply {
        val idxToView = indexOf(HomeTopNavItem.ToView)
        val idxDrama = indexOf(HomeTopNavItem.FollowingDrama)
        if (idxToView >= 0 && idxDrama >= 0 && idxDrama > idxToView) {
            removeAt(idxDrama)
            add(idxToView, HomeTopNavItem.FollowingDrama)
        }
        val idxFavorite = indexOf(HomeTopNavItem.Favorite)
        val idxOther = indexOf(HomeTopNavItem.OtherFavorite)
        if (idxFavorite >= 0 && idxOther >= 0 && idxOther > idxFavorite + 1) {
            removeAt(idxOther)
            add(idxFavorite + 1, HomeTopNavItem.OtherFavorite)
        }
    }

/**
 * 把保存的排序与默认顺序合并：保存过的项（含隐藏项）保持原位置，
 * 老配置里没有的新增项按默认位置插入，隐藏过的新增项不会被补回来。
 */
private fun mergeHomeNavOrder(orderString: String): List<NavItemConfig> {
    val configs = orderString.split(",").mapNotNull { part ->
        val trimmed = part.trim()
        if (trimmed.isEmpty()) return@mapNotNull null
        val ordinal = trimmed.removePrefix("-").toIntOrNull() ?: return@mapNotNull null
        if (ordinal !in HomeTopNavItem.entries.indices) return@mapNotNull null
        NavItemConfig(ordinal, trimmed.startsWith("-"))
    }.toMutableList()

    val savedOrdinals = configs.mapTo(mutableSetOf()) { it.ordinal }
    defaultHomeNavOrder.forEachIndexed { defaultIndex, item ->
        if (item.ordinal in savedOrdinals) return@forEachIndexed
        val insertAt = configs.indexOfFirst { config ->
            defaultHomeNavOrder.indexOfFirst { it.ordinal == config.ordinal } > defaultIndex
        }.let { if (it < 0) configs.size else it }
        configs.add(insertAt, NavItemConfig(item.ordinal, false))
    }
    return configs
}

fun parseUgcTopNavItemsOrder(orderString: String): List<UgcTopNavItem> {
    return parseTopNavItemsOrder(orderString, UgcTopNavItem.entries)
}

fun parsePgcTopNavItemsOrder(orderString: String): List<PgcTopNavItem> {
    return parseTopNavItemsOrder(orderString, PgcTopNavItem.entries)
}

/**
 * 将指定导航项移到第一位并取消隐藏
 * 用于切换默认标签时，将新默认标签移到第一位
 * @param orderString 当前排序配置字符串
 * @param ordinal 要移到第一位的导航项 ordinal
 * @return 更新后的排序配置字符串
 */
fun moveNavItemToFirstAndUnhide(orderString: String, ordinal: Int): String {
    return moveNavItemToFirstAndUnhide(orderString = orderString, ordinal = ordinal, entriesCount = 0)
}

/**
 * 将指定导航项移到第一位并取消隐藏
 * @param orderString 当前排序配置字符串
 * @param ordinal 要移到第一位的导航项 ordinal
 * @param entriesCount 枚举项数量；当 orderString 为空时用于生成默认序列
 */
fun moveNavItemToFirstAndUnhide(orderString: String, ordinal: Int, entriesCount: Int): String {
    val normalizedOrderString = if (orderString.isBlank()) {
        if (entriesCount <= 0) return orderString
        (0 until entriesCount).joinToString(",")
    } else {
        orderString
    }

    val parts = normalizedOrderString.split(",").map { part ->
        val trimmed = part.trim()
        val isHidden = trimmed.startsWith("-")
        val absNum = trimmed.removePrefix("-").toIntOrNull() ?: return@map 0 to false
        absNum to isHidden
    }

    // 找到目标项
    val targetItem = parts.find { it.first == ordinal }
    if (targetItem == null) return normalizedOrderString

    // 构建新的顺序：目标项在前，其他项按原顺序在后
    val otherItems = parts.filter { it.first != ordinal }
    val newParts = listOf(
        ordinal.toString()  // 默认标签在第一位，取消隐藏
    ) + otherItems.map { (ord, hidden) ->
        if (hidden) "-$ord" else "$ord"
    }

    return newParts.joinToString(",")
}

/**
 * 解析首页导航排序字符串为配置列表
 * @param orderString 逗号分隔的 ordinal 列表，负数表示隐藏
 * @return 导航项配置列表（按显示顺序）
 */
fun parseNavItemsOrderToConfig(orderString: String): List<NavItemConfig> {
    if (orderString.isBlank()) {
        return defaultHomeNavOrder.map { NavItemConfig(it.ordinal, false) }
    }
    return mergeHomeNavOrder(orderString)
}

/**
 * 解析排序字符串为配置列表（通用版本）
 * @param orderString 逗号分隔的 ordinal 列表，负数表示隐藏
 * @param entriesCount 枚举项数量
 */
fun parseNavItemsOrderToConfig(orderString: String, entriesCount: Int): List<NavItemConfig> {
    if (entriesCount <= 0) return emptyList()

    if (orderString.isBlank()) {
        return (0 until entriesCount).map { NavItemConfig(it, false) }
    }

    val parsed = orderString
        .split(",")
        .mapNotNull { part ->
            val trimmed = part.trim()
            val isHidden = trimmed.startsWith("-")
            val actualOrdinal = trimmed.removePrefix("-").toIntOrNull() ?: return@mapNotNull null
            if (actualOrdinal !in 0 until entriesCount) return@mapNotNull null
            NavItemConfig(actualOrdinal, isHidden)
        }

    // 追加已保存配置中不存在的条目（如新增的导航项），默认显示
    val parsedOrdinals = parsed.map { it.ordinal }.toSet()
    val missing = (0 until entriesCount)
        .filter { it !in parsedOrdinals }
        .map { NavItemConfig(it, false) }

    return parsed + missing
}

// ======================== 主导航（左侧侧栏）配置 ========================

/**
 * 可配置的主导航项列表（不含 User 和 Settings）
 */
val configurableDrawerItems = listOf(
    DrawerItem.Search,
    DrawerItem.Home,
    DrawerItem.UGC,
    DrawerItem.PGC,
    DrawerItem.Live
)

/**
 * 解析主导航排序字符串为 DrawerItem 列表（过滤隐藏项）
 */
fun parseDrawerNavItemsOrder(orderString: String): List<DrawerItem> {
    if (orderString.isBlank()) return configurableDrawerItems

    return orderString
        .split(",")
        .mapNotNull { part ->
            val trimmed = part.trim()
            val isHidden = trimmed.startsWith("-")
            if (isHidden) return@mapNotNull null
            val ordinal = trimmed.toIntOrNull() ?: return@mapNotNull null
            DrawerItem.entries.getOrNull(ordinal)
        }
        .filter { it in configurableDrawerItems }
}

/**
 * 解析主导航排序字符串为配置列表（用于设置对话框）
 */
fun parseDrawerNavItemsOrderToConfig(orderString: String): List<NavItemConfig> {
    if (orderString.isBlank()) {
        return configurableDrawerItems.mapIndexed { _, item ->
            NavItemConfig(item.ordinal, false)
        }
    }

    val configs = orderString
        .split(",")
        .mapNotNull { part ->
            val trimmed = part.trim()
            val isHidden = trimmed.startsWith("-")
            val ordinal = trimmed.removePrefix("-").toIntOrNull() ?: return@mapNotNull null
            val drawerItem = DrawerItem.entries.getOrNull(ordinal) ?: return@mapNotNull null
            if (drawerItem !in configurableDrawerItems) return@mapNotNull null
            NavItemConfig(ordinal, isHidden)
        }

    // 追加配置中缺失的可配置项
    val existingOrdinals = configs.map { it.ordinal }.toSet()
    val missingConfigs = configurableDrawerItems
        .filter { it.ordinal !in existingOrdinals }
        .map { NavItemConfig(it.ordinal, false) }

    return configs + missingConfigs
}

/**
 * 保存主导航排序配置
 */
fun saveDrawerNavConfigs(navConfigs: List<NavItemConfig>) {
    val finalOrderString = navConfigs.joinToString(",") { config ->
        if (config.hidden) "-${config.ordinal}" else "${config.ordinal}"
    }
    Prefs.drawerNavItemsOrder = finalOrderString
}

/**
 * 获取根据设置过滤和排序后的主导航项列表（Flow 版本）
 */
val drawerNavItemsFlow: Flow<List<DrawerItem>>
    get() = Prefs.drawerNavItemsOrderFlow.map { orderString ->
        parseDrawerNavItemsOrder(orderString)
    }
