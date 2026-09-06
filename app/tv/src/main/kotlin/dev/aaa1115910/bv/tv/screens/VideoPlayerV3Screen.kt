package dev.aaa1115910.bv.tv.screens

import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.aaa1115910.bv.tv.activities.video.SeasonInfoActivity
import dev.aaa1115910.bv.tv.activities.video.VideoInfoActivity
import dev.aaa1115910.bv.entity.proxy.ProxyArea
import dev.aaa1115910.bv.player.entity.LocalVideoPlayerConfigData
import dev.aaa1115910.bv.player.danmaku.DanmakuView
import dev.aaa1115910.bv.player.entity.LocalVideoPlayerDanmakuMasksData
import dev.aaa1115910.bv.player.entity.LocalVideoPlayerHistoryData
import dev.aaa1115910.bv.player.entity.LocalVideoPlayerLoadStateData
import dev.aaa1115910.bv.player.entity.LocalVideoPlayerPaymentData
import dev.aaa1115910.bv.player.entity.LocalVideoPlayerSeekThumbData
import dev.aaa1115910.bv.player.entity.LocalVideoPlayerVideoInfoData
import dev.aaa1115910.bv.player.entity.LocalVideoPlayerVideoShotData
import dev.aaa1115910.bv.player.entity.PortraitVideoFixMode
import dev.aaa1115910.bv.player.entity.PlayMode
import dev.aaa1115910.bv.player.entity.Resolution
import dev.aaa1115910.bv.player.entity.VideoListInteractiveNode
import dev.aaa1115910.bv.player.entity.VideoListItemData
import dev.aaa1115910.bv.entity.carddata.VideoCardData
import dev.aaa1115910.bv.player.entity.VideoPlayerConfigData
import dev.aaa1115910.biliapi.entity.live.LiveRoomItem
import dev.aaa1115910.bv.tv.component.live.LiveRoomCard
import dev.aaa1115910.bv.tv.component.videocard.SmallVideoCard
import dev.aaa1115910.bv.player.entity.VideoPlayerDanmakuMasksData
import dev.aaa1115910.bv.player.entity.VideoPlayerHistoryData
import dev.aaa1115910.bv.player.entity.VideoPlayerLoadStateData
import dev.aaa1115910.bv.player.entity.VideoPlayerPaymentData
import dev.aaa1115910.bv.player.entity.VideoPlayerSeekThumbData
import dev.aaa1115910.bv.player.entity.VideoPlayerVideoInfoData
import dev.aaa1115910.bv.player.entity.VideoPlayerVideoShotData
import dev.aaa1115910.bv.player.tv.BvPlayer
import dev.aaa1115910.bv.player.tv.controller.LiveViewerCountTip
import dev.aaa1115910.bv.player.tv.controller.OnlineViewerCountTip
import dev.aaa1115910.bv.player.tv.controller.SkipTip
import dev.aaa1115910.bv.player.tv.controller.UserActionKey
import dev.aaa1115910.bv.tv.activities.video.TagActivity
import dev.aaa1115910.bv.tv.activities.video.UpInfoActivity
import dev.aaa1115910.bv.tv.component.buttons.CoinButton
import dev.aaa1115910.bv.tv.component.CommentPanel
import dev.aaa1115910.bv.tv.component.DescriptionPanel
import dev.aaa1115910.bv.tv.component.InteractiveOptionDialog
import dev.aaa1115910.bv.tv.component.buttons.FavoriteButton
import dev.aaa1115910.bv.tv.component.buttons.LikeButton
import dev.aaa1115910.bv.tv.component.buttons.ToViewButton
import dev.aaa1115910.bv.tv.manager.FollowStateManager
import dev.aaa1115910.bv.tv.manager.PlayedAidsCache
import dev.aaa1115910.bv.tv.manager.VideoUserActionManager
import dev.aaa1115910.bv.tv.manager.VideoUserActionManager.getStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import dev.aaa1115910.bv.util.Prefs
import dev.aaa1115910.bv.util.toast
import dev.aaa1115910.bv.util.formatHourMinSec
import dev.aaa1115910.bv.util.swapList
import dev.aaa1115910.bv.viewmodel.VideoPlayerV3ViewModel
import dev.aaa1115910.bv.tv.component.GeetestTvVerifyDialog
import dev.aaa1115910.biliapi.http.BiliHttpApi
import dev.aaa1115910.biliapi.util.AvBvConverter
import dev.aaa1115910.bv.player.entity.DefaultStartPosition
import dev.aaa1115910.bv.player.entity.NextVideoStrategy
import dev.aaa1115910.bv.tv.component.videocard.TabbedVideosPanel
import dev.aaa1115910.bv.tv.component.live.LiveRoomListPanel
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import org.koin.androidx.compose.koinViewModel

private data class VideoPlayerScreenPrefsSnapshot(
    val uid: Long,
    val isLogin: Boolean,
    val defaultPlaySpeed: Float,
    val showOnlineViewerCount: Int,
    val showLiveViewerCountTip: Int,
    val incognitoMode: Boolean,
    val playerNextVideoStrategyOrder: String,
    val defaultStartPosition: DefaultStartPosition,
    val skipPgcIntroOutro: Boolean,
    val controllerButtonsOrder: String,
    val seekForwardStep: Int,
    val seekBackwardStep: Int,
    val showBottomProgressBar: Boolean,
    val playerDoubleBackToExit: Boolean,
    val portraitVideoFixMode: PortraitVideoFixMode,
    val exitWhenAllPlayed: Boolean,
    val longPressAction: Int,
    val longPressSpeed: Float,
    val nextTipDuration: Float,
)

@Composable
fun VideoPlayerV3Screen(
    modifier: Modifier = Modifier,
    playerViewModel: VideoPlayerV3ViewModel = koinViewModel(),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val logger = KotlinLogging.logger { }
    val prefsSnapshot = remember {
        VideoPlayerScreenPrefsSnapshot(
            uid = Prefs.uid,
            isLogin = Prefs.isLogin,
            defaultPlaySpeed = Prefs.defaultPlaySpeed,
            showOnlineViewerCount = Prefs.showOnlineViewerCount,
            showLiveViewerCountTip = Prefs.showLiveViewerCountTip,
            incognitoMode = Prefs.incognitoMode,
            playerNextVideoStrategyOrder = Prefs.playerNextVideoStrategyOrder,
            defaultStartPosition = Prefs.playerDefaultStartPosition.toPlayerType(),
            skipPgcIntroOutro = Prefs.skipPgcIntroOutro,
            controllerButtonsOrder = Prefs.playerControllerButtonsOrder,
            seekForwardStep = Prefs.playerSeekForwardStep,
            seekBackwardStep = Prefs.playerSeekBackwardStep,
            showBottomProgressBar = Prefs.playerShowBottomProgressBar,
            playerDoubleBackToExit = Prefs.playerDoubleBackToExit,
            portraitVideoFixMode = Prefs.portraitVideoFixMode,
            exitWhenAllPlayed = Prefs.playerExitWhenAllIsPlayed,
            longPressAction = Prefs.playerLongPressAction,
            longPressSpeed = Prefs.playerLongPressSpeed,
            nextTipDuration = Prefs.playerNextTipDuration,
        )
    }
    val customNextVideoStrategies = remember(prefsSnapshot.playerNextVideoStrategyOrder) {
        val validOrdinals = NextVideoStrategy.entries.map { it.ordinalValue }.toSet()
        prefsSnapshot.playerNextVideoStrategyOrder
            .split(",")
            .filter { !it.startsWith("-") }
            .mapNotNull { idText ->
                val id = idText.toIntOrNull() ?: return@mapNotNull null
                if (id !in validOrdinals) return@mapNotNull null
                NextVideoStrategy.fromOrdinal(id)
            }
    }

    // 存储下一个自动播放的内容，用于 SkipTip 卡片预览
    var nextTipCardData by remember { mutableStateOf<Any?>(null) }
    // 按确认键时立即执行的 action（跳过倒计时）
    var pendingAutoAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    // 外部创建 DanmakuView，与 videoPlayer 一致的模式
    val danmakuView = remember { DanmakuView(context).also { playerViewModel.danmakuView = it } }

    DisposableEffect(danmakuView) {
        onDispose {
            danmakuView.release()
        }
    }

    // subscribe shared action state by aid
    val currentAid = playerViewModel.currentAid
    val sharedActionFlow = remember(currentAid, prefsSnapshot.uid) {
        getStateFlow(currentAid, prefsSnapshot.uid)
    }
    val sharedActionState by sharedActionFlow.collectAsState()
    val followStateMap by FollowStateManager.followStateMap.collectAsState()

    LaunchedEffect(followStateMap, playerViewModel.upId) {
        val currentUpId = playerViewModel.upId
        if (currentUpId > 0) {
            val result = FollowStateManager.ensureFollowState(currentUpId)
            if (result != null && playerViewModel.isFollowingUp != result) {
                playerViewModel.isFollowingUp = result
            }
        }
    }

    // 倒计时相关状态
    var autoActionCountdownJob by remember { mutableStateOf<Job?>(null) }
    var autoActionTipVisible by remember { mutableStateOf(false) }
    var autoActionTipText by remember { mutableStateOf("") }
    var skipNextKeyUpCancel by remember { mutableStateOf(false) }
    var showDebugInfo by remember { mutableStateOf(Prefs.playerShowDebugInfo) }

    // 在线观看人数状态
    var onlineViewerCount by remember { mutableStateOf("") }
    var showOnlineViewerCountTip by remember { mutableStateOf(false) }
    var canShowViewerCountTip by remember { mutableStateOf(true) }
    var showLiveViewerCountTip by remember { mutableStateOf(false) }
    var viewerCountText by remember { mutableStateOf("") }

    // 评论面板状态
    var showCommentPanel by remember { mutableStateOf(false) }

    // 简介面板状态
    var showDescriptionPanel by remember { mutableStateOf(false) }

    // 焦点管理
    val relatedVideosFocusRequester = remember { FocusRequester() }

    // 当显示相关视频时，自动将焦点转移到VideosRow的第一个卡片
    LaunchedEffect(playerViewModel.showRelatedVideos) {
        if (playerViewModel.showRelatedVideos) {
            delay(300)
            kotlin.runCatching {
                relatedVideosFocusRequester.requestFocus()
            }
        }
    }

    // 当显示直播列表时，自动将焦点转移到第一个卡片
    LaunchedEffect(playerViewModel.showRelatedRooms) {
        if (playerViewModel.showRelatedRooms) {
            delay(300)
            kotlin.runCatching {
                relatedVideosFocusRequester.requestFocus()
            }
        }
    }

    // 获取在线观看人数
    LaunchedEffect(playerViewModel.currentCid, playerViewModel.currentAid) {
        if (playerViewModel.currentCid > 0 && playerViewModel.currentAid > 0 && prefsSnapshot.showOnlineViewerCount > 0) {
            withContext(Dispatchers.IO) {
                try {
                    val response = BiliHttpApi.getVideoOnlineTotal(
                        cid = playerViewModel.currentCid,
                        bvid = AvBvConverter.av2bv(playerViewModel.currentAid)
                    )
                    if (response.code == 0) {
                        onlineViewerCount = response.data?.total ?: ""
                        showOnlineViewerCountTip = true

                        // 如果设置为 30 秒后隐藏，则自动隐藏
                        if (prefsSnapshot.showOnlineViewerCount == 1) {
                            delay(30_000)
                            showOnlineViewerCountTip = false
                        }
                    }
                } catch (e: Exception) {
                    logger.warn(e) { "Failed to get online viewer count" }
                }
            }
        } else {
            onlineViewerCount = ""
        }
    }

    // 在线观看人数设置为30秒后隐藏或者始终显示，每 5 分钟刷新一次数据。虽然左下角隐藏，但播放器控制条中还要显示
    LaunchedEffect(showOnlineViewerCountTip, prefsSnapshot.showOnlineViewerCount) {
        if (showOnlineViewerCountTip) {
            while (true) {
                delay(300_000)  // 5 分钟
                if (playerViewModel.currentCid > 0 && playerViewModel.currentAid > 0) {
                    withContext(Dispatchers.IO) {
                        try {
                            val response = BiliHttpApi.getVideoOnlineTotal(
                                cid = playerViewModel.currentCid,
                                bvid = AvBvConverter.av2bv(playerViewModel.currentAid)
                            )
                            if (response.code == 0) {
                                onlineViewerCount = response.data?.total ?: ""
                            }
                        } catch (e: Exception) {
                            logger.warn(e) { "Failed to refresh online viewer count" }
                        }
                    }
                }
            }
        }
    }

    // 控制直播播放量显示
    LaunchedEffect(playerViewModel.isLive, prefsSnapshot.showLiveViewerCountTip, playerViewModel.watchedText) {
        if (playerViewModel.isLive && (prefsSnapshot.showLiveViewerCountTip > 0 || playerViewModel.watchedText.isNotEmpty())) {
            showLiveViewerCountTip = true
            if (prefsSnapshot.showLiveViewerCountTip == 1) {
                delay(30_000)
                showLiveViewerCountTip = false
            }
        } else {
            showLiveViewerCountTip = false
        }
    }

    // 更新 viewerCountText
    LaunchedEffect(prefsSnapshot.showOnlineViewerCount, onlineViewerCount, playerViewModel.watchedText, playerViewModel.liveOnlineCount) {
        if (playerViewModel.isLive && prefsSnapshot.showOnlineViewerCount > 0) {
            viewerCountText = ""
            if (playerViewModel.watchedText.isNotEmpty()) {
                viewerCountText = playerViewModel.watchedText
            }
            if (playerViewModel.watchedText.isNotEmpty() && playerViewModel.liveOnlineCount.isNotEmpty()) {
                viewerCountText += "  ·  "
            }
            if (playerViewModel.liveOnlineCount.isNotEmpty()) {
                viewerCountText += playerViewModel.liveOnlineCount
            }
        } else if (prefsSnapshot.showOnlineViewerCount > 0 && onlineViewerCount.isNotEmpty()) {
            viewerCountText = "$onlineViewerCount 人在看"
        }
    }

    // 处理back键，当推荐视频有焦点时隐藏推荐视频并将焦点返回到播放器
    BackHandler(enabled = playerViewModel.showRelatedVideos) {
        playerViewModel.showRelatedVideos = false
    }
    BackHandler(enabled = playerViewModel.showRelatedRooms) {
        playerViewModel.showRelatedRooms = false
    }

    val exitPlayer = {
        playerViewModel.dismissInteractiveOptionDialog()
        Prefs.currentPlaySpeed = prefsSnapshot.defaultPlaySpeed
        PlayedAidsCache.clear()
        (context as Activity).finish()
    }

    CompositionLocalProvider(
        LocalVideoPlayerSeekThumbData provides VideoPlayerSeekThumbData(
            idleIcon = playerViewModel.playerIconIdle,
            movingIcon = playerViewModel.playerIconMoving
        ),
        LocalVideoPlayerVideoInfoData provides VideoPlayerVideoInfoData(
            width = playerViewModel.currentVideoWidth,
            height = playerViewModel.currentVideoHeight,
            codec = playerViewModel.currentVideoCodec.name,
            title = playerViewModel.title,
            partTitle = playerViewModel.partTitle,
            play = playerViewModel.play,
            danmaku = playerViewModel.danmaku,
            like = playerViewModel.like,
            coin = playerViewModel.coin,
            favorite = playerViewModel.favorite,
            upName = playerViewModel.upName,
            pubTime = playerViewModel.pubTime,
            fromSeason = playerViewModel.fromSeason,
            isFollowingUp = playerViewModel.isFollowingUp,
            isVerticalVideo = playerViewModel.isVerticalVideo,
            isLive = playerViewModel.isLive,
            liveTime = playerViewModel.liveTime
        ),
        LocalVideoPlayerHistoryData provides VideoPlayerHistoryData(
            lastPlayed = playerViewModel.lastPlayed,
        ),
        LocalVideoPlayerPaymentData provides VideoPlayerPaymentData(
            needPay = playerViewModel.needPay,
            epid = playerViewModel.epid,
            showPreviewTip = playerViewModel.showPreviewTip,
        ),
        LocalVideoPlayerLoadStateData provides VideoPlayerLoadStateData(
            loadState = playerViewModel.loadState,
            errorMessage = playerViewModel.errorMessage,
        ),
        LocalVideoPlayerConfigData provides VideoPlayerConfigData(
            availableResolutions = playerViewModel.availableQuality,
            availableVideoCodec = playerViewModel.availableVideoCodec,
            availableAudio = playerViewModel.availableAudio,
            availableSubtitleTracks = playerViewModel.availableSubtitle,
            availableVideoList = playerViewModel.availableVideoList,
            currentVideoCid = playerViewModel.currentCid,
            currentResolution = playerViewModel.currentQuality,
            currentVideoCodec = playerViewModel.currentVideoCodec,
            currentVideoAspectRatio = playerViewModel.currentVideoAspectRatio,
            currentVideoRotation = playerViewModel.currentVideoRotation,
            currentVideoSpeed = playerViewModel.currentPlaySpeed,
            currentAudio = playerViewModel.currentAudio,
            currentDanmakuEnabled = playerViewModel.currentDanmakuEnabled,
            currentDanmakuEnabledList = playerViewModel.currentDanmakuTypes,
            currentDanmakuScale = playerViewModel.currentDanmakuScale,
            currentDanmakuOpacity = playerViewModel.currentDanmakuOpacity,
            currentDanmakuArea = playerViewModel.currentDanmakuArea,
            currentDanmakuMask = playerViewModel.currentDanmakuMask,
            currentDanmakuLaneDensity = playerViewModel.currentDanmakuLaneDensity,
            currentDanmakuRollingDurationFactor = playerViewModel.currentDanmakuRollingDurationFactor,
            currentDanmakuFilterLevel = playerViewModel.currentDanmakuFilterLevel,
            currentLiveDanmakuFilterLevel = playerViewModel.currentLiveDanmakuFilterLevel,
            currentSubtitleId = playerViewModel.currentSubtitleId,
            currentSubtitleData = playerViewModel.currentSubtitleData,
            currentSubtitleFontSize = playerViewModel.currentSubtitleFontSize,
            currentSubtitleBackgroundOpacity = playerViewModel.currentSubtitleBackgroundOpacity,
            currentSubtitleBottomPadding = playerViewModel.currentSubtitleBottomPadding,
            currentPlayMode = playerViewModel.currentPlayMode,
            incognitoMode = prefsSnapshot.incognitoMode,
            hasPreloadedVideoList = playerViewModel.preloadedVideoList.isNotEmpty(),
            hasRelatedVideos = playerViewModel.relatedVideos.isNotEmpty(),
            fromSeason = playerViewModel.fromSeason,
            showDanmaku = playerViewModel.showDanmaku,
            showRelatedVideos = playerViewModel.showRelatedVideos,
            showRelatedRooms = playerViewModel.showRelatedRooms,
            hasPreloadedLiveRoomList = playerViewModel.preloadedLiveRoomList.isNotEmpty(),
            showNextVideoBtn = !(playerViewModel.currentPlayMode == PlayMode.SingleVideo || playerViewModel.currentPlayMode == PlayMode.SingleLoop || (playerViewModel.currentPlayMode == PlayMode.Custom && customNextVideoStrategies.isEmpty())),
            defaultStartPosition = prefsSnapshot.defaultStartPosition,
            clipInfoList = playerViewModel.clipInfoList,
            skipPgcIntroOutro = prefsSnapshot.skipPgcIntroOutro,
            isLive = playerViewModel.isLive,
            availableLiveQualities = playerViewModel.availableLiveQualities.toList(),
            currentLiveQn = playerViewModel.currentLiveQn,
            currentLiveQualityDescription = playerViewModel.currentLiveQualityDescription,
            currentLiveCodec = playerViewModel.currentLiveCodec,
            availableLiveLines = playerViewModel.availableLiveLines.toList(),
            currentLiveLineIndex = playerViewModel.currentLiveLineIndex,
            controllerButtonsOrder = prefsSnapshot.controllerButtonsOrder,
            showDebugInfo = showDebugInfo,
            longPressAction = prefsSnapshot.longPressAction,
            longPressSpeed = prefsSnapshot.longPressSpeed
        ),
        LocalVideoPlayerDanmakuMasksData provides VideoPlayerDanmakuMasksData(
            danmakuMasks = playerViewModel.danmakuMasks,
        ),
        LocalVideoPlayerVideoShotData provides VideoPlayerVideoShotData(
            videoShot = playerViewModel.videoShot,
        ),
    ) {
        Box(
            modifier = Modifier
                .onPreviewKeyEvent { keyEvent ->
                    // 检测长按下键，标记跳过对应的 KeyUp 取消
                    if (keyEvent.type == KeyEventType.KeyDown
                        && keyEvent.nativeKeyEvent.isLongPress) {
                        skipNextKeyUpCancel = true
                    }
                    if (!skipNextKeyUpCancel && keyEvent.type == KeyEventType.KeyUp && listOf(Key.Enter, Key.DirectionCenter).contains(keyEvent.key) && autoActionCountdownJob != null && nextTipCardData != null) {
                        // 确认键：立即播放下一个，跳过倒计时
                        logger.debug { "按下确认键，立即播放下一个" }
                        autoActionCountdownJob?.cancel()
                        autoActionCountdownJob = null
                        autoActionTipVisible = false
                        pendingAutoAction?.invoke()
                        pendingAutoAction = null
                        return@onPreviewKeyEvent true
                    }
                    if (keyEvent.type == KeyEventType.KeyUp && autoActionCountdownJob != null) {
                        // 跳过长按下键触发的那次 KeyUp（长按下键释放）
                        if (skipNextKeyUpCancel) {
                            skipNextKeyUpCancel = false
                            return@onPreviewKeyEvent false
                        }
                        // 任何按键都可以取消倒计时
                        logger.debug { "按下按键: ${keyEvent.key}, 取消播放下一个（或自动退出）" }
                        autoActionCountdownJob?.cancel()
                        autoActionCountdownJob = null
                        autoActionTipVisible = false
                        nextTipCardData = null
                        pendingAutoAction = null
                        return@onPreviewKeyEvent keyEvent.key == Key.Back
                    }
                    false
                }
        ) {
            BvPlayer(
                modifier = modifier
                    .fillMaxSize(),
                videoPlayer = playerViewModel.videoPlayer!!,
                playerSeekForwardStep = prefsSnapshot.seekForwardStep,
                playerSeekBackwardStep = prefsSnapshot.seekBackwardStep,
                showBottomProgressBar = prefsSnapshot.showBottomProgressBar,
                playerDoubleBackToExit = prefsSnapshot.playerDoubleBackToExit,
                // 如果portraitVideoFixMode是降到1080P，但视频可能不存在1080P以下的资源（试看视频只有一个清晰度，可能是4K）
//              // 所以 其实是只要启用portraitVideoFixMode的任意模式，遇到4K视频都要用TextureView方式
                useTextureViewFixPortraitVideo = playerViewModel.isVerticalVideo && prefsSnapshot.portraitVideoFixMode != PortraitVideoFixMode.None && playerViewModel.currentQuality >= Resolution.R4K,
                onViewerCountTipCanShowChanged = { canShow ->
                    if (canShowViewerCountTip != canShow) {
                        canShowViewerCountTip = canShow
                    }
                },
                viewerCountText = viewerCountText,
                isShowSkipTip = autoActionTipVisible,
                danmakuView = danmakuView,
                onToggleRelatedVideos = { state ->
                    playerViewModel.showRelatedVideos = if (playerViewModel.relatedVideos.isNotEmpty() || playerViewModel.preloadedVideoList.isNotEmpty()) state else false
                },
                onToggleRelatedRooms = { state ->
                    playerViewModel.showRelatedRooms = if (playerViewModel.preloadedLiveRoomList.isNotEmpty()) state else false
                },
                onSwitchNextRoom = {
                    val rooms = playerViewModel.preloadedLiveRoomList
                    if (rooms.isEmpty()) return@BvPlayer
                    val currentIndex = rooms.indexOfFirst { it.roomId == playerViewModel.liveRoomId }
                    val nextRooms = rooms.filter { it.liveStatus == 1 }
                    if (nextRooms.isEmpty()) return@BvPlayer
                    // 找到当前房间之后的第一个在播房间
                    val nextRoom = nextRooms.firstOrNull { room ->
                        rooms.indexOf(room) > currentIndex
                    } ?: nextRooms.first() // 如果后面没有了，循环到第一个
                    // 如果下一个房间就是当前房间（只有一个在播房间），不切换
                    if (nextRoom.roomId == playerViewModel.liveRoomId) return@BvPlayer
                    nextTipCardData = nextRoom
                    pendingAutoAction = {
                        nextTipCardData = null
                        autoActionCountdownJob = null
                        logger.info { "Switch to next live room: ${nextRoom.roomId} - ${nextRoom.title}" }
                        val roomWatchedText = nextRoom.watchedShow?.let { show ->
                            show.textSmall + if (show.switch) "播放" else "人气"
                        } ?: ""
                        playerViewModel.apply {
                            title = nextRoom.title
                            upName = nextRoom.uname
                            upId = nextRoom.uid
                            upFace = nextRoom.face
                            isLive = true
                            watchedText = roomWatchedText
                            showRelatedRooms = false
                            loadLiveStreamWithQuality(nextRoom.roomId)
                        }
                        pendingAutoAction = null
                    }
                    autoActionCountdownJob = scope.launch {
                        if(prefsSnapshot.nextTipDuration > 0f) {
                            autoActionTipVisible = true
                        }
                        delay((prefsSnapshot.nextTipDuration * 1000).toLong())
                        autoActionTipVisible = false
                        if (autoActionCountdownJob != null) {
                            pendingAutoAction?.invoke()
                        }
                    }
                },
                autoOpenPlayListOnVideoEnd = false,
                onSendHeartbeat = playerViewModel::uploadHistory,
                onClearBackToHistoryData = { playerViewModel.lastPlayed = 0 },
                onLoadNextVideo = { immediate ->
                    if (playerViewModel.showRelatedVideos || playerViewModel.showRelatedRooms) {
                        logger.info { "Related videos/rooms is shown, skip auto action" }
                        return@BvPlayer
                    }

                    if (showCommentPanel) {
                        logger.info { "Comment panel is shown, skip auto action" }
                        return@BvPlayer
                    }

                    if (playerViewModel.isInteractivePlayback) {
                        playerViewModel.requestInteractiveOptionDialog()
                        return@BvPlayer
                    }

                    // 找出下一个剧集/分P
                    val currentIndex = playerViewModel.availableVideoList.indexOfFirst {
                        when (it) {
                            is VideoListItemData -> it.cid == playerViewModel.currentCid
                            else -> false
                        }
                    }
                    val nextEp =
                        if (currentIndex >= 0 && currentIndex + 1 < playerViewModel.availableVideoList.size) {
                            playerViewModel.availableVideoList
                                .drop(currentIndex + 1)
                                .firstOrNull { it is VideoListItemData } as? VideoListItemData
                        } else null

                    // 找出上一个剧集/分P（逆序模式用）
                    val prevEp =
                        if (currentIndex > 0) {
                            playerViewModel.availableVideoList
                                .take(currentIndex)
                                .lastOrNull { it is VideoListItemData } as? VideoListItemData
                        } else null

                    // 标记当前稿件已播放
                    PlayedAidsCache.markPlayed(playerViewModel.currentAid)

                    // 找出下一个推荐视频（非充电、非播放过的aid）
                    val candidates = playerViewModel.relatedVideos
                        .filter { related -> !related.isChargingArc && !PlayedAidsCache.hasPlayed(related.avid) }
                    val nextRelatedVideo = if (!playerViewModel.fromSeason && candidates.isNotEmpty()) candidates.random() else null

                    // 找出预加载列表的下一个
                    val preloaded = playerViewModel.preloadedVideoList
                    val preloadIndex = playerViewModel.resolveLastPreloadedVideoIndex()
                    val nextPreloaded = if (!playerViewModel.fromSeason && preloadIndex >= 0 && preloadIndex + 1 < preloaded.size) {
                        preloaded[preloadIndex + 1]
                    } else null

                    // 找出预加载列表的上一个（逆序模式用）
                    val prevPreloaded = if (!playerViewModel.fromSeason && preloadIndex > 0) {
                        preloaded[preloadIndex - 1]
                    } else null

                    // nextVideo 可以是分P/剧集(VideoListItemData) 或推荐卡片(VideoCardData)
                    var nextVideo: Any? = null

                    when (playerViewModel.currentPlayMode) {
                        PlayMode.Custom -> {
                            // 使用设置中的策略顺序
                            for (strategy in customNextVideoStrategies) {
                                if (strategy == NextVideoStrategy.SingleVideo) {
                                    // 单视频模式：不自动播放下一个
                                    break
                                } else if (strategy == NextVideoStrategy.PartAndEpisode) {
                                    if (nextEp != null) { nextVideo = nextEp; break }
                                } else if (strategy == NextVideoStrategy.PreloadedVideoList) {
                                    if (nextPreloaded != null) { nextVideo = nextPreloaded; break }
                                } else if (strategy == NextVideoStrategy.RelatedVideo) {
                                    if (nextRelatedVideo != null) { nextVideo = nextRelatedVideo; break }
                                } else if (strategy == NextVideoStrategy.PartAndEpisodeReverse) {
                                    if (prevEp != null) { nextVideo = prevEp; break }
                                } else if (strategy == NextVideoStrategy.PreloadedVideoListReverse) {
                                    if (prevPreloaded != null) { nextVideo = prevPreloaded; break }
                                }
                            }
                        }
                        PlayMode.SingleVideo -> {
                            // 单视频模式：不自动播放下一个
                            logger.info { "PlayMode.SingleVideo: no auto next" }
                        }
                        PlayMode.SingleLoop -> {
                            // BvPlayer.onEnd 已处理循环，这里不应到达
                            logger.info { "PlayMode.SingleLoop: should not reach onLoadNextVideo" }
                        }
                        PlayMode.ListOrder -> {
                            if (nextPreloaded != null) {
                                playerViewModel.resolveLastPreloadedVideoIndex(nextPreloaded.avid)
                            }
                            nextVideo = nextPreloaded
                        }
                        PlayMode.ListOrderReverse -> {
                            if (prevPreloaded != null) {
                                playerViewModel.resolveLastPreloadedVideoIndex(prevPreloaded.avid)
                            }
                            nextVideo = prevPreloaded
                        }
                        PlayMode.PartAndEpisode -> {
                            nextVideo = nextEp
                        }
                        PlayMode.PartAndEpisodeReverse -> {
                            nextVideo = prevEp
                        }
                        PlayMode.RelatedVideo -> {
                            nextVideo = nextRelatedVideo
                        }
                    }

                    if (nextVideo != null) {
                        nextTipCardData = nextVideo
                        pendingAutoAction = {
                            nextTipCardData = null
                            autoActionCountdownJob = null
                            when (nextVideo) {
                                is VideoListItemData -> {
                                    PlayedAidsCache.markPlayed(nextVideo.aid)
                                    playerViewModel.title = nextVideo.title
                                    playerViewModel.partTitle = nextVideo.partTitle
                                    if (nextVideo.seasonId == null && playerViewModel.currentAid != nextVideo.aid) {
                                        VideoInfoActivity.actionStart(
                                            context = context,
                                            aid = nextVideo.aid,
                                            cid = nextVideo.cid,
                                            fromPlayer = true
                                        )
                                    } else {
                                        playerViewModel.loadPlayUrl(
                                            avid = nextVideo.aid,
                                            cid = nextVideo.cid!!,
                                            epid = nextVideo.epid,
                                            seasonId = nextVideo.seasonId,
                                            continuePlayNext = true
                                        )
                                    }
                                }

                                is VideoCardData -> {
                                    // 推荐视频卡片：跳转到视频详情（再进入播放器）
                                    PlayedAidsCache.markPlayed(nextVideo.avid)
                                    if (nextVideo.jumpToSeason) {
                                        SeasonInfoActivity.actionStart(
                                            context = context,
                                            epId = nextVideo.epId!!,
                                            seasonId = nextVideo.seasonId!!,
                                            proxyArea = ProxyArea.checkProxyArea(nextVideo.title)
                                        )
                                    } else {
                                        VideoInfoActivity.actionStart(
                                            context = context,
                                            aid = nextVideo.avid,
                                            fromPlayer = true
                                        )
                                    }
                                }
                            }
                            pendingAutoAction = null
                        }
                        autoActionCountdownJob = scope.launch {
                            try {
                                if (!immediate) {
                                    autoActionTipVisible = true
                                    delay((prefsSnapshot.nextTipDuration * 1000).toLong())
                                }
                                autoActionTipVisible = false
                                if (autoActionCountdownJob != null) {
                                    pendingAutoAction?.invoke()
                                }
                            } catch (_: Exception) {
                                autoActionTipVisible = false
                                autoActionCountdownJob = null
                                nextTipCardData = null
                                pendingAutoAction = null
                            }
                        }
                    } else if (prefsSnapshot.exitWhenAllPlayed) {
                        // 没有下一个：退出
                        autoActionCountdownJob = scope.launch {
                            try {
                                autoActionTipText = "播放结束，即将退出"
                                autoActionTipVisible = true
                                delay(1380)
                                autoActionTipVisible = false
                                if (autoActionCountdownJob != null) {
                                    autoActionCountdownJob = null
                                    Prefs.currentPlaySpeed = prefsSnapshot.defaultPlaySpeed
                                    // 自动退出时也清空缓存
                                    PlayedAidsCache.clear()
                                    (context as Activity).finish()
                                }
                            } catch (_: Exception) {
                                autoActionTipVisible = false
                                autoActionCountdownJob = null
                            }
                        }
                    }
                    // 什么都不做
                },
                onExit = exitPlayer,
                onLoadNewVideo = { videoListItem ->
                    when (videoListItem) {
                        is VideoListItemData -> {
                            // 手动选择新视频时也标记播放
                            PlayedAidsCache.markPlayed(videoListItem.aid)
                            if (videoListItem is VideoListInteractiveNode) {
                                playerViewModel.playInteractiveOption(videoListItem)
                            } else if (videoListItem.seasonId == null && playerViewModel.currentAid != videoListItem.aid) {
                                playerViewModel.title = videoListItem.title
                                playerViewModel.partTitle = videoListItem.partTitle
                                VideoInfoActivity.actionStart(
                                    context = context,
                                    aid = videoListItem.aid,
                                    cid = videoListItem.cid,
                                    fromPlayer = true
                                )
                            } else {
                                playerViewModel.title = videoListItem.title
                                playerViewModel.partTitle = videoListItem.partTitle
                                playerViewModel.loadPlayUrl(
                                    avid = videoListItem.aid,
                                    cid = videoListItem.cid!!,
                                    epid = videoListItem.epid,
                                    seasonId = videoListItem.seasonId,
                                    continuePlayNext = true
                                )
                            }
                        }
                    }
                },
                onRefreshVideo = {
                    if (playerViewModel.isLive) {
                        // 直播模式：重新获取直播流 URL
                        logger.info { "Reload live stream for room ${playerViewModel.liveRoomId}" }
                        playerViewModel.loadLiveStreamWithQuality(
                            playerViewModel.liveRoomId,
                            playerViewModel.currentLiveQn
                        )
                    } else {
                        val time = playerViewModel.videoPlayer?.currentPosition ?: 0
                        logger.info { "Reload video and back to time: ${time.formatHourMinSec()}" }
                        scope.launch {
                            playerViewModel.playQuality()
                            playerViewModel.videoPlayer?.seekTo(time)
                            playerViewModel.danmakuView?.notifySeek(time)
                            playerViewModel.videoPlayer?.start()
                            Toast.makeText(
                                context,
                                "New video host: ${playerViewModel.lastVideoHost}\nNew audio host: ${playerViewModel.lastAudioHost}",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                },
                onLiveRetry = {
                    playerViewModel.retryLiveStream()
                },
                onShowComment = { showCommentPanel = true },
                onShowDescription = { showDescriptionPanel = true },
                onShowVideoDetail = {
                    if (playerViewModel.currentAid != 0L) {
                        VideoInfoActivity.actionStart(
                            context = context,
                            aid = playerViewModel.currentAid,
                            fromPlayer = false,
                            forceShowDetail = true
                        )
                    }
                },
                onResolutionChange = { resolutionCode, afterChange ->
                    scope.launch(Dispatchers.Default) {
                        playerViewModel.playQuality(resolutionCode)
                        afterChange()
                        playerViewModel.currentQuality = resolutionCode
                    }
                },
                onCodecChange = { videoCodec, afterChange ->
                    playerViewModel.currentVideoCodec = videoCodec
                    scope.launch(Dispatchers.Default) {
                        playerViewModel.playQuality(
                            playerViewModel.currentQuality,
                            playerViewModel.currentVideoCodec
                        )
                        afterChange()
                    }
                },
                onAspectRatioChange = { aspectRatio ->
                    playerViewModel.currentVideoAspectRatio = aspectRatio
                },
                onRotationChange = { rotation ->
                    playerViewModel.currentVideoRotation = rotation
                },
                onPlaySpeedChange = { speed ->
                    Prefs.currentPlaySpeed = speed
                    playerViewModel.currentPlaySpeed = speed
                },
                onAudioChange = { audio, afterChange ->
                    playerViewModel.currentAudio = audio
                    scope.launch(Dispatchers.Default) {
                        playerViewModel.playQuality(audio = audio)
                        afterChange()
                    }
                },
                onLiveQualityChange = { qn ->
                    playerViewModel.changeLiveQuality(qn)
                },
                onLiveCodecChange = { codec ->
                    println("VideoPlayerV3Screen: onLiveCodecChange called with codec=$codec")
                    playerViewModel.changeLiveCodec(codec)
                },
                onLiveLineChange = { lineIndex ->
                    playerViewModel.changeLiveLine(lineIndex)
                },
                onDanmakuSwitchChange = { enabledDanmakuTypes ->
                    Prefs.defaultDanmakuTypes = enabledDanmakuTypes
                    playerViewModel.currentDanmakuTypes.swapList(enabledDanmakuTypes)
                },
                onDanmakuSizeChange = { scale ->
                    Prefs.defaultDanmakuScale = scale
                    playerViewModel.currentDanmakuScale = scale
                },
                onDanmakuOpacityChange = { opacity ->
                    Prefs.defaultDanmakuOpacity = opacity
                    playerViewModel.currentDanmakuOpacity = opacity
                },
                onDanmakuAreaChange = { area ->
                    Prefs.defaultDanmakuArea = area
                    playerViewModel.currentDanmakuArea = area
                },
                onDanmakuMaskChange = { mask ->
                    Prefs.defaultDanmakuMask = mask
                    playerViewModel.currentDanmakuMask = mask
                },
                onDanmakuLaneDensityChange = { density ->
                    Prefs.defaultDanmakuLaneDensity = density
                    playerViewModel.currentDanmakuLaneDensity = density
                },
                onDanmakuRollingDurationFactorChange = { factor ->
                    Prefs.defaultDanmakuRollingDurationFactor = factor
                    playerViewModel.currentDanmakuRollingDurationFactor = factor
                },
                onDanmakuFilterLevelChange = { filterLevel ->
                    if (playerViewModel.isLive) {
                        Prefs.defaultLiveDanmakuFilterLevel = filterLevel
                        playerViewModel.currentLiveDanmakuFilterLevel = filterLevel
                    } else {
                        Prefs.defaultDanmakuFilterLevel = filterLevel
                        playerViewModel.currentDanmakuFilterLevel = filterLevel
                    }
                },
                onSubtitleChange = { subtitle ->
                    playerViewModel.loadSubtitle(subtitle.id)
                },
                onSubtitleSizeChange = { size ->
                    Prefs.defaultSubtitleFontSize = size
                    playerViewModel.currentSubtitleFontSize = size
                },
                onSubtitleBackgroundOpacityChange = { opacity ->
                    Prefs.defaultSubtitleBackgroundOpacity = opacity
                    playerViewModel.currentSubtitleBackgroundOpacity = opacity
                },
                onSubtitleBottomPadding = { padding ->
                    Prefs.defaultSubtitleBottomPadding = padding
                    playerViewModel.currentSubtitleBottomPadding = padding
                },
                onPlayModeChange = { playMode ->
                    Prefs.defaultPlayMode = playMode
                    playerViewModel.currentPlayMode = playMode
                },
                onDebugInfoChange = { enabled ->
                    Prefs.playerShowDebugInfo = enabled
                    showDebugInfo = enabled
                },
                onOpenUpSpace = {
                    UpInfoActivity.actionStart(
                        context,
                        mid = playerViewModel.upId,
                        name = playerViewModel.upName,
                        face = playerViewModel.upFace
                    )
                },
                onShowDanmakuChange = {
                    Prefs.showDanmaku = it
                    playerViewModel.showDanmaku = it
                },
                userActionContent = { 
                    modifier,
                    focusMap, 
                    onFocus, 
                    onPauseAutoHide ->
                    if (prefsSnapshot.isLogin && !playerViewModel.fromSeason) {
                        // 增加操作：点赞、收藏、投币。通过 focusMap 获取 focusRequester 并在 onFocusChanged 回调时通知 controller
                        val likeFocus = focusMap[UserActionKey.Like]
                        val favFocus = focusMap[UserActionKey.Favorite]
                        val coinFocus = focusMap[UserActionKey.Coin]
                        val toViewFocus = focusMap[UserActionKey.ToView]

                        Row(
                            modifier = modifier
                                .fillMaxWidth()
                                .padding(start = 32.dp, bottom = 4.dp)
                                .offset(y = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            LikeButton(
                                modifier = Modifier
                                    .height(26.dp)
                                    .onFocusChanged { if (it.isFocused) onFocus(UserActionKey.Like) }
                                    .then(likeFocus?.let { Modifier.focusRequester(it) } ?: Modifier),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                colors = ButtonDefaults.colors(
                                    containerColor = Color.Transparent,
                                    focusedContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                                    focusedContentColor = MaterialTheme.colorScheme.onSurface
                                ),
                                border = ButtonDefaults.border(
                                    border = Border(
                                        border = BorderStroke(
                                            width = 1.dp,
                                            color = Color.Transparent
                                        )
                                    ),
                                    focusedBorder = Border(
                                        border = BorderStroke(
                                            width = 1.dp,
                                            color = Color.White.copy(alpha = 0.45f)
                                        )
                                    )
                                ),
                                // use shared state
                                isLike = sharedActionState.liked,
                                onToggleLike = {
                                    val aid = playerViewModel.currentAid
                                    scope.launch {
                                        val flow = getStateFlow(aid, prefsSnapshot.uid)
                                        val current = flow.value
                                        if (current.liked) {
                                            val success = VideoUserActionManager.delLike(aid, prefsSnapshot.uid)
                                            if (!success) {
                                                "点赞失败".toast(context)
                                            }
                                        } else {
                                            val success = VideoUserActionManager.addLike(aid, prefsSnapshot.uid)
                                            if (!success) {
                                                "取消点赞失败".toast(context)
                                            }
                                        }
                                    }
                                }
                            )
                            FavoriteButton(
                                modifier = Modifier
                                    .height(24.dp)
                                    .onFocusChanged { if (it.isFocused) onFocus(UserActionKey.Favorite) }
                                    .then(favFocus?.let { Modifier.focusRequester(it) } ?: Modifier),
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                                colors = ButtonDefaults.colors(
                                    containerColor = Color.Transparent,
                                    focusedContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                                    focusedContentColor = MaterialTheme.colorScheme.onSurface
                                ),
                                border = ButtonDefaults.border(
                                    border = Border(
                                        border = BorderStroke(
                                            width = 1.dp,
                                            color = Color.Transparent
                                        )
                                    ),
                                    focusedBorder = Border(
                                        border = BorderStroke(
                                            width = 1.dp,
                                            color = Color.White.copy(alpha = 0.45f)
                                        )
                                    )
                                ),
                                dialogContainerColor = Color.Black.copy(alpha = 0.5f),
                                isFavorite = sharedActionState.favorited,
                                favoriteFolderIds = sharedActionState.favoriteFolderIds,
                                onAddToDefaultFavoriteFolder = {
                                    scope.launch {
                                        val success = VideoUserActionManager.addToDefaultFavoriteFolder(playerViewModel.currentAid, prefsSnapshot.uid)
                                        if (!success) {
                                            "收藏失败！默认收藏夹不存在？".toast(context)
                                        }
                                    }
                                },
                                onUpdateFavoriteFolders = {
                                    scope.launch {
                                        val success = VideoUserActionManager.updateVideoFavoriteFolders(playerViewModel.currentAid, it, prefsSnapshot.uid)
                                        if (!success) {
                                            "收藏失败！此收藏夹收藏数量已达上限（1000）".toast(context)
                                        }
                                    }
                                },
                                onDialogVisibilityChanged = onPauseAutoHide
                            )
                            CoinButton(
                                modifier = Modifier
                                    .height(26.dp)
                                    .onFocusChanged { if (it.isFocused) onFocus(UserActionKey.Coin) }
                                    .then(coinFocus?.let { Modifier.focusRequester(it) } ?: Modifier),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                colors = ButtonDefaults.colors(
                                    containerColor = Color.Transparent,
                                    focusedContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                                    focusedContentColor = MaterialTheme.colorScheme.onSurface
                                ),
                                border = ButtonDefaults.border(
                                    border = Border(
                                        border = BorderStroke(
                                            width = 1.dp,
                                            color = Color.Transparent
                                        )
                                    ),
                                    focusedBorder = Border(
                                        border = BorderStroke(
                                            width = 1.dp,
                                            color = Color.White.copy(alpha = 0.45f)
                                        )
                                    )
                                ),
                                isCoin = sharedActionState.coin,
                                onAddCoin = {
                                    if (sharedActionState.coin)
                                        return@CoinButton

                                    scope.launch {
                                        val success = VideoUserActionManager.addCoin(playerViewModel.currentAid, prefsSnapshot.uid)
                                        withContext(Dispatchers.Main) {
                                            if (!success) {
                                                "投币失败".toast(context)
                                            }
                                        }
                                    }
                                }
                            )
                            ToViewButton(
                                modifier = Modifier
                                    .height(26.dp)
                                    .onFocusChanged { if (it.isFocused) onFocus(UserActionKey.ToView) }
                                    .then(toViewFocus?.let { Modifier.focusRequester(it) } ?: Modifier),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                colors = ButtonDefaults.colors(
                                    containerColor = Color.Transparent,
                                    focusedContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                                    focusedContentColor = MaterialTheme.colorScheme.onSurface
                                ),
                                border = ButtonDefaults.border(
                                    border = Border(
                                        border = BorderStroke(
                                            width = 1.dp,
                                            color = Color.Transparent
                                        )
                                    ),
                                    focusedBorder = Border(
                                        border = BorderStroke(
                                            width = 1.dp,
                                            color = Color.White.copy(alpha = 0.45f)
                                        )
                                    )
                                ),
                                onAddToView = {
                                    scope.launch {
                                        val success = VideoUserActionManager.addToView(playerViewModel.currentAid, prefsSnapshot.uid)
                                        if (success) {
                                            "已添加到稍后再看".toast(context)
                                        } else {
                                            "添加到稍后再看失败".toast(context)
                                        }
                                    }
                                }
                            )
                        }
                    }
                }
            )

            // 显示跳过提示
            if (autoActionTipVisible) {
                val cardData = nextTipCardData
                SkipTip(
                    modifier = Modifier.padding(bottom = 22.dp),
                    show = true,
                    text = autoActionTipText,
                    align = Alignment.BottomEnd,
                    content = if (cardData != null) {
                        {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .scale(1.1f)
                                    .background(Color.Black.copy(alpha = 0.6f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Column (
                                    modifier = Modifier.offset(y = (-16).dp),
                                    horizontalAlignment = Alignment.Start
                                ) {
                                    Text(
                                        text = "即将播放",
                                        style = MaterialTheme.typography.titleLarge,
                                        color = Color.White
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    NextTipCardPreview(
                                        data = cardData,
                                        modifier = Modifier.width(210.dp)
                                    )
                                }
                            }
                        }
                    } else {
                        null
                    }
                )
            }

            InteractivePlaybackDialogHost(
                playerViewModel = playerViewModel,
                onExit = exitPlayer
            )

            // 推荐视频 / 视频列表
            AnimatedVisibility(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth(),
                visible = playerViewModel.showRelatedVideos && !playerViewModel.isLive && !playerViewModel.fromSeason,
                enter = expandVertically(),
                exit = shrinkVertically(),
                label = "RelatedVideosForPlayer"
            ) {
                TabbedVideosPanel(
                    relatedVideos = playerViewModel.relatedVideos,
                    preloadedVideos = playerViewModel.preloadedVideoList,
                    currentAid = playerViewModel.currentAid,
                    focusRequester = relatedVideosFocusRequester,
                    onOpenSeasonInfo = { videoData, fromUGCList ->
                        if (fromUGCList) {
                            playerViewModel.resolveLastPreloadedVideoIndex(videoData.avid)
                        }
                        SeasonInfoActivity.actionStart(
                            context = context,
                            epId = videoData.epId!!,
                            seasonId = videoData.seasonId!!,
                            proxyArea = ProxyArea.checkProxyArea(videoData.title)
                        )
                    },
                    onOpenVideoInfo = { videoData, fromUGCList ->
                        if (fromUGCList) {
                            playerViewModel.resolveLastPreloadedVideoIndex(videoData.avid)
                        }
                        VideoInfoActivity.actionStart(
                            context = context,
                            aid = videoData.avid,
                            fromPlayer = true
                        )
                    }
                )
            }

            // 直播房间列表
            AnimatedVisibility(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth(),
                visible = playerViewModel.showRelatedRooms && playerViewModel.isLive,
                enter = expandVertically(),
                exit = shrinkVertically(),
                label = "RelatedRoomsForPlayer"
            ) {
                LiveRoomListPanel(
                    liveRooms = playerViewModel.preloadedLiveRoomList,
                    currentRoomId = playerViewModel.liveRoomId,
                    focusRequester = relatedVideosFocusRequester,
                    onOpenLiveRoom = { room ->
                        if (room.liveStatus != 1) {
                            "${room.uname} 未开播".toast(context)
                            return@LiveRoomListPanel
                        }
                        val roomWatchedText = room.watchedShow?.let { show ->
                            show.textSmall + if (show.switch) "播放" else "人气"
                        } ?: ""
                        playerViewModel.apply {
                            title = room.title
                            upName = room.uname
                            upId = room.uid
                            upFace = room.face
                            isLive = true
                            watchedText = roomWatchedText
                            showRelatedRooms = false
                            loadLiveStreamWithQuality(room.roomId)
                        }
                    }
                )
            }

            // 在线观看人数 Tip
            OnlineViewerCountTip(
                show = showOnlineViewerCountTip && canShowViewerCountTip && !playerViewModel.showRelatedVideos && !playerViewModel.showRelatedRooms,
                count = onlineViewerCount
            )

            // 评论面板
            if (playerViewModel.currentAid > 0) {
                CommentPanel(
                    show = showCommentPanel,
                    oid = playerViewModel.currentAid,
                    onHide = { showCommentPanel = false }
                )
            }

            // 简介面板
            DescriptionPanel(
                show = showDescriptionPanel,
                description = playerViewModel.videoDescription,
                tags = playerViewModel.videoTags,
                onHide = { showDescriptionPanel = false },
                onClickTag = { tag ->
                    TagActivity.actionStart(
                        context = context,
                        tagId = tag.id,
                        tagName = tag.name
                    )
                }
            )

            // 直播人气 Tip（左下角常驻）
            LiveViewerCountTip(
                show = showLiveViewerCountTip && canShowViewerCountTip,
                watchedText = playerViewModel.watchedText,
                onlineCount = playerViewModel.liveOnlineCount
            )

            // 风控 Geetest 验证弹窗（TV 遥控器十字光标 + WebView）
            if (playerViewModel.showGeetestDialog) {
                GeetestTvVerifyDialog(
                    gt = playerViewModel.geetestGt,
                    challenge = playerViewModel.geetestChallenge,
                    onResult = { result ->
                        playerViewModel.onGeetestResult(
                            challenge = result.challenge,
                            validate = result.validate,
                            seccode = result.seccode,
                        )
                    },
                    onDismiss = {
                        playerViewModel.onGeetestCancelled()
                    },
                )
            }
        }
    }
}

@Composable
private fun InteractivePlaybackDialogHost(
    playerViewModel: VideoPlayerV3ViewModel,
    onExit: () -> Unit,
) {
    val showInteractiveOptionDialog = playerViewModel.showInteractiveOptionDialog
    val interactiveOptions = playerViewModel.interactiveOptions
    val dismissDialog = playerViewModel::dismissInteractiveOptionDialog

    BackHandler(enabled = showInteractiveOptionDialog) {
        dismissDialog()
    }

    InteractiveOptionDialog(
        show = showInteractiveOptionDialog,
        options = interactiveOptions,
        onSelectOption = { option ->
            PlayedAidsCache.markPlayed(option.aid)
            playerViewModel.playInteractiveOption(option)
        },
        onDismiss = dismissDialog,
        onExit = onExit
    )
}

@Composable
private fun NextTipCardPreview(
    data: Any,
    modifier: Modifier = Modifier,
) {
    when (data) {
        is VideoCardData -> SmallVideoCard(
            modifier = modifier.width(180.dp),
            data = data,
        )
        is LiveRoomItem -> LiveRoomCard(
            modifier = modifier.width(180.dp),
            data = data,
        )
        is VideoListItemData -> {
            val cardData = remember(data) {
                VideoCardData(
                    avid = data.aid,
                    title = data.partTitle.ifBlank { data.title },
                    cover = data.cover,
                    upName = "",
                )
            }
            SmallVideoCard(
                modifier = modifier.width(180.dp),
                data = cardData,
            )
        }
    }
}
