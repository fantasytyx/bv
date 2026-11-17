package dev.aaa1115910.biliapi.http.entity.user.favorite

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 用户收藏的合集/收藏夹列表（`/x/v3/fav/folder/collected/list`）
 *
 * 带 `platform=web` 时合集（type=21）与收藏夹（type=11）混在同一个 list 里，按收藏时间倒序。
 */
@Serializable
data class CollectedFavoriteListData(
    val count: Int = 0,
    val list: List<CollectedFavoriteItem> = emptyList(),
    @SerialName("has_more")
    val hasMore: Boolean = false
)

/**
 * 收藏的合集/收藏夹
 *
 * @param type 21：合集 11：收藏夹
 * @param state 0：正常 其它：已失效（合集标题会变成「该合集已失效」）
 * @param mediaCount 内容数量；失效的收藏夹仍保留原值，所以只能靠 [state] 判失效
 */
@Serializable
data class CollectedFavoriteItem(
    val id: Long = 0,
    val fid: Long = 0,
    val mid: Long = 0,
    val attr: Int = 0,
    val title: String = "",
    val cover: String = "",
    val upper: Upper? = null,
    val mtime: Long = 0,
    val state: Int = 0,
    val type: Int = 0,
    @SerialName("media_count")
    val mediaCount: Int = 0
)

