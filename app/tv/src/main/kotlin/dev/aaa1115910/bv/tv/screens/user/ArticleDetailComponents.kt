package dev.aaa1115910.bv.tv.screens.user

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import dev.aaa1115910.biliapi.entity.Picture
import dev.aaa1115910.biliapi.entity.user.ArticleViewData
import dev.aaa1115910.biliapi.entity.user.ArticleVideo
import dev.aaa1115910.bv.component.QrImage
import dev.aaa1115910.bv.util.ImageSize
import dev.aaa1115910.bv.util.focusedBorder
import dev.aaa1115910.bv.util.resizedImageUrl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal val AccentColor = Color(0xFFFB7299)

/**
 * 图文/动态详情页共用的头部。
 *
 * @param title 标题，为空时不占位
 * @param favoriteCount 传 null 表示该详情页没有收藏操作
 */
@Composable
fun DetailHeader(
    authorName: String,
    publishTime: String,
    commentCount: Int,
    likeCount: Int,
    liked: Boolean,
    onHorizontalFocusChanged: (Boolean) -> Unit,
    onComment: () -> Unit,
    onLike: () -> Unit,
    onShare: () -> Unit,
    title: String = "",
    favoriteCount: Int? = null,
    favorited: Boolean = false,
    onFavorite: () -> Unit = {}
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 48.dp, end = 48.dp, top = 24.dp, bottom = 12.dp)
    ) {
        if (title.isNotBlank()) {
            Text(
                text = title,
                fontSize = 26.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(10.dp))
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = authorName.ifBlank { "未知作者" },
                    color = AccentColor,
                    fontSize = 18.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (publishTime.isNotBlank()) {
                    Text(
                        text = publishTime,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DetailActionChip(
                    text = "评论 ${formatCount(commentCount)}",
                    onFocusChanged = onHorizontalFocusChanged,
                    onClick = onComment
                )
                DetailActionChip(
                    text = "点赞 ${formatCount(likeCount)}",
                    isActive = liked,
                    onFocusChanged = onHorizontalFocusChanged,
                    onClick = onLike
                )
                if (favoriteCount != null) {
                    DetailActionChip(
                        text = "收藏 ${formatCount(favoriteCount)}",
                        isActive = favorited,
                        onFocusChanged = onHorizontalFocusChanged,
                        onClick = onFavorite
                    )
                }
                DetailActionChip(
                    text = "分享",
                    onFocusChanged = onHorizontalFocusChanged,
                    onClick = onShare
                )
            }
        }
    }
}

@Composable
fun DetailActionChip(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isActive: Boolean = false,
    onFocusChanged: (Boolean) -> Unit = {}
) {
    val shape = RoundedCornerShape(8.dp)
    Surface(
        modifier = modifier.onFocusChanged { onFocusChanged(it.isFocused) },
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            pressedContainerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        scale = ClickableSurfaceDefaults.scale(scale = 1f, focusedScale = 1f, pressedScale = 1f),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(2.dp, Color.Transparent), shape = shape),
            focusedBorder = Border(BorderStroke(2.dp, MaterialTheme.colorScheme.border), shape = shape)
        )
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            color = if (isActive) AccentColor else MaterialTheme.colorScheme.onSurface,
            fontSize = 16.sp
        )
    }
}

@Composable
fun DetailBody(
    text: String,
    focusRequester: FocusRequester,
    onPreviewKey: (KeyEvent) -> Boolean
) {
    val shape = RoundedCornerShape(8.dp)
    Text(
        text = text,
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focusRequester)
            .focusable()
            .focusedBorder(shape = shape)
            .onPreviewKeyEvent(onPreviewKey)
            .padding(vertical = 4.dp),
        fontSize = 20.sp,
        lineHeight = 32.sp,
        color = MaterialTheme.colorScheme.onSurface
    )
}

/**
 * 正文获得焦点时，上下键先滚动正文自身，滚到边界再让给列表焦点移动。
 *
 * @param itemKey 正文所在列表项的 key
 * @param onUpAtTop 正文已经滚到顶部时按上键的处理，null 表示交给默认的焦点搜索
 * @param onDownAtBottom 正文已经滚到底部时按下键的处理，null 表示交给默认的焦点搜索
 */
fun handleDetailBodyKey(
    event: KeyEvent,
    listState: LazyListState,
    scope: CoroutineScope,
    itemKey: String,
    onUpAtTop: (() -> Unit)? = null,
    onDownAtBottom: (() -> Unit)? = null
): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    val layoutInfo = listState.layoutInfo
    val bodyInfo = layoutInfo.visibleItemsInfo.firstOrNull { it.key == itemKey }
    val bodyBottomVisible = bodyInfo == null ||
        bodyInfo.offset + bodyInfo.size <= layoutInfo.viewportEndOffset
    val bodyTopVisible = bodyInfo == null || bodyInfo.offset >= layoutInfo.viewportStartOffset

    return when (event.key) {
        Key.DirectionUp -> when {
            !bodyTopVisible -> {
                scope.launch { listState.animateScrollBy(-120f) }
                true
            }

            onUpAtTop != null -> {
                onUpAtTop()
                true
            }

            else -> false
        }

        Key.DirectionDown -> when {
            !bodyBottomVisible -> {
                scope.launch { listState.animateScrollBy(120f) }
                true
            }

            onDownAtBottom != null -> {
                onDownAtBottom()
                true
            }

            else -> false
        }

        else -> false
    }
}

/** 下键：从上方进入图片宫格 */
internal fun KeyEvent.isEnterImageGridKey(): Boolean =
    type == KeyEventType.KeyDown && key == Key.DirectionDown

/** 上键：从下方进入图片宫格 */
internal fun KeyEvent.isEnterImageGridUpKey(): Boolean =
    type == KeyEventType.KeyDown && key == Key.DirectionUp

/**
 * 宫格下方的列表项：上键改为进入图片宫格。
 *
 * 方向键默认的焦点搜索按几何位置就近匹配：从宫格下方上移会落到与焦点水平对齐的那一张
 * （三列时是中间那张），而不是期望的最后一张。
 *
 * @param onEnter 进入宫格时要聚焦的那一张（宫格还没被组合出来时由它自己先滚过去）
 */
fun Modifier.enterImageGridOnUp(
    scope: CoroutineScope,
    onEnter: suspend () -> Unit
): Modifier = onPreviewKeyEvent { event ->
    if (event.isEnterImageGridUpKey()) {
        scope.launch { onEnter() }
        true
    } else {
        false
    }
}

/**
 * 把焦点送进图片宫格，落到调用方指定的那一张。
 *
 * 宫格整项都还在视口外时 LazyColumn 不会组合它，requester 找不到落点：朝宫格方向滚一点
 * 让它进视口再重试。
 *
 * @param fromAbove true：从宫格上方进入，向下滚；false：从下方进入，向上滚
 */
suspend fun focusImageGrid(
    listState: LazyListState,
    requester: FocusRequester,
    fromAbove: Boolean
) {
    if (requester.requestFocusWhenAttached()) return
    listState.animateScrollBy(
        (if (fromAbove) 1 else -1) * listState.layoutInfo.viewportEndOffset / 4f
    )
    requester.requestFocusWhenAttached()
}

private suspend fun FocusRequester.requestFocusWhenAttached(attempts: Int = 3): Boolean {
    repeat(attempts) {
        if (runCatching { requestFocus() }.getOrDefault(false)) return true
        delay(30L)
    }
    return false
}

/**
 * 列表项里的焦点落点。
 *
 * @param first 项内第一个可聚焦控件（从顶部进入时用）
 * @param last 项内最后一个可聚焦控件（回到底部时用，例如图片宫格的最后一张）
 */
class DetailItemFocus(
    val first: FocusRequester,
    val last: FocusRequester? = null
)

/**
 * 把焦点交还给视口里可见的列表项。
 *
 * 列表项被滚出组合范围时 Compose 会连同焦点一起把它移除，页面上就没有焦点目标了：
 * 方向键不再做焦点搜索，头部的互动按钮也就再也聚焦不到。程序滚动结束后必须重新交还焦点。
 *
 * @param itemFocus 列表项 key 到焦点落点的映射，未登记的项（不可聚焦）会被跳过
 * @param fromBottom true：优先取视口最下面那一项及其末位落点，false：优先取最上面那一项及其首位落点
 */
suspend fun restoreListFocus(
    listState: LazyListState,
    itemFocus: Map<String, DetailItemFocus>,
    fromBottom: Boolean
) {
    // 滚动的目标项要等布局更新后才存在，所以整段重试
    repeat(20) {
        val visible = listState.layoutInfo.visibleItemsInfo
        val ordered = if (fromBottom) {
            visible.sortedByDescending { it.index }
        } else {
            visible.sortedBy { it.index }
        }
        val candidates = ordered.flatMap { info ->
            val focus = itemFocus[info.key as? String] ?: return@flatMap emptyList()
            if (fromBottom) listOfNotNull(focus.last, focus.first) else listOf(focus.first)
        }
        if (candidates.any { requester -> runCatching { requester.requestFocus() }.isSuccess }) return
        delay(50)
    }
}

@Composable
fun DetailImageGrid(
    pictures: List<Picture>,
    firstFocusRequester: FocusRequester,
    onFocusChanged: (Boolean) -> Unit,
    onImageClick: (Int) -> Unit,
    lastFocusRequester: FocusRequester? = null,
    thumbnailSize: ImageSize = ImageSize.DetailImageCover
) {
    val lastIndex = pictures.lastIndex
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        pictures.chunked(3).forEachIndexed { rowIndex, row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                row.forEachIndexed { columnIndex, picture ->
                    val index = rowIndex * 3 + columnIndex
                    DetailImageTile(
                        // 列表里用缩略图；点开大图时 FullscreenImageViewer 仍用 picture.url 原图
                        model = picture.url.resizedImageUrl(thumbnailSize),
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .then(
                                when {
                                    index == 0 -> Modifier.focusRequester(firstFocusRequester)
                                    lastFocusRequester != null && index == lastIndex ->
                                        Modifier.focusRequester(lastFocusRequester)

                                    else -> Modifier
                                }
                            ),
                        onFocusChanged = onFocusChanged,
                        onClick = { onImageClick(index) }
                    )
                }
                repeat(3 - row.size) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun DetailImageTile(
    model: String,
    modifier: Modifier = Modifier,
    onFocusChanged: (Boolean) -> Unit = {},
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(6.dp)
    Surface(
        modifier = modifier.onFocusChanged { onFocusChanged(it.isFocused) },
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = Color.Transparent,
            pressedContainerColor = Color.Transparent
        ),
        scale = ClickableSurfaceDefaults.scale(scale = 1f, focusedScale = 1f, pressedScale = 1f),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(3.dp, Color.Transparent), shape = shape),
            focusedBorder = Border(BorderStroke(3.dp, MaterialTheme.colorScheme.border), shape = shape)
        )
    ) {
        AsyncImage(
            model = model,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )
    }
}

@Composable
fun DetailVideoCard(
    video: ArticleVideo,
    focusRequester: FocusRequester,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(8.dp)
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .focusRequester(focusRequester),
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
            pressedContainerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        scale = ClickableSurfaceDefaults.scale(scale = 1f, focusedScale = 1f, pressedScale = 1f),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(2.dp, Color.Transparent), shape = shape),
            focusedBorder = Border(BorderStroke(2.dp, MaterialTheme.colorScheme.border), shape = shape)
        )
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .width(260.dp)
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(6.dp))
            ) {
                AsyncImage(
                    model = video.cover.resizedImageUrl(ImageSize.WideCover),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
                if (video.duration.isNotBlank()) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(6.dp)
                            .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(text = video.duration, color = Color.White, fontSize = 12.sp)
                    }
                }
            }
            Column(Modifier.padding(start = 16.dp)) {
                Text(
                    text = video.title.ifBlank { "视频" },
                    fontSize = 20.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    if (video.play.isNotBlank()) {
                        Text(
                            text = "播放 ${video.play}",
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            fontSize = 14.sp
                        )
                    }
                    if (video.danmaku.isNotBlank()) {
                        Text(
                            text = "弹幕 ${video.danmaku}",
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            fontSize = 14.sp
                        )
                    }
                }
            }
        }
    }
}

/**
 * 转发动态里引用的图文内容，用背景框和动态详情正文区分开。
 *
 * 只展示内容本身，不带头部与互动操作。
 */
@Composable
fun EmbeddedArticleContent(
    article: ArticleViewData,
    onImageClick: (Int) -> Unit,
    onVideoClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
    bodyFocusRequester: FocusRequester? = null,
    lastImageFocusRequester: FocusRequester? = null,
    onBodyPreviewKey: (KeyEvent) -> Boolean = { false },
    onFocusChanged: (Boolean) -> Unit = {},
    onBodyUpAtTop: (() -> Unit)? = null
) {
    val shape = RoundedCornerShape(8.dp)
    val scope = rememberCoroutineScope()
    val pictures = remember(article) {
        article.paragraphs.mapNotNull { it.imageUrl }
            .mapIndexed { index, url -> Picture(url, 0, 0, "quoted-image-$index") }
    }
    val firstImageFocusRequester = remember { FocusRequester() }
    val videoFocusRequester = remember { FocusRequester() }
    val bodyText = article.bodyText
    // 内嵌视频卡上键进入宫格时落到最后一张；只有一张图时默认焦点搜索本来就只可能是它
    val lastImageEntryRequester = lastImageFocusRequester?.takeIf { pictures.size > 1 }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (article.title.isNotBlank() || article.authorName.isNotBlank()) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (article.title.isNotBlank()) {
                    Text(
                        text = article.title,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (article.authorName.isNotBlank()) {
                    Text(
                        text = listOf(article.authorName, article.publishTime)
                            .filter { it.isNotBlank() }
                            .joinToString(" · "),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        if (bodyText.isNotBlank()) {
            Text(
                text = bodyText,
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (bodyFocusRequester != null) {
                            Modifier.focusRequester(bodyFocusRequester)
                        } else {
                            Modifier
                        }
                    )
                    .focusable()
                    .focusedBorder(shape = RoundedCornerShape(6.dp))
                    .onPreviewKeyEvent { event ->
                        when {
                            // 正文内部滚动优先
                            onBodyPreviewKey(event) -> true
                            // 正文滚到底后下键默认会落到宫格中间那张，这里固定落到第一张
                            pictures.isNotEmpty() && event.isEnterImageGridKey() -> {
                                scope.launch { firstImageFocusRequester.requestFocusWhenAttached() }
                                true
                            }
                            // 正文滚到顶后上键交给外层的上一项（外层宫格的最后一张）
                            onBodyUpAtTop != null && event.isEnterImageGridUpKey() -> {
                                onBodyUpAtTop()
                                true
                            }

                            else -> false
                        }
                    },
                fontSize = 18.sp,
                lineHeight = 28.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        if (pictures.isNotEmpty()) {
            DetailImageGrid(
                pictures = pictures,
                firstFocusRequester = firstImageFocusRequester,
                lastFocusRequester = lastImageFocusRequester,
                onFocusChanged = onFocusChanged,
                onImageClick = onImageClick
            )
        }
        article.video?.let { video ->
            DetailVideoCard(
                video = video,
                focusRequester = videoFocusRequester,
                modifier = if (lastImageEntryRequester != null) {
                    Modifier.enterImageGridOnUp(scope) {
                        lastImageEntryRequester.requestFocusWhenAttached()
                    }
                } else {
                    Modifier
                },
                onClick = { onVideoClick(video.aid) }
            )
        }
    }
}

@Composable
fun DetailShareDialog(
    url: String,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true
        )
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            androidx.compose.material3.Surface(
                shape = RoundedCornerShape(16.dp),
                color = Color.White
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "分享",
                        color = Color.Black,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(16.dp))
                    QrImage(
                        modifier = Modifier.size(320.dp),
                        content = url,
                        borderWidth = 12.dp,
                        shape = RoundedCornerShape(4.dp)
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = url,
                        color = Color.Gray,
                        fontSize = 12.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(text = "按返回键关闭", color = Color.Gray, fontSize = 12.sp)
                }
            }
        }
    }
}
