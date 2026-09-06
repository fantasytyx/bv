package dev.aaa1115910.biliapi.repositories

import bilibili.app.view.v1.ViewGrpcKt
import bilibili.app.view.v1.viewReq
import bilibili.main.community.reply.v1.Mode
import bilibili.main.community.reply.v1.ReplyGrpcKt
import bilibili.main.community.reply.v1.detailListReq
import bilibili.main.community.reply.v1.mainListReq
import bilibili.pagination.feedPagination
import dev.aaa1115910.biliapi.entity.ApiType
import dev.aaa1115910.biliapi.entity.reply.CommentPage
import dev.aaa1115910.biliapi.entity.reply.CommentRepliesData
import dev.aaa1115910.biliapi.entity.reply.CommentReplyPage
import dev.aaa1115910.biliapi.entity.reply.CommentSort
import dev.aaa1115910.biliapi.entity.reply.CommentsData
import dev.aaa1115910.biliapi.entity.video.InteractiveNode
import dev.aaa1115910.biliapi.entity.video.VideoDetail
import dev.aaa1115910.biliapi.entity.video.season.SeasonDetail
import dev.aaa1115910.biliapi.grpc.utils.handleGrpcException
import dev.aaa1115910.biliapi.http.BiliHttpApi
import dev.aaa1115910.biliapi.http.entity.user.garb.EquipPart
import dev.aaa1115910.biliapi.util.AvBvConverter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.koin.core.annotation.Single
import java.util.concurrent.ConcurrentHashMap

@Single
class VideoDetailRepository(
    private val authRepository: AuthRepository,
    private val channelRepository: ChannelRepository,
    private val favoriteRepository: FavoriteRepository,
    private val likeRepository: LikeRepository,
    private val coinRepository: CoinRepository
) {
    private val viewStub
        get() = runCatching {
            ViewGrpcKt.ViewCoroutineStub(channelRepository.defaultChannel!!)
        }.getOrNull()
    private val replyStub
        get() = runCatching {
            ReplyGrpcKt.ReplyCoroutineStub(channelRepository.defaultChannel!!)
        }.getOrNull()

    /**
     * Cache for user action status (like/favorite/coin), keyed by aid.
     * Populated by [ensureStateLoaded] (via [setCachedUserActions]) or the first call
     * to [getVideoDetail] with [withUserActions]=true. Subsequent consumers read from
     * cache, avoiding duplicate network requests.
     */
    private val userActionsCache = ConcurrentHashMap<Long, Triple<Boolean, Boolean, Boolean>>()

    /**
     * Cache user action results that were already fetched externally (e.g. by [VideoUserActionManager]).
     * Subsequent [getVideoDetail] calls for the same [aid] will read from cache instead of re-fetching.
     */
    fun setCachedUserActions(aid: Long, liked: Boolean, favored: Boolean, coined: Boolean) {
        userActionsCache.putIfAbsent(aid, Triple(liked, favored, coined))
    }

    private suspend fun fillInteractiveInfo(
        videoDetail: VideoDetail,
        knownInteractive: Boolean? = null
    ) {
        val sessData = authRepository.sessionData.orEmpty()
        val isInteractive = knownInteractive ?: runCatching {
            BiliHttpApi.getVideoInfo(
                bv = videoDetail.bvid,
                sessData = sessData.ifBlank { null }
            ).getResponseData().let {
                it.rights.isSteinGate == 1
            }
        }.onFailure {
            println("Get interactive flag failed: $it")
        }.getOrDefault(false)

        videoDetail.isInteractive = isInteractive
        if (!isInteractive) {
            videoDetail.interactiveGraphVersion = null
            videoDetail.interactiveNodes = emptyList()
            return
        }

        val graphVersion = runCatching {
            BiliHttpApi.getVideoPlayerInfo(
                bv = videoDetail.bvid,
                cid = videoDetail.cid,
                sessData = sessData.ifBlank { null }
            ).getResponseData().interaction?.graphVersion
        }.onFailure {
            println("Get interactive graph version failed: $it")
        }.getOrNull()

        videoDetail.interactiveGraphVersion = graphVersion
        if (graphVersion == null) {
            videoDetail.interactiveNodes = emptyList()
            return
        }

        val interactiveNodes = runCatching {
            BiliHttpApi.getInteractiveEdgeInfo(
                bvid = videoDetail.bvid,
                graphVersion = graphVersion,
                sessData = sessData.ifBlank { null }
            ).getResponseData().storyList.map(InteractiveNode::fromStoryNode)
        }.onFailure {
            println("Get interactive edge info failed: $it")
        }.getOrDefault(emptyList())

        videoDetail.interactiveNodes = interactiveNodes
    }

    suspend fun getVideoDetail(
        aid: Long,
        preferApiType: ApiType = ApiType.Web,
        withUserActions: Boolean = true
    ): VideoDetail {
        return when (preferApiType) {
            ApiType.Web -> {
                withContext(Dispatchers.IO) {
                    // 串行执行：获取视频详情
                    val videoDetailWithoutUserActions = run {
                        val httpVideoDetail = BiliHttpApi.getVideoDetail(
                            // 使用 bvid 发起请求，相比 aid 更贴近 web 端行为，降低被风控的概率
                            bv = AvBvConverter.av2bv(aid),
                            sessData = authRepository.sessionData ?: "",
                            gaiaVtoken = authRepository.gaiaVtoken
                        ).getResponseData()
                        VideoDetail.fromVideoDetail(httpVideoDetail)
                    }

                    // 声明变量
                    var isLiked = false
                    var isFavoured = false
                    var isCoined = false

                    if (withUserActions) {
                        // 未登录时跳过点赞/收藏/投币状态请求，减少对风控接口的触发
                        val hasWebSession = !authRepository.sessionData.isNullOrBlank()
                        val cached = if (hasWebSession) {
                            // 从缓存读取（缓存由 ensureStateLoaded 或本方法的首次调用填充）
                            userActionsCache.getOrPut(aid) {
                                Triple(
                                    runCatching { likeRepository.checkVideoLike(aid, preferApiType = ApiType.Web) }
                                        .onFailure { println("Check video liked failed: $it") }
                                        .getOrDefault(false),
                                    runCatching { favoriteRepository.checkVideoFavoured(aid, preferApiType = ApiType.Web) }
                                        .onFailure { println("Check video favoured failed: $it") }
                                        .getOrDefault(false),
                                    runCatching { coinRepository.checkVideoCoin(aid, preferApiType = ApiType.Web) }
                                        .onFailure { println("Check video coin failed: $it") }
                                        .getOrDefault(false)
                                )
                            }
                        } else {
                            Triple(false, false, false)
                        }
                        isLiked = cached.first
                        isFavoured = cached.second
                        isCoined = cached.third
                    }

                    // 串行执行：获取历史和播放器图标
                    val (history, playerIcon) = runCatching {
                        val videoModeInfo = BiliHttpApi.getVideoMoreInfo(
                            bvid = AvBvConverter.av2bv(aid),
                            cid = videoDetailWithoutUserActions.cid,
                            sessData = authRepository.sessionData ?: "",
                            buvid3 = authRepository.buvid3 ?: ""
                        ).getResponseData()
                        val history = VideoDetail.History(
                            progress = videoModeInfo.lastPlayTime / 1000,
                            lastPlayedCid = videoModeInfo.lastPlayCid
                        )
                        val playerIcon =
                            VideoDetail.PlayerIcon.fromPlayerIcon(videoModeInfo.playerIcon)
                        history to playerIcon
                    }.onFailure {
                        println("Get video history failed: $it")
                    }.getOrDefault(VideoDetail.History(0, 0) to null)

                    // 更新并返回结果
                    videoDetailWithoutUserActions.apply {
                        userActions.like = isLiked
                        userActions.coin = isCoined
                        userActions.favorite = isFavoured
                        this.history = history
                        this.playerIcon = playerIcon
                        fillInteractiveInfo(this, knownInteractive = this.isInteractive)
                    }
                }
            }

            ApiType.App -> {
                val viewReply = runCatching {
                    viewStub?.view(viewReq {
                        this.aid = aid
                    }) ?: throw IllegalStateException("Player stub is not initialized")
                }.onFailure { handleGrpcException(it) }.getOrThrow()
                VideoDetail.fromViewReply(viewReply).apply {
                    fillInteractiveInfo(this)
                    if (playerIcon?.idle?.isBlank() != false && authRepository.sessionData != null) {
                        println("player icon not found in view reply, try to get it from garb api")
                        runCatching {
                            val playerIconGarb = BiliHttpApi.getUserEquippedGarb(
                                part = EquipPart.PlayerIcon,
                                sessData = authRepository.sessionData!!
                            ).getResponseData()
                            val playerIconItem = playerIconGarb.item
                                ?: throw IllegalStateException("player icon not equipped")
                            this.playerIcon = VideoDetail.PlayerIcon(
                                idle = playerIconItem.properties.icon ?: "",
                                moving = playerIconItem.properties.dragIcon ?: ""
                            )
                        }.onFailure {
                            println("Get player icon failed: $it")
                        }
                    }
                }
            }
        }
    }

    suspend fun getPgcVideoDetail(
        epid: Int? = null,
        seasonId: Int? = null,
        preferApiType: ApiType = ApiType.Web
    ): SeasonDetail {
        when (preferApiType) {
            ApiType.Web -> {
                val webSeasonData = BiliHttpApi.getWebSeasonInfo(
                    epId = epid,
                    seasonId = seasonId,
                    sessData = authRepository.sessionData ?: ""
                ).getResponseData()
                webSeasonData.userStatus = BiliHttpApi.getSeasonUserStatus(
                    seasonId = seasonId!!,
                    sessData = authRepository.sessionData ?: ""
                ).getResponseData()
                val seasonDetail = SeasonDetail.fromSeasonData(webSeasonData)
                val firstEp = webSeasonData.episodes.firstOrNull() ?: return seasonDetail

                val playerIcon = runCatching {
                    val videoModeInfo = BiliHttpApi.getVideoMoreInfo(
                        bvid = firstEp.bvid,
                        cid = firstEp.cid,
                        sessData = authRepository.sessionData ?: "",
                        buvid3 = authRepository.buvid3 ?: ""
                    ).getResponseData()
                    val playerIcon = VideoDetail.PlayerIcon.fromPlayerIcon(videoModeInfo.playerIcon)
                    playerIcon
                }.onFailure {
                    println("Get video player icon failed: $it")
                }.getOrDefault(null)
                seasonDetail.playerIcon = playerIcon
                return seasonDetail
            }

            ApiType.App -> {
                val appSeasonData = BiliHttpApi.getAppSeasonInfo(
                    epId = epid,
                    seasonId = seasonId,
                    mobiApp = "android_hd",
                    accessKey = authRepository.accessToken ?: ""
                ).getResponseData()
                return SeasonDetail.fromSeasonData(appSeasonData)
            }
        }
    }

    suspend fun getComments(
        aid: Long,
        sort: CommentSort = CommentSort.Hot,
        page: CommentPage = CommentPage(),
        preferApiType: ApiType = ApiType.Web
    ): CommentsData {
        when (preferApiType) {
            ApiType.Web -> {
                val webComments = BiliHttpApi.getComments(
                    oid = aid,
                    type = 1,
                    mode = sort.param,
                    paginationStr = Json.encodeToString(mapOf("offset" to page.nextWebPage)),
                    sessData = authRepository.sessionData ?: "",
                    dedeUserID = authRepository.mid,
                    buvid3 = authRepository.buvid3 ?: ""
                ).getResponseData()
                return CommentsData.fromCommentData(webComments)
            }

            ApiType.App -> {
                val appComments = replyStub?.mainList(
                    mainListReq {
                        oid = aid
                        type = 1
                        /*cursor = cursorReq {
                            next = page.nextAppPage.toLong()
                            mode = when (sort) {
                                CommentSort.Hot -> Mode.MAIN_LIST_HOT
                                CommentSort.HotAndTime -> Mode.DEFAULT
                                CommentSort.Time -> Mode.MAIN_LIST_TIME
                            }
                        }*/
                        mode = when (sort) {
                            CommentSort.Hot -> Mode.MAIN_LIST_HOT
                            CommentSort.HotAndTime -> Mode.DEFAULT
                            CommentSort.Time -> Mode.MAIN_LIST_TIME
                        }
                        pagination = feedPagination {
                            offset = page.nextAppPage
                        }
                    }
                ) ?: throw IllegalStateException("Reply stub is not initialized")
                return CommentsData.fromMainListReply(appComments)
            }
        }
    }

    suspend fun getCommentReplies(
        aid: Long,
        commentId: Long,
        page: CommentReplyPage = CommentReplyPage(),
        sort: CommentSort = CommentSort.Hot,
        preferApiType: ApiType = ApiType.Web
    ): CommentRepliesData {
        when (preferApiType) {
            ApiType.Web -> {
                val webReplies = BiliHttpApi.getCommentReplies(
                    oid = aid,
                    type = 1,
                    root = commentId,
                    pageNumber = page.nextWebPage,
                    sessData = authRepository.sessionData ?: "",
                    dedeUserID = authRepository.mid,
                    buvid3 = authRepository.buvid3 ?: ""
                ).getResponseData()
                return CommentRepliesData.fromCommentReplyData(webReplies)
            }

            ApiType.App -> {
                val appReplies = replyStub?.detailList(
                    detailListReq {
                        oid = aid
                        type = 1
                        root = commentId
                        /*cursor = cursorReq {
                            next = page.nextAppPage.toLong()
                        }*/
                        mode = when (sort) {
                            CommentSort.Hot -> Mode.MAIN_LIST_HOT
                            CommentSort.HotAndTime -> Mode.DEFAULT
                            CommentSort.Time -> Mode.MAIN_LIST_TIME
                        }
                        pagination = feedPagination {
                            offset = page.nextAppPage
                        }
                    }
                ) ?: throw IllegalStateException("Reply stub is not initialized")
                return CommentRepliesData.fromCommentReplyList(appReplies)
            }
        }
    }
}
