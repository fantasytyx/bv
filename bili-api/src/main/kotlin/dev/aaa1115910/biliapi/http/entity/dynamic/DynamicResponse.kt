package dev.aaa1115910.biliapi.http.entity.dynamic

import dev.aaa1115910.biliapi.http.entity.user.Pendant
import dev.aaa1115910.biliapi.http.entity.user.Vip
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

@Serializable
data class DynamicData(
    @SerialName("has_more")
    val hasMore: Boolean = false,
    val offset: String = "",
    @SerialName("update_baseline")
    val updateBaseline: String = "",
    @SerialName("update_num")
    val updateNum: Int = 0,
    val total: Int = 0,
    val items: List<DynamicItem> = emptyList()
)

@Serializable
data class DynamicItem(
    val basic: Basic = Basic(),
    @SerialName("id_str")
    @Serializable(with = FlexibleStringSerializer::class)
    val idStr: String = "",
    val modules: Modules = Modules(),
    val orig: DynamicItem? = null,
    val type: String = "",
    val visible: Boolean = true,
    @SerialName("jump_url")
    val jumpUrl: String? = null
) {
    @Serializable
    data class Basic(
        @SerialName("comment_id_str")
        @Serializable(with = FlexibleStringSerializer::class)
        val commentIdStr: String = "",
        @SerialName("comment_type")
        @Serializable(with = FlexibleLongSerializer::class)
        val commentType: Long = 0L,
        @SerialName("jump_url")
        val jumpUrl: String? = null,
        @SerialName("like_icon")
        val likeIcon: LikeIcon? = null,
        @SerialName("rid_str")
        @Serializable(with = FlexibleStringSerializer::class)
        val ridStr: String = ""
    ) {
        @Serializable
        data class LikeIcon(
            @SerialName("action_url")
            val actionUrl: String = "",
            @SerialName("end_url")
            val endUrl: String = "",
            @Serializable(with = FlexibleLongSerializer::class)
            val id: Long = 0L,
            @SerialName("start_url")
            val startUrl: String = ""
        )
    }

    @Serializable
    data class Modules(
        @SerialName("module_author")
        val moduleAuthor: Author? = null,
        @SerialName("module_dynamic")
        val moduleDynamic: Dynamic = Dynamic(),
        @SerialName("module_more")
        val moduleMore: More? = null,
        @SerialName("module_stat")
        val moduleStat: Stat? = null,
        @SerialName("module_tag")
        val moduleTag: ModuleTag? = null,
        @SerialName("module_interaction")
        val moduleInteraction: JsonElement? = null,
        @SerialName("module_dispute")
        val moduleDispute: JsonElement? = null,
        @SerialName("module_fold")
        val moduleFold: JsonElement? = null
    ) {
        @Serializable
        data class Author(
            val face: String = "",
            @SerialName("face_nft")
            val faceNft: Boolean = false,
            @Serializable(with = FlexibleBooleanSerializer::class)
            val following: Boolean = false,
            @SerialName("icon_badge")
            val iconBadge: IconBadge? = null,
            @SerialName("jump_url")
            val jumpUrl: String = "",
            val label: String = "",
            @Serializable(with = FlexibleLongSerializer::class)
            val mid: Long = 0L,
            val name: String = "",
            @SerialName("official_verify")
            val officialVerify: OfficialVerify? = null,
            val pendant: Pendant? = null,
            @SerialName("pub_action")
            val pubAction: String = "",
            @SerialName("pub_location_text")
            val pubLocationText: String? = null,
            @SerialName("pub_time")
            val pubTime: String = "",
            @SerialName("pub_ts")
            @Serializable(with = FlexibleIntSerializer::class)
            val pubTs: Int = 0,
            val type: String = "",
            val vip: Vip? = null
        ) {
            @Serializable
            data class IconBadge(
                val icon: String = "",
                @SerialName("render_img")
                val renderImg: String = "",
                val text: String = ""
            )

            @Serializable
            data class OfficialVerify(
                val desc: String = "",
                val type: Int = 0
            )
        }

        @Serializable
        data class Dynamic(
            val additional: Additional? = null,
            val desc: Desc? = null,
            val major: Major? = null,
            val topic: Topic? = null
        ) {
            @Serializable
            data class Additional(
                val common: Common? = null,
                val reserve: Reserve? = null,
                val vote: Vote? = null,
                val ugc: Ugc? = null,
                val goods: Goods? = null,
                @SerialName("upower_lottery")
                val upowerLottery: UpowerLottery? = null,
                val match: Match? = null,
                val type: String = ""
            ) {
                @Serializable
                data class Common(
                    val button: Button? = null,
                    val cover: String = "",
                    val desc1: String = "",
                    val desc2: String = "",
                    @SerialName("head_text")
                    val headText: String = "",
                    @SerialName("id_str")
                    val idStr: String = "",
                    @SerialName("jump_url")
                    val jumpUrl: String = "",
                    val style: Int = 0,
                    @SerialName("sub_type")
                    val subType: String = "",
                    val title: String = ""
                )

                @Serializable
                data class Vote(
                    @SerialName("join_num")
                    @Serializable(with = FlexibleIntSerializer::class)
                    val joinNum: Int = 0,
                    @SerialName("vote_id")
                    @Serializable(with = FlexibleIntSerializer::class)
                    val voteId: Int = 0,
                    val title: String = "",
                    val desc: String = ""
                )

                @Serializable
                data class Ugc(
                    val cover: String = "",
                    @SerialName("desc_second")
                    val descSecond: String = "",
                    val duration: String = "",
                    @SerialName("jump_url")
                    val jumpUrl: String = "",
                    val stat: Stat? = null,
                    val title: String = ""
                ) {
                    @Serializable
                    data class Stat(
                        val danmaku: String = "",
                        val play: String = ""
                    )
                }

                @Serializable
                data class Goods(
                    val items: List<GoodsItem> = emptyList()
                ) {
                    @Serializable
                    data class GoodsItem(
                        val cover: String = "",
                        @SerialName("jump_desc")
                        val jumpDesc: String = "",
                        @SerialName("jump_url")
                        val jumpUrl: String = "",
                        val name: String = "",
                        val price: String = ""
                    )
                }

                @Serializable
                data class UpowerLottery(
                    val button: Button? = null,
                    val desc: TextLink? = null,
                    val hint: Hint? = null,
                    @SerialName("jump_url")
                    val jumpUrl: String = "",
                    val title: String = ""
                ) {
                    @Serializable
                    data class Hint(
                        val text: String = ""
                    )
                }

                @Serializable
                data class Match(
                    val button: Button? = null,
                    @SerialName("jump_url")
                    val jumpUrl: String = "",
                    @SerialName("match_info")
                    val matchInfo: MatchInfo? = null
                ) {
                    @Serializable
                    data class MatchInfo(
                        @SerialName("center_bottom")
                        val centerBottom: String = "",
                        @SerialName("center_top")
                        val centerTop: JsonElement? = null,
                        @SerialName("left_team")
                        val leftTeam: Team? = null,
                        @SerialName("right_team")
                        val rightTeam: Team? = null,
                        @SerialName("sub_title")
                        val subTitle: JsonElement? = null,
                        val title: String = ""
                    )

                    @Serializable
                    data class Team(
                        val name: String = "",
                        val pic: String = ""
                    )
                }
            }

            @Serializable
            data class Button(
                val check: ButtonItem? = null,
                val icon: String = "",
                val status: Int? = null,
                val text: String = "",
                val type: Int = 0,
                val uncheck: ButtonItem? = null,
                @SerialName("jump_style")
                val jumpStyle: ButtonItem? = null,
                @SerialName("jump_url")
                val jumpUrl: String? = null
            ) {
                @Serializable
                data class ButtonItem(
                    @SerialName("icon_url")
                    val iconUrl: String? = null,
                    val text: String = "",
                    val disable: Int = 0
                )
            }

            @Serializable
            data class TextLink(
                val style: Int = 0,
                val text: String = "",
                val visible: Boolean? = null,
                @SerialName("jump_url")
                val jumpUrl: String? = null
            )

            @Serializable
            data class Reserve(
                val button: Button? = null,
                val desc1: TextLink? = null,
                val desc2: TextLink? = null,
                val desc3: TextLink? = null,
                @SerialName("jump_url")
                val jumpUrl: String = "",
                @SerialName("reserve_total")
                val reserveTotal: Int = 0,
                @Serializable(with = FlexibleLongSerializer::class)
                val rid: Long = 0L,
                val state: Int = 0,
                val stypc: Int? = null,
                val title: String = "",
                @SerialName("up_mid")
                @Serializable(with = FlexibleLongSerializer::class)
                val upMid: Long = 0L
            )

            @Serializable
            data class Desc(
                @SerialName("rich_text_nodes")
                val richTextNodes: List<RichTextNodeItem> = emptyList(),
                val text: String = ""
            ) {
                @Serializable
                data class RichTextNodeItem(
                    val emoji: Emoji? = null,
                    @SerialName("orig_text")
                    val origText: String = "",
                    val text: String = "",
                    val type: String = "",
                    val rid: String? = null,
                    val pics: List<Major.Opus.Pic> = emptyList(),
                    @SerialName("dyn_pic")
                    val dynPic: List<Major.Opus.Pic> = emptyList(),
                    @SerialName("jump_url")
                    val jumpUrl: String? = null
                ) {
                    @Serializable
                    data class Emoji(
                        @SerialName("icon_url")
                        val iconUrl: String = "",
                        @SerialName("webp_url")
                        val webpUrl: String = "",
                        @SerialName("gif_url")
                        val gifUrl: String = "",
                        val size: Int = 1,
                        val text: String = "",
                        val type: Int = 0
                    )
                }
            }

            @Serializable
            data class Major(
                val archive: Archive? = null,
                @SerialName("ugc_season")
                val ugcSeason: UgcSeason? = null,
                @SerialName("live_rcmd")
                val liveRcmd: LiveRcmd? = null,
                val live: Live? = null,
                val opus: Opus? = null,
                val draw: Draw? = null,
                val pgc: Pgc? = null,
                val article: Article? = null,
                val courses: Courses? = null,
                val common: Common? = null,
                @SerialName("upower_common")
                val upowerCommon: Common? = null,
                val music: Music? = null,
                val blocked: Blocked? = null,
                val medialist: Medialist? = null,
                @SerialName("subscription_new")
                val subscriptionNew: SubscriptionNew? = null,
                val none: None? = null,
                val type: String = ""
            ) {
                @Serializable
                data class Archive(
                    @Serializable(with = FlexibleLongSerializer::class)
                    val aid: Long = 0L,
                    val badge: Badge? = null,
                    val bvid: String = "",
                    val cover: String = "",
                    val desc: String = "",
                    @SerialName("disable_preview")
                    val disablePreview: Int = 0,
                    @SerialName("duration_text")
                    val durationText: String = "",
                    @SerialName("jump_url")
                    val jumpUrl: String = "",
                    val stat: Stat? = null,
                    val title: String = "",
                    val type: Int = 0
                ) {
                    @Serializable
                    data class Badge(
                        @SerialName("bg_color")
                        val bgColor: String = "",
                        val color: String = "",
                        val text: String = ""
                    )

                    @Serializable
                    data class Stat(
                        val danmaku: String = "",
                        val play: String = ""
                    )
                }

                @Serializable
                data class UgcSeason(
                    @Serializable(with = FlexibleLongSerializer::class)
                    val aid: Long = 0L,
                    val badge: Archive.Badge? = null,
                    val bvid: String = "",
                    val cover: String = "",
                    val desc: String = "",
                    @SerialName("disable_preview")
                    val disablePreview: Int = 0,
                    @SerialName("duration_text")
                    val durationText: String = "",
                    @SerialName("jump_url")
                    val jumpUrl: String = "",
                    val stat: Archive.Stat? = null,
                    val title: String = "",
                    val type: Int = 0
                )

                @Serializable
                data class LiveRcmd(
                    val content: String = "",
                    @SerialName("reserve_type")
                    val reserveType: Int = 0
                )

                @Serializable
                data class Live(
                    val badge: Archive.Badge? = null,
                    val cover: String = "",
                    @SerialName("desc_first")
                    val descFirst: String = "",
                    @Serializable(with = FlexibleLongSerializer::class)
                    val id: Long = 0L,
                    @SerialName("jump_url")
                    val jumpUrl: String = "",
                    @SerialName("live_state")
                    val liveState: Int = 0,
                    val title: String = ""
                )

                @Serializable
                data class Opus(
                    @SerialName("fold_action")
                    val foldAction: List<String> = emptyList(),
                    @SerialName("jump_url")
                    val jumpUrl: String = "",
                    val pics: List<Pic> = emptyList(),
                    val summary: Desc = Desc(),
                    val title: String? = null
                ) {
                    @Serializable
                    data class Pic(
                        val height: Int = 0,
                        val width: Int = 0,
                        val size: Float? = null,
                        val url: String = "",
                        val src: String = "",
                        @SerialName("live_url")
                        val liveUrl: String = ""
                    )
                }

                @Serializable
                data class Draw(
                    val id: Int = 0,
                    val items: List<Pic> = emptyList()
                ) {
                    @Serializable
                    data class Pic(
                        val height: Int = 0,
                        val width: Int = 0,
                        val size: Float? = null,
                        val src: String = "",
                        val tags: List<String> = emptyList()
                    )
                }

                @Serializable
                data class Pgc(
                    @Serializable(with = FlexibleLongSerializer::class)
                    val aid: Long = 0L,
                    val badge: Archive.Badge? = null,
                    val bvid: String = "",
                    @Serializable(with = FlexibleLongSerializer::class)
                    val cid: Long = 0L,
                    val cover: String = "",
                    @SerialName("duration_text")
                    val durationText: String = "",
                    @Serializable(with = FlexibleIntSerializer::class)
                    val epid: Int = 0,
                    @SerialName("jump_url")
                    val jumpUrl: String = "",
                    @SerialName("season_id")
                    @Serializable(with = FlexibleIntSerializer::class)
                    val seasonId: Int = 0,
                    val stat: Archive.Stat? = null,
                    @SerialName("sub_type")
                    val subType: Int = 0,
                    val title: String = "",
                    val type: Int = 0
                )

                @Serializable
                data class Article(
                    val covers: List<String> = emptyList(),
                    val desc: String = "",
                    @Serializable(with = FlexibleIntSerializer::class)
                    val id: Int = 0,
                    @SerialName("jump_url")
                    val jumpUrl: String = "",
                    val label: String = "",
                    val title: String = ""
                )

                @Serializable
                data class Courses(
                    @Serializable(with = FlexibleLongSerializer::class)
                    val aid: Long = 0L,
                    val badge: Archive.Badge? = null,
                    val cover: String = "",
                    val desc: String = "",
                    @SerialName("duration_text")
                    val durationText: String = "",
                    @Serializable(with = FlexibleLongSerializer::class)
                    val epid: Long = 0L,
                    @SerialName("jump_url")
                    val jumpUrl: String = "",
                    @SerialName("season_id")
                    @Serializable(with = FlexibleLongSerializer::class)
                    val seasonId: Long = 0L,
                    val title: String = ""
                )

                @Serializable
                data class Common(
                    val badge: Archive.Badge? = null,
                    val button: Button? = null,
                    val cover: String = "",
                    val desc: String = "",
                    @SerialName("jump_url")
                    val jumpUrl: String = "",
                    val label: String = "",
                    val title: String = ""
                )

                @Serializable
                data class Music(
                    @Serializable(with = FlexibleLongSerializer::class)
                    val id: Long = 0L,
                    val cover: String = "",
                    val label: String = "",
                    val title: String = ""
                )

                @Serializable
                data class Blocked(
                    @SerialName("bg_img")
                    val bgImg: BgImg? = null,
                    @SerialName("blocked_type")
                    val blockedType: Int = 0,
                    val button: Button? = null,
                    val icon: BgImg? = null,
                    @SerialName("hint_message")
                    val hintMessage: String = "",
                    val title: String = ""
                ) {
                    @Serializable
                    data class BgImg(
                        @SerialName("img_dark")
                        val imgDark: String = "",
                        @SerialName("img_day")
                        val imgDay: String = ""
                    )
                }

                @Serializable
                data class Medialist(
                    val id: JsonElement? = null,
                    val badge: Archive.Badge? = null,
                    val cover: String = "",
                    @SerialName("jump_url")
                    val jumpUrl: String = "",
                    @SerialName("sub_title")
                    val subTitle: String = "",
                    val title: String = ""
                )

                @Serializable
                data class SubscriptionNew(
                    @SerialName("live_rcmd")
                    val liveRcmd: LiveRcmd? = null
                )

                @Serializable
                data class None(
                    val tips: String = ""
                )
            }

            @Serializable
            data class Topic(
                val id: Int = 0,
                @SerialName("jump_url")
                val jumpUrl: String = "",
                val name: String = ""
            )
        }

        @Serializable
        data class More(
            @SerialName("three_point_items")
            val threePointItems: List<MoreItem> = emptyList()
        ) {
            @Serializable
            data class MoreItem(
                val label: String = "",
                val type: String = ""
            )
        }

        @Serializable
        data class ModuleTag(
            val text: String = ""
        )

        @Serializable
        data class Stat(
            val comment: StatItem? = null,
            val forward: StatItem? = null,
            val like: StatItem? = null,
            val favorite: StatItem? = null
        ) {
            @Serializable
            data class StatItem(
                @Serializable(with = FlexibleIntSerializer::class)
                val count: Int = 0,
                val forbidden: Boolean = false,
                val status: Boolean = false
            )
        }
    }
}

object FlexibleStringSerializer : KSerializer<String> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("FlexibleString", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): String {
        val jsonDecoder = decoder as? JsonDecoder ?: return decoder.decodeString()
        val element = jsonDecoder.decodeJsonElement()
        return (element as? JsonPrimitive)?.contentOrNull ?: ""
    }

    override fun serialize(encoder: Encoder, value: String) {
        encoder.encodeString(value)
    }
}

object FlexibleIntSerializer : KSerializer<Int> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("FlexibleInt", PrimitiveKind.INT)

    override fun deserialize(decoder: Decoder): Int {
        val jsonDecoder = decoder as? JsonDecoder ?: return decoder.decodeInt()
        val element = jsonDecoder.decodeJsonElement()
        val primitive = element as? JsonPrimitive ?: return 0
        return primitive.intOrNull ?: primitive.contentOrNull?.toIntOrNull() ?: 0
    }

    override fun serialize(encoder: Encoder, value: Int) {
        encoder.encodeInt(value)
    }
}

@OptIn(ExperimentalSerializationApi::class)
object FlexibleNullableIntSerializer : KSerializer<Int?> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("FlexibleNullableInt", PrimitiveKind.INT)

    override fun deserialize(decoder: Decoder): Int? {
        val jsonDecoder = decoder as? JsonDecoder
        if (jsonDecoder == null) {
            if (!decoder.decodeNotNullMark()) {
                decoder.decodeNull()
                return null
            }
            return decoder.decodeInt()
        }

        val element = jsonDecoder.decodeJsonElement()
        if (element is JsonNull) return null
        val primitive = element as? JsonPrimitive ?: return null
        return primitive.intOrNull ?: primitive.contentOrNull?.toIntOrNull()
    }

    override fun serialize(encoder: Encoder, value: Int?) {
        if (value == null) {
            encoder.encodeNull()
        } else {
            encoder.encodeInt(value)
        }
    }
}

object FlexibleBooleanSerializer : KSerializer<Boolean> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("FlexibleBoolean", PrimitiveKind.BOOLEAN)

    override fun deserialize(decoder: Decoder): Boolean {
        val jsonDecoder = decoder as? JsonDecoder ?: return decoder.decodeBoolean()
        val element = jsonDecoder.decodeJsonElement()
        val primitive = element as? JsonPrimitive ?: return false
        primitive.booleanOrNull?.let { return it }
        primitive.intOrNull?.let { return it != 0 }
        return when (primitive.contentOrNull?.lowercase()) {
            "true", "1" -> true
            else -> false
        }
    }

    override fun serialize(encoder: Encoder, value: Boolean) {
        encoder.encodeBoolean(value)
    }
}

object FlexibleLongSerializer : KSerializer<Long> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("FlexibleLong", PrimitiveKind.LONG)

    override fun deserialize(decoder: Decoder): Long {
        val jsonDecoder = decoder as? JsonDecoder ?: return decoder.decodeLong()
        val element = jsonDecoder.decodeJsonElement()
        val primitive = element as? JsonPrimitive ?: return 0L
        return primitive.longOrNull ?: primitive.contentOrNull?.toLongOrNull() ?: 0L
    }

    override fun serialize(encoder: Encoder, value: Long) {
        encoder.encodeLong(value)
    }
}

@OptIn(ExperimentalSerializationApi::class)
object FlexibleNullableLongSerializer : KSerializer<Long?> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("FlexibleNullableLong", PrimitiveKind.LONG)

    override fun deserialize(decoder: Decoder): Long? {
        val jsonDecoder = decoder as? JsonDecoder
        if (jsonDecoder == null) {
            if (!decoder.decodeNotNullMark()) {
                decoder.decodeNull()
                return null
            }
            return decoder.decodeLong()
        }

        val element = jsonDecoder.decodeJsonElement()
        if (element is JsonNull) return null
        val primitive = element as? JsonPrimitive ?: return null
        return primitive.longOrNull ?: primitive.contentOrNull?.toLongOrNull()
    }

    override fun serialize(encoder: Encoder, value: Long?) {
        if (value == null) {
            encoder.encodeNull()
        } else {
            encoder.encodeLong(value)
        }
    }
}
