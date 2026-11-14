package dev.aaa1115910.bv.tv.screens.user

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.itemsIndexed as staggeredItemsIndexed
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Done
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import dev.aaa1115910.biliapi.repositories.UserRepository
import dev.aaa1115910.bv.R
import dev.aaa1115910.bv.entity.carddata.VideoCardData
import dev.aaa1115910.bv.entity.proxy.ProxyArea
import dev.aaa1115910.bv.tv.activities.dynamic.DynamicDetailActivity
import dev.aaa1115910.bv.tv.activities.user.ArticleActivity
import dev.aaa1115910.bv.tv.activities.video.VideoInfoActivity
import dev.aaa1115910.bv.tv.component.TopNav
import dev.aaa1115910.bv.tv.component.TopNavItem
import dev.aaa1115910.bv.tv.component.videocard.SmallVideoCard
import dev.aaa1115910.bv.tv.manager.FollowStateManager
import dev.aaa1115910.bv.tv.util.ProvideListBringIntoViewSpec
import dev.aaa1115910.bv.tv.util.archiveFocusIndex
import dev.aaa1115910.bv.tv.util.blockDownFocusExitAtGridEnd
import dev.aaa1115910.bv.tv.util.gridFocusItemCount
import dev.aaa1115910.bv.tv.util.GridItemBox
import dev.aaa1115910.bv.tv.util.GridVertical
import dev.aaa1115910.bv.tv.util.VerticalMove
import dev.aaa1115910.bv.tv.util.revealScrollStep
import dev.aaa1115910.bv.tv.util.sameColumnNeighbor
import dev.aaa1115910.bv.tv.util.stableItemKey
import dev.aaa1115910.bv.tv.util.verticalMove
import dev.aaa1115910.bv.tv.util.requestFocusWithRetry
import dev.aaa1115910.bv.tv.util.verticalScrollStep
import dev.aaa1115910.bv.ui.theme.BVTheme
import dev.aaa1115910.bv.util.Prefs
import dev.aaa1115910.bv.util.collectAsStateLazily
import dev.aaa1115910.bv.util.fInfo
import dev.aaa1115910.bv.util.toast
import dev.aaa1115910.bv.viewmodel.user.UserSpaceTab
import dev.aaa1115910.bv.viewmodel.user.UserSpaceViewModel
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import dev.aaa1115910.bv.repository.VideoInfoRepository
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.getKoin
import org.koin.compose.koinInject

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun UpSpaceScreen(
    modifier: Modifier = Modifier,
    userSpaceViewModel: UserSpaceViewModel = koinViewModel(),
    userRepository: UserRepository = getKoin().get(),
) {
    val context = LocalContext.current
    val videoInfoRepository: VideoInfoRepository = koinInject()
    val scope = rememberCoroutineScope()
    val logger = KotlinLogging.logger { }
    var currentIndex by remember { mutableIntStateOf(0) }
    var selectedTab by remember { mutableStateOf(UserSpaceTab.Videos) }

    var showFollowButton by remember { mutableStateOf(false) }
    var isFollowing by remember { mutableStateOf(false) }
    var isLongPress by remember { mutableStateOf(false) }
    val selectedArchive = userSpaceViewModel.selectedCollection != null ||
        userSpaceViewModel.selectedSeries != null
    val archiveTitle = archiveDisplayTitle(
        userSpaceViewModel.selectedCollection,
        userSpaceViewModel.selectedSeries
    )
    // 各列表的分页状态类型一致，UI 只需要当前分支那一个
    val listState = when {
        selectedArchive -> userSpaceViewModel.archives
        selectedTab == UserSpaceTab.Articles -> userSpaceViewModel.articles
        selectedTab == UserSpaceTab.Dynamics -> userSpaceViewModel.dynamics
        selectedTab == UserSpaceTab.Collections -> userSpaceViewModel.collections
        else -> userSpaceViewModel.videos
    }
    val contentCount = when {
        selectedArchive -> userSpaceViewModel.tvSpaceArchives.size
        selectedTab == UserSpaceTab.Articles -> userSpaceViewModel.tvSpaceArticles.size
        selectedTab == UserSpaceTab.Dynamics -> userSpaceViewModel.tvSpaceDynamics.size
        selectedTab == UserSpaceTab.Collections ->
            userSpaceViewModel.tvSpaceCollections.size + userSpaceViewModel.tvSpaceSeries.size

        else -> userSpaceViewModel.tvSpaceVideos.size
    }
    val isLoading = listState.loading
    val error = listState.error
    val hasMore = listState.hasMore
    val focusableStatus = error != null || (hasMore && !isLoading)

    // 监听关注状态变化
    val followStateMap by FollowStateManager.followStateMap.collectAsState()

    // 当关注状态map变化时，更新当前用户的关注状态
    LaunchedEffect(followStateMap, userSpaceViewModel.upMid) {
        if (userSpaceViewModel.upMid > 0) {
            FollowStateManager.getFollowState(userSpaceViewModel.upMid)?.let { following ->
                isFollowing = following
            }
        }
    }

    val tabFocusRequester = remember { FocusRequester() }
    val lazyGridState = rememberLazyGridState()
    val archiveGridState = rememberLazyGridState()
    val dynamicGridState = rememberLazyStaggeredGridState()
    val dynamicColumnCount = 3
    val dynamicFocusRequesters = remember { mutableMapOf<Int, FocusRequester>() }
    val dynamicFocusRequesterFor: (Int) -> FocusRequester = { index ->
        dynamicFocusRequesters.getOrPut(index) { FocusRequester() }
    }
    // 动态卡片是唯一的上下移动来源：-1 表示焦点不在卡片上（例如在底部「加载更多」）
    var focusedDynamicIndex by remember { mutableIntStateOf(-1) }
    var dynamicViewportHeightPx by remember { mutableIntStateOf(0) }
    var pendingVerticalMove by remember { mutableStateOf<GridItemBox?>(null) }
    var pendingVerticalDirection by remember { mutableStateOf<GridVertical?>(null) }
    val tabItems = remember { UserSpaceTab.entries.map(::UpSpaceTopNavItem) }
    val navSwitchMode by Prefs.navSwitchModeFlow.collectAsStateLazily { Prefs.navSwitchMode }
    val density = LocalDensity.current
    val dynamicVerticalSpacingPx = with(density) { DynamicCardSpacing.roundToPx() }

    fun dynamicItemBoxes(): List<GridItemBox> = dynamicGridState.layoutInfo.visibleItemsInfo.map {
        GridItemBox(
            index = it.index,
            left = it.offset.x,
            top = it.offset.y,
            right = it.offset.x + it.size.width,
            bottom = it.offset.y + it.size.height
        )
    }

    // 上下移动时目标卡可能还没组合出来：先滚过「当前卡高 + 间距」，等 layout 更新后再把焦点交给同列那张卡
    LaunchedEffect(dynamicGridState) {
        snapshotFlow { dynamicItemBoxes() }.collect { boxes ->
            val anchor = pendingVerticalMove ?: return@collect
            val direction = pendingVerticalDirection ?: return@collect
            val target = sameColumnNeighbor(anchor, boxes, direction) ?: return@collect
            if (runCatching { dynamicFocusRequesterFor(target).requestFocus() }.isSuccess) {
                pendingVerticalMove = null
                pendingVerticalDirection = null
            }
        }
    }

    val onDynamicVerticalKey: (androidx.compose.ui.input.key.KeyEvent) -> Boolean = verticalKey@{ event ->
        val nativeEvent = event.nativeKeyEvent
        val direction = when (nativeEvent.keyCode) {
            AndroidKeyEvent.KEYCODE_DPAD_DOWN -> GridVertical.Down
            AndroidKeyEvent.KEYCODE_DPAD_UP -> GridVertical.Up
            else -> null
        }
        if (nativeEvent.action != AndroidKeyEvent.ACTION_DOWN ||
            direction == null ||
            focusedDynamicIndex < 0
        ) {
            false
        } else {
            // 目标卡一直没组合出来时，残留的锚点会在下一次布局变化时劫持焦点，这里先作废
            pendingVerticalMove = null
            pendingVerticalDirection = null
            val boxes = dynamicItemBoxes()
            val current = boxes.firstOrNull { it.index == focusedDynamicIndex } ?: return@verticalKey false
            val canScroll = when (direction) {
                GridVertical.Down -> dynamicGridState.canScrollForward
                GridVertical.Up -> dynamicGridState.canScrollBackward
            }
            when (
                val move = verticalMove(
                    current = current,
                    items = boxes,
                    viewportHeight = dynamicViewportHeightPx,
                    direction = direction,
                    canScroll = canScroll
                )
            ) {
                is VerticalMove.Focus ->
                    runCatching { dynamicFocusRequesterFor(move.index).requestFocus() }.isSuccess

                VerticalMove.ScrollToReveal -> {
                    pendingVerticalMove = null
                    pendingVerticalDirection = null
                    scope.launch {
                        dynamicGridState.scrollBy(
                            (revealScrollStep(dynamicViewportHeightPx) * direction.step).toFloat()
                        )
                    }
                    true
                }

                VerticalMove.ScrollToNeighbour -> {
                    // 滚过「卡高 + 间距」，让同列下一张卡落到当前卡的位置，再由 layout 变化复焦
                    val step = verticalScrollStep(current, dynamicVerticalSpacingPx)
                    pendingVerticalMove = current.copy(
                        top = current.top - step * direction.step,
                        bottom = current.bottom - step * direction.step
                    )
                    pendingVerticalDirection = direction
                    scope.launch { dynamicGridState.scrollBy((step * direction.step).toFloat()) }
                    true
                }

                VerticalMove.Delegate -> false
            }
        }
    }

    LaunchedEffect(Unit) {
        tabFocusRequester.requestFocusWithRetry()
    }
    val firstArchiveFocusRequester = remember { FocusRequester() }
    var openedArchiveKey by remember { mutableStateOf<String?>(null) }
    // 返回合集列表后要复原的卡片 key；合集详情是独立网格，列表会重新组合
    var archiveReturnKey by remember { mutableStateOf<String?>(null) }
    val collectionFocusRequesters = remember { mutableMapOf<String, FocusRequester>() }
    val collectionFocusRequesterFor: (String) -> FocusRequester = { key ->
        collectionFocusRequesters.getOrPut(key) { FocusRequester() }
    }

    // 打开合集/系列后把焦点放到第一个稿件卡片上，而不是头部按钮
    val firstArchiveKey = userSpaceViewModel.tvSpaceArchives.firstOrNull()?.stableItemKey()
    LaunchedEffect(selectedArchive, firstArchiveKey) {
        if (!selectedArchive || firstArchiveKey == null) return@LaunchedEffect
        firstArchiveFocusRequester.requestFocusWithRetry()
    }

    val closeArchiveAndRestoreFocus: () -> Unit = {
        currentIndex = 0
        archiveReturnKey = openedArchiveKey
        userSpaceViewModel.closeArchives()
    }

    LaunchedEffect(archiveReturnKey, selectedArchive) {
        val key = archiveReturnKey ?: return@LaunchedEffect
        if (selectedArchive) return@LaunchedEffect
        archiveReturnKey = null
        val index = archiveFocusIndex(
            key = key,
            seasonIds = userSpaceViewModel.tvSpaceCollections.map { it.seasonId },
            seriesIds = userSpaceViewModel.tvSpaceSeries.map { it.seriesId }
        )
        if (index < 0) return@LaunchedEffect
        lazyGridState.scrollToItem(index)
        collectionFocusRequesterFor(key).requestFocusWithRetry()
    }

    BackHandler(enabled = selectedTab == UserSpaceTab.Collections && selectedArchive) {
        closeArchiveAndRestoreFocus()
    }

    val addFollow: (afterModify: (success: Boolean) -> Unit) -> Unit = { afterModify ->
        scope.launch(Dispatchers.IO) {
            val userMid = userSpaceViewModel.upMid
            logger.fInfo { "Add follow to user $userMid" }
            val success = userRepository.followUser(
                mid = userMid,
                preferApiType = Prefs.apiType
            )
            logger.fInfo { "Add follow result: $success" }
            // 更新缓存状态
            if (success) {
                FollowStateManager.updateFollowState(userMid, true)
            }
            afterModify(success)
        }
    }

    val delFollow: (afterModify: (success: Boolean) -> Unit) -> Unit = { afterModify ->
        scope.launch(Dispatchers.IO) {
            val userMid = userSpaceViewModel.upMid
            logger.fInfo { "Del follow to user $userMid" }
            val success = userRepository.unfollowUser(
                mid = userMid,
                preferApiType = Prefs.apiType
            )
            logger.fInfo { "Del follow result: $success" }
            // 更新缓存状态
            if (success) {
                FollowStateManager.updateFollowState(userMid, false)
            }
            afterModify(success)
        }
    }

    LaunchedEffect(Unit) {
        val intent = (context as Activity).intent
        if (intent.hasExtra("mid")) {
            val mid = intent.getLongExtra("mid", 0)
            val name = intent.getStringExtra("name") ?: ""
            val face = intent.getStringExtra("face") ?: ""
            userSpaceViewModel.initialize(mid, name, face)
            scope.launch(Dispatchers.IO) {
                runCatching {
                    val userInfo = userRepository.getUserCardInfo(
                        mid = mid
                    )
                    withContext(Dispatchers.Main) {
                        userSpaceViewModel.upName = userInfo.card.name
                        userSpaceViewModel.upFace = userInfo.card.face
                        userSpaceViewModel.sign = userInfo.card.sign.replace("\n", "")
                        userSpaceViewModel.fans = userInfo.card.fans
                        userSpaceViewModel.likeNum = userInfo.likeNum
                        userSpaceViewModel.friend = userInfo.card.attention
                        userSpaceViewModel.currentLevel = userInfo.card.levelInfo.currentLevel
                        userSpaceViewModel.spacestaText = if(userInfo.card.spacesta == -2) "该账号封禁中" else if (userInfo.card.spacesta == 0) "" else ""
                        if (Prefs.isLogin) {
                            FollowStateManager.updateFollowState(mid, userInfo.following)
                            isFollowing = userInfo.following
                            showFollowButton = true
                        }
                    }
                }.onFailure {
                    logger.fInfo { "Get user info failed: ${it.stackTraceToString()}" }
                }
            }
        } else {
            context.finish()
        }
    }

    Scaffold(
        modifier = modifier
            .onPreviewKeyEvent {
                val isDpadCenter =
                    listOf(Key.Enter, Key.DirectionCenter).contains(it.key)

                if (isDpadCenter && it.type == KeyEventType.KeyDown) {
                    isLongPress = it.nativeKeyEvent.repeatCount > 0
                }
                false
            },
        topBar = {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 24.dp, top = 12.dp, bottom = 12.dp, end = 24.dp),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(24.dp)
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // 头像与昵称/数据同一行，签名另起一行从最左边开始
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // 接口给的头像可能已带 @ 后缀或 // 前缀，先归一化
                            val face = userSpaceViewModel.upFace
                            val originalAvatarUrl = remember(face) {
                                face.takeIf { it.isNotBlank() }?.let {
                                    if (it.startsWith("//")) "https:$it" else it
                                }
                            }
                            // 缩略图优先；解不出来时退回原图，别让头像一直空着
                            var useOriginalAvatar by remember(face) { mutableStateOf(false) }
                            val avatarUrl = originalAvatarUrl?.let {
                                if (useOriginalAvatar) it
                                else it.substringBefore('@') + "@128w_128h_1c_1s.webp"
                            }
                            // 首字兜底垫在底层：图片没加载出来时头部也不会空一块
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .background(
                                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = userSpaceViewModel.upName.take(1),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                                )
                                // model 为 null 时不发请求，只露出底下的首字
                                AsyncImage(
                                    modifier = Modifier.fillMaxSize(),
                                    model = avatarUrl,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    onError = { useOriginalAvatar = true }
                                )
                            }
                            Column {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = userSpaceViewModel.upName,
                                        style = MaterialTheme.typography.titleLarge.copy(
                                            lineHeight = 28.sp
                                        )
                                    )
                                    // 关注按钮
                                    if (showFollowButton) {
                                        UpSpaceHeaderChip(
                                            onClick = {
                                                if (isFollowing) {
                                                    delFollow { success ->
                                                        scope.launch(Dispatchers.Main) {
                                                            if (success) "已取消关注".toast(context)
                                                            else "取消关注失败".toast(context)
                                                        }
                                                    }
                                                } else {
                                                    addFollow { success ->
                                                        scope.launch(Dispatchers.Main) {
                                                            if (success) "关注成功".toast(context)
                                                            else "关注失败".toast(context)
                                                        }
                                                    }
                                                }
                                            }
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(
                                                    horizontal = 8.dp,
                                                    vertical = 3.dp
                                                ),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                                            ) {
                                                Icon(
                                                    modifier = Modifier.size(12.dp),
                                                    imageVector = if (isFollowing) Icons.Rounded.Done else Icons.Rounded.Add,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.onSurface
                                                )
                                                Text(
                                                    text = stringResource(
                                                        if (isFollowing) R.string.video_info_followed
                                                        else R.string.video_info_follow
                                                    ),
                                                    color = MaterialTheme.colorScheme.onSurface,
                                                    fontSize = 12.sp
                                                )
                                            }
                                        }
                                    }
                                }
                                Text(
                                    modifier = Modifier.offset(y = 1.dp),
                                    text = listOf(
                                        stringResource(
                                            R.string.user_info_level,
                                            userSpaceViewModel.currentLevel
                                        ),
                                        stringResource(
                                            R.string.friend_count,
                                            userSpaceViewModel.friend.toCountText()
                                        ),
                                        stringResource(
                                            R.string.fans_count,
                                            userSpaceViewModel.fans.toCountText()
                                        ),
                                        stringResource(
                                            R.string.like_count,
                                            userSpaceViewModel.likeNum.toCountText()
                                        )
                                    ).joinToString(" · "),
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        lineHeight = 16.sp
                                    )
                                )
                            }
                        }
                        Text(
                            text = userSpaceViewModel.sign,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                            style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 18.sp),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = stringResource(R.string.load_data_count, contentCount),
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                        AnimatedVisibility(
                            visible = selectedTab == UserSpaceTab.Videos &&
                                !userSpaceViewModel.videos.hasMore
                        ) {
                            Text(
                                text = stringResource(R.string.load_data_no_more),
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }
                    }
                    // up账号状态
                    if (userSpaceViewModel.spacestaText.isNotBlank()) {
                        Surface(
                            modifier = Modifier.align(Alignment.Top),
                            colors = SurfaceDefaults.colors(
                                containerColor = Color.Transparent,
                                contentColor = Color.Yellow.copy(alpha = 0.8f)
                            ),
                            shape = MaterialTheme.shapes.small
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(1.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    modifier = Modifier.scale(0.8f),
                                    imageVector = Icons.Rounded.Warning,
                                    contentDescription = null,
                                    tint = Color.Yellow
                                )
                                Text(text = userSpaceViewModel.spacestaText)
                            }
                        }
                    }
                }
                TopNav(
                    paddingTop = 0.dp,
                    items = tabItems,
                    initialSelectedItem = tabItems[UserSpaceTab.entries.indexOf(selectedTab)],
                    navSwitchMode = navSwitchMode,
                    tabFocusRequester = tabFocusRequester,
                    onSelectedChanged = { item ->
                        val tab = (item as UpSpaceTopNavItem).tab
                        if (selectedTab != tab) {
                            selectedTab = tab
                            currentIndex = 0
                            focusedDynamicIndex = -1
                            val shouldLoad = when (tab) {
                                UserSpaceTab.Videos -> userSpaceViewModel.tvSpaceVideos.isEmpty() &&
                                    userSpaceViewModel.videos.error == null

                                UserSpaceTab.Articles -> userSpaceViewModel.tvSpaceArticles.isEmpty() &&
                                    userSpaceViewModel.articles.error == null

                                UserSpaceTab.Dynamics -> userSpaceViewModel.tvSpaceDynamics.isEmpty() &&
                                    userSpaceViewModel.dynamics.error == null

                                UserSpaceTab.Collections -> userSpaceViewModel.tvSpaceCollections.isEmpty() &&
                                    userSpaceViewModel.tvSpaceSeries.isEmpty() &&
                                    userSpaceViewModel.collections.error == null
                            }
                            if (shouldLoad) userSpaceViewModel.load(tab)
                            scope.launch {
                                if (tab == UserSpaceTab.Dynamics) {
                                    dynamicGridState.scrollToItem(0)
                                } else {
                                    lazyGridState.scrollToItem(0)
                                }
                            }
                        }
                    }
                )
            }
        }
    ) { innerPadding ->
        val contentModifier = Modifier.padding(innerPadding)
        val listModifier = Modifier
            .focusRestorer()
            .onPreviewKeyEvent {
                if (it.key == Key.Back && !selectedArchive) {
                    if (it.type == KeyEventType.KeyUp) {
                        tabFocusRequester.requestFocus()
                    }
                    true
                } else {
                    false
                }
            }
        ProvideListBringIntoViewSpec(padding = 24.dp) {
            when {
                selectedTab == UserSpaceTab.Dynamics -> LazyVerticalStaggeredGrid(
                    modifier = contentModifier
                        .fillMaxSize()
                        .then(listModifier)
                        .onSizeChanged { dynamicViewportHeightPx = it.height }
                        .onPreviewKeyEvent(onDynamicVerticalKey),
                    columns = StaggeredGridCells.Fixed(dynamicColumnCount),
                    state = dynamicGridState,
                    contentPadding = PaddingValues(24.dp),
                    verticalItemSpacing = DynamicCardSpacing,
                    horizontalArrangement = Arrangement.spacedBy(24.dp)
                ) {
                    staggeredItemsIndexed(
                        items = userSpaceViewModel.tvSpaceDynamics,
                        key = { index, dynamic -> dynamic.id ?: "dynamic-$index" }
                    ) { index, dynamic ->
                        SpaceDynamicCard(
                            dynamic = dynamic,
                            modifier = Modifier.focusRequester(dynamicFocusRequesterFor(index)),
                            onClick = {
                                val video = dynamic.video
                                when (dynamic.cardAction()) {
                                    DynamicCardAction.Article -> dynamic.id?.toLongOrNull()
                                        ?.let { ArticleActivity.actionStart(context, it) }

                                    DynamicCardAction.Video -> video?.let { playVideo ->
                                        videoInfoRepository.setPreloadedVideoList(
                                            userSpaceViewModel.tvSpaceDynamics.mapNotNull { item ->
                                                item.video?.let { videoItem ->
                                                    VideoCardData(
                                                        avid = videoItem.aid,
                                                        title = videoItem.title,
                                                        cover = videoItem.cover,
                                                        upName = "",
                                                        play = videoItem.playCount,
                                                        danmaku = videoItem.danmakuCount,
                                                        time = videoItem.durationSeconds * 1000L,
                                                        pubTime = item.author.pubTime
                                                    )
                                                }
                                            }
                                        )
                                        VideoInfoActivity.actionStart(
                                            context = context,
                                            aid = playVideo.aid,
                                            proxyArea = ProxyArea.checkProxyArea(playVideo.title)
                                        )
                                    }

                                    DynamicCardAction.Detail -> dynamic.id?.let {
                                        DynamicDetailActivity.actionStart(context, it)
                                    }
                                }
                            },
                            onFocus = {
                                focusedDynamicIndex = index
                                currentIndex = index
                                if (index + 6 > userSpaceViewModel.tvSpaceDynamics.size) {
                                    userSpaceViewModel.load(UserSpaceTab.Dynamics)
                                }
                            }
                        )
                    }

                    if (isLoading || error != null || hasMore || contentCount == 0) {
                        item(span = StaggeredGridItemSpan.FullLine) {
                            // 焦点落在底部状态上时不再按卡片处理，避免上下键被卡片逻辑劫持
                            Box(
                                modifier = Modifier.onFocusChanged {
                                    if (it.hasFocus) focusedDynamicIndex = -1
                                }
                            ) {
                                SpaceContentStatus(
                                    loading = isLoading,
                                    error = error,
                                    hasMore = hasMore,
                                    onRetry = { userSpaceViewModel.load(UserSpaceTab.Dynamics) }
                                )
                            }
                        }
                    }
                }

                // 合集/系列详情：头部固定在网格上方，网格只放稿件
                selectedArchive -> Column(modifier = contentModifier.fillMaxSize()) {
                    ArchiveHeader(
                        title = archiveTitle,
                        subscribed = userSpaceViewModel.collectionSubscribed,
                        showSubscribe = userSpaceViewModel.selectedCollection != null && Prefs.isLogin,
                        onFocused = { currentIndex = 0 },
                        onBack = closeArchiveAndRestoreFocus,
                        onToggleSubscribe = { userSpaceViewModel.toggleCollectionSubscription() }
                    )
                    LazyVerticalGrid(
                        modifier = Modifier
                            .fillMaxSize()
                            .then(listModifier)
                            .blockDownFocusExitAtGridEnd(
                                currentIndexProvider = { currentIndex },
                                itemCount = gridFocusItemCount(
                                    contentItemCount = contentCount,
                                    footerFocusable = focusableStatus,
                                    columnCount = 4
                                ),
                                columnCount = 4
                            ),
                        columns = GridCells.Fixed(4),
                        state = archiveGridState,
                        contentPadding = PaddingValues(24.dp),
                        verticalArrangement = Arrangement.spacedBy(24.dp),
                        horizontalArrangement = Arrangement.spacedBy(24.dp)
                    ) {
                        itemsIndexed(userSpaceViewModel.tvSpaceArchives, key = { _, video ->
                            video.stableItemKey()
                        }) { index, video ->
                            SmallVideoCard(
                                modifier = if (index == 0) {
                                    Modifier.focusRequester(firstArchiveFocusRequester)
                                } else {
                                    Modifier
                                },
                                data = video,
                                onClick = {
                                    if (!isLongPress) {
                                        videoInfoRepository.setPreloadedVideoList(userSpaceViewModel.tvSpaceArchives)
                                        VideoInfoActivity.actionStart(
                                            context = context,
                                            aid = video.avid,
                                            proxyArea = ProxyArea.checkProxyArea(video.title)
                                        )
                                    }
                                },
                                onFocus = {
                                    currentIndex = index
                                    if (index + 12 > userSpaceViewModel.tvSpaceArchives.size) {
                                        userSpaceViewModel.loadArchives()
                                    }
                                }
                            )
                        }

                        if (isLoading || error != null || hasMore || contentCount == 0) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                SpaceContentStatus(
                                    loading = isLoading,
                                    error = error,
                                    hasMore = hasMore,
                                    onRetry = { userSpaceViewModel.loadArchives() }
                                )
                            }
                        }
                    }
                }

                else -> LazyVerticalGrid(
                    modifier = contentModifier
                        .then(listModifier)
                        .blockDownFocusExitAtGridEnd(
                            currentIndexProvider = { currentIndex },
                            itemCount = gridFocusItemCount(
                                contentItemCount = contentCount,
                                footerFocusable = focusableStatus,
                                columnCount = 4
                            ),
                            columnCount = 4
                        ),
                    columns = GridCells.Fixed(4),
                    state = lazyGridState,
                    contentPadding = PaddingValues(24.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp),
                    horizontalArrangement = Arrangement.spacedBy(24.dp)
                ) {
                    when (selectedTab) {
                        UserSpaceTab.Videos -> itemsIndexed(
                            items = userSpaceViewModel.tvSpaceVideos,
                            key = { index, video -> "$index-${video.stableItemKey()}" }
                        ) { index, video ->
                            SmallVideoCard(
                                data = video,
                                onClick = {
                                    if (!isLongPress) {
                                        videoInfoRepository.setPreloadedVideoList(userSpaceViewModel.tvSpaceVideos)
                                        VideoInfoActivity.actionStart(
                                            context = context,
                                            aid = video.avid,
                                            proxyArea = ProxyArea.checkProxyArea(video.title)
                                        )
                                    }
                                },
                                onFocus = {
                                    currentIndex = index
                                    if (index + 12 > userSpaceViewModel.tvSpaceVideos.size) {
                                        userSpaceViewModel.update()
                                    }
                                }
                            )
                        }

                        UserSpaceTab.Articles -> itemsIndexed(
                            userSpaceViewModel.tvSpaceArticles,
                            key = { _, article -> article.id }
                        ) { index, article ->
                            SpaceArticleCard(
                                article = article,
                                onClick = { ArticleActivity.actionStart(context, article.id) },
                                onFocus = {
                                    currentIndex = index
                                    if (index + 12 > userSpaceViewModel.tvSpaceArticles.size) {
                                        userSpaceViewModel.load(UserSpaceTab.Articles)
                                    }
                                }
                            )
                        }

                        // 动态走独立的瀑布流容器，合集详情走上方分支
                        UserSpaceTab.Dynamics -> Unit
                        UserSpaceTab.Collections -> {
                            itemsIndexed(
                                items = userSpaceViewModel.tvSpaceCollections,
                                key = { _, collection -> "season-${collection.seasonId}" }
                            ) { index, collection ->
                                SpaceCollectionCard(
                                    title = collection.title,
                                    cover = collection.cover,
                                    videoCount = collection.videoCount,
                                    typeLabel = "合集",
                                    updateTime = collection.updateTime,
                                    modifier = Modifier.focusRequester(
                                        collectionFocusRequesterFor("season-${collection.seasonId}")
                                    ),
                                    onClick = {
                                        openedArchiveKey = "season-${collection.seasonId}"
                                        currentIndex = 0
                                        scope.launch { archiveGridState.scrollToItem(0) }
                                        userSpaceViewModel.openCollection(collection)
                                    },
                                    onFocus = {
                                        currentIndex = index
                                        if (index + 12 > userSpaceViewModel.tvSpaceCollections.size) {
                                            userSpaceViewModel.load(UserSpaceTab.Collections)
                                        }
                                    }
                                )
                            }
                            itemsIndexed(
                                items = userSpaceViewModel.tvSpaceSeries,
                                key = { _, series -> "series-${series.seriesId}" }
                            ) { index, series ->
                                SpaceCollectionCard(
                                    title = series.title,
                                    cover = series.cover,
                                    videoCount = series.videoCount,
                                    typeLabel = "系列",
                                    updateTime = series.updateTime,
                                    modifier = Modifier.focusRequester(
                                        collectionFocusRequesterFor("series-${series.seriesId}")
                                    ),
                                    onClick = {
                                        openedArchiveKey = "series-${series.seriesId}"
                                        currentIndex = 0
                                        scope.launch { archiveGridState.scrollToItem(0) }
                                        userSpaceViewModel.openSeries(series)
                                    },
                                    onFocus = {
                                        currentIndex = userSpaceViewModel.tvSpaceCollections.size + index
                                        if (index + 12 > userSpaceViewModel.tvSpaceSeries.size) {
                                            userSpaceViewModel.load(UserSpaceTab.Collections)
                                        }
                                    }
                                )
                            }
                        }
                    }

                    if (isLoading || error != null || hasMore || contentCount == 0) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            SpaceContentStatus(
                                loading = isLoading,
                                error = error,
                                hasMore = hasMore,
                                onRetry = { userSpaceViewModel.load(selectedTab) }
                            )
                        }
                    }
                }
            }
        }
    }
}

//https://i2.hdslb.com/bfs/face/ea9b2fd60b04b123d0b48477838f60532b6271cd.jpg
@Preview(uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun UpFacePreview() {
    BVTheme {
        AsyncImage(
            modifier = Modifier
                .size(80.dp)
                .clip(CircleShape),
            model = "https://i2.hdslb.com/bfs/face/ea9b2fd60b04b123d0b48477838f60532b6271cd.jpg@80h_80w_1c.webp",
            contentDescription = null,
            contentScale = ContentScale.Crop
        )
    }
}

private fun Int.toCountText(): String =
    if (this >= 10000) String.format("%.2f万", this / 10000.0) else toString()

private class UpSpaceTopNavItem(val tab: UserSpaceTab) : TopNavItem {
    override fun getDisplayName(context: Context): String = tab.label
}

private val DynamicCardSpacing = 20.dp

/**
 * 合集/系列详情头部：返回、标题、订阅。固定在网格上方，不随稿件滚动。
 */
@Composable
private fun ArchiveHeader(
    title: String,
    subscribed: Boolean,
    showSubscribe: Boolean,
    onFocused: () -> Unit,
    onBack: () -> Unit,
    onToggleSubscribe: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 返回与订阅按钮沿用顶部关注按钮的聚焦样式（底色 + 聚焦描边）
        UpSpaceHeaderChip(
            modifier = Modifier.onFocusChanged { if (it.isFocused) onFocused() },
            onClick = onBack
        ) {
            Icon(
                modifier = Modifier
                    .padding(horizontal = 10.dp, vertical = 7.dp)
                    .size(20.dp),
                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = "返回合集和系列"
            )
        }
        Text(
            modifier = Modifier.weight(1f, fill = false),
            text = title,
            style = MaterialTheme.typography.titleLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        // 只有合集支持订阅，系列没有订阅
        if (showSubscribe) {
            UpSpaceHeaderChip(
                modifier = Modifier.onFocusChanged { if (it.isFocused) onFocused() },
                onClick = onToggleSubscribe
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        modifier = Modifier.size(18.dp),
                        imageVector = if (subscribed) Icons.Rounded.Done else Icons.Rounded.Add,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (subscribed) "已订阅" else "订阅",
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 15.sp
                    )
                }
            }
        }
    }
}

/**
 * 合集/系列详情头部的胶囊按钮，聚焦样式与顶部关注按钮保持一致。
 */
@Composable
private fun UpSpaceHeaderChip(
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    content: @Composable () -> Unit
) {
    Surface(
        modifier = modifier,
        onClick = onClick,
        colors = ClickableSurfaceDefaults.colors(
            containerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f),
            focusedContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f),
            pressedContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
        ),
        shape = ClickableSurfaceDefaults.shape(shape = MaterialTheme.shapes.small),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(
                    width = 2.dp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                ),
                shape = MaterialTheme.shapes.small
            )
        )
    ) {
        content()
    }
}
