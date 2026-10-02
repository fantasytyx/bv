package dev.aaa1115910.bv.tv.screens.main

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.aaa1115910.biliapi.entity.live.LiveAreaItem
import dev.aaa1115910.biliapi.entity.live.LiveRoomItem
import dev.aaa1115910.bv.tv.activities.video.VideoPlayerV3Activity
import dev.aaa1115910.bv.tv.component.LoadingTip
import dev.aaa1115910.bv.tv.component.TopNav
import dev.aaa1115910.bv.tv.component.TopNavItem
import dev.aaa1115910.bv.tv.component.live.LiveRoomActionMenu
import dev.aaa1115910.bv.tv.component.live.LiveRoomCard
import dev.aaa1115910.bv.tv.util.blockDownFocusExitAtGridEnd
import dev.aaa1115910.bv.tv.util.getLiveNavItemAreaGroup
import dev.aaa1115910.bv.tv.util.isLiveAreaItem
import dev.aaa1115910.bv.tv.util.isLiveFollowingItem
import dev.aaa1115910.bv.tv.util.isLiveRecommendItem
import dev.aaa1115910.bv.tv.util.liveNavItemsOrderFlow
import dev.aaa1115910.bv.tv.util.onMenuKeyDown
import dev.aaa1115910.bv.tv.util.parseLiveNavItemsOrder
import dev.aaa1115910.bv.util.Prefs
import dev.aaa1115910.bv.util.collectAsStateLazily
import dev.aaa1115910.bv.util.requestFocus
import dev.aaa1115910.bv.util.toast
import dev.aaa1115910.bv.viewmodel.live.LiveMode
import dev.aaa1115910.bv.viewmodel.live.LiveViewModel
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import dev.aaa1115910.bv.repository.VideoInfoRepository
import dev.aaa1115910.bv.tv.util.ProvideListBringIntoViewSpec

// 子分区 TopNavItem
private data class SubAreaNavItem(val area: LiveAreaItem) : TopNavItem {
    override fun getDisplayName(context: Context): String = area.name
}

@Composable
fun LiveContent(
    modifier: Modifier = Modifier,
    navFocusRequester: FocusRequester,
    liveViewModel: LiveViewModel = koinViewModel()
) {
    val scope = rememberCoroutineScope()
    val logger = KotlinLogging.logger("LiveContent")
    val context = LocalContext.current
    val navSwitchMode by Prefs.navSwitchModeFlow.collectAsStateLazily { Prefs.navSwitchMode }
    val videoInfoRepository: VideoInfoRepository = koinInject()

    val gridState = rememberLazyGridState()
    // 使用 MainScreen 传入的 FocusRequester 作为默认入口焦点（从侧边栏按右进入内容区）
    val parentNavFocusRequester = navFocusRequester
    val subNavFocusRequester = remember { FocusRequester() }
    var focusOnContent by remember { mutableStateOf(false) }
    var parentNavHasFocus by remember { mutableStateOf(false) }
    var subNavHasFocus by remember { mutableStateOf(false) }

    val currentListOnTop by remember {
        derivedStateOf {
            gridState.firstVisibleItemIndex == 0 && gridState.firstVisibleItemScrollOffset == 0
        }
    }

    // 长按/菜单键操作菜单
    var showLiveRoomActionMenu by remember { mutableStateOf(false) }
    var menuUpId by remember { mutableLongStateOf(0L) }
    var menuUpName by remember { mutableStateOf("") }
    var menuUpFace by remember { mutableStateOf("") }

    val onLongClickLiveRoom: (LiveRoomItem) -> Unit = { room ->
        menuUpId = room.uid
        menuUpName = room.uname
        menuUpFace = room.face
        showLiveRoomActionMenu = true
    }

    // 监听焦点位置，触发分页加载
    val focusedIndex = liveViewModel.lastFocusedRoomIndex
    val totalItems = liveViewModel.roomList.size
    LaunchedEffect(focusedIndex, totalItems, liveViewModel.loading) {
        if (totalItems > 0 && (totalItems < 10 || focusedIndex >= totalItems - 8) && liveViewModel.hasMore && !liveViewModel.loading) {
            logger.info { "Trigger load more, focusedIndex: $focusedIndex, totalItems: $totalItems" }
            liveViewModel.loadMore()
        }
    }

    LaunchedEffect(liveViewModel.roomList, liveViewModel.loading) {
        if (liveViewModel.roomList.isEmpty() && liveViewModel.loading) {
            liveViewModel.lastFocusedRoomIndex = 0
            gridState.scrollToItem(0)
        }
    }

    BackHandler(focusOnContent || subNavHasFocus || parentNavHasFocus) {
        logger.info { "onFocusBackToNav" }
        if (subNavHasFocus) {
            parentNavFocusRequester.requestFocus(scope)
            return@BackHandler
        }
        if (parentNavHasFocus) {
            drawerItemFocusRequesters[DrawerItem.Live]?.requestFocus()
            return@BackHandler
        }
        // 推荐/关注模式没有子分区栏，直接返回主分区栏
        if (liveViewModel.currentMode == LiveMode.AREA) {
            subNavFocusRequester.requestFocus(scope)
        } else {
            parentNavFocusRequester.requestFocus(scope)
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            androidx.compose.foundation.layout.Column {
                // 第一行：推荐 + 关注 + 主分区（根据设置过滤和排序）
                val liveNavOrderString by liveNavItemsOrderFlow.collectAsStateLazily {
                    Prefs.liveNavItemsOrder
                }

                val parentNavItems = remember(
                    liveNavOrderString,
                    liveViewModel.parentAreaGroups.size,
                    liveViewModel.isLoggedIn
                ) {
                    val items = parseLiveNavItemsOrder(
                        liveNavOrderString,
                        liveViewModel.parentAreaGroups,
                        liveViewModel.isLoggedIn
                    )
                    // 全部隐藏时强制显示推荐
                    items.ifEmpty {
                        parseLiveNavItemsOrder("", emptyList(), false)
                    }
                }

                val currentParentVisible = remember(
                    parentNavItems,
                    liveViewModel.areaGroupsLoadCompleted,
                    liveViewModel.currentMode,
                    liveViewModel.currentParentGroup?.id
                ) {
                    liveViewModel.areaGroupsLoadCompleted && parentNavItems.any {
                        it.matchesLiveMode(liveViewModel.currentMode, liveViewModel.currentParentGroup?.id)
                    }
                }

                // 首次加载或配置变化时，确保 ViewModel 模式与导航列表一致
                var initialSynced by remember { mutableStateOf(false) }
                LaunchedEffect(
                    parentNavItems,
                    liveViewModel.areaGroupsLoadCompleted,
                    liveViewModel.currentMode,
                    liveViewModel.currentParentGroup?.id
                ) {
                    if (parentNavItems.isEmpty() || !liveViewModel.areaGroupsLoadCompleted) {
                        return@LaunchedEffect
                    }

                    val shouldSwitch = if (!initialSynced) {
                        initialSynced = true
                        // 首次：排序后的第一项与默认模式不一致时切换
                        !parentNavItems.first().matchesLiveMode(liveViewModel.currentMode, liveViewModel.currentParentGroup?.id)
                    } else {
                        // 后续：当前选中项被隐藏时切换
                        !currentParentVisible
                    }

                    if (shouldSwitch) {
                        liveViewModel.lastFocusedRoomIndex = 0
                        parentNavItems.first().applyToLiveViewModel(liveViewModel)
                        gridState.scrollToItem(0)
                        return@LaunchedEffect
                    }

                    if (currentParentVisible) {
                        liveViewModel.ensureRoomsLoaded()
                    }
                }

                val initialSelectedParent = remember(liveViewModel.currentMode, liveViewModel.currentParentGroup, parentNavItems) {
                    parentNavItems.firstOrNull {
                        it.matchesLiveMode(liveViewModel.currentMode, liveViewModel.currentParentGroup?.id)
                    } ?: parentNavItems.firstOrNull()
                }

                if (parentNavItems.isNotEmpty()) {
                    TopNav(
                        modifier = Modifier
                            .focusRequester(parentNavFocusRequester)
                            .onFocusChanged { parentNavHasFocus = it.hasFocus },
                        items = parentNavItems,
                        initialSelectedItem = initialSelectedParent,
                        navSwitchMode = navSwitchMode,
                        onSelectedChanged = { nav ->
                            liveViewModel.lastFocusedRoomIndex = 0
                            scope.launch { gridState.scrollToItem(0) }
                            nav.applyToLiveViewModel(liveViewModel)
                        },
                        onClick = { nav ->
                            if (nav.matchesLiveMode(liveViewModel.currentMode, liveViewModel.currentParentGroup?.id)) {
                                liveViewModel.lastFocusedRoomIndex = 0
                                liveViewModel.refresh()
                                scope.launch { gridState.scrollToItem(0) }
                            }
                        },
                        onLeftKeyEvent = {
                            drawerItemFocusRequesters[DrawerItem.Live]?.requestFocus()
                        }
                    )
                }

                // 第二行：子分区（仅在分区模式下显示）
                if (liveViewModel.currentMode == LiveMode.AREA && liveViewModel.subAreaList.isNotEmpty()) {
                    // 监听 currentParentGroup 变化以触发子分区列表更新
                    val subNavItems = remember(liveViewModel.currentParentGroup, liveViewModel.subAreaList.size) {
                        liveViewModel.subAreaList.map { SubAreaNavItem(it) }
                    }
                    TopNav(
                        modifier = Modifier
                            .focusRequester(subNavFocusRequester)
                            .onFocusChanged { subNavHasFocus = it.hasFocus },
                        paddingTop = 0.dp,
                        items = subNavItems,
                        useSmallSize = true,
                        initialSelectedItem = subNavItems.firstOrNull { it.area.id == liveViewModel.currentSubArea?.id },
                        navSwitchMode = navSwitchMode,
                        onSelectedChanged = { nav ->
                            (nav as? SubAreaNavItem)?.let {
                                liveViewModel.lastFocusedRoomIndex = 0
                                liveViewModel.switchSubArea(it.area)
                                scope.launch { gridState.scrollToItem(0) }
                            }
                        },
                        onClick = { nav ->
                            (nav as? SubAreaNavItem)?.let { item ->
                                if (item.area.id == liveViewModel.currentSubArea?.id) {
                                    liveViewModel.lastFocusedRoomIndex = 0
                                    liveViewModel.refresh()
                                    scope.launch { gridState.scrollToItem(0) }
                                }
                            }
                        },
                        onLeftKeyEvent = {
                            parentNavFocusRequester.requestFocus(scope)
                        }
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .onFocusChanged { focusOnContent = it.hasFocus }
        ) {
            if (liveViewModel.roomList.isEmpty() && liveViewModel.loading) {
                Row(
                    modifier = Modifier.align(Alignment.Center)
                ){
                    LoadingTip()
                }
            } else {
                ProvideListBringIntoViewSpec {
                    LazyVerticalGrid(
                        modifier = Modifier
                            .fillMaxSize()
                            .focusRestorer()
                            .blockDownFocusExitAtGridEnd(
                                currentIndexProvider = { focusedIndex },
                                itemCount = totalItems,
                                columnCount = 4
                            )
                            .onMenuKeyDown {
                                liveViewModel.roomList.getOrNull(focusedIndex)
                                    ?.let(onLongClickLiveRoom)
                            },
                        state = gridState,
                        columns = GridCells.Fixed(4),
                        contentPadding = PaddingValues(20.dp),
                        verticalArrangement = Arrangement.spacedBy(13.dp),
                        horizontalArrangement = Arrangement.spacedBy(13.dp)
                    ) {
                        itemsIndexed(
                            items = liveViewModel.roomList,
                            key = { index, room -> "$index-room-${room.roomId}" }
                        ) { index, room ->
                            LiveRoomCard(
                                data = room,
                                onClick = {
                                    // 保存焦点位置
                                    liveViewModel.lastFocusedRoomIndex = index
                                    if (room.liveStatus != 1) {
                                        "${room.uname} 未开播".toast(context)
                                        return@LiveRoomCard
                                    }

                                    // 填充预加载直播房间列表
                                    videoInfoRepository.preloadedLiveRoomList.clear()
                                    videoInfoRepository.preloadedLiveRoomList.addAll(liveViewModel.roomList)
                                    videoInfoRepository.lastPreloadedRoomIndex = index

                                    val watchedText = room.watchedShow?.let { show ->
                                        show.textSmall + if (show.switch) "播放" else "人气"
                                    } ?: ""
                                    // 启动播放器
                                    VideoPlayerV3Activity.actionStartLive(
                                        context = context,
                                        roomId = room.roomId,
                                        title = room.title,
                                        upId = room.uid,
                                        upName = room.uname,
                                        upFace = room.face,
                                        watchedText = watchedText
                                    )
                                },
                                onFocus = {
                                    liveViewModel.lastFocusedRoomIndex = index
                                    logger.debug { "Focus on room ${room.roomId}" }
                                },
                                onLongClick = { onLongClickLiveRoom(room) }
                            )
                        }

                        // 加载中提示
                        if (liveViewModel.loading) {
                            item {
                                LoadingTip()
                            }
                        }

                        // 没有更多了
                        if (!liveViewModel.hasMore) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                Row(
                                    modifier = Modifier.offset(y = (-16).dp),
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    Text(
                                        text = "没有更多内容了~",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // 长按/菜单键操作菜单
    LiveRoomActionMenu(
        show = showLiveRoomActionMenu,
        upId = menuUpId,
        upName = menuUpName,
        upFace = menuUpFace,
        onDismiss = { showLiveRoomActionMenu = false }
    )
}

private fun TopNavItem.matchesLiveMode(mode: LiveMode, parentGroupId: Int?): Boolean = when {
    isLiveRecommendItem(this) -> mode == LiveMode.RECOMMEND
    isLiveFollowingItem(this) -> mode == LiveMode.FOLLOWING
    isLiveAreaItem(this) -> mode == LiveMode.AREA && getLiveNavItemAreaGroup(this)?.id == parentGroupId
    else -> false
}

private fun TopNavItem.applyToLiveViewModel(viewModel: LiveViewModel) {
    when {
        isLiveRecommendItem(this) -> viewModel.switchToRecommend()
        isLiveFollowingItem(this) -> viewModel.switchToFollowing()
        isLiveAreaItem(this) -> getLiveNavItemAreaGroup(this)?.let { viewModel.switchParentArea(it) }
    }
}
