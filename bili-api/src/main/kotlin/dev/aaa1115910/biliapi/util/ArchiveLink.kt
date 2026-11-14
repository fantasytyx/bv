package dev.aaa1115910.biliapi.util

private val AvLinkRegex = Regex("""(?:^|/)video/av(\d+)""")
private val BvLinkRegex = Regex("""(?:^|/)video/(BV[0-9A-Za-z]+)""")

/**
 * 从 `/video/av123`、`/video/BV1xx` 这类链接里取出稿件 id。
 *
 * 动态的 `additional.ugc` 与图文富文本里的视频卡都只给 `jump_url`，取不到 id 时返回 null。
 */
fun extractArchiveId(url: String): Long? {
    AvLinkRegex.find(url)?.groupValues?.get(1)?.toLongOrNull()?.let { return it.takeIf { id -> id > 0 } }
    val bvid = BvLinkRegex.find(url)?.groupValues?.get(1) ?: return null
    return runCatching { bvid.toAv() }.getOrNull()?.takeIf { it > 0 }
}
