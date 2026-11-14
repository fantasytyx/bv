package dev.aaa1115910.bv.tv.screens.user

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import dev.aaa1115910.biliapi.entity.Picture
import dev.aaa1115910.biliapi.entity.user.ArticleViewData
import dev.aaa1115910.biliapi.entity.user.ArticleVideo
import dev.aaa1115910.biliapi.entity.user.DynamicItem
import dev.aaa1115910.biliapi.entity.user.toArticleVideo
import dev.aaa1115910.biliapi.entity.user.toQuotedArticle
import dev.aaa1115910.biliapi.repositories.LikeRepository
import dev.aaa1115910.biliapi.repositories.UserRepository
import dev.aaa1115910.bv.tv.activities.video.VideoInfoActivity
import dev.aaa1115910.bv.tv.activities.video.VideoPlayerV3Activity
import dev.aaa1115910.bv.tv.component.CommentPanel
import dev.aaa1115910.bv.tv.component.FullscreenImageViewer
import dev.aaa1115910.bv.util.ImageSize
import dev.aaa1115910.bv.util.Prefs
import dev.aaa1115910.bv.util.resizedImageUrl
import dev.aaa1115910.bv.util.toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

private const val DynamicBodyItemKey = "dynamic-body"
private const val DynamicQuoteItemKey = "dynamic-quote"
private const val DynamicBlockedItemKey = "dynamic-blocked"
private const val DynamicLiveItemKey = "dynamic-live"
private const val DynamicImagesItemKey = "dynamic-images"
private const val DynamicVideoItemKey = "dynamic-video"
private const val DynamicPgcItemKey = "dynamic-pgc"
private const val DynamicArticleItemKey = "dynamic-article"
private const val DynamicQuoteVideoItemKey = "dynamic-quote-video"

@Composable
fun DynamicDetailScreen(
    dynamicId: String,
    userRepository: UserRepository = koinInject(),
    likeRepository: LikeRepository = koinInject()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    var dynamic by remember(dynamicId) { mutableStateOf<DynamicItem?>(null) }
    var quotedArticle by remember(dynamicId) { mutableStateOf<ArticleViewData?>(null) }
    var error by remember(dynamicId) { mutableStateOf<String?>(null) }
    var imageIndex by remember(dynamicId) { mutableStateOf<Int?>(null) }
    var quotedImageIndex by remember(dynamicId) { mutableStateOf<Int?>(null) }
    var showComments by remember(dynamicId) { mutableStateOf(false) }
    var showShare by remember(dynamicId) { mutableStateOf(false) }
    var liked by remember(dynamicId) { mutableStateOf(false) }
    var likeCount by remember(dynamicId) { mutableIntStateOf(0) }
    // 焦点在图片宫格或顶部操作按钮上时，左右键需要留给焦点移动
    var isHorizontalFocusActive by remember(dynamicId) { mutableStateOf(false) }

    val bodyFocusRequester = remember { FocusRequester() }
    val firstImageFocusRequester = remember { FocusRequester() }
    val lastImageFocusRequester = remember { FocusRequester() }
    val videoFocusRequester = remember { FocusRequester() }
    val pgcFocusRequester = remember { FocusRequester() }
    val liveFocusRequester = remember { FocusRequester() }
    val quotedBodyFocusRequester = remember { FocusRequester() }
    val quotedLastImageFocusRequester = remember { FocusRequester() }
    val quotedVideoFocusRequester = remember { FocusRequester() }
    val itemFocus = remember {
        mapOf(
            DynamicLiveItemKey to DetailItemFocus(liveFocusRequester),
            DynamicBodyItemKey to DetailItemFocus(bodyFocusRequester),
            DynamicImagesItemKey to DetailItemFocus(firstImageFocusRequester, lastImageFocusRequester),
            DynamicVideoItemKey to DetailItemFocus(videoFocusRequester),
            DynamicPgcItemKey to DetailItemFocus(pgcFocusRequester),
            DynamicQuoteItemKey to DetailItemFocus(quotedBodyFocusRequester, quotedLastImageFocusRequester),
            DynamicQuoteVideoItemKey to DetailItemFocus(quotedVideoFocusRequester)
        )
    }

    LaunchedEffect(dynamicId) {
        try {
            dynamic = withContext(Dispatchers.IO) {
                userRepository.getDynamicDetail(dynamicId, Prefs.apiType)
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            error = exception.message ?: "动态加载失败"
        }
    }

    LaunchedEffect(dynamic) {
        val current = dynamic ?: return@LaunchedEffect
        liked = current.footer?.liked == true
        likeCount = current.footer?.like ?: 0

        // 引用内容已经在动态详情的 orig 里，不需要再请求一次图文详情
        quotedArticle = current.orig?.toQuotedArticle()
    }

    val pictures = remember(dynamic) {
        dynamic?.draw?.images?.takeIf { it.isNotEmpty() }
            ?: dynamic?.article?.covers.orEmpty().mapIndexed { index, url ->
                Picture(url, 0, 0, "article-cover-$index")
            }
    }
    val quotedPictures = remember(quotedArticle) {
        quotedArticle?.paragraphs.orEmpty()
            .mapNotNull { it.imageUrl }
            .mapIndexed { index, url -> Picture(url, 0, 0, "quoted-cover-$index") }
    }
    val bodyText = remember(dynamic) {
        dynamic?.draw?.text.orEmpty()
            .ifBlank { dynamic?.word?.text.orEmpty() }
            .ifBlank { dynamic?.article?.text.orEmpty() }
            .ifBlank { dynamic?.none?.text.orEmpty() }
    }

    /**
     * 程序滚动后把焦点交还给内容。
     *
     * 焦点所在的列表项被滚出组合范围时 Compose 会清掉焦点，页面上就没有焦点目标了：
     * 方向键不再做焦点搜索，头部的互动按钮也就再也聚焦不到。
     */
    suspend fun restoreContentFocus() {
        restoreListFocus(listState, itemFocus, fromBottom = false)
    }

    LaunchedEffect(dynamic) {
        if (dynamic == null) return@LaunchedEffect
        delay(120)
        restoreContentFocus()
    }

    fun toggleLike() {
        val current = dynamic ?: return
        val id = current.id
        if (id.isNullOrBlank()) {
            "动态尚未加载完成".toast(context)
            return
        }
        if (Prefs.uid <= 0) {
            "请先登录".toast(context)
            return
        }
        scope.launch {
            val target = !liked
            val success = runCatching {
                withContext(Dispatchers.IO) {
                    likeRepository.setDynamicLike(id, target)
                }
            }.isSuccess
            if (success) {
                liked = target
                likeCount = (likeCount + if (target) 1 else -1).coerceAtLeast(0)
            } else {
                (if (target) "点赞失败" else "取消点赞失败").toast(context)
            }
        }
    }

    fun openComments() {
        val current = dynamic ?: return
        if (current.commentId <= 0) {
            "评论尚未加载完成".toast(context)
            return
        }
        showComments = true
    }

    fun share() {
        val url = dynamicShareUrl(dynamic, dynamicId)
        if (url.isBlank()) {
            "暂无可分享的链接".toast(context)
            return
        }
        showShare = true
    }

    /**
     * 进入图片宫格。
     *
     * 方向键默认的焦点搜索按几何位置就近匹配：从正文下移、从下方卡片上移都会落到与焦点
     * 水平对齐的那一张（三列时是中间那张），期望的却是首张/末张，所以这里显式指定落点。
     *
     * @param fromAbove true：从宫格上方进入（落到第一张）；false：从下方进入（落到最后一张）
     */
    suspend fun enterImages(fromAbove: Boolean) {
        if (pictures.isEmpty()) return
        // 只有一张图时首末是同一张，首张的 requester 才是 attached 的那个
        val requester = if (fromAbove || pictures.size == 1) {
            firstImageFocusRequester
        } else {
            lastImageFocusRequester
        }
        focusImageGrid(listState, requester, fromAbove)
    }

    fun enterImagesFromBody() {
        scope.launch { enterImages(fromAbove = true) }
    }

    fun enterImagesFromBelow() {
        scope.launch { enterImages(fromAbove = false) }
    }

    /** 宫格下方那些简单卡片的上键处理：进入宫格落到最后一张 */
    fun gridEnterModifier(): Modifier =
        if (pictures.size > 1) {
            Modifier.enterImageGridOnUp(scope) { enterImagesFromBelow() }
        } else {
            Modifier
        }

    /**
     * 左右键整页跳转。
     *
     * 滚动会把原来的焦点项移出组合范围，焦点随之丢失（之后连互动按钮都聚焦不到），
     * 所以跳转结束后必须把焦点重新交给视口里的内容。
     */
    fun scrollToPage(fromBottom: Boolean) {
        isHorizontalFocusActive = false
        scope.launch {
            val lastIndex = (listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)
            listState.animateScrollToItem(if (fromBottom) lastIndex else 0)
            // 动画期间可能弹出了评论/分享/大图，此时不能把焦点抢回列表
            if (showComments || showShare || imageIndex != null || quotedImageIndex != null) return@launch
            restoreListFocus(listState, itemFocus, fromBottom)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    // 菜单键：显示评论
                    Key.Menu -> {
                        if (!showComments && !showShare && imageIndex == null && quotedImageIndex == null) {
                            openComments()
                            true
                        } else {
                            false
                        }
                    }
                    // 左键回到顶部、右键回到底部；
                    // 焦点在图片宫格或顶部操作按钮时让位给默认的横向移动
                    Key.DirectionLeft -> {
                        if (isHorizontalFocusActive) {
                            false
                        } else {
                            scrollToPage(fromBottom = false)
                            true
                        }
                    }

                    Key.DirectionRight -> {
                        if (isHorizontalFocusActive) {
                            false
                        } else {
                            scrollToPage(fromBottom = true)
                            true
                        }
                    }

                    else -> false
                }
            }
    ) {
        val currentDynamic = dynamic
        Column(Modifier.fillMaxSize()) {
            currentDynamic?.let { current ->
                DetailHeader(
                    title = current.draw?.title.orEmpty()
                        .ifBlank { current.article?.title.orEmpty() },
                    authorName = current.author.author,
                    publishTime = current.author.pubTime,
                    commentCount = current.footer?.comment ?: 0,
                    likeCount = likeCount,
                    liked = liked,
                    onHorizontalFocusChanged = { isHorizontalFocusActive = it },
                    onComment = ::openComments,
                    onLike = ::toggleLike,
                    onShare = ::share
                )
            }

            when {
                error != null -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = error.orEmpty(),
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 18.sp
                    )
                }

                currentDynamic == null -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = "加载中…", fontSize = 18.sp)
                }

                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                    contentPadding = PaddingValues(start = 48.dp, end = 48.dp, bottom = 40.dp)
                ) {
                    currentDynamic.blocked?.let { blocked ->
                        item(key = DynamicBlockedItemKey) {
                            DynamicBlockedCard(blocked)
                        }
                    }

                    currentDynamic.liveRcmd?.let { live ->
                        item(key = DynamicLiveItemKey) {
                            DetailLiveCard(
                                live = live,
                                focusRequester = liveFocusRequester,
                                onClick = {
                                    if (live.roomId <= 0) {
                                        "该直播间信息不完整".toast(context)
                                    } else {
                                        VideoPlayerV3Activity.actionStartLive(
                                            context = context,
                                            roomId = live.roomId,
                                            title = live.title,
                                            upName = currentDynamic.author.author,
                                            watchedText = if (live.online > 0) {
                                                "${live.online} 人气"
                                            } else {
                                                live.watchedText
                                            },
                                            upId = currentDynamic.author.mid,
                                            upFace = currentDynamic.author.avatar
                                        )
                                    }
                                }
                            )
                        }
                    }

                    if (bodyText.isNotBlank()) {
                        item(key = DynamicBodyItemKey) {
                            DetailBody(
                                text = bodyText,
                                focusRequester = bodyFocusRequester,
                                onPreviewKey = { event ->
                                    handleDetailBodyKey(
                                        event = event,
                                        listState = listState,
                                        scope = scope,
                                        itemKey = DynamicBodyItemKey,
                                        onDownAtBottom = if (pictures.isEmpty()) {
                                            null
                                        } else {
                                            ::enterImagesFromBody
                                        }
                                    )
                                }
                            )
                        }
                    }

                    if (pictures.isNotEmpty()) {
                        item(key = DynamicImagesItemKey) {
                            DetailImageGrid(
                                pictures = pictures,
                                firstFocusRequester = firstImageFocusRequester,
                                lastFocusRequester = lastImageFocusRequester,
                                onFocusChanged = { isHorizontalFocusActive = it },
                                onImageClick = { index -> imageIndex = index }
                            )
                        }
                    }

                    currentDynamic.video?.let { video ->
                        item(key = DynamicVideoItemKey) {
                            DetailVideoCard(
                                video = video.toArticleVideo(),
                                focusRequester = videoFocusRequester,
                                modifier = gridEnterModifier(),
                                onClick = {
                                    VideoInfoActivity.actionStart(context, video.aid)
                                }
                            )
                        }
                    }

                    currentDynamic.pgc?.let { pgc ->
                        item(key = DynamicPgcItemKey) {
                            DetailVideoCard(
                                video = ArticleVideo(
                                    aid = pgc.aid,
                                    title = pgc.title,
                                    cover = pgc.cover
                                ),
                                focusRequester = pgcFocusRequester,
                                modifier = gridEnterModifier(),
                                onClick = {
                                    if (pgc.aid > 0) {
                                        VideoInfoActivity.actionStart(context, pgc.aid)
                                    } else {
                                        "暂不支持播放该番剧".toast(context)
                                    }
                                }
                            )
                        }
                    }

                    currentDynamic.article?.let { article ->
                        item(key = DynamicArticleItemKey) {
                            DetailActionChip(
                                text = "打开专栏：${article.title}",
                                modifier = gridEnterModifier(),
                                onClick = { openDynamicUrl(context, article.url) }
                            )
                        }
                    }

                    // 转发动态引用的内容：图文用背景框嵌入，视频用视频卡片
                    val quoted = currentDynamic.orig
                    val currentQuotedArticle = quotedArticle
                    val quotedHasArticleContent = quoted != null &&
                        (quoted.draw != null || quoted.article != null || quoted.word != null)
                    when {
                        currentQuotedArticle != null -> item(key = DynamicQuoteItemKey) {
                            EmbeddedArticleContent(
                                article = currentQuotedArticle,
                                bodyFocusRequester = quotedBodyFocusRequester,
                                lastImageFocusRequester = quotedLastImageFocusRequester,
                                onBodyPreviewKey = { event ->
                                    handleDetailBodyKey(
                                        event = event,
                                        listState = listState,
                                        scope = scope,
                                        itemKey = DynamicQuoteItemKey
                                    )
                                },
                                onFocusChanged = { isHorizontalFocusActive = it },
                                onImageClick = { index -> quotedImageIndex = index },
                                onVideoClick = { aid ->
                                    VideoInfoActivity.actionStart(context, aid)
                                }
                            )
                        }

                        quoted?.video != null && !quotedHasArticleContent -> item(key = DynamicQuoteVideoItemKey) {
                            val quotedVideo = quoted.video ?: return@item
                            DetailVideoCard(
                                video = quotedVideo.toArticleVideo(),
                                focusRequester = quotedVideoFocusRequester,
                                modifier = gridEnterModifier(),
                                onClick = {
                                    VideoInfoActivity.actionStart(context, quotedVideo.aid)
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    val currentDynamic = dynamic
    if (currentDynamic != null) {
        CommentPanel(
            show = showComments,
            oid = currentDynamic.commentId,
            commentType = currentDynamic.commentType.takeIf { it > 0 } ?: 17L,
            onHide = {
                showComments = false
            }
        )
    }

    LaunchedEffect(showComments, showShare, imageIndex, quotedImageIndex) {
        if (!showComments && !showShare && imageIndex == null && quotedImageIndex == null) {
            delay(150)
            restoreContentFocus()
        }
    }

    if (showShare) {
        DetailShareDialog(
            url = dynamicShareUrl(dynamic, dynamicId),
            onDismiss = { showShare = false }
        )
    }

    imageIndex?.let { index ->
        if (index in pictures.indices) {
            FullscreenImageViewer(
                pictures = pictures,
                initialIndex = index,
                onDismiss = { imageIndex = null }
            )
        }
    }

    quotedImageIndex?.let { index ->
        if (index in quotedPictures.indices) {
            FullscreenImageViewer(
                pictures = quotedPictures,
                initialIndex = index,
                onDismiss = { quotedImageIndex = null }
            )
        }
    }
}

@Composable
private fun DetailLiveCard(
    live: DynamicItem.DynamicLiveRcmdModule,
    focusRequester: FocusRequester,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(8.dp)
    Surface(
        modifier = Modifier
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
                    .background(Color.Black.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                if (live.cover.isNotBlank()) {
                    AsyncImage(
                        model = live.cover.resizedImageUrl(ImageSize.WideCover),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                }
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .background(Color.Black.copy(alpha = 0.45f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        modifier = Modifier.size(28.dp),
                        imageVector = Icons.Rounded.PlayArrow,
                        contentDescription = null,
                        tint = Color.White
                    )
                }
            }
            Column(Modifier.padding(start = 16.dp)) {
                Text(
                    text = live.title.ifBlank { "直播" },
                    fontSize = 20.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    LiveStatusChip(
                        status = live.liveStatus,
                        fontSize = 13.sp,
                        horizontalPadding = 6.dp,
                        verticalPadding = 2.dp
                    )
                    val meta = liveMetaText(live)
                    if (meta.isNotBlank()) {
                        Text(
                            text = meta,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "按 OK 进入直播间",
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 13.sp
                )
            }
        }
    }
}

@Composable
private fun DynamicBlockedCard(blocked: DynamicItem.DynamicBlockedModule) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (blocked.iconUrl.isNotBlank()) {
            AsyncImage(
                model = blocked.iconUrl,
                contentDescription = null,
                modifier = Modifier.size(96.dp),
                contentScale = ContentScale.Fit
            )
        }
        if (blocked.title.isNotBlank()) {
            Text(text = blocked.title, fontSize = 22.sp)
        }
        if (blocked.hintMessage.isNotBlank()) {
            Text(
                text = blocked.hintMessage,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                fontSize = 16.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}

private fun dynamicShareUrl(dynamic: DynamicItem?, dynamicId: String): String =
    dynamic?.jumpUrl?.takeIf { it.isNotBlank() } ?: "https://t.bilibili.com/$dynamicId"

private fun openDynamicUrl(context: Context, url: String) {
    // 接口下发的是 //www.bilibili.com/... 这种协议相对地址，直接 ACTION_VIEW 会找不到应用
    val normalized = when {
        url.isBlank() -> ""
        url.startsWith("//") -> "https:$url"
        url.startsWith("http://") || url.startsWith("https://") -> url
        else -> "https://$url"
    }
    try {
        if (normalized.isBlank()) throw IllegalArgumentException("empty url")
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(normalized)))
    } catch (exception: Exception) {
        "无法打开此链接".toast(context)
    }
}
