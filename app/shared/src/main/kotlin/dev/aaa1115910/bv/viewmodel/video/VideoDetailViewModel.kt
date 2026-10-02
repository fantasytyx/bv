package dev.aaa1115910.bv.viewmodel.video

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.aaa1115910.biliapi.entity.video.VideoDetail
import dev.aaa1115910.biliapi.http.entity.VVoucherException
import dev.aaa1115910.biliapi.repositories.AuthRepository
import dev.aaa1115910.biliapi.repositories.VideoDetailRepository
import dev.aaa1115910.bv.entity.carddata.VideoCardData
import dev.aaa1115910.bv.player.entity.VideoListItem
import dev.aaa1115910.bv.player.entity.VideoListInteractiveNode
import dev.aaa1115910.bv.player.entity.VideoListPart
import dev.aaa1115910.bv.player.entity.VideoListUgcEpisode
import dev.aaa1115910.bv.player.entity.VideoListUgcEpisodeTitle
import dev.aaa1115910.bv.repository.VideoInfoRepository
import dev.aaa1115910.bv.util.GeetestSession
import dev.aaa1115910.bv.util.GeetestSubmitResult
import dev.aaa1115910.bv.util.Prefs
import dev.aaa1115910.bv.util.VVoucherAlreadyAttemptedException
import dev.aaa1115910.bv.util.fDebug
import dev.aaa1115910.bv.util.fException
import dev.aaa1115910.bv.util.fInfo
import dev.aaa1115910.bv.util.fWarn
import dev.aaa1115910.bv.util.swapListWithMainContext
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.annotation.KoinViewModel

@KoinViewModel
class VideoDetailViewModel(
    private val videoDetailRepository: VideoDetailRepository,
    private val videoInfoRepository: VideoInfoRepository,
    private val authRepository: AuthRepository
) : ViewModel() {
    private val logger = KotlinLogging.logger { }
    var state by mutableStateOf(VideoInfoState.Loading)
    var videoDetail: VideoDetail? by mutableStateOf(null)

    var relatedVideos = mutableStateListOf<VideoCardData>()

    // 风控 Geetest 验证状态
    var showGeetestDialog by mutableStateOf(false)
    var geetestGt by mutableStateOf("")
    var geetestChallenge by mutableStateOf("")
    var errorMessage by mutableStateOf<String?>(null)
    private var pendingRetryAid = 0L
    private var pendingRetryFromPgcSeason = false
    private var pendingRetryWithUserActions = true
    // v_voucher 是一次性凭证，会话内部保证同一个 voucher 只注册一次，避免重复弹窗死循环
    private val geetestSession = GeetestSession(authRepository)

    suspend fun loadDetail(aid: Long, fromPgcSeason: Boolean = false, withUserActions: Boolean = true) {
        logger.fInfo { "Load detail: [avid=$aid, preferApiType=${Prefs.apiType.name}]" }
        state = VideoInfoState.Loading
        errorMessage = null
        runCatching {
            val videoDetailData = videoDetailRepository.getVideoDetail(
                aid = aid,
                preferApiType = Prefs.apiType,
                withUserActions = withUserActions
            )
            withContext(Dispatchers.Main) { videoDetail = videoDetailData }
            if (!fromPgcSeason) updateVideoList(aid)

            updateRelatedVideos()
            state = VideoInfoState.Success
            logger.fInfo { "Load video av$aid success" }
        }.onFailure {
            if (it is VVoucherException) {
                logger.fWarn { "Risk control v_voucher detected: ${it.vVoucher}" }
                handleVVoucher(it.vVoucher, aid, fromPgcSeason, withUserActions)
                return
            }
            errorMessage = it.localizedMessage
            state = VideoInfoState.Error
            logger.fInfo { "Load video av$aid failed: ${it.stackTraceToString()}" }
        }
    }

    private suspend fun handleVVoucher(
        vVoucher: String,
        aid: Long,
        fromPgcSeason: Boolean,
        withUserActions: Boolean,
    ) {
        runCatching {
            val challenge = geetestSession.start(vVoucher)
            withContext(Dispatchers.Main) {
                pendingRetryAid = aid
                pendingRetryFromPgcSeason = fromPgcSeason
                pendingRetryWithUserActions = withUserActions
                geetestGt = challenge.gt
                geetestChallenge = challenge.challenge
                showGeetestDialog = true
            }
        }.onFailure {
            if (it is VVoucherAlreadyAttemptedException) {
                logger.fWarn { "Skip duplicated Geetest verification: ${it.message}" }
                errorMessage = "风控验证申请已提交，请稍后重试"
                state = VideoInfoState.Error
                return@onFailure
            }
            logger.fException(it) { "gaiaVgateRegister failed" }
            errorMessage = "风控验证申请失败：${it.localizedMessage}"
            state = VideoInfoState.Error
        }
    }

    fun onGeetestResult(challenge: String, validate: String, seccode: String) {
        val retryAid = pendingRetryAid
        val retryFromPgcSeason = pendingRetryFromPgcSeason
        val retryWithUserActions = pendingRetryWithUserActions
        if (retryAid <= 0L) return
        viewModelScope.launch(Dispatchers.IO) {
            when (val result = geetestSession.submit(challenge, validate, seccode)) {
                is GeetestSubmitResult.StaleChallenge -> withContext(Dispatchers.Main) {
                    // 失败重试后极验会回传另一个 challenge，其 token 已被服务端丢弃，提交必然失败
                    logger.fWarn {
                        "Ignore stale Geetest result: expected=${result.expected.take(8)} actual=${result.actual.take(8)}"
                    }
                    // 静默丢弃会让弹窗停在"正在提交…"且无出路，这里直接给出可见失败反馈
                    clearGeetestState()
                    errorMessage = "人机验证信息已失效，请重新验证"
                    state = VideoInfoState.Error
                }

                is GeetestSubmitResult.Failure -> withContext(Dispatchers.Main) {
                    logger.fException(result.cause) { "gaiaVgateValidate failed" }
                    clearGeetestState()
                    errorMessage = "风控验证失败：${result.message}"
                    state = VideoInfoState.Error
                }

                GeetestSubmitResult.Success -> {
                    withContext(Dispatchers.Main) { clearGeetestState() }
                    // videoDetail 为 null 表示详情尚未成功加载（首次加载即被风控），属于同一会话，应允许重试
                    val stillCurrent = withContext(Dispatchers.Main) {
                        videoDetail == null || videoDetail?.aid == retryAid
                    }
                    if (!stillCurrent) {
                        logger.fDebug { "Skip Geetest detail retry: detail changed" }
                        return@launch
                    }
                    logger.fInfo { "Gaia vgate validate success, retrying load detail" }
                    loadDetail(
                        aid = retryAid,
                        fromPgcSeason = retryFromPgcSeason,
                        withUserActions = retryWithUserActions,
                    )
                }
            }
        }
    }

    private fun clearGeetestState() {
        showGeetestDialog = false
        pendingRetryAid = 0L
        geetestSession.clear()
    }

    fun onGeetestCancelled() {
        clearGeetestState()
        if (videoDetail == null) {
            errorMessage = "验证已取消"
            state = VideoInfoState.Error
        }
    }

    suspend fun loadDetailOnlyUpdateHistory(aid: Long) {
        logger.fInfo { "Load detail only update history: [avid=$aid, preferApiType=${Prefs.apiType.name}]" }
        runCatching {
            val historyData = videoDetailRepository.getVideoDetail(
                aid = aid,
                preferApiType = Prefs.apiType,
                withUserActions = false
            ).history
            withContext(Dispatchers.Main) { videoDetail?.history = historyData }
        }.onFailure {
            logger.fInfo { "Load video av$aid only update history failed: ${it.stackTraceToString()}" }
        }.onSuccess {
            logger.fInfo { "Load video av$aid only update history success: ${videoDetail?.history}" }
        }
    }

    private suspend fun updateRelatedVideos() {
        logger.fInfo { "Start update relate video" }
        val relateVideoCardDataList = videoDetail?.relatedVideos?.map {
            VideoCardData(
                avid = it.aid,
                title = it.title,
                cover = it.cover,
                upName = it.author?.name ?: "",
                time = it.duration * 1000L,
                play = it.view,
                danmaku = it.danmaku,
                jumpToSeason = it.jumpToSeason,
                epId = it.epid,
                pubTime = it.pubTime,
                upId = it.author?.mid ?: 0,
                upFace = it.author?.face ?: "",
                isChargingArc = it.isChargingArchive
            )
        } ?: emptyList()
        relatedVideos.swapListWithMainContext(relateVideoCardDataList)
        logger.fInfo { "Update ${relateVideoCardDataList.size} relate videos" }
    }

    private fun syncInteractivePlaybackContext() {
        val detail = videoDetail
        if (
            detail != null &&
            detail.isInteractive &&
            detail.bvid.isNotBlank() &&
            detail.interactiveGraphVersion != null
        ) {
            videoInfoRepository.updateInteractivePlaybackContext(
                bvid = detail.bvid,
                graphVersion = detail.interactiveGraphVersion,
            )
        } else {
            videoInfoRepository.clearInteractivePlaybackContext()
        }
    }

    private fun updateVideoList(aid: Long) {
        syncInteractivePlaybackContext()
        if (videoDetail?.interactiveNodes?.isNotEmpty() == true) {
            val interactiveVideoList = videoDetail!!.interactiveNodes.mapIndexed { index, node ->
                VideoListInteractiveNode(
                    aid = aid,
                    cid = node.cid,
                    title = videoDetail!!.title,
                    partTitle = node.title,
                    index = index,
                    nodeId = node.nodeId,
                    edgeId = node.edgeId,
                    startPos = node.startPos,
                    isCurrent = node.isCurrent,
                )
            }
            videoInfoRepository.videoList.clear()
            videoInfoRepository.videoList.addAll(interactiveVideoList)
        } else if (videoDetail?.ugcSeason != null) {
            updateUgcSeasonSectionVideoList(0)
        } else {
            val partVideoList =
                videoDetail!!.pages.mapIndexed { index, videoPage ->
                    VideoListPart(
                        aid = aid,
                        cid = videoPage.cid,
                        title = videoDetail!!.title,
                        partTitle = videoPage.title,
                        index = index,
                        cover = videoDetail!!.cover,
                        duration = videoPage.duration,
                    )
                }
            videoInfoRepository.videoList.clear()
            videoInfoRepository.videoList.addAll(partVideoList)
        }
    }

    fun updateUgcSeasonSectionVideoList(sectionIndex: Int) {
        val partVideoList = mutableListOf<VideoListItem>()
        videoDetail!!.ugcSeason!!.sections[sectionIndex].episodes.mapIndexed { epIndex, episode ->
            if (episode.pages.size == 1) {
                episode.pages.mapIndexed { pageInd, videoPage ->
                    partVideoList.add(
                        VideoListUgcEpisode(
                            aid = episode.aid,
                            cid = videoPage.cid,
                            title = episode.title,
                            partTitle = "",
                            index = epIndex,
                            cover = episode.cover,
                            duration = episode.duration,
                            pubDate = episode.pubDate,
                        )
                    )
                }
            } else {
                partVideoList.add(
                    VideoListUgcEpisodeTitle(
                        title = episode.title,
                        index = epIndex,
                    )
                )
                episode.pages.mapIndexed { pageIndex, videoPage ->
                    partVideoList.add(
                        VideoListPart(
                            aid = episode.aid,
                            cid = videoPage.cid,
                            title = episode.title,
                            partTitle = videoPage.title,
                            index = pageIndex,
                            cover = episode.cover,
                            duration = videoPage.duration,
                            pubDate = episode.pubDate,
                        )
                    )
                }
            }
        }
        videoInfoRepository.videoList.clear()
        videoInfoRepository.videoList.addAll(partVideoList)
    }
}

enum class VideoInfoState {
    Loading,
    Success,
    Error
}