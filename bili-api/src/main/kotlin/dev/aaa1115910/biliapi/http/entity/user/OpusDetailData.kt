package dev.aaa1115910.biliapi.http.entity.user

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 图文（opus）详情：`/x/polymer/web-dynamic/v1/opus/detail`。
 *
 * 与 `/x/polymer/web-dynamic/v1/detail` 的两个关键区别：
 * - `modules` 是**数组**，靠 `module_type`（`MODULE_TYPE_AUTHOR` / `MODULE_TYPE_CONTENT` /
 *   `MODULE_TYPE_STAT`）区分内容，不是按字段名分模块。
 * - 只有这里的 `module_stat` 带 `favorite`：动态详情接口返回的 `module_stat` 只有
 *   转发/评论/点赞，图文详情页的收藏数与「我已收藏」状态只能从这里取。
 */
@Serializable
data class OpusDetailData(
    val item: OpusDetailItem? = null
)

@Serializable
data class OpusDetailItem(
    @SerialName("id_str")
    val id: String = "",
    val basic: OpusDetailBasic = OpusDetailBasic(),
    val modules: List<OpusDetailModule> = emptyList()
)

@Serializable
data class OpusDetailBasic(
    /** 图文没有独立标题时，这里是自动生成的「{用户}的动态 - 哔哩哔哩」 */
    val title: String = "",
    @SerialName("comment_id_str")
    val commentId: String = "",
    @SerialName("comment_type")
    val commentType: Long = 0
)

@Serializable
data class OpusDetailModule(
    @SerialName("module_type")
    val moduleType: String = "",
    @SerialName("module_top")
    val top: OpusDetailTop? = null,
    @SerialName("module_title")
    val title: OpusDetailTitle? = null,
    @SerialName("module_author")
    val author: OpusDetailAuthor? = null,
    @SerialName("module_content")
    val content: OpusDetailContent? = null,
    @SerialName("module_stat")
    val stat: OpusDetailStat? = null
)

/**
 * `MODULE_TYPE_TOP`：图集的图片只在这里出现（`module_content.paragraphs` 只有文字），
 * 纯图文帖子的图片全靠它，漏解析会让详情页只剩正文。
 */
@Serializable
data class OpusDetailTop(
    val display: OpusDetailTopDisplay? = null
)

@Serializable
data class OpusDetailTopDisplay(
    /** 1 = 图集，2 = 视频 */
    val type: Int = 0,
    val album: OpusDetailAlbum? = null
)

@Serializable
data class OpusDetailAlbum(
    val pics: List<OpusDetailPicture> = emptyList()
)

/** `MODULE_TYPE_TITLE`：`basic.title` 是网页 `<title>`，真正的标题在这里 */
@Serializable
data class OpusDetailTitle(
    val text: String = ""
)

@Serializable
data class OpusDetailAuthor(
    val name: String = "",
    @SerialName("pub_time")
    val pubTime: String = ""
)

@Serializable
data class OpusDetailContent(
    val paragraphs: List<OpusDetailParagraph> = emptyList()
)

@Serializable
data class OpusDetailParagraph(
    val text: OpusDetailText? = null,
    val pic: OpusDetailPic? = null,
    @SerialName("link_card")
    val linkCard: OpusDetailLinkCard? = null
)

@Serializable
data class OpusDetailText(
    val nodes: List<OpusDetailTextNode> = emptyList()
)

@Serializable
data class OpusDetailTextNode(
    val word: OpusDetailWord? = null,
    val rich: OpusDetailRich? = null
)

@Serializable
data class OpusDetailWord(
    val words: String = ""
)

/** 富文本节点：`text` 是正文展示文案（视频类节点是 BV 号），`orig_text` 是原始文案 */
@Serializable
data class OpusDetailRich(
    val text: String = "",
    @SerialName("orig_text")
    val origText: String = ""
)

@Serializable
data class OpusDetailPic(
    val pics: List<OpusDetailPicture> = emptyList()
)

@Serializable
data class OpusDetailPicture(
    val url: String = "",
    val width: Int = 0,
    val height: Int = 0
)

@Serializable
data class OpusDetailLinkCard(
    val card: OpusDetailCard? = null
)

@Serializable
data class OpusDetailCard(
    val type: String = "",
    val ugc: OpusDetailUgc? = null
)

/** 图文正文里的视频卡（`LINK_CARD_TYPE_UGC`），字段与 `additional.ugc` 同构 */
@Serializable
data class OpusDetailUgc(
    @SerialName("id_str")
    val id: String = "",
    val title: String = "",
    val cover: String = "",
    val duration: String = "",
    @SerialName("jump_url")
    val jumpUrl: String = "",
    val stat: OpusDetailUgcStat? = null
)

@Serializable
data class OpusDetailUgcStat(
    val play: String = "",
    val danmaku: String = ""
)

@Serializable
data class OpusDetailStat(
    val forward: OpusDetailStatItem? = null,
    val comment: OpusDetailStatItem? = null,
    val like: OpusDetailStatItem? = null,
    val favorite: OpusDetailStatItem? = null
)

@Serializable
data class OpusDetailStatItem(
    val status: Boolean = false,
    val count: Int = 0
)
