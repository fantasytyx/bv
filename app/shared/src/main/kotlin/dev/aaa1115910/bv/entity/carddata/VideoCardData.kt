package dev.aaa1115910.bv.entity.carddata

import dev.aaa1115910.biliapi.entity.FavoriteItemType
import dev.aaa1115910.biliapi.entity.FavoriteResourceType
import dev.aaa1115910.bv.util.formatHourMinSec
import java.text.SimpleDateFormat

data class VideoCardData(
    val avid: Long,
    val title: String,
    val cover: String,
    val upName: String,
    val upId: Long = 0,
    val upFace: String = "",
    val reason: String = "",
    val play: Long? = null,
    var playString: String = "",
    val danmaku: Int? = null,
    var danmakuString: String = "",
    val time: Long? = null,
    var timeString: String = "",
    val jumpToSeason: Boolean = false,
    val epId: Int? = null,
    val seasonId: Int? = null,
    val pubTime: String? = null,
    val historyBusiness: String? = null,
    val historyKid: Long? = null,
    val historyBadgeText: String = "",
    // var pubTimeString: String = "",
    val isInteractive: Boolean = false,
    val isChargingArc: Boolean = false,
    val badgeText: String = "",
    val favoriteItemType: FavoriteItemType = FavoriteItemType.Video,
    val pgcTypeName: String? = null
) {
    val coverBadges: List<String>
        get() = listOfNotNull(
            "互动视频".takeIf { isInteractive },
            when {
                isChargingArc && badgeText.isNotBlank() -> "⚡$badgeText"
                isChargingArc -> "⚡充电专属"
                else -> null
            },
            historyBadgeText.takeIf { it.isNotBlank() },
            pgcTypeName?.takeIf { it.isNotBlank() }
        )

    val favoriteResourceType: FavoriteResourceType
        get() = FavoriteResourceType.fromFavoriteItemType(favoriteItemType)

    init {
        play?.let {
            playString = if (it < 0) "" else if (it >= 10000) "${it / 10000}万" else "$it"
        }
        danmaku?.let {
            danmakuString = if (it < 0) "" else if (it >= 10000) "${it / 10000}万" else "$it"
        }
        time?.let {
            timeString = if (it > 0) it.formatHourMinSec() else ""
        }
        // pubTime?.let {
        //     pubTimeString =
        //         if (it > 0) SimpleDateFormat("yyyy-MM-dd").format(java.util.Date(it * 1000L)) else ""
        // }
    }
}
