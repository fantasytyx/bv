package dev.aaa1115910.biliapi.entity

import bilibili.app.playerunite.v1.PlayViewUniteReply
import bilibili.pgc.gateway.player.v2.dashVideoOrNull
import bilibili.pgc.gateway.player.v2.dolbyOrNull
import bilibili.playershared.dashVideoOrNull
import bilibili.playershared.dolbyOrNull
import bilibili.playershared.lossLessItemOrNull
import bilibili.playershared.segmentVideoOrNull
import bilibili.playershared.volumeOrNull
import dev.aaa1115910.biliapi.http.entity.video.ClipInfo

data class PlayData(
    val dashVideos: List<DashVideo>,
    val dashAudios: List<DashAudio>,
    val dolby: DashAudio? = null,
    val flac: DashAudio? = null,
    val codec: Map<Int, List<String>> = emptyMap(),
    val needPay: Boolean = false,
    val clipInfoList: List<ClipInfo> = emptyList(),
    val volume: VolumeInfo? = null,
) {
    companion object {
        fun fromPlayViewUniteReply(playViewUniteReply: PlayViewUniteReply): PlayData {
            val vodInfo = playViewUniteReply.vodInfo

            // 过滤出有 dashVideo 的流
            val dashVideoStreams = vodInfo.streamListList.filter { it.dashVideoOrNull != null }
            // 过滤出有 segmentVideo 的流（试看流）
            val segmentVideoStreams = vodInfo.streamListList.filter { it.segmentVideoOrNull != null }

            val audioList = vodInfo.dashAudioList
            val dolbyItem = vodInfo.dolbyOrNull?.audioList?.firstOrNull()
            val lossLessItem =
                vodInfo.lossLessItemOrNull?.audio.takeIf { it?.id != 0 }

            // 处理 dashVideo
            val dashVideos = dashVideoStreams.map {
                DashVideo(
                    quality = it.streamInfo.quality,
                    baseUrl = it.dashVideo.baseUrl,
                    bandwidth = it.dashVideo.bandwidth,
                    codecId = it.dashVideo.codecid,
                    width = it.dashVideo.width,
                    height = it.dashVideo.height,
                    frameRate = it.dashVideo.frameRate,
                    backUrl = it.dashVideo.backupUrlList,
                    codecs = CodeType.fromCodecId(it.dashVideo.codecid).str
                )
            }.toMutableList()

            val isPreview = dashVideos.isEmpty() && segmentVideoStreams.isNotEmpty()

            // 当 dashVideo 不存在时，使用 segmentVideo（试看流）的 durl 填充
            if (isPreview) {
                segmentVideoStreams.forEach { stream ->
                    val firstSegment = stream.segmentVideo.segmentList.firstOrNull()
                    if (firstSegment != null) {
                        dashVideos.add(
                            DashVideo(
                                quality = stream.streamInfo.quality,
                                baseUrl = firstSegment.url,
                                bandwidth = 0,
                                codecId = stream.streamInfo.quality,
                                width = 0,
                                height = 0,
                                frameRate = "",
                                backUrl = firstSegment.backupUrlList,
                                codecs = CodeType.fromCodecId(stream.streamInfo.quality).str
                            )
                        )
                    }
                }
            }
            val dashAudios = audioList.map {
                DashAudio(
                    baseUrl = it.baseUrl,
                    bandwidth = it.bandwidth,
                    codecId = it.id,
                    backUrl = it.backupUrlList
                )
            }
            val dolby = dolbyItem?.let {
                DashAudio(
                    baseUrl = it.baseUrl,
                    bandwidth = it.bandwidth,
                    codecId = it.id,
                    backUrl = it.backupUrlList
                )
            }
            val flac = lossLessItem?.let {
                DashAudio(
                    baseUrl = it.baseUrl,
                    bandwidth = it.bandwidth,
                    codecId = it.id,
                    backUrl = it.backupUrlList
                )
            }

            // 生成 codec 映射（优先使用 dashVideo，如果没有则使用 segmentVideo）
            val codecs = if (dashVideoStreams.isNotEmpty()) {
                dashVideoStreams.associate {
                    it.streamInfo.quality to listOf(CodeType.fromCodecId(it.dashVideo.codecid).str)
                }
            } else {
                segmentVideoStreams.associate {
                    it.streamInfo.quality to listOf(CodeType.fromCodecId(it.streamInfo.quality).str)
                }
            }

            return PlayData(
                dashVideos = dashVideos,
                dashAudios = dashAudios,
                dolby = dolby,
                flac = flac,
                codec = codecs,
                needPay = isPreview,
                volume = vodInfo.volumeOrNull?.toVolumeInfo()
            )
        }

        fun fromPgcPlayViewReply(pgcPlayViewReply: bilibili.pgc.gateway.player.v2.PlayViewReply): PlayData {
            val streamList =
                pgcPlayViewReply.videoInfo.streamListList.filter { it.dashVideoOrNull != null }
            val audioList = pgcPlayViewReply.videoInfo.dashAudioList
            val dolbyItem = pgcPlayViewReply.videoInfo.dolbyOrNull?.audio
            val codecs = pgcPlayViewReply.videoInfo.streamListList.associate {
                it.info.quality to listOf(CodeType.fromCodecId(it.dashVideo.codecid).str)
            }
            val needPay = pgcPlayViewReply.business.isPreview

            val dashVideos = streamList.map {
                DashVideo(
                    quality = it.info.quality,
                    baseUrl = it.dashVideo.baseUrl,
                    bandwidth = it.dashVideo.bandwidth,
                    codecId = it.dashVideo.codecid,
                    width = it.dashVideo.width,
                    height = it.dashVideo.height,
                    frameRate = it.dashVideo.frameRate,
                    backUrl = it.dashVideo.backupUrlList,
                    codecs = CodeType.fromCodecId(it.dashVideo.codecid).str
                )
            }
            val dashAudios = audioList.map {
                DashAudio(
                    baseUrl = it.baseUrl,
                    bandwidth = it.bandwidth,
                    codecId = it.id,
                    backUrl = it.backupUrlList
                )
            }
            val dolby = dolbyItem?.let {
                DashAudio(
                    baseUrl = it.baseUrl,
                    bandwidth = it.bandwidth,
                    codecId = it.codecid,
                    backUrl = it.backupUrlList
                )
            }

            // gRPC PGC 的 proto VideoInfo 没有 volume 字段，无法提供响度元数据
            return PlayData(
                dashVideos = dashVideos,
                dashAudios = dashAudios,
                dolby = dolby,
                flac = null,
                codec = codecs,
                needPay = needPay
            )
        }

        fun fromPlayUrlV2Data(playUrlV2Data: dev.aaa1115910.biliapi.http.entity.video.PlayUrlV2Data): PlayData {
            return fromPlayUrlData(playUrlV2Data.videoInfo)
        }

        fun fromPlayUrlData(playUrlData: dev.aaa1115910.biliapi.http.entity.video.PlayUrlData): PlayData {
            val hasDash = playUrlData.dash != null
            val isPreview = !hasDash && playUrlData.durl.isNotEmpty()

            val audios = playUrlData.dash?.audio
            val dolbyItem = playUrlData.dash?.dolby?.audio?.firstOrNull()
            val flacItem = playUrlData.dash?.flac?.audio
            val codec = if (hasDash) {
                playUrlData.supportFormats
                    .mapNotNull { it.codecs?.let { c -> it.quality to c } }
                    .toMap()
            } else {
                mapOf(playUrlData.quality to listOf(CodeType.fromCodecId(playUrlData.videoCodecId).str))
            }

            val dashVideos = if (hasDash) {
                playUrlData.dash!!.video.map {
                    DashVideo(
                        quality = it.id,
                        baseUrl = it.baseUrl,
                        bandwidth = it.bandwidth,
                        codecId = it.id,
                        width = it.width,
                        height = it.height,
                        frameRate = it.frameRate,
                        backUrl = it.backupUrl,
                        codecs = it.codecs
                    )
                }
            } else {
                // 充电视频未付费状态下没有 dash，只有试看流 durl，转成 DASH 结构
                playUrlData.durl.map {
                    DashVideo(
                        quality = playUrlData.quality,
                        baseUrl = it.url,
                        backUrl = it.backupUrl,
                        codecId = playUrlData.videoCodecId,
                        bandwidth = 0, width = 0, height = 0, frameRate = "", codecs = ""
                    )
                }
            }
            val dashAudios = audios?.map {
                DashAudio(
                    baseUrl = it.baseUrl,
                    bandwidth = it.bandwidth,
                    codecId = it.id,
                    backUrl = it.backupUrl
                )
            } ?: emptyList()
            val dolby = dolbyItem?.let {
                DashAudio(
                    baseUrl = it.baseUrl,
                    bandwidth = it.bandwidth,
                    codecId = it.id,
                    backUrl = it.backupUrl
                )
            }
            val flac = flacItem?.let {
                DashAudio(
                    baseUrl = it.baseUrl,
                    bandwidth = it.bandwidth,
                    codecId = it.id,
                    backUrl = it.backupUrl
                )
            }

            return PlayData(
                dashVideos = dashVideos,
                dashAudios = dashAudios,
                dolby = dolby,
                flac = flac,
                codec = codec,
                needPay = isPreview,
                clipInfoList = playUrlData.clipInfoList,
                volume = playUrlData.volume
            )
        }

        fun fromPlayUrlData(playUrlData: dev.aaa1115910.biliapi.http.entity.proxy.ProxyWebPlayUrlData): PlayData {
            val hasDash = playUrlData.dash != null
            val isPreview = !hasDash && playUrlData.durl.isNotEmpty()

            val audios = playUrlData.dash?.audio
            val dolbyItem = playUrlData.dash?.dolby?.audio?.firstOrNull()
            val flacItem = playUrlData.dash?.flac?.audio
            val codec = if (hasDash) {
                playUrlData.supportFormats
                    .mapNotNull { it.codecs?.let { c -> it.quality to c } }
                    .toMap()
            } else {
                mapOf(playUrlData.quality to listOf(CodeType.fromCodecId(playUrlData.videoCodecId).str))
            }

            val dashVideos = if (hasDash) {
                playUrlData.dash!!.video.map {
                    DashVideo(
                        quality = it.id,
                        baseUrl = it.baseUrl,
                        bandwidth = it.bandwidth,
                        codecId = it.id,
                        width = it.width,
                        height = it.height,
                        frameRate = it.frameRate,
                        backUrl = it.backupUrl,
                        codecs = it.codecs
                    )
                }
            } else {
                playUrlData.durl.map {
                    DashVideo(
                        quality = playUrlData.quality,
                        baseUrl = it.url,
                        backUrl = it.backupUrl,
                        codecId = playUrlData.videoCodecId,
                        bandwidth = 0, width = 0, height = 0, frameRate = "", codecs = ""
                    )
                }
            }
            val dashAudios = audios?.map {
                DashAudio(
                    baseUrl = it.baseUrl,
                    bandwidth = it.bandwidth,
                    codecId = it.id,
                    backUrl = it.backupUrl
                )
            } ?: emptyList()
            val dolby = dolbyItem?.let {
                DashAudio(
                    baseUrl = it.baseUrl,
                    bandwidth = it.bandwidth,
                    codecId = it.id,
                    backUrl = it.backupUrl
                )
            }
            val flac = flacItem?.let {
                DashAudio(
                    baseUrl = it.baseUrl,
                    bandwidth = it.bandwidth,
                    codecId = it.id,
                    backUrl = it.backupUrl
                )
            }

            return PlayData(
                dashVideos = dashVideos,
                dashAudios = dashAudios,
                dolby = dolby,
                flac = flac,
                codec = codec,
                needPay = isPreview,
                clipInfoList = playUrlData.clipInfoList,
                volume = playUrlData.volume
            )
        }

        fun fromPlayUrlData(playUrlData: dev.aaa1115910.biliapi.http.entity.proxy.ProxyAppPlayUrlData): PlayData {
            val hasDash = playUrlData.dash != null
            val isPreview = !hasDash && playUrlData.durl.isNotEmpty()

            val audios = playUrlData.dash?.audio
            val dolbyItem = playUrlData.dash?.dolby?.audio?.firstOrNull()
            val flacItem = playUrlData.dash?.flac?.audio
            val codec = if (hasDash) {
                playUrlData.supportFormats
                    .mapNotNull { it.codecs?.let { c -> it.quality to c } }
                    .toMap()
            } else {
                mapOf(playUrlData.quality to listOf(CodeType.fromCodecId(playUrlData.videoCodecId).str))
            }

            val dashVideos = if (hasDash) {
                playUrlData.dash!!.video.map {
                    DashVideo(
                        quality = it.id,
                        baseUrl = it.baseUrl,
                        bandwidth = it.bandwidth,
                        codecId = it.id,
                        width = it.width,
                        height = it.height,
                        frameRate = it.frameRate,
                        backUrl = it.backupUrl,
                        codecs = it.codecs
                    )
                }
            } else {
                playUrlData.durl.map {
                    DashVideo(
                        quality = playUrlData.quality,
                        baseUrl = it.url,
                        backUrl = it.backupUrl,
                        codecId = playUrlData.videoCodecId,
                        bandwidth = 0, width = 0, height = 0, frameRate = "", codecs = ""
                    )
                }
            }
            val dashAudios = audios?.map {
                DashAudio(
                    baseUrl = it.baseUrl,
                    bandwidth = it.bandwidth,
                    codecId = it.id,
                    backUrl = it.backupUrl
                )
            } ?: emptyList()
            val dolby = dolbyItem?.let {
                DashAudio(
                    baseUrl = it.baseUrl,
                    bandwidth = it.bandwidth,
                    codecId = it.id,
                    backUrl = it.backupUrl
                )
            }
            val flac = flacItem?.let {
                DashAudio(
                    baseUrl = it.baseUrl,
                    bandwidth = it.bandwidth,
                    codecId = it.id,
                    backUrl = it.backupUrl
                )
            }

            return PlayData(
                dashVideos = dashVideos,
                dashAudios = dashAudios,
                dolby = dolby,
                flac = flac,
                codec = codec,
                needPay = isPreview,
                clipInfoList = playUrlData.clipInfoList,
                volume = playUrlData.volume
            )
        }
    }

    operator fun plus(other: PlayData): PlayData {
        return PlayData(
            dashVideos = (dashVideos + other.dashVideos)
                .distinctBy { "${it.codecId}_${it.quality}" }
                .sortedByDescending { it.quality },
            dashAudios = (dashAudios + other.dashAudios)
                .distinctBy { it.codecId }
                .sortedByDescending { it.codecId },
            dolby = dolby ?: other.dolby,
            flac = flac ?: other.flac,
            codec = (codec.keys + other.codec.keys).associate { key ->
                key to (codec[key].orEmpty() + other.codec[key].orEmpty())
                    .distinct()
                    .filter { it != "none" }
            },
            needPay = needPay || other.needPay,
            clipInfoList = clipInfoList + other.clipInfoList,
            volume = volume ?: other.volume
        )
    }
}

/**
 * @param quality 视频分辨率
 * @param baseUrl 主线流
 * @param bandwidth 码率
 * @param codecId 编码ID
 * @param width 视频宽度
 * @param height 视频高度
 * @param frameRate 帧率
 * @param backUrl 备用流
 * @param codecs 编码格式 仅 Web 接口有该值
 */
data class DashVideo(
    val quality: Int,
    val baseUrl: String,
    val bandwidth: Int,
    val codecId: Int,
    val width: Int,
    val height: Int,
    val frameRate: String,
    val backUrl: List<String>,
    val codecs: String? = null
)

/**
 * @param baseUrl 主线流
 * @param bandwidth 码率
 * @param codecId 编码ID
 * @param backUrl 备用流
 */
data class DashAudio(
    val baseUrl: String,
    val bandwidth: Int,
    val codecId: Int,
    val backUrl: List<String>
)

/**
 * gRPC 的 VolumeInfo 没有 multi_scene_args，两挡目标响度会退化成共用 [VolumeInfo.targetI]。
 *
 * `targetOffset` 原样带过来仅作留档：增益策略不使用它（见 `AudioBalanceGain`），
 * 因此 proto3 把它读成 0.0 也不会影响结果。
 */
internal fun bilibili.playershared.VolumeInfo.toVolumeInfo() = VolumeInfo(
    measuredI = measuredI,
    measuredLra = measuredLra,
    measuredTp = measuredTp,
    measuredThreshold = measuredThreshold,
    targetOffset = targetOffset,
    targetI = targetI,
    targetTp = targetTp,
    multiSceneArgs = null,
)
