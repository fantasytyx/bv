package dev.aaa1115910.biliapi.http.entity.user

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class SpaceArticleData(
    val items: List<SpaceArticleItem> = emptyList(),
    val offset: String = "",
    @SerialName("has_more")
    val hasMore: Boolean = false
)

@Serializable
data class SpaceArticleItem(
    @SerialName("opus_id")
    val opusId: String? = null,
    val content: String? = null,
    val cover: SpaceArticleCover? = null,
    val stat: SpaceArticleStats? = null
)

@Serializable
data class SpaceArticleCover(
    val url: String? = null
)

@Serializable
data class SpaceArticleStats(
    val like: JsonElement? = null
)
