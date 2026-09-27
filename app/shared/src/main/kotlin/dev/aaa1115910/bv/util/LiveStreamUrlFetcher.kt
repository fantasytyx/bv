package dev.aaa1115910.bv.util

import android.widget.Toast
import dev.aaa1115910.biliapi.entity.live.LiveCodec as ApiLiveCodec
import dev.aaa1115910.biliapi.entity.live.LiveRoomPlayInfoResponse
import dev.aaa1115910.biliapi.entity.live.LiveUrlInfo
import dev.aaa1115910.bv.player.entity.LiveCodec as AppLiveCodec
import dev.aaa1115910.biliapi.entity.live.LiveStream
import dev.aaa1115910.bv.player.entity.LiveStreamLine
import dev.aaa1115910.biliapi.repositories.LiveRepository
import dev.aaa1115910.bv.BVApp
import dev.aaa1115910.bv.util.Prefs
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.java.KoinJavaComponent.inject

object LiveStreamUrlFetcher {
    private val logger = KotlinLogging.logger("LiveStreamUrlFetcher")
    private val liveRepository: LiveRepository by inject(LiveRepository::class.java)

    // Codec 优先级: HEVC > AV1 > AVC
    private val codecPriority = listOf("hevc", "av1", "avc")

    /**
     * 获取直播流URL
     * @param roomId 直播间ID
     * @param qn 画质编号，默认30000（杜比，最高值），服务端会自动降级到实际最高可用画质
     * @param preferredCodec 首选编码格式，默认 HLS 自动选择最佳编码
     * @param preferredLineIndex 首选线路序号，null 表示自动选择当前最佳线路
     * @return 直播播放信息，包含流URL和可用画质列表，如果未开播或获取失败则返回null
     */
    suspend fun fetchLiveStreamUrl(
        roomId: Int,
        qn: Int = 30000,
        preferredCodec: AppLiveCodec = AppLiveCodec.HLS,
        preferredLineIndex: Int? = null
    ): LivePlayInfo? = withContext(Dispatchers.IO) {
        try {
            val sessData = Prefs.sessData
            val response = liveRepository.getLiveRoomPlayInfo(roomId, qn, sessData)
            
            if (response.code != 0) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        BVApp.context,
                        "获取直播流失败: ${response.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                return@withContext null
            }

            val data = response.data
            if (data == null) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        BVApp.context,
                        "获取直播流失败: 数据为空",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                return@withContext null
            }

            // 检查直播状态
            if (data.liveStatus != 1) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        BVApp.context,
                        "主播未开播",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                return@withContext null
            }

            // 构建画质描述映射表
            val qnDescMap = mutableMapOf<Int, String>()
            data.playUrlInfo?.playurl?.gQnDesc?.forEach { desc ->
                qnDescMap[desc.qn] = desc.desc
            }

            // 解析播放URL和画质信息
            val result = parsePlayUrl(response, preferredCodec, preferredLineIndex)
            if (result == null) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        BVApp.context,
                        "无法获取播放地址",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                return@withContext null
            }

            logger.info { "Successfully fetched live stream URL for room $roomId: ${result.url}" }
            logger.info { "Current qn: ${result.currentQn}, accept_qn: ${result.acceptQn}" }
            LivePlayInfo(
                roomId = roomId,
                streamUrl = result.url,
                isLive = true,
                currentQn = result.currentQn,
                acceptQn = result.acceptQn,
                qnDescMap = qnDescMap,
                availableLines = result.availableLines,
                currentLineIndex = result.currentLineIndex,
                liveTime = data.liveTime ?: 0L,
                expiresAt = result.expiresAt
            )
        } catch (e: Exception) {
            logger.error(e) { "Failed to fetch live stream URL for room $roomId" }
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    BVApp.context,
                    "获取直播流失败: ${e.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }
            null
        }
    }

    private data class ParseResult(
        val url: String,
        val currentQn: Int,
        val acceptQn: List<Int>,
        val availableLines: List<LiveStreamLine>,
        val currentLineIndex: Int,
        val expiresAt: Long = 0
    )

    /**
     * 从URL的extra参数中解析expires时间戳
     * @param extra URL查询参数字符串，如 "?expires=1773483819&..."
     * @return 过期时间的毫秒时间戳，解析失败返回0
     */
    fun parseExpiresFromExtra(extra: String): Long {
        return runCatching {
            val regex = Regex("""[?&]expires=(\d+)""")
            val match = regex.find(extra)
            match?.groupValues?.get(1)?.toLong()?.times(1000) ?: 0L
        }.getOrElse { 0L }
    }

    /**
     * 解析播放URL
     * @param response 直播房间播放信息响应
     * @param preferredCodec 首选编码格式
     */
    private fun parsePlayUrl(
        response: LiveRoomPlayInfoResponse,
        preferredCodec: AppLiveCodec,
        preferredLineIndex: Int?
    ): ParseResult? {
        val streams = response.data?.playUrlInfo?.playurl?.stream ?: return null

        when (preferredCodec) {
            AppLiveCodec.HLS -> {
                // HLS 自动选择最佳编码（HEVC > AV1 > AVC）
                val hlsStream = streams.find { it.protocolName == "http_hls" }
                if (hlsStream != null) {
                    val result = buildUrlFromStream(hlsStream, null, preferredLineIndex)
                    if (result != null) {
                        logger.info { "Using HLS stream with auto codec" }
                        return result
                    }
                }
                // 回退到 FLV
                val flvStream = streams.find { it.protocolName == "http_stream" }
                if (flvStream != null) {
                    val result = buildUrlFromStream(flvStream, "avc", preferredLineIndex)
                    if (result != null) {
                        logger.info { "Using FLV stream (fallback)" }
                        return result
                    }
                }
            }
            AppLiveCodec.FLV -> {
                // 强制使用 FLV（仅支持 AVC）
                val flvStream = streams.find { it.protocolName == "http_stream" }
                if (flvStream != null) {
                    val result = buildUrlFromStream(flvStream, "avc", preferredLineIndex)
                    if (result != null) {
                        logger.info { "Using FLV stream" }
                        return result
                    }
                }
                // 回退到 HLS
                val hlsStream = streams.find { it.protocolName == "http_hls" }
                if (hlsStream != null) {
                    val result = buildUrlFromStream(hlsStream, "avc", preferredLineIndex)
                    if (result != null) {
                        logger.info { "Using HLS stream with AVC (fallback)" }
                        return result
                    }
                }
            }
            AppLiveCodec.AVC -> {
                // HLS 强制 AVC
                val hlsStream = streams.find { it.protocolName == "http_hls" }
                if (hlsStream != null) {
                    val result = buildUrlFromStream(hlsStream, "avc", preferredLineIndex)
                    if (result != null) {
                        logger.info { "Using HLS stream with AVC" }
                        return result
                    }
                }
                // 回退到 FLV
                val flvStream = streams.find { it.protocolName == "http_stream" }
                if (flvStream != null) {
                    val result = buildUrlFromStream(flvStream, "avc", preferredLineIndex)
                    if (result != null) {
                        logger.info { "Using FLV stream (fallback)" }
                        return result
                    }
                }
            }
        }

        // 使用第一个可用的流作为兜底
        for (stream in streams) {
            val result = buildUrlFromStream(stream, null, preferredLineIndex)
            if (result != null) {
                logger.info { "Using fallback stream: ${stream.protocolName}" }
                return result
            }
        }

        return null
    }

    /**
     * 从编解码器列表中按优先级选择最佳编解码器 (HEVC > AV1 > AVC)
     */
    private fun selectBestCodec(codecs: List<ApiLiveCodec>): ApiLiveCodec? {
        for (preferredCodec in codecPriority) {
            val codec = codecs.find { it.codecName == preferredCodec }
            if (codec != null && codec.urlInfo.isNotEmpty()) {
                logger.debug { "Selected codec: ${codec.codecName}" }
                return codec
            }
        }
        // 如果没有匹配的，取第一个有效的
        return codecs.firstOrNull { it.urlInfo.isNotEmpty() }
    }

    /**
     * 从流信息构建URL
     * @param stream 直播流信息
     * @param preferredCodecName 首选编码名称，null 表示按优先级自动选择
     */
    private fun buildUrlFromStream(stream: LiveStream, preferredCodecName: String?, preferredLineIndex: Int?): ParseResult? {
        // 优先选择 fmp4，其次 ts，最后 flv
        val formatOrder = listOf("fmp4", "ts", "flv")

        for (formatName in formatOrder) {
            val format = stream.format.find { it.formatName == formatName }
            if (format != null && format.codec.isNotEmpty()) {
                val codec = if (preferredCodecName != null) {
                    // 指定编码时，查找指定编码
                    format.codec.find { it.codecName == preferredCodecName && it.urlInfo.isNotEmpty() }
                } else {
                    // 自动选择最佳编码
                    selectBestCodec(format.codec)
                } ?: continue
                val urlInfo = selectUrlInfo(codec.urlInfo, preferredLineIndex) ?: continue
                val lineIndex = resolveLineIndex(codec, urlInfo, preferredLineIndex)
                val fullUrl = "${urlInfo.host}${codec.baseUrl}${urlInfo.extra}"
                val expiresAt = parseExpiresFromExtra(urlInfo.extra)
                logger.debug { "Built URL with format $formatName, codec ${codec.codecName}: $fullUrl" }
                return ParseResult(
                    url = fullUrl,
                    currentQn = codec.currentQn,
                    acceptQn = codec.acceptQn,
                    availableLines = buildLineOptions(codec),
                    currentLineIndex = lineIndex,
                    expiresAt = expiresAt
                )
            }
        }

        // 如果没有找到特定格式，使用第一个可用的
        for (format in stream.format) {
            if (format.codec.isNotEmpty()) {
                val codec = if (preferredCodecName != null) {
                    format.codec.find { it.codecName == preferredCodecName && it.urlInfo.isNotEmpty() }
                } else {
                    selectBestCodec(format.codec)
                } ?: continue
                val urlInfo = selectUrlInfo(codec.urlInfo, preferredLineIndex) ?: continue
                val lineIndex = resolveLineIndex(codec, urlInfo, preferredLineIndex)
                val fullUrl = "${urlInfo.host}${codec.baseUrl}${urlInfo.extra}"
                val expiresAt = parseExpiresFromExtra(urlInfo.extra)
                logger.debug { "Built URL with fallback format ${format.formatName}, codec ${codec.codecName}: $fullUrl" }
                return ParseResult(
                    url = fullUrl,
                    currentQn = codec.currentQn,
                    acceptQn = codec.acceptQn,
                    availableLines = buildLineOptions(codec),
                    currentLineIndex = lineIndex,
                    expiresAt = expiresAt
                )
            }
        }

        return null
    }

    private fun selectUrlInfo(urlInfos: List<LiveUrlInfo>, preferredLineIndex: Int?): LiveUrlInfo? {
        if (urlInfos.isEmpty()) return null
        return preferredLineIndex
            ?.coerceIn(0, urlInfos.lastIndex)
            ?.let { urlInfos[it] }
            ?: selectBestUrlInfo(urlInfos)
    }

    /**
     * 计算实际生效的线路序号。
     *
     * 用户指定线路时直接沿用请求的序号：`url_info` 若出现重复项，`indexOf` 只会返回第一个，
     * 界面上的线路标记就一直停在“线路 1”，看起来像切换线路没生效。
     */
    private fun resolveLineIndex(
        codec: ApiLiveCodec,
        urlInfo: LiveUrlInfo,
        preferredLineIndex: Int?
    ): Int = preferredLineIndex
        ?.coerceIn(0, codec.urlInfo.lastIndex)
        ?: codec.urlInfo.indexOf(urlInfo).coerceAtLeast(0)

    private fun selectBestUrlInfo(urlInfos: List<LiveUrlInfo>): LiveUrlInfo? =
        urlInfos.maxWithOrNull(
            compareBy<LiveUrlInfo> { parseExtraLong(it.extra, "score") ?: Long.MIN_VALUE }
                .thenBy { parseExtraLong(it.extra, "total") ?: Long.MIN_VALUE }
        )

    private fun parseExtraLong(extra: String, key: String): Long? = Regex("""[?&]$key=(\d+)""")
        .find(extra)?.groupValues?.getOrNull(1)?.toLongOrNull()

    private fun buildLineOptions(codec: ApiLiveCodec): List<LiveStreamLine> =
        codec.urlInfo.mapIndexed { index, urlInfo ->
            LiveStreamLine(
                index = index,
                displayName = "线路 ${index + 1}",
                host = urlInfo.host.trimEnd('/')
            )
        }
}

/**
 * 直播播放信息
 * @param expiresAt URL过期时间戳（毫秒），0表示未知
 */
data class LivePlayInfo(
    val roomId: Int,
    val streamUrl: String,
    val isLive: Boolean = true,
    val currentQn: Int = 0,
    val acceptQn: List<Int> = emptyList(),
    val qnDescMap: Map<Int, String> = emptyMap(),
    val availableLines: List<LiveStreamLine> = emptyList(),
    val currentLineIndex: Int = 0,
    val liveTime: Long = 0L,
    val expiresAt: Long = 0
)

