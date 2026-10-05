package dev.aaa1115910.bv

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.webkit.WebViewCompat
import coil.Coil
import de.schnettler.datastore.manager.DataStoreManager
import dev.aaa1115910.biliapi.http.BiliHttpApi
import dev.aaa1115910.biliapi.http.BiliHttpProxyApi
import dev.aaa1115910.biliapi.http.entity.BiliAuthFailureHandler
import dev.aaa1115910.biliapi.http.util.BiliAppConf
import dev.aaa1115910.biliapi.http.util.BiliDns
import dev.aaa1115910.biliapi.http.util.BiliWebConf
import dev.aaa1115910.biliapi.http.util.WebCookieManager
import dev.aaa1115910.biliapi.repositories.AuthRepository
import dev.aaa1115910.biliapi.repositories.ChannelRepository
import dev.aaa1115910.bv.dao.AppDatabase
import dev.aaa1115910.bv.entity.AuthData
import dev.aaa1115910.bv.entity.db.UserDB
import dev.aaa1115910.bv.network.GithubApi
import dev.aaa1115910.bv.network.HttpServer
import dev.aaa1115910.bv.network.UpdateCheckResult
import dev.aaa1115910.bv.repository.UserRepository
import dev.aaa1115910.bv.util.BlacklistUtil
import dev.aaa1115910.bv.util.CoilConfig
import dev.aaa1115910.bv.util.LogCatcherUtil
import dev.aaa1115910.bv.util.Prefs
import dev.aaa1115910.bv.util.toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.KoinApplication as KoinRuntimeApplication
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Configuration
import org.koin.core.annotation.KoinApplication
import org.koin.core.annotation.Module
import org.koin.core.logger.Level
import org.koin.plugin.module.dsl.startKoin
import org.slf4j.impl.HandroidLoggerAdapter

class BVApp : Application() {
    companion object {
        @SuppressLint("StaticFieldLeak")
        lateinit var context: Context
        lateinit var dataStoreManager: DataStoreManager
        lateinit var koinApplication: KoinRuntimeApplication
        var instance: BVApp? = null

        /** 更新检查失败后的重试间隔 */
        private const val UPDATE_CHECK_RETRY_DELAY_MS = 1_500L

        /** 启动时发现新版本的提示文案 */
        private const val UPDATE_AVAILABLE_PROMPT = "发现新版本，可前往\"设置→关于\"完成更新"

        fun getAppDatabase(context: Context = this.context) = AppDatabase.getDatabase(context)
    }

    private val authFailureScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val webCookieScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val updateCheckScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 是否已有 Activity 处于 resumed；为 false 时提交的 Toast 会被系统当成后台应用的提示丢弃 */
    @Volatile
    private var appForeground = false

    /** 已确认有新版本、但还没到能展示的时机的提示文案 */
    @Volatile
    private var pendingUpdatePrompt: String? = null

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        // 尽早注册崩溃日志捕获，覆盖 ContentProvider 初始化与 onCreate 全过程中的
        // 崩溃（Release 包 R8 混淆引发的崩溃大多发生在这个阶段），此处不能依赖任何
        // 其他初始化
        LogCatcherUtil.installLogCatcher(this)
    }

    override fun onCreate() {
        super.onCreate()
        context = this.applicationContext
        HandroidLoggerAdapter.DEBUG = BuildConfig.DEBUG
        dataStoreManager = DataStoreManager(applicationContext.dataStore)
        // 先在后台把 DataStore 读进内存，下面第一次读偏好时就不会在主线程做磁盘 I/O
        Prefs.warmUp()
        koinApplication = startKoin<BVKoinApp> {
            androidLogger(if (BuildConfig.DEBUG) Level.ERROR else Level.NONE)
            androidContext(this@BVApp)
        }
        initCoil()
        // Koin/Coil 初始化期间 DataStore 已加载完，这里的读取是内存命中
        if (Prefs.blacklistUser) {
            R.string.blacklist_user_toast.toast(context)
            return
        }
        initApiConfig()
        initDns()
        initRepository()
        initProxy()
        instance = this
        updateMigration()
        HttpServer.startServer()
        updateBlacklist()
        startWebCookieMaintenance()
        observeAppForeground()
        checkUpdateAtStartup()
    }

    /**
     * 初始化 Coil 图片加载库
     * 使用优化后的 ImageLoader 配置，支持多线程并发加载
     */
    private fun initCoil() {
        Coil.setImageLoader(CoilConfig.createImageLoader(this))
    }

    private fun initApiConfig() {
        BiliAppConf.osVersion = Build.VERSION.RELEASE
        BiliAppConf.model = Build.MODEL
        // 设置 sessData 提供者，用于更新 WBI keys 时携带登录凭证
        BiliHttpApi.sessDataProvider = { Prefs.sessData }
        BiliHttpApi.buvid3Provider = { Prefs.buvid3 }
        BiliHttpApi.userAgentProvider = { Prefs.customUserAgent.takeIf { it.isNotBlank() } }
        initWebCookieManager()
        // 会话失效（-101）统一自动登出
        BiliAuthFailureHandler.onAuthFailure = { message ->
            if (Prefs.isLogin) {
                authFailureScope.launch {
                    val userRepository by koinApplication.koin.inject<UserRepository>()
                    userRepository.logoutOnAuthFailure(message)
                }
            }
        }
        BiliWebConf.webViewVersion = runCatching {
            WebViewCompat.getCurrentLoadedWebViewPackage()!!.versionName!!
                .substringBefore(".").toInt()
        }.getOrDefault(144)
        // provider 与失效回调都就绪后再预热 WBI keys，避免启动时发出匿名 nav 请求
        BiliHttpApi.preloadWbi()
    }

    private fun initWebCookieManager() {
        WebCookieManager.sessDataProvider = { Prefs.sessData }
        WebCookieManager.biliJctProvider = { Prefs.biliJct }
        WebCookieManager.refreshTokenProvider = { Prefs.refreshToken }
        WebCookieManager.midProvider = { Prefs.uid }
        WebCookieManager.uidCkMd5Provider = { Prefs.uidCkMd5 }
        WebCookieManager.sidProvider = { Prefs.sid }
        WebCookieManager.cookieGetter = { name -> Prefs.getWebCookie(name) }
        WebCookieManager.appBuvid3Provider = { Prefs.buvid3 }
        WebCookieManager.onCookiesUpdated = { updated ->
            // 账号级 cookie 只走 applyWebCookieRefresh，落进 webExtraCookies 会让登出后
            // 仍被 ApiSign 注入旧凭证
            Prefs.setWebCookies(updated.filterKeys { it !in UserRepository.AUTH_COOKIE_NAMES })
            // 请求头里用的是 Prefs.buvid3，必须与 finger/spi 成对的 buvid4 同源
            updated["buvid3"]?.takeIf { it.isNotBlank() }?.let { buvid3 ->
                Prefs.buvid3 = buvid3
                runCatching { koinApplication.koin.get<AuthRepository>().buvid3 = buvid3 }
            }
            val authUpdated = updated.filterKeys { it in UserRepository.AUTH_COOKIE_NAMES }
            if (authUpdated.isNotEmpty()) {
                webCookieScope.launch {
                    runCatching {
                        val userRepository by koinApplication.koin.inject<UserRepository>()
                        userRepository.applyWebCookieRefresh(authUpdated)
                    }
                }
            }
        }
    }

    /** 启动 Web Cookie 每日维护：指纹 cookie、bili_ticket、风控激活、cookie 续期 */
    private fun startWebCookieMaintenance() {
        webCookieScope.launch {
            runCatching { WebCookieManager.ensureWebFingerprintCookies() }
            runCatching { WebCookieManager.ensureBiliTicket() }
            runCatching { WebCookieManager.ensureBuvidActiveOncePerDay() }
            runCatching { WebCookieManager.refreshCookieIfNeededOncePerDay() }
        }
    }

    private fun initDns() {
        BiliDns.ipv4Only = Prefs.ipv4Only
    }

    fun initRepository() {
        val channelRepository by koinApplication.koin.inject<ChannelRepository>()
        channelRepository.initDefaultChannel(Prefs.accessToken, Prefs.buvid)

        val authRepository by koinApplication.koin.inject<AuthRepository>()
        authRepository.sessionData = Prefs.sessData.takeIf { it.isNotEmpty() }
        authRepository.biliJct = Prefs.biliJct.takeIf { it.isNotEmpty() }
        authRepository.dedeUserIDCkMd5 = Prefs.uidCkMd5.takeIf { it.isNotEmpty() }
        authRepository.sid = Prefs.sid.takeIf { it.isNotEmpty() }
        authRepository.accessToken = Prefs.accessToken.takeIf { it.isNotEmpty() }
        authRepository.mid = Prefs.uid.takeIf { it != 0L }
        authRepository.buvid3 = Prefs.buvid3
        authRepository.buvid = Prefs.buvid
    }

    fun initProxy() {
        if (Prefs.enableProxy) {
            BiliHttpProxyApi.createClient(Prefs.proxyHttpServer)

            val channelRepository by koinApplication.koin.inject<ChannelRepository>()
            runCatching {
                channelRepository.initProxyChannel(
                    Prefs.accessToken,
                    Prefs.buvid,
                    Prefs.proxyGRPCServer
                )
            }
        }
    }

    private fun updateMigration() {
        val lastVersionCode = Prefs.lastVersionCode
        if (lastVersionCode >= BuildConfig.VERSION_CODE) return
        Log.i("BVApp", "updateMigration from $lastVersionCode")
        if (lastVersionCode < 576) {
            // 从 Prefs 中读取登录数据写入 UserDB
            if (Prefs.isLogin) {
                runBlocking {
                    val existedUser = getAppDatabase().userDao().findUserByUid(Prefs.uid)
                    if (existedUser == null) {
                        val user = UserDB(
                            uid = Prefs.uid,
                            username = "Unknown",
                            avatar = "",
                            auth = AuthData.fromPrefs().toJson()
                        )
                        getAppDatabase().userDao().insert(user)
                    }
                }
            }
        }
        Prefs.lastVersionCode = BuildConfig.VERSION_CODE
    }

    private fun updateBlacklist() {
        CoroutineScope(Dispatchers.IO).launch {
            BlacklistUtil.updateBlacklist(context)
            BlacklistUtil.checkUid(Prefs.uid)
        }
    }

    /**
     * 记录应用是否处于前台，并在前台就绪时补发挂起的更新提示。
     *
     * Application.onCreate 早于任何 Activity 的窗口，此时提交的 Toast 会被 Android 11+
     * 当作后台应用的提示直接丢弃，因此提示必须等到有 Activity resume 之后再发。
     */
    private fun observeAppForeground() {
        registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                appForeground = true
                showPendingUpdatePromptIfForeground()
            }

            override fun onActivityPaused(activity: Activity) {
                appForeground = false
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    /**
     * 每次冷启动都静默检查更新，只要有新版本就在应用前台就绪后 Toast 提示。
     *
     * 冷启动时检查会和黑名单、Cookie 维护、WBI 预热等请求并发，偶发失败不能和"没有新版本"
     * 一样被静默掉，所以失败重试一次。
     */
    private fun checkUpdateAtStartup() {
        updateCheckScope.launch {
            runCatching { checkAndStageUpdatePrompt() }
                .onFailure {
                    if (it is CancellationException) throw it
                    Log.e("BVApp", "startup update check failed", it)
                }
        }
    }

    /** 检查更新并挂起待展示的提示；在 IO 线程执行（内部有网络请求） */
    private suspend fun checkAndStageUpdatePrompt() {
        var result = GithubApi.checkUpdate()
        if (result is UpdateCheckResult.Failed) {
            delay(UPDATE_CHECK_RETRY_DELAY_MS)
            result = GithubApi.checkUpdate()
        }
        if (result !is UpdateCheckResult.UpdateAvailable) return
        pendingUpdatePrompt = UPDATE_AVAILABLE_PROMPT
        withContext(Dispatchers.Main) { showPendingUpdatePromptIfForeground() }
    }

    /** 必须在主线程调用 */
    private fun showPendingUpdatePromptIfForeground() {
        if (!appForeground) return
        val prompt = pendingUpdatePrompt ?: return
        pendingUpdatePrompt = null
        prompt.toast(context, Toast.LENGTH_LONG)
    }
}

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "Settings")

@Module
@Configuration
@ComponentScan
class AppModule

@KoinApplication
class BVKoinApp