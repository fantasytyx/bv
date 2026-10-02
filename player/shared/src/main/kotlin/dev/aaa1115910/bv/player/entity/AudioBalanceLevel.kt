package dev.aaa1115910.bv.player.entity

import android.content.Context
import dev.aaa1115910.bv.player.shared.R

/**
 * 音量均衡挡位。
 *
 * 挡位本身不带目标响度：目标由服务端随播放地址下发（标准模式取 `normal_target_i`，
 * 影音模式取 `high_dynamic_target_i`），这里只决定「用哪个服务端目标」。
 * 增益是纯标量、无频谱处理，因此不影响音调与音色。
 */
enum class AudioBalanceLevel(private val strRes: Int) {
    Off(R.string.video_player_menu_audio_balance_off),
    Standard(R.string.video_player_menu_audio_balance_standard),
    Cinema(R.string.video_player_menu_audio_balance_cinema);

    fun getDisplayName(context: Context) = context.getString(strRes)

    companion object {
        /** 界面展示顺序 */
        val ordered: List<AudioBalanceLevel> = entries.toList()
    }
}
