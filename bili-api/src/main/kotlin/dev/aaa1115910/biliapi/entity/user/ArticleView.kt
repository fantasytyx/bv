package dev.aaa1115910.biliapi.entity.user

import dev.aaa1115910.biliapi.http.entity.user.OpusDetailData
import dev.aaa1115910.biliapi.http.entity.user.OpusDetailText
import dev.aaa1115910.biliapi.http.entity.user.OpusDetailUgc
import dev.aaa1115910.biliapi.util.extractArchiveId

/**
 * 图文/动态详情页的展示模型。
 *
 * 图文详情只有一个数据源：`/x/polymer/web-dynamic/v1/opus/detail`（正文、图片、附带视频、
 * 互动数**含收藏**、评论 id 都在里面），见
 * [dev.aaa1115910.biliapi.repositories.UserRepository.getSpaceArticle]。
 *
 * 动态详情页与转发引用仍走 `/x/polymer/web-dynamic/v1/detail`：那个接口的 `module_stat`
 * 没有 `favorite`，但动态卡片本来就不展示收藏数。
 */
data class ArticleViewData(
    val id: String,
    val title: String,
    val paragraphs: List<ArticleParagraph>,
    val authorName: String = "",
    val publishTime: String = "",
    val likeCount: Int = 0,
    val commentCount: Int = 0,
    val favoriteCount: Int = 0,
    val liked: Boolean = false,
    val favorited: Boolean = false,
    val commentId: Long = 0L,
    val commentType: Long = 0L,
    val jumpUrl: String = "",
    val video: ArticleVideo? = null
) {
    val displayJumpUrl: String
        get() = jumpUrl.normalizeUrl().ifBlank {
            if (id.isNotBlank()) "https://www.bilibili.com/opus/$id" else ""
        }

    /** 正文完整内容（不做任何截断或与标题去重） */
    val bodyText: String
        get() = paragraphs
            .filter { it.text.isNotBlank() }
            .joinToString(separator = "\n") { it.text.trim() }

    companion object {
        fun fromDynamic(dynamic: DynamicItem): ArticleViewData {
            val draw = dynamic.draw
            val images = draw?.images.orEmpty().map { it.url }
                .ifEmpty { dynamic.article?.covers.orEmpty() }
            val text = listOf(
                draw?.text,
                dynamic.word?.text,
                dynamic.article?.text,
                dynamic.bodyText,
                dynamic.blocked?.hintMessage
            ).firstOrNull { !it.isNullOrBlank() }.orEmpty().trim()
            val paragraphs = buildList {
                if (text.isNotBlank()) add(ArticleParagraph(text = text))
                images.filter { it.isNotBlank() }.forEach { add(ArticleParagraph(imageUrl = it)) }
            }

            return ArticleViewData(
                id = dynamic.id.orEmpty(),
                // opus 详情里的「{用户}的动态 - 哔哩哔哩」是自动生成的，不当作标题
                title = draw?.title.orEmpty()
                    .ifBlank { dynamic.article?.title.orEmpty() }
                    .trim()
                    .takeUnless { it.isAutoOpusTitle() }
                    .orEmpty(),
                paragraphs = paragraphs,
                authorName = dynamic.author.author,
                publishTime = dynamic.author.pubTime,
                likeCount = dynamic.footer?.like ?: 0,
                commentCount = dynamic.footer?.comment ?: 0,
                favoriteCount = dynamic.footer?.favorite ?: 0,
                liked = dynamic.footer?.liked == true,
                favorited = dynamic.footer?.favorited == true,
                commentId = dynamic.commentId,
                commentType = dynamic.commentType,
                jumpUrl = dynamic.jumpUrl.orEmpty(),
                video = dynamic.video?.toArticleVideo()
            )
        }

        /**
         * 图文详情（`/web-dynamic/v1/opus/detail`）。
         *
         * 收藏数与「我已收藏」只在 `module_stat.favorite` 里，动态详情接口没有这个字段。
         */
        fun fromOpusDetail(data: OpusDetailData): ArticleViewData {
            val item = requireNotNull(data.item) { "图文详情缺失" }
            val modules = item.modules
            val author = modules.firstNotNullOfOrNull { it.author }
            val stat = modules.firstNotNullOfOrNull { it.stat }
            val content = modules.firstNotNullOfOrNull { it.content }
            // basic.title 是网页 <title>（「{用户}的动态 - 哔哩哔哩」），真实标题在 MODULE_TYPE_TITLE
            val moduleTitle = modules.firstNotNullOfOrNull {
                it.title?.text?.takeIf { title -> title.isNotBlank() }
            }
            // 纯图集帖的图片只在 MODULE_TYPE_TOP 的 album 里，paragraphs 只有文字
            val albumPics = modules.firstNotNullOfOrNull { it.top?.display?.album?.pics }.orEmpty()

            val paragraphs = buildList {
                content?.paragraphs.orEmpty().forEach { paragraph ->
                    paragraph.text?.toPlainText()?.takeIf { it.isNotBlank() }
                        ?.let { add(ArticleParagraph(text = it)) }
                    paragraph.pic?.pics.orEmpty()
                        .filter { it.url.isNotBlank() }
                        .forEach { add(ArticleParagraph(imageUrl = it.url)) }
                }
                val existingImageUrls = mapNotNull { it.imageUrl }.toSet()
                albumPics.asSequence()
                    .map { it.url }
                    .filter { it.isNotBlank() && it !in existingImageUrls }
                    .distinct()
                    .forEach { add(ArticleParagraph(imageUrl = it)) }
            }

            return ArticleViewData(
                id = item.id,
                title = (moduleTitle ?: item.basic.title).toOpusTitle(),
                paragraphs = paragraphs,
                authorName = author?.name.orEmpty(),
                publishTime = author?.pubTime.orEmpty(),
                likeCount = stat?.like?.count ?: 0,
                commentCount = stat?.comment?.count ?: 0,
                favoriteCount = stat?.favorite?.count ?: 0,
                liked = stat?.like?.status == true,
                favorited = stat?.favorite?.status == true,
                commentId = item.basic.commentId.toLongOrNull() ?: 0L,
                commentType = item.basic.commentType,
                video = content?.paragraphs.orEmpty()
                    .firstNotNullOfOrNull { it.linkCard?.card?.ugc }
                    ?.toArticleVideo()
            )
        }
    }
}

private fun OpusDetailText.toPlainText(): String = nodes.joinToString(separator = "") { node ->
    node.word?.words?.takeIf { it.isNotEmpty() }
        ?: node.rich?.text?.takeIf { it.isNotEmpty() }
        ?: node.rich?.origText.orEmpty()
}

/** 图文正文里的视频卡：`id_str` 是 avid，`jump_url` 是 BV 链接（取不到 id 时兜底） */
private fun OpusDetailUgc.toArticleVideo(): ArticleVideo? {
    val aid = id.toLongOrNull()?.takeIf { it > 0 }
        ?: extractArchiveId(jumpUrl)
        ?: return null
    return ArticleVideo(
        aid = aid,
        title = title,
        cover = cover,
        duration = duration,
        play = stat?.play.orEmpty(),
        danmaku = stat?.danmaku.orEmpty()
    )
}

data class ArticleParagraph(
    val text: String = "",
    val imageUrl: String? = null
)

data class ArticleVideo(
    val aid: Long,
    val bvid: String? = null,
    val title: String = "",
    val cover: String = "",
    val duration: String = "",
    val play: String = "",
    val danmaku: String = ""
)

fun DynamicItem.DynamicVideoModule.toArticleVideo() = ArticleVideo(
    aid = aid,
    bvid = bvid,
    title = title,
    cover = cover,
    duration = duration,
    play = play,
    danmaku = danmaku
)

/**
 * 把转发动态引用的原动态转成可内嵌的内容。
 *
 * 没有可展示内容（标题、正文、图片、视频全空）时返回 null。
 */
fun DynamicItem.toQuotedArticle(): ArticleViewData? {
    val article = ArticleViewData.fromDynamic(this)
    val hasContent = article.title.isNotBlank() ||
        article.bodyText.isNotBlank() ||
        article.paragraphs.any { it.imageUrl != null } ||
        article.video != null
    return article.takeIf { hasContent }
}

private fun String.isAutoOpusTitle(): Boolean {
    val title = trim()
    return title.isEmpty() ||
        title.endsWith("的动态") ||
        title.endsWith("的动态 - 哔哩哔哩")
}

/**
 * opus 详情的标题。
 *
 * `basic.title` 是网页 `<title>`：有标题时是「{标题} - 哔哩哔哩」，没有独立标题时是
 * 「{用户}的动态 - 哔哩哔哩」，后者不能当标题展示。
 */
private fun String.toOpusTitle(): String =
    removeSuffix(" - 哔哩哔哩").trim().takeUnless { it.isAutoOpusTitle() }.orEmpty()

private fun String.normalizeUrl(): String = when {
    startsWith("//") -> "https:$this"
    startsWith("http://") || startsWith("https://") -> this
    isBlank() -> this
    else -> "https://$this"
}
