package dev.aaa1115910.bv.tv.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Done
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.FilterChip
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.aaa1115910.biliapi.entity.FavoriteFolderMetadata
import dev.aaa1115910.bv.R
import dev.aaa1115910.bv.util.swapList
import kotlinx.coroutines.delay

/**
 * 收藏夹选择对话框
 * 抽取自 FavoriteButton，供 VideoActionMenu 等多处复用
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun FavoriteFolderDialog(
    modifier: Modifier = Modifier,
    show: Boolean,
    onDismiss: () -> Unit,
    userFavoriteFolders: List<FavoriteFolderMetadata> = emptyList(),
    favoriteFolderIds: List<Long> = emptyList(),
    onUpdateFavoriteFolders: (List<Long>) -> Unit,
    containerColor: Color = MaterialTheme.colorScheme.surface
) {
    val selectedFavoriteFolderIds = remember { mutableStateListOf<Long>() }
    val defaultFocusRequester = remember { FocusRequester() }
    var lastInteractionTime by remember { mutableStateOf(System.currentTimeMillis()) }
    fun touch() { lastInteractionTime = System.currentTimeMillis() }

    LaunchedEffect(show) {
        if (show) {
            selectedFavoriteFolderIds.swapList(favoriteFolderIds)
            defaultFocusRequester.requestFocus()
            touch()
        }
    }
    // 10 秒无操作自动关闭
    LaunchedEffect(lastInteractionTime, show) {
        if (show) {
            val base = lastInteractionTime
            delay(10000)
            if (base == lastInteractionTime) onDismiss()
        }
    }

    if (!show) return

    // M3 AlertDialog 的内容宽度被限制在 560dp，大屏设备上会明显偏窄，这里改用 Dialog 自绘
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = modifier
                    .fillMaxWidth(0.65f)
                    .background(color = containerColor, shape = RoundedCornerShape(16.dp))
                    .padding(24.dp)
            ) {
                Text(
                    text = stringResource(R.string.favorite_dialog_title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = AlertDialogDefaults.titleContentColor
                )
                FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    userFavoriteFolders.forEachIndexed { index, folder ->
                        val selected = selectedFavoriteFolderIds.contains(folder.id)
                        val isDefault = folder.title == "默认收藏夹"

                        val itemModifier =
                            if (index == 0) Modifier.focusRequester(defaultFocusRequester)
                            else Modifier

                        FilterChip(
                            modifier = itemModifier
                                .onFocusChanged { if (it.hasFocus) touch() },
                            enabled = isDefault || selected || folder.mediaCount < 1000,
                            selected = selected,
                            onClick = {
                                if (selectedFavoriteFolderIds.contains(folder.id)) {
                                    selectedFavoriteFolderIds.remove(folder.id)
                                    folder.mediaCount -= 1
                                } else {
                                    selectedFavoriteFolderIds.add(folder.id)
                                    folder.mediaCount += 1
                                }
                                onUpdateFavoriteFolders(selectedFavoriteFolderIds)
                                touch()
                            },
                            leadingIcon = {
                                Row {
                                    if (selected) {
                                        Icon(
                                            modifier = Modifier.size(20.dp),
                                            imageVector = Icons.Rounded.Done,
                                            contentDescription = null
                                        )
                                    }
                                }
                            }
                        ) {
                            Column {
                                Text(
                                    text = folder.title,
                                    style = MaterialTheme.typography.labelLarge.copy(
                                        fontSize = 15.sp,
                                        lineHeight = 15.sp
                                    )
                                )
                                Text(
                                    text = "${folder.mediaCount}${if (!isDefault) "/1000" else ""}",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontSize = 10.sp,
                                        lineHeight = 11.sp
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
