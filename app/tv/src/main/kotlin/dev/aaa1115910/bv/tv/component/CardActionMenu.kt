package dev.aaa1115910.bv.tv.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text

/**
 * 卡片操作菜单的菜单项
 *
 * @param icon 图标
 * @param text 文案
 * @param tint 图标颜色，null 使用默认色
 * @param textColor 文案颜色，null 使用默认色
 * @param dismissOnClick 点击后是否关闭菜单
 * @param onClick 点击回调
 */
data class CardActionMenuItem(
    val icon: ImageVector,
    val text: String,
    val tint: Color? = null,
    val textColor: Color? = null,
    val dismissOnClick: Boolean = true,
    val onClick: () -> Unit
)

/**
 * 通用卡片操作菜单弹窗，第一项默认获得焦点
 *
 * @param show 是否显示
 * @param items 菜单项
 * @param onDismiss 关闭回调
 * @param overlay 叠加在菜单之上的内容（如收藏夹选择对话框）
 */
@Composable
fun CardActionMenu(
    show: Boolean,
    items: List<CardActionMenuItem>,
    onDismiss: () -> Unit,
    overlay: @Composable () -> Unit = {}
) {
    if (!show) return

    val firstItemFocusRequester = remember { FocusRequester() }

    // 打开菜单那次按键的抬起事件不应触发菜单项：弹窗内没收到过按住事件（长按重复不算）就吞掉抬起事件
    var hasKeyDownInDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        firstItemFocusRequester.requestFocus()
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onPreviewKeyEvent { event ->
                    when {
                        event.type == KeyEventType.KeyDown -> {
                            if (event.nativeKeyEvent.repeatCount == 0) hasKeyDownInDialog = true
                            false
                        }

                        event.type == KeyEventType.KeyUp && !hasKeyDownInDialog -> true
                        else -> false
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .widthIn(300.dp, 450.dp)
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp))
                    .padding(16.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items.forEachIndexed { index, item ->
                        CardMenuItem(
                            focusRequester = if (index == 0) firstItemFocusRequester else null,
                            item = item,
                            onClick = {
                                item.onClick()
                                if (item.dismissOnClick) onDismiss()
                            }
                        )
                    }
                }
            }

            overlay()
        }
    }
}

@Composable
private fun CardMenuItem(
    item: CardActionMenuItem,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier),
        onClick = onClick,
        colors = ClickableSurfaceDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            pressedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f, pressedScale = 1f),
        shape = ClickableSurfaceDefaults.shape(shape = MaterialTheme.shapes.small)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start
        ) {
            Icon(
                modifier = Modifier.size(22.dp),
                imageVector = item.icon,
                contentDescription = null,
                tint = item.tint ?: MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.width(14.dp))
            Text(
                text = item.text,
                style = MaterialTheme.typography.bodyLarge,
                color = item.textColor ?: MaterialTheme.colorScheme.onSurface
            )
        }
    }
}
