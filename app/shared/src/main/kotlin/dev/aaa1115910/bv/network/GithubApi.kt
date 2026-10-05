package dev.aaa1115910.bv.network

import dev.aaa1115910.biliapi.BiliApiConstants
import dev.aaa1115910.biliapi.http.BiliHttpClient
import dev.aaa1115910.bv.BuildConfig
import dev.aaa1115910.bv.network.entity.Release
import dev.aaa1115910.bv.util.NetworkUtil
import dev.aaa1115910.bv.util.Prefs
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.content.ProgressListener
import io.ktor.client.plugins.onDownload
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.url
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.util.cio.writeChannel
import io.ktor.utils.io.copyAndClose
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

object GithubApi {
    private const val OWNER = "fantasytyx"
    private const val REPO = "bv"
    private const val PROXY_URL = "https://ghfast.top/"
    private const val API_URL = "https://api.github.com"

    /** 分页拉取 release 的最大页数，避免服务端/代理异常时无限发包 */
    private const val MAX_RELEASE_PAGES = 3
    private val client = AppHttpClient.client
    private val json = BiliHttpClient.json
    private val isDebug get() = BuildConfig.DEBUG
    private val isAlpha get() = Prefs.updateAlpha
    private val logger = KotlinLogging.logger("GithubApi")

    private suspend fun getReleases(
        owner: String = OWNER,
        repo: String = REPO,
        pageSize: Int = 30,
        page: Int = 1
    ): List<Release> {
        val response = client.get("$API_URL/repos/$owner/$repo/releases") {
            parameter("per_page", pageSize)
            parameter("page", page)
        }.bodyAsText()
        checkErrorMessage(response)
        return json.decodeFromString<List<Release>>(response)
    }

    private suspend fun getLatestRelease(
        owner: String = OWNER,
        repo: String = REPO
    ): Release {
        val response = client.get("$API_URL/repos/$owner/$repo/releases/latest").bodyAsText()
        checkErrorMessage(response)
        return json.decodeFromString<Release>(response)
    }

    suspend fun getLatestPreReleaseBuild(): Release {
        var release: Release? = null
        var page = 1
        while (release == null && page <= MAX_RELEASE_PAGES) {
            val releases = getReleases(page = page)
            if (releases.isEmpty()) break
            release = releases.firstOrNull { it.isPreRelease }
            page++
        }
        return release ?: throw IllegalStateException("No pre-release found in $MAX_RELEASE_PAGES pages")
    }

    suspend fun getLatestReleaseBuild(): Release = getLatestRelease()

    suspend fun getLatestBuild(): Release {
        val preRelease = if (isAlpha) {
            try {
                getLatestPreReleaseBuild()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // alpha 通道暂时没有 pre-release 时回退到正式 release，否则启动检查会一直失败
                logger.warn(e) { "No pre-release build available, fallback to latest release" }
                null
            }
        } else {
            null
        }
        return preRelease ?: getLatestReleaseBuild()
    }

    /**
     * 获取比当前版本新的所有 Release 列表（按发布时间降序）
     * - 如果当前版本在发布历史中，返回当前版本之后的所有更新
     * - 如果当前版本不在发布历史中，只返回最新的一个版本
     * - 如果没有更新，返回空列表
     */
    suspend fun getUpdateReleases(
        currentVersionCode: Int,
        currentVersionName: String
    ): List<Release> {
        val newerReleases = mutableListOf<Release>()
        var page = 1
        var currentVersionInHistory = false

        outer@ while (page <= MAX_RELEASE_PAGES) {
            val releases = getReleases(page = page)
            if (releases.isEmpty()) break

            for (release in releases) {
                if (isAlpha != release.isPreRelease) continue

                val asset = release.assets.firstOrNull { it.name.startsWith("BV") } ?: continue
                val assetName = asset.name
                val revision =
                    runCatching { assetName.split("_")[1].toInt() }.getOrNull() ?: continue

                if (revision < currentVersionCode || assetName.contains(currentVersionName)) {
                    currentVersionInHistory =
                        revision == currentVersionCode && assetName.contains(currentVersionName)
                    break@outer
                }
                newerReleases.add(release)
            }
            page++
        }

        // 当前版本不在发布历史中，只返回最新的一个版本
        if (!currentVersionInHistory && newerReleases.size > 1) {
            return listOf(newerReleases.first())
        }

        return newerReleases
    }

    private fun checkErrorMessage(data: String) {
        val responseElement = json.parseToJsonElement(data)
        if (responseElement !is JsonObject) return
        val responseObject = responseElement.jsonObject
        check(responseObject.size != 2 && responseObject["message"] == null) { responseObject["message"]!!.jsonPrimitive.content }
    }

    /**
     * 下载更新文件
     * 策略：大陆用户优先使用 ghfast.top 代理，非大陆用户优先使用直连
     * 代理格式：https://ghfast.top/{原下载地址}
     */
    suspend fun downloadUpdate(
        release: Release,
        file: File,
        downloadListener: ProgressListener
    ) {
        val downloadUrl =
            if (isDebug) release.assets.firstOrNull { it.name.contains("debug") }?.browserDownloadUrl
            else release.assets.firstOrNull { it.name.contains("alpha") || it.name.contains("release") }?.browserDownloadUrl
        downloadUrl ?: throw IllegalStateException("Didn't find download url")
        val isMainlandChina = NetworkUtil.isMainlandChina()

        if (isMainlandChina) {
            // 先尝试代理
            val proxyUrl = PROXY_URL + downloadUrl
            val proxyResult = runCatching {
                downloadFile(proxyUrl, file, downloadListener)
            }
            if (proxyResult.isSuccess) {
                logger.info { "Download successful via proxy" }
                return
            }
            logger.warn(proxyResult.exceptionOrNull()) { "Proxy download failed, trying direct connection" }

            // 代理失败，使用直连
            try {
                downloadFile(downloadUrl, file, downloadListener)
                logger.info { "Download successful via direct connection" }
            } catch (e: Exception) {
                logger.error(e) { "Direct download failed" }
                throw e
            }
        } else {
            // 先尝试直连
            val directResult = runCatching {
                downloadFile(downloadUrl, file, downloadListener)
            }

            if (directResult.isSuccess) {
                logger.info { "Download successful via direct connection" }
                return
            }

            logger.warn(directResult.exceptionOrNull()) { "Direct download failed, trying proxy" }

            // 直连失败，使用代理
            val proxyUrl = PROXY_URL + downloadUrl
            logger.info { "Trying proxy: $proxyUrl" }
            try {
                downloadFile(proxyUrl, file, downloadListener)
                logger.info { "Download successful via proxy" }
            } catch (e: Exception) {
                logger.error(e) { "Proxy download failed" }
                throw e
            }
        }
    }

    /**
     * 执行实际的文件下载
     */
    private suspend fun downloadFile(
        url: String,
        file: File,
        downloadListener: ProgressListener
    ) {
        client.prepareRequest {
            url(url)
            onDownload(downloadListener)
        }.execute { response ->
            response.bodyAsChannel().copyAndClose(file.writeChannel())
        }
    }

    /**
     * 测试代理连接
     * @return 测试结果信息
     */
    suspend fun testProxyConnection(): String {
        val results = StringBuilder()
        results.appendLine("=== 代理连接测试 ===")

        // 1. 测试直连 GitHub API
        results.appendLine("\n1. 测试直连 GitHub API:")
        var release: Release? = null
        try {
            release = getLatestBuild()
            results.appendLine("   成功! 最新版本: ${release.name}")
        } catch (e: Exception) {
            results.appendLine("   失败: ${e.message}")
        }

        // 2. 测试代理下载（使用实际的 APK 下载链接）
        results.appendLine("\n2. 测试代理下载 (ghfast.top):")
        try {
            // 获取实际的 APK 下载链接
            val downloadUrl = release?.assets
                ?.firstOrNull { it.name.endsWith(".apk") }
                ?.browserDownloadUrl
            
            if (downloadUrl == null) {
                results.appendLine("   未找到 APK 下载链接")
            } else {
                val proxyUrl = PROXY_URL + downloadUrl
                results.appendLine("   代理链接: $proxyUrl")
                
                // HEAD 请求探测代理可用性，不下载整个文件；newBuilder 复用共享连接池
                val probeClient = AppHttpClient.okHttpClient.newBuilder()
                    .connectTimeout(5, TimeUnit.SECONDS)
                    .readTimeout(10, TimeUnit.SECONDS)
                    .build()
                val response = probeClient.newCall(
                    Request.Builder()
                        .url(proxyUrl)
                        .head()
                        .header("User-Agent", BiliApiConstants.USER_AGENT_WEB)
                        .build()
                ).execute()

                response.use {
                    if (it.code in 200..399) {
                        results.appendLine("   代理可用 ✓ (状态码: ${it.code})")
                    } else {
                        results.appendLine("   代理响应异常 ✗ (状态码: ${it.code})")
                    }
                }
            }
        } catch (e: Exception) {
            results.appendLine("   代理失败: ${e.message}")
        }

        return results.toString()
    }

    /**
     * 判断指定 Release 是否比当前版本新
     */
    fun isNewerRelease(release: Release): Boolean {
        val assetName = release.assets.firstOrNull { it.name.startsWith("BV") }?.name
            ?: return false
        val revision = assetName.split("_").getOrNull(1)?.toIntOrNull()
            ?: return false
        return revision >= BuildConfig.VERSION_CODE && !assetName.contains(BuildConfig.VERSION_NAME)
    }

    /**
     * 检查更新，并把"检查失败"和"已是最新"区分开——排查启动提示不出现时需要知道是哪一种。
     */
    suspend fun checkUpdate(): UpdateCheckResult = try {
        if (isNewerRelease(getLatestBuild())) UpdateCheckResult.UpdateAvailable
        else UpdateCheckResult.UpToDate
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.error(e) { "Failed to check update availability" }
        UpdateCheckResult.Failed
    }
}

/**
 * [GithubApi.checkUpdate] 的结果
 */
sealed interface UpdateCheckResult {
    /** 发现新版本 */
    object UpdateAvailable : UpdateCheckResult

    /** 已是最新版本 */
    object UpToDate : UpdateCheckResult

    /** 检查失败：网络异常、接口限流、响应格式异常等 */
    object Failed : UpdateCheckResult
}
