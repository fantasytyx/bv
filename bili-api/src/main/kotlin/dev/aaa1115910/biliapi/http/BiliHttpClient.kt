package dev.aaa1115910.biliapi.http

import com.tfowl.ktor.client.plugins.JsoupPlugin
import dev.aaa1115910.biliapi.http.plugins.BiliUserAgent
import dev.aaa1115910.biliapi.http.plugins.BiliUserAgentConfig
import dev.aaa1115910.biliapi.http.util.BiliDns
import dev.aaa1115910.biliapi.http.util.encApiSign
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.compression.ContentEncoding
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.request.header
import io.ktor.client.request.url
import io.ktor.http.URLProtocol
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

/**
 * B 站接口 HttpClient 的唯一构造入口。插件组合只在这里定义一次，各个 API 用参数声明自己的差异，
 * 不再各自复制 ContentNegotiation / ContentEncoding / BiliUserAgent 的配置。
 *
 * 所有 client 共用同一个 [okHttpClient]：连接池、线程池、DNS 缓存全局只有一份
 * （每个 API 各 new 一个 OkHttpClient 会让它们各占一套连接池）。
 */
object BiliHttpClient {
    /** 全项目共用：coerceInputValues 容错类型不匹配，ignoreUnknownKeys 容错新增字段 */
    val json = Json {
        coerceInputValues = true
        ignoreUnknownKeys = true
    }

    val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dns(BiliDns)
            .build()
    }

    /**
     * @param host 相对路径的默认域名；null 表示该 client 只用绝对 URL
     * @param port 默认端口，null 表示用协议默认端口
     * @param userAgent BiliUserAgent 的配置，传 null 表示不发 UA
     * @param addApiSign 是否挂 [encApiSign]（补 buvid3/web cookie + wbi/app 签名）
     * @param addJsoup 是否解析 HTML/XML 响应为 jsoup Document
     * @param addWebSockets 是否启用 wss
     * @param retryOnException 异常重试次数；null 表示不装 HttpRequestRetry
     * @param compression 是否处理 gzip/deflate 响应
     * @param defaultHeaders 额外默认请求头
     */
    fun create(
        host: String? = null,
        port: Int? = null,
        protocol: URLProtocol = URLProtocol.HTTPS,
        userAgent: (BiliUserAgentConfig.() -> Unit)? = {},
        addApiSign: Boolean = false,
        addJsoup: Boolean = false,
        addWebSockets: Boolean = false,
        retryOnException: Int? = null,
        compression: Boolean = true,
        defaultHeaders: Map<String, String> = emptyMap(),
    ): HttpClient {
        // 下面 defaultRequest 的作用域里 url { } 的接收者是 URLBuilder，同名的 host/port/protocol 会被遮蔽，
        // 所以先落到局部变量再引用。
        val defaultHost = host
        val defaultPort = port
        val defaultProtocol = protocol
        val requestHeaders = defaultHeaders

        return HttpClient(OkHttp) {
            engine {
                preconfigured = okHttpClient
            }
            userAgent?.let { install(BiliUserAgent, it) }
            install(ContentNegotiation) {
                json(BiliHttpClient.json)
            }
            if (compression) {
                install(ContentEncoding) {
                    deflate(1.0F)
                    gzip(0.9F)
                }
            }
            retryOnException?.let { maxRetries ->
                install(HttpRequestRetry) {
                    retryOnException(maxRetries = maxRetries)
                }
            }
            if (addJsoup) install(JsoupPlugin)
            if (addWebSockets) install(WebSockets)
            if (defaultHost != null || defaultPort != null || requestHeaders.isNotEmpty()) {
                defaultRequest {
                    url {
                        defaultHost?.let { this.host = it }
                        defaultPort?.let { this.port = it }
                        this.protocol = defaultProtocol
                    }
                    requestHeaders.forEach { (name, value) -> header(name, value) }
                }
            }
        }.apply {
            if (addApiSign) encApiSign()
        }
    }
}
