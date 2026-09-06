package dev.aaa1115910.bv.player.entity

import android.content.Context
import dev.aaa1115910.bv.player.shared.R

enum class VideoPlayerDanmakuMenuItem(private val strRes: Int) {
    Switch(R.string.video_player_menu_danmaku_switch),
    RollingDurationFactor(R.string.video_player_menu_danmaku_rolling_duration_factor),
    Density(R.string.video_player_menu_danmaku_density),
    Size(R.string.video_player_menu_danmaku_size),
    Opacity(R.string.video_player_menu_danmaku_opacity),
    Area(R.string.video_player_menu_danmaku_area),
    Mask(R.string.video_player_menu_danmaku_mask),
    FilterLevel(R.string.video_player_menu_danmaku_filter_level);

    fun getDisplayName(context: Context, isLive: Boolean = false): String = when {
        this == FilterLevel && isLive -> context.getString(R.string.video_player_menu_danmaku_filter_user_level)
        else -> context.getString(strRes)
    }
}