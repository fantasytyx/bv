package dev.aaa1115910.bv.player.cdn

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 「频繁缓冲换地址」的判定直接决定会不会误切地址，所以逐条钉住边界：
 * 起播缓冲、快进/快退那一次都不计，次数按窗口统计，超时只在被计入的缓冲上生效。
 */
class FrequentBufferingDetectorTest {

    private fun detector() = FrequentBufferingDetector(
        maxBufferingCount = 3,
        countWindowMs = 90_000,
        singleBufferingTimeoutMs = 12_000,
        seekGraceMs = 3_000,
    )

    /** 走完起播：缓冲一次、进 READY，之后才进入正常计数。 */
    private fun FrequentBufferingDetector.arm() {
        assertEquals(BufferingAction.IGNORED, onBufferingStart(0))
        onBufferingEnd()
    }

    @Test
    fun `buffering before the first ready is ignored`() {
        val detector = detector()

        assertEquals(BufferingAction.IGNORED, detector.onBufferingStart(0))
        assertFalse(detector.isTimedOut(60_000), "起播缓冲不算，也不应该超时")
    }

    @Test
    fun `three buffering starts within the window trigger a switch`() {
        val detector = detector()
        detector.arm()

        assertEquals(BufferingAction.COUNTED, detector.onBufferingStart(1_000))
        detector.onBufferingEnd()
        assertEquals(BufferingAction.COUNTED, detector.onBufferingStart(2_000))
        detector.onBufferingEnd()
        assertEquals(BufferingAction.SWITCH, detector.onBufferingStart(3_000))
    }

    @Test
    fun `buffering starts spread wider than the window do not trigger a switch`() {
        val detector = detector()
        detector.arm()

        // 第 1 次在第 3 次的前 90s 之外，统计窗口里只剩 2 次
        assertEquals(BufferingAction.COUNTED, detector.onBufferingStart(0))
        detector.onBufferingEnd()
        assertEquals(BufferingAction.COUNTED, detector.onBufferingStart(10_000))
        detector.onBufferingEnd()
        assertEquals(BufferingAction.COUNTED, detector.onBufferingStart(91_000))
    }

    @Test
    fun `a single buffering that outlasts the timeout is detected`() {
        val detector = detector()
        detector.arm()

        detector.onBufferingStart(1_000)
        assertFalse(detector.isTimedOut(12_999), "还没到 12s")
        assertTrue(detector.isTimedOut(13_000), "满 12s")
    }

    @Test
    fun `an ignored buffering never times out`() {
        val detector = detector()
        detector.arm()

        detector.onSeek(1_000)
        assertEquals(BufferingAction.IGNORED, detector.onBufferingStart(2_000))
        assertFalse(detector.isTimedOut(120_000), "快进/快退引起的那次缓冲不参与超时")
    }

    @Test
    fun `buffering shortly after a seek is ignored but later ones count`() {
        val detector = detector()
        detector.arm()

        detector.onSeek(10_000)
        assertEquals(BufferingAction.IGNORED, detector.onBufferingStart(12_000))
        detector.onBufferingEnd()
        assertEquals(BufferingAction.COUNTED, detector.onBufferingStart(14_000))
    }

    @Test
    fun `buffering ends reset the timeout`() {
        val detector = detector()
        detector.arm()

        detector.onBufferingStart(1_000)
        detector.onBufferingEnd()
        assertFalse(detector.isTimedOut(60_000))
    }

    @Test
    fun `reset puts the detector back to its startup state`() {
        val detector = detector()
        detector.arm()
        detector.onBufferingStart(1_000)
        detector.onBufferingEnd()
        detector.onBufferingStart(2_000)
        detector.onBufferingEnd()
        assertEquals(BufferingAction.SWITCH, detector.onBufferingStart(3_000))

        detector.reset()

        // 重载后的那次缓冲相当于新的起播缓冲
        assertEquals(BufferingAction.IGNORED, detector.onBufferingStart(4_000))
        assertFalse(detector.isTimedOut(120_000))
    }
}
