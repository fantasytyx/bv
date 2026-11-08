package dev.aaa1115910.bv.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RiskCaptchaSessionTest {
    @Test
    fun `begin exposes trimmed voucher together with retry snapshot`() {
        val session = RiskCaptchaSession<String>()
        assertTrue(session.begin("  voucher_1  ", "retry-1"))
        assertEquals("voucher_1", session.request?.vVoucher)
        assertEquals("retry-1", session.request?.retry)
    }

    @Test
    fun `begin rejects blank voucher`() {
        val session = RiskCaptchaSession<String>()
        assertFalse(session.begin("   ", "retry-1"))
        assertNull(session.request)
    }

    @Test
    fun `begin is rejected after two verifications`() {
        val session = RiskCaptchaSession<String>(maxVerifyCount = 2)
        repeat(2) {
            assertTrue(session.begin("voucher_$it", "retry-$it"))
            assertNotNull(session.consume())
        }
        assertFalse(session.begin("voucher_3", "retry-3"))
        assertNull(session.request)
    }

    @Test
    fun `consume returns snapshot once then clears request`() {
        val session = RiskCaptchaSession<String>()
        session.begin("voucher_1", "retry-1")
        assertEquals("retry-1", session.consume())
        assertNull(session.request)
        assertNull(session.consume())
    }

    @Test
    fun `cancel clears request and does not spend budget`() {
        val session = RiskCaptchaSession<String>(maxVerifyCount = 1)
        session.begin("voucher_1", "retry-1")
        session.cancel()
        assertNull(session.request)
        assertTrue(session.begin("voucher_2", "retry-2"))
    }

    @Test
    fun `resetBudget allows verifying again after budget spent`() {
        val session = RiskCaptchaSession<String>(maxVerifyCount = 1)
        session.begin("voucher_1", "retry-1")
        session.consume()
        assertFalse(session.begin("voucher_2", "retry-2"))
        session.resetBudget()
        assertTrue(session.begin("voucher_3", "retry-3"))
    }
}
