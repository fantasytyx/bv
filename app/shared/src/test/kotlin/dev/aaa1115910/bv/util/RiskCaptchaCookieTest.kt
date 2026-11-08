package dev.aaa1115910.bv.util

import dev.aaa1115910.biliapi.http.util.WebCookieManager
import dev.aaa1115910.biliapi.repositories.AuthRepository
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RiskCaptchaCookieTest {
    @AfterTest
    fun tearDown() {
        WebCookieManager.cookieGetter = { null }
    }

    @Test
    fun `blank cookie still clears every managed name in both scopes`() {
        val commands = riskCaptchaCookieCommands("")
        assertEquals(riskCaptchaCookieNames.size * 2, commands.size)
        riskCaptchaCookieNames.forEach { name ->
            assertTrue(commands.contains("$name=; Max-Age=0; path=/"), "缺少 $name 的 host 清理指令")
            assertTrue(
                commands.contains("$name=; Max-Age=0; path=/; domain=.bilibili.com"),
                "缺少 $name 的 domain 清理指令",
            )
        }
    }

    @Test
    fun `provided cookie is cleared first then written`() {
        val commands = riskCaptchaCookieCommands("SESSDATA=abc; bili_jct=def")
        assertEquals(riskCaptchaCookieNames.size * 2 + 2, commands.size)
        assertEquals("SESSDATA=abc; path=/", commands[riskCaptchaCookieNames.size * 2])
        assertEquals("bili_jct=def; path=/", commands[riskCaptchaCookieNames.size * 2 + 1])
        assertTrue(commands.first().endsWith("Max-Age=0; path=/"))
    }

    @Test
    fun `buildRiskCaptchaCookie merges login state with web fingerprint without duplicates`() {
        WebCookieManager.cookieGetter = { name ->
            when (name) {
                "buvid4" -> "web-buvid4"
                else -> null
            }
        }
        val cookie = buildRiskCaptchaCookie(
            AuthRepository().apply {
                sessionData = "sess"
                mid = 42L
                biliJct = "csrf"
                buvid3 = "app-buvid3"
            }
        )
        assertTrue(cookie.contains("SESSDATA=sess"))
        assertTrue(cookie.contains("DedeUserID=42"))
        assertTrue(cookie.contains("bili_jct=csrf"))
        assertTrue(cookie.contains("buvid3=app-buvid3"))
        assertTrue(cookie.contains("buvid4=web-buvid4"))
        assertEquals(1, Regex("buvid3=").findAll(cookie).count())
    }
}
