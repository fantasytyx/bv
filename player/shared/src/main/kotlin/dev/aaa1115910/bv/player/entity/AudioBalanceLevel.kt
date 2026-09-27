package dev.aaa1115910.bv.player.entity

import android.content.Context
import dev.aaa1115910.bv.player.shared.R

/**
 * 音量均衡挡位。
 *
 * 只做整体增益（纯标量）与峰值软限制，不改变频谱、不做时间伸缩，
 * 因此不会影响音调与音色。
 *
 * @param targetLufs 目标综合响度（LUFS），数值越大听感越响
 * @param maxGainDb 允许的最大提升量，避免把底噪一起放大
 */
enum class AudioBalanceLevel(
    val targetLufs: Double,
    val maxGainDb: Double,
    private val strRes: Int
) {
    Off(0.0, 0.0, R.string.video_player_menu_audio_balance_off),
    Low(-22.0, 6.0, R.string.video_player_menu_audio_balance_low),
    Medium(-16.0, 10.0, R.string.video_player_menu_audio_balance_medium),
    High(-12.0, 12.0, R.string.video_player_menu_audio_balance_high);

    fun getDisplayName(context: Context) = context.getString(strRes)

    companion object {
        /** 界面展示顺序 */
        val ordered: List<AudioBalanceLevel> = entries.toList()
    }
}
