package dev.aaa1115910.bv.tv.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.Divider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.aaa1115910.biliapi.entity.reply.Comment
import dev.aaa1115910.biliapi.entity.reply.CommentReplyPage
import dev.aaa1115910.biliapi.repositories.CommentRepository
import dev.aaa1115910.bv.util.Prefs
import dev.aaa1115910.bv.util.isDpadDown
import dev.aaa1115910.bv.util.isKeyDown
import dev.aaa1115910.bv.util.requestFocus
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.compose.getKoin

/**
 * 子评论内容（由评论主面板的 Surface 承载，背景/宽度/返回键统一由调用方处理）
 *
 * @param oid 视频 ID
 * @param rootId 根评论 ID
 * @param rootComment 根评论数据
 */
@Composable
fun SubCommentPanelContent(
    oid: Long,
    rootId: Long,
    rootComment: Comment
) {
    val commentRepository: CommentRepository = getKoin().get()
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }
    // 根评论的焦点请求器：进入子评论后立刻聚焦，避免列表数据回来之前面板里没有焦点
    val rootFocusRequester = remember { FocusRequester() }
    val density = LocalDensity.current

    val replies = remember { mutableStateListOf<Comment>() }
    var focusedCommentIndex by remember { mutableStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    // 首次加载是否已结束：没结束前显示加载态，避免首帧闪一下"暂无回复"
    var hasLoadedOnce by remember { mutableStateOf(false) }
    var currentPage by remember { mutableStateOf(CommentReplyPage()) }
    var hasNext by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    val context = LocalContext.current

    // 全屏图片查看器状态
    var showImageViewer by remember { mutableStateOf(false) }
    var imageViewerPictures by remember { mutableStateOf<List<dev.aaa1115910.biliapi.entity.Picture>>(emptyList()) }
    var imageViewerBitmaps by remember { mutableStateOf<Map<Int, androidx.compose.ui.graphics.ImageBitmap>>(emptyMap()) }

    // 加载子评论
    val loadReplies: (Boolean) -> Unit = { reset ->
        scope.launch {
            if (loading) return@launch
            loading = true
            error = null

            try {
                val page = if (reset) CommentReplyPage() else currentPage
                val data = commentRepository.getCommentReplies(
                    rpid = rootId,
                    type = 1L,
                    commentId = oid,
                    page = page,
                    preferApiType = Prefs.apiType
                )

                if (reset) {
                    replies.clear()
                    replies.addAll(data.replies)
                } else {
                    replies.addAll(data.replies)
                }

                currentPage = data.nextPage
                hasNext = data.hasNext
            } catch (e: Exception) {
                error = e.message ?: "加载失败"
            } finally {
                loading = false
                hasLoadedOnce = true
            }
        }
    }

    // 打开面板后加载第一页
    LaunchedEffect(rootId) {
        if (replies.isEmpty()) {
            loadReplies(true)
        }
    }

    // 进入子评论后先把焦点放到根评论上：回复还没加载出来时面板里不能没有焦点，
    // 否则返回键会逃逸、聚焦高亮也会先消失再出现
    LaunchedEffect(rootId) {
        withFrameNanos { }
        runCatching { rootFocusRequester.requestFocus() }
    }

    // 回复加载完成后请求焦点到回复列表（此时 LazyColumn 才存在，focusRequester 才有目标）
    LaunchedEffect(rootId, replies.isNotEmpty()) {
        if (replies.isNotEmpty()) {
            delay(300)
            focusRequester.requestFocus(scope)
        }
    }

    // 懒加载
    val isAtBottom by remember {
        derivedStateOf {
            listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index == replies.size - 1
        }
    }
    LaunchedEffect(isAtBottom, hasNext, loading) {
        if (isAtBottom && hasNext && !loading && replies.isNotEmpty()) {
            loadReplies(false)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // 根评论（只读显示，右键展开/收起）
        SubCommentRootItem(
            comment = rootComment,
            focusRequester = rootFocusRequester,
            onLongClick = {
                val result = getDisplayPictures(context, rootComment)
                if (result.pictures.isNotEmpty()) {
                    imageViewerPictures = result.pictures
                    imageViewerBitmaps = result.bitmapOverrides
                    showImageViewer = true
                }
            }
        )

        // 分隔线
        Divider(
            color = Color.White.copy(alpha = 0.2f),
            thickness = 1.dp
        )

        // 子评论列表
        if (error != null) {
            Text(
                text = error ?: "加载失败",
                color = Color.Red,
                modifier = Modifier.padding(16.dp)
            )
        } else if (replies.isEmpty() && (loading || !hasLoadedOnce)) {
            // 首次加载：直接给加载态，别先闪一下空面板
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                LoadingTip()
            }
        } else if (replies.isEmpty()) {
            Text(
                text = "暂无回复",
                color = Color.White.copy(alpha = 0.5f),
                modifier = Modifier.padding(16.dp)
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .onPreviewKeyEvent { event ->
                        when {
                            // 左键返回顶部
                            event.isKeyDown() && event.key == Key.DirectionLeft -> {
                                scope.launch {
                                    listState.scrollToItem(0)
                                    delay(100)
                                    focusRequester.requestFocus(scope)
                                }
                                true
                            }
                            // 下键：逐步滚动，在列表末尾时阻止焦点移出
                            event.isKeyDown() && event.isDpadDown() -> {
                                val layoutInfo = listState.layoutInfo

                                // 已聚焦到最后一条回复时，阻止焦点移出列表
                                if (focusedCommentIndex >= replies.size - 1 && replies.isNotEmpty()) {
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
                    items = replies,
                    key = { index, it -> "$index-reply-${it.rpid}" }
                ) { index, reply ->
                    SubCommentItem(
                        comment = reply,
                        modifier = Modifier
                            .fillMaxWidth()
                            .onFocusChanged { focusState ->
                                if (focusState.hasFocus) {
                                    focusedCommentIndex = index
                                }
                            },
                        onLongClick = {
                            val result = getDisplayPictures(context, reply)
                            if (result.pictures.isNotEmpty()) {
                                imageViewerPictures = result.pictures
                                imageViewerBitmaps = result.bitmapOverrides
                                showImageViewer = true
                            }
                        }
                    )
                }

                if (loading) {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            horizontalArrangement = Arrangement.Center
                        ) {
                            LoadingTip()
                        }
                    }
                }

                if (!hasNext && replies.isNotEmpty()) {
                    item {
                        Text(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            text = "没有更多了",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.5f)
                        )
                    }
                }
            }
        }

        // 底部提示
        Spacer(modifier = Modifier.padding(bottom = 8.dp))
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
