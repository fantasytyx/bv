package dev.aaa1115910.bv.tv.util

/**
 * 计算返回合集列表后应该聚焦的卡片在合并 grid 里的下标。
 *
 * 合集与系列共用同一个 [androidx.compose.foundation.lazy.grid.LazyVerticalGrid]，
 * 系列卡的下标需要加上合集数量。找不到时返回 -1。
 */
fun archiveFocusIndex(
    key: String?,
    seasonIds: List<Long>,
    seriesIds: List<Long>
): Int {
    if (key == null) return -1
    return when {
        key.startsWith(prefix = "season-") -> {
            val id = key.removePrefix(prefix = "season-").toLongOrNull() ?: return -1
            seasonIds.indexOf(id)
        }

        key.startsWith(prefix = "series-") -> {
            val id = key.removePrefix(prefix = "series-").toLongOrNull() ?: return -1
            val index = seriesIds.indexOf(id)
            if (index < 0) -1 else seasonIds.size + index
        }

        else -> -1
    }
}
