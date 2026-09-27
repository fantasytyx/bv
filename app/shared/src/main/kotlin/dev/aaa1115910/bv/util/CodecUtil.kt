package dev.aaa1115910.bv.util

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import android.util.Range
import androidx.annotation.RequiresApi
import androidx.core.util.toRange
import io.github.oshai.kotlinlogging.KotlinLogging

object CodecUtil {
    private val logger = KotlinLogging.logger("CodecUtil")

    /**
     * 真机上编解码器可能有数百个，每个都要跨进程查询 capabilities，耗时可达数秒，
     * 必须在非主线程调用
     */
    fun parseCodecs(): List<CodecInfoData> {
        val codecInfos = runCatching { MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos }
            .onFailure { logger.warn(it) { "get codec list failed" } }
            .getOrNull()
            ?: return emptyList()

        val codecs = codecInfos.mapNotNull { codecInfo ->
            runCatching { CodecInfoData.fromCodecInfo(codecInfo) }
                .onFailure { logger.warn(it) { "parse codec failed: ${codecInfo.name}" } }
                .getOrNull()
        }
        logger.info { "parsed ${codecs.size}/${codecInfos.size} codecs" }
        return codecs
    }
}

data class CodecInfoData(
    val name: String,
    val mimeType: String,
    val type: CodecType,
    val mode: CodecMode,
    val media: CodecMedia,
    //val codecProvider: CodecProvider,
    val maxSupportedInstances: Int?,
    val colorFormats: List<Int>,
    val audioBitrateRange: IntRange?,
    val videoBitrateRange: IntRange?,
    val videoFrame: IntRange?,
    private val codecInfo: MediaCodecInfo? = null,
    private val capabilitiesType: String? = null
) {
    /** 帧率要按分辨率逐个查询 capabilities，真机上开销明显，改为选中该解码器后再计算 */
    val supportedFrameRates: List<SupportedFrameRate> by lazy(LazyThreadSafetyMode.NONE) {
        runCatching { codecInfo?.getSupportedFrameRates(capabilitiesType) }
            .getOrNull() ?: emptyList()
    }

    val achievableFrameRates: List<SupportedFrameRate> by lazy(LazyThreadSafetyMode.NONE) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                codecInfo?.getAchievableFrameRates(capabilitiesType)
            } else emptyList()
        }.getOrNull() ?: emptyList()
    }

    companion object {
        fun fromCodecInfo(codecInfo: MediaCodecInfo): CodecInfoData? {
            val supportedType = codecInfo.supportedTypes.firstOrNull() ?: return null
            val capabilities = runCatching { codecInfo.getCapabilitiesForType(supportedType) }.getOrNull()
                ?: return null
            return CodecInfoData(
                name = codecInfo.name,
                mimeType = capabilities.mimeType,
                type = CodecType.fromMediaCodecInfo(codecInfo),
                mode = CodecMode.fromMediaCodecInfo(codecInfo),
                media = CodecMedia.fromMediaCodecInfo(codecInfo),
                maxSupportedInstances = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                    capabilities.maxSupportedInstances else null,
                colorFormats = capabilities.colorFormats.toList(),
                audioBitrateRange = capabilities.audioCapabilities?.bitrateRange?.let { it.lower..it.upper },
                videoBitrateRange = capabilities.videoCapabilities?.bitrateRange?.let { it.lower..it.upper },
                videoFrame = capabilities.videoCapabilities?.supportedFrameRates?.let { it.lower..it.upper },
                codecInfo = codecInfo,
                capabilitiesType = supportedType
            )
        }
    }
}

enum class CodecType {
    Encoder,
    Decoder;

    companion object {
        fun fromMediaCodecInfo(mediaCodecInfo: MediaCodecInfo): CodecType {
            return if (mediaCodecInfo.isEncoder) Encoder else Decoder
        }

    }
}

enum class CodecMedia {
    Audio,
    Video;

    companion object {
        fun fromMediaCodecInfo(mediaCodecInfo: MediaCodecInfo): CodecMedia {
            return if (mediaCodecInfo.isAudioCodec()) Audio else Video
        }
    }
}

enum class CodecMode {
    Hardware,
    Software;

    companion object {
        fun fromMediaCodecInfo(mediaCodecInfo: MediaCodecInfo): CodecMode {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                return if (mediaCodecInfo.isSoftwareOnly) Software else Hardware
            }

            if (mediaCodecInfo.isAudioCodec()) return Software

            val name = mediaCodecInfo.name
            if (name.contains("omx.brcm.video", true)
                && name.contains("hw", true)
            ) return Hardware
            if (name.startsWith("omx.marvell.video.hw", true)) return Hardware
            if (name.startsWith("omx.intel.hw_vd", true)) return Hardware
            if (name.startsWith("omx.qcom", true) && name.endsWith("hw")) return Hardware
            if (name.startsWith("c2.vda.arc", true)
                || name.startsWith("arc.")
            ) return Hardware

            return if (
                name.startsWith("omx.google.", true)
                || name.contains("ffmpeg", true)
                || (name.startsWith("omx.sec.", true) && name.contains(".sw.", true))
                || name.equals("omx.qcom.video.decoder.hevcswvdec", true)
                || name.startsWith("c2.android.", true)
                || name.startsWith("c2.google.", true)
                || name.startsWith("omx.sprd.soft.", true)
                || name.startsWith("omx.avcodec.", true)
                || name.startsWith("omx.pv", true)
                || name.endsWith("sw", true)
                || name.endsWith("sw.dec", true)
                || name.endsWith("sw_vd", true)
                || (!name.startsWith("omx.", true) && !name.startsWith("c2.", true))
            ) Software else Hardware
        }
    }
}

private fun MediaCodecInfo.isAudioCodec(): Boolean {
    return supportedTypes.joinToString().contains("audio")
}

private val resolutions = mapOf(
    480 to 360,
    720 to 480,
    1280 to 720,
    1920 to 1080,
    2560 to 1440,
    3840 to 2160,
    7680 to 4320
)

data class SupportedFrameRate(
    val resolution: Pair<Int, Int>,
    val frameRate: Range<Double>,
    val unsupported: Boolean
)

private fun MediaCodecInfo.getSupportedFrameRates(supportedType: String?): List<SupportedFrameRate> {
    if (supportedType == null) return emptyList()
    return resolutions.map { (width, height) ->
        val frameRates = runCatching {
            getCapabilitiesForType(supportedType).videoCapabilities?.getSupportedFrameRatesFor(width, height)
        }.getOrNull()
        SupportedFrameRate(
            resolution = width to height,
            frameRate = frameRates ?: ((0.0..0.0).toRange()),
            unsupported = frameRates == null
        )
    }
}

@RequiresApi(Build.VERSION_CODES.M)
private fun MediaCodecInfo.getAchievableFrameRates(supportedType: String?): List<SupportedFrameRate> {
    if (supportedType == null) return emptyList()
    return resolutions.map { (width, height) ->
        val frameRates = runCatching {
            getCapabilitiesForType(supportedType).videoCapabilities?.getAchievableFrameRatesFor(width, height)
        }.getOrNull()
        SupportedFrameRate(
            resolution = width to height,
            frameRate = frameRates ?: ((0.0..0.0).toRange()),
            unsupported = frameRates == null
        )
    }
}