package dev.aaa1115910.bv.tv.util

import android.view.KeyEvent
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.onPreviewKeyEvent
import dev.aaa1115910.bv.entity.carddata.VideoCardData

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