package dev.aaa1115910.biliapi.repositories

import dev.aaa1115910.biliapi.entity.ApiType
import dev.aaa1115910.biliapi.entity.FavoriteFolderData
import dev.aaa1115910.biliapi.entity.FavoriteFolderMetadata
import dev.aaa1115910.biliapi.entity.FavoriteResourceType
import dev.aaa1115910.biliapi.http.BiliHttpApi
import kotlinx.coroutines.CancellationException
import org.koin.core.annotation.Single

@Single
class FavoriteRepository(
    private val authRepository: AuthRepository
) {
    suspend fun checkVideoFavoured(
        aid: Long,
        preferApiType: ApiType = ApiType.Web
    ): Boolean {
        return when (preferApiType) {
            ApiType.Web -> BiliHttpApi.checkVideoFavoured(
                avid = aid,
                sessData = authRepository.sessionData
            )

            ApiType.App -> BiliHttpApi.checkVideoFavoured(
                avid = aid,
                accessKey = authRepository.accessToken
            )
        }
    }

    suspend fun addVideoToFavoriteFolder(
        aid: Long,
        addMediaIds: List<Long>,
        preferApiType: ApiType = ApiType.Web,
        resourceType: FavoriteResourceType = FavoriteResourceType.Video
    ) {
        when (preferApiType) {
            ApiType.Web -> BiliHttpApi.setVideoToFavorite(
                avid = aid,
                type = resourceType.value,
                addMediaIds = addMediaIds,
                sessData = authRepository.sessionData,
                csrf = authRepository.biliJct
            )

            ApiType.App -> BiliHttpApi.setVideoToFavorite(
                avid = aid,
                type = resourceType.value,
                addMediaIds = addMediaIds,
                accessKey = authRepository.accessToken
            )
        }
    }

    suspend fun delVideoFromFavoriteFolder(
        aid: Long,
        delMediaIds: List<Long>,
        resourceType: FavoriteResourceType = FavoriteResourceType.Video,
        preferApiType: ApiType = ApiType.Web
    ) {
        when (preferApiType) {
            ApiType.Web -> BiliHttpApi.setVideoToFavorite(
                avid = aid,
                type = resourceType.value,
                delMediaIds = delMediaIds,
                sessData = authRepository.sessionData,
                csrf = authRepository.biliJct
            )

            ApiType.App -> BiliHttpApi.setVideoToFavorite(
                avid = aid,
                type = resourceType.value,
                delMediaIds = delMediaIds,
                accessKey = authRepository.accessToken
            )
        }
    }

    suspend fun updateVideoToFavoriteFolder(
        aid: Long,
        addMediaIds: List<Long>,
        delMediaIds: List<Long>,
        preferApiType: ApiType = ApiType.Web,
        resourceType: FavoriteResourceType = FavoriteResourceType.Video
    ) {
        when (preferApiType) {
            ApiType.Web -> BiliHttpApi.setVideoToFavorite(
                avid = aid,
                type = resourceType.value,
                addMediaIds = addMediaIds,
                delMediaIds = delMediaIds,
                sessData = authRepository.sessionData,
                csrf = authRepository.biliJct
            )

            ApiType.App -> BiliHttpApi.setVideoToFavorite(
                avid = aid,
                type = resourceType.value,
                addMediaIds = addMediaIds,
                delMediaIds = delMediaIds,
                accessKey = authRepository.accessToken
            )
        }
    }

    /**
     * 订阅或取消订阅合集[seasonId]，合集订阅由服务端放进默认收藏夹
     */
    suspend fun subscribeSeason(seasonId: Long, subscribe: Boolean) {
        BiliHttpApi.subscribeSeason(
            seasonId = seasonId,
            subscribe = subscribe,
            csrf = authRepository.biliJct.orEmpty(),
            sessData = authRepository.sessionData.orEmpty()
        )
    }

    /**
     * 通过合集中的稿件[avid]查询该合集是否已订阅
     *
     * 稿件互动状态里的 `season_fav` 是服务端给出的合集订阅状态，比按收藏夹反查可靠。
     */
    suspend fun checkSeasonSubscribed(avid: Long): Boolean = try {
        BiliHttpApi.getArchiveRelation(
            avid = avid,
            sessData = authRepository.sessionData
        ).getResponseData().seasonFav
    } catch (exception: CancellationException) {
        throw exception
    } catch (exception: Exception) {
        false
    }

    suspend fun getAllFavoriteFolderMetadataList(
        mid: Long,
        resourceType: FavoriteResourceType = FavoriteResourceType.Video,
        rid: Long? = null,
        preferApiType: ApiType = ApiType.Web
    ): List<FavoriteFolderMetadata> {
        // 42 只在收藏操作接口中定义，收藏夹列表按「全部」查询，避免按内容属性过滤掉收藏夹
        val listType = if (resourceType == FavoriteResourceType.Pgc) 42 else resourceType.value
        val userFavoriteFoldersData = when (preferApiType) {
            ApiType.Web -> BiliHttpApi.getAllFavoriteFoldersInfo(
                mid = mid,
                type = listType,
                rid = rid,
                sessData = authRepository.sessionData ?: ""
            )

            ApiType.App -> BiliHttpApi.getAllFavoriteFoldersInfo(
                mid = mid,
                type = listType,
                rid = rid,
                accessKey = authRepository.accessToken ?: ""
            )
        }.getResponseData()
        return userFavoriteFoldersData.list.map {
            FavoriteFolderMetadata.fromHttpUserFavoriteFolder(it)
        }
    }

    /**
     * 收藏/取消收藏图文[opusId]。
     *
     * 图文收藏必须走 cosmo 的 `simple_action`（网页端同款）。它进的是**独立的「图文收藏」**，
     * 不是用户创建的收藏夹（网页端空间页「其他收藏 › 图文收藏夹」），列表走
     * `/x/polymer/web-dynamic/v1/opus/feed/fav`，收藏夹列表接口查不到它。
     * 旧的收藏夹接口 `/x/v3/fav/resource/deal` 对图文不生效，表现为「UI 显示已收藏、实际没收藏」。
     *
     * 失败时抛出带服务端 message 的异常，方便把原因透出到提示里。
     */
    suspend fun setOpusFavorite(opusId: Long, favorite: Boolean) {
        require(opusId > 0) { "图文 id 无效" }
        val csrf = authRepository.biliJct?.takeIf { it.isNotBlank() }
            ?: error("登录信息缺少 csrf，请重新登录")
        BiliHttpApi.setOpusFavorite(
            opusId = opusId.toString(),
            favorite = favorite,
            csrf = csrf,
            sessData = authRepository.sessionData.orEmpty(),
            dedeUserID = authRepository.mid,
            dedeUserIDCkMd5 = authRepository.dedeUserIDCkMd5
        )
    }

    suspend fun getFavoriteFolderData(
        mediaId: Long,
        pageSize: Int = 20,
        pageNumber: Int = 1,
        preferApiType: ApiType = ApiType.Web
    ): FavoriteFolderData {
        val favoriteFolderListData = when (preferApiType) {
            ApiType.Web -> BiliHttpApi.getFavoriteList(
                mediaId = mediaId,
                pageSize = pageSize,
                pageNumber = pageNumber,
                platform = "web",
                sessData = authRepository.sessionData ?: ""
            )

            ApiType.App -> BiliHttpApi.getFavoriteList(
                mediaId = mediaId,
                pageSize = pageSize,
                pageNumber = pageNumber,
                accessKey = authRepository.accessToken ?: ""
            )
        }.getResponseData()
        return FavoriteFolderData.fromHttpFavoriteFolderInfoListData(favoriteFolderListData)
    }
}
