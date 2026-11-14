package dev.aaa1115910.bv.tv.util

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * Provides a [BringIntoViewSpec] that calculates the scroll offset for a child item in a LazyList
 * with intelligent positioning logic.
 *
 * The positioning logic:
 * 1. If the focused element is fully visible, don't scroll
 * 2. If the focused element is in the upper, align its top edge with container top
 * 3. If the focused element is in the lower, align its bottom edge with container bottom
 *
 * @param padding 焦点项停靠时与容器边缘保留的间距（不是 LazyList 的 contentPadding）。单位是dp
 *     注意：延迟列表默认只组合可见项。焦点移出视口时，默认焦点搜索会走 beyond-bounds 只补组合
 *     视口外的少量 item；在网格里向下补到的是下一行第一个、向上补到的是上一行最后一个，
 *     所以按 DOWN/UP 会跳到行首/行尾（跳列）。留出间距让下一行/上一行露出一条，整行进入
 *     正常组合后，默认的几何焦点搜索才能选到同列。
 * @param topPadding 容器上边距。默认与 [padding] 相同
 * @param bottomPadding 容器下边距。默认与 [padding] 相同
 * @param content 包含在 LazyList 中的内容
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ProvideListBringIntoViewSpec(
    padding: Dp = 22.dp,
    topPadding: Dp = padding,
    bottomPadding: Dp = padding,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val topPaddingPx = remember(topPadding, density) { with(density) { topPadding.toPx() } }
    val bottomPaddingPx = remember(bottomPadding, density) { with(density) { bottomPadding.toPx() } }
    val bringIntoViewSpec = remember(topPaddingPx, bottomPaddingPx) {
        object : BringIntoViewSpec {
            // 焦点滚动必须瞬时完成：spring 动画期间列表还没把下一行组合出来，此时快速连按
            // DOWN 会让焦点搜索走 beyond-bounds 兜底、落到下一行第一列（跳列 bug）。
            // 瞬时滚动保证焦点移动后下一帧列表就已就位。
            @Suppress("OVERRIDE_DEPRECATION")
            override val scrollAnimationSpec: AnimationSpec<Float> = snap()

            override fun calculateScrollDistance(
                offset: Float,
                size: Float,
                containerSize: Float
            ): Float = calculateScrollDistanceWithPadding(
                offset = offset,
                size = size,
                containerSize = containerSize,
                topPadding = topPaddingPx,
                bottomPadding = bottomPaddingPx,
            )
        }
    }
    CompositionLocalProvider(
        LocalBringIntoViewSpec provides bringIntoViewSpec,
        content = content,
    )
}

private fun calculateScrollDistanceWithPadding(
    offset: Float,
    size: Float,
    containerSize: Float,
    topPadding: Float, // 容器上边距
    bottomPadding: Float, // 容器下边距
): Float {
    val trailingEdge = offset + size + bottomPadding
    val leadingEdge = offset - topPadding
    return when {

        // 如果组件已经完整显示，不滚动
        leadingEdge >= 0 && trailingEdge <= containerSize -> 0f

        // 如果组件可见但比容器大，不滚动
        leadingEdge < 0 && trailingEdge > containerSize -> 0f

        // 找出使其中一条边与容器的边重合所需的最小滚动量
        abs(leadingEdge) < abs(trailingEdge - containerSize) -> leadingEdge
        else -> trailingEdge - containerSize
    }
}
