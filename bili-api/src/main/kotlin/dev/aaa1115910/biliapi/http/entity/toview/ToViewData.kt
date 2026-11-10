// package dev.aaa1115910.biliapi.http.entity.history
package dev.aaa1115910.biliapi.http.entity.toview

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ToViewData(
    // val cursor: Cursor,
    // val tab: List<TabItem>,
    val list: List<ToViewItem> = emptyList()
) {
    /**
     * 历史记录页面信息
     *
     * @param max 最后一项目标 id 见请求参数
     * @param viewAt 最后一项时间节点 时间戳
     * @param business 最后一项业务类型 见请求参数
     * @param ps 每页项数
     */
    @Serializable
    data class Cursor(
        val max: Long,
        @SerialName("view_at")
        val viewAt: Long,
        val business: String,
        val ps: Int
    )

    /**
     * 历史记录筛选类型
     *
     * @param type 类型
     * @param name 类型名
     */
    @Serializable
    data class TabItem(
        val type: String,
        val name: String
    )
}

@Serializable
data class ToViewItem(
    var aid: Long = 0,
    var bvid: String = "",
    var cid: Long = 0,
    var owner: Owner? = null,
    val title: String = "",
    // @SerialName("long_title")
    // val longTitle: String,
    val pic: String = "",
    // val cover: String,
    // val covers: List<String>? = null,
    // val uri: String,
    // val history: HistoryInfo,
    val videos: Int = 1,
    // @SerialName("author_name")
    // val authorName: String,
    // @SerialName("author_face")
    // val authorFace: String,
    // @SerialName("author_mid")
    // val authorMid: Long,
    // @SerialName("view_at")
    // val viewAt: Int,
    val progress: Int = 0,
    // val badge: String,
    // @SerialName("show_title")
    // val showTitle: String,
    val duration: Int = 0,
    // val current: String,
    // val total: Int,
    // @SerialName("new_desc")
    // val newDesc: String,
    // @SerialName("is_finish")
    // val isFinish: Int,
    // @SerialName("is_fav")
    // val isFav: Int,
    // val kid: Int,
    val pubdate: Long = 0,
    val stat: Stat? = null,
    /** 是否为 PGC 内容（番剧、影视、纪录片等） */
    @SerialName("is_pgc")
    val isPgc: Boolean = false,
    /** PGC 条目的剧集/番剧信息，普通稿件为 null */
    val bangumi: Bangumi? = null,
    /** PGC 条目的类型角标，如「番剧」「电影」 */
    @SerialName("pgc_label")
    val pgcLabel: String = "",
    /** PGC 条目对应的播放页地址 */
    @SerialName("redirect_url")
    val redirectUrl: String = ""
) {
    @Serializable
    data class Owner(
        val name: String = "",
        val mid: Long = 0,
        val face: String = ""
    )

    @Serializable
    data class Bangumi(
        @SerialName("ep_id")
        val epId: Int = 0,
        val season: Season? = null
    )

    @Serializable
    data class Season(
        @SerialName("season_id")
        val seasonId: Int = 0,
        val title: String = ""
    )
    // @Serializable
    // data class HistoryInfo(
    //     val oid: Long,
    //     val epid: Int,
    //     val bvid: String,
    //     val page: Int,
    //     val cid: Long,
    //     val part: String,
    //     val business: String,
    //     val dt: Int
    // )

    @Serializable
    data class Stat(
        val view: Long = 0,
        val danmaku: Int = 0,
        val reply: Int = 0,
        val favorite: Int = 0,
        val coin: Int = 0,
        val share: Int = 0,
        val like: Int = 0
    )
}
