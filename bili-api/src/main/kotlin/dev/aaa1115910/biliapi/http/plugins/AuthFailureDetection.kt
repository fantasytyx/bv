package dev.aaa1115910.biliapi.http.plugins

import dev.aaa1115910.biliapi.http.BiliHttpClient
import dev.aaa1115910.biliapi.http.entity.BiliAuthFailureHandler
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.isSaved
import io.ktor.client.plugins.plugin
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** 会话失效响应里必然出现的字面量，用作廉价预筛；判定仍以 JSON 里的 code 为准 */
private const val AUTH_FAILURE_MARKER = "-101"

/**
 * 会话失效判定。
 *
 * `-101` 只是「账号未登录」，匿名请求同样会拿到（nav / myinfo / relation / coins / favoured 等
 * 接口未登录都返回 -101），所以只有**确实带了登录凭证**（SESSDATA 或 access_key）的请求收到
 * -101 才算会话失效，回调 App 层自动登出。
 *
 * 判定放在 HTTP 层而不是 `BiliResponse.init`：那里只能看到响应体，看不到请求是否带了凭证，
 * 会导致启动期/游客请求的 -101 被误判成会话失效。
 */
fun HttpClient.installAuthFailureDetection() = plugin(HttpSend).intercept { request ->
    val call = execute(request)
    if (!request.carriesLoginCredential()) return@intercept call
    // Ktor 默认用 SaveBody 缓存非流式响应体，这里重复读取不会破坏调用方的 body
    if (!call.response.isSaved) return@intercept call
    if (!call.response.isJsonResponse()) return@intercept call

    val message = runCatching { call.response.bodyAsText().authFailureMessage() }.getOrNull()
    if (message != null) BiliAuthFailureHandler.notify(message)
    call
}

private fun String.authFailureMessage(): String? {
    // 先扫一遍字面量，正常响应（code=0）不用再解析一次 JSON
    if (!contains(AUTH_FAILURE_MARKER)) return null
    val root = runCatching { BiliHttpClient.json.parseToJsonElement(this).jsonObject }.getOrNull()
        ?: return null
    if (root["code"]?.jsonPrimitive?.intOrNull != -101) return null
    return root["message"]?.jsonPrimitive?.contentOrNull.orEmpty()
}

/** 只判定 JSON 响应：弹幕/protobuf、XML、图片等二进制体不需要按文本判定 */
private fun HttpResponse.isJsonResponse(): Boolean {
    val raw = headers[HttpHeaders.ContentType] ?: return false
    val contentType = runCatching { ContentType.parse(raw) }.getOrNull() ?: return false
    return contentType.match(ContentType.Application.Json)
}

/** 请求是否带上了登录凭证：Cookie 里的非空 SESSDATA，或非空的 access_key */
private fun HttpRequestBuilder.carriesLoginCredential(): Boolean {
    val cookie = headers[HttpHeaders.Cookie]
    if (cookie != null) {
        val sessData = cookie.substringAfter("SESSDATA=", "")
            .substringBefore(';')
            .trim()
        if (sessData.isNotEmpty()) return true
    }
    if (!url.parameters["access_key"].isNullOrBlank()) return true
    val body = body
    if (body is FormDataContent) {
        return !body.formData["access_key"].isNullOrBlank()
    }
    return false
}
