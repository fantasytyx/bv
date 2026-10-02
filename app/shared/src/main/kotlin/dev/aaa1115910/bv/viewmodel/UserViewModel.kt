package dev.aaa1115910.bv.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.aaa1115910.biliapi.http.BiliHttpApi
import dev.aaa1115910.biliapi.http.entity.AuthFailureException
import dev.aaa1115910.biliapi.http.entity.user.MyInfoData
import dev.aaa1115910.bv.BVApp
import dev.aaa1115910.bv.repository.UserRepository
import dev.aaa1115910.bv.util.Prefs
import dev.aaa1115910.bv.util.fInfo
import dev.aaa1115910.bv.util.toast
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.annotation.KoinViewModel

@KoinViewModel
class UserViewModel(
    private val userRepository: UserRepository
) : ViewModel() {
    private var shouldUpdateInfo = true
    private val logger = KotlinLogging.logger { }
    val isLogin get() = userRepository.isLogin
    val username get() = userRepository.username
    val face get() = userRepository.avatar

    var responseData: MyInfoData? by mutableStateOf(null)

    fun updateUserInfo(forceUpdate: Boolean = false) {
        if (!forceUpdate) {
            if (!shouldUpdateInfo || !userRepository.isLogin) return
        } else {
            if (!userRepository.isLogin) return
        }
        logger.fInfo { "Update user info" }
        viewModelScope.launch(Dispatchers.IO) {
            userRepository.reloadAvatar()

            runCatching {
                val data = BiliHttpApi.getUserSelfInfo(sessData = Prefs.sessData).getResponseData()
                withContext(Dispatchers.Main) { responseData = data }
                logger.fInfo { "Update user info success" }
                shouldUpdateInfo = false
                userRepository.username = responseData!!.name
                userRepository.avatar = responseData!!.face
            }.onFailure {
                when (it) {
                    // 会话失效的 toast 与登出由 AuthFailureDetection 全局回调统一处理
                    is AuthFailureException -> logger.fInfo { "User auth failure" }

                    else -> {
                        withContext(Dispatchers.Main) {
                            "获取用户信息失败：${it.message}".toast(BVApp.context)
                        }
                    }
                }
            }
        }
    }

    fun logout() {
        viewModelScope.launch(Dispatchers.IO) {
            userRepository.logout()
        }
    }

    fun clearUserInfo() {
        userRepository.username = ""
        userRepository.avatar = ""
    }
}