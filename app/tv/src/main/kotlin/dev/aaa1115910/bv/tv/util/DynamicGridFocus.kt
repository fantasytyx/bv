package dev.aaa1115910.bv.tv.util

/**
 * 瀑布流里卡高不一致，Compose 的默认焦点搜索按几何最近邻选卡，上下移动时会跳到别的列。
 * 这里只接管 UP/DOWN：在同「列」内找上下相邻的卡，左右移动仍交给默认搜索。
 *
 * 卡片来自 `LazyStaggeredGridState.layoutInfo.visibleItemsInfo`，用可见矩形判断列，
 * 而不是 `index % 列数`——瀑布流里同一列的卡片 index 并不连续。
 */
data class GridItemBox(
    val index: Int,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

enum class GridVertical(val step: Int) {
    Up(-1),
    Down(1)
}

/** 同列内当前卡片上方/下方最近的一张卡；没有则返回 null */
fun sameColumnNeighbor(
    current: GridItemBox,
    items: List<GridItemBox>,
    direction: GridVertical
): Int? {
    val candidates = items.filter { it.index != current.index && it.overlapsColumnOf(current) }
    return when (direction) {
        // 用 >= / <=：滚过一整卡高后，同列下一张卡会正好落在锚点行，必须能被选中
        GridVertical.Down -> candidates
            .filter { it.top >= current.top }
            .minByOrNull { it.top }
            ?.index

        GridVertical.Up -> candidates
            .filter { it.top <= current.top }
            .maxByOrNull { it.top }
            ?.index
    }
}

/** 同列下一张卡还没组合出来时，向下滚过「当前卡高 + 间距」正好把它带到当前位置 */
fun verticalScrollStep(current: GridItemBox, spacing: Int): Int = current.height + spacing

/**
 * 聚焦中的卡片没有被完整显示时，方向键只滚动、不换焦点——否则用户永远看不到这张卡的下半截。
 * 判据是「按下去的方向上还有内容在视口外」，另一个方向仍交给正常的换焦点逻辑。
 *
 * @param viewportHeight 列表容器高度（px），`top` 为 0 即视口顶部
 */
fun shouldScrollToReveal(
    current: GridItemBox,
    viewportHeight: Int,
    direction: GridVertical
): Boolean {
    if (viewportHeight <= 0) return false
    return when (direction) {
        GridVertical.Down -> current.bottom > viewportHeight
        GridVertical.Up -> current.top < 0
    }
}

/**
 * 卡片被截断时每次的滚动量：半屏。
 *
 * 卡片不再按视口限制媒体高度，长图卡片可能比一屏还高，固定小步长要按很多次才看得到下半截。
 */
fun revealScrollStep(viewportHeight: Int): Int = (viewportHeight / 2).coerceAtLeast(1)

/** 上下键的处置结果 */
sealed interface VerticalMove {
    /** 换焦点到同列相邻卡 */
    data class Focus(val index: Int) : VerticalMove

    /** 卡片被截断：只滚动半屏，先把这张卡看完 */
    data object ScrollToReveal : VerticalMove

    /** 同列目标还没组合出来：滚过「卡高 + 间距」，让它落到当前卡的位置 */
    data object ScrollToNeighbour : VerticalMove

    /** 交回默认焦点搜索：没有同列目标，也滚不动了（例如首行按 UP 回到 tab 栏） */
    data object Delegate : VerticalMove
}

/**
 * 决定这次 UP/DOWN 该做什么。
 *
 * 规则按优先级：
 * 1. 聚焦卡没被完整显示 → 先滚动把它露出来；
 * 2. 同列相邻卡已可见 → 直接换焦点；
 * 3. 同列没有目标但列表还能滚 → 滚动，等 layout 更新后再换焦点；
 * 4. 都不成立 → 交回默认搜索（首行按 UP 应该回到 tab 栏，而不是被吞掉）。
 *
 * @param canScroll 该方向上列表是否还能滚动
 */
fun verticalMove(
    current: GridItemBox,
    items: List<GridItemBox>,
    viewportHeight: Int,
    direction: GridVertical,
    canScroll: Boolean
): VerticalMove {
    if (shouldScrollToReveal(current, viewportHeight, direction)) {
        return if (canScroll) VerticalMove.ScrollToReveal else VerticalMove.Delegate
    }
    val target = sameColumnNeighbor(current, items, direction)
    return when {
        target != null -> VerticalMove.Focus(target)
        canScroll -> VerticalMove.ScrollToNeighbour
        else -> VerticalMove.Delegate
    }
}

private fun GridItemBox.overlapsColumnOf(other: GridItemBox): Boolean {
    val overlap = minOf(right, other.right) - maxOf(left, other.left)
    val narrower = minOf(width, other.width)
    return narrower > 0 && overlap * 2 >= narrower
}
