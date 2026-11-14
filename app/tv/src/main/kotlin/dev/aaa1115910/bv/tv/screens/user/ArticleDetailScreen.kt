package dev.aaa1115910.bv.tv.screens.user

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.aaa1115910.biliapi.entity.Picture
import dev.aaa1115910.biliapi.entity.user.ArticleViewData
import dev.aaa1115910.biliapi.repositories.FavoriteRepository
import dev.aaa1115910.biliapi.repositories.LikeRepository
import dev.aaa1115910.biliapi.repositories.UserRepository
import dev.aaa1115910.bv.tv.activities.video.VideoInfoActivity
import dev.aaa1115910.bv.tv.component.CommentPanel
import dev.aaa1115910.bv.tv.component.FullscreenImageViewer
import dev.aaa1115910.bv.util.Prefs
import dev.aaa1115910.bv.util.toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

private const val ArticleBodyItemKey = "article-body"
private const val ArticleImagesItemKey = "article-images"
private const val ArticleVideoItemKey = "article-video"

@Composable
fun ArticleDetailScreen(
    articleId: Long,
    userRepository: UserRepository = koinInject(),
    likeRepository: LikeRepository = koinInject(),
    favoriteRepository: FavoriteRepository = koinInject()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    var article by remember(articleId) { mutableStateOf<ArticleViewData?>(null) }
    var error by remember(articleId) { mutableStateOf<String?>(null) }
    var imageIndex by remember(articleId) { mutableStateOf<Int?>(null) }
    var showComments by remember(articleId) { mutableStateOf(false) }
    var showShare by remember(articleId) { mutableStateOf(false) }
    var liked by remember(articleId) { mutableStateOf(false) }
    var favorited by remember(articleId) { mutableStateOf(false) }
    var likeCount by remember(articleId) { mutableIntStateOf(0) }
    var favoriteCount by remember(articleId) { mutableIntStateOf(0) }
    // 焦点在图片宫格或顶部操作按钮上时，左右键需要留给焦点移动
    var isHorizontalFocusActive by remember(articleId) { mutableStateOf(false) }

    val bodyFocusRequester = remember { FocusRequester() }
    val firstImageFocusRequester = remember { FocusRequester() }
    val lastImageFocusRequester = remember { FocusRequester() }
    val videoFocusRequester = remember { FocusRequester() }
    val itemFocus = remember {
        mapOf(
            ArticleBodyItemKey to DetailItemFocus(bodyFocusRequester),
            ArticleImagesItemKey to DetailItemFocus(firstImageFocusRequester, lastImageFocusRequester),
            ArticleVideoItemKey to DetailItemFocus(videoFocusRequester)
        )
    }

    LaunchedEffect(articleId) {
        try {
            article = withContext(Dispatchers.IO) {
                userRepository.getSpaceArticle(articleId)
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            error = exception.message ?: "图文加载失败"
        }
    }

    LaunchedEffect(article) {
        article?.let {
            liked = it.liked
            favorited = it.favorited
            likeCount = it.likeCount
            favoriteCount = it.favoriteCount
        }
    }

    val pictures = remember(article) {
        article?.paragraphs.orEmpty()
            .mapNotNull { it.imageUrl }
            .mapIndexed { index, url -> Picture(url, 0, 0, "article-image-$index") }
    }
    val bodyText = article?.bodyText.orEmpty()

    /**
     * 程序滚动后把焦点交还给内容。
     *
     * 焦点所在的列表项被滚出组合范围时 Compose 会清掉焦点，页面上就没有焦点目标了：
     * 方向键不再做焦点搜索，头部的互动按钮也就再也聚焦不到。
     */
    suspend fun restoreContentFocus() {
        if (article == null) return
        restoreListFocus(listState, itemFocus, fromBottom = false)
    }

    LaunchedEffect(article) {
        if (article == null) return@LaunchedEffect
        delay(120)
        restoreContentFocus()
    }

    fun toggleLike() {
        val current = article ?: return
        if (Prefs.uid <= 0) {
            "请先登录".toast(context)
            return
        }
        scope.launch {
            val target = !liked
            val success = runCatching {
                withContext(Dispatchers.IO) {
                    likeRepository.setDynamicLike(current.id, target)
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

    fun toggleFavorite() {
        if (Prefs.uid <= 0) {
            "请先登录".toast(context)
            return
        }
        scope.launch {
            val target = !favorited
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    favoriteRepository.setOpusFavorite(opusId = articleId, favorite = target)
                }
            }
            if (result.isSuccess) {
                favorited = target
                favoriteCount = (favoriteCount + if (target) 1 else -1).coerceAtLeast(0)
            } else {
                // 服务端 message 比笼统的「收藏失败」有用，例如风控或凭证问题
                result.exceptionOrNull()?.message?.takeIf { it.isNotBlank() }?.toast(context)
                    ?: (if (target) "收藏失败" else "取消收藏失败").toast(context)
            }
        }
    }

    fun openComments() {
        val current = article ?: return
        if (current.commentId <= 0) {
            "评论尚未加载完成".toast(context)
            return
        }
        showComments = true
    }

    fun share() {
        val url = article?.displayJumpUrl.orEmpty()
        if (url.isBlank()) {
            "暂无可分享的链接".toast(context)
            return
        }
        showShare = true
    }

    /**
     * 进入图片宫格。
     *
     * 方向键默认的焦点搜索按几何位置就近匹配：从正文下移、从下方视频卡上移都会落到与焦点
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
            if (showComments || showShare || imageIndex != null) return@launch
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
                        if (!showComments && !showShare && imageIndex == null) {
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
        Column(Modifier.fillMaxSize()) {
            article?.let { current ->
                DetailHeader(
                    title = current.title,
                    authorName = current.authorName,
                    publishTime = current.publishTime,
                    commentCount = current.commentCount,
                    likeCount = likeCount,
                    favoriteCount = favoriteCount,
                    liked = liked,
                    favorited = favorited,
                    onHorizontalFocusChanged = { isHorizontalFocusActive = it },
                    onComment = ::openComments,
                    onLike = ::toggleLike,
                    onFavorite = ::toggleFavorite,
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

                article == null -> Box(
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
                    if (bodyText.isNotBlank()) {
                        item(key = ArticleBodyItemKey) {
                            DetailBody(
                                text = bodyText,
                                focusRequester = bodyFocusRequester,
                                onPreviewKey = { event ->
                                    handleDetailBodyKey(
                                        event = event,
                                        listState = listState,
                                        scope = scope,
                                        itemKey = ArticleBodyItemKey,
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
                        item(key = ArticleImagesItemKey) {
                            DetailImageGrid(
                                pictures = pictures,
                                firstFocusRequester = firstImageFocusRequester,
                                lastFocusRequester = lastImageFocusRequester,
                                onFocusChanged = { isHorizontalFocusActive = it },
                                onImageClick = { index -> imageIndex = index }
                            )
                        }
                    }

                    article?.video?.let { video ->
                        item(key = ArticleVideoItemKey) {
                            DetailVideoCard(
                                video = video,
                                focusRequester = videoFocusRequester,
                                modifier = if (pictures.size > 1) {
                                    Modifier.enterImageGridOnUp(scope) {
                                        enterImages(fromAbove = false)
                                    }
                                } else {
                                    Modifier
                                },
                                onClick = {
                                    VideoInfoActivity.actionStart(context, video.aid)
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    val currentArticle = article
    if (currentArticle != null) {
        CommentPanel(
            show = showComments,
            oid = currentArticle.commentId,
            commentType = currentArticle.commentType.takeIf { it > 0 } ?: 11L,
            onHide = {
                showComments = false
            }
        )
    }

    LaunchedEffect(showComments, showShare, imageIndex) {
        if (!showComments && !showShare && imageIndex == null) {
            delay(150)
            restoreContentFocus()
        }
    }

    if (showShare) {
        DetailShareDialog(
            url = article?.displayJumpUrl.orEmpty(),
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
}
