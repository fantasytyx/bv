package dev.aaa1115910.biliapi.http

import dev.aaa1115910.biliapi.BiliApiConstants
import dev.aaa1115910.biliapi.http.entity.BiliResponse
import dev.aaa1115910.biliapi.http.entity.BiliResponseWithoutData
import dev.aaa1115910.biliapi.http.entity.login.CaptchaData
import dev.aaa1115910.biliapi.http.entity.login.LoginWebKeyData
import dev.aaa1115910.biliapi.http.entity.login.PreCaptureData
import dev.aaa1115910.biliapi.http.entity.login.qr.AppQRDataRequest
import dev.aaa1115910.biliapi.http.entity.login.qr.AppQRLoginData
import dev.aaa1115910.biliapi.http.entity.login.qr.RequestWebQRData
import dev.aaa1115910.biliapi.http.entity.login.qr.WebQRLoginData
import dev.aaa1115910.biliapi.http.entity.login.sms.SendSmsResponse
import dev.aaa1115910.biliapi.http.entity.login.sms.SmsLoginResponse
import dev.aaa1115910.biliapi.http.plugins.BiliUserAgent
import dev.aaa1115910.biliapi.http.util.BiliLoginConf
import dev.aaa1115910.biliapi.http.util.encApiSign
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.compression.ContentEncoding
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Cookie
import io.ktor.http.HttpHeaders
import io.ktor.http.Parameters
import io.ktor.http.URLProtocol
import io.ktor.http.setCookie
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import java.net.URLEncoder
import java.security.MessageDigest

object BiliPassportHttpApi {
    private lateinit var client: HttpClient
    private val json = Json {
        coerceInputValues = true
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    init {
        createClient()
    }

    private fun createClient() {
        client = HttpClient(OkHttp) {
            install(BiliUserAgent) {
                version = BiliLoginConf.APP_VERSION_NAME
                buildCode = BiliLoginConf.APP_BUILD_CODE
                channel = BiliLoginConf.CHANNEL
                platform = BiliLoginConf.PLATFORM
                mobiApp = BiliLoginConf.MOBI_APP
                model = BiliLoginConf.MODEL
                osVersion = BiliLoginConf.OS_VERSION
                network = BiliLoginConf.NETWORK
            }
            install(ContentNegotiation) {
                json(Json {
                    coerceInputValues = true
                    ignoreUnknownKeys = true
                    prettyPrint = true
                })
            }
            install(ContentEncoding) {
                deflate(1.0F)
                gzip(0.9F)
            }
            defaultRequest {
                url {
                    host = "passport.bilibili.com"
                    protocol = URLProtocol.HTTPS
                }
                header("env", "prod")
                header("app-key", BiliLoginConf.MOBI_APP)
                header("x-bili-trace-id", BiliLoginConf.TRACE_ID)
                header("x-bili-aurora-eid", "")
                header("x-bili-aurora-zone", "")
                header("bili-http-engine", "cronet")
            }
        }.apply {
            encApiSign()
        }
    }

    /**
     * 申请二维码（Web）
     */
    suspend fun getWebQRUrl(
        source: String? = null,
        goUrl: String? = null
    ): BiliResponse<RequestWebQRData> =
        client.get("/x/passport-login/web/qrcode/generate") {
            source?.let { parameter("source", it) }
            goUrl?.let { parameter("go_url", it) }
        }.body()

    /**
     * 使用[qrcodeKey]进行二维码登录
     */
    suspend fun loginWithWebQR(qrcodeKey: String): Pair<BiliResponse<WebQRLoginData>, List<Cookie>> {
        val loginResponse = client.get("/x/passport-login/web/qrcode/poll") {
            parameter("qrcode_key", qrcodeKey)
        }
        return Pair(loginResponse.body(), loginResponse.setCookie())
    }

    /**
     * 申请二维码（App）
     */
    suspend fun getAppQRUrl(
        localId: String? = null,
        ts: Int? = null,
        mobiApp: String? = null,
        platform: String? = null
    ): BiliResponse<AppQRDataRequest> =
        client.post("/x/passport-tv-login/qrcode/auth_code") {
            header(HttpHeaders.ContentType, "application/x-www-form-urlencoded; charset=utf-8")
            localId?.let { parameter("local_id", it) }
            ts?.let { parameter("ts", "$it") }
            platform?.let { parameter("platform", it) }
            mobiApp?.let { parameter("mobi_app", it) }
            encTvLoginSign()
        }.body()


    /**
     * 使用[authCode]进行二维码登录
     */
    suspend fun loginWithAppQR(
        authCode: String,
        localId: String? = null,
        ts: Int? = null
    ): BiliResponse<AppQRLoginData> =
        client.post("/x/passport-tv-login/qrcode/poll") {
            header(HttpHeaders.ContentType, "application/x-www-form-urlencoded; charset=utf-8")
            parameter("auth_code", authCode)
            localId?.let { parameter("local_id", it) }
            ts?.let { parameter("ts", "$it") }
            encTvLoginSign()
        }.body()

    /**
     * 申请 captcha 验证码
     *
     * @param source 获取来源 已知：main_web
     */
    suspend fun getCaptcha(
        source: String? = null
    ): BiliResponse<CaptchaData> =
        client.get("/x/passport-login/captcha") {
            source?.let { parameter("source", it) }
        }.body()

    /**
     * 风控验证码备用接口
     */
    suspend fun preCapture(): BiliResponse<PreCaptureData> =
        client.post("/x/safecenter/captcha/pre").body()

    /**
     * 获取登录用 RSA 公钥
     */
    suspend fun getWebKey(): BiliResponse<LoginWebKeyData> =
        client.get("/x/passport-login/web/key").body()

    /**
     * 发送短信验证码
     *
     * @param cid 国际冠字码
     * @param tel 手机号码
     * @param loginSessionId 使用 md5(buvid + 当前毫秒时间戳) 生成
     * @param channel 固定值为 "master"
     * @param buvid 登录设备标识
     * @param statistics HD 端 statistics
     */
    suspend fun sendSms(
        cid: Long,
        tel: Long,
        loginSessionId: String,
        recaptchaToken: String? = null,
        geeChallenge: String? = null,
        geeValidate: String? = null,
        geeSeccode: String? = null,
        channel: String,
        buvid: String,
        statistics: String,
        build: Int? = null,
        cLocale: String? = null,
        disableRcmd: String? = null,
        localId: String? = null,
        mobiApp: String? = null,
        platform: String? = null,
        sLocale: String? = null,
        ts: Long
    ): BiliResponse<SendSmsResponse> = client.post("/x/passport-login/sms/send") {
        header("buvid", buvid)
        setBody(FormDataContent(
            Parameters.build {
                build?.let { append("build", "$it") }
                append("buvid", buvid)
                cLocale?.let { append("c_locale", it) }
                append("channel", channel)
                append("cid", "$cid")
                disableRcmd?.let { append("disable_rcmd", it) }
                geeChallenge?.let { append("gee_challenge", it) }
                geeSeccode?.let { append("gee_seccode", it) }
                geeValidate?.let { append("gee_validate", it) }
                localId?.let { append("local_id", it) }
                append("tel", "$tel")
                append("login_session_id", loginSessionId)
                recaptchaToken?.let { append("recaptcha_token", it) }
                mobiApp?.let { append("mobi_app", it) }
                platform?.let { append("platform", it) }
                sLocale?.let { append("s_locale", it) }
                append("statistics", statistics)
                append("ts", "$ts")
            }
        ))
    }.body()

    suspend fun loginWithSms(
        cid: Long,
        tel: Long,
        loginSessionId: String? = null,
        code: Int,
        captchaKey: String,
        build: Int? = null,
        buvid: String? = null,
        biliLocalId: String? = null,
        cLocale: String? = null,
        channel: String? = null,
        device: String? = null,
        deviceId: String? = null,
        deviceName: String? = null,
        devicePlatform: String? = null,
        disableRcmd: String? = null,
        dt: String? = null,
        fromPv: String? = null,
        fromUrl: String? = null,
        localId: String? = null,
        mobiApp: String? = null,
        platform: String? = null,
        sLocale: String? = null,
        statistics: String? = null,
        ts: Long? = null
    ): BiliResponse<SmsLoginResponse> = client.post("/x/passport-login/login/sms") {
        buvid?.let { header("buvid", it) }
        setBody(FormDataContent(
            Parameters.build {
                biliLocalId?.let { append("bili_local_id", it) }
                build?.let { append("build", "$it") }
                buvid?.let { append("buvid", it) }
                cLocale?.let { append("c_locale", it) }
                append("cid", "$cid")
                append("captcha_key", captchaKey)
                channel?.let { append("channel", it) }
                append("code", "$code")
                device?.let { append("device", it) }
                deviceId?.let { append("device_id", it) }
                deviceName?.let { append("device_name", it) }
                devicePlatform?.let { append("device_platform", it) }
                disableRcmd?.let { append("disable_rcmd", it) }
                dt?.let { append("dt", it) }
                fromPv?.let { append("from_pv", it) }
                fromUrl?.let { append("from_url", it) }
                localId?.let { append("local_id", it) }
                loginSessionId?.let { append("login_session_id", it) }
                mobiApp?.let { append("mobi_app", it) }
                platform?.let { append("platform", it) }
                sLocale?.let { append("s_locale", it) }
                statistics?.let { append("statistics", it) }
                append("tel", "$tel")
                ts?.let { append("ts", "$it") }
            }
        ))
    }.body()

    /**
     * 退出登录，和 PiliPlus 一样只提交 biliCSRF，账号凭证通过 Cookie 传递。
     */
    suspend fun logout(
        biliCSRF: String,
        sessData: String,
        dedeUserID: Long? = null,
        dedeUserIDCkMd5: String? = null,
        sid: String? = null
    ): BiliResponseWithoutData = client.post("/login/exit/v2") {
        val cookieParts = buildList {
            sessData.takeIf { it.isNotBlank() }?.let { add("SESSDATA=$it") }
            dedeUserID?.takeIf { it > 0 }?.let { add("DedeUserID=$it") }
            dedeUserIDCkMd5?.takeIf { it.isNotBlank() }?.let { add("DedeUserID__ckMd5=$it") }
            biliCSRF.takeIf { it.isNotBlank() }?.let { add("bili_jct=$it") }
            sid?.takeIf { it.isNotBlank() }?.let { add("sid=$it") }
        }
        if (cookieParts.isNotEmpty()) {
            header(HttpHeaders.Cookie, cookieParts.joinToString("; ") + ";")
        }
        setBody(FormDataContent(
            Parameters.build {
                append("biliCSRF", biliCSRF)
            }
        ))
    }.body()

    /**
     * 获取buvid3
     *
     * @param source 获取来源 已知：main_web
     */
    suspend fun getbuvid3(): String {
        val response = client.get("/x/web-frontend/getbuvid") {
            url{
                host = "api.bilibili.com"
                protocol = URLProtocol.HTTPS
            }
        }
        return runCatching {
            json.decodeFromString<BiliResponse<String>>(response.bodyAsText()).getResponseData()
        }.getOrDefault("")
    }
}

// android_hd 登录接口的 app key/secret，与 blbl AppSigner 保持一致
private const val TV_APP_KEY = "dfca71928277209b"
private const val TV_APP_SEC = "b5475a8825547a4fc26c7d518eaaa02e"

/**
 * 为 TV 登录接口（auth_code/poll）签名，规则与 blbl AppSigner.signQuery 一致：
 * 自动补 ts、appkey，参数按 key 排序、值做 RFC3986 编码后拼接，再附加 appsec 计算 MD5。
 */
private fun HttpRequestBuilder.encTvLoginSign() {
    parameter("ts", (System.currentTimeMillis() / 1000).toString())
    parameter("appkey", TV_APP_KEY)
    val sortedParams = url.encodedParameters.entries()
        .associate { it.key to it.value.first() }
        .toSortedMap()
        .also {
            url.parameters.clear()
            it.entries.forEach { (key, value) -> parameter(key, value) }
        }

    val sortedParamsString = sortedParams
        .map { (key, value) -> "${encodeComponent(key)}=${encodeComponent(value)}" }
        .joinToString("&")

    val sign = MessageDigest.getInstance("MD5")
        .digest((sortedParamsString + TV_APP_SEC).toByteArray())
        .joinToString("") { "%02x".format(it) }
    parameter("sign", sign)
}

private fun encodeComponent(value: String): String =
    URLEncoder.encode(value, "UTF-8").replace("+", "%20")