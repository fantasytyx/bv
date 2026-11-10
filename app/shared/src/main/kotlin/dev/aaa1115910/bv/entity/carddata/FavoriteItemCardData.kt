package dev.aaa1115910.bv.entity.carddata

import dev.aaa1115910.biliapi.entity.FavoriteItem
import dev.aaa1115910.biliapi.entity.FavoriteItemType
import dev.aaa1115910.biliapi.util.toAv
import dev.aaa1115910.biliapi.util.toBv

fun FavoriteItem.toVideoCardDataOrNull(pubTime: String? = null): VideoCardData? {
    return when (type) {
        FavoriteItemType.Video -> VideoCardData(
            avid = id,
            title = title,
            cover = cover,
            play = cntInfo.play,
            danmaku = cntInfo.danmaku,
            upName = upper.name,
            upId = upper.mid,
            upFace = upper.face,
            time = duration * 1000L,
            pubTime = pubTime,
            favoriteItemType = type
        )

        FavoriteItemType.Pgc -> {
            val pgc = ogv ?: return null
            val seasonId = pgc.seasonId?.takeIf { it > 0 } ?: return null
            if (bvid.length != 12 || !bvid.startsWith("BV1")) return null
            val avid = bvid.toAv()
            if (avid <= 0L || avid.toBv() != bvid) return null
            val epId = id.takeIf { it in 1..Int.MAX_VALUE.toLong() }?.toInt() ?: return null
            VideoCardData(
                avid = avid,
                title = title,
                cover = cover,
                play = cntInfo.play,
                danmaku = cntInfo.danmaku,
                upName = upper.name,
                upId = upper.mid,
                upFace = upper.face,
                time = duration * 1000L,
                jumpToSeason = true,
                epId = epId,
                seasonId = seasonId,
                pubTime = pubTime,
                favoriteItemType = type,
                pgcTypeName = pgc.typeName
            )
        }

        FavoriteItemType.All,
        FavoriteItemType.Audio,
        FavoriteItemType.VideoCollection -> null
    }
}
