package dev.aaa1115910.biliapi.entity

import dev.aaa1115910.biliapi.http.entity.user.favorite.CollectedFavoriteItem
import dev.aaa1115910.biliapi.http.entity.user.favorite.CollectedFavoriteListData

enum class CollectedFavoriteType {
    Season,
    Folder;

    companion object {
        /** 21：合集，11：收藏夹，其余类型没有对应的内容列表，直接跳过 */
        fun fromValue(value: Int) = when (value) {
            21 -> Season
            11 -> Folder
            else -> null
        }
    }
}

/**
 * 用户收藏的合集/收藏夹（网页端空间页「其他收藏 › 合集/收藏夹」）
 *
 * @param ownerMid 合集/收藏夹创建者的 mid，取内容列表时要用
 * @param invalid 服务端置了 state 的失效项：列表里照样显示，但点进去没有内容
 * @param updateTime 秒级时间戳
 */
data class CollectedFavorite(
    val id: Long,
    val type: CollectedFavoriteType,
    val title: String,
    val cover: String,
    val ownerMid: Long = 0,
    val upperName: String = "",
    val mediaCount: Int = 0,
    val updateTime: Long = 0,
    val invalid: Boolean = false
) {
    companion object {
        fun fromHttpItem(item: CollectedFavoriteItem): CollectedFavorite? {
            val type = CollectedFavoriteType.fromValue(item.type) ?: return null
            return CollectedFavorite(
                id = item.id,
                type = type,
                title = item.title,
                cover = item.cover,
                ownerMid = item.mid,
                upperName = item.upper?.name.orEmpty(),
                mediaCount = item.mediaCount,
                updateTime = item.mtime,
                invalid = item.state != 0
            )
        }
    }
}

data class CollectedFavoritePage(
    val items: List<CollectedFavorite>,
    val hasMore: Boolean
) {
    companion object {
        fun fromData(data: CollectedFavoriteListData) = CollectedFavoritePage(
            items = data.list.mapNotNull(CollectedFavorite::fromHttpItem),
            hasMore = data.hasMore
        )
    }
}

