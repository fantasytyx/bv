package dev.aaa1115910.bv.tv.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import dev.aaa1115910.biliapi.entity.reply.Comment
import dev.aaa1115910.biliapi.entity.reply.CommentPage
import dev.aaa1115910.biliapi.entity.reply.CommentSort
import dev.aaa1115910.biliapi.entity.video.season.Episode
import dev.aaa1115910.biliapi.entity.video.season.Section
import dev.aaa1115910.biliapi.repositories.CommentRepository
import dev.aaa1115910.bv.util.Prefs
import dev.aaa1115910.bv.util.focusedBorder
import dev.aaa1115910.bv.util.isDpadDown
import dev.aaa1115910.bv.util.isDpadLeft
import dev.aaa1115910.bv.util.isKeyDown
import dev.aaa1115910.bv.util.onBackPressed
import dev.aaa1115910.bv.util.requestFocus
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.compose.getKoin
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import dev.aaa1115910.bv.util.ImageSize
import dev.aaa1115910.bv.util.resizedImageUrl

/**
 * 评论浮层组件
 *
 * @param show 是否显示浮层
 * @param oid 视频 aid
 * @param onHide 关闭浮层回调
 * @param episodes 正片剧集列表（用于选集切换）
 * @param sections 章节选集列表（用于选集切换）
 * @param initialEpisodeId 初始选中的剧集ID
 * @param onEpisodeChange 剧集切换回调
 */
@Composable
fun CommentPanel(
    show: Boolean,
    oid: Long,
    onHide: () -> Unit,
    episodes: List<Episode> = emptyList(),
    sections: List<Section> = emptyList(),
    initialEpisodeId: Int = -1,
    onEpisodeChange: ((Episode) -> Unit)? = null
) {
    val commentRepository: CommentRepository = getKoin().get()
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }
    val sidebarFocusRequester = remember { FocusRequester() }
    val density = LocalDensity.current

    val comments = remember { mutableStateListOf<Comment>() }
    var loading by remember { mutableStateOf(false) }
    var currentPage by remember { mutableStateOf(CommentPage()) }
    var hasNext by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    // 排序方式：默认按热度
    var commentSort by remember { mutableStateOf(CommentSort.Hot) }

    // 子评论浮窗状态
    var showSubCommentPanel by remember { mutableStateOf(false) }
    var selectedRootComment by remember { mutableStateOf<Comment?>(null) }
    var hasRequestedFocus by remember { mutableStateOf(false) }
    var wasSubCommentPanelShown by remember { mutableStateOf(false) }
    var selectedCommentIndex by remember { mutableStateOf(0) }
    var focusedCommentIndex by remember { mutableStateOf(0) }

    // 进入子评论面板前的列表滚动位置，以及之前点击的评论项的焦点请求器
    var savedScrollIndex by remember { mutableIntStateOf(0) }
    var savedScrollOffset by remember { mutableIntStateOf(0) }
    val selectedCommentFocusRequester = remember { FocusRequester() }

    val context = LocalContext.current

    // 全屏图片查看器状态
    var showImageViewer by remember { mutableStateOf(false) }
    var imageViewerPictures by remember { mutableStateOf<List<dev.aaa1115910.biliapi.entity.Picture>>(emptyList()) }
    var imageViewerBitmaps by remember { mutableStateOf<Map<Int, androidx.compose.ui.graphics.ImageBitmap>>(emptyMap()) }

    // 选集相关状态
    var currentEpisode by remember { mutableStateOf<Episode?>(null) }
    var focusOnSidebar by remember { mutableStateOf(false) }
    var sidebarFocusRequestToken by remember { mutableIntStateOf(0) }
    var pendingFocusToComments by remember { mutableStateOf(false) }

    // 合并所有剧集（正片 + 章节）
    val allEpisodeItems by remember(episodes, sections) {
        derivedStateOf {
            buildList {
                var idx = 0
                // 添加正片剧集
                if (episodes.isNotEmpty()) {
                    episodes.forEach { ep ->
                        // 过滤掉 aid 为 0 的剧集
                        if (ep.aid > 0) {
                            add(EpisodeItem(ep, "正片", idx++))
                        }
                    }
                }
                // 添加章节剧集
                sections.forEach { section ->
                    section.episodes.forEach { ep ->
                        // 过滤掉 aid 为 0 的剧集
                        if (ep.aid > 0) {
                            add(EpisodeItem(ep, section.title, idx++))
                        }
                    }
                }
            }
        }
    }

    // 是否显示侧边栏（多于1集时显示）
    val showSidebar by remember(allEpisodeItems) {
        derivedStateOf { allEpisodeItems.size > 1 }
    }

    fun requestFocusToCurrentEpisode() {
        if (!showSidebar) return
        focusOnSidebar = true
        sidebarFocusRequestToken++
    }

    // 初始化当前选中的剧集
    LaunchedEffect(allEpisodeItems, initialEpisodeId) {
        if (currentEpisode == null && allEpisodeItems.isNotEmpty()) {
            currentEpisode = if (initialEpisodeId != -1) {
                allEpisodeItems.find { it.episode.id == initialEpisodeId }?.episode
            } else {
                allEpisodeItems.firstOrNull()?.episode
            }
        }
    }

    // 获取当前要加载评论的 aid
    val currentOid by remember(currentEpisode, oid) {
        derivedStateOf { currentEpisode?.aid ?: oid }
    }

    // 判断是否滚动到底部
    val isAtBottom by remember {
        derivedStateOf {
            listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index == comments.size - 1
        }
    }

    // 加载评论
    val loadComments: (reset: Boolean) -> Unit = { reset ->
        scope.launch {
            if (loading) return@launch
            loading = true
            error = null

            try {
                val page = if (reset) CommentPage() else currentPage
                val data = commentRepository.getComments(
                    id = currentOid,
                    type = 1L, // 视频评论
                    sort = commentSort,
                    page = page,
                    preferApiType = Prefs.apiType
                )

                if (reset) {
                    comments.clear()
                    comments.addAll(data.topReplies)
                    comments.addAll(data.comments)
                } else {
                    comments.addAll(data.comments)
                }

                currentPage = data.nextPage
                hasNext = data.hasNext
            } catch (e: Exception) {
                error = e.message ?: "加载失败"
            } finally {
                loading = false
            }
        }
    }

    // 在按热度 / 按时间之间切换，并重新从第一页加载
    fun toggleCommentSort() {
        commentSort = if (commentSort == CommentSort.Hot) CommentSort.Time else CommentSort.Hot
        focusedCommentIndex = 0
        scope.launch {
            // 等待正在进行的加载结束，避免请求被丢弃
            while (loading) delay(50)
            listState.scrollToItem(0)
            loadComments(true)
        }
    }

    // 显示时加载评论
    LaunchedEffect(show, currentOid) {
        if (show && comments.isEmpty()) {
            loadComments(true)
        }
        if (!show) {
            hasRequestedFocus = false
            wasSubCommentPanelShown = false
            // 重置边栏焦点状态，确保下次打开时焦点在评论列表
            focusOnSidebar = false
            sidebarFocusRequestToken = 0
            pendingFocusToComments = false
        }
    }

    // 显示后请求焦点（初次显示时或子评论浮窗关闭后）
    LaunchedEffect(show, showSubCommentPanel, comments.isNotEmpty(), loading) {
        if (show && !showSubCommentPanel) {
            // 子评论浮窗刚关闭，恢复进入前的滚动位置并聚焦到之前点击的评论
            if (wasSubCommentPanelShown) {
                delay(300) // 等待动画完成
                // 子面板打开期间评论可能已被清空/替换，索引越界时不做恢复
                if (selectedCommentIndex in comments.indices) {
                    // 位置未变化时此调用不产生滚动
                    listState.scrollToItem(
                        savedScrollIndex.coerceIn(0, comments.lastIndex),
                        savedScrollOffset,
                    )
                    delay(100)
                    selectedCommentFocusRequester.requestFocus(scope)
                } else {
                    focusRequester.requestFocus(scope)
                }
                wasSubCommentPanelShown = false
            }
            // 切换剧集后评论加载完成，请求焦点到评论列表
            else if (pendingFocusToComments) {
                delay(100) // 等待渲染完成
                if (!loading) {
                    delay(100) // 等待渲染完成
                    if (comments.isNotEmpty() || !showSidebar || error != null) {
                        focusRequester.requestFocus(scope)
                    } else {
                        requestFocusToCurrentEpisode()
                    }
                    pendingFocusToComments = false
                }
            }
            // 初次显示父评论浮窗，请求焦点
            else if (!hasRequestedFocus) {
                delay(200) // 等待请求完成
                if(!loading){
                    delay(200) // 等待动画完成
                    if (comments.isNotEmpty() || !showSidebar || error != null) {
                        focusRequester.requestFocus(scope)
                    } else {
                        requestFocusToCurrentEpisode()
                    }
                    hasRequestedFocus = true
                }
            }
        }
        // 记录子评论浮窗显示状态
        if (showSubCommentPanel) {
            wasSubCommentPanelShown = true
        }
    }

    // 懒加载：滚动到底部时加载更多
    LaunchedEffect(isAtBottom, hasNext, loading) {
        if (isAtBottom && hasNext && !loading && comments.isNotEmpty()) {
            loadComments(false)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(onClick = onHide),
        contentAlignment = Alignment.CenterEnd
    ) {
        AnimatedVisibility(
            visible = show && !showSubCommentPanel,
            enter = expandHorizontally(expandFrom = Alignment.End),
            exit = shrinkHorizontally(shrinkTowards = Alignment.End)
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxHeight()
                    .widthIn(
                        min = if (showSidebar) 650.dp else 350.dp,
                        max = if (showSidebar) 750.dp else 450.dp
                    )
                    .fillMaxWidth(if (showSidebar) 0.5f else 0.3f)
                    .clickable(enabled = true, onClick = {}) // 阻止点击穿透
                    .onBackPressed {
                        if (focusOnSidebar && showSidebar) {
                            // 在边栏时按返回键关闭评论面板
                            onHide()
                        } else if (showSidebar) {
                            // 在评论列表时按返回键切换到边栏
                            requestFocusToCurrentEpisode()
                        } else {
                            // 没有边栏时直接关闭
                            onHide()
                        }
                    },
                colors = SurfaceDefaults.colors(
                    containerColor = Color.Black.copy(alpha = 0.8f)
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(20.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // 左侧边栏 - 仅在有多个剧集时显示
                    if (showSidebar) {
                        EpisodeSidebar(
                            episodes = allEpisodeItems,
                            currentEpisode = currentEpisode,
                            onEpisodeSelected = { episodeItem ->
                                currentEpisode = episodeItem.episode
                                onEpisodeChange?.invoke(episodeItem.episode)
                                comments.clear()
                                loadComments(true)
                                // 切换剧集后将焦点移回评论列表
                                focusOnSidebar = false
                                // 标记需要在评论加载完成后请求焦点
                                pendingFocusToComments = true
                            },
                            modifier = Modifier
                                .width(220.dp)
                                .fillMaxHeight(),
                            focusRequester = sidebarFocusRequester,
                            onFocusMoved = {
                                // 焦点返回评论列表
                                focusOnSidebar = false
                                scope.launch {
                                    focusRequester.requestFocus(scope)
                                }
                            },
                            focusRequestToken = sidebarFocusRequestToken
                        )
                    }

                    // 右侧评论列表区域
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .onFocusChanged { focusState ->
                                if (focusState.hasFocus && focusOnSidebar) {
                                    focusOnSidebar = false
                                }
                            },
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // 标题栏
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = if (commentSort == CommentSort.Hot) "热门评论" else "最新评论",
                                    style = MaterialTheme.typography.titleLarge,
                                    color = Color.White
                                )
                                // 操作提示
                                Text(
                                    text = if (showSidebar) "左键返回顶部,返回键切换剧集" else "左键返回顶部",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.White.copy(alpha = 0.5f)
                                )
                                // 显示当前剧集
                                if (currentEpisode != null && showSidebar) {
                                    Text(
                                        text = generateEpisodeTitle(currentEpisode, ""),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color.White.copy(alpha = 0.7f)
                                    )
                                }
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                CommentSortToggle(
                                    sort = commentSort,
                                    onToggle = { toggleCommentSort() }
                                )
                                Text(
                                    text = if (comments.isNotEmpty()) "${comments.size} 条" else "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.White.copy(alpha = 0.7f)
                                )
                            }
                        }

                        // 评论列表
                        if (error != null) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                                    .focusRequester(focusRequester)
                                    .focusable(),
                                contentAlignment = Alignment.TopStart
                            ) {
                                Text(
                                    text = error ?: "加载失败",
                                    color = Color.Red,
                                    modifier = Modifier
                                        .align(Alignment.Center)
                                        .padding(16.dp)
                                )
                            }
                        } else if (comments.isEmpty() && !loading) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                                    .focusRequester(focusRequester)
                                    .focusable(),
                                contentAlignment = Alignment.TopStart
                            ) {
                                Text(
                                    text = "暂无评论",
                                    color = Color.White.copy(alpha = 0.5f),
                                    modifier = Modifier
                                        .align(Alignment.Center)
                                        .padding(16.dp)
                                )
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                                    .focusRequester(focusRequester)
                                    .onPreviewKeyEvent { event ->
                                        when {
                                            // 左键：第一条时转移焦点到左侧边栏，否则返回顶部
                                            event.isKeyDown() && event.isDpadLeft() -> {
                                                if (focusedCommentIndex == 0 && showSidebar) {
                                                    requestFocusToCurrentEpisode()
                                                } else {
                                                    scope.launch {
                                                        listState.scrollToItem(0)
                                                        delay(100)
                                                        focusRequester.requestFocus(scope)
                                                    }
                                                }
                                                true
                                            }
                                            // 下键：逐步滚动，在列表末尾时阻止焦点移出
                                            event.isKeyDown() && event.isDpadDown() -> {
                                                val layoutInfo = listState.layoutInfo

                                                // 已聚焦到最后一条评论时，阻止焦点移出列表
                                                if (focusedCommentIndex >= comments.size - 1 && comments.isNotEmpty()) {
                                                    true
                                                } else {
                                                    val currentItemInfo = layoutInfo.visibleItemsInfo
                                                        .firstOrNull { it.index == focusedCommentIndex }

                                                    if (currentItemInfo != null) {
                                                        val viewportEnd = layoutInfo.viewportEndOffset
                                                        val itemBottom = currentItemInfo.offset + currentItemInfo.size

                                                        // 如果评论底部不可见，逐步滚动
                                                        if (itemBottom > viewportEnd) {
                                                            scope.launch {
                                                                // 每次滚动约 100dp
                                                                val scrollAmount = with(density) { 100.dp.toPx() }
                                                                listState.animateScrollBy(scrollAmount)
                                                            }
                                                            true // 拦截事件，不允许焦点转移
                                                        } else {
                                                            false // 评论已完全可见，允许焦点转移
                                                        }
                                                    } else {
                                                        false
                                                    }
                                                }
                                            }
                                            else -> false
                                        }
                                    },
                            state = listState,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            itemsIndexed(
                                items = comments,
                                key = { index, it -> "$index-comment-${it.rpid}" }
                            ) { index, comment ->
                                CommentItem(
                                    comment = comment,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .then(
                                            if (index == selectedCommentIndex) {
                                                Modifier.focusRequester(selectedCommentFocusRequester)
                                            } else Modifier
                                        )
                                        .onFocusChanged { focusState ->
                                            if (focusState.hasFocus) {
                                                focusedCommentIndex = index
                                            }
                                        },
                                    onClick = {
                                        // 只有有子评论时才能点击打开子评论浮窗
                                        if (comment.repliesCount > 0) {
                                            selectedCommentIndex = index
                                            // 记住当前滚动位置，返回时恢复
                                            savedScrollIndex = listState.firstVisibleItemIndex
                                            savedScrollOffset = listState.firstVisibleItemScrollOffset
                                            selectedRootComment = comment
                                            showSubCommentPanel = true
                                        }
                                    },
                                    onLongClick = {
                                        // 如果发现评论带链接（可能有多个链接）
                                        // 把链接转成二维码图片（二维码图片底部显示二维码内容、也就是链接文本）
                                        // 拼接comment.pictures和二维码图片,comment.pictures在前面
                                        val result = getDisplayPictures(context, comment)
                                        if (result.pictures.isNotEmpty()) {
                                            imageViewerPictures = result.pictures
                                            imageViewerBitmaps = result.bitmapOverrides
                                            showImageViewer = true
                                        }
                                    }
                                )
                            }

                            // 加载状态
                            if (loading) {
                                item {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(16.dp),
                                        horizontalArrangement = Arrangement.Center
                                    ) {
                                        LoadingTip()
                                    }
                                }
                            }

                            // 没有更多了
                            if (!hasNext && comments.isNotEmpty()) {
                                item {
                                    Text(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(16.dp),
                                        text = "没有更多评论了",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color.White.copy(alpha = 0.5f)
                                    )
                                }
                            }
                        }
                    }

                    // 底部提示
                    Spacer(modifier = Modifier.height(8.dp))
                } // Column (右侧评论列表区域) 结束
            } // Row 结束
        } // Surface 结束
        } // AnimatedVisibility 结束
    } // Box 结束

    // 子评论浮窗
    if (selectedRootComment != null) {
        SubCommentPanel(
            show = showSubCommentPanel,
            oid = currentOid,
            rootId = selectedRootComment!!.rpid,
            rootComment = selectedRootComment!!,
            onHide = {
                showSubCommentPanel = false
                selectedRootComment = null
            }
        )
    }

    // 全屏图片查看器
    if (showImageViewer && imageViewerPictures.isNotEmpty()) {
        FullscreenImageViewer(
            pictures = imageViewerPictures,
            bitmapOverrides = imageViewerBitmaps,
            onDismiss = {
                showImageViewer = false
                imageViewerPictures = emptyList()
                imageViewerBitmaps = emptyMap()
            }
        )
    }
}

/**
 * 评论排序切换按钮：在按热度 / 按时间之间来回切换
 */
@Composable
private fun CommentSortToggle(
    sort: CommentSort,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isHot = sort == CommentSort.Hot

    Surface(
        modifier = modifier.focusedBorder(MaterialTheme.shapes.small),
        onClick = onToggle,
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.1f),
            focusedContainerColor = Color.White.copy(alpha = 0.16f),
            pressedContainerColor = Color.White.copy(alpha = 0.22f)
        ),
        scale = ClickableSurfaceDefaults.scale(
            focusedScale = 1f,
            pressedScale = 1f
        ),
        shape = ClickableSurfaceDefaults.shape(shape = MaterialTheme.shapes.small)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                modifier = Modifier.size(16.dp),
                imageVector = Icons.Rounded.SwapVert,
                contentDescription = null,
                tint = if (isHot) Color.White else Color.White.copy(alpha = 0.8f)
            )
            Text(
                text = if (isHot) "按热度" else "按时间",
                style = MaterialTheme.typography.bodySmall,
                color = if (isHot) Color.White else Color.White.copy(alpha = 0.8f)
            )
        }
    }
}

/**
 * 选集侧边栏项数据类
 */
private data class EpisodeItem(
    val episode: Episode,
    val sectionTitle: String,
    val index: Int
)

/**
 * 生成剧集标题
 */
private fun generateEpisodeTitle(
    episode: Episode?,
    sectionTitle: String
): String {
    if (episode == null) return ""

    return if (episode.longTitle.isNotEmpty()) {
        runCatching {
            "第 ${episode.title.toInt()} 集 "
        }.getOrDefault("") + episode.longTitle
    } else if (sectionTitle == "正片") {
        runCatching {
            "第 ${episode.title.toInt()} 集"
        }.getOrDefault(episode.title)
    } else {
        episode.title
    }
}

/**
 * 选集侧边栏组件
 */
@Composable
private fun EpisodeSidebar(
    episodes: List<EpisodeItem>,
    currentEpisode: Episode?,
    onEpisodeSelected: (EpisodeItem) -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester,
    onFocusMoved: () -> Unit = {},
    focusRequestToken: Int = 0
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // 为每个剧集项创建独立的 FocusRequester
    val itemFocusRequesters = remember(episodes.size) {
        List(episodes.size) { FocusRequester() }
    }

    // 计算在 LazyColumn 中的实际索引（考虑章节标题）
    fun calculateLazyColumnIndex(episodes: List<EpisodeItem>, episodeIndex: Int): Int {
        if (episodeIndex < 0 || episodes.isEmpty()) return 0
        var actualIndex = 0
        var lastSectionTitle = ""
        // 遍历到目标索引之前的所有项
        for (i in 0 until episodeIndex) {
            if (episodes[i].sectionTitle != lastSectionTitle) {
                lastSectionTitle = episodes[i].sectionTitle
                actualIndex++  // 章节标题占一个索引
            }
            actualIndex++  // 剧集项占一个索引
        }
        // 检查目标项本身是否有新章节标题
        if (episodes[episodeIndex].sectionTitle != lastSectionTitle) {
            actualIndex++  // 目标项的章节标题
        }
        return actualIndex  // 返回剧集项的正确索引
    }

    // 初始化时滚动到当前选中的剧集（只滚动，不请求焦点）
    LaunchedEffect(currentEpisode, episodes) {
        val index = episodes.indexOfFirst { it.episode.id == currentEpisode?.id }
        if (index >= 0) {
            val actualIndex = calculateLazyColumnIndex(episodes, index)
            listState.scrollToItem(maxOf(0, actualIndex - 2))
        }
    }

    // 当收到焦点请求时，滚动并请求焦点到当前剧集
    LaunchedEffect(focusRequestToken) {
        if (focusRequestToken > 0) {
            delay(50) // 短暂等待确保布局就绪
            val index = episodes.indexOfFirst { it.episode.id == currentEpisode?.id }
            if (index >= 0) {
                val actualIndex = calculateLazyColumnIndex(episodes, index)
                listState.scrollToItem(maxOf(0, actualIndex - 2))
                delay(50)
                // 直接请求焦点到当前选中的剧集项
                itemFocusRequesters.getOrNull(index)?.requestFocus()
            } else {
                // 没有找到当前剧集，焦点到第一个剧集
                itemFocusRequesters.firstOrNull()?.requestFocus()
            }
        }
    }

    LazyColumn(
        modifier = modifier.focusRequester(focusRequester),
        state = listState,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        contentPadding = PaddingValues(vertical = 8.dp)
    ) {
        // 按章节分组显示
        var lastSectionTitle = ""

        episodes.forEachIndexed { index, item ->
            // 章节标题
            if (item.sectionTitle != lastSectionTitle) {
                lastSectionTitle = item.sectionTitle
                item {
                    Text(
                        text = item.sectionTitle,
                        style = MaterialTheme.typography.titleSmall,
                        color = Color.White.copy(alpha = 0.7f),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            // 剧集按钮
            item {
                val isSelected = item.episode.id == currentEpisode?.id
                EpisodeSidebarItem(
                    episode = item.episode,
                    sectionTitle = item.sectionTitle,
                    isSelected = isSelected,
                    onClick = { onEpisodeSelected(item) },
                    onBackKeyPressed = onFocusMoved,
                    focusRequester = itemFocusRequesters[index]
                )
            }
        }
    }
}

/**
 * 选集侧边栏单项组件
 */
@Composable
private fun EpisodeSidebarItem(
    episode: Episode,
    sectionTitle: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    onBackKeyPressed: () -> Unit = {},
    focusRequester: FocusRequester = remember { FocusRequester() }
) {
    val borderColor = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.3f) else null
    val context = LocalContext.current

    Surface(
        modifier = Modifier.focusRequester(focusRequester),
        onClick = onClick,
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (isSelected) {
                Color.White.copy(alpha = 0.15f)
            } else {
                Color.Transparent
            },
            focusedContainerColor = if (isSelected) {
                Color.White.copy(alpha = 0.15f)
            } else {
                Color.Transparent
            }
        ),
        scale = ClickableSurfaceDefaults.scale(
            focusedScale = 1f
        ),
        border = ClickableSurfaceDefaults.border(
            border = borderColor?.let {
                Border(border = BorderStroke(width = 2.dp, color = it))
            } ?: Border.None,
            focusedBorder = Border(
                border = BorderStroke(width = 2.dp, color = MaterialTheme.colorScheme.border),
                shape = MaterialTheme.shapes.small
            )
        ),
        shape = ClickableSurfaceDefaults.shape(shape = MaterialTheme.shapes.small)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 剧集封面缩略图
            AsyncImage(
                modifier = Modifier
                    .size(48.dp)
                    .clip(MaterialTheme.shapes.extraSmall),
                model = episode.cover.resizedImageUrl(ImageSize.UgcEpisodeCover),
                contentDescription = null,
                contentScale = ContentScale.Crop
            )

            // 剧集标题
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = generateEpisodeTitle(episode, sectionTitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (episode.longTitle.isNotEmpty()) {
                    Text(
                        text = episode.longTitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.6f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
