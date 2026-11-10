package dev.aaa1115910.biliapi.entity

import dev.aaa1115910.biliapi.http.entity.user.favorite.CntInfo
import dev.aaa1115910.biliapi.http.entity.user.favorite.FavoriteItemId
import kotlinx.serialization.Serializable

data class FavoriteFolderItemId(
    val id: Long,
    val type: FavoriteItemType,
    val bvid: String
) {
    companion object {
        fun fromFavoriteItemId(favoriteItemId: FavoriteItemId): FavoriteFolderItemId {
            return FavoriteFolderItemId(
                id = favoriteItemId.id,
                type = FavoriteItemType.fromValue(favoriteItemId.type),
                bvid = favoriteItemId.bvid
            )
        }
    }
}

enum class FavoriteItemType(val value: Int) {
    All(0),
    Video(2),
    Audio(12),
    VideoCollection(21),
    Pgc(24);

    companion object {
        fun fromValue(typeId: Int) = entries.first { it.value == typeId }
    }
}

enum class FavoriteResourceType(val value: Int) {
    Video(2),
    Pgc(42);

    companion object {
        fun fromFavoriteItemType(type: FavoriteItemType): FavoriteResourceType = when (type) {
            FavoriteItemType.Video -> Video
            FavoriteItemType.Pgc -> Pgc
            else -> throw IllegalArgumentException("Unsupported favorite item type: $type")
        }
    }
}


/**
 * 收藏夹元数据
 *
 * @param id 收藏夹mlid（完整id） 收藏夹原始id+创建者mid尾号2位
 * @param fid 收藏夹原始id
 * @param mid 创建者mid
 * @param title 收藏夹标题
 * @param cover 收藏夹封面图片url
 * @param mediaCount 收藏夹内容数量
 */
data class FavoriteFolderMetadata(
    val id: Long,
    val fid: Long,
    val mid: Long,
    val title: String,
    val cover: String?,
    val videoInThisFav: Boolean,
    var mediaCount: Int
) {
    companion object {
        fun fromHttpFavoriteFolderInfo(httpFavoriteFolderInfo: dev.aaa1115910.biliapi.http.entity.user.favorite.FavoriteFolderInfo): FavoriteFolderMetadata {
            return FavoriteFolderMetadata(
                id = httpFavoriteFolderInfo.id,
                fid = httpFavoriteFolderInfo.fid,
                mid = httpFavoriteFolderInfo.mid,
                title = httpFavoriteFolderInfo.title,
                cover = httpFavoriteFolderInfo.cover,
                videoInThisFav = httpFavoriteFolderInfo.favState == 1,
                mediaCount = httpFavoriteFolderInfo.mediaCount
            )
        }

        fun fromHttpUserFavoriteFolder(httpUserFavoriteFoldersData: dev.aaa1115910.biliapi.http.entity.user.favorite.UserFavoriteFoldersData.UserFavoriteFolder): FavoriteFolderMetadata {
            return FavoriteFolderMetadata(
                id = httpUserFavoriteFoldersData.id,
                fid = httpUserFavoriteFoldersData.fid,
                mid = httpUserFavoriteFoldersData.mid,
                title = httpUserFavoriteFoldersData.title,
                cover = null,
                videoInThisFav = httpUserFavoriteFoldersData.favState == 1,
                mediaCount = httpUserFavoriteFoldersData.mediaCount
            )
        }
    }
}

data class FavoriteFolderData(
    val info: FavoriteFolderMetadata,
    val medias: List<FavoriteItem>,
    val hasMore: Boolean
) {
    companion object {
        fun fromHttpFavoriteFolderInfoListData(httpFavoriteFolderInfoListData: dev.aaa1115910.biliapi.http.entity.user.favorite.FavoriteFolderInfoListData): FavoriteFolderData {
            return FavoriteFolderData(
                info = FavoriteFolderMetadata.fromHttpFavoriteFolderInfo(
                    httpFavoriteFolderInfoListData.info
                ),
                medias = httpFavoriteFolderInfoListData.medias.map {
                    FavoriteItem.fromHttpFavoriteItem(
                        it
                    )
                },
                hasMore = httpFavoriteFolderInfoListData.hasMore
            )
        }
    }
}

data class FavoriteItem(
    val id: Long,
    val type: FavoriteItemType,
    val title: String,
    val cover: String,
    val intro: String,
    val page: Int,
    val duration: Int,
    val upper: Upper,
    val link: String,
    val favTime: Long,
    val bvid: String,
    val cntInfo: CntInfo,
    val ogv: FavoriteItemOgv? = null
) {
    companion object {
        fun fromHttpFavoriteItem(httpFavoriteItem: dev.aaa1115910.biliapi.http.entity.user.favorite.FavoriteItem): FavoriteItem {
            return FavoriteItem(
                id = httpFavoriteItem.id,
                type = FavoriteItemType.fromValue(httpFavoriteItem.type),
                title = httpFavoriteItem.title,
                cover = httpFavoriteItem.cover,
                intro = httpFavoriteItem.intro,
                page = httpFavoriteItem.page,
                duration = httpFavoriteItem.duration,
                upper = Upper.fromHttpUpper(httpFavoriteItem.upper),
                link = httpFavoriteItem.link,
                favTime = httpFavoriteItem.favTime,
                bvid = httpFavoriteItem.bvid,
                cntInfo = httpFavoriteItem.cntInfo,
                ogv = httpFavoriteItem.ogv?.let {
                    FavoriteItemOgv(
                        typeId = it.typeId,
                        typeName = it.typeName,
                        seasonId = it.seasonId
                    )
                }
            )
        }
    }
}

data class FavoriteItemOgv(
    val typeId: Int,
    val typeName: String,
    val seasonId: Int?
)

/**
 * 收藏夹归属变更
 *
 * @param addMediaIds 需要新增的收藏夹 id
 * @param delMediaIds 需要移除的收藏夹 id
 */
data class FavoriteFolderChange(
    val addMediaIds: List<Long>,
    val delMediaIds: List<Long>
) {
    val isEmpty: Boolean
        get() = addMediaIds.isEmpty() && delMediaIds.isEmpty()

    companion object {
        /**
         * 只对选中状态发生变化的收藏夹发起请求，避免把已知之外的收藏关系一并删除
         */
        fun between(
            currentFolderIds: Collection<Long>,
            targetFolderIds: Collection<Long>
        ): FavoriteFolderChange {
            val current = currentFolderIds.toSet()
            val target = targetFolderIds.toSet()
            return FavoriteFolderChange(
                addMediaIds = targetFolderIds.distinct().filterNot { it in current },
                delMediaIds = currentFolderIds.distinct().filterNot { it in target }
            )
        }
    }
}

@Serializable
data class Upper(
    val mid: Long,
    val name: String,
    val face: String
) {
    companion object {
        fun fromHttpUpper(httpUpper: dev.aaa1115910.biliapi.http.entity.user.favorite.Upper): Upper {
            return Upper(
                mid = httpUpper.mid,
                name = httpUpper.name,
                face = httpUpper.face
            )
        }
    }
}