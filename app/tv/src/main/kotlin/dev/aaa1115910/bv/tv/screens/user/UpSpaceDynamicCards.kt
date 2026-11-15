package dev.aaa1115910.bv.tv.screens.user

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Comment
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.ThumbUp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import dev.aaa1115910.biliapi.entity.Picture
import dev.aaa1115910.biliapi.entity.user.ArticleVideo
import dev.aaa1115910.biliapi.entity.user.DynamicItem
import dev.aaa1115910.biliapi.entity.user.DynamicMajorType
import dev.aaa1115910.biliapi.entity.user.DynamicType
import dev.aaa1115910.biliapi.entity.user.toArticleVideo
import dev.aaa1115910.biliapi.entity.user.toQuotedArticle
import dev.aaa1115910.bv.tv.component.videocard.CardCover
import dev.aaa1115910.bv.util.ImageSize
import dev.aaa1115910.bv.util.resizedImageUrl

/** 动态卡片点击后的目标页面 */
enum class DynamicCardAction { Video, Article, Detail }

/** 卡片主体由哪个内容模块渲染 */
enum class DynamicCardBody { Blocked, Forward, Draw, Article, Video, Live, Fallback }

/**
 * 卡片主体分发。
 *
 * 顺序：充电专属覆盖 → **外层声明类型** → 内容类型（`major.type`）→ 实际存在的模块。
 *
 * - 声明类型才是卡片的语义：图文动态可能附带一个视频（`additional.ugc` 映射成 `video`），
 *   按模块探测会把它当成视频卡，正文和图片全丢。
 * - 充电专属的内容类型是 `MAJOR_TYPE_BLOCKED`，但外层类型仍是 DRAW/AV，所以最先判定。
 * - 期望模块缺失时（例如 `DYNAMIC_TYPE_AV` 没有 `archive`，解析器把正文映射到 `word`）
 *   退到内容类型/实际模块，卡片降级成「只有正文」，不是空白卡。
 */
fun DynamicItem.cardBody(): DynamicCardBody = when {
    blocked != null -> DynamicCardBody.Blocked
    type == DynamicType.Forward -> DynamicCardBody.Forward
    // 文章动态：旧版内容在 major.article，新版「投稿了文章」在 major.opus（解析时已映射成 draw）
    type == DynamicType.Article ->
        if (article != null || draw != null) DynamicCardBody.Article else contentBody()

    type == DynamicType.Draw ->
        if (draw != null) DynamicCardBody.Draw else contentBody()

    type == DynamicType.Av || type == DynamicType.UgcSeason ->
        if (video != null) DynamicCardBody.Video else contentBody()

    type == DynamicType.LiveRcmd ->
        if (liveRcmd != null) DynamicCardBody.Live else contentBody()

    else -> contentBody()
}

/** 声明类型认不出、或期望模块缺失时的兜底：先看内容类型，再看实际存在的模块 */
private fun DynamicItem.contentBody(): DynamicCardBody = when {
    majorType == DynamicMajorType.Archive ->
        if (video != null) DynamicCardBody.Video else DynamicCardBody.Fallback

    majorType == DynamicMajorType.Article ->
        if (article != null) DynamicCardBody.Article else DynamicCardBody.Fallback

    majorType == DynamicMajorType.Opus || majorType == DynamicMajorType.Draw ->
        if (draw != null) DynamicCardBody.Draw else DynamicCardBody.Fallback

    majorType == DynamicMajorType.LiveRcmd || majorType == DynamicMajorType.Live ->
        if (liveRcmd != null) DynamicCardBody.Live else DynamicCardBody.Fallback

    draw != null -> DynamicCardBody.Draw
    article != null -> DynamicCardBody.Article
    video != null -> DynamicCardBody.Video
    liveRcmd != null -> DynamicCardBody.Live
    else -> DynamicCardBody.Fallback
}

/**
 * 决定动态卡片的点击目标：图文（DRAW）进图文详情、视频进视频详情，其余进动态详情。
 *
 * 被屏蔽（充电专属）的动态即使外层类型是 DRAW 也没有图文内容，仍走动态详情。
 * 文章动态的主体是文章预览，即使附带视频也进动态详情（详情页提供「打开专栏」）。
 */
fun DynamicItem.cardAction(): DynamicCardAction = when {
    type == DynamicType.Draw && blocked == null && id?.toLongOrNull() != null ->
        DynamicCardAction.Article

    type == DynamicType.Article -> DynamicCardAction.Detail
    video != null -> DynamicCardAction.Video
    else -> DynamicCardAction.Detail
}

fun DynamicItem.upSpaceTypeLabel(): String = when {
    blocked != null -> "充电专属"
    // 转发动态本身不带内容模块，必须优先判断，否则会被下面的 word 误标成图文
    type == DynamicType.Forward -> ""
    // 文章动态的正文可能来自 major.article，也可能来自新版 major.opus
    type == DynamicType.Article -> "文章"
    // 图文动态即使附加了视频（additional.ugc），主体仍是图文
    type == DynamicType.Draw -> "图文"
    // 视频卡片右上角直接用接口返回的角标文案（动态视频/合作视频/充电专属等）
    video != null -> video?.badgeText.orEmpty()
    word != null -> "图文"
    pgc != null -> "番剧"
    liveRcmd != null -> "直播"
    else -> "动态"
}

/** 头部副标题：发布时间在前、发布动作在后（两者都是接口原值） */
fun DynamicItem.headerSubtitle(): String =
    listOf(author.pubTime, author.pubAction)
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .joinToString(" · ")

data class DynamicImagePreview(
    val urls: List<String>,
    val moreCount: Int
)

/**
 * 文章卡与图文卡共用一套排版。
 *
 * 新版文章动态在解析时已经把 opus 内容映射成 `draw`；旧版专栏动态只有
 * `article.title/text/covers`，这里补一个临时 `DynamicDrawModule` 交给图文卡渲染。
 */
internal fun DynamicItem.articleAsDraw(): DynamicItem.DynamicDrawModule? {
    draw?.let { return it }
    val article = article ?: return null
    val images = article.covers
        .filter { it.isNotBlank() }
        .distinct()
        .mapIndexed { index, url -> Picture(url = url, width = 0, height = 0, key = "article-cover-$index") }
    return DynamicItem.DynamicDrawModule(
        title = article.title.trim().takeIf { !isPlaceholderText(it) },
        text = article.text,
        images = images
    )
}

/** 卡片最多展示三张图，剩余数量用于右下角 `+(n-3)张` */
fun DynamicItem.DynamicDrawModule.imagePreview(limit: Int = 3): DynamicImagePreview {
    val shown = images.take(limit)
    return DynamicImagePreview(
        urls = shown.map { it.url },
        moreCount = (images.size - shown.size).coerceAtLeast(0)
    )
}

/** 卡片最多展示三张图，剩余数量用于右下角 `+(n-3)张` */
fun DynamicItem.imagePreview(limit: Int = 3): DynamicImagePreview =
    draw?.imagePreview(limit) ?: DynamicImagePreview(urls = emptyList(), moreCount = 0)

/**
 * B 站会用 `-`、`——`、`…` 这类占位符表示「没有描述」，这类内容不该在卡片上占一行。
 * 只把「全部由占位符号组成」的短文本判为空，避免误伤正常文案里的破折号。
 */
fun isPlaceholderText(text: String?): Boolean {
    val trimmed = text?.trim().orEmpty()
    if (trimmed.isEmpty()) return true
    if (trimmed.length > 8) return false
    return trimmed.all { it == '-' || it == '—' || it == '–' || it == '·' || it == '.' || it == '…' }
}

data class DynamicVideoTexts(
    /** 封面之上：动态正文（`module_dynamic.desc.text`），与视频标题重复时不显示 */
    val body: String?,
    /** 封面之下：视频标题；视频简介（`archive.desc`）不再展示 */
    val title: String?
)

/**
 * 视频卡的两段文字。
 *
 * 网页端把**动态正文**放在封面上方、**视频标题**放在封面下方，视频简介（`archive.desc`）不展示。
 * 正文与标题经常是同一句话（UP 投稿时 B 站会自动把标题塞进正文），此时只保留封面下方的标题。
 */
fun DynamicItem.dynamicVideoTexts(): DynamicVideoTexts {
    val video = video ?: return DynamicVideoTexts(body = null, title = null)
    val title = video.title.trim().takeIf { it.isNotEmpty() }
    val body = bodyText.trim()
        .takeIf { it.isNotEmpty() && !isPlaceholderText(it) && it != title }
    return DynamicVideoTexts(body = body, title = title)
}

data class DynamicTextBlocks(
    val title: String?,
    val body: String?
)

/**
 * 图文的标题与正文。opus 的 title 经常是 summary 的首句，重复展示会让卡片像是写了两遍，
 * 所以正文里已经包含标题时只保留正文。
 */
fun DynamicItem.drawTextBlocks(): DynamicTextBlocks =
    draw?.textBlocks(video) ?: DynamicTextBlocks(title = null, body = null)

internal fun DynamicItem.DynamicDrawModule.textBlocks(
    video: DynamicItem.DynamicVideoModule?
): DynamicTextBlocks {
    val rawTitle = title?.trim().orEmpty()
    // 图文附带视频时，正文里的「视频：BVxxxx」是给视频卡留的占位，卡片下方已经有视频卡了
    val rawBody = text.trim()
        .let { body -> if (video != null) body.replace(DrawVideoPlaceholder, "").trim() else body }
    val title = rawTitle.takeIf { !isPlaceholderText(it) }
    val body = rawBody.takeIf { !isPlaceholderText(it) }
    return DynamicTextBlocks(
        title = title?.takeIf { !rawBody.contains(it) },
        body = body
    )
}

/** opus 正文里指向附带视频的占位行，例如 `视频：BV1Dony6eEk1` */
private val DrawVideoPlaceholder = Regex("""(?m)^[ \t]*视频[:：]\s*BV[0-9A-Za-z]+[ \t]*$""")

/** 引用块的展示内容：作者、正文与媒体（图文最多 3 张） */
internal data class QuotedContent(
    val author: String,
    val text: String,
    val imageUrls: List<String>,
    val moreCount: Int,
    val video: ArticleVideo?
)

/**
 * 转发引用的原动态。
 *
 * 视频标题固定显示在封面下方，所以不进正文；正文里的「视频：BVxxxx」是给视频卡留的占位。
 */
internal fun DynamicItem.quotedContent(): QuotedContent? {
    val article = orig?.toQuotedArticle() ?: return null
    val video = article.video
    val text = article.title.ifBlank { article.bodyText }
        .replace(DrawVideoPlaceholder, "")
        .trim()
        .takeIf { it.isNotEmpty() && it != video?.title }
        .orEmpty()
    val images = article.paragraphs.mapNotNull { it.imageUrl }
    val shown = images.take(3)
    return QuotedContent(
        author = article.authorName,
        text = text,
        imageUrls = shown,
        moreCount = (images.size - shown.size).coerceAtLeast(0),
        video = video
    )
}

/** 引用视频封面下方的标题；与引用正文重复时不展示 */
internal fun quotedVideoCaption(quoted: QuotedContent): String? {
    val title = quoted.video?.title?.trim().orEmpty()
    return title.takeIf { it.isNotEmpty() && it != quoted.text.trim() }
}

/** 引用视频的播放/弹幕文案，与网页端的「41.2万观看 174弹幕」一致；都缺失时返回 null */
internal fun quotedVideoStatText(video: ArticleVideo): String? =
    listOfNotNull(
        video.play.trim().takeIf { it.isNotEmpty() }?.let { "${it}观看" },
        video.danmaku.trim().takeIf { it.isNotEmpty() }?.let { "${it}弹幕" }
    ).joinToString(" ").takeIf { it.isNotBlank() }

@Composable
fun SpaceDynamicCard(
    dynamic: DynamicItem,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onFocus: () -> Unit
) {
    val draw = dynamic.draw
    val live = dynamic.liveRcmd
    val video = dynamic.video
    val blocked = dynamic.blocked
    val article = dynamic.article
    // 视频/直播/多图/文章封面的类型角标画在媒体上，头部右侧只留给没有媒体的卡片
    val badgeOnMedia = video != null || live != null ||
        !draw?.images.isNullOrEmpty() || !article?.covers.isNullOrEmpty()
    val headerBadge = dynamic.upSpaceTypeLabel().takeIf { it.isNotBlank() && !badgeOnMedia }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { if (it.isFocused) onFocus() },
        onClick = onClick,
        colors = ClickableSurfaceDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
            focusedContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f),
            contentColor = MaterialTheme.colorScheme.onSurface,
            focusedContentColor = MaterialTheme.colorScheme.onSurface,
            pressedContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
        ),
        shape = ClickableSurfaceDefaults.shape(shape = MaterialTheme.shapes.medium),
        scale = ClickableSurfaceDefaults.scale(scale = 1f, focusedScale = 1f),
        border = ClickableSurfaceDefaults.border(
            border = Border(
                border = BorderStroke(2.dp, Color.Transparent),
                shape = MaterialTheme.shapes.medium
            ),
            focusedBorder = Border(
                border = BorderStroke(
                    width = 2.dp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.65f)
                ),
                shape = MaterialTheme.shapes.medium
            )
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            DynamicHeader(dynamic.author, dynamic.headerSubtitle(), headerBadge)
            when (dynamic.cardBody()) {
                DynamicCardBody.Blocked -> blocked?.let { DynamicBlockedBody(it) }
                DynamicCardBody.Forward -> DynamicForwardBody(dynamic)
                DynamicCardBody.Draw -> draw?.let {
                    DynamicDrawBody(
                        draw = it,
                        video = video
                    )
                }

                DynamicCardBody.Article -> dynamic.articleAsDraw()?.let {
                    DynamicDrawBody(
                        draw = it,
                        video = video,
                        badgeText = "文章"
                    )
                } ?: DynamicFallbackBody(dynamic)

                DynamicCardBody.Video -> video?.let { DynamicVideoBody(dynamic, it) }
                DynamicCardBody.Live -> live?.let { DynamicLiveBody(it) }
                DynamicCardBody.Fallback -> DynamicFallbackBody(dynamic)
            }
            DynamicStatBar(dynamic.footer)
        }
    }
}

@Composable
private fun DynamicHeader(
    author: DynamicItem.DynamicAuthorModule,
    subtitle: String,
    badge: String?
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (author.avatar.isNotBlank()) {
            AsyncImage(
                modifier = Modifier
                    .size(32.dp)
                    .background(
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                        shape = CircleShape
                    )
                    .clip(CircleShape),
                model = author.avatar.resizedImageUrl(ImageSize.Icon),
                contentDescription = null,
                contentScale = ContentScale.Crop
            )
            Spacer(modifier = Modifier.width(8.dp))
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = author.author,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (badge != null) {
            Spacer(modifier = Modifier.width(8.dp))
            DynamicBadge(text = badge)
        }
    }
}

@Composable
private fun DynamicVideoBody(dynamic: DynamicItem, video: DynamicItem.DynamicVideoModule) {
    val texts = remember(dynamic) { dynamic.dynamicVideoTexts() }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // 封面上方是动态正文，不是视频标题
        texts.body?.let { body ->
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        CardCover(
            cover = video.cover,
            play = formatCount(video.playCount),
            danmaku = formatCount(video.danmakuCount),
            time = video.duration,
            badges = listOfNotNull(video.badgeText.takeIf { it.isNotBlank() })
        )
        // 封面下方是视频标题，视频简介不展示
        texts.title?.let { title ->
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun DynamicLiveBody(live: DynamicItem.DynamicLiveRcmdModule) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Black.copy(alpha = 0.2f)),
            contentAlignment = Alignment.Center
        ) {
            if (live.cover.isNotBlank()) {
                AsyncImage(
                    modifier = Modifier.fillMaxSize(),
                    model = live.cover.resizedImageUrl(ImageSize.WideCover),
                    contentDescription = null,
                    contentScale = ContentScale.Crop
                )
            }
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .background(Color.Black.copy(alpha = 0.45f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    modifier = Modifier.size(18.dp),
                    imageVector = Icons.Rounded.PlayArrow,
                    contentDescription = null,
                    tint = Color.White
                )
            }
        }
        Text(
            text = live.title,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            LiveStatusChip(status = live.liveStatus)
            val meta = liveMetaText(live)
            if (meta.isNotBlank()) {
                Text(
                    text = meta,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** 直播卡的一行元信息：分区 + 人气（人气为 0 时退回观看数文案） */
internal fun liveMetaText(live: DynamicItem.DynamicLiveRcmdModule): String {
    val areaText = listOf(live.areaName, live.parentAreaName)
        .filter { it.isNotBlank() }
        .distinct()
        .joinToString("·")
    val popularity = if (live.online > 0) "${live.online} 人气" else live.watchedText
    return listOf(areaText, popularity)
        .filter { it.isNotBlank() }
        .joinToString(" · ")
}

/** 直播状态角标；卡片与详情页共用，尺寸由调用方指定 */
@Composable
internal fun LiveStatusChip(
    status: Int,
    fontSize: TextUnit = 11.sp,
    horizontalPadding: Dp = 4.dp,
    verticalPadding: Dp = 1.dp
) {
    val text: String
    val background: Color
    val contentColor: Color
    when (status) {
        1 -> {
            text = "直播中"
            background = MaterialTheme.colorScheme.primary
            contentColor = MaterialTheme.colorScheme.onPrimary
        }

        2 -> {
            text = "轮播"
            background = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
            contentColor = MaterialTheme.colorScheme.onSurface
        }

        else -> {
            text = "未开播"
            background = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
            contentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        }
    }
    Text(
        modifier = Modifier
            .background(background, RoundedCornerShape(4.dp))
            .padding(horizontal = horizontalPadding, vertical = verticalPadding),
        text = text,
        fontSize = fontSize,
        color = contentColor,
        maxLines = 1
    )
}

@Composable
private fun DynamicDrawBody(
    draw: DynamicItem.DynamicDrawModule,
    video: DynamicItem.DynamicVideoModule? = null,
    badgeText: String = "图文"
) {
    val blocks = remember(draw, video) { draw.textBlocks(video) }
    val preview = remember(draw) { draw.imagePreview() }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.title?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        blocks.body?.let {
            Text(
                text = it,
                style = if (blocks.title.isNullOrBlank()) {
                    MaterialTheme.typography.bodyMedium
                } else {
                    MaterialTheme.typography.bodySmall
                },
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                maxLines = if (blocks.title != null) 3 else 5,
                overflow = TextOverflow.Ellipsis
            )
        }
        when {
            draw.images.size == 1 -> Box {
                DynamicSingleImage(draw.images.first())
                DynamicBadge(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp),
                    text = badgeText
                )
            }

            preview.urls.isNotEmpty() -> Box {
                DynamicImageRow(urls = preview.urls, moreCount = preview.moreCount)
                DynamicBadge(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp),
                    text = badgeText
                )
            }
        }
        // 图文附带视频（additional.ugc）时，视频作为小卡跟在图片下方
        if (video != null) {
            DynamicQuotedVideoRow(video.toArticleVideo())
        }
    }
}

@Composable
private fun DynamicSingleImage(image: dev.aaa1115910.biliapi.entity.Picture) {
    val ratio = if (image.height > 0 && image.width > 0) {
        (image.width.toFloat() / image.height).coerceIn(3f / 4f, 16f / 9f)
    } else {
        16f / 9f
    }
    AsyncImage(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(ratio)
            .clip(RoundedCornerShape(8.dp)),
        model = image.url.resizedImageUrl(ImageSize.DynamicSingleImage),
        contentDescription = null,
        contentScale = ContentScale.Crop
    )
}

@Composable
private fun DynamicImageRow(
    urls: List<String>,
    moreCount: Int,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        urls.forEachIndexed { index, url ->
            Box(
                modifier = Modifier
                    .weight(1f)
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
            ) {
                AsyncImage(
                    modifier = Modifier.fillMaxSize(),
                    model = url.resizedImageUrl(ImageSize.DynamicImageCover),
                    contentDescription = null,
                    contentScale = ContentScale.Crop
                )
                if (index == urls.lastIndex && moreCount > 0) {
                    DynamicBadge(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(4.dp),
                        text = "+${moreCount}",
                        icon = Icons.Rounded.Image
                    )
                }
            }
        }
    }
}

/**
 * 引用图文附带的视频：网页端是「左侧 16:9 缩略图（右下角时长）+ 右侧标题与播放/弹幕」的小卡，
 * 图片才是这块的主体，所以视频不占满整卡宽度。
 */
@Composable
private fun DynamicQuotedVideoRow(video: ArticleVideo) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .width(88.dp)
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
        ) {
            if (video.cover.isNotBlank()) {
                AsyncImage(
                    modifier = Modifier.fillMaxSize(),
                    model = video.cover.resizedImageUrl(ImageSize.UgcEpisodeCover),
                    contentDescription = null,
                    contentScale = ContentScale.Crop
                )
            }
            if (video.duration.isNotBlank()) {
                Text(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(3.dp)
                        .background(
                            color = Color.Black.copy(alpha = 0.55f),
                            shape = RoundedCornerShape(3.dp)
                        )
                        .padding(horizontal = 3.dp),
                    text = video.duration,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    maxLines = 1
                )
            }
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = video.title,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            quotedVideoStatText(video)?.let { stat ->
                Text(
                    text = stat,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun DynamicForwardBody(dynamic: DynamicItem) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val reason = remember(dynamic) { dynamic.word?.text?.trim().orEmpty() }
    val quoted = remember(dynamic) { dynamic.quotedContent() }
        if (reason.isNotEmpty()) {
            Text(
                text = reason,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                maxLines = ForwardReasonMaxLines,
                overflow = TextOverflow.Ellipsis
            )
        }
        quoted?.let { quoted ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                colors = SurfaceDefaults.colors(
                    containerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
                ),
                shape = MaterialTheme.shapes.small
            ) {
                Column(
                    modifier = Modifier.padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (quoted.author.isNotBlank()) {
                        Text(
                            text = "@${quoted.author}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (quoted.text.isNotBlank()) {
                        Text(
                            text = quoted.text,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    // 「图文带视频」是图文动态额外挂了一个视频卡片（additional.ugc），
                    // 图片和视频要一起显示，不能二选一
                    if (quoted.imageUrls.isNotEmpty()) {
                        DynamicImageRow(urls = quoted.imageUrls, moreCount = quoted.moreCount)
                    }
                    val quotedVideo = quoted.video
                    when {
                        quotedVideo == null -> Unit
                        // 图文带视频：图片是主体，视频缩成左侧缩略图 + 右侧标题/统计
                        quoted.imageUrls.isNotEmpty() -> DynamicQuotedVideoRow(quotedVideo)
                        else -> {
                            CardCover(
                                cover = quotedVideo.cover,
                                play = quotedVideo.play,
                                danmaku = quotedVideo.danmaku,
                                time = quotedVideo.duration
                            )
                            quotedVideoCaption(quoted)?.let { caption ->
                                Text(
                                    text = caption,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 转发理由最多两行，超长省略；被引用内容才是这张卡的主体 */
private const val ForwardReasonMaxLines = 2

@Composable
private fun DynamicBlockedBody(blocked: DynamicItem.DynamicBlockedModule) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        colors = SurfaceDefaults.colors(
            containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Box {
            if (blocked.bgImageUrl.isNotBlank()) {
                AsyncImage(
                    modifier = Modifier
                        .matchParentSize()
                        .clip(RoundedCornerShape(12.dp)),
                    model = blocked.bgImageUrl.resizedImageUrl(ImageSize.LargeCover),
                    contentDescription = null,
                    alpha = 0.12f,
                    contentScale = ContentScale.Crop
                )
            }
            // 瀑布流列宽有限，标题/提示与按钮横排会把文字挤成竖排，
            // 因此按钮单独占一行靠右，文字拿满整卡宽度
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val title = blocked.title.trim().ifBlank { blocked.hintMessage.trim() }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (blocked.iconUrl.isNotBlank()) {
                        AsyncImage(
                            modifier = Modifier.size(32.dp),
                            model = blocked.iconUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Fit
                        )
                    }
                    if (title.isNotEmpty()) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                if (blocked.hintMessage.isNotBlank() && blocked.hintMessage.trim() != title) {
                    Text(
                        text = blocked.hintMessage,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (blocked.buttonText.isNotBlank()) {
                    Text(
                        modifier = Modifier
                            .align(Alignment.End)
                            .background(
                                color = MaterialTheme.colorScheme.primary,
                                shape = RoundedCornerShape(50)
                            )
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                        text = blocked.buttonText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimary,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@Composable
private fun DynamicFallbackBody(dynamic: DynamicItem) {
    val text = listOfNotNull(
        dynamic.word?.text,
        dynamic.article?.title,
        dynamic.article?.text,
        dynamic.pgc?.title,
        dynamic.none?.text
    ).firstOrNull { it.isNotBlank() }
    val cover = dynamic.article?.covers?.firstOrNull()
        ?: dynamic.pgc?.cover
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!text.isNullOrBlank()) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (!cover.isNullOrBlank()) {
            AsyncImage(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 10f)
                    .clip(RoundedCornerShape(8.dp)),
                model = cover.resizedImageUrl(ImageSize.SmallVideoCardCover),
                contentDescription = null,
                contentScale = ContentScale.Crop
            )
        }
    }
}

@Composable
private fun DynamicStatBar(footer: DynamicItem.DynamicFooterModule?) {
    val share = footer?.share ?: 0
    val comment = footer?.comment ?: 0
    val like = footer?.like ?: 0
    val liked = footer?.liked == true
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        DynamicStatItem(
            modifier = Modifier.weight(1f),
            icon = Icons.Filled.Share,
            count = share,
            label = "转发"
        )
        DynamicStatItem(
            modifier = Modifier.weight(1f),
            icon = Icons.AutoMirrored.Filled.Comment,
            count = comment,
            label = "评论"
        )
        DynamicStatItem(
            modifier = Modifier.weight(1f),
            icon = if (liked) Icons.Rounded.ThumbUp else Icons.Outlined.ThumbUp,
            count = like,
            label = "赞",
            highlighted = liked
        )
    }
}

@Composable
private fun DynamicStatItem(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    count: Int,
    label: String,
    highlighted: Boolean = false
) {
    val text = formatCount(count).ifBlank { label }
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Icon(
            modifier = Modifier.size(12.dp),
            imageVector = icon,
            contentDescription = null,
            tint = if (highlighted) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            }
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = if (highlighted) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            },
            maxLines = 1
        )
    }
}

@Composable
internal fun DynamicBadge(
    modifier: Modifier = Modifier,
    text: String,
    icon: ImageVector? = null
) {
    Row(
        modifier = modifier
            .background(
                color = Color.Black.copy(alpha = 0.35f),
                shape = MaterialTheme.shapes.extraSmall
            )
            .padding(horizontal = 4.dp, vertical = 0.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(
                modifier = Modifier.size(12.dp),
                imageVector = icon,
                contentDescription = null,
                tint = Color.White
            )
            Spacer(modifier = Modifier.width(2.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
            maxLines = 1
        )
    }
}
