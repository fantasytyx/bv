package dev.aaa1115910.bv.tv.screens.user

/**
 * 计数文案：一万以下原样展示，一万以上压缩成「x.x万」，超过 Int 用「亿」。
 *
 * 0 返回空串，由调用方决定是否退回文案（例如统计栏在 0 时显示「转发」）。
 */
fun formatCount(count: Long): String = when {
    count <= 0 -> ""
    count < 10_000 -> count.toString()
    count < 100_000 -> {
        val tenths = (count / 1_000).toInt()
        if (tenths % 10 == 0) "${tenths / 10}万" else "${tenths / 10}.${tenths % 10}万"
    }

    count < 100_000_000 -> "${count / 10_000}万"
    else -> "${count / 100_000_000}亿"
}

fun formatCount(count: Int): String = formatCount(count.toLong())
