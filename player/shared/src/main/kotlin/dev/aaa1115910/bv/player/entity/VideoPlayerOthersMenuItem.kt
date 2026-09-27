package dev.aaa1115910.bv.player.entity

import android.content.Context
import dev.aaa1115910.bv.player.shared.R

enum class VideoPlayerOthersMenuItem(private val strRes: Int) {
    PlayMode(R.string.video_player_menu_others_play_mode),
    AudioBalance(R.string.video_player_menu_others_audio_balance),
    DebugInfo(R.string.video_player_menu_others_debug_info);

    fun getDisplayName(context: Context) = context.getString(strRes)
}