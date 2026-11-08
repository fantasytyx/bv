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
import dev.aaa1115910.bv.util.GeetestChallengeData
import dev.aaa1115910.bv.util.GeetestSession
import dev.aaa1115910.bv.util.GeetestSubmitResult
import dev.aaa1115910.bv.util.Prefs
import dev.aaa1115910.bv.util.RiskCaptchaSession
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

    /** 触发风控的那次详情加载参数，验证通过后按它重试 */
    private data class DetailRetryRequest(
        val aid: Long,
        val fromPgcSeason: Boolean,
        val withUserActions: Boolean,
    )

    // 风控验证：只发布"待验证凭证"，具体弹什么由屏幕决定（TV = WebView 官方 SDK，移动端 = GT3）
    private val riskCaptcha = RiskCaptchaSession<DetailRetryRequest>()

    /** 待验证的一次性 v_voucher；非 null 时界面应展示风控验证弹窗 */
    val riskCaptchaVoucher: String?
        get() = riskCaptcha.request?.vVoucher

    var errorMessage by mutableStateOf<String?>(null)

    // legacy（v1 register / GT3）只在移动端弹窗惰性申请题目时用到，会话内部保证同一 voucher 只注册一次
    private val geetestSession = GeetestSession(authRepository)

    suspend fun loadDetail(aid: Long, fromPgcSeason: Boolean = false, withUserActions: Boolean = true) {
        // 用户主动打开某个详情：重置风控验证的次数预算
        riskCaptcha.resetBudget()
        loadDetailInternal(aid, fromPgcSeason, withUserActions)
    }

    private suspend fun loadDetailInternal(
        aid: Long,
        fromPgcSeason: Boolean,
        withUserActions: Boolean,
    ) {
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
                handleVVoucher(
                    vVoucher = it.vVoucher,
                    retry = DetailRetryRequest(
                        aid = aid,
                        fromPgcSeason = fromPgcSeason,
                        withUserActions = withUserActions,
                    ),
                )
                return
            }
            errorMessage = it.localizedMessage
            state = VideoInfoState.Error
            logger.fInfo { "Load video av$aid failed: ${it.stackTraceToString()}" }
        }
    }

    /**
     * 被风控：只登记"待验证凭证 + 重试快照"。
     *
     * 用哪种验证方式（WebView 官方风控 SDK / GT3）由屏幕组合的弹窗决定，这里不区分。
     */
    private suspend fun handleVVoucher(
        vVoucher: String,
        retry: DetailRetryRequest,
    ) {
        val started = withContext(Dispatchers.Main) { riskCaptcha.begin(vVoucher, retry) }
        if (!started) {
            // 同一次详情加载里验证通过后仍被风控，说明服务端不接受这个 token；继续弹窗只会死循环
            withContext(Dispatchers.Main) {
                errorMessage = "风控验证未生效，请稍后重试"
                state = VideoInfoState.Error
            }
        }
    }

    /**
     * legacy（GT3 弹窗）的题目申请：拿当前待验证凭证去 v1 register。
     *
     * 只有移动端会调用——TV 端由 WebView 里的官方风控 SDK 自己 register（提前 register 会消耗掉
     * 一次性的 v_voucher）。返回 null 表示无法发起验证，失败文案已写进 [errorMessage]。
     */
    suspend fun requestLegacyGeetest(): GeetestChallengeData? {
        val voucher = riskCaptcha.request?.vVoucher ?: return null
        return withContext(Dispatchers.IO) {
            runCatching { geetestSession.start(voucher) }.getOrElse { error ->
                withContext(Dispatchers.Main) {
                    riskCaptcha.cancel()
                    if (error is VVoucherAlreadyAttemptedException) {
                        logger.fWarn { "Skip duplicated Geetest verification: ${error.message}" }
                        errorMessage = "风控验证申请已提交，请稍后重试"
                    } else {
                        logger.fException(error) { "gaiaVgateRegister failed" }
                        errorMessage = "风控验证申请失败：${error.localizedMessage}"
                    }
                    state = VideoInfoState.Error
                }
                null
            }
        }
    }

    /** legacy（GT3 弹窗）的验证结果：提交成功后与 WebView 通道汇合到 [onRiskCaptchaVerified] */
    fun onGeetestResult(challenge: String, validate: String, seccode: String) {
        if (riskCaptcha.request == null) {
            logger.fWarn { "Ignore legacy geetest result: no pending request" }
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            when (val result = geetestSession.submit(challenge, validate, seccode)) {
                is GeetestSubmitResult.Success -> withContext(Dispatchers.Main) {
                    onRiskCaptchaVerified(result.token)
                }

                is GeetestSubmitResult.StaleChallenge -> withContext(Dispatchers.Main) {
                    // 失败重试后极验会回传另一个 challenge，其 token 已被服务端丢弃，提交必然失败
                    logger.fWarn {
                        "Ignore stale Geetest result: expected=${result.expected.take(8)} actual=${result.actual.take(8)}"
                    }
                    // 静默丢弃会让弹窗停在"正在提交…"且无出路，这里直接给出可见失败反馈
                    riskCaptcha.cancel()
                    geetestSession.clear()
                    errorMessage = "人机验证信息已失效，请重新验证"
                    state = VideoInfoState.Error
                }

                is GeetestSubmitResult.Failure -> {
                    logger.fException(result.cause) { "gaiaVgateValidate failed" }
                    onRiskCaptchaFailed(result.message)
                }
            }
        }
    }

    /**
     * 风控验证成功：两条通道（WebView 官方 SDK / GT3）最终都走这里。
     *
     * @param token 可直接使用的 gaia_vtoken（legacy 流程里就是 grisk_id）
     */
    fun onRiskCaptchaVerified(token: String) {
        val vToken = token.trim()
        if (vToken.isEmpty()) {
            onRiskCaptchaFailed("验证结果为空")
            return
        }
        val retry = riskCaptcha.consume()
        if (retry == null) {
            logger.fWarn { "Ignore risk captcha token: no pending request" }
            return
        }
        geetestSession.clear()
        authRepository.gaiaVtoken = vToken
        viewModelScope.launch(Dispatchers.IO) {
            logger.fInfo { "Risk captcha verified, retrying load detail" }
            // videoDetail 为 null 表示详情尚未成功加载（首次加载即被风控），属于同一会话，应允许重试
            val stillCurrent = withContext(Dispatchers.Main) {
                videoDetail == null || videoDetail?.aid == retry.aid
            }
            if (!stillCurrent) {
                logger.fDebug { "Skip risk captcha detail retry: detail changed" }
                return@launch
            }
            loadDetailInternal(
                aid = retry.aid,
                fromPgcSeason = retry.fromPgcSeason,
                withUserActions = retry.withUserActions,
            )
        }
    }

    /** 风控验证失败/被关闭：清掉待验证状态并给出可见失败 */
    fun onRiskCaptchaFailed(message: String) {
        logger.fWarn { "Risk captcha failed: $message" }
        riskCaptcha.cancel()
        geetestSession.clear()
        viewModelScope.launch(Dispatchers.Main) {
            errorMessage = "风控验证失败：$message"
            state = VideoInfoState.Error
        }
    }

    fun onRiskCaptchaCancelled() {
        riskCaptcha.cancel()
        geetestSession.clear()
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