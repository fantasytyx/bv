package dev.aaa1115910.bv.tv.screens.user

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.itemsIndexed as staggeredItemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.aaa1115910.biliapi.entity.user.DynamicItem
import dev.aaa1115910.bv.tv.util.GridItemBox
import dev.aaa1115910.bv.tv.util.GridVertical
import dev.aaa1115910.bv.tv.util.VerticalMove
import dev.aaa1115910.bv.tv.util.revealScrollStep
import dev.aaa1115910.bv.tv.util.sameColumnNeighbor
import dev.aaa1115910.bv.tv.util.verticalMove
import dev.aaa1115910.bv.tv.util.verticalScrollStep
import dev.aaa1115910.bv.tv.util.onMenuKeyDown
import dev.aaa1115910.bv.tv.util.redirectLeftFocusAtLeftEdge
import kotlinx.coroutines.launch

/**
 * 动态瀑布流：卡片高度不一致，默认焦点搜索会跳到别的列，因此 UP/DOWN 由
 * [verticalMove] 接管；返回键、`focusRestorer` 与 `BringIntoViewSpec` 仍由调用方决定。
 *
 * @param leftEdgeFocusRequester 最左列按 LEFT 时直接聚焦的目标（左侧面板的「全部」）。
 *     交给默认焦点搜索会先落到面板里几何上最近的 UP 行、把面板列表滚走一段再跳回来。
 */
@Composable
fun DynamicWaterfall(
    items: List<DynamicItem>,
    state: LazyStaggeredGridState,
    onItemClick: (DynamicItem) -> Unit,
    onLoadMore: () -> Unit,
    modifier: Modifier = Modifier,
    columns: Int = 3,
    contentPadding: PaddingValues = PaddingValues(24.dp),
    horizontalSpacing: Dp = 24.dp,
    verticalItemSpacing: Dp = 20.dp,
    loadMoreThreshold: Int = 6,
    onItemLongClick: ((DynamicItem) -> Unit)? = null,
    leftEdgeFocusRequester: FocusRequester? = null,
    footer: (@Composable () -> Unit)? = null
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val focusRequesters = remember { mutableMapOf<Int, FocusRequester>() }
    val focusRequesterFor: (Int) -> FocusRequester = { index ->
        focusRequesters.getOrPut(index) { FocusRequester() }
    }
    // 动态卡片是唯一的上下移动来源：-1 表示焦点不在卡片上（例如在底部状态行）
    var focusedIndex by remember { mutableIntStateOf(-1) }
    var viewportHeightPx by remember { mutableIntStateOf(0) }
    var pendingAnchor by remember { mutableStateOf<GridItemBox?>(null) }
    var pendingDirection by remember { mutableStateOf<GridVertical?>(null) }
    val verticalSpacingPx = with(density) { verticalItemSpacing.roundToPx() }

    fun itemBoxes(): List<GridItemBox> = state.layoutInfo.visibleItemsInfo.map {
        GridItemBox(
            index = it.index,
            left = it.offset.x,
            top = it.offset.y,
            right = it.offset.x + it.size.width,
            bottom = it.offset.y + it.size.height
        )
    }

    // 最左列：当前卡的左边缘等于所有可见卡片里最小的左边缘；焦点不在卡片上（底部状态行）时也算
    fun atLeftmostColumn(): Boolean {
        val index = focusedIndex
        if (index < 0) return true
        val cards = itemBoxes().filter { it.index < items.size }
        val current = cards.firstOrNull { it.index == index } ?: return false
        return cards.none { it.left < current.left }
    }

    // 上下移动时目标卡可能还没组合出来：先滚过「当前卡高 + 间距」，等 layout 更新后再把焦点交给同列那张卡
    LaunchedEffect(state) {
        snapshotFlow { itemBoxes() }.collect { boxes ->
            val anchor = pendingAnchor ?: return@collect
            val direction = pendingDirection ?: return@collect
            val target = sameColumnNeighbor(anchor, boxes, direction) ?: return@collect
            if (runCatching { focusRequesterFor(target).requestFocus() }.isSuccess) {
                pendingAnchor = null
                pendingDirection = null
            }
        }
    }

    val onVerticalKey: (androidx.compose.ui.input.key.KeyEvent) -> Boolean = verticalKey@{ event ->
        val nativeEvent = event.nativeKeyEvent
        val direction = when (nativeEvent.keyCode) {
            AndroidKeyEvent.KEYCODE_DPAD_DOWN -> GridVertical.Down
            AndroidKeyEvent.KEYCODE_DPAD_UP -> GridVertical.Up
            else -> null
        }
        if (nativeEvent.action != AndroidKeyEvent.ACTION_DOWN ||
            direction == null ||
            focusedIndex < 0
        ) {
            false
        } else {
            // 目标卡一直没组合出来时，残留的锚点会在下一次布局变化时劫持焦点，这里先作废
            pendingAnchor = null
            pendingDirection = null
            val boxes = itemBoxes()
            val current = boxes.firstOrNull { it.index == focusedIndex } ?: return@verticalKey false
            val canScroll = when (direction) {
                GridVertical.Down -> state.canScrollForward
                GridVertical.Up -> state.canScrollBackward
            }
            when (
                val move = verticalMove(
                    current = current,
                    items = boxes,
                    viewportHeight = viewportHeightPx,
                    direction = direction,
                    canScroll = canScroll
                )
            ) {
                is VerticalMove.Focus ->
                    runCatching { focusRequesterFor(move.index).requestFocus() }.isSuccess

                VerticalMove.ScrollToReveal -> {
                    pendingAnchor = null
                    pendingDirection = null
                    scope.launch {
                        state.scrollBy(
                            (revealScrollStep(viewportHeightPx) * direction.step).toFloat()
                        )
                    }
                    true
                }

                VerticalMove.ScrollToNeighbour -> {
                    // 滚过「卡高 + 间距」，让同列下一张卡落到当前卡的位置，再由 layout 变化复焦
                    val step = verticalScrollStep(current, verticalSpacingPx)
                    pendingAnchor = current.copy(
                        top = current.top - step * direction.step,
                        bottom = current.bottom - step * direction.step
                    )
                    pendingDirection = direction
                    scope.launch { state.scrollBy((step * direction.step).toFloat()) }
                    true
                }

                VerticalMove.Delegate -> false
            }
        }
    }

    LazyVerticalStaggeredGrid(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { viewportHeightPx = it.height }
            .then(
                if (onItemLongClick != null) {
                    Modifier.onMenuKeyDown {
                        val index = focusedIndex
                        if (index >= 0) items.getOrNull(index)?.let(onItemLongClick)
                    }
                } else {
                    Modifier
                }
            )
            .onPreviewKeyEvent(onVerticalKey)
            .then(
                if (leftEdgeFocusRequester != null) {
                    Modifier.redirectLeftFocusAtLeftEdge(
                        isAtLeftEdge = { atLeftmostColumn() },
                        focusRequester = leftEdgeFocusRequester
                    )
                } else {
                    Modifier
                }
            ),
        columns = StaggeredGridCells.Fixed(columns),
        state = state,
        contentPadding = contentPadding,
        verticalItemSpacing = verticalItemSpacing,
        horizontalArrangement = Arrangement.spacedBy(horizontalSpacing)
    ) {
        staggeredItemsIndexed(
            items = items,
            key = { index, dynamic -> dynamic.id ?: "dynamic-$index" }
        ) { index, dynamic ->
            SpaceDynamicCard(
                dynamic = dynamic,
                modifier = Modifier.focusRequester(focusRequesterFor(index)),
                onClick = { onItemClick(dynamic) },
                onFocus = {
                    focusedIndex = index
                    if (index + loadMoreThreshold > items.size) onLoadMore()
                },
                onLongClick = { onItemLongClick?.invoke(dynamic) }
            )
        }

        if (footer != null) {
            item(span = StaggeredGridItemSpan.FullLine) {
                // 焦点落在底部状态上时不再按卡片处理，避免上下键被卡片逻辑劫持
                Box(
                    modifier = Modifier.onFocusChanged {
                        if (it.hasFocus) focusedIndex = -1
                    }
                ) {
                    footer()
                }
            }
        }
    }
}
