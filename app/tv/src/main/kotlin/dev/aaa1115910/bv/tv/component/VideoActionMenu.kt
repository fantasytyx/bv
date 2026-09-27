package dev.aaa1115910.bv.tv.component

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Paid
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Paid
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.ThumbUp
import androidx.compose.material.icons.rounded.WatchLater
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import dev.aaa1115910.bv.tv.activities.video.UpInfoActivity
import dev.aaa1115910.bv.tv.activities.video.VideoInfoActivity
import dev.aaa1115910.bv.tv.manager.VideoUserActionManager
import dev.aaa1115910.bv.util.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val ActionedColor = Color(0xfffb7299)

/**
 * 视频操作菜单的额外菜单项
 *
 * @param item 菜单项
 * @param index 插入位置，0 为菜单最前面；超出范围时落到末尾
 */
data class VideoActionMenuExtraItem(
    val item: CardActionMenuItem,
    val index: Int = Int.MAX_VALUE
)

/**
 * UGC 视频卡片操作菜单
 *
 * @param show 是否显示
 * @param aid 视频 aid
 * @param upId UP 主 mid
 * @param upName UP 主昵称
 * @param upFace UP 主头像
 * @param onDismiss 关闭回调
 * @param onDelete 删除操作回调，不为 null 时显示删除菜单项
 * @param deleteLabel 删除菜单项文案，默认"删除"
 * @param extraItems 额外的操作项，按各自 index 插入到固定菜单项之间
 */
@Composable
fun VideoActionMenu(
    show: Boolean,
    aid: Long,
    upId: Long,
    upName: String,
    upFace: String,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)? = null,
    deleteLabel: String = "删除",
    extraItems: List<VideoActionMenuExtraItem> = emptyList()
) {
    if (!show) return

    val context = LocalContext.current
    val scope = remember { CoroutineScope(SupervisorJob()) }

    // 菜单打开时对登录态取快照，不响应运行时登出
    val isLogin = remember { Prefs.isLogin }

    // 惰性加载操作状态（仅首次打开菜单时触发）
    LaunchedEffect(aid) {
        if (isLogin) {
            VideoUserActionManager.ensureStateLoaded(aid)
        }
    }

    // 获取当前操作状态
    val actionState by VideoUserActionManager.getStateFlow(aid).collectAsState()

    // 收藏夹选择对话框状态
    var showFavoriteFolderDialog by remember { mutableStateOf(false) }
    val favoriteFolders by VideoUserActionManager.getFavoriteFoldersFlow().collectAsState()

    CardActionMenu(
        show = show,
        onDismiss = onDismiss,
        items = buildList {
            add(
                CardActionMenuItem(
                    icon = Icons.Rounded.Person,
                    text = "UP 主页"
                ) {
                    if (upId > 0) {
                        UpInfoActivity.actionStart(context, mid = upId, name = upName, face = upFace)
                    }
                }
            )
            add(
                CardActionMenuItem(
                    icon = Icons.Rounded.Info,
                    text = "详情"
                ) {
                    VideoInfoActivity.actionStart(context, aid = aid, forceShowDetail = true)
                }
            )
            if (isLogin) {
                add(
                    CardActionMenuItem(
                        icon = if (actionState.liked) Icons.Rounded.ThumbUp else Icons.Outlined.ThumbUp,
                        text = if (actionState.liked) "已点赞" else "点赞",
                        tint = if (actionState.liked) ActionedColor else null
                    ) {
                        scope.launch {
                            if (actionState.liked) {
                                VideoUserActionManager.delLike(aid)
                            } else {
                                VideoUserActionManager.addLike(aid)
                            }
                        }
                    }
                )
                // 收藏要先选收藏夹，保持菜单打开
                add(
                    CardActionMenuItem(
                        icon = if (actionState.favorited) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                        text = if (actionState.favorited) "已收藏" else "收藏",
                        tint = if (actionState.favorited) ActionedColor else null,
                        dismissOnClick = false
                    ) {
                        showFavoriteFolderDialog = true
                    }
                )
                add(
                    CardActionMenuItem(
                        icon = if (actionState.coin) Icons.Rounded.Paid else Icons.Outlined.Paid,
                        text = if (actionState.coin) "已投币" else "投币",
                        tint = if (actionState.coin) ActionedColor else null
                    ) {
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                VideoUserActionManager.addCoin(aid)
                            }
                        }
                    }
                )
                add(
                    CardActionMenuItem(
                        icon = Icons.Rounded.WatchLater,
                        text = "稍后再看"
                    ) {
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                VideoUserActionManager.addToView(aid)
                            }
                        }
                    }
                )
            }
            if (onDelete != null) {
                add(
                    CardActionMenuItem(
                        icon = Icons.Rounded.Delete,
                        text = deleteLabel,
                        textColor = ActionedColor,
                        onClick = onDelete
                    )
                )
            }
        }.toMutableList().apply {
            // 按 extraItems 顺序依次插入，越界时落到末尾
            extraItems.forEach { extra -> add(extra.index.coerceIn(0, size), extra.item) }
        },
        // 收藏夹选择对话框（叠加在菜单之上，仅登录后可用）
        overlay = {
            if (isLogin) {
                FavoriteFolderDialog(
                    show = showFavoriteFolderDialog,
                    onDismiss = { showFavoriteFolderDialog = false },
                    userFavoriteFolders = favoriteFolders,
                    favoriteFolderIds = actionState.favoriteFolderIds,
                    onUpdateFavoriteFolders = { folderIds ->
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                VideoUserActionManager.updateVideoFavoriteFolders(
                                    aid = aid,
                                    folderIds = folderIds
                                )
                            }
                        }
                    }
                )
            }
        }
    )
}
