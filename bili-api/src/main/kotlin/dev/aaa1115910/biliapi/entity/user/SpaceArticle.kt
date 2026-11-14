package dev.aaa1115910.biliapi.entity.user

import dev.aaa1115910.biliapi.http.entity.user.SpaceArticleData
import dev.aaa1115910.biliapi.http.entity.user.SpaceArticleItem
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

data class SpaceArticlePage(
    val articles: List<SpaceArticle>,
    val nextOffset: String,
    val hasMore: Boolean
) {
    companion object {
        fun fromWebSpaceArticleData(data: SpaceArticleData) = SpaceArticlePage(
            articles = data.items.mapNotNull(SpaceArticleItem::toSpaceArticle),
            nextOffset = data.offset,
            hasMore = data.hasMore
        )
    }
}

data class SpaceArticle(
    val id: Long,
    val title: String,
    val cover: String,
    val likeCount: Long
)

private fun SpaceArticleItem.toSpaceArticle(): SpaceArticle? {
    val articleId = opusId?.toLongOrNull()?.takeIf { it > 0 } ?: return null

    return SpaceArticle(
        id = articleId,
        title = content.orEmpty(),
        cover = cover?.url.orEmpty(),
        likeCount = stat?.like.asLongOrZero()
    )
}

private fun kotlinx.serialization.json.JsonElement?.asLongOrZero() =
    (this as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: 0L
