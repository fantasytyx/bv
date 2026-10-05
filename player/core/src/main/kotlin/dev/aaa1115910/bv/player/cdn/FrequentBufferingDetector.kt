package dev.aaa1115910.bv.player.cdn

/** 一次进入缓冲后的判定结果。 */
internal enum class BufferingAction {
    /** 起播或紧随快进/快退的那次缓冲：不计次数也不计时。 */
    IGNORED,

    /** 已计入，继续观察。 */
    COUNTED,

    /** 达到切换阈值。 */
    SWITCH,
}

/**
 * 「同一个地址频繁缓冲」的判定，用于非直播时自动换下一个候选地址。
 *
 * 两个判据任一成立即返回 [BufferingAction.SWITCH]：窗口 [countWindowMs] 内进入缓冲的次数达到
 * [maxBufferingCount]，或单次缓冲持续到 [singleBufferingTimeoutMs]。
 *
 * 起播那次缓冲（首次进入 READY 之前）与 [onSeek] 之后 [seekGraceMs] 内的那次缓冲都不计：
 * 前者必然发生，后者是用户主动造成的，都不能说明地址有问题。时间由调用方传入，便于单测。
 */
internal class FrequentBufferingDetector(
    private val maxBufferingCount: Int,
    private val countWindowMs: Long,
    private val singleBufferingTimeoutMs: Long,
    private val seekGraceMs: Long,
) {
    /** 首次进入 READY 之后才开始计数，避免把起播缓冲算进去。 */
    private var armed = false

    private var bufferingStartedAtMs: Long? = null
    private var lastSeekAtMs: Long? = null
    private val recentBufferingStarts = ArrayDeque<Long>()

    /** 进入缓冲。 */
    fun onBufferingStart(nowMs: Long): BufferingAction {
        if (!armed) return BufferingAction.IGNORED
        if (lastSeekAtMs?.let { nowMs - it <= seekGraceMs } == true) return BufferingAction.IGNORED

        bufferingStartedAtMs = nowMs
        recentBufferingStarts.addLast(nowMs)
        while (recentBufferingStarts.first() < nowMs - countWindowMs) {
            recentBufferingStarts.removeFirst()
        }
        return if (recentBufferingStarts.size >= maxBufferingCount) {
            BufferingAction.SWITCH
        } else {
            BufferingAction.COUNTED
        }
    }

    /** 缓冲结束（READY / ENDED）。 */
    fun onBufferingEnd() {
        armed = true
        bufferingStartedAtMs = null
    }

    /** 当前这次缓冲是否已持续到 [singleBufferingTimeoutMs]；被忽略的那次缓冲不会超时。 */
    fun isTimedOut(nowMs: Long): Boolean =
        bufferingStartedAtMs?.let { nowMs - it >= singleBufferingTimeoutMs } == true

    /** 记录一次快进/快退。 */
    fun onSeek(nowMs: Long) {
        lastSeekAtMs = nowMs
    }

    /** 换地址重载后重置：新一轮的起播缓冲同样不计。 */
    fun reset() {
        armed = false
        bufferingStartedAtMs = null
        lastSeekAtMs = null
        recentBufferingStarts.clear()
    }
}
