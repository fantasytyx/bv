package dev.aaa1115910.biliapi.repositories

import dev.aaa1115910.biliapi.entity.login.Captcha
import dev.aaa1115910.biliapi.entity.login.QrLoginData
import dev.aaa1115910.biliapi.entity.login.QrLoginResult
import dev.aaa1115910.biliapi.entity.login.QrLoginState
import dev.aaa1115910.biliapi.entity.login.SmsLoginResult
import dev.aaa1115910.biliapi.entity.login.WebCookies
import dev.aaa1115910.biliapi.http.BiliPassportHttpApi
import dev.aaa1115910.biliapi.http.util.BiliLoginConf
import dev.aaa1115910.biliapi.http.util.encodeLoginComponent
import dev.aaa1115910.biliapi.http.util.encryptLoginDt
import dev.aaa1115910.biliapi.http.util.generateLoginDeviceId
import dev.aaa1115910.biliapi.http.util.generateLoginSessionId
import io.ktor.util.date.toJvmDate
import org.koin.core.annotation.Single
import java.util.Date

@Single
class LoginRepository {
    private val loginDeviceId = generateLoginDeviceId()

    /**
     * 请求扫码登录的二维码，仅支持 Http 接口使用
     */
    suspend fun requestWebQrLogin(
        source: String? = null,
        goUrl: String? = null
    ): QrLoginData {
        val response = BiliPassportHttpApi.getWebQRUrl(
            source = source,
            goUrl = goUrl
        ).getResponseData()
        return QrLoginData(
            url = response.url,
            key = response.qrcodeKey
        )
    }

    /**
     * 检查扫码登录情况
     *
     * @param qrcodeKey 二维码内容
     */
    suspend fun checkWebQrLoginState(qrcodeKey: String): QrLoginResult {
        val (response, cookies) = BiliPassportHttpApi.loginWithWebQR(qrcodeKey)
        val responseData = response.getResponseData()
        var resultCookies: WebCookies? = null
        val resultState = when (responseData.code) {
            0 -> {
                val sessDataCookie = cookies.find { it.name == "SESSDATA" }
                    ?: throw IllegalArgumentException("Cookie SESSDATA not found")
                resultCookies = WebCookies(
                    dedeUserId = cookies.find { it.name == "DedeUserID" }?.value?.toLong()
                        ?: throw IllegalArgumentException("Cookie DedeUserID not found"),
                    dedeUserIdCkMd5 = cookies.find { it.name == "DedeUserID__ckMd5" }?.value
                        ?: throw IllegalArgumentException("Cookie DedeUserID__ckMd5 not found"),
                    sid = cookies.find { it.name == "sid" }?.value
                        ?: throw IllegalArgumentException("Cookie sid not found"),
                    biliJct = cookies.find { it.name == "bili_jct" }?.value
                        ?: throw IllegalArgumentException("Cookie bili_jct not found"),
                    sessData = sessDataCookie.value,
                    // Some Web login responses omit Expires. The value is metadata only;
                    // authentication validity is checked against the authenticated profile API.
                    expiredDate = sessDataCookie.expires?.toJvmDate() ?: Date(0)
                )
                QrLoginState.Success
            }

            86101 -> QrLoginState.WaitingForScan
            86090 -> QrLoginState.WaitingForConfirm
            86038 -> QrLoginState.Expired
            else -> QrLoginState.Unknown
        }
        return QrLoginResult(
            state = resultState,
            accessToken = null,
            // Web QR login does not issue an App access token, but its refresh token is
            // still required by the Web cookie refresh flow and must not be discarded.
            refreshToken = responseData.refreshToken.takeIf {
                resultState == QrLoginState.Success && it.isNotBlank()
            },
            cookies = resultCookies
        )
    }

    /**
     * 请求扫码登录的二维码，支持 Http+gRPC 接口使用
     */
    suspend fun requestAppQrLogin(): QrLoginData {
        val response = BiliPassportHttpApi.getAppQRUrl(
            localId = BiliLoginConf.LOCAL_ID,
            platform = BiliLoginConf.PLATFORM,
            mobiApp = BiliLoginConf.MOBI_APP
        ).getResponseData()
        return QrLoginData(
            url = response.url,
            key = response.authCode
        )
    }

    /**
     * 检查扫码登录情况
     *
     * @param authCode 二维码内容
     */
    suspend fun checkAppQrLoginState(authCode: String): QrLoginResult {
        val response = BiliPassportHttpApi.loginWithAppQR(
            authCode = authCode,
            localId = BiliLoginConf.LOCAL_ID
        )
        println(response)
        var resultCookies: WebCookies? = null
        val resultState = when (response.code) {
            0 -> {
                resultCookies = WebCookies(
                    dedeUserId = response.getResponseData().cookieInfo.cookies.find { it.name == "DedeUserID" }?.value?.toLong()
                        ?: throw IllegalArgumentException("Cookie DedeUserID not found"),
                    dedeUserIdCkMd5 = response.getResponseData().cookieInfo.cookies.find { it.name == "DedeUserID__ckMd5" }?.value
                        ?: throw IllegalArgumentException("Cookie DedeUserID__ckMd5 not found"),
                    sid = response.getResponseData().cookieInfo.cookies.find { it.name == "sid" }?.value
                        ?: throw IllegalArgumentException("Cookie sid not found"),
                    biliJct = response.getResponseData().cookieInfo.cookies.find { it.name == "bili_jct" }?.value
                        ?: throw IllegalArgumentException("Cookie bili_jct not found"),
                    sessData = response.getResponseData().cookieInfo.cookies.find { it.name == "SESSDATA" }?.value
                        ?: throw IllegalArgumentException("Cookie SESSDATA not found"),
                    expiredDate = Date(response.getResponseData().cookieInfo.cookies.firstOrNull()?.expires?.times(1000L)
                        ?: throw IllegalArgumentException("Cookie expires date not found"))
                )
                QrLoginState.Success
            }

            86039 -> QrLoginState.WaitingForScan
            86090 -> QrLoginState.WaitingForConfirm
            86038 -> QrLoginState.Expired
            else -> QrLoginState.Unknown
        }
        return QrLoginResult(
            state = resultState,
            accessToken = response.data?.accessToken,
            refreshToken = response.data?.refreshToken,
            cookies = resultCookies
        )
    }

    /**
     * 申请 captcha 验证码
     */
    suspend fun getCaptcha(): Captcha {
        val captchaData = BiliPassportHttpApi.getCaptcha().getResponseData()
        return Captcha(
            token = captchaData.token,
            challenge = captchaData.geetest.challenge,
            gt = captchaData.geetest.gt
        )
    }

    suspend fun preCapture(): Captcha {
        val preCaptureData = BiliPassportHttpApi.preCapture().getResponseData()
        return Captcha(
            token = preCaptureData.recaptchaToken,
            challenge = preCaptureData.geeChallenge,
            gt = preCaptureData.geeGt
        )
    }

    /**
     * 请求验证码
     */
    suspend fun requestSms(
        phone: Long,
        buvid: String,
        recaptchaToken: String? = null,
        geetestChallenge: String? = null,
        geetestValidate: String? = null,
        geetestSeccode: String? = null
    ): SendSmsResult {
        val timestampMillis = System.currentTimeMillis()
        val response = BiliPassportHttpApi.sendSms(
            cid = 86,
            tel = phone,
            loginSessionId = generateLoginSessionId(buvid, timestampMillis),
            recaptchaToken = recaptchaToken,
            geeChallenge = geetestChallenge,
            geeValidate = geetestValidate,
            geeSeccode = geetestSeccode ?: geetestValidate?.let { "$it|jordan" },
            channel = BiliLoginConf.CHANNEL,
            buvid = buvid,
            statistics = BiliLoginConf.STATISTICS,
            build = BiliLoginConf.APP_BUILD_CODE,
            cLocale = BiliLoginConf.C_LOCALE,
            disableRcmd = BiliLoginConf.DISABLE_RCMD,
            localId = buvid,
            mobiApp = BiliLoginConf.MOBI_APP,
            platform = BiliLoginConf.PLATFORM,
            sLocale = BiliLoginConf.S_LOCALE,
            ts = timestampMillis / 1000
        )
        val responseData = response.data
        return when {
            response.code == 0 && responseData?.captchaKey?.isNotBlank() == true -> {
                SendSmsResult(
                    state = SendSmsState.Success,
                    captchaKey = responseData.captchaKey
                )
            }

            responseData?.recaptchaUrl?.isNotBlank() == true -> {
                SendSmsResult(
                    state = SendSmsState.RecaptchaRequire,
                    recaptchaUrl = responseData.recaptchaUrl
                )
            }

            response.code == 0 || response.code == -105 -> {
                SendSmsResult(
                    state = SendSmsState.RecaptchaRequire,
                    recaptchaUrl = responseData?.recaptchaUrl
                )
            }

            else -> {
                SendSmsResult(
                    state = SendSmsState.Error,
                    message = response.message
                )
            }
        }
    }

    /**
     * 验证码登录
     */
    suspend fun loginWithSms(
        phone: Long,
        buvid: String,
        code: Int,
        captchaKey: String
    ): SmsLoginResult {
        val webKey = BiliPassportHttpApi.getWebKey().getResponseData().key
        val response = BiliPassportHttpApi.loginWithSms(
            cid = 86,
            tel = phone,
            code = code,
            captchaKey = captchaKey,
            build = BiliLoginConf.APP_BUILD_CODE,
            buvid = buvid,
            biliLocalId = loginDeviceId,
            cLocale = BiliLoginConf.C_LOCALE,
            channel = BiliLoginConf.CHANNEL,
            device = BiliLoginConf.DEVICE,
            deviceId = loginDeviceId,
            deviceName = BiliLoginConf.DEVICE_NAME,
            devicePlatform = BiliLoginConf.DEVICE_PLATFORM,
            disableRcmd = BiliLoginConf.DISABLE_RCMD,
            dt = encryptLoginDt(webKey),
            fromPv = BiliLoginConf.FROM_PV,
            fromUrl = encodeLoginComponent(BiliLoginConf.FROM_URL),
            localId = buvid,
            mobiApp = BiliLoginConf.MOBI_APP,
            platform = BiliLoginConf.PLATFORM,
            sLocale = BiliLoginConf.S_LOCALE,
            statistics = BiliLoginConf.STATISTICS,
            ts = System.currentTimeMillis() / 1000
        ).getResponseData()
        return SmsLoginResult.fromSmsLoginResponse(response)
    }

    suspend fun getbuvid3 (): String {
        return BiliPassportHttpApi.getbuvid3()
    }
}

data class SendSmsResult(
    val state: SendSmsState,
    val message: String = "",
    val captchaKey: String? = null,
    val recaptchaUrl: String? = null
)

enum class SendSmsState {
    Ready,
    Error,
    Success,
    RecaptchaRequire
}