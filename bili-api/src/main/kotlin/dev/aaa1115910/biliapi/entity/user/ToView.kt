package dev.aaa1115910.biliapi.entity.user

import bilibili.app.interfaces.v1.CursorItem

//TODO 暂时仅解析 UGC 和 PGC
data class ToViewData(
    val cursor: Long,
    val data: List<ToViewItem>
) {
    companion object {
        fun fromToViewResponse(data: dev.aaa1115910.biliapi.http.entity.toview.ToViewData) =
            ToViewData(
//               cursor = data.cursor.viewAt,
                cursor = 0,
                data = data.list
//                    .filter { it.history.business == "archive" || it.history.business == "pgc" }
                    .map { ToViewItem.fromToViewItem(it) }
            )

        // fun fromToViewResponse(data: bilibili.app.interfaces.v1.CursorV2Reply) = ToViewData(
        //     cursor = data.cursor.max,
        //     data = data.itemsList
        //         .filter { it.cardItemCase == CursorItem.CardItemCase.CARD_UGC || it.cardItemCase == CursorItem.CardItemCase.CARD_OGV }
        //         .map { ToViewItem.fromToViewItem(it) }
        // )
    }
}

data class ToViewItem(
    val oid: Long,
    val bvid: String,
    val cid: Long,
    val kid: Int,
    val epid: Int?,
    val seasonId: Int?,
    val title: String,
    val cover: String,
    val author: String,
    val authorId: Long,
    val authorFace: String = "",
    val duration: Int,
    val progress: Int,
    val type: ToViewItemType,
    val pubdate: Long,
    val play: Long,
    val danmaku: Int,
    /** PGC 条目的类型角标，如「番剧」「电影」 */
    val pgcLabel: String = ""
) {
    companion object {
        /** PGC 条目的播放页地址形如 `https://www.bilibili.com/bangumi/play/ep123456` */
        private val pgcPlayUrlRegex = Regex("""/play/(ep|ss)(\d+)""")

        fun fromToViewItem(item: dev.aaa1115910.biliapi.http.entity.toview.ToViewItem): ToViewItem {
            val playUrlMatch = pgcPlayUrlRegex.find(item.redirectUrl)
            val epIdFromUrl = playUrlMatch
                ?.takeIf { it.groupValues[1] == "ep" }
                ?.groupValues?.get(2)?.toIntOrNull()
            val seasonIdFromUrl = playUrlMatch
                ?.takeIf { it.groupValues[1] == "ss" }
                ?.groupValues?.get(2)?.toIntOrNull()
            val epid = item.bangumi?.epId?.takeIf { it > 0 } ?: epIdFromUrl
            val seasonId = item.bangumi?.season?.seasonId?.takeIf { it > 0 } ?: seasonIdFromUrl
            // 部分接口版本的 PGC 条目没有 bangumi 字段，只有 pgc_label / redirect_url 可以判定
            val isPgc = item.isPgc || item.bangumi != null || item.pgcLabel.isNotBlank() || epid != null
            return ToViewItem(
                oid = item.aid,
                bvid = item.bvid,
                cid = item.cid,
                kid = 0,
                epid = epid,
                seasonId = seasonId,
                title = item.title,
                cover = item.pic,
                author = item.owner?.name ?: "",
                authorId = item.owner?.mid ?: 0,
                authorFace = item.owner?.face ?: "",
                duration = item.duration,
                progress = item.progress,
                type = if (isPgc) ToViewItemType.Pgc else ToViewItemType.Archive,
                pubdate = item.pubdate,
                play = item.stat?.view ?: -1,
                danmaku = item.stat?.danmaku ?: -1,
                pgcLabel = item.pgcLabel
            )
        }

        @Suppress("RemoveRedundantQualifierName")
        fun fromToViewItem(item: bilibili.app.interfaces.v1.CursorItem) = ToViewItem(
            oid = item.oid,
            bvid = when (item.cardItemCase) {
                CursorItem.CardItemCase.CARD_UGC -> item.cardUgc.bvid
                CursorItem.CardItemCase.CARD_OGV -> ""
                else -> ""
            },
            cid = when (item.cardItemCase) {
                CursorItem.CardItemCase.CARD_UGC -> item.cardUgc.cid
                CursorItem.CardItemCase.CARD_OGV -> 0
                else -> 0
            },
            kid = item.kid.toInt(),
            epid = null,
            seasonId = when (item.cardItemCase) {
                CursorItem.CardItemCase.CARD_OGV -> item.kid.toInt()
                else -> null
            },
            title = item.title,
            cover = when (item.cardItemCase) {
                CursorItem.CardItemCase.CARD_UGC -> item.cardUgc.cover
                CursorItem.CardItemCase.CARD_OGV -> item.cardOgv.cover
                else -> ""
            },
            author = when (item.cardItemCase) {
                CursorItem.CardItemCase.CARD_UGC -> item.cardUgc.name
                CursorItem.CardItemCase.CARD_OGV -> ""
                else -> ""
            },
            authorId = when (item.cardItemCase) {
                CursorItem.CardItemCase.CARD_UGC -> item.cardUgc.mid
                CursorItem.CardItemCase.CARD_OGV -> 0
                else -> 0
            },
            duration = when (item.cardItemCase) {
                CursorItem.CardItemCase.CARD_UGC -> item.cardUgc.duration.toInt()
                CursorItem.CardItemCase.CARD_OGV -> item.cardOgv.duration.toInt()
                else -> 0
            },
            progress = when (item.cardItemCase) {
                CursorItem.CardItemCase.CARD_UGC -> item.cardUgc.progress.toInt()
                CursorItem.CardItemCase.CARD_OGV -> item.cardOgv.progress.toInt()
                else -> 0
            },
            type = when (item.cardItemCase) {
                CursorItem.CardItemCase.CARD_UGC -> ToViewItemType.Archive
                CursorItem.CardItemCase.CARD_OGV -> ToViewItemType.Pgc
                else -> ToViewItemType.Unknown
            },
            pubdate = -1,
            play = -1,
            danmaku = -1
        )
    }
}

enum class ToViewItemType {
    Unknown, Archive, Pgc
}