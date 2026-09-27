package dev.aaa1115910.bv.player.danmaku

import android.graphics.Typeface

enum class DanmakuLaneDensity(val laneHeightFactor: Float) {
    Sparse(1.3f),
    Standard(1.0f),
    Dense(0.8f),
}

enum class DanmakuFontWeight(val typeface: Typeface) {
    Normal(Typeface.DEFAULT),
    Bold(Typeface.DEFAULT_BOLD),
}

data class DanmakuConfig(
    val enabled: Boolean = true,
    val opacity: Float = 1f,
    val textSizeSp: Float = 18f,
    val textSizeScale: Int = 100,
    val fontWeight: DanmakuFontWeight = DanmakuFontWeight.Bold,
    val strokeWidthPx: Int = 3,
    val durationMultiplier: Float = 1f,
    val area: Float = 1f,
    val laneDensity: DanmakuLaneDensity = DanmakuLaneDensity.Standard,
    val allowScroll: Boolean = true,
    val allowTop: Boolean = true,
    val allowBottom: Boolean = true,
    val minLevel: Int = 0,
)
