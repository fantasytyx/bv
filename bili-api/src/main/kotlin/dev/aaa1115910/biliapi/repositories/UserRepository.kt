package dev.aaa1115910.biliapi.repositories

import bilibili.app.dynamic.v2.DynamicGrpcKt
import bilibili.app.dynamic.v2.Refresh
import bilibili.app.dynamic.v2.UserInfo
import bilibili.app.dynamic.v2.dynAllReq
import bilibili.app.dynamic.v2.dynDetailReq
import bilibili.app.dynamic.v2.dynVideoReq
import dev.aaa1115910.biliapi.entity.ApiType
import dev.aaa1115910.biliapi.entity.user.DynamicData
import dev.aaa1115910.biliapi.entity.user.DynamicItem
import dev.aaa1115910.biliapi.entity.user.DynamicVideoData
import dev.aaa1115910.biliapi.entity.user.ArticleViewData
import dev.aaa1115910.biliapi.entity.user.FollowedUser
import dev.aaa1115910.biliapi.entity.user.SpaceArticlePage
import dev.aaa1115910.biliapi.entity.user.SpaceArchivePage
import dev.aaa1115910.biliapi.entity.user.SpaceSeasonSeriesPage
import dev.aaa1115910.biliapi.entity.user.SpaceVideoData
import dev.aaa1115910.biliapi.entity.user.SpaceVideoOrder
import dev.aaa1115910.biliapi.entity.user.SpaceVideoPage
import dev.aaa1115910.biliapi.grpc.utils.handleGrpcException
import dev.aaa1115910.biliapi.http.BiliHttpApi
import dev.aaa1115910.biliapi.http.entity.user.FollowAction
import dev.aaa1115910.biliapi.http.entity.user.FollowActionSource
import dev.aaa1115910.biliapi.http.entity.user.RelationType
import dev.aaa1115910.biliapi.http.entity.user.UserCardData
import dev.aaa1115910.biliapi.http.entity.user.UserInfoData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Single
import kotlin.math.ceil

/**
 * 与网页端空间动态页一致的 features，决定图文以 opus 形态返回、充电专属动态带 blocked 载荷。
 * 少传会被部分 UP 的空间接口当成异常请求，导致整页动态加载失败。
 */
private const val SpaceDynamicFeatures =
    "itemOpusStyle,listOnlyfans,opusBigCover,onlyfansVote,forwardListHidden,decorationCard," +
        "commentsNewVersion,onlyfansAssetsV2,ugcDelete,onlyfansQaCard,avatarAutoTheme,sunflowerStyle," +
        "cardsEnhance,eva3CardOpus,eva3CardVideo,eva3CardComment,eva3CardUser"

@Single
class UserRepository(
    private val authRepository: AuthRepository,
    private val channelRepository: ChannelRepository
) {
    private val dynamicStub
        get() = runCatching {
            DynamicGrpcKt.DynamicCoroutineStub(channelRepository.defaultChannel!!)
        }.getOrNull()

    private suspend fun modifyFollow(
        mid: Long,
        action: FollowAction,
        preferApiType: ApiType = ApiType.Web
    ): Boolean {
        val response = when (preferApiType) {
            ApiType.Web -> {
                BiliHttpApi.modifyFollow(
                    mid = mid,
                    action = action,
                    actionSource = FollowActionSource.Space,
                    csrf = authRepository.biliJct,
                    sessData = authRepository.sessionData
                )
            }

            ApiType.App -> {
                BiliHttpApi.modifyFollow(
                    mid = mid,
                    action = action,
                    actionSource = FollowActionSource.Space,
                    accessKey = authRepository.accessToken
                )
            }
        }
        return response.code == 0
    }

    suspend fun followUser(
        mid: Long,
        preferApiType: ApiType = ApiType.Web
    ): Boolean = modifyFollow(mid, FollowAction.AddFollow, preferApiType)

    suspend fun unfollowUser(
        mid: Long,
        preferApiType: ApiType = ApiType.Web
    ): Boolean = modifyFollow(mid, FollowAction.DelFollow, preferApiType)

    suspend fun checkIsFollowing(
        mid: Long,
        preferApiType: ApiType = ApiType.Web
    ): Boolean? {
        if (authRepository.sessionData == null && authRepository.accessToken == null) return null
        return runCatching {
            val response = when (preferApiType) {
                ApiType.Web -> {
                    BiliHttpApi.getRelations(
                        mid = mid,
                        sessData = authRepository.sessionData
                    )
                }

                ApiType.App -> {
                    BiliHttpApi.getRelations(
                        mid = mid,
                        //移动端貌似并没有使用这个接口，目前该接口返回-663鉴权失败，直接改用sessdata获取
                        sessData = authRepository.sessionData
                        //accessKey = authRepository.accessToken
                    )
                }
            }.getResponseData()
            listOf(
                RelationType.Followed,
                RelationType.FollowedQuietly,
                RelationType.BothFollowed
            ).contains(response.relation.attribute)
        }.onFailure {
            it.printStackTrace()
        }.getOrNull()
    }

    //TODO 改成返回 关注数，粉丝数，黑名单数
    suspend fun getFollowingUpCount(
        mid: Long,
        preferApiType: ApiType
    ): Int {
        if (authRepository.sessionData == null && authRepository.accessToken == null) return 0
        return runCatching {
            val response = when (preferApiType) {
                ApiType.Web -> {
                    BiliHttpApi.getRelationStat(
                        mid = mid,
                        sessData = authRepository.sessionData
                    )
                }

                ApiType.App -> {
                    BiliHttpApi.getRelationStat(
                        mid = mid,
                        accessKey = authRepository.accessToken
                    )
                }
            }.getResponseData()
            response.following
        }.onFailure {
            it.printStackTrace()
        }.getOrNull() ?: 0
    }

    suspend fun addSeasonFollow(
        seasonId: Int,
        preferApiType: ApiType = ApiType.Web
    ): String {
        return when (preferApiType) {
            ApiType.Web -> BiliHttpApi.addSeasonFollow(
                seasonId = seasonId,
                csrf = authRepository.biliJct!!,
                sessData = authRepository.sessionData!!
            )

            ApiType.App -> BiliHttpApi.addSeasonFollow(
                seasonId = seasonId,
                accessKey = authRepository.accessToken!!
            )
        }.getResponseData().toast
    }

    suspend fun delSeasonFollow(
        seasonId: Int,
        preferApiType: ApiType = ApiType.Web
    ): String {
        return when (preferApiType) {
            ApiType.Web -> BiliHttpApi.delSeasonFollow(
                seasonId = seasonId,
                csrf = authRepository.biliJct!!,
                sessData = authRepository.sessionData!!
            )

            ApiType.App -> BiliHttpApi.delSeasonFollow(
                seasonId = seasonId,
                accessKey = authRepository.accessToken!!
            )
        }.getResponseData().toast
    }

    suspend fun getSpaceVideos(
        mid: Long,
        order: SpaceVideoOrder = SpaceVideoOrder.PubDate,
        page: SpaceVideoPage = SpaceVideoPage(),
        preferApiType: ApiType = ApiType.Web
    ): SpaceVideoData {
        return when (preferApiType) {
            ApiType.Web -> {
                val webSpaceVideoData = BiliHttpApi.getWebUserSpaceVideos(
                    mid = mid,
                    order = order.value,
                    pageNumber = page.nextWebPageNumber,
                    pageSize = page.nextWebPageSize,
                    sessData = authRepository.sessionData ?: "",
                    dedeUserID = authRepository.mid
                ).getResponseData()
                SpaceVideoData.fromWebSpaceVideoData(webSpaceVideoData)
            }

            ApiType.App -> {
                val appSpaceVideoData = BiliHttpApi.getAppUserSpaceVideos(
                    mid = mid,
                    lastAvid = page.lastAvid,
                    order = order.value,
                    ts = System.currentTimeMillis(),
                    accessKey = authRepository.accessToken ?: ""
                ).getResponseData()
                SpaceVideoData.fromAppSpaceVideoData(appSpaceVideoData)
            }
        }
    }

    suspend fun getDynamicVideos(
        page: Int,
        offset: String,
        updateBaseline: String,
        preferApiType: ApiType = ApiType.Web
    ): DynamicVideoData {
        return when (preferApiType) {
            ApiType.Web -> {
                val responseData = BiliHttpApi.getDynamicList(
                    type = "video",
                    page = page,
                    offset = offset,
                    sessData = authRepository.sessionData ?: ""
                ).getResponseData()
                DynamicVideoData.fromDynamicData(responseData)
            }

            ApiType.App -> {
                var result: DynamicVideoData? = null
                runCatching {
                    val dynVideoReply = dynamicStub?.dynVideo(dynVideoReq {
                        this.page = page
                        this.offset = offset
                        this.updateBaseline = updateBaseline
                        localTime = 8
                        refreshType =
                            if (offset == "") Refresh.refresh_new else Refresh.refresh_history
                    })
                    result = DynamicVideoData.fromDynamicData(dynVideoReply!!)
                }.onFailure {
                    handleGrpcException(it)
                }
                result!!
            }
        }
    }

    suspend fun getDynamics(
        page: Int,
        offset: String,
        updateBaseline: String,
        preferApiType: ApiType = ApiType.Web
    ): DynamicData {
        return when (preferApiType) {
            ApiType.Web -> {
                val responseData = BiliHttpApi.getDynamicList(
                    type = "all",
                    page = page,
                    offset = offset,
                    sessData = authRepository.sessionData ?: ""
                ).getResponseData()
                DynamicData.fromDynamicData(responseData)
            }

            ApiType.App -> {
                var result: DynamicData? = null
                runCatching {
                    val dynAllReply = dynamicStub?.dynAll(dynAllReq {
                        this.page = page
                        this.offset = offset
                        this.updateBaseline = updateBaseline
                        localTime = 8
                        refreshType =
                            if (offset == "") Refresh.refresh_new else Refresh.refresh_history
                    })
                    result = DynamicData.fromDynamicData(dynAllReply!!)
                }.onFailure {
                    handleGrpcException(it)
                }
                result!!
            }
        }
    }

    suspend fun getDynamicsByUp(
        mid: Long,
        offset: String
    ): DynamicData {
        val responseData = BiliHttpApi.getUpSpaceDynamicList(
            mid = mid,
            offset = offset,
            sessData = authRepository.sessionData ?: "",
            dedeUserID = authRepository.mid,
            dedeUserIDCkMd5 = authRepository.dedeUserIDCkMd5,
            features = SpaceDynamicFeatures
        ).getResponseData()
        return DynamicData.fromDynamicData(responseData)
    }

    suspend fun getSpaceArticles(
        mid: Long,
        page: Int,
        offset: String = ""
    ): SpaceArticlePage {
        val responseData = BiliHttpApi.getWebUserSpaceArticles(
            mid = mid,
            pageNumber = page,
            offset = offset,
            sessData = authRepository.sessionData ?: "",
            dedeUserID = authRepository.mid,
            dedeUserIDCkMd5 = authRepository.dedeUserIDCkMd5
        ).getResponseData()
        return SpaceArticlePage.fromWebSpaceArticleData(responseData)
    }

    /**
     * 图文详情。
     *
     * 只请求 opus 详情一个接口：正文/图片、附带视频、互动数与评论 id 都在里面。
     * 不能用动态详情（`/web-dynamic/v1/detail`）代替——那个接口的 `module_stat` 没有
     * `favorite`，收藏数与「我已收藏」会一直是 0。
     */
    suspend fun getSpaceArticle(id: Long): ArticleViewData {
        val responseData = BiliHttpApi.getOpusDetail(
            opusId = id.toString(),
            sessData = authRepository.sessionData ?: "",
            dedeUserID = authRepository.mid,
            dedeUserIDCkMd5 = authRepository.dedeUserIDCkMd5
        ).getResponseData()
        return ArticleViewData.fromOpusDetail(responseData)
    }

    suspend fun getSpaceSeasonSeries(
        mid: Long,
        page: Int,
        pageSize: Int = 18
    ): SpaceSeasonSeriesPage {
        val responseData = BiliHttpApi.getSpaceSeasonSeries(
            mid = mid,
            pageNumber = page,
            pageSize = pageSize,
            sessData = authRepository.sessionData ?: "",
            dedeUserID = authRepository.mid,
            dedeUserIDCkMd5 = authRepository.dedeUserIDCkMd5
        ).getResponseData()
        return SpaceSeasonSeriesPage.fromWebData(responseData)
    }

    suspend fun getSeasonArchives(
        mid: Long,
        seasonId: Long,
        page: Int,
        pageSize: Int = 30
    ): SpaceArchivePage {
        val responseData = BiliHttpApi.getSeasonArchives(
            mid = mid,
            seasonId = seasonId,
            pageNumber = page,
            pageSize = pageSize,
            sessData = authRepository.sessionData ?: "",
            dedeUserID = authRepository.mid,
            dedeUserIDCkMd5 = authRepository.dedeUserIDCkMd5
        ).getResponseData()
        return SpaceArchivePage.fromSeasonArchivesData(responseData, mid)
    }

    suspend fun getSeriesArchives(
        mid: Long,
        seriesId: Long,
        page: Int,
        pageSize: Int = 30
    ): SpaceArchivePage {
        val responseData = BiliHttpApi.getSeriesArchives(
            mid = mid,
            currentMid = authRepository.mid ?: 0L,
            seriesId = seriesId,
            pageNumber = page,
            pageSize = pageSize,
            sessData = authRepository.sessionData ?: "",
            dedeUserID = authRepository.mid,
            dedeUserIDCkMd5 = authRepository.dedeUserIDCkMd5
        ).getResponseData()
        return SpaceArchivePage.fromSeriesArchivesData(responseData, mid)
    }

    suspend fun getDynamicDetail(
        dynamicId: String,
        preferApiType: ApiType = ApiType.Web
    ): DynamicItem {
        return when (preferApiType) {
            ApiType.Web -> {
                val responseData = BiliHttpApi.getDynamicDetail(
                    id = dynamicId,
                    features = "itemOpusStyle",
                    sessData = authRepository.sessionData ?: ""
                ).getResponseData()
                DynamicItem.fromDynamicItem(responseData.item)
            }

            ApiType.App -> {
                var result: DynamicItem? = null
                runCatching {
                    val dynDetailReply = dynamicStub?.dynDetail(dynDetailReq {
                        this.dynamicId = dynamicId
                        localTime = 8
                    })
                    result = DynamicItem.fromDynamicItem(dynDetailReply!!.item)
                }.onFailure {
                    handleGrpcException(it)
                }
                result!!
            }
        }
    }

    suspend fun getFollowedUsers(
        mid: Long,
        preferApiType: ApiType = ApiType.Web
    ): List<FollowedUser> {
        return when (preferApiType) {
            ApiType.Web -> {
                val result = mutableListOf<FollowedUser>()
                val firstResponse = BiliHttpApi.getUserFollow(
                    mid = mid,
                    sessData = authRepository.sessionData!!
                ).getResponseData()
                val userCount = firstResponse.total
                val pageCount = ceil((userCount.toFloat() / 50)).toInt()
                result.addAll(firstResponse.list.map { FollowedUser.fromHttpFollowedUser(it) })
                withContext(Dispatchers.IO) {
                    (2..pageCount).map { pageNumber ->
                        async {
                            BiliHttpApi.getUserFollow(
                                mid = mid,
                                pageNumber = pageNumber,
                                sessData = authRepository.sessionData!!
                            ).getResponseData()
                        }
                    }.awaitAll().forEach { userFollowData ->
                        result.addAll(userFollowData.list.map { FollowedUser.fromHttpFollowedUser(it) })
                    }
                }
                result
            }

            ApiType.App -> {
                val result = mutableListOf<FollowedUser>()
                val firstResponse = BiliHttpApi.getUserFollow(
                    mid = mid,
                    accessKey = authRepository.accessToken!!
                ).getResponseData()
                val userCount = firstResponse.total
                val pageCount = ceil((userCount.toFloat() / 50)).toInt()
                result.addAll(firstResponse.list.map { FollowedUser.fromHttpFollowedUser(it) })
                withContext(Dispatchers.IO) {
                    (2..pageCount).map { pageNumber ->
                        async {
                            BiliHttpApi.getUserFollow(
                                mid = mid,
                                pageNumber = pageNumber,
                                accessKey = authRepository.accessToken!!
                            ).getResponseData()
                        }
                    }.awaitAll().forEach { userFollowData ->
                        result.addAll(userFollowData.list.map { FollowedUser.fromHttpFollowedUser(it) })
                    }
                }
                result
            }
        }
    }
    
    suspend fun getUserInfo(mid: Long): UserInfoData {
        val response = BiliHttpApi.getUserInfo(
            uid = mid,
            sessData = authRepository.sessionData ?: ""
        ).getResponseData()
        return response
    }

    suspend fun getUserCardInfo(mid: Long): UserCardData {
        val response = BiliHttpApi.getUserCardInfo(
            mid = mid,
            sessData = authRepository.sessionData ?: ""
        ).getResponseData()
        return response
    }
}