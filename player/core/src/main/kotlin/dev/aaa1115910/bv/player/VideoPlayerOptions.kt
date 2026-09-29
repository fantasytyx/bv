package dev.aaa1115910.bv.player

import dev.aaa1115910.bv.player.cdn.CdnSpeedRecorder
import dev.aaa1115910.bv.player.entity.AudioBalanceLevel

data class VideoPlayerOptions(
    val userAgent: String? = null,
    val referer: String? = null,
    val enableFfmpegAudioRenderer: Boolean = false,
    val enableAsyncQueueing: Boolean = true,
    val enableScreenRefreshRateMatching: Boolean = false,
    /** 跳过音频中的静音段，只在 2.5 倍速及以上生效（低倍速会压缩时间轴导致画面快进） */
    val enableSkipSilence: Boolean = false,
    /** 音量均衡挡位，播放中可通过 AbstractVideoPlayer.setAudioBalanceLevel 动态调整 */
    val audioBalanceLevel: AudioBalanceLevel = AudioBalanceLevel.Off,
    /** CDN 吞吐样本上报入口，为空时不上报 */
    val cdnSpeedRecorder: CdnSpeedRecorder? = null
)