package dev.aaa1115910.biliapi.entity.user

import dev.aaa1115910.biliapi.http.entity.user.SpaceArchiveItem
import dev.aaa1115910.biliapi.http.entity.user.SpaceSeasonArchivesData
import dev.aaa1115910.biliapi.http.entity.user.SpaceSeasonSeriesData
import dev.aaa1115910.biliapi.http.entity.user.SpaceSeasonSeriesEntry
import dev.aaa1115910.biliapi.http.entity.user.SpaceSeriesArchivesData
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.util.Date

data class SpaceSeasonSeriesPage(
    val seasons: List<SpaceCollection>,
    val series: List<SpaceSeries>,
    val hasMore: Boolean
) {
    companion object {
        fun fromWebData(data: SpaceSeasonSeriesData): SpaceSeasonSeriesPage {
            val lists = data.itemsLists
            val page = lists.page
            return SpaceSeasonSeriesPage(
                seasons = lists.seasonsList.mapNotNull(SpaceSeasonSeriesEntry::toSpaceCollection),
                series = lists.seriesList.mapNotNull(SpaceSeasonSeriesEntry::toSpaceSeries),
                hasMore = page.total > page.pageNumber * page.pageSize
            )
        }
    }
}

data class SpaceCollection(
    val seasonId: Long,
    val title: String,
    val cover: String,
    val videoCount: Int,
    val updateTime: Long? = null
)

data class SpaceSeries(
    val seriesId: Long,
    val title: String,
    val cover: String,
    val videoCount: Int,
    val updateTime: Long? = null
)

data class SpaceArchivePage(
    val videos: List<SpaceVideo>,
    val hasMore: Boolean
) {
    companion object {
        fun fromSeasonArchivesData(data: SpaceSeasonArchivesData, mid: Long) =
            SpaceArchivePage(
                videos = data.archives.map { it.toSpaceVideo(mid) },
                hasMore = data.page?.let { it.total > it.pageNumber * it.pageSize } ?: false
            )

        fun fromSeriesArchivesData(data: SpaceSeriesArchivesData, mid: Long) =
            SpaceArchivePage(
                videos = data.archives.map { it.toSpaceVideo(mid) },
                hasMore = data.page?.let { it.total > it.num * it.size } ?: false
            )
    }
}

private fun SpaceSeasonSeriesEntry.toSpaceCollection(): SpaceCollection? {
    val meta = meta ?: return null
    val id = meta.seasonId?.takeIf { it > 0 } ?: return null
    return SpaceCollection(
        seasonId = id,
        title = meta.title?.takeIf { it.isNotBlank() } ?: meta.name.orEmpty(),
        cover = meta.cover.orEmpty(),
        videoCount = meta.total,
        updateTime = resolveUpdateTime()
    )
}

private fun SpaceSeasonSeriesEntry.toSpaceSeries(): SpaceSeries? {
    val meta = meta ?: return null
    val id = meta.seriesId?.takeIf { it > 0 } ?: return null
    return SpaceSeries(
        seriesId = id,
        title = meta.name?.takeIf { it.isNotBlank() } ?: meta.title.orEmpty(),
        cover = meta.cover.orEmpty(),
        videoCount = meta.total,
        updateTime = resolveUpdateTime()
    )
}

/**
 * 合集/系列的更新时间：优先取 meta 的 mtime，其次取最近一条稿件的时间。
 * 秒级时间戳，没有任何时间信息时返回 null。
 */
internal fun SpaceSeasonSeriesEntry.resolveUpdateTime(): Long? =
    meta?.mtime?.takeIf { it > 0 }
        ?: archives.maxOfOrNull { maxOf(it.pubdate, it.ctime) }?.takeIf { it > 0 }

private fun SpaceArchiveItem.toSpaceVideo(mid: Long) = SpaceVideo(
    aid = aid,
    bvid = bvid,
    title = title,
    cover = pic,
    author = "",
    authorId = upMid ?: mid,
    duration = duration,
    play = stat?.view.asLongOrZero(),
    danmaku = stat?.danmaku.asLongOrZero().coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
    publishDate = Date(pubdate * 1000L),
    isInteractive = interactiveVideo,
    isChargingArc = ugcPay > 0
)

private fun kotlinx.serialization.json.JsonElement?.asLongOrZero() =
    (this as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: 0L
