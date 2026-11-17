package dev.aaa1115910.bv.tv.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

/** 输入框聚焦后停留多久才认为是真的要输入 */
private const val DefaultImeFocusDelayMs = 350L

/**
 * 遥控器输入框的「延迟弹出输入法」开关，配合 `readOnly = !editable` 使用。
 *
 * D-pad 换焦点时会经过路径上的输入框（例如「已关注 UP」面板里从「全部」上移到刷新按钮，
 * 中间正好是搜索框）。输入框一聚焦就弹输入法，输入法窗口会把焦点抢走、后续按键全被打断。
 * 宽限期内把输入框设成 readOnly，Compose 不建立输入会话，也就不会弹输入法；停留超过
 * [delayMillis] 才恢复可编辑并自动弹出。焦点离开立即复位，下次进入重新计时。
 *
 * @param focused 输入框自身的焦点状态，取自 `.onFocusChanged { focused = it.isFocused }`
 */
@Composable
fun rememberDelayedImeEditable(
    focused: Boolean,
    delayMillis: Long = DefaultImeFocusDelayMs
): Boolean {
    var editable by remember { mutableStateOf(false) }
    LaunchedEffect(focused) {
        if (!focused) {
            editable = false
            return@LaunchedEffect
        }
        delay(delayMillis)
        editable = true
    }
    return editable
}
