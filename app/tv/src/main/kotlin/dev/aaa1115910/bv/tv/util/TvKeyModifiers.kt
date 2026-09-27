package dev.aaa1115910.bv.tv.util

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type

/**
 * 遥控器菜单键按下时回调 [onMenuKeyDown]，并消费掉菜单键的按下/抬起事件，避免被其它层再处理
 */
fun Modifier.onMenuKeyDown(onMenuKeyDown: () -> Unit): Modifier = onPreviewKeyEvent { event ->
    if (event.key != Key.Menu) return@onPreviewKeyEvent false
    if (event.type == KeyEventType.KeyDown) onMenuKeyDown()
    true
}
