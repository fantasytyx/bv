package dev.aaa1115910.biliapi.repositories

import dev.aaa1115910.biliapi.entity.ApiType
import dev.aaa1115910.biliapi.entity.FavoriteFolderData
import dev.aaa1115910.biliapi.entity.FavoriteFolderMetadata
import dev.aaa1115910.biliapi.entity.FavoriteResourceType
import dev.aaa1115910.biliapi.http.BiliHttpApi
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
