package dev.aaa1115910.bv.tv.screens.main.home

import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.People
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import dev.aaa1115910.biliapi.entity.FavoriteResourceType
import dev.aaa1115910.biliapi.entity.user.DynamicItem
import dev.aaa1115910.biliapi.entity.user.DynamicVideo
import dev.aaa1115910.bv.R as SharedR
import dev.aaa1115910.bv.tv.component.DynamicActionMenu
import dev.aaa1115910.bv.tv.component.LoadingTip
import dev.aaa1115910.bv.entity.carddata.VideoCardData
import dev.aaa1115910.bv.entity.proxy.ProxyArea
import dev.aaa1115910.bv.tv.R
import dev.aaa1115910.bv.tv.activities.user.FollowActivity
import dev.aaa1115910.bv.tv.activities.video.SeasonInfoActivity
import dev.aaa1115910.bv.tv.activities.video.VideoInfoActivity
import dev.aaa1115910.bv.tv.component.TopNav
import dev.aaa1115910.bv.tv.component.TopNavItem
import dev.aaa1115910.bv.tv.component.VideoActionMenu
import dev.aaa1115910.bv.tv.component.CardActionMenuItem
import dev.aaa1115910.bv.tv.component.VideoActionMenuExtraItem
import dev.aaa1115910.bv.tv.component.videocard.SmallVideoCard
import dev.aaa1115910.bv.tv.screens.user.DynamicWaterfall
import dev.aaa1115910.bv.tv.screens.user.SpaceContentStatus
import dev.aaa1115910.bv.tv.screens.user.openDynamic
import dev.aaa1115910.bv.tv.util.blockDownFocusExitAtGridEnd
import dev.aaa1115910.bv.tv.util.gridFocusItemCount
import dev.aaa1115910.bv.tv.util.onMenuKeyDown
import dev.aaa1115910.bv.tv.util.ProvideListBringIntoViewSpec
import dev.aaa1115910.bv.tv.util.redirectLeftFocusAtLeftEdge
import dev.aaa1115910.bv.tv.util.requestFocusWithRetry
import dev.aaa1115910.bv.repository.VideoInfoRepository
import dev.aaa1115910.bv.util.Prefs
import dev.aaa1115910.bv.util.collectAsStateLazily
import dev.aaa1115910.bv.viewmodel.home.DynamicViewModel
import dev.aaa1115910.bv.viewmodel.home.FollowedUpPanelViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

private enum class DynamicTab(private val displayName: String) : TopNavItem {
    Video("视频"),
    All("全部");

    override fun getDisplayName(context: Context): String = displayName
}

@Composable
fun DynamicsScreen(
    modifier: Modifier = Modifier,
    lazyGridState: LazyGridState = rememberLazyGridState(),
    allState: LazyStaggeredGridState = rememberLazyStaggeredGridState(),
    dynamicViewModel: DynamicViewModel = koinViewModel(),
    panelViewModel: FollowedUpPanelViewModel = koinViewModel()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val videoInfoRepository: VideoInfoRepository = koinInject()
    val tabItems = remember { DynamicTab.entries.toList() }
    var selectedTab by remember { mutableStateOf(DynamicTab.Video) }
    val tabFocusRequester = remember { FocusRequester() }
    val panelFocusRequester = remember { FocusRequester() }
    var contentHasFocus by remember { mutableStateOf(false) }
    val navSwitchMode by Prefs.navSwitchModeFlow.collectAsStateLazily { Prefs.navSwitchMode }
    val showPanelPref by Prefs.showFollowedUpsInDynamicsFlow.collectAsStateLazily {
        Prefs.showFollowedUpsInDynamics
    }
    // 已关注 UP 列表来自账号的「关注」，未登录时既取不到数据也不该占位
    val showPanel = showPanelPref && dynamicViewModel.isLogin
    val columns = if (showPanel) 3 else 4

    // 右侧两个 tab 平时各自保留滚动位置；换数据源（选中/取消 UP、刷新后重置回「全部」）要回到顶部。
    // 只有一个 tab 在组合中，所以记一个版本号让两个 tab 各自消费一次：
    // 可见的立刻消费，隐藏的等切过去时再消费（不回顶会被当成「刷新没生效」）
    var feedResetVersion by remember { mutableIntStateOf(0) }
    var videoResetConsumed by remember { mutableIntStateOf(0) }
    var allResetConsumed by remember { mutableIntStateOf(0) }

    var currentFocusedIndex by remember { mutableIntStateOf(-1) }
    val shouldLoadMore by remember {
        derivedStateOf { dynamicViewModel.currentVideos.isNotEmpty() && currentFocusedIndex + 12 > dynamicViewModel.currentVideos.size }
    }
    val onClickVideo: (DynamicVideo) -> Unit = { dynamic ->
        val proxyArea = ProxyArea.checkProxyArea(dynamic.title)
        val hasSeasonHint = dynamic.seasonId != null || dynamic.epid != null

        videoInfoRepository.setPreloadedVideoList(
            dynamicViewModel.currentVideos.map { item ->
                VideoCardData(
                    avid = item.aid,
                    title = item.title,
                    cover = item.cover,
                    upName = item.author,
                    upId = item.authorId,
                    play = item.play,
                    danmaku = item.danmaku,
                    time = item.duration * 1000L,
                    pubTime = item.pubTime
                )
            }
        )

        if (hasSeasonHint) {
            SeasonInfoActivity.actionStart(
                context = context,
                epId = dynamic.epid,
                seasonId = dynamic.seasonId,
                proxyArea = proxyArea
            )
        } else {
            VideoInfoActivity.actionStart(
                context = context,
                aid = dynamic.aid,
                proxyArea = proxyArea
            )
        }
    }

    // 长按菜单状态
    var showVideoActionMenu by remember { mutableStateOf(false) }
    var menuAid by remember { mutableLongStateOf(0L) }
    var menuUpId by remember { mutableLongStateOf(0L) }
    var menuUpName by remember { mutableStateOf("") }
    var menuUpFace by remember { mutableStateOf("") }

    val onLongClickVideo: (DynamicVideo) -> Unit = { dynamic ->
        menuAid = dynamic.aid
        menuUpId = dynamic.authorId
        menuUpName = dynamic.author
        menuUpFace = dynamic.authorFace
        showVideoActionMenu = true
    }

    // 「全部」tab 的长按菜单
    var menuDynamic by remember { mutableStateOf<DynamicItem?>(null) }

    //不能直接使用 LaunchedEffect(currentFocusedIndex)，会导致整个页面重组
    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore) {
            if (dynamicViewModel.selectedUp == null) {
                scope.launch(Dispatchers.IO) {
                    dynamicViewModel.loadMoreVideo()
                }
            } else {
                dynamicViewModel.loadMoreForSelection()
            }
        }
    }

    // 切到「全部」时由 ViewModel 决定该拉关注流还是选中 UP 的下一页；已加载过的不会重复请求
    LaunchedEffect(selectedTab) {
        if (selectedTab == DynamicTab.All) dynamicViewModel.ensureCurrentFeedLoaded()
    }

    // 内容区按返回：直接回到顶部二级 Tab（已关注 UP 面板用左键进入）
    BackHandler(enabled = contentHasFocus) {
        tabFocusRequester.requestFocus()
    }

    Column(modifier = modifier.fillMaxSize()) {
        TopNav(
            paddingTop = 0.dp,
            items = tabItems,
            useSmallSize = true,
            initialSelectedItem = selectedTab,
            navSwitchMode = navSwitchMode,
            tabFocusRequester = tabFocusRequester,
            onSelectedChanged = { selectedTab = it as DynamicTab },
            // 面板在左侧时，二级 Tab 第一项按 ← 进面板的「全部」，和内容区最左列按 ← 的落点一致
            onLeftKeyEvent = {
                if (showPanel) panelFocusRequester.requestFocus()
            }
        )
        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            if (showPanel) {
                FollowedUpPanel(
                    modifier = Modifier.fillMaxHeight(),
                    viewModel = panelViewModel,
                    selectedMid = dynamicViewModel.selectedUp,
                    focusRequester = panelFocusRequester,
                    onSelectedUpChange = { mid ->
                        dynamicViewModel.selectUp(mid)
                        // 换数据源：右侧两个 tab 的列表都要回到顶部，由各自 tab 消费这个版本号
                        feedResetVersion++
                    },
                    onBack = { tabFocusRequester.requestFocus() },
                    // 面板最上面的刷新按钮再按 ↑：交回二级 Tab 的当前选中项（topNav 里共享的 requester 挂在选中项上）
                    onFocusSelectedTab = { tabFocusRequester.requestFocus() }
                )
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .onFocusChanged { contentHasFocus = it.hasFocus }
            ) {
                if (dynamicViewModel.isLogin) {
                    when (selectedTab) {
                        DynamicTab.Video -> {
                            // 消费「回到顶部」请求。挂在本 tab 的组合里：列表此时已布局，
                            // scrollToItem 的「等首次布局」不会一直挂起，且会强制重measure
                            LaunchedEffect(feedResetVersion) {
                                if (videoResetConsumed == feedResetVersion) return@LaunchedEffect
                                val version = feedResetVersion
                                lazyGridState.scrollToItem(0)
                                videoResetConsumed = version
                            }
                            val padding = dimensionResource(R.dimen.grid_padding)
                            val spacedBy = dimensionResource(R.dimen.grid_spacedBy)
                            // 面板可见时，面板内边距 5dp + 网格起始 8dp = 13dp，正好是原来 26dp 的一半
                            val gridContentPadding = if (showPanel) {
                                PaddingValues(
                                    start = padding / 2,
                                    top = padding,
                                    end = padding,
                                    bottom = padding
                                )
                            } else {
                                PaddingValues(padding)
                            }
                            ProvideListBringIntoViewSpec {
                                LazyVerticalGrid(
                                    modifier = Modifier.fillMaxSize().focusRestorer()
                                        .blockDownFocusExitAtGridEnd(
                                            currentIndexProvider = { currentFocusedIndex },
                                            itemCount = gridFocusItemCount(
                                                contentItemCount = dynamicViewModel.currentVideos.size,
                                                footerFocusable = true,
                                                columnCount = columns
                                            ),
                                            columnCount = columns
                                        )
                                        .onFocusChanged {
                                            if (!it.isFocused) {
                                                currentFocusedIndex = -1
                                            }
                                        }
                                        // 最左列（含底部状态行）按 LEFT 直接进面板的「全部」：
                                        // 交给默认焦点搜索会先落到面板里某个 UP 行、把面板列表滚走一段
                                        .then(
                                            if (showPanel) {
                                                Modifier.redirectLeftFocusAtLeftEdge(
                                                    isAtLeftEdge = {
                                                        currentFocusedIndex < 0 ||
                                                            currentFocusedIndex % columns == 0
                                                    },
                                                    focusRequester = panelFocusRequester
                                                )
                                            } else {
                                                Modifier
                                            }
                                        )
                                        .onMenuKeyDown {
                                            dynamicViewModel.currentVideos.getOrNull(currentFocusedIndex)?.let { video ->
                                                onLongClickVideo(video)
                                            }
                                        },
                                    columns = GridCells.Fixed(columns),
                                    state = lazyGridState,
                                    contentPadding = gridContentPadding,
                                    verticalArrangement = Arrangement.spacedBy(spacedBy),
                                    horizontalArrangement = Arrangement.spacedBy(spacedBy)
                                ) {
                                    itemsIndexed(
                                    items = dynamicViewModel.currentVideos,
                                    key = { index, item -> "$index-av-${item.aid}" }
                                ) { index, item ->
                                    SmallVideoCard(
                                        data = remember(item.aid) {
                                            VideoCardData(
                                                avid = item.aid,
                                                title = item.title,
                                                cover = item.cover,
                                                play = item.play,
                                                danmaku = item.danmaku,
                                                upName = item.author,
                                                time = item.duration * 1000L,
                                                pubTime = item.pubTime,
                                                isChargingArc = item.isChargingArc,
                                                badgeText = item.chargingArcBadge
                                            )
                                        },
                                        onClick = { onClickVideo(item) },
                                        onLongClick = { onLongClickVideo(item) },
                                        onFocus = { currentFocusedIndex = index }
                                    )
                                }

                                if (dynamicViewModel.currentVideos.isEmpty()) {
                                    item(span = { GridItemSpan(maxLineSpan) }) {
                                        Box(
                                            modifier = Modifier.fillMaxSize(),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            when {
                                                dynamicViewModel.currentVideoPaging.loading -> LoadingTip()
                                                dynamicViewModel.currentVideoPaging.error != null -> Text(
                                                    text = dynamicViewModel.currentVideoPaging.error.orEmpty(),
                                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                                )

                                                !dynamicViewModel.currentVideoPaging.hasMore -> Text(
                                                    text = stringResource(SharedR.string.no_data),
                                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                                )
                                            }
                                        }
                                    }
                                }

                                if (dynamicViewModel.currentVideos.isNotEmpty()) {
                                    item(span = { GridItemSpan(maxLineSpan) }) {
                                        SpaceContentStatus(
                                            loading = dynamicViewModel.currentVideoPaging.loading,
                                            error = dynamicViewModel.currentVideoPaging.error,
                                            hasMore = dynamicViewModel.currentVideoPaging.hasMore,
                                            onRetry = {
                                                if (dynamicViewModel.selectedUp == null) {
                                                    scope.launch(Dispatchers.IO) { dynamicViewModel.loadMoreVideo() }
                                                } else {
                                                    dynamicViewModel.loadMoreForSelection()
                                                }
                                            },
                                            onFocused = { currentFocusedIndex = -1 }
                                        )
                                    }
                                }
                            }
                        }

                        // 长按操作菜单
                        VideoActionMenu(
                            show = showVideoActionMenu,
                            aid = menuAid,
                            upId = menuUpId,
                            upName = menuUpName,
                            upFace = menuUpFace,
                            onDismiss = { showVideoActionMenu = false },
                            resourceType = FavoriteResourceType.Video,
                            extraItems = listOf(
                                VideoActionMenuExtraItem(
                                    CardActionMenuItem(icon = Icons.Rounded.People, text = "已关注 UP 列表") {
                                        context.startActivity(Intent(context, FollowActivity::class.java))
                                    }
                                )
                            )
                        )
                    }

                    DynamicTab.All -> {
                        // 与视频 tab 相同：切到本 tab 时消费未处理的「回到顶部」请求
                        LaunchedEffect(feedResetVersion) {
                            if (allResetConsumed == feedResetVersion) return@LaunchedEffect
                            val version = feedResetVersion
                            allState.scrollToItem(0)
                            allResetConsumed = version
                        }
                        DynamicWaterfall(
                            items = dynamicViewModel.currentAll,
                            state = allState,
                            onItemClick = {
                                openDynamic(context, it, videoInfoRepository, dynamicViewModel.currentAll)
                            },
                            onLoadMore = {
                                if (dynamicViewModel.selectedUp == null) {
                                    scope.launch(Dispatchers.IO) { dynamicViewModel.loadMoreAll() }
                                } else {
                                    dynamicViewModel.loadMoreForSelection()
                                }
                            },
                            modifier = Modifier.fillMaxSize(),
                            // 面板可见时同样把左右之间的间距收到一半（面板 5dp + 这里 12dp）
                            contentPadding = if (showPanel) {
                                PaddingValues(start = 12.dp, top = 24.dp, end = 24.dp, bottom = 24.dp)
                            } else {
                                PaddingValues(24.dp)
                            },
                            // 面板占掉左侧宽度后卡片变窄，列间距也跟着收一半，把宽度还给卡片
                            horizontalSpacing = if (showPanel) 16.dp else 24.dp,
                            onItemLongClick = { menuDynamic = it },
                            leftEdgeFocusRequester = panelFocusRequester.takeIf { showPanel },
                            footer = {
                                SpaceContentStatus(
                                    loading = dynamicViewModel.currentAllPaging.loading,
                                    error = dynamicViewModel.currentAllPaging.error,
                                    hasMore = dynamicViewModel.currentAllPaging.hasMore,
                                    onRetry = {
                                        if (dynamicViewModel.selectedUp == null) {
                                            scope.launch(Dispatchers.IO) { dynamicViewModel.loadMoreAll() }
                                        } else {
                                            dynamicViewModel.loadMoreForSelection()
                                        }
                                    }
                                )
                            }
                        )
                    }
                }
            } else {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = "请先登录")
                }
            }
            }
        }
    }

    menuDynamic?.let { item ->
        DynamicActionMenu(
            show = true,
            dynamic = item,
            onDismiss = { menuDynamic = null }
        )
    }
}
