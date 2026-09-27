package dev.aaa1115910.bv.viewmodel.login

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.aaa1115910.biliapi.entity.ApiType
import dev.aaa1115910.biliapi.entity.login.QrLoginState
import dev.aaa1115910.biliapi.repositories.LoginRepository
import dev.aaa1115910.bv.BVApp
import dev.aaa1115910.bv.entity.AuthData
import dev.aaa1115910.bv.repository.UserRepository
import dev.aaa1115910.bv.util.BlacklistUtil
import dev.aaa1115910.bv.util.Prefs
import dev.aaa1115910.bv.util.fError
import dev.aaa1115910.bv.util.fInfo
import dev.aaa1115910.bv.util.toast
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.annotation.KoinViewModel

@KoinViewModel
class AppQrLoginViewModel(
    private val userRepository: UserRepository,
    private val loginRepository: LoginRepository
) : ViewModel() {
    var state by mutableStateOf(QrLoginState.Ready)
    private val logger = KotlinLogging.logger { }
    var loginUrl by mutableStateOf("")

    private var qrRequestJob: Job? = null
    private var pollingJob: Job? = null
    private var requestGeneration = 0L

    fun requestQRCode(
        preferApiType: ApiType = ApiType.App,
        webQrSource: String? = null,
        webQrGoUrl: String? = null
    ) {
        val generation = ++requestGeneration
        qrRequestJob?.cancel()
        pollingJob?.cancel()
        state = QrLoginState.Ready
        loginUrl = ""
        logger.fInfo { "Request login qr code with apiType=$preferApiType" }
        qrRequestJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                withContext(Dispatchers.Main) { state = QrLoginState.RequestingQRCode }
                val qrLoginData = when (preferApiType) {
                    ApiType.Web -> loginRepository.requestWebQrLogin(
                        source = webQrSource,
                        goUrl = webQrGoUrl
                    )
                    ApiType.App -> loginRepository.requestAppQrLogin()
                }
                withContext(Dispatchers.Main) {
                    if (generation != requestGeneration) return@withContext
                    loginUrl = qrLoginData.url
                    state = QrLoginState.WaitingForScan
                }
                if (generation != requestGeneration) return@launch
                logger.fInfo { "Get login request code url with apiType=$preferApiType" }
                logger.info { qrLoginData.url }
                pollingJob = viewModelScope.launch(Dispatchers.IO) {
                    while (isActive && generation == requestGeneration) {
                        delay(1000)
                        if (checkLoginResult(
                                generation = generation,
                                loginKey = qrLoginData.key,
                                apiType = preferApiType
                            )
                        ) {
                            break
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    if (generation == requestGeneration) {
                        e.message?.toast(BVApp.context)
                        state = QrLoginState.Error
                    }
                }
                logger.fError { "Get login request code url failed: ${e.stackTraceToString()}" }
            }
        }
    }

    fun cancelCheckLoginResultTimer() {
        requestGeneration += 1
        qrRequestJob?.cancel()
        qrRequestJob = null
        pollingJob?.cancel()
        pollingJob = null
    }

    /**
     * @return true when polling reached a terminal state and should stop.
     */
    private suspend fun checkLoginResult(
        generation: Long,
        loginKey: String,
        apiType: ApiType
    ): Boolean {
        logger.fInfo { "Check for login result with apiType=$apiType" }
        return try {
            val qrLoginResult = when (apiType) {
                ApiType.Web -> loginRepository.checkWebQrLoginState(loginKey)
                ApiType.App -> loginRepository.checkAppQrLoginState(loginKey)
            }
            if (generation != requestGeneration) return true
            when (qrLoginResult.state) {
                QrLoginState.WaitingForScan -> {
                    withContext(Dispatchers.Main) { state = qrLoginResult.state }
                    logger.fInfo { "Waiting to scan" }
                    false
                }

                QrLoginState.WaitingForConfirm -> {
                    withContext(Dispatchers.Main) { state = qrLoginResult.state }
                    logger.fInfo { "Waiting to confirm" }
                    false
                }

                QrLoginState.Expired -> {
                    withContext(Dispatchers.Main) { state = qrLoginResult.state }
                    logger.fInfo { "QR expired" }
                    true
                }

                QrLoginState.Success -> {
                    logger.fInfo { "Login success" }
                    runCatching {
                        Prefs.buvid3 = loginRepository.getbuvid3()
                    }.onFailure {
                        logger.warn { "Get buvid3 failed: ${it.stackTraceToString()}" }
                    }

                    val authData = AuthData(
                        uid = qrLoginResult.cookies!!.dedeUserId,
                        uidCkMd5 = qrLoginResult.cookies!!.dedeUserIdCkMd5,
                        sid = qrLoginResult.cookies!!.sid,
                        biliJct = qrLoginResult.cookies!!.biliJct,
                        sessData = qrLoginResult.cookies!!.sessData,
                        tokenExpiredData = qrLoginResult.cookies!!.expiredDate.time,
                        accessToken = qrLoginResult.accessToken.orEmpty(),
                        refreshToken = qrLoginResult.refreshToken.orEmpty()
                    )

                    BlacklistUtil.checkUid(authData.uid)
                    val identity = userRepository.validateAuthData(authData)
                    userRepository.addUser(authData, identity)
                    withContext(Dispatchers.Main) {
                        if (generation == requestGeneration) {
                            state = QrLoginState.Success
                        }
                    }
                    true
                }

                else -> {
                    withContext(Dispatchers.Main) { state = qrLoginResult.state }
                    logger.fInfo { "This state should not be here: ${qrLoginResult.state}" }
                    true
                }
            }
        } catch (e: CancellationException) {
            logger.fInfo { "QR polling job cancelled" }
            throw e
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                if (generation == requestGeneration) {
                    e.message?.toast(BVApp.context)
                    state = QrLoginState.Error
                }
            }
            logger.fError { "Check qr state failed: ${e.stackTraceToString()}" }
            true
        }
    }
}
