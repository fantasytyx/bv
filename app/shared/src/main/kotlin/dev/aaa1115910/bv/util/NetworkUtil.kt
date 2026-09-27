package dev.aaa1115910.bv.util

import dev.aaa1115910.bv.network.AppHttpClient
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext

object NetworkUtil {
    private val client = AppHttpClient.client
    private val locCheckUrls = listOf(
        "https://www.cloudflare.com/cdn-cgi/trace",
        "https://1.1.1.1/cdn-cgi/trace"
    )
    private val logger = KotlinLogging.logger { }

    suspend fun isMainlandChina() = withContext(Dispatchers.IO) {
        val deferreds = locCheckUrls.map { locCheckUrl ->
            async {
                runCatching {
                    val result = client.get(locCheckUrl).bodyAsText()
                    logger.info { "Network result:\n$result" }

                    val networkCheckResult = result
                        .lines()
                        .filter { it.isNotBlank() }
                        .associate { with(it.split("=")) { this[0] to this[1] } }

                    require(networkCheckResult["loc"] != "CN") { "BV doesn't support use in mainland China" }
                    false
                }.getOrDefault(true)
            }
        }

        select {
            deferreds.forEach { deferred ->
                deferred.onAwait { it }
            }
        }.also {
            deferreds.forEach { it.cancel() }
        }
    }
}