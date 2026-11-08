package dev.aaa1115910.bv.util

import dev.aaa1115910.biliapi.http.util.WebCookieManager
import dev.aaa1115910.biliapi.repositories.AuthRepository

/**
 * WebView 风控页需要与 App 保持一致的 cookie 名。
 *
 * 不在本次同步值里的名字必须被清掉：退出登录 / 换账号后，WebView 里残留的
 * `SESSDATA` 会让风控 SDK 以上一个账号的身份发请求。
 */
val riskCaptchaCookieNames: List<String> = listOf(
    "SESSDATA",
    "DedeUserID",
    "DedeUserID__ckMd5",
    "bili_jct",
    "sid",
    "buvid3",
    "buvid4",
    "b_nut",
    "bili_ticket",
    "bili_ticket_expires",
    "x-bili-gaia-vtoken",
)

/**
 * 生成写入 Cookie 罐的指令（`CookieManager.setCookie(url, 指令)` 的第二个参数）。
 *
 * 先给每个受管名字各发两条 `Max-Age=0` 清理指令（host 作用域 + `.bilibili.com` 域作用域），
 * 再写入本次提供的值。两种作用域都要清：`SpeedTestScreen` 之类的地方会以 `.bilibili.com`
 * 写域 cookie，只清 host 作用域删不掉它，登出后 SDK 仍会带上旧账号的 `SESSDATA`。
 */
fun riskCaptchaCookieCommands(cookie: String): List<String> {
    val clears = riskCaptchaCookieNames.flatMap { name ->
        listOf(
            "$name=; Max-Age=0; path=/",
            "$name=; Max-Age=0; path=/; domain=.bilibili.com",
        )
    }
    val writes = cookie.split(';')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .mapNotNull { pair ->
            val name = pair.substringBefore('=').trim()
            val value = pair.substringAfter('=', "").trim()
            if (name.isEmpty() || value.isEmpty()) null else "$name=$value; path=/"
        }
    return clears + writes
}

/**
 * 内嵌 B 站官方风控 SDK（WebView）时注入的 Cookie。
 *
 * SDK 在 WebView 里自己请求 `/x/gaia-vgate/v2/register` / `/v2/validate`，
 * 登录态与 `bili_jct`(csrf) 只能靠 Cookie 带上；web 指纹（buvid3/buvid4/b_nut/bili_ticket）
 * 取自 [WebCookieManager]，与 API 请求侧保持同一套指纹。
 */
fun buildRiskCaptchaCookie(authRepository: AuthRepository): String {
    val parts = mutableListOf<String>()
    fun add(name: String, value: String?) {
        value?.takeIf { it.isNotBlank() }?.let { parts += "$name=$it" }
    }

    add("SESSDATA", authRepository.sessionData)
    add("DedeUserID", authRepository.mid?.takeIf { it > 0L }?.toString())
    add("DedeUserID__ckMd5", authRepository.dedeUserIDCkMd5)
    add("bili_jct", authRepository.biliJct)
    add("sid", authRepository.sid)
    add("buvid3", authRepository.buvid3)
    WebCookieManager.cookieHeader()
        ?.split(';')
        ?.map { it.trim() }
        ?.filter { it.isNotBlank() }
        ?.forEach { part ->
            val name = part.substringBefore('=')
            if (parts.none { it.startsWith("$name=") }) parts += part
        }
    return parts.joinToString("; ")
}
