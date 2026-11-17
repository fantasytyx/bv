package dev.aaa1115910.bv.tv.util

import android.view.KeyEvent
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import dev.aaa1115910.bv.entity.carddata.VideoCardData
import dev.aaa1115910.bv.util.isDpadLeft
import dev.aaa1115910.bv.util.isKeyDown

fun VideoCardData.stableItemKey(): Any {
    return when {
        seasonId != null -> "season-$seasonId-${epId ?: 0}-$upId"
        avid > 0 -> "av-$avid-$upId"
        else -> "$title|$upId"
    }
}

internal fun gridFocusItemCount(
    contentItemCount: Int,
    footerFocusable: Boolean,
    columnCount: Int,
    leadingItemCount: Int = 0
) = contentItemCount + leadingItemCount + if (footerFocusable) columnCount else 0

internal fun shouldBlockDownFocusExit(
    currentIndex: Int,
    itemCount: Int,
    columnCount: Int
): Boolean {
    val hasNextRow = itemCount > 0 && currentIndex >= 0 &&
        currentIndex + columnCount < itemCount
    return !hasNextRow
}

/** 数据还在加载、末尾行下面暂无内容时消费 DOWN，避免焦点被甩到别的列或左侧导航栏 */
fun Modifier.blockDownFocusExitAtGridEnd(
    currentIndexProvider: () -> Int,
    itemCount: Int,
    columnCount: Int
): Modifier {
    return onPreviewKeyEvent { event ->
        val nativeEvent = event.nativeKeyEvent
        if (nativeEvent.keyCode != KeyEvent.KEYCODE_DPAD_DOWN) return@onPreviewKeyEvent false
        if (nativeEvent.action != KeyEvent.ACTION_DOWN && nativeEvent.action != KeyEvent.ACTION_UP) {
            return@onPreviewKeyEvent false
        }

        shouldBlockDownFocusExit(
            currentIndex = currentIndexProvider(),
            itemCount = itemCount,
            columnCount = columnCount
        )
    }
}

/**
 * 在最左列按 LEFT 时，把焦点直接交给 [focusRequester]（左侧「已关注 UP」面板的「全部」）。
 *
 * 交给默认的几何焦点搜索时，面板里几何上最近的是某个 UP 行（可能在列表中途），列表为把它
 * 滚进视口会先滚一段，随后面板再把焦点重定向到「全部」，表现为进面板时列表乱滚。
 * [isAtLeftEdge] 判断当前聚焦项是否已在最左列：不在时返回 false，交回默认焦点搜索在网格内左移。
 */
fun Modifier.redirectLeftFocusAtLeftEdge(
    isAtLeftEdge: () -> Boolean,
    focusRequester: FocusRequester
): Modifier = onPreviewKeyEvent { event ->
    if (!event.isKeyDown() || !event.isDpadLeft() || !isAtLeftEdge()) {
        return@onPreviewKeyEvent false
    }
    // 目标还没组合出来时 requestFocus 会抛异常，此时不消费，交回默认焦点搜索
    runCatching { focusRequester.requestFocus() }.isSuccess
}
