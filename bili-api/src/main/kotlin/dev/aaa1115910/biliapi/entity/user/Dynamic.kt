package dev.aaa1115910.biliapi.entity.user

import bilibili.app.dynamic.v2.DynModuleType
import bilibili.app.dynamic.v2.Module
import bilibili.app.dynamic.v2.ModuleDynamic.ModuleItemCase
import bilibili.app.dynamic.v2.Paragraph
import bilibili.app.dynamic.v2.VideoType
import dev.aaa1115910.biliapi.entity.Picture
import dev.aaa1115910.biliapi.util.extractArchiveId
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import okhttp3.internal.toLongOrDefault

data class DynamicData(
    val dynamics: List<DynamicItem>,
    val hasMore: Boolean,
    val historyOffset: String,
    val updateBaseline: String
) {
    companion object {
        private val logger = KotlinLogging.logger { }
        private val availableDynamicTypes = listOf(
            DynamicType.Av,
            DynamicType.Draw,
            DynamicType.Forward,
            DynamicType.Word,
            DynamicType.LiveRcmd,
            DynamicType.Pgc,
            DynamicType.Article,
            DynamicType.None
        )
        private val availableWebDynamicTypes = availableDynamicTypes.map { it.webValue }
        private val availableAppDynamicTypes = availableDynamicTypes.map { it.appValue }

        fun fromDynamicData(data: dev.aaa1115910.biliapi.http.entity.dynamic.DynamicData) =
            DynamicData(
                dynamics = data.items
                    .mapNotNull {
                        if (!availableWebDynamicTypes.contains(it.type)) {
                            logger.warn { "unknown dynamic type ${it.type}, up: ${it.modules.moduleAuthor?.name}, date: ${it.modules.moduleAuthor?.pubTime}" }
                            return@mapNotNull null
                        }

                        if (it.type == DynamicType.Forward.webValue) {
                            if (!availableWebDynamicTypes.contains(it.orig?.type)) {
                                logger.warn { "unknown dynamic forward type ${it.orig?.type}, up: ${it.modules.moduleAuthor?.name}, date: ${it.modules.moduleAuthor?.pubTime}" }
                                return@mapNotNull null
                            }
                        }

                        runCatching { DynamicItem.fromDynamicItem(it) }
                            .onFailure { exception ->
                                logger.warn(exception) { "map dynamic ${it.idStr} type ${it.type} failed, skipping" }
                            }
                            .getOrNull()
                    },
                hasMore = data.hasMore,
                historyOffset = data.offset,
                updateBaseline = data.updateBaseline
            ).also {
                logger.info { "updateBaseline: ${data.updateBaseline}" }
                logger.info { "offset: ${data.offset}" }
            }

        fun fromDynamicData(data: bilibili.app.dynamic.v2.DynAllReply) = DynamicData(
            dynamics = data.dynamicList.listList
                .mapNotNull {
                    if (!availableAppDynamicTypes.contains(it.cardType)) {
                        logger.warn { "unknown dynamic type ${it.cardType.name}, up: ${it.getAuthorModule()?.author?.name}, date: ${it.getAuthorModule()?.ptimeLabelText}" }
                        return@mapNotNull null
                    }

                    if (it.cardType == bilibili.app.dynamic.v2.DynamicType.forward) {
                        // source not exist
                        if (it.getItemNullModule() != null) {
                            return@mapNotNull runCatching { DynamicItem.fromDynamicItem(it) }
                                .onFailure { exception ->
                                    logger.warn(exception) { "map app dynamic ${it.extend.dynIdStr} failed, skipping" }
                                }
                                .getOrNull()
                        } else if (!availableAppDynamicTypes.contains(it.getDynamicModule()?.dynForward?.item?.cardType)) {
                            logger.warn { "unknown dynamic forward type ${it.getDynamicModule()?.dynForward?.item?.cardType}, up: ${it.getAuthorModule()?.author?.name}, date: ${it.getAuthorModule()?.ptimeLabelText}" }
                            return@mapNotNull null
                        }
                    }

                    runCatching { DynamicItem.fromDynamicItem(it) }
                        .onFailure { exception ->
                            logger.warn(exception) { "map app dynamic ${it.extend.dynIdStr} failed, skipping" }
                        }
                        .getOrNull()
                },
            hasMore = data.dynamicList.hasMore,
            historyOffset = data.dynamicList.historyOffset,
            updateBaseline = data.dynamicList.updateBaseline
        ).also {
            logger.info { "updateBaseline: ${data.dynamicList.updateBaseline}" }
            logger.info { "historyOffset: ${data.dynamicList.historyOffset}" }
        }
    }
}

data class DynamicItem(
    var id: String? = null,
    var commentId: Long = 0,
    var commentType: Long = 0,
    var type: DynamicType,
    /**
     * 内容类型（`module_dynamic.major.type`），见 [DynamicMajorType]。
     *
     * 外层 [type] 是卡片的声明类型，这里才是实际内容；gRPC 接口没有这个字符串，此时为空。
     */
    var majorType: String = "",
    val author: DynamicAuthorModule,
    var video: DynamicVideoModule? = null,
    var draw: DynamicDrawModule? = null,
    var word: DynamicWordModule? = null,
    /**
     * 动态自身的正文（`module_dynamic.desc.text`）。
     *
     * 视频动态里它和视频简介（`archive.desc` → `video.text`）不是一回事：网页端把正文放在封面上方、
     * 视频标题放在封面下方。
     */
    var bodyText: String = "",
    var liveRcmd: DynamicLiveRcmdModule? = null,
    var pgc: DynamicPgcModule? = null,
    var article: DynamicArticleModule? = null,
    var none: DynamicNoneModule? = null,
    var blocked: DynamicBlockedModule? = null,
    val footer: DynamicFooterModule? = null,
    var orig: DynamicItem? = null,
    var jumpUrl: String? = null
) {
    companion object {
        /**
         * 容错映射：空间动态一页里混着十几种卡片，任何一条载荷缺字段都不能让整页失败。
         * 内容模块缺失时宁可少渲染一块，也不要抛异常。
         */
        fun fromDynamicItem(item: dev.aaa1115910.biliapi.http.entity.dynamic.DynamicItem): DynamicItem {
            val dynamicType = DynamicType.fromWebValueOrNull(item.type) ?: DynamicType.None
            val moduleDynamic = item.modules.moduleDynamic
            val dynamicItem = DynamicItem(
                id = item.idStr,
                commentId = item.basic.commentIdStr.toLongOrDefault(0),
                commentType = item.basic.commentType,
                type = dynamicType,
                majorType = moduleDynamic.major?.type.orEmpty(),
                author = item.modules.moduleAuthor?.let(DynamicAuthorModule::fromModuleAuthor)
                    ?: DynamicAuthorModule("", "", 0, "", ""),
                bodyText = moduleDynamic.desc?.text.orEmpty(),
                footer = DynamicFooterModule.fromModuleStat(item.modules.moduleStat),
                jumpUrl = item.basic.jumpUrl ?: item.jumpUrl
            )
            // 被屏蔽的动态（如充电专属）没有对应的内容模块，必须提前返回，
            // 否则下面的内容模块取值会直接抛 NPE
            val blockedModule = moduleDynamic.major
                ?.let(DynamicBlockedModule::fromModuleDynamic)
            if (blockedModule != null) {
                dynamicItem.blocked = blockedModule
                return dynamicItem
            }
            when (dynamicType) {
                DynamicType.Av -> {
                    val archive = moduleDynamic.major?.archive
                    if (archive != null) {
                        dynamicItem.video = DynamicVideoModule.fromModuleArchive(archive)
                    } else {
                        // 载荷缺失（失效/受限的视频动态）时退回正文，卡片至少还有内容
                        dynamicItem.word = DynamicWordModule.fromModuleDynamic(moduleDynamic)
                    }
                }

                DynamicType.UgcSeason -> dynamicItem.video =
                    moduleDynamic.major?.ugcSeason?.let {
                        DynamicVideoModule.fromModuleUgcSeason(it)
                    }

                DynamicType.Forward -> dynamicItem.apply {
                    word = DynamicWordModule.fromModuleDynamic(moduleDynamic)
                    orig = item.orig?.let { fromDynamicItem(it) }
                }

                DynamicType.Word -> dynamicItem.word =
                    DynamicWordModule.fromModuleDynamic(moduleDynamic)

                DynamicType.Draw -> dynamicItem.draw =
                    DynamicDrawModule.fromModuleDynamic(moduleDynamic)

                DynamicType.LiveRcmd -> {
                    val liveRcmd = moduleDynamic.major?.liveRcmd
                    dynamicItem.liveRcmd = if (liveRcmd != null) {
                        DynamicLiveRcmdModule.fromModuleDynamic(moduleDynamic)
                    } else {
                        // 部分卡片下发的是旧版 major.live 载荷
                        moduleDynamic.major?.live?.let(DynamicLiveRcmdModule::fromModuleLive)
                    }
                }

                DynamicType.Pgc -> {
                    val pgc = moduleDynamic.major?.pgc
                    if (pgc != null) {
                        dynamicItem.pgc = DynamicPgcModule.fromModulePgc(pgc)
                    } else {
                        dynamicItem.word = DynamicWordModule.fromModuleDynamic(moduleDynamic)
                    }
                }

                DynamicType.Article -> {
                    val article = moduleDynamic.major?.article
                    if (article != null) {
                        dynamicItem.article = DynamicArticleModule.fromModuleArticle(article)
                    } else {
                        // 「投稿了文章」动态可能用 MAJOR_TYPE_OPUS 下发内容，此时 major.article 为 null，
                        // 标题/正文/封面都在 major.opus 里（jump_url 指向 /opus/{动态id}）
                        val draw = DynamicDrawModule.fromModuleDynamic(moduleDynamic)
                        if (moduleDynamic.major?.opus != null || moduleDynamic.major?.draw != null) {
                            dynamicItem.draw = draw
                        } else {
                            dynamicItem.word = DynamicWordModule.fromModuleDynamic(moduleDynamic)
                        }
                        dynamicItem.article = DynamicArticleModule.fromOpus(
                            draw = draw,
                            jumpUrl = moduleDynamic.major?.opus?.jumpUrl.orEmpty()
                        )
                    }
                }

                DynamicType.None -> dynamicItem.none =
                    moduleDynamic.major?.none?.let(DynamicNoneModule::fromModuleDynamic)
            }
            // 图文/动态附带的视频卡片走 additional.ugc（如 B 站手机端发布的图文视频）
            if (dynamicItem.video == null) {
                moduleDynamic.additional?.ugc?.let { ugc ->
                    dynamicItem.video = DynamicVideoModule.fromAdditionalUgc(ugc)
                }
            }
            return dynamicItem
        }

        fun fromDynamicItem(
            item: bilibili.app.dynamic.v2.DynamicItem,
            isForwardItem: Boolean = false
        ): DynamicItem {
            val dynamicType = DynamicType.fromAppValue(item.cardType)
            val commentType: Long = when (item.cardType) {
                bilibili.app.dynamic.v2.DynamicType.av,
                bilibili.app.dynamic.v2.DynamicType.pgc -> 1

                bilibili.app.dynamic.v2.DynamicType.draw -> 11

                bilibili.app.dynamic.v2.DynamicType.forward,
                bilibili.app.dynamic.v2.DynamicType.word,
                bilibili.app.dynamic.v2.DynamicType.article,
                bilibili.app.dynamic.v2.DynamicType.live_rcmd -> 17

                else -> 17
            }
            val dynamicItem = DynamicItem(
                id = item.extend.dynIdStr,
                commentId = item.extend.businessId.toLongOrDefault(0),
                commentType = commentType,
                // item.extend.rType 总是为 0
                //commentType = item.extend.rType,
                type = dynamicType,
                author = if (dynamicType == DynamicType.None) {
                    DynamicAuthorModule("", "", -1, "", "")
                } else if (isForwardItem) {
                    DynamicAuthorModule.fromExtendAndModuleAuthorForward(
                        item.extend, item.getAuthorModuleForward()!!
                    )
                } else {
                    DynamicAuthorModule.fromModuleAuthor(item.getAuthorModule()!!)
                },
                video = item.getDynamicModule()?.let {
                    DynamicVideoModule.fromModuleArchive(it.dynArchive).apply {
                        text = item.getDescModule()?.text ?: ""
                    }
                },
                bodyText = item.getDescModule()?.text.orEmpty(),
                footer = if (!isForwardItem) {
                    // 获取动态详情时 module_list 中没有 module_stat，但 module_bottom 中包含了 module_stat
                    DynamicFooterModule.fromModuleStat(
                        item.getStatModule() ?: item.getBottomModule()!!.moduleStat
                    )
                } else null
            )

            when (dynamicType) {
                DynamicType.Av -> dynamicItem.video = item.getDynamicModule()?.let {
                    DynamicVideoModule.fromModuleArchive(it.dynArchive).apply {
                        text = item.getDescModule()?.text ?: ""
                    }
                }

                DynamicType.UgcSeason -> dynamicItem.video = item.getDynamicModule()?.dynUgcSeason?.let {
                    DynamicVideoModule.fromModuleUgcSeason(it)
                }

                DynamicType.Draw -> dynamicItem.draw =
                    item.getOpusSummaryModule()?.let { opusSummaryModule ->
                        DynamicDrawModule.fromModuleOpusSummaryAndModuleDynamic(
                            opusSummaryModule,
                            item.getDynamicModule()
                        )
                    } ?: let {
                        DynamicDrawModule.fromModuleDescAndModuleDynamic(
                            item.getParagraphModule(),
                            item.getDescModule()!!,
                            item.getDynamicModule()
                        )
                    }


                DynamicType.Word -> dynamicItem.word =
                    item.getOpusSummaryModule()?.let { opusSummaryModule ->
                        DynamicWordModule.fromModuleOpusSummary(opusSummaryModule)
                    } ?: let {
                        DynamicWordModule.fromModuleDesc(item.getDescModule()!!)
                    }

                DynamicType.Forward -> dynamicItem.apply {
                    word = DynamicWordModule.fromModuleDesc(item.getDescModule()!!)
                    val item2 = item.getDynamicModule()?.dynForward?.item
                    if (item2 == null) {
                        println()
                        val emptyDynamic = bilibili.app.dynamic.v2.dynamicItem {
                            cardType = bilibili.app.dynamic.v2.DynamicType.dyn_none
                            modules.addAll(item.modulesList)
                        }
                        orig = fromDynamicItem(emptyDynamic, true)
                    } else {
                        orig = fromDynamicItem(item2!!, true)
                    }
                }

                DynamicType.LiveRcmd -> dynamicItem.liveRcmd =
                    DynamicLiveRcmdModule.fromModuleDynamic(item.getDynamicModule()!!)

                DynamicType.Pgc -> dynamicItem.pgc =
                    DynamicPgcModule.fromModulePgc(item.getDynamicModule()!!.dynPgc)

                DynamicType.Article -> {
                    val dynamicModule = item.getDynamicModule()
                    val article = dynamicModule?.dynArticle
                    if (article != null) {
                        dynamicItem.article = DynamicArticleModule.fromModuleArticle(article)
                    } else {
                        // 新版文章动态走 opus 摘要模块，内容不在 dyn_article 里
                        val opusSummary = item.getOpusSummaryModule()
                        if (opusSummary != null) {
                            val draw = DynamicDrawModule.fromModuleOpusSummaryAndModuleDynamic(
                                opusSummary,
                                dynamicModule
                            ).let { draw ->
                                // 文章封面有时只出现在 ModuleOpusSummary.covers 里
                                if (draw.images.isNotEmpty()) {
                                    draw
                                } else {
                                    draw.copy(
                                        images = opusSummary.coversList
                                            .map(Picture::fromPicture)
                                            .filter { it.url.isNotBlank() }
                                            .distinctBy { it.url }
                                    )
                                }
                            }
                            dynamicItem.draw = draw
                            dynamicItem.article = DynamicArticleModule.fromOpus(
                                draw = draw,
                                jumpUrl = dynamicModule?.dynDraw?.uri.orEmpty()
                            )
                        } else {
                            val word = item.getDescModule()?.let(DynamicWordModule::fromModuleDesc)
                            dynamicItem.word = word
                            dynamicItem.article = DynamicArticleModule.fromOpus(
                                draw = DynamicDrawModule(
                                    title = null,
                                    text = word?.text.orEmpty(),
                                    images = emptyList()
                                ),
                                jumpUrl = dynamicModule?.dynDraw?.uri.orEmpty()
                            )
                        }
                    }
                }

                DynamicType.None -> dynamicItem.none =
                    DynamicNoneModule.fromModuleDynamic(item.getItemNullModule()!!)
            }

            return dynamicItem
        }
    }

    data class DynamicAuthorModule(
        val author: String,
        val avatar: String,
        val mid: Long,
        val pubTime: String,
        val pubAction: String
    ) {
        companion object {
            fun fromModuleAuthor(moduleAuthor: dev.aaa1115910.biliapi.http.entity.dynamic.DynamicItem.Modules.Author) =
                DynamicAuthorModule(
                    author = moduleAuthor.name,
                    avatar = moduleAuthor.face,
                    mid = moduleAuthor.mid,
                    pubTime = moduleAuthor.pubTime,
                    pubAction = moduleAuthor.pubAction
                )

            fun fromModuleAuthor(moduleAuthor: bilibili.app.dynamic.v2.ModuleAuthor) =
                DynamicAuthorModule(
                    author = moduleAuthor.author.name,
                    avatar = moduleAuthor.author.face,
                    mid = moduleAuthor.author.mid,
                    pubTime = moduleAuthor.ptimeLabelText,
                    pubAction = ""
                )

            fun fromExtendAndModuleAuthorForward(
                extend: bilibili.app.dynamic.v2.Extend,
                moduleAuthorForward: bilibili.app.dynamic.v2.ModuleAuthorForward
            ) =
                DynamicAuthorModule(
                    author = extend.origName,
                    avatar = extend.origFace,
                    mid = extend.uid,
                    pubTime = moduleAuthorForward.ptimeLabelText,
                    pubAction = ""
                )
        }
    }

    data class DynamicVideoModule(
        val aid: Long,
        val bvid: String? = null,
        val cid: Long,
        val epid: Int? = null,
        val seasonId: Int? = null,
        val title: String,
        var text: String,
        val cover: String,
        val duration: String,
        val play: String,
        val danmaku: String,
        /** 角标文案（动态视频/合作视频/充电专属…），接口原值，卡片右上角直接展示 */
        val badgeText: String = "",
        val playCount: Long = 0L,
        val danmakuCount: Int = 0,
        val durationSeconds: Int = 0
    ) {
        companion object {
            fun fromAdditionalUgc(
                ugc: dev.aaa1115910.biliapi.http.entity.dynamic.DynamicItem.Modules.Dynamic.Additional.Ugc
            ): DynamicVideoModule? {
                val aid = extractArchiveId(ugc.jumpUrl) ?: return null
                // `desc_second` 是「4.1万观看 53弹幕」这类统计文案，不是时长；
                // 时长在 `duration`（`02:50`），播放/弹幕在 `stat`。
                val play = ugc.stat?.play.orEmpty()
                val danmaku = ugc.stat?.danmaku.orEmpty()
                return DynamicVideoModule(
                    aid = aid,
                    cid = 0,
                    title = ugc.title,
                    text = "",
                    cover = ugc.cover,
                    duration = ugc.duration,
                    play = play,
                    danmaku = danmaku,
                    playCount = convertStringPlayCountToNumberPlayCount(play),
                    danmakuCount = convertStringPlayCountToNumberPlayCount(danmaku).toInt(),
                    durationSeconds = convertStringTimeToSeconds(ugc.duration)
                )
            }

            fun fromModuleArchive(moduleArchive: dev.aaa1115910.biliapi.http.entity.dynamic.DynamicItem.Modules.Dynamic.Major.Archive): DynamicVideoModule {
                val play = moduleArchive.stat?.play ?: ""
                val danmaku = moduleArchive.stat?.danmaku ?: ""
                return DynamicVideoModule(
                    aid = moduleArchive.aid.toLong(),
                    bvid = moduleArchive.bvid,
                    cid = 0,
                    title = moduleArchive.title,
                    text = moduleArchive.desc,
                    cover = moduleArchive.cover,
                    duration = moduleArchive.durationText,
                    play = play,
                    danmaku = danmaku,
                    badgeText = moduleArchive.badge?.text.orEmpty(),
                    playCount = convertStringPlayCountToNumberPlayCount(play),
                    danmakuCount = convertStringPlayCountToNumberPlayCount(danmaku).toInt(),
                    durationSeconds = convertStringTimeToSeconds(moduleArchive.durationText)
                )
            }

            fun fromModuleArchive(moduleArchive: bilibili.app.dynamic.v2.MdlDynArchive): DynamicVideoModule {
                val badgeText = moduleArchive.badgeList.firstOrNull()?.text ?: ""
                return DynamicVideoModule(
                    aid = moduleArchive.avid,
                    bvid = moduleArchive.bvid,
                    cid = moduleArchive.cid,
                    epid = moduleArchive.episodeId.toInt(),
                    seasonId = moduleArchive.pgcSeasonId.toInt(),
                    title = moduleArchive.title,
                    text = "",
                    cover = moduleArchive.cover,
                    duration = moduleArchive.coverLeftText1,
                    play = moduleArchive.coverLeftText2,
                    danmaku = moduleArchive.coverLeftText3,
                    badgeText = badgeText,
                    playCount = convertStringPlayCountToNumberPlayCount(moduleArchive.coverLeftText2),
                    danmakuCount = convertStringPlayCountToNumberPlayCount(moduleArchive.coverLeftText3).toInt(),
                    durationSeconds = moduleArchive.duration.toInt().takeIf { it > 0 }
                        ?: convertStringTimeToSeconds(moduleArchive.coverLeftText1)
                )
            }

            fun fromModuleUgcSeason(ugcSeason: dev.aaa1115910.biliapi.http.entity.dynamic.DynamicItem.Modules.Dynamic.Major.UgcSeason): DynamicVideoModule {
                val play = ugcSeason.stat?.play ?: ""
                val danmaku = ugcSeason.stat?.danmaku ?: ""
                return DynamicVideoModule(
                    aid = ugcSeason.aid,
                    bvid = ugcSeason.bvid,
                    cid = 0,
                    title = ugcSeason.title,
                    text = ugcSeason.desc,
                    cover = ugcSeason.cover,
                    duration = ugcSeason.durationText,
                    play = play,
                    danmaku = danmaku,
                    badgeText = ugcSeason.badge?.text.orEmpty(),
                    playCount = convertStringPlayCountToNumberPlayCount(play),
                    danmakuCount = convertStringPlayCountToNumberPlayCount(danmaku).toInt(),
                    durationSeconds = convertStringTimeToSeconds(ugcSeason.durationText)
                )
            }

            fun fromModuleUgcSeason(ugcSeason: bilibili.app.dynamic.v2.MdlDynUGCSeason): DynamicVideoModule =
                DynamicVideoModule(
                    aid = ugcSeason.avid,
                    bvid = null,
                    cid = ugcSeason.cid,
                    title = ugcSeason.title,
                    text = "",
                    cover = ugcSeason.cover,
                    duration = ugcSeason.coverLeftText1,
                    play = ugcSeason.coverLeftText2,
                    danmaku = ugcSeason.coverLeftText3,
                    playCount = convertStringPlayCountToNumberPlayCount(ugcSeason.coverLeftText2),
                    danmakuCount = convertStringPlayCountToNumberPlayCount(ugcSeason.coverLeftText3).toInt(),
                    durationSeconds = ugcSeason.duration.toInt().takeIf { it > 0 }
                        ?: convertStringTimeToSeconds(ugcSeason.coverLeftText1)
                )
        }
    }

    data class DynamicFooterModule(
        val like: Int,
        val comment: Int,
        val share: Int,
        val liked: Boolean = false,
        val favorite: Int = 0,
        val favorited: Boolean = false
    ) {
        companion object {
            fun fromModuleStat(moduleStat: dev.aaa1115910.biliapi.http.entity.dynamic.DynamicItem.Modules.Stat?) =
                moduleStat?.let {
                    DynamicFooterModule(
                        like = moduleStat.like?.count ?: 0,
                        comment = moduleStat.comment?.count ?: 0,
                        share = moduleStat.forward?.count ?: 0,
                        liked = moduleStat.like?.status == true,
                        favorite = moduleStat.favorite?.count ?: 0,
                        favorited = moduleStat.favorite?.status == true
                    )
                }

            fun fromModuleStat(moduleStat: bilibili.app.dynamic.v2.ModuleStat) =
                DynamicFooterModule(
                    like = moduleStat.like.toInt(),
                    comment = moduleStat.reply.toInt(),
                    share = moduleStat.repost.toInt(),
                    liked = moduleStat.likeInfo.isLike
                )

            fun fromModuleBottom(moduleButtom: bilibili.app.dynamic.v2.ModuleButtom) =
                fromModuleStat(moduleButtom.moduleStat)
        }
    }

    data class DynamicBlockedModule(
        val title: String,
        val hintMessage: String,
        val iconUrl: String,
        val bgImageUrl: String = "",
        val buttonText: String = "",
        val jumpUrl: String = ""
    ) {
        companion object {
            fun fromModuleBlocked(blocked: dev.aaa1115910.biliapi.http.entity.dynamic.DynamicItem.Modules.Dynamic.Major.Blocked) =
                DynamicBlockedModule(
                    title = blocked.title,
                    hintMessage = blocked.hintMessage,
                    iconUrl = blocked.icon?.imgDark?.takeIf { it.isNotBlank() }
                        ?: blocked.icon?.imgDay.orEmpty(),
                    bgImageUrl = blocked.bgImg?.imgDark?.takeIf { it.isNotBlank() }
                        ?: blocked.bgImg?.imgDay.orEmpty(),
                    buttonText = blocked.button?.text.orEmpty(),
                    jumpUrl = blocked.button?.jumpUrl.orEmpty()
                )

            /**
             * 被屏蔽的动态（充电专属等）没有内容模块。
             *
             * 部分接口只把 `major.type` 标成 `MAJOR_TYPE_BLOCKED`，`blocked` 载荷缺失，
             * 此时退回 `bg_img`、`none.tips` 与图文内容取封面/标题，避免卡片只剩一个类型角标。
             */
            fun fromModuleDynamic(
                major: dev.aaa1115910.biliapi.http.entity.dynamic.DynamicItem.Modules.Dynamic.Major
            ): DynamicBlockedModule? {
                val blocked = major.blocked
                if (blocked == null && major.type != DynamicMajorType.Blocked) return null
                val base = blocked?.let(::fromModuleBlocked)
                val iconUrl = base?.iconUrl?.takeIf { it.isNotBlank() }
                    ?: listOfNotNull(
                        blocked?.bgImg?.imgDark,
                        blocked?.bgImg?.imgDay,
                        major.opus?.pics?.firstOrNull()?.url,
                        major.draw?.items?.firstOrNull()?.src
                    ).firstOrNull { it.isNotBlank() }.orEmpty()
                val hintMessage = base?.hintMessage.orEmpty().trim()
                    .ifBlank { major.none?.tips.orEmpty().trim() }
                return DynamicBlockedModule(
                    // 卡片优先展示 hint_message，两者都缺失时兜底一个可读标题
                    title = base?.title.orEmpty().trim().ifBlank {
                        if (hintMessage.isBlank()) "充电专属动态" else ""
                    },
                    hintMessage = hintMessage,
                    iconUrl = iconUrl,
                    bgImageUrl = base?.bgImageUrl.orEmpty(),
                    buttonText = base?.buttonText.orEmpty(),
                    jumpUrl = base?.jumpUrl.orEmpty()
                )
            }
        }
    }

    data class DynamicDrawModule(
        val title: String?,
        val text: String,
        val images: List<Picture>
    ) {
        companion object {
            fun fromModuleDynamic(moduleDynamic: dev.aaa1115910.biliapi.http.entity.dynamic.DynamicItem.Modules.Dynamic) =
                DynamicDrawModule(
                    title = moduleDynamic.major?.opus?.title?.takeIf { it.isNotBlank() },
                    text = moduleDynamic.desc?.text?.takeIf { it.isNotBlank() }
                        ?: moduleDynamic.major?.opus?.summary?.text.orEmpty(),
                    images = drawImages(moduleDynamic)
                )

            /**
             * `major.draw.items` 是旧结构，opus 的图片在 `opus.pics`。
             * 少数图文把图片只放在富文本节点里（`rich_text_nodes[].pics`/`dyn_pic`），
             * `pics` 为空时用它们兜底，否则引用块会只剩一段文字。
             * 注意不能和 `pics` 合并：视频节点的 `pics` 是视频封面，混进图片行会多出一张图。
             */
            private fun drawImages(
                moduleDynamic: dev.aaa1115910.biliapi.http.entity.dynamic.DynamicItem.Modules.Dynamic
            ): List<Picture> {
                val drawImages = moduleDynamic.major?.draw?.items
                    ?.map(Picture::fromPicture)
                    .orEmpty()
                val opusImages = moduleDynamic.major?.opus?.let { opus ->
                    val pics = opus.pics.ifEmpty {
                        opus.summary.richTextNodes.flatMap { node -> node.pics + node.dynPic }
                    }
                    pics.map(Picture::fromPicture)
                }.orEmpty()
                return drawImages.ifEmpty { opusImages }
                    .filter { it.url.isNotBlank() }
                    .distinctBy { it.url }
            }

            fun fromModuleOpusSummaryAndModuleDynamic(
                moduleOpusSummary: bilibili.app.dynamic.v2.ModuleOpusSummary,
                moduleDynamic: bilibili.app.dynamic.v2.ModuleDynamic?
            ): DynamicDrawModule {
                var title = ""
                var text = ""
                val images = mutableListOf<Picture>()

                when (val titleContentType = moduleOpusSummary.title.contentCase) {
                    Paragraph.ContentCase.TEXT -> title = moduleOpusSummary.title.text.nodesList
                        .joinToString("") { it.rawText }

                    else -> println("not implemented: ModuleOpusSummary titleContentType: $titleContentType")
                }

                when (val summaryContentType = moduleOpusSummary.summary.contentCase) {
                    Paragraph.ContentCase.TEXT -> text = moduleOpusSummary.summary.text.nodesList
                        .joinToString("") { it.rawText }

                    else -> println("not implemented: ModuleOpusSummary summaryContentType: $summaryContentType")
                }

                when (val dynamicItemType = moduleDynamic?.moduleItemCase) {
                    null -> println("ModuleDynamic is null")
                    ModuleItemCase.DYN_DRAW -> images.addAll(
                        moduleDynamic.dynDraw.itemsList.map(Picture::fromPicture)
                    )

                    else -> println("not implemented: ModuleOpusSummary dynamicItemType $dynamicItemType")
                }

                return DynamicDrawModule(
                    title = title,
                    text = text,
                    images = images.distinctBy { it.url }
                )
            }

            fun fromModuleDescAndModuleDynamic(
                moduleParagraph: bilibili.app.dynamic.v2.ModuleParagraph?,
                moduleDesc: bilibili.app.dynamic.v2.ModuleDesc,
                moduleDynamic: bilibili.app.dynamic.v2.ModuleDynamic?
            ): DynamicDrawModule {
                var title = ""
                var text = ""
                val images = mutableListOf<Picture>()

                text = moduleDesc.descList.joinToString("") { it.text }

                if (moduleParagraph != null && moduleParagraph.isArticleTitle) {

                    when (val titleContentType = moduleParagraph.paragraph.contentCase) {
                        Paragraph.ContentCase.TEXT -> title =
                            moduleParagraph.paragraph.text.nodesList
                                .joinToString("") { it.rawText }

                        else -> println("not implemented: ModuleOpusSummary titleContentType: $titleContentType")
                    }
                }

                when (val dynamicItemType = moduleDynamic?.moduleItemCase) {
                    null -> println("ModuleDynamic is null")
                    ModuleItemCase.DYN_DRAW -> images.addAll(
                        moduleDynamic.dynDraw.itemsList.map(Picture::fromPicture)
                    )

                    else -> println("not implemented: ModuleOpusSummary dynamicItemType $dynamicItemType")
                }

                return DynamicDrawModule(
                    title = title,
                    text = text,
                    images = images.distinctBy { it.url }
                )
            }
        }
    }

    data class DynamicWordModule(
        val text: String
    ) {
        companion object {
            fun fromModuleDynamic(moduleDynamic: dev.aaa1115910.biliapi.http.entity.dynamic.DynamicItem.Modules.Dynamic) =
                DynamicWordModule(
                    text = moduleDynamic.major?.opus?.summary?.text
                        ?: moduleDynamic.desc?.text
                        ?: ""
                )

            fun fromModuleOpusSummary(moduleOpusSummary: bilibili.app.dynamic.v2.ModuleOpusSummary) =
                DynamicWordModule(
                    text = moduleOpusSummary.summary.text.nodesList
                        .joinToString("") { it.rawText }
                )

            fun fromModuleDesc(moduleDesc: bilibili.app.dynamic.v2.ModuleDesc) =
                DynamicWordModule(
                    text = moduleDesc.text
                )
        }
    }

    data class DynamicLiveRcmdModule(
        val title: String,
        val cover: String,
        val roomId: Int,
        val areaName: String = "",
        val parentAreaName: String = "",
        val online: Int = 0,
        val liveStatus: Int = 0,
        val watchedText: String = "",
        val link: String = ""
    ) {
        companion object {
            private val json = Json {
                coerceInputValues = true
                ignoreUnknownKeys = true
                prettyPrint = true
            }

            fun fromModuleDynamic(moduleDynamic: dev.aaa1115910.biliapi.http.entity.dynamic.DynamicItem.Modules.Dynamic): DynamicLiveRcmdModule? {
                val content = moduleDynamic.major?.liveRcmd?.content ?: return null
                return decodeLiveRcmdContent(content)
            }

            fun fromModuleDynamic(moduleDynamic: bilibili.app.dynamic.v2.ModuleDynamic): DynamicLiveRcmdModule? =
                decodeLiveRcmdContent(moduleDynamic.dynLiveRcmd.content)

            /** 旧版直播动态用 major.live 下发，没有 live_rcmd 加密内容 */
            fun fromModuleLive(
                live: dev.aaa1115910.biliapi.http.entity.dynamic.DynamicItem.Modules.Dynamic.Major.Live
            ) = DynamicLiveRcmdModule(
                title = live.title,
                cover = live.cover,
                roomId = live.id.toInt(),
                areaName = live.descFirst,
                liveStatus = live.liveState,
                link = live.jumpUrl
            )

            private fun decodeLiveRcmdContent(content: String): DynamicLiveRcmdModule? {
                val liveRcmdContent = runCatching {
                    json.decodeFromString<LiveRcmdContent>(content)
                }.getOrNull() ?: return null
                val livePlayInfo = liveRcmdContent.livePlayInfo ?: return null
                return DynamicLiveRcmdModule(
                    title = livePlayInfo.title,
                    cover = livePlayInfo.cover,
                    roomId = livePlayInfo.roomId,
                    areaName = livePlayInfo.areaName,
                    parentAreaName = livePlayInfo.parentAreaName,
                    online = livePlayInfo.online,
                    liveStatus = livePlayInfo.liveStatus,
                    watchedText = livePlayInfo.watchedShow?.textLarge.orEmpty(),
                    link = livePlayInfo.link
                )
            }
        }

        @Serializable
        private data class LiveRcmdContent(
            @SerialName("live_play_info")
            val livePlayInfo: LivePlayInfo? = null,
            @SerialName("live_record_info")
            val liveRecordInfo: JsonElement? = null,
            val type: Int = 0
        ) {
            @Serializable
            data class LivePlayInfo(
                val title: String = "",
                @SerialName("parent_area_name")
                val parentAreaName: String = "",
                val cover: String = "",
                val online: Int = 0,
                @SerialName("parent_area_id")
                val parentAreaId: Int = 0,
                @SerialName("live_start_time")
                val liveStartTime: Long = 0,
                @SerialName("room_id")
                val roomId: Int = 0,
                @SerialName("live_status")
                val liveStatus: Int = 0,
                @SerialName("room_type")
                val roomType: Int = 0,
                @SerialName("play_type")
                val playType: Int = 0,
                val link: String = "",
                @SerialName("area_id")
                val areaId: Int = 0,
                @SerialName("area_name")
                val areaName: String = "",
                @SerialName("watched_show")
                val watchedShow: WatchedShow? = null,
                @SerialName("room_paid_type")
                val roomPaidType: Int = 0,
                val uid: Long = 0,
                @SerialName("live_screen_type")
                val liveScreenType: Int = 0,
                @SerialName("live_id")
                val liveId: Long = 0,
                val pendants: Pendants? = null
            ) {
                @Serializable
                data class WatchedShow(
                    val num: Int = 0,
                    @SerialName("text_small")
                    val textSmall: String = "",
                    @SerialName("text_large")
                    val textLarge: String = "",
                    val icon: String = "",
                    @SerialName("icon_location")
                    val iconLocation: String = "",
                    @SerialName("icon_web")
                    val iconWeb: String = "",
                    val switch: Boolean = false
                )

                @Serializable
                data class Pendants(
                    val list: JsonElement? = null
                )
            }
        }
    }

    data class DynamicPgcModule(
        val title: String,
        val epid: Int,
        val seasonId: Int,
        val cover: String,
        val aid: Long,
        val cid: Long
    ) {
        companion object {
            fun fromModulePgc(modulePgc: dev.aaa1115910.biliapi.http.entity.dynamic.DynamicItem.Modules.Dynamic.Major.Pgc) =
                DynamicPgcModule(
                    title = modulePgc.title,
                    epid = modulePgc.epid,
                    seasonId = modulePgc.seasonId,
                    cover = modulePgc.cover,
                    aid = 0,
                    cid = 0
                )

            fun fromModulePgc(modulePgc: bilibili.app.dynamic.v2.MdlDynPGC): DynamicPgcModule {
                return DynamicPgcModule(
                    title = modulePgc.title,
                    epid = modulePgc.epid.toInt(),
                    seasonId = modulePgc.seasonId.toInt(),
                    cover = modulePgc.cover,
                    aid = modulePgc.aid,
                    cid = modulePgc.cid
                )
            }
        }
    }

    data class DynamicArticleModule(
        val title: String,
        val text: String,
        val url: String,
        val label: String,
        val id: Int,
        val covers: List<String>
    ) {
        companion object {
            /**
             * 新版文章动态（`DYNAMIC_TYPE_ARTICLE` + `MAJOR_TYPE_OPUS`）没有 `major.article`，
             * 标题/正文/封面都在 opus 里，这里转成文章模块，卡片与详情页才能共用展示逻辑。
             */
            fun fromOpus(draw: DynamicDrawModule, jumpUrl: String) =
                DynamicArticleModule(
                    title = draw.title.orEmpty(),
                    text = draw.text,
                    url = jumpUrl,
                    label = "",
                    id = 0,
                    covers = draw.images.map { it.url }
                )

            fun fromModuleArticle(moduleDynamic: dev.aaa1115910.biliapi.http.entity.dynamic.DynamicItem.Modules.Dynamic.Major.Article) =
                DynamicArticleModule(
                    title = moduleDynamic.title,
                    text = moduleDynamic.desc,
                    url = moduleDynamic.jumpUrl,
                    label = moduleDynamic.label,
                    id = moduleDynamic.id,
                    covers = moduleDynamic.covers
                )

            fun fromModuleArticle(moduleArticle: bilibili.app.dynamic.v2.MdlDynArticle): DynamicArticleModule {
                return DynamicArticleModule(
                    title = moduleArticle.title,
                    text = moduleArticle.desc,
                    url = moduleArticle.uri,
                    label = moduleArticle.label,
                    covers = moduleArticle.coversList,
                    id = moduleArticle.id.toInt()
                )
            }
        }
    }

    data class DynamicNoneModule(
        val text: String
    ) {
        companion object {
            fun fromModuleDynamic(moduleNone: dev.aaa1115910.biliapi.http.entity.dynamic.DynamicItem.Modules.Dynamic.Major.None) =
                DynamicNoneModule(
                    text = moduleNone.tips
                )

            fun fromModuleDynamic(moduleNone: bilibili.app.dynamic.v2.ModuleItemNull): DynamicNoneModule {
                return DynamicNoneModule(
                    text = moduleNone.text
                )
            }
        }
    }

    data class DynamicUgcSeasonModule(
        val aid: Long,
        val bvid: String,
        val cover: String,
        val desc: String,
        val duration: String,
        val url: String,
        val play: String,
        val danmaku: String,
        val title: String
    ) {
        companion object {
            fun fromModuleUgcSeason(moduleDynamic: dev.aaa1115910.biliapi.http.entity.dynamic.DynamicItem.Modules.Dynamic.Major.UgcSeason) =
                DynamicUgcSeasonModule(
                    aid = moduleDynamic.aid,
                    bvid = moduleDynamic.bvid,
                    cover = moduleDynamic.cover,
                    desc = moduleDynamic.desc ?: "empty description",
                    duration = moduleDynamic.durationText,
                    url = moduleDynamic.jumpUrl,
                    play = moduleDynamic.stat?.play ?: "",
                    danmaku = moduleDynamic.stat?.danmaku ?: "",
                    title = moduleDynamic.title
                )
        }
    }
}

data class DynamicVideoData(
    val videos: List<DynamicVideo>,
    val hasMore: Boolean,
    val historyOffset: String,
    val updateBaseline: String
) {
    companion object {
        private val logger = KotlinLogging.logger { }
        fun fromDynamicData(data: dev.aaa1115910.biliapi.http.entity.dynamic.DynamicData) =
            DynamicVideoData(
                videos = data.items.map { DynamicVideo.fromDynamicVideoItem(it) },
                hasMore = data.hasMore,
                historyOffset = data.offset,
                updateBaseline = data.updateBaseline
            ).also {
                logger.info { "updateBaseline: ${data.updateBaseline}" }
                logger.info { "offset: ${data.offset}" }
            }

        fun fromDynamicData(data: bilibili.app.dynamic.v2.DynVideoReply) = DynamicVideoData(
            videos = data.dynamicList.listList.mapNotNull { DynamicVideo.fromDynamicVideoItem(it) },
            hasMore = data.dynamicList.hasMore,
            historyOffset = data.dynamicList.historyOffset,
            updateBaseline = data.dynamicList.updateBaseline
        ).also {
            logger.info { "updateBaseline: ${data.dynamicList.updateBaseline}" }
            logger.info { "historyOffset: ${data.dynamicList.historyOffset}" }
        }
    }
}

/**
 * 动态视频
 *
 * @property aid 视频av号
 * @property bvid 视频bv号，grpc pgc 没有bv号
 * @property cid 视频cid，仅 grpc 接口
 * @property epid 番剧epid，仅 grpc 接口
 * @property seasonId 番剧seasonId，仅 grpc 接口
 * @property title 视频标题
 * @property cover 视频封面
 * @property author 视频作者
 * @property duration 视频时长，单位秒
 * @property play 视频播放量
 * @property danmaku 视频弹幕数
 * @property avatar 视频作者头像
 * @property pubTime 发布时间
 */
data class DynamicVideo(
    val aid: Long,
    val bvid: String? = null,
    val cid: Long,
    val epid: Int? = null,
    val seasonId: Int? = null,
    val title: String,
    val cover: String,
    val author: String,
    var authorId: Long = 0,
    var authorFace: String = "",
    val duration: Int,
    val play: Long,
    val danmaku: Int,
    val avatar: String,
    val time: Long = 0L,
    val pubTime: String? = null,
    val isChargingArc: Boolean = false,
    val chargingArcBadge: String = ""
) {
    companion object {
        fun fromDynamicVideoItem(item: dev.aaa1115910.biliapi.http.entity.dynamic.DynamicItem): DynamicVideo {
            val archive = item.modules.moduleDynamic.major!!.archive!!
            val author = item.modules.moduleAuthor!!
            val isChargingArc = archive.badge?.text?.contains("充电") == true || archive.badge?.text?.contains("限时免费") == true
            return DynamicVideo(
                aid = archive.aid.toLong(),
                bvid = archive.bvid,
                cid = 0,
                title = archive.title
                    .replace("动态视频｜", ""),
                cover = archive.cover,
                author = author.name,
                authorId = author.mid,
                authorFace = author.face,
                duration = convertStringTimeToSeconds(archive.durationText),
                play = convertStringPlayCountToNumberPlayCount(archive.stat?.play ?: "0"),
                danmaku = convertStringPlayCountToNumberPlayCount(archive.stat?.danmaku ?: "0").toInt(),
                avatar = author.face,
                pubTime = author.pubTime,
                isChargingArc = isChargingArc,
                chargingArcBadge = if (isChargingArc) archive.badge?.text ?: "" else ""
            )
        }

        fun fromDynamicVideoItem(item: bilibili.app.dynamic.v2.DynamicItem): DynamicVideo? {
            val author =
                item.modulesList.first { it.moduleType == DynModuleType.module_author }.moduleAuthor.author
            val dynamic =
                item.modulesList.first { it.moduleType == DynModuleType.module_dynamic }.moduleDynamic
            val desc =
                item.modulesList.firstOrNull { it.moduleType == DynModuleType.module_desc }?.moduleDesc
            val isDynamicVideo = dynamic.dynArchive?.stype == VideoType.video_type_dynamic
            when (dynamic.moduleItemCase) {
                ModuleItemCase.DYN_ARCHIVE -> {
                    val archive = dynamic.dynArchive
                    return DynamicVideo(
                        aid = archive.avid,
                        bvid = archive.bvid,
                        cid = archive.cid,
                        title = if (!isDynamicVideo) archive.title else {
                            desc?.text?.replace("动态视频｜", "") ?: "NO TITLE"
                        },
                        cover = archive.cover,
                        author = author.name,
                        authorId = author.mid,
                        authorFace = author.face,
                        duration = convertStringTimeToSeconds(archive.coverLeftText1),
                        play = convertStringPlayCountToNumberPlayCount(archive.coverLeftText2),
                        danmaku = convertStringPlayCountToNumberPlayCount(archive.coverLeftText3).toInt(),
                        avatar = author.face
                    )
                }

                ModuleItemCase.DYN_PGC -> {
                    val pgc = dynamic.dynPgc
                    return DynamicVideo(
                        aid = pgc.aid,
                        bvid = null,
                        cid = pgc.cid,
                        epid = pgc.epid.toInt(),
                        seasonId = pgc.seasonId.toInt(),
                        title = pgc.title,
                        cover = pgc.cover,
                        author = author.name,
                        duration = convertStringTimeToSeconds(pgc.coverLeftText1),
                        play = convertStringPlayCountToNumberPlayCount(pgc.coverLeftText2),
                        danmaku = convertStringPlayCountToNumberPlayCount(pgc.coverLeftText3).toInt(),
                        avatar = author.face
                    )
                }

                ModuleItemCase.DYN_CHARGING_ARCHIVE -> {
                    val chargingArchiveInfo = dynamic.dynChargingArchive.archiveInfo
                    return DynamicVideo(
                        aid = chargingArchiveInfo.avid,
                        bvid = chargingArchiveInfo.bvid,
                        cid = chargingArchiveInfo.cid,
                        title = chargingArchiveInfo.title,
                        cover = chargingArchiveInfo.cover,
                        author = author.name,
                        duration = convertStringTimeToSeconds(chargingArchiveInfo.coverLeftText1),
                        play = convertStringPlayCountToNumberPlayCount(chargingArchiveInfo.coverLeftText2),
                        danmaku = convertStringPlayCountToNumberPlayCount(chargingArchiveInfo.coverLeftText3).toInt(),
                        avatar = author.face
                    )
                }

                else -> {
                    println("unsupported dynamic moduleItemCase: ${dynamic.moduleItemCase}")
                    return null
                }
            }
        }
    }
}

private fun convertStringTimeToSeconds(time: String): Int {
    //部分稿件可能没有时长，Web 接口返回 NaN:NaN:NaN，App 接口返回空字符串
    if (time.startsWith("NaN") || time.isBlank()) return 0
    // 时长字段偶尔会塞进别的文案（如图文附带视频的 desc_second），
    // 解析失败只能让时长归零，不能让整条动态被丢掉
    return runCatching {
        val parts = time.split(":")
        if (parts.size < 2) return@runCatching 0
        val hours = if (parts.size >= 3) parts[parts.size - 3].toInt() else 0
        val minutes = parts[parts.size - 2].toInt()
        val seconds = parts[parts.size - 1].toInt()
        (hours * 3600) + (minutes * 60) + seconds
    }.getOrDefault(0)
}

//web 接口获取到的是“xx万”，而 grpc 接口获取到的是“xx.x万播放”
private fun convertStringPlayCountToNumberPlayCount(play: String): Long {
    if (play.startsWith("-")) return 0
    runCatching {
        val number = play
            .replace("弹幕", "")
            .replace("观看", "")
            .replace("播放", "")
            .substringBefore("万").toFloat()
        return (if (play.contains("万")) number * 10000 else number).toLong()
    }.onFailure {
        println("convert play count [$play] failed: ${it.stackTraceToString()}")
    }
    return -1
}

enum class DynamicType(val webValue: String, val appValue: bilibili.app.dynamic.v2.DynamicType) {
    Av("DYNAMIC_TYPE_AV", bilibili.app.dynamic.v2.DynamicType.av),

    // bilibili hd 端的接口并不会返回合集更新动态
    UgcSeason("DYNAMIC_TYPE_UGC_SEASON", bilibili.app.dynamic.v2.DynamicType.ugc_season),
    Forward("DYNAMIC_TYPE_FORWARD", bilibili.app.dynamic.v2.DynamicType.forward),
    Word("DYNAMIC_TYPE_WORD", bilibili.app.dynamic.v2.DynamicType.word),
    Draw("DYNAMIC_TYPE_DRAW", bilibili.app.dynamic.v2.DynamicType.draw),
    LiveRcmd("DYNAMIC_TYPE_LIVE_RCMD", bilibili.app.dynamic.v2.DynamicType.live_rcmd),
    Pgc("DYNAMIC_TYPE_PGC_UNION", bilibili.app.dynamic.v2.DynamicType.pgc),
    Article("DYNAMIC_TYPE_ARTICLE", bilibili.app.dynamic.v2.DynamicType.article),
    None("DYNAMIC_TYPE_NONE", bilibili.app.dynamic.v2.DynamicType.dyn_none);

    companion object {
        fun fromWebValue(webValue: String) = entries.firstOrNull { it.webValue == webValue }
            ?: throw IllegalArgumentException("unknown type $webValue")

        fun fromWebValueOrNull(webValue: String?): DynamicType? =
            entries.firstOrNull { it.webValue == webValue }

        fun fromAppValue(appValue: bilibili.app.dynamic.v2.DynamicType) =
            entries.firstOrNull { it.appValue == appValue }
                ?: throw IllegalArgumentException("unknown type ${appValue.name}")
    }
}

/**
 * 内容类型（`module_dynamic.major.type`）。
 *
 * 外层 [DynamicType] 是卡片的声明类型，这里才是实际内容：同样是 `DYNAMIC_TYPE_DRAW`，
 * 可能是 `MAJOR_TYPE_OPUS`，也可能是 `MAJOR_TYPE_BLOCKED`（充电专属）。
 * gRPC 接口没有这个字符串，此时 [DynamicItem.majorType] 为空。
 */
object DynamicMajorType {
    const val None = "MAJOR_TYPE_NONE"
    const val Archive = "MAJOR_TYPE_ARCHIVE"
    const val Opus = "MAJOR_TYPE_OPUS"
    const val Draw = "MAJOR_TYPE_DRAW"
    const val Article = "MAJOR_TYPE_ARTICLE"
    const val Pgc = "MAJOR_TYPE_PGC"
    const val Live = "MAJOR_TYPE_LIVE"
    const val LiveRcmd = "MAJOR_TYPE_LIVE_RCMD"
    const val Blocked = "MAJOR_TYPE_BLOCKED"
}

private fun Module.isAuthorModule() = moduleType == DynModuleType.module_author
private fun Module.isAuthorModuleForward() = moduleType == DynModuleType.module_author_forward
private fun Module.isDescModule() = moduleType == DynModuleType.module_desc
private fun Module.isDynamicModule() = moduleType == DynModuleType.module_dynamic
private fun Module.isModuleOpusSummary() = moduleType == DynModuleType.module_opus_summary
private fun Module.isStatModule() = moduleType == DynModuleType.module_stat
private fun Module.isBottomModel() = moduleType == DynModuleType.module_bottom
private fun Module.isItemNullModel() = moduleType == DynModuleType.module_item_null
private fun Module.isParagraphModel() = moduleType == DynModuleType.module_paragraph

private fun bilibili.app.dynamic.v2.DynamicItem.getAuthorModule() =
    modulesList.firstOrNull { it.isAuthorModule() }?.moduleAuthor

private fun bilibili.app.dynamic.v2.DynamicItem.getAuthorModuleForward() =
    modulesList.firstOrNull { it.isAuthorModuleForward() }?.moduleAuthorForward

private fun bilibili.app.dynamic.v2.DynamicItem.getDescModule() =
    modulesList.firstOrNull { it.isDescModule() }?.moduleDesc

private fun bilibili.app.dynamic.v2.DynamicItem.getDynamicModule() =
    modulesList.firstOrNull { it.isDynamicModule() }?.moduleDynamic

private fun bilibili.app.dynamic.v2.DynamicItem.getOpusSummaryModule() =
    modulesList.firstOrNull { it.isModuleOpusSummary() }?.moduleOpusSummary

private fun bilibili.app.dynamic.v2.DynamicItem.getStatModule() =
    modulesList.firstOrNull { it.isStatModule() }?.moduleStat

private fun bilibili.app.dynamic.v2.DynamicItem.getBottomModule() =
    modulesList.firstOrNull { it.isBottomModel() }?.moduleButtom

private fun bilibili.app.dynamic.v2.DynamicItem.getItemNullModule() =
    modulesList.firstOrNull { it.isItemNullModel() }?.moduleItemNull

private fun bilibili.app.dynamic.v2.DynamicItem.getParagraphModule() =
    modulesList.firstOrNull { it.isParagraphModel() }?.moduleParagraph