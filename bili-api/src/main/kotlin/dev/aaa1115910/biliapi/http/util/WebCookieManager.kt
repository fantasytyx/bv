package dev.aaa1115910.biliapi.http.util

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.compression.ContentEncoding
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.content.TextContent
import io.ktor.http.setCookie
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.SecureRandom
import java.security.spec.MGF1ParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec

/**
 * Web Cookie 维护器（移植自 blbl 的 WebCookieMaintainer）：
 * - 从首页 Set-Cookie 获取 b_nut，从 finger/spi 获取 buvid3/buvid4
 * - 通过 GenWebTicket 获取 bili_ticket
 * - 每天一次 ExClimbWuzhi 风控激活
 * - 基于 refresh_token 的 Web Cookie 每日续期
 *
 * 本类不依赖应用层存储，cookie 的读写通过 [cookieGetter]/[onCookiesUpdated] 由应用层桥接。
 */
object WebCookieManager {
    private const val TAG = "WebCookieManager"

    private const val BILI_TICKET_KEY_ID = "ec02"
    private const val BILI_TICKET_HMAC_KEY = "XgwSnGZ1p"

    private const val REFRESH_SOURCE = "main_web"

    private val refreshCsrfRegex = Regex("<div\\s+id=\"1-name\">\\s*([0-9a-fA-F]{16,})\\s*</div>")

    // From bilibili-api-docs cookie_refresh.md
    private val correspondPublicKey by lazy {
        val derBase64 =
            "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDLgd2OAkcGVtoE3ThUREbio0Eg" +
                "Uc/prcajMKXvkCKFCWhJYJcLkcM2DKKcSeFpD/j6Boy538YXnR6VhcuUJOhH2x71" +
                "nzPjfdTcqMz7djHum0qSZA0AyCBDABUqCrfNgCiJ00Ra7GmRj+YCK1NJEuewlb40" +
                "JNrRuoEUXpabUzGB8QIDAQAB"
        val keyBytes = Base64.getDecoder().decode(derBase64)
        val spec = X509EncodedKeySpec(keyBytes)
        KeyFactory.getInstance("RSA").generatePublic(spec)
    }

    // ---- 由应用层注入 ----
    /** 读取当前登录凭证：sessData、bili_jct、DedeUserID、refresh_token */
    var sessDataProvider: () -> String = { "" }
    var biliJctProvider: () -> String = { "" }
    var refreshTokenProvider: () -> String = { "" }
    var midProvider: () -> Long = { 0 }

    /** 读取指定 cookie 值（如 buvid3、b_nut），由应用层提供 Prefs 存储 */
    var cookieGetter: (name: String) -> String? = { null }
    /** 落盘回调，应用层负责写 Prefs */
    var onCookiesUpdated: (Map<String, String>) -> Unit = {}

    // ---- 已经检查过的日期标记（进程内，重启后最多重试一次） ----
    private var biliTicketCheckedEpochDay = 0L
    private var buvidActiveEpochDay = 0L
    private var cookieRefreshCheckedEpochDay = 0L

    // ---- 单飞锁 ----
    private val ticketMutex = Mutex()
    private val fingerprintMutex = Mutex()
    private val cookieRefreshMutex = Mutex()

    private val json = Json {
        coerceInputValues = true
        ignoreUnknownKeys = true
    }

    private val client by lazy {
        HttpClient(OkHttp) {
            install(ContentNegotiation) {
                json(this@WebCookieManager.json)
            }
            install(ContentEncoding) {
                deflate(1.0F)
                gzip(0.9F)
            }
        }
    }

    /** 汇总所有 web cookie，供 API 层附加到请求头 */
    fun cookieHeader(): String? {
        val parts = buildList {
            listOf(
                "buvid3",
                "buvid4",
                "b_nut",
                "bili_ticket",
                "bili_ticket_expires",
            ).forEach { name ->
                cookieGetter(name)?.takeIf { it.isNotBlank() }?.let { add("$name=$it") }
            }
        }
        return if (parts.isEmpty()) null else parts.joinToString("; ") + ";"
    }

    /**
     * 确保 buvid3/buvid4/b_nut 等指纹 cookie 就绪：
     * 先访问首页拿 b_nut（顺带 buvid3），再走 finger/spi 拿 b_3/b_4。
     */
    suspend fun ensureWebFingerprintCookies() = fingerprintMutex.withLock {
        val hasBuvid3 = !cookieGetter("buvid3").isNullOrBlank()
        val hasBNut = !cookieGetter("b_nut").isNullOrBlank()
        val needHomepage = !hasBuvid3 || !hasBNut
        val hasBuvid4 = !cookieGetter("buvid4").isNullOrBlank()
        val needSpi = !hasBuvid4
        if (!needHomepage && !needSpi) return

        if (needHomepage) {
            runCatching {
                val setCookies = client.get("https://www.bilibili.com/").setCookie()
                val updated = mutableMapOf<String, String>()
                setCookies.filter { it.name in setOf("buvid3", "b_nut") }
                    .forEach { cookie ->
                        cookie.value.takeIf { it.isNotBlank() }?.let { updated[cookie.name] = it }
                    }
                if (updated.isNotEmpty()) {
                    onCookiesUpdated(updated)
                }
            }.onFailure {
                logWarn("homepage failed", it)
            }
        }

        if (needSpi) {
            runCatching {
                val raw = client.get("https://api.bilibili.com/x/frontend/finger/spi")
                    .bodyAsText()
                val data = json.parseToJsonElement(raw).jsonObject
                    .get("data")?.jsonObject ?: buildJsonObject { }
                val b3 = data["b_3"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val b4 = data["b_4"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val updated = mutableMapOf<String, String>()
                if (b3.isNotBlank() && cookieGetter("buvid3").isNullOrBlank()) {
                    updated["buvid3"] = b3
                }
                if (b4.isNotBlank()) {
                    updated["buvid4"] = b4
                }
                if (updated.isNotEmpty()) {
                    onCookiesUpdated(updated)
                }
            }.onFailure {
                logWarn("finger/spi failed", it)
            }
        }
    }

    /**
     * 获取/续期 bili_ticket。有效期内（剩余 > 6h）或当天已尝试过则跳过。
     */
    suspend fun ensureBiliTicket() {
        val nowMs = System.currentTimeMillis()
        val epochDay = nowMs / 86_400_000L
        if (biliTicketCheckedEpochDay == epochDay) return
        val existing = cookieGetter("bili_ticket")
        val existingExpires = cookieGetter("bili_ticket_expires")?.toLongOrNull() ?: 0L
        if (!existing.isNullOrBlank() && existingExpires * 1000L - nowMs > 6 * 60 * 60 * 1000L) {
            return
        }
        biliTicketCheckedEpochDay = epochDay

        ticketMutex.withLock {
            runCatching {
                val ts = (nowMs / 1000).toString()
                val hexsign = hmacSha256Hex(key = BILI_TICKET_HMAC_KEY, message = "ts$ts")
                val csrf = biliJctProvider().takeIf { it.isNotBlank() }
                val raw = client.post("https://api.bilibili.com/bapis/bilibili.api.ticket.v1.Ticket/GenWebTicket") {
                    header(HttpHeaders.ContentType, "application/x-www-form-urlencoded; charset=utf-8")
                    parameter("key_id", BILI_TICKET_KEY_ID)
                    parameter("hexsign", hexsign)
                    parameter("context[ts]", ts)
                    csrf?.let { parameter("csrf", it) }
                }.bodyAsText()
                val data = json.parseToJsonElement(raw).jsonObject
                    .get("data")?.jsonObject ?: return@runCatching
                val ticket = data["ticket"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val createdAt = data["created_at"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
                val ttl = data["ttl"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
                if (ticket.isBlank() || createdAt <= 0L || ttl <= 0L) return@runCatching
                onCookiesUpdated(
                    mapOf(
                        "bili_ticket" to ticket,
                        "bili_ticket_expires" to (createdAt + ttl).toString(),
                    )
                )
            }.onFailure {
                logWarn("ensureBiliTicket failed", it)
            }
        }
    }

    /**
     * 每天一次调用 ExClimbWuzhi 激活 buvid，缓解风控。
     */
    suspend fun ensureBuvidActiveOncePerDay() {
        val mid = midProvider()
        if (mid <= 0) return
        val epochDay = System.currentTimeMillis() / 86_400_000L
        if (buvidActiveEpochDay == epochDay) return

        runCatching {
            val rand = ByteArray(32 + 8 + 4)
            SecureRandom().nextBytes(rand)
            // mimic PiliPlus' fixed PNG tail marker
            val iend = byteArrayOf(0, 0, 0, 0, 73, 69, 78, 68)
            iend.copyInto(rand, 32)
            val tail = ByteArray(4)
            SecureRandom().nextBytes(tail)
            tail.copyInto(rand, 40)

            val randPngEnd = Base64.getEncoder().encodeToString(rand)
            val jsonData =
                buildJsonObject {
                    put("3064", 1)
                    put("39c8", "333.1387.fp.risk")
                    put(
                        "3c43",
                        buildJsonObject {
                            put("adca", "Linux")
                            put("bfe9", randPngEnd.takeLast(50))
                        }
                    )
                }

            val cookie = buildList {
                listOf("SESSDATA", "bili_jct", "DedeUserID", "DedeUserID__ckMd5", "sid", "buvid3")
                    .forEach { name ->
                        val v = cookieGetter(name)?.takeIf { it.isNotBlank() } ?: return@forEach
                        add("$name=$v")
                    }
            }.joinToString("; ")

            val body = buildJsonObject {
                put("payload", jsonData)
            }.toString()

            client.post("https://api.bilibili.com/x/internal/gaia-gateway/ExClimbWuzhi") {
                header(HttpHeaders.ContentType, "application/json")
                header("env", "prod")
                header("app-key", "android64")
                header("x-bili-aurora-zone", "sh001")
                header("x-bili-mid", mid.toString())
                genAuroraEid(mid)?.let { header("x-bili-aurora-eid", it) }
                header("Referer", "https://www.bilibili.com")
                if (cookie.isNotBlank()) header(HttpHeaders.Cookie, cookie)
                setBody(TextContent(body, io.ktor.http.ContentType.Application.Json))
            }
            buvidActiveEpochDay = epochDay
        }.onFailure {
            logWarn("buvidActive failed mid=$mid", it)
        }
    }

    private fun genAuroraEid(mid: Long): String? {
        if (mid <= 0) return null
        val key = "ad1va46a7lza".toByteArray()
        val input = mid.toString().toByteArray()
        val out = ByteArray(input.size)
        for (i in input.indices) out[i] = (input[i].toInt() xor key[i % key.size].toInt()).toByte()
        return Base64.getEncoder().withoutPadding().encodeToString(out)
    }

    /**
     * 基于 refresh_token 的 Web Cookie 每日续期。成功后把新 cookie 写回应用层。
     */
    suspend fun refreshCookieIfNeededOncePerDay() {
        if (sessDataProvider().isBlank() || biliJctProvider().isBlank()) return
        val refreshToken = refreshTokenProvider().takeIf { it.isNotBlank() } ?: return
        val epochDay = System.currentTimeMillis() / 86_400_000L
        if (cookieRefreshCheckedEpochDay == epochDay) return

        cookieRefreshMutex.withLock {
            if (cookieRefreshCheckedEpochDay == epochDay) return
            runCatching {
                val info = client.get("https://passport.bilibili.com/x/passport-login/web/cookie/info") {
                    url {
                        parameters.append("csrf", biliJctProvider())
                    }
                }.bodyAsText()
                val infoJson = json.parseToJsonElement(info).jsonObject
                val infoData = infoJson["data"]?.jsonObject ?: return@runCatching
                val shouldRefresh = infoData["refresh"]?.jsonPrimitive?.contentOrNull == "true" ||
                    infoData["refresh"]?.jsonPrimitive?.booleanOrNull == true
                if (!shouldRefresh) return@runCatching

                val timestamp = infoData["timestamp"]?.jsonPrimitive?.contentOrNull
                    ?.toLongOrNull() ?: System.currentTimeMillis()
                val correspondPath = getCorrespondPath(timestamp)
                val html = client.get("https://www.bilibili.com/correspond/1/$correspondPath")
                    .bodyAsText()
                val refreshCsrf = refreshCsrfRegex.find(html)?.groupValues?.getOrNull(1).orEmpty()
                if (refreshCsrf.isBlank()) error("refresh_csrf not found")

                val refreshUrl = "https://passport.bilibili.com/x/passport-login/web/cookie/refresh"
                val raw = client.get(refreshUrl) {
                    parameter("csrf", biliJctProvider())
                    parameter("refresh_csrf", refreshCsrf)
                    parameter("source", REFRESH_SOURCE)
                    parameter("refresh_token", refreshToken)
                }.bodyAsText()
                val root = json.parseToJsonElement(raw).jsonObject
                val data = root["data"]?.jsonObject
                if (root["code"]?.jsonPrimitive?.contentOrNull != "0" || data == null) {
                    error("cookie refresh failed: ${root["message"]?.jsonPrimitive?.contentOrNull}")
                }
                val refreshed = mutableMapOf<String, String>()
                data["cookies"]?.jsonArray?.forEach { element ->
                    val cookie = element.jsonObject
                    val name = cookie["name"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                    val value = cookie["value"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                    refreshed[name] = value
                }
                data["refresh_token"]?.jsonPrimitive?.contentOrNull
                    ?.takeIf { it.isNotBlank() }?.let { refreshed["refresh_token"] = it }
                if (refreshed.isNotEmpty()) {
                    onCookiesUpdated(refreshed)
                }

                // 确认续期
                val newBiliJct = refreshed["bili_jct"]?.takeIf { it.isNotBlank() }
                    ?: biliJctProvider()
                runCatching {
                    client.get("https://passport.bilibili.com/x/passport-login/web/confirm/refresh") {
                        parameter("csrf", newBiliJct)
                        parameter("refresh_token", refreshToken)
                    }
                }
                cookieRefreshCheckedEpochDay = epochDay
            }.onFailure {
                logWarn("cookie refresh failed", it)
            }
        }
    }

    /**
     * correspond 路径：RSA OAEP-SHA256 加密 "refresh_${timestamp}" 后的十六进制串。
     */
    private fun getCorrespondPath(timestampMs: Long): String {
        val plaintext = "refresh_$timestampMs"
        val cipher =
            runCatching { Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding") }
                .getOrElse { Cipher.getInstance("RSA/ECB/OAEPPadding") }
        cipher.init(
            Cipher.ENCRYPT_MODE,
            correspondPublicKey,
            OAEPParameterSpec(
                "SHA-256",
                "MGF1",
                MGF1ParameterSpec.SHA256,
                PSource.PSpecified.DEFAULT
            )
        )
        return cipher.doFinal(plaintext.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun hmacSha256Hex(key: String, message: String): String {
        val secretKey = SecretKeySpec(key.toByteArray(), "HmacSHA256")
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(secretKey)
        return mac.doFinal(message.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun logWarn(message: String, throwable: Throwable) {
        io.github.oshai.kotlinlogging.KotlinLogging.logger(TAG)
            .warn(throwable) { message }
    }
}