package dev.aaa1115910.bv.tv.screens.search

import android.app.Activity
import android.content.Context
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import dev.aaa1115910.biliapi.entity.ApiType
import dev.aaa1115910.biliapi.entity.live.LiveRoomItem
import dev.aaa1115910.biliapi.entity.ugc.toSmartDate
import dev.aaa1115910.biliapi.repositories.SearchType
import dev.aaa1115910.biliapi.repositories.SearchTypeResult
import dev.aaa1115910.bv.R
import dev.aaa1115910.bv.tv.component.videocard.SeasonCard
import dev.aaa1115910.bv.tv.component.videocard.SmallVideoCard
import dev.aaa1115910.bv.entity.carddata.SeasonCardData
import dev.aaa1115910.bv.entity.carddata.VideoCardData
import dev.aaa1115910.bv.entity.proxy.ProxyArea
import dev.aaa1115910.bv.repository.VideoInfoRepository
import dev.aaa1115910.bv.tv.activities.video.SeasonInfoActivity
import dev.aaa1115910.bv.tv.activities.video.UpInfoActivity
import dev.aaa1115910.bv.tv.activities.video.VideoInfoActivity
import dev.aaa1115910.bv.tv.activities.video.VideoPlayerV3Activity
import dev.aaa1115910.bv.tv.component.TopNav
import dev.aaa1115910.bv.tv.component.TopNavItem
import dev.aaa1115910.bv.tv.component.VideoActionMenu
import dev.aaa1115910.bv.tv.component.CardActionMenuItem
import dev.aaa1115910.bv.tv.component.live.LiveRoomCard
import dev.aaa1115910.bv.tv.screens.user.UpCard
import dev.aaa1115910.bv.tv.util.blockDownFocusExitAtGridEnd
import dev.aaa1115910.bv.tv.util.onMenuKeyDown
import dev.aaa1115910.bv.tv.util.ProvideListBringIntoViewSpec
import dev.aaa1115910.bv.util.Prefs
import dev.aaa1115910.bv.util.collectAsStateLazily
import dev.aaa1115910.bv.util.fInfo
import dev.aaa1115910.bv.util.focusedScale
import dev.aaa1115910.bv.util.removeHtmlTags
import dev.aaa1115910.bv.util.requestFocus
import dev.aaa1115910.bv.viewmodel.search.SearchResultViewModel
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.getKoin

@Composable
fun SearchResultScreen(
    modifier: Modifier = Modifier,
    searchResultViewModel: SearchResultViewModel = koinViewModel()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val logger = KotlinLogging.logger { }
    val videoInfoRepository: VideoInfoRepository = getKoin().get()
    val navSwitchMode by Prefs.navSwitchModeFlow.collectAsStateLazily { Prefs.navSwitchMode }
    val tabRowFocusRequester = remember { FocusRequester() }
    val searchTopNavItems = remember { SearchType.entries.map(::SearchTopNavItem) }

    var rowSize by remember { mutableIntStateOf(4) }
    var currentIndex by remember { mutableIntStateOf(0) }
    val lazyGridState = rememberLazyGridState()
    val firstItemFocusRequester = remember { FocusRequester() }
    val showLargeTitle by remember { derivedStateOf { currentIndex < rowSize } }
    val titleFontSize by animateFloatAsState(
        targetValue = if (showLargeTitle) 48f else 24f,
        label = "Title font size"
    )

    var searchKeyword by remember { mutableStateOf("") }

    val searchResult = when (searchResultViewModel.searchType) {
        SearchType.Video -> searchResultViewModel.videoSearchResult
        SearchType.MediaBangumi -> searchResultViewModel.mediaBangumiSearchResult
        SearchType.MediaFt -> searchResultViewModel.mediaFtSearchResult
        SearchType.BiliUser -> searchResultViewModel.biliUserSearchResult
        SearchType.LiveRoom -> searchResultViewModel.liveRoomSearchResult
    }

    var showFilter by remember { mutableStateOf(false) }

    // 视频卡片操作菜单
    var menuVideo by remember { mutableStateOf<SearchTypeResult.Video?>(null) }

    // 筛选数据重新加载后需要把焦点放回第一张卡片
    var pendingFocusFirstItem by remember { mutableStateOf(false) }

    val selectedOrder = searchResultViewModel.selectedOrder
    val selectedDuration = searchResultViewModel.selectedDuration
    val selectedPartition = searchResultViewModel.selectedPartition
    val selectedChildPartition = searchResultViewModel.selectedChildPartition

    val onClickResult: (SearchTypeResult.SearchTypeResultItem) -> Unit = { resultItem ->
        when (resultItem) {
            is SearchTypeResult.Video -> {
                videoInfoRepository.setPreloadedVideoList(
                    searchResult.videos.map { video ->
                        VideoCardData(
                            avid = video.aid,
                            title = video.title,
                            cover = video.cover,
                            upName = video.author,
                            play = with(video.play) { if (this == -1L) null else this },
                            danmaku = with(video.danmaku) { if (this == -1) null else this },
                            time = video.duration * 1000L
                        )
                    }
                )
                VideoInfoActivity.actionStart(
                    context = context,
                    aid = resultItem.aid,
                    fromSeason = false,
                    proxyArea = ProxyArea.checkProxyArea(resultItem.title)
                )
            }

            is SearchTypeResult.Pgc -> {
                SeasonInfoActivity.actionStart(
                    context = context,
                    seasonId = resultItem.seasonId,
                    proxyArea = ProxyArea.checkProxyArea(resultItem.title)
                )
            }

            is SearchTypeResult.User -> {
                UpInfoActivity.actionStart(
                    context = context,
                    mid = resultItem.mid,
                    name = resultItem.name,
                    face = resultItem.avatar
                )
            }

            is SearchTypeResult.LiveRoom -> {
                VideoPlayerV3Activity.actionStartLive(
                    context = context,
                    roomId = resultItem.roomId.toInt(),
                    title = resultItem.title,
                    upId = resultItem.uid,
                    upName = resultItem.uname,
                    upFace = resultItem.face
                )
            }

            else -> {}
        }
    }

    val backToTabRow: () -> Unit = {
        tabRowFocusRequester.requestFocus(scope)
    }

    // 视频筛选只在 Web API 下可用
    val filterAvailable = Prefs.apiType == ApiType.Web

    val onLongClickSearchResultItem: (SearchTypeResult.SearchTypeResultItem) -> Unit = { resultItem ->
        if (resultItem is SearchTypeResult.Video) menuVideo = resultItem
    }

    LaunchedEffect(Unit) {
        val intent = (context as Activity).intent
        if (intent.hasExtra("keyword")) {
            searchKeyword = intent.getStringExtra("keyword") ?: ""
            val enableProxy = intent.getBooleanExtra("enableProxy", false)
            if (searchKeyword == "") context.finish()
            searchResultViewModel.enableProxySearchResult = enableProxy
            searchResultViewModel.keyword = searchKeyword
        } else {
            context.finish()
        }
    }

    LaunchedEffect(searchResultViewModel.searchType) {
        rowSize = when (searchResultViewModel.searchType) {
            SearchType.Video -> 4
            SearchType.MediaBangumi, SearchType.MediaFt -> 6
            SearchType.BiliUser -> 3
            SearchType.LiveRoom -> 4
        }
    }

    // 首次进入时这个 effect 也会执行一次，此时不需要回顶
    var filterEffectInited by remember { mutableStateOf(false) }

    LaunchedEffect(
        selectedOrder, selectedDuration, selectedPartition, selectedChildPartition
    ) {
        logger.fInfo { "Start update search result because filter updated" }
        val previousVideos =
            if (filterEffectInited) searchResultViewModel.videoSearchResult.videos else null
        filterEffectInited = true
        if (previousVideos != null) currentIndex = 0
        searchResultViewModel.update()
        if (previousVideos != null) {
            // 等新数据替换掉旧列表后再回顶，否则焦点会停留在已移除的卡片上
            snapshotFlow { searchResultViewModel.videoSearchResult.videos }
                .first { it !== previousVideos && it.isNotEmpty() }
            lazyGridState.scrollToItem(0)
            pendingFocusFirstItem = true
        }
    }

    // 筛选对话框关闭、窗口重新拿到焦点后再聚焦第一张卡片（对话框打开时 requestFocus 会被拒绝）
    LaunchedEffect(pendingFocusFirstItem, showFilter) {
        if (!pendingFocusFirstItem || showFilter) return@LaunchedEffect
        repeat(10) {
            if (runCatching { firstItemFocusRequester.requestFocus() }.getOrDefault(false)) {
                pendingFocusFirstItem = false
                return@LaunchedEffect
            }
            delay(50)
        }
        pendingFocusFirstItem = false
    }

    LaunchedEffect(currentIndex) {
        if (currentIndex + 12 > searchResult.count) {
            searchResultViewModel.loadMore(searchResult.type)
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            Box(
                modifier = Modifier.padding(start = 48.dp, top = 24.dp, bottom = 8.dp, end = 48.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        modifier = Modifier.fillMaxWidth(0.7f),
                        text = searchKeyword,
                        fontSize = titleFontSize.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Column(
                        horizontalAlignment = Alignment.End,
                    ) {
                        if (searchResultViewModel.searchType == SearchType.Video) {
                            Text(
                                text = stringResource(R.string.search_video_card_menu_open_tip),
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }
                        Text(
                            text = stringResource(
                                R.string.load_data_count,
                                searchResult.count
                            ),
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
        ) {
            TopNav(
                paddingTop = 0.dp,
                items = searchTopNavItems,
                initialSelectedItem = searchTopNavItems.firstOrNull {
                    it.searchType == searchResultViewModel.searchType
                },
                navSwitchMode = navSwitchMode,
                tabFocusRequester = tabRowFocusRequester,
                onSelectedChanged = { selectedItem ->
                    val selectedSearchType = (selectedItem as SearchTopNavItem).searchType
                    if (searchResultViewModel.searchType != selectedSearchType) {
                        scope.launch {
                            searchResultViewModel.searchType = selectedSearchType
                            searchResultViewModel.init(selectedSearchType)
                        }
                    }
                }
            )
            ProvideListBringIntoViewSpec(padding = 26.dp) {
                LazyVerticalGrid(
                    modifier = Modifier
                        .focusRestorer()
                        .blockDownFocusExitAtGridEnd(
                            currentIndexProvider = { currentIndex },
                            itemCount = searchResult.count,
                            columnCount = rowSize
                        )
                        .onPreviewKeyEvent {
                            if (it.key == Key.Back) {
                                if (it.type == KeyEventType.KeyUp) backToTabRow()
                                return@onPreviewKeyEvent true
                            }
                            false
                        }
                        .onMenuKeyDown {
                            if (searchResultViewModel.searchType == SearchType.Video) {
                                searchResult.videos.getOrNull(currentIndex)?.let { video ->
                                    menuVideo = video
                                }
                            }
                        },
                    columns = GridCells.Fixed(rowSize),
                    state = lazyGridState,
                    contentPadding = PaddingValues(24.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp),
                    horizontalArrangement = Arrangement.spacedBy(24.dp)
                ) {
                    itemsIndexed(
                        items = when (searchResult.type) {
                            SearchType.Video -> searchResult.videos
                            SearchType.MediaBangumi -> searchResult.mediaBangumis
                            SearchType.MediaFt -> searchResult.mediaFts
                            SearchType.BiliUser -> searchResult.biliUsers
                            SearchType.LiveRoom -> searchResult.liveRooms
                        },
                        key = { index, item -> "$index-${searchResultItemKey(item)}" }
                    ) { index, searchResultItem ->
                        SearchResultListItem(
                            modifier = if (index == 0) {
                                Modifier.focusRequester(firstItemFocusRequester)
                            } else {
                                Modifier
                            },
                            searchResult = searchResultItem,
                            onClick = { onClickResult(searchResultItem) },
                            onLongClick = { onLongClickSearchResultItem(searchResultItem) },
                            onFocus = { currentIndex = index }
                        )
                    }
                }
            }
        }
    }

    SearchResultVideoFilter(
        show = showFilter,
        onHideFilter = { showFilter = false },
        selectedOrder = selectedOrder,
        selectedDuration = selectedDuration,
        selectedPartition = selectedPartition,
        selectedChildPartition = selectedChildPartition,
        onSelectedOrderChange = { searchResultViewModel.selectedOrder = it },
        onSelectedDurationChange = { searchResultViewModel.selectedDuration = it },
        onSelectedPartitionChange = { searchResultViewModel.selectedPartition = it },
        onSelectedChildPartitionChange = { searchResultViewModel.selectedChildPartition = it }
    )

    // 视频卡片操作菜单：长按确认键或按菜单键打开
    VideoActionMenu(
        show = menuVideo != null,
        aid = menuVideo?.aid ?: 0L,
        upId = menuVideo?.mid ?: 0L,
        upName = menuVideo?.author ?: "",
        upFace = menuVideo?.face ?: "",
        onDismiss = { menuVideo = null },
        extraItems = if (filterAvailable) {
            listOf(
                CardActionMenuItem(icon = Icons.Rounded.Tune, text = "筛选") { showFilter = true }
            )
        } else {
            emptyList()
        }
    )
}

private data class SearchTopNavItem(
    val searchType: SearchType
) : TopNavItem {
    override fun getDisplayName(context: Context): String {
        return searchType.getDisplayName(context)
    }
}

private fun searchResultItemKey(item: SearchTypeResult.SearchTypeResultItem): Any {
    return when (item) {
        is SearchTypeResult.Video -> "video-${item.aid}"
        is SearchTypeResult.Pgc -> "pgc-${item.seasonId}"
        is SearchTypeResult.User -> "user-${item.mid}"
        is SearchTypeResult.LiveRoom -> "live-${item.roomId}"
        else -> item.hashCode()
    }
}

@Composable
private fun SearchResultListItem(
    modifier: Modifier = Modifier,
    searchResult: SearchTypeResult.SearchTypeResultItem,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onFocus: () -> Unit
) {
    when (searchResult) {
        is SearchTypeResult.Video -> {
            SmallVideoCard(
                modifier = modifier,
                data = VideoCardData(
                    avid = searchResult.aid,
                    title = searchResult.title.removeHtmlTags(),
                    cover = searchResult.cover,
                    play = with(searchResult.play) { if (this == -1L) null else this },
                    danmaku = with(searchResult.danmaku) { if (this == -1) null else this },
                    upName = searchResult.author,
                    time = searchResult.duration * 1000L,
                    pubTime = searchResult.pubTime.toLong().toSmartDate()
                ),
                onClick = onClick,
                onLongClick = onLongClick,
                onFocus = onFocus
            )
        }

        is SearchTypeResult.Pgc -> {
            SeasonCard(
                modifier = modifier,
                data = SeasonCardData(
                    seasonId = searchResult.seasonId,
                    title = searchResult.title.removeHtmlTags(),
                    cover = searchResult.cover,
                    rating = String.format("%.1f", searchResult.star)
                ),
                onClick = onClick,
                onLongClick = onLongClick,
                onFocus = onFocus
            )
        }

        is SearchTypeResult.User -> {
            UpCard(
                modifier = modifier.focusedScale(0.95f),
                face = searchResult.avatar,
                sign = searchResult.sign,
                username = searchResult.name,
                onFocusChange = { if (it) onFocus() },
                onClick = onClick,
                onLongClick = onLongClick
            )
        }

        is SearchTypeResult.LiveRoom -> {
            LiveRoomCard(
                modifier = modifier,
                data = LiveRoomItem(
                    roomId = searchResult.roomId.toInt(),
                    uid = 0, // Not available directly in search result
                    title = searchResult.title.removeHtmlTags(),
                    uname = searchResult.uname,
                    userCover = "",
                    systemCover = "",
                    cover = searchResult.userCover ?: searchResult.cover,
                    face = searchResult.face,
                    parentId = 0,
                    parentName = "",
                    areaId = 0,
                    areaName = searchResult.areaName ?: ""
                ),
                onClick = onClick
            )
        }

        else -> {

        }
    }
}

fun SearchType.getDisplayName(context: Context) = when (this) {
    SearchType.Video -> context.getString(R.string.search_result_type_name_video)
    SearchType.MediaBangumi -> context.getString(R.string.search_result_type_name_media_bangumi)
    SearchType.MediaFt -> context.getString(R.string.search_result_type_name_media_ft)
    SearchType.BiliUser -> context.getString(R.string.search_result_type_name_bili_user)
    SearchType.LiveRoom -> context.getString(R.string.search_result_type_name_live_room)
}
