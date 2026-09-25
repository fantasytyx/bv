package dev.aaa1115910.bv.player

import dev.aaa1115910.bv.player.cdn.CdnSpeedRecorder

data class VideoPlayerOptions(
    val userAgent: String? = null,
    val referer: String? = null,
    val enableFfmpegAudioRenderer: Boolean = false,
    val enableAsyncQueueing: Boolean = true,
    val enableScreenRefreshRateMatching: Boolean = false,
    /** CDN 吞吐样本上报入口，为空时不上报 */
    val cdnSpeedRecorder: CdnSpeedRecorder? = null
)