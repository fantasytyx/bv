package dev.aaa1115910.biliapi.http.entity.user

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class SpaceSeasonSeriesData(
    @SerialName("items_lists")
    val itemsLists: SpaceSeasonSeriesLists = SpaceSeasonSeriesLists()
)

@Serializable
data class SpaceSeasonSeriesLists(
    val page: SpaceSeasonSeriesPageInfo = SpaceSeasonSeriesPageInfo(),
    @SerialName("seasons_list")
    val seasonsList: List<SpaceSeasonSeriesEntry> = emptyList(),
    @SerialName("series_list")
    val seriesList: List<SpaceSeasonSeriesEntry> = emptyList()
)

@Serializable
data class SpaceSeasonSeriesPageInfo(
    @SerialName("page_num")
    val pageNumber: Int = 1,
    @SerialName("page_size")
    val pageSize: Int = 0,
    val total: Int = 0
)

@Serializable
data class SpaceSeasonSeriesEntry(
    val meta: SpaceSeasonSeriesMeta? = null,
    val archives: List<SpaceArchiveItem> = emptyList(),
    @SerialName("recent_aids")
    val recentAids: List<Long> = emptyList()
)

@Serializable
data class SpaceSeasonSeriesMeta(
    @SerialName("season_id")
    val seasonId: Long? = null,
    @SerialName("series_id")
    val seriesId: Long? = null,
    val title: String? = null,
    val name: String? = null,
    val cover: String? = null,
    val description: String? = null,
    val total: Int = 0,
    val ctime: Long = 0,
    val mtime: Long = 0
)

@Serializable
data class SpaceSeasonArchivesData(
    val archives: List<SpaceArchiveItem> = emptyList(),
    val page: SpaceSeasonArchivesPage? = null
)

@Serializable
data class SpaceSeasonArchivesPage(
    @SerialName("page_num")
    val pageNumber: Int = 1,
    @SerialName("page_size")
    val pageSize: Int = 0,
    val total: Int = 0
)

@Serializable
data class SpaceSeriesArchivesData(
    val archives: List<SpaceArchiveItem> = emptyList(),
    val page: SpaceSeriesArchivesPage? = null
)

@Serializable
data class SpaceSeriesArchivesPage(
    val num: Int = 1,
    val size: Int = 0,
    val total: Int = 0
)

@Serializable
data class SpaceArchiveItem(
    val aid: Long = 0,
    val bvid: String = "",
    val title: String = "",
    val pic: String = "",
    val duration: Int = 0,
    val pubdate: Long = 0,
    val ctime: Long = 0,
    val stat: SpaceArchiveStat? = null,
    @SerialName("interactive_video")
    val interactiveVideo: Boolean = false,
    @SerialName("ugc_pay")
    val ugcPay: Int = 0,
    @SerialName("upMid")
    val upMid: Long? = null
)

@Serializable
data class SpaceArchiveStat(
    val view: JsonElement? = null,
    val danmaku: JsonElement? = null
)
