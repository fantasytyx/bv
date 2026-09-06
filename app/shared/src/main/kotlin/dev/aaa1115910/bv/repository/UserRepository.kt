package dev.aaa1115910.bv.repository

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.aaa1115910.biliapi.http.BiliHttpApi
import dev.aaa1115910.biliapi.http.BiliPassportHttpApi
import dev.aaa1115910.biliapi.repositories.AuthRepository
import dev.aaa1115910.bv.BVApp
import dev.aaa1115910.bv.R
import dev.aaa1115910.bv.dao.AppDatabase
import dev.aaa1115910.bv.entity.AuthData
import dev.aaa1115910.bv.entity.db.UserDB
import dev.aaa1115910.bv.util.Prefs
import dev.aaa1115910.bv.util.fInfo
import dev.aaa1115910.bv.util.toast
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Single
import java.util.Date

data class ValidatedUserIdentity(
    val uid: Long,
    val username: String,
    val avatar: String
)

@Single
class UserRepository(
    private val authRepository: AuthRepository,
    private val db: AppDatabase = BVApp.getAppDatabase()
) {
    companion object {
        private val logger = KotlinLogging.logger { }
    }

    private val authFailureLogoutMutex = Mutex()

    var isLogin by mutableStateOf(Prefs.isLogin)
    var uid by mutableLongStateOf(Prefs.uid)
    var uidCkMd5 by mutableStateOf(Prefs.uidCkMd5)
    var sid by mutableStateOf(Prefs.sid)
    var sessData by mutableStateOf(Prefs.sessData)
    var biliJct by mutableStateOf(Prefs.biliJct)
    var expiredDate by mutableStateOf(Prefs.tokenExpiredData)

    var accessToken by mutableStateOf(Prefs.accessToken)
    var refreshToken by mutableStateOf(Prefs.refreshToken)

    var username by mutableStateOf("")
    var avatar by mutableStateOf("")

    private fun reloadFromPrefs() {
        logger.info { "Reload auth data from prefs" }

        uid = Prefs.uid
        uidCkMd5 = Prefs.uidCkMd5
        sid = Prefs.sid
        sessData = Prefs.sessData
        biliJct = Prefs.biliJct
        isLogin = Prefs.isLogin
        expiredDate = Prefs.tokenExpiredData
        accessToken = Prefs.accessToken
        refreshToken = Prefs.refreshToken
    }

    private fun saveToPrefs(authData: AuthData) {
        logger.info { "Save auth data to prefs" }

        Prefs.uid = authData.uid
        Prefs.uidCkMd5 = authData.uidCkMd5
        Prefs.sid = authData.sid
        Prefs.sessData = authData.sessData
        Prefs.biliJct = authData.biliJct
        Prefs.isLogin = true
        Prefs.tokenExpiredData = Date(authData.tokenExpiredData)
        Prefs.accessToken = authData.accessToken
        Prefs.refreshToken = authData.refreshToken

        updateAuthRepository()
    }

    private fun saveToPrefs() {
        logger.info { "Save auth data to prefs" }

        Prefs.uid = uid
        Prefs.uidCkMd5 = uidCkMd5
        Prefs.sid = sid
        Prefs.sessData = sessData
        Prefs.biliJct = biliJct
        Prefs.isLogin = isLogin
        Prefs.tokenExpiredData = expiredDate
        Prefs.accessToken = accessToken
        Prefs.refreshToken = refreshToken

        updateAuthRepository()
    }

    suspend fun logout() {
        val user = db.userDao().findUserByUid(uid)
        user?.let {
            db.userDao().delete(it)
            logger.info { "Delete user $uid in user db" }
        } ?: let {
            logger.info { "Not found user $uid in user db" }
        }
        clearAuth()
    }

    suspend fun logoutFromServer() {
        val logoutUid = uid
        BiliPassportHttpApi.logout(
            biliCSRF = biliJct,
            sessData = sessData,
            dedeUserID = uid,
            dedeUserIDCkMd5 = uidCkMd5,
            sid = sid
        ).requireSuccess()
        if (uid == logoutUid) {
            logout()
        }
    }

    suspend fun logoutOnAuthFailure(reason: String) {
        authFailureLogoutMutex.withLock {
            // 未登录状态或已完成登出时忽略，避免并发 -101 重复弹窗/清数据
            if (!isLogin && !Prefs.isLogin) return
            logger.info { "Auth failure detected, auto logout: $reason" }
            withContext(Dispatchers.Main) {
                BVApp.context.getString(R.string.exception_auth_failure)
                    .toast(BVApp.context)
            }
            logout()
        }
    }

    private fun clearAuth() {
        logger.info { "Clear auth data in UserRepository" }
        uid = 0
        uidCkMd5 = ""
        sid = ""
        sessData = ""
        biliJct = ""
        isLogin = false
        expiredDate = Date(0)
        accessToken = ""
        refreshToken = ""
        saveToPrefs()
    }

    private fun updateAuthRepository() {
        authRepository.sessionData = sessData
        authRepository.biliJct = biliJct
        authRepository.dedeUserIDCkMd5 = uidCkMd5
        authRepository.sid = sid
        authRepository.accessToken = accessToken
        authRepository.mid = uid
        authRepository.buvid3 = Prefs.buvid3
    }

    suspend fun setUser(user: UserDB) {
        saveToPrefs(AuthData.fromJson(user.auth))
        reloadFromPrefs()
        BVApp.instance?.initRepository()
        BVApp.instance?.initProxy()
        updateAvatar()
    }

    /**
     * Verifies that the cookie returned by a login flow is usable before it is persisted.
     * The account id from the authenticated endpoint must match the one from the login result.
     */
    suspend fun validateAuthData(authData: AuthData): ValidatedUserIdentity {
        require(authData.uid > 0L) { "Invalid account id returned by login" }
        require(authData.sessData.isNotBlank()) { "Login cookie is empty" }

        val profile = BiliHttpApi.getWebInterfaceNav(
            buvid3 = Prefs.buvid3,
            sessData = authData.sessData,
            dedeUserID = authData.uid,
            dedeUserIDCkMd5 = authData.uidCkMd5,
            biliJct = authData.biliJct,
            sid = authData.sid
        ).getResponseData()
        check(profile.isLogin) { "Login cookie is not authenticated" }
        check(profile.mid == authData.uid) {
            "Login cookie does not match the returned account"
        }
        return ValidatedUserIdentity(
            uid = profile.mid,
            username = profile.uname,
            avatar = profile.face
        )
    }

    suspend fun addUser(
        authData: AuthData,
        identity: ValidatedUserIdentity? = null
    ) {
        require(identity == null || identity.uid == authData.uid) {
            "Validated account does not match the login result"
        }

        val existUser = db.userDao().findUserByUid(authData.uid)
        existUser?.let {
            it.auth = authData.toJson()
            identity?.username?.takeIf(String::isNotBlank)?.let { username ->
                it.username = username
            }
            identity?.avatar?.takeIf(String::isNotBlank)?.let { avatar ->
                it.avatar = avatar
            }
            db.userDao().update(it)
        } ?: let {
            val newUser = UserDB(
                uid = authData.uid,
                username = identity?.username?.takeIf(String::isNotBlank)
                    ?: "User ${authData.uid}",
                avatar = identity?.avatar?.takeIf(String::isNotBlank)
                    ?: "https://i0.hdslb.com/bfs/article/b6b843d84b84a3ba5526b09ebf538cd4b4c8c3f3.jpg",
                auth = authData.toJson()
            )
            db.userDao().insert(newUser)
        }
        saveToPrefs(authData)
        reloadFromPrefs()
        BVApp.instance?.initRepository()
        BVApp.instance?.initProxy()
        if (identity == null) {
            updateAvatar()
        } else {
            reloadAvatar()
        }
        // 登录成功后激活 buvid 风控指纹（当天仅一次，失败静默）
        runCatching {
            dev.aaa1115910.biliapi.http.util.WebCookieManager.ensureBuvidActiveOncePerDay()
        }.onFailure {
            logger.info { "buvidActive after login failed: ${it.message}" }
        }
    }

    suspend fun updateAvatar() {
        val user = db.userDao().findUserByUid(uid)
        user?.let {
            runCatching {
                val responseData =
                    BiliHttpApi.getUserSelfInfo(sessData = Prefs.sessData).getResponseData()
                logger.fInfo { "Updating user name and avatar" }
                username = responseData.name
                avatar = responseData.face
                user.username = username
                user.avatar = avatar
                db.userDao().update(user)
            }.onFailure {
                logger.info {
                    "Update user name and avatar failed: ${it.stackTraceToString()}"
                }
            }
        }
    }

    suspend fun reloadAvatar() {
        val user = db.userDao().findUserByUid(uid)
        user?.let {
            username = it.username
            avatar = it.avatar
        }
    }

    suspend fun findUserByUid(uid: Long): UserDB? {
        return db.userDao().findUserByUid(uid)
    }

    /**
     * 应用 Web Cookie 每日续期的结果（SESSDATA/bili_jct/refresh_token 等）。
     */
    suspend fun applyWebCookieRefresh(updated: Map<String, String>) {
        updated["SESSDATA"]?.takeIf { it.isNotBlank() }?.let { sessData = it }
        updated["bili_jct"]?.takeIf { it.isNotBlank() }?.let { biliJct = it }
        updated["refresh_token"]?.takeIf { it.isNotBlank() }?.let { refreshToken = it }
        updated["DedeUserID__ckMd5"]?.takeIf { it.isNotBlank() }?.let { uidCkMd5 = it }
        updated["sid"]?.takeIf { it.isNotBlank() }?.let { sid = it }
        if (updated.keys.any { it in setOf("SESSDATA", "bili_jct", "refresh_token", "DedeUserID__ckMd5", "sid") }) {
            saveToPrefs()
        }
    }

    suspend fun updateUser(user: UserDB){
        db.userDao().update(user)
    }
}