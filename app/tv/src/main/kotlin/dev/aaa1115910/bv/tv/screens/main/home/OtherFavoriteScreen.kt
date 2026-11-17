package dev.aaa1115910.bv.tv.screens.main.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.aaa1115910.biliapi.entity.CollectedFavoriteType
import dev.aaa1115910.bv.entity.proxy.ProxyArea
import dev.aaa1115910.bv.repository.VideoInfoRepository
import dev.aaa1115910.bv.tv.activities.user.ArticleActivity
import dev.aaa1115910.bv.tv.activities.video.SeasonInfoActivity
import dev.aaa1115910.bv.tv.activities.video.VideoInfoActivity
import dev.aaa1115910.bv.tv.component.TopNav
import dev.aaa1115910.bv.tv.component.TopNavItem
import dev.aaa1115910.bv.tv.component.videocard.SmallVideoCard
import dev.aaa1115910.bv.tv.screens.user.SpaceArticleCard
import dev.aaa1115910.bv.tv.screens.user.SpaceCollectionCard
import dev.aaa1115910.bv.tv.screens.user.SpaceContentStatus
import dev.aaa1115910.bv.tv.screens.user.UpSpaceHeaderChip
import dev.aaa1115910.bv.tv.util.ProvideListBringIntoViewSpec
import dev.aaa1115910.bv.tv.util.blockDownFocusExitAtGridEnd
import dev.aaa1115910.bv.tv.util.gridFocusItemCount
import dev.aaa1115910.bv.tv.util.requestFocusWithRetry
import dev.aaa1115910.bv.tv.util.stableItemKey
import dev.aaa1115910.bv.util.Prefs
import dev.aaa1115910.bv.util.collectAsStateLazily
import dev.aaa1115910.bv.viewmodel.home.OtherFavoriteTab
import dev.aaa1115910.bv.viewmodel.home.OtherFavoriteViewModel
import dev.aaa1115910.bv.viewmodel.home.itemKey
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

private class OtherFavoriteTopNavItem(val tab: OtherFavoriteTab) : TopNavItem {
    override fun getDisplayName(context: android.content.Context): String = tab.label
}

/**
 * 首页「其它收藏」：图文收藏 + 收藏的合集/收藏夹
 *
 * 分类列表与内容列表共用一套 4 列网格；内容页头部固定在网格上方，把手感对齐 UP 空间页「合集和系列」。
 */
@Composable
fun OtherFavoriteScreen(
    modifier: Modifier = Modifier,
    articleGridState: LazyGridState = rememberLazyGridState(),
    folderGridState: LazyGridState = rememberLazyGridState(),
    contentGridState: LazyGridState = rememberLazyGridState(),
    viewModel: OtherFavoriteViewModel = koinViewModel()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val videoInfoRepository: VideoInfoRepository = koinInject()
    val navSwitchMode by Prefs.navSwitchModeFlow.collectAsStateLazily { Prefs.navSwitchMode }
    val tabFocusRequester = remember { FocusRequester() }
    val firstContentFocusRequester = remember { FocusRequester() }

    var currentIndex by remember { mutableIntStateOf(0) }
    // 从内容页返回时要复原的卡片 key，以及本次进入的是哪一个
    var openedFolderKey by remember { mutableStateOf<String?>(null) }
    var returnFolderKey by remember { mutableStateOf<String?>(null) }
    val folderFocusRequesters = remember { mutableMapOf<String, FocusRequester>() }
    val folderFocusRequesterFor: (String) -> FocusRequester = { key ->
        folderFocusRequesters.getOrPut(key) { FocusRequester() }
    }

    val selectedFolder = viewModel.selectedFolder
    val tabItems = remember { OtherFavoriteTab.entries.map(::OtherFavoriteTopNavItem) }

    val closeFolder: () -> Unit = {
        returnFolderKey = openedFolderKey
        currentIndex = 0
        viewModel.closeContent()
    }

    // 打开内容后把焦点放到第一张卡片上，而不是头部返回按钮
    val firstContentKey = viewModel.contentVideos.firstOrNull()?.stableItemKey()
    LaunchedEffect(selectedFolder, firstContentKey) {
        if (selectedFolder == null || firstContentKey == null) return@LaunchedEffect
        firstContentFocusRequester.requestFocusWithRetry()
    }

    // 返回列表后把焦点放回刚打开的那一项
    LaunchedEffect(returnFolderKey, selectedFolder) {
        val key = returnFolderKey ?: return@LaunchedEffect
        if (selectedFolder != null) return@LaunchedEffect
        returnFolderKey = null
        val index = viewModel.folders.indexOfFirst { it.itemKey() == key }
        if (index < 0) return@LaunchedEffect
        folderGridState.scrollToItem(index)
        folderFocusRequesterFor(key).requestFocusWithRetry()
    }

    BackHandler(enabled = selectedFolder != null) { closeFolder() }

    // 登录后首次进入时可能还没人触发加载；已有数据或正在加载时是空操作
    LaunchedEffect(Unit) { viewModel.loadIfEmpty() }

    Column(modifier = modifier.fillMaxSize()) {
        TopNav(
            paddingTop = 0.dp,
            items = tabItems,
            useSmallSize = true,
            initialSelectedItem = tabItems[OtherFavoriteTab.entries.indexOf(viewModel.currentTab)],
            navSwitchMode = navSwitchMode,
            tabFocusRequester = tabFocusRequester,
            onSelectedChanged = { item ->
                val tab = (item as OtherFavoriteTopNavItem).tab
                if (tab != viewModel.currentTab) {
                    currentIndex = 0
                    returnFolderKey = null
                    openedFolderKey = null
                    if (viewModel.selectedFolder != null) viewModel.closeContent()
                    viewModel.selectTab(tab)
                    scope.launch {
                        when (tab) {
                            OtherFavoriteTab.Articles -> articleGridState.scrollToItem(0)
                            OtherFavoriteTab.Collections -> folderGridState.scrollToItem(0)
                        }
                    }
                }
            }
        )

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when {
                selectedFolder != null -> Column(modifier = Modifier.fillMaxSize()) {
                    FolderContentHeader(
                        title = "${selectedFolder.type.label}·${selectedFolder.title}",
                        onFocused = { currentIndex = 0 },
                        onBack = closeFolder
                    )
                    LazyVerticalGrid(
                        modifier = Modifier
                            .fillMaxSize()
                            .focusRestorer()
                            .blockDownFocusExitAtGridEnd(
                                currentIndexProvider = { currentIndex },
                                itemCount = gridFocusItemCount(
                                    contentItemCount = viewModel.contentVideos.size,
                                    footerFocusable = true,
                                    columnCount = GridColumns
                                ),
                                columnCount = GridColumns
                            ),
                        columns = GridCells.Fixed(GridColumns),
                        state = contentGridState,
                        contentPadding = PaddingValues(GridPadding),
                        verticalArrangement = Arrangement.spacedBy(GridSpacing),
                        horizontalArrangement = Arrangement.spacedBy(GridSpacing)
                    ) {
                        itemsIndexed(
                            items = viewModel.contentVideos,
                            key = { _, video -> video.stableItemKey() }
                        ) { index, video ->
                            SmallVideoCard(
                                modifier = if (index == 0) {
                                    Modifier.focusRequester(firstContentFocusRequester)
                                } else {
                                    Modifier
                                },
                                data = video,
                                onClick = {
                                    videoInfoRepository.setPreloadedVideoList(viewModel.contentVideos)
                                    val proxyArea = ProxyArea.checkProxyArea(video.title)
                                    if (video.jumpToSeason) {
                                        SeasonInfoActivity.actionStart(
                                            context = context,
                                            epId = video.epId,
                                            seasonId = video.seasonId,
                                            proxyArea = proxyArea
                                        )
                                    } else {
                                        VideoInfoActivity.actionStart(
                                            context = context,
                                            aid = video.avid,
                                            proxyArea = proxyArea
                                        )
                                    }
                                },
                                onFocus = {
                                    currentIndex = index
                                    if (index + LoadMoreThreshold > viewModel.contentVideos.size) {
                                        viewModel.loadMore()
                                    }
                                }
                            )
                        }

                        item(span = { GridItemSpan(maxLineSpan) }) {
                            SpaceContentStatus(
                                loading = viewModel.contentPaging.loading,
                                error = viewModel.contentPaging.error,
                                hasMore = viewModel.contentPaging.hasMore,
                                onRetry = { viewModel.loadMore() },
                                onFocused = { currentIndex = -1 }
                            )
                        }
                    }
                }

                viewModel.currentTab == OtherFavoriteTab.Articles -> ProvideListBringIntoViewSpec(
                    padding = GridPadding
                ) {
                    LazyVerticalGrid(
                        modifier = Modifier
                            .fillMaxSize()
                            .focusRestorer()
                            .blockDownFocusExitAtGridEnd(
                                currentIndexProvider = { currentIndex },
                                itemCount = gridFocusItemCount(
                                    contentItemCount = viewModel.articles.size,
                                    footerFocusable = true,
                                    columnCount = GridColumns
                                ),
                                columnCount = GridColumns
                            ),
                        columns = GridCells.Fixed(GridColumns),
                        state = articleGridState,
                        contentPadding = PaddingValues(GridPadding),
                        verticalArrangement = Arrangement.spacedBy(GridSpacing),
                        horizontalArrangement = Arrangement.spacedBy(GridSpacing)
                    ) {
                        itemsIndexed(
                            items = viewModel.articles,
                            key = { _, article -> article.id }
                        ) { index, article ->
                            SpaceArticleCard(
                                article = article,
                                onClick = { ArticleActivity.actionStart(context, article.id) },
                                onFocus = {
                                    currentIndex = index
                                    if (index + LoadMoreThreshold > viewModel.articles.size) {
                                        viewModel.loadMore()
                                    }
                                }
                            )
                        }

                        item(span = { GridItemSpan(maxLineSpan) }) {
                            SpaceContentStatus(
                                loading = viewModel.articlesPaging.loading,
                                error = viewModel.articlesPaging.error,
                                hasMore = viewModel.articlesPaging.hasMore,
                                onRetry = { viewModel.loadMore() },
                                onFocused = { currentIndex = -1 }
                            )
                        }
                    }
                }

                else -> ProvideListBringIntoViewSpec(padding = GridPadding) {
                    LazyVerticalGrid(
                        modifier = Modifier
                            .fillMaxSize()
                            .focusRestorer()
                            .blockDownFocusExitAtGridEnd(
                                currentIndexProvider = { currentIndex },
                                itemCount = gridFocusItemCount(
                                    contentItemCount = viewModel.folders.size,
                                    footerFocusable = true,
                                    columnCount = GridColumns
                                ),
                                columnCount = GridColumns
                            ),
                        columns = GridCells.Fixed(GridColumns),
                        state = folderGridState,
                        contentPadding = PaddingValues(GridPadding),
                        verticalArrangement = Arrangement.spacedBy(GridSpacing),
                        horizontalArrangement = Arrangement.spacedBy(GridSpacing)
                    ) {
                        itemsIndexed(
                            items = viewModel.folders,
                            key = { _, folder -> folder.itemKey() }
                        ) { index, folder ->
                            SpaceCollectionCard(
                                title = folder.title,
                                cover = folder.cover,
                                videoCount = folder.mediaCount,
                                typeLabel = folder.type.label,
                                updateTime = folder.updateTime.takeIf { it > 0 },
                                badgeText = if (folder.invalid) {
                                    "已失效"
                                } else {
                                    null
                                },
                                modifier = Modifier.focusRequester(
                                    folderFocusRequesterFor(folder.itemKey())
                                ),
                                onClick = {
                                    // 失效的合集/收藏夹点了没有反应
                                    if (!folder.invalid) {
                                        openedFolderKey = folder.itemKey()
                                        currentIndex = 0
                                        scope.launch { contentGridState.scrollToItem(0) }
                                        viewModel.openFolder(folder)
                                    }
                                },
                                onFocus = {
                                    currentIndex = index
                                    if (index + LoadMoreThreshold > viewModel.folders.size) {
                                        viewModel.loadMore()
                                    }
                                }
                            )
                        }

                        item(span = { GridItemSpan(maxLineSpan) }) {
                            SpaceContentStatus(
                                loading = viewModel.foldersPaging.loading,
                                error = viewModel.foldersPaging.error,
                                hasMore = viewModel.foldersPaging.hasMore,
                                onRetry = { viewModel.loadMore() },
                                onFocused = { currentIndex = -1 }
                            )
                        }
                    }
                }
            }
        }
    }
}

private const val GridColumns = 4
private const val LoadMoreThreshold = 12
private val GridPadding = 24.dp
private val GridSpacing = 24.dp

/** 卡片角标与内容页标题共用的类型文案 */
private val CollectedFavoriteType.label: String
    get() = when (this) {
        CollectedFavoriteType.Season -> "合集"
        CollectedFavoriteType.Folder -> "收藏夹"
    }

@Composable
private fun FolderContentHeader(
    title: String,
    onFocused: () -> Unit,
    onBack: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = GridPadding, end = GridPadding, top = 8.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        UpSpaceHeaderChip(
            modifier = Modifier.onFocusChanged { if (it.isFocused) onFocused() },
            onClick = onBack
        ) {
            Icon(
                modifier = Modifier
                    .padding(horizontal = 10.dp, vertical = 7.dp)
                    .size(20.dp),
                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = "返回合集/收藏夹列表"
            )
        }
        Text(
            modifier = Modifier.weight(1f, fill = false),
            text = title,
            style = MaterialTheme.typography.titleLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
