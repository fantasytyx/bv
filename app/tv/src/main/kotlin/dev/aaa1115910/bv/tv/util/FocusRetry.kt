package dev.aaa1115910.bv.tv.util

import androidx.compose.ui.focus.FocusRequester
import kotlinx.coroutines.delay

/**
 * 请求焦点，失败就重试。
 *
 * 目标项可能还没被组合出来（列表刚切换、刚 scrollToItem），此时 `requestFocus()` 会抛异常，
 * 等下一轮布局更新后重试即可。
 */
suspend fun FocusRequester.requestFocusWithRetry(attempts: Int = 20, delayMs: Long = 50L): Boolean {
    repeat(attempts) {
        if (runCatching { requestFocus() }.isSuccess) return true
        delay(delayMs)
    }
    return false
}
