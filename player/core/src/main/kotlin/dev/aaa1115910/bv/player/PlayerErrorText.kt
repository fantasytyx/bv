package dev.aaa1115910.bv.player

/**
 * 把播放器底层异常翻译成用户能看懂的话。
 *
 * 视频 CDN 走的是 TLS，证书校验失败时最外层报错只有
 * `SSLHandshakeException: Chain validation failed` 这种看不出所以然的英文，
 * 而真正的原因（证书生效时间 vs 设备时间、证书链用了 SHA-1）都写在更深层的 cause 里。
 */
object PlayerErrorText {

    private val notYetValidRegex =
        Regex("Certificate not valid until (.+?) \\(compared to (.+?)\\)")
    private val expiredRegex =
        Regex("Certificate expired at (.+?) \\(compared to (.+?)\\)")

    /**
     * 给播放器错误状态用的包装：能翻译就换成可读异常，否则原样返回 [Throwable.cause]。
     * 保持与 `error.cause as Exception?` 一致的行为（cause 为空时不显示错误 UI）。
     */
    fun wrap(error: Throwable): Exception? =
        describe(error)?.let { Exception(it, error.cause) } ?: (error.cause as? Exception)

    /** 返回可读的提示；认不出来时返回 null。 */
    private fun describe(error: Throwable): String? {
        val chain = generateSequence(error) { it.cause }.take(10).toList()
        val text = chain.joinToString("\n") { "${it.javaClass.simpleName}: ${it.message}" }

        // 证书链里有 SHA-1 签名的证书，Conscrypt 的 ChainStrengthAnalyzer 会直接拒绝，
        // 这种节点即使设备时间正确也连不上，只能换节点。
        if (text.contains("insecure hash function", ignoreCase = true)) {
            return "该视频节点的 HTTPS 证书使用了不安全的 SHA-1 签名，Android 无法校验。\n" +
                    "请尝试「刷新视频」换一个节点，或在设置里改用官方 CDN。"
        }

        // 证书时间与设备时间对不上 —— 电视盒子不联网校时，差出几个月很常见
        notYetValidRegex.find(text)?.let { match ->
            return "设备系统时间不正确：证书要到 ${match.groupValues[1]} 才生效，" +
                    "而设备当前时间是 ${match.groupValues[2]}。\n请在系统设置里校正时间后重试。"
        }
        expiredRegex.find(text)?.let { match ->
            return "设备系统时间不正确：证书已于 ${match.groupValues[1]} 过期，" +
                    "而设备当前时间是 ${match.groupValues[2]}。\n请在系统设置里校正时间后重试。"
        }

        if (text.contains("Chain validation failed") || text.contains("Unacceptable certificate")) {
            val detail = chain.last().let { "${it.javaClass.simpleName}: ${it.message}" }
            return "视频节点的 HTTPS 证书校验失败（常见原因：设备系统时间不正确）。\n" +
                    "可以尝试「刷新视频」换个节点。\n具体原因：$detail"
        }

        return null
    }
}
