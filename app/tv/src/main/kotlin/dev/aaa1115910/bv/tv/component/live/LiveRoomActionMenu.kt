package dev.aaa1115910.bv.tv.component.live

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Done
import androidx.compose.material.icons.rounded.Person
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import dev.aaa1115910.biliapi.repositories.UserRepository
import dev.aaa1115910.bv.R
import dev.aaa1115910.bv.tv.activities.video.UpInfoActivity
import dev.aaa1115910.bv.tv.component.CardActionMenu
import dev.aaa1115910.bv.tv.component.CardActionMenuItem
import dev.aaa1115910.bv.tv.manager.FollowStateManager
import dev.aaa1115910.bv.util.Prefs
import dev.aaa1115910.bv.util.toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

private val ActionedColor = Color(0xfffb7299)

/**
 * 直播间卡片操作菜单：UP 空间、关注/已关注
 *
 * @param show 是否显示
 * @param upId 主播 mid
 * @param upName 主播昵称
 * @param upFace 主播头像
 * @param onDismiss 关闭回调
 */
@Composable
fun LiveRoomActionMenu(
    show: Boolean,
    upId: Long,
    upName: String,
    upFace: String,
    onDismiss: () -> Unit
) {
    if (!show) return

    val context = LocalContext.current
    val scope = remember { CoroutineScope(SupervisorJob()) }
    val userRepository: UserRepository = koinInject()

    // 菜单打开时对登录态取快照，不响应运行时登出
    val isLogin = remember { Prefs.isLogin }

    // 关注状态：先读缓存，未命中时请求一次
    val followStateMap by FollowStateManager.followStateMap.collectAsState()
    val isFollowing = followStateMap[upId] == true

    LaunchedEffect(upId) {
        if (isLogin && upId > 0) {
            withContext(Dispatchers.IO) {
                FollowStateManager.ensureFollowState(upId)
            }
        }
    }

    val followIcon = if (isFollowing) Icons.Rounded.Done else Icons.Rounded.Add
    val followText = stringResource(
        if (isFollowing) R.string.video_info_followed else R.string.video_info_follow
    )

    CardActionMenu(
        show = show,
        onDismiss = onDismiss,
        items = buildList {
            add(
                CardActionMenuItem(
                    icon = Icons.Rounded.Person,
                    text = "UP 空间"
                ) {
                    if (upId > 0) {
                        UpInfoActivity.actionStart(context, mid = upId, name = upName, face = upFace)
                    }
                }
            )
            if (isLogin && upId > 0) {
                add(
                    CardActionMenuItem(
                        icon = followIcon,
                        text = followText,
                        tint = if (isFollowing) ActionedColor else null
                    ) {
                        scope.launch {
                            val success = withContext(Dispatchers.IO) {
                                if (isFollowing) {
                                    userRepository.unfollowUser(mid = upId, preferApiType = Prefs.apiType)
                                } else {
                                    userRepository.followUser(mid = upId, preferApiType = Prefs.apiType)
                                }
                            }
                            if (success) {
                                FollowStateManager.updateFollowState(upId, !isFollowing)
                            }
                            withContext(Dispatchers.Main) {
                                if (success) {
                                    (if (isFollowing) "已取消关注" else "关注成功").toast(context)
                                } else {
                                    (if (isFollowing) "取消关注失败" else "关注失败").toast(context)
                                }
                            }
                        }
                    }
                )
            }
        }
    )
}
