package dev.aaa1115910.bv.network

import dev.aaa1115910.biliapi.BiliApiConstants
import dev.aaa1115910.biliapi.http.BiliHttpClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.UserAgent
import io.ktor.client.plugins.compression.ContentEncoding
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * 非 B 站域名（GitHub、CDN、黑名单、字幕、连通性检测）的 HttpClient 统一入口。
 *
 * 与 BiliHttpClient 分开：这些站点不走 BiliDns、不需要 bili UA 和签名，读超时也更宽松。
 * 但同样只维护一个 [okHttpClient]，避免每个调用点各占一套连接池。
 */
object AppHttpClient {
    val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /** 请求一律用绝对 URL */
    val client: HttpClient by lazy {
        HttpClient(OkHttp) {
            engine {
                preconfigured = okHttpClient
            }
            install(UserAgent) {
                agent = BiliApiConstants.USER_AGENT_WEB
            }
            install(ContentNegotiation) {
                json(BiliHttpClient.json)
            }
            install(ContentEncoding) {
                deflate(1.0F)
                gzip(0.9F)
            }
            install(HttpRequestRetry) {
                retryOnException(maxRetries = 2)
            }
        }
    }
}
