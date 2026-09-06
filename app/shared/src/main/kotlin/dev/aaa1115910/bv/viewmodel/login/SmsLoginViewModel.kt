package dev.aaa1115910.bv.viewmodel.login

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import dev.aaa1115910.biliapi.entity.login.Captcha
import dev.aaa1115910.biliapi.repositories.LoginRepository
import dev.aaa1115910.biliapi.repositories.SendSmsState
import dev.aaa1115910.bv.BVApp
import dev.aaa1115910.bv.entity.AuthData
import dev.aaa1115910.bv.repository.UserRepository
import dev.aaa1115910.bv.util.BlacklistUtil
import dev.aaa1115910.bv.util.Prefs
import dev.aaa1115910.bv.util.fDebug
import dev.aaa1115910.bv.util.toast
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.koin.android.annotation.KoinViewModel
import java.net.URI
import java.net.URLDecoder

internal fun parseSmsCaptchaUrl(recaptchaUrl: String?): Captcha? {
    if (recaptchaUrl.isNullOrBlank()) return null
    val parameters = runCatching {
        parseSmsCaptchaQuery(URI(recaptchaUrl).rawQuery.orEmpty())
    }.getOrNull() ?: return null
    val token = parameters["recaptcha_token"].orEmpty()
    val gt = parameters["gee_gt"].orEmpty()
    val challenge = parameters["gee_challenge"].orEmpty()
    if (token.isBlank() || gt.isBlank() || challenge.isBlank()) return null
    return Captcha(
        token = token,
        gt = gt,
        challenge = challenge,
    )
}

private fun parseSmsCaptchaQuery(query: String): Map<String, String> {
    if (query.isBlank()) return emptyMap()
    return query.split("&")
        .mapNotNull { parameter ->
            val index = parameter.indexOf("=")
            if (index <= 0) return@mapNotNull null
            val key = URLDecoder.decode(parameter.substring(0, index), "UTF-8")
            val value = URLDecoder.decode(parameter.substring(index + 1), "UTF-8")
            key to value
        }
        .toMap()
}

@KoinViewModel
class SmsLoginViewModel(
    private val userRepository: UserRepository,
    private val loginRepository: LoginRepository
) : ViewModel() {
    private val logger = KotlinLogging.logger { }
    var sendSmsState by mutableStateOf(SendSmsState.Ready)

    private var phone: Long = 0
    private var recaptchaToken: String? = null
    var geetestChallenge: String? = null
    var geetestValidate: String? = null
    var geetestSeccode: String? = null
    private var geetestGt: String? = null
    private val buvid = Prefs.buvid
    private var captchaKey: String? = null

    suspend fun sendSms(
        phone: Long,
        onCaptcha: (challenge: String, gt: String) -> Unit
    ) {
        this.phone = phone
        logger.info { "Send sms to $phone" }
        runCatching {
            val sendSmsResult = loginRepository.requestSms(
                phone = phone,
                buvid = buvid,
                recaptchaToken = recaptchaToken,
                geetestChallenge = geetestChallenge,
                geetestValidate = geetestValidate,
                geetestSeccode = geetestSeccode
            )
            when (sendSmsResult.state) {
                SendSmsState.Ready -> {
                    logger.info { "this state should be here: $sendSmsState" }
                    withContext(Dispatchers.Main) { sendSmsState = sendSmsResult.state }
                }

                SendSmsState.Error -> {
                    logger.warn { "Send sms failed: ${sendSmsResult.message}" }
                    withContext(Dispatchers.Main) {
                        sendSmsState = sendSmsResult.state
                        "发送短信失败：${sendSmsResult.message}".toast(BVApp.context)
                    }
                    clearCaptchaData()
                }

                SendSmsState.Success -> {
                    logger.info { "Send sms success" }
                    captchaKey = sendSmsResult.captchaKey
                    withContext(Dispatchers.Main) {
                        sendSmsState = sendSmsResult.state
                        "验证码已发送".toast(BVApp.context)
                    }
                }

                SendSmsState.RecaptchaRequire -> {
                    logger.info { "Require manual recaptcha" }
                    logger.info { "recaptcha url: ${sendSmsResult.recaptchaUrl}" }

                    if (!loadCaptchaData(sendSmsResult.recaptchaUrl)) {
                        logger.warn { "Load captcha data failed" }
                        withContext(Dispatchers.Main) {
                            sendSmsState = SendSmsState.Error
                            "获取验证码失败，请尝试其它登录方式".toast(BVApp.context)
                        }
                        clearCaptchaData()
                        return
                    }

                    logger.info { "recaptchaToken: $recaptchaToken" }
                    logger.info { "geetestGt: $geetestGt" }
                    logger.info { "geetestChallenge: $geetestChallenge" }
                    onCaptcha(geetestChallenge!!, geetestGt!!)
                    withContext(Dispatchers.Main) { sendSmsState = sendSmsResult.state }
                }
            }
        }.onFailure {
            logger.warn { "Send sms failed: ${it.stackTraceToString()}" }
            withContext(Dispatchers.Main) {
                "发送短信失败：${it.message}".toast(BVApp.context)
                clearCaptchaData()
            }
        }
    }

    private suspend fun loadCaptchaData(recaptchaUrl: String?): Boolean {
        parseSmsCaptchaUrl(recaptchaUrl)?.let { captcha ->
            applyCaptcha(captcha)
            return true
        }
        if (!recaptchaUrl.isNullOrBlank()) {
            logger.warn { "SMS recaptcha url does not contain complete Geetest parameters" }
        }

        return runCatching {
            val captcha = loginRepository.preCapture()
            applyCaptcha(captcha)
            isCaptchaDataReady()
        }.getOrElse {
            logger.warn { "Pre capture failed: ${it.stackTraceToString()}" }
            false
        }
    }

    private fun isCaptchaDataReady(): Boolean =
        recaptchaToken?.isNotBlank() == true &&
                geetestGt?.isNotBlank() == true &&
                geetestChallenge?.isNotBlank() == true

    /**
     * 本机 WebView 与手机浏览器不能重复初始化同一个 challenge。切换设备前重新请求短信接口，
     * 从同一短信风控流程取得新的 challenge，避免通用 preCapture 把滑块题替换成点击题。
     * 若本次请求直接发送短信成功，则返回 null，并将 [sendSmsState] 更新为 Success。
     */
    suspend fun refreshCaptchaChallenge(): Captcha? {
        return try {
            check(phone > 0) { "手机号无效" }
            val result = withContext(Dispatchers.IO) {
                loginRepository.requestSms(
                    phone = phone,
                    buvid = buvid,
                )
            }
            when (result.state) {
                SendSmsState.RecaptchaRequire -> {
                    val captcha = parseSmsCaptchaUrl(result.recaptchaUrl)
                        ?: error("短信接口未返回有效的极验参数")
                    applyCaptcha(captcha)
                    sendSmsState = SendSmsState.RecaptchaRequire
                    captcha
                }

                SendSmsState.Success -> {
                    captchaKey = result.captchaKey
                        ?: error("短信接口未返回 captcha_key")
                    recaptchaToken = null
                    geetestGt = null
                    geetestChallenge = null
                    geetestValidate = null
                    geetestSeccode = null
                    withContext(Dispatchers.Main) {
                        sendSmsState = SendSmsState.Success
                        "验证码已发送".toast(BVApp.context)
                    }
                    null
                }

                SendSmsState.Error -> error(
                    result.message.ifBlank { "短信接口刷新验证码失败" }
                )

                SendSmsState.Ready -> error("短信接口返回了无效状态")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn { "Refresh login captcha failed: ${e.stackTraceToString()}" }
            withContext(Dispatchers.Main) {
                "刷新验证码失败：${e.message}".toast(BVApp.context)
            }
            null
        }
    }

    fun applyGeetestResult(
        challenge: String,
        validate: String,
        seccode: String
    ) {
        geetestChallenge = challenge
        geetestValidate = validate
        geetestSeccode = seccode
        sendSmsState = SendSmsState.Ready
    }

    private fun applyCaptcha(captcha: Captcha) {
        recaptchaToken = captcha.token
        geetestGt = captcha.gt
        geetestChallenge = captcha.challenge
        geetestValidate = null
        geetestSeccode = null
    }

    suspend fun loginWithSms(code: Int, onSuccess: () -> Unit) {
        logger.info { "Login with sms code: $code" }
        runCatching {
            val loginResult = loginRepository.loginWithSms(
                phone = phone,
                buvid = buvid,
                code = code,
                captchaKey = captchaKey!!
            )
            if (loginResult.status == 0) {
                val authData = AuthData(
                    uid = loginResult.dedeUserId,
                    uidCkMd5 = loginResult.dedeUserIdCkMd5,
                    sid = loginResult.sid,
                    sessData = loginResult.sessData,
                    biliJct = loginResult.biliJct,
                    tokenExpiredData = loginResult.expiredDate.time,
                    accessToken = loginResult.accessToken,
                    refreshToken = loginResult.refreshToken
                )
                BlacklistUtil.checkUid(authData.uid)
                val identity = userRepository.validateAuthData(authData)
                userRepository.addUser(authData, identity)

                withContext(Dispatchers.Main) {
                    "登录成功".toast(BVApp.context)
                }
                logger.info { "Login with sms success" }
                logger.fDebug { "$loginResult" }
                onSuccess()
            } else {
                logger.warn { "Login with sms return a unknown response: [status=${loginResult.status}, message=${loginResult.message}]" }
                withContext(Dispatchers.Main) {
                    "未知情况：${loginResult.message}".toast(BVApp.context)
                }
            }
        }.onFailure {
            logger.warn { "Login with sms failed: ${it.stackTraceToString()}" }
            withContext(Dispatchers.Main) {
                "短信登录失败：${it.message}".toast(BVApp.context)
            }
        }
    }

    fun clearCaptchaData() {
        logger.info { "Clear captcha data" }
        recaptchaToken = null
        geetestGt = null
        geetestChallenge = null
        geetestValidate = null
        geetestSeccode = null
        captchaKey = null
        sendSmsState = SendSmsState.Ready
    }
}

@Serializable
data class GeetestResult(
    @SerialName("geetest_challenge")
    val geetestChallenge: String,
    @SerialName("geetest_validate")
    val geetestValidate: String,
    @SerialName("geetest_seccode")
    val geetestSeccode: String
)