package dev.aaa1115910.bv.tv.component

import android.content.Intent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.People
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.ThumbUp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import dev.aaa1115910.biliapi.entity.FavoriteResourceType
import dev.aaa1115910.biliapi.entity.user.DynamicItem
import dev.aaa1115910.biliapi.entity.user.DynamicType
import dev.aaa1115910.biliapi.repositories.FavoriteRepository
import dev.aaa1115910.biliapi.repositories.LikeRepository
import dev.aaa1115910.bv.tv.activities.dynamic.DynamicDetailActivity
import dev.aaa1115910.bv.tv.activities.user.ArticleActivity
import dev.aaa1115910.bv.tv.activities.user.FollowActivity
import dev.aaa1115910.bv.tv.activities.video.UpInfoActivity
import dev.aaa1115910.bv.util.Prefs
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

private val ActionedColor = Color(0xfffb7299)

/**
 * 动态卡片的长按菜单：视频卡直接复用 [VideoActionMenu]，其余卡片用 [CardActionMenu] 拼动态专属项。
 * 图文与其它卡片只在本地覆盖点赞/收藏状态，不额外发查询请求。
 */
@Composable
fun DynamicActionMenu(
    show: Boolean,
    dynamic: DynamicItem,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val likeRepository: LikeRepository = koinInject()
    val favoriteRepository: FavoriteRepository = koinInject()
    val logger = remember { KotlinLogging.logger("DynamicActionMenu") }
    val video = dynamic.video
    val upInfo: () -> Unit = {
        if (dynamic.author.mid > 0) {
            UpInfoActivity.actionStart(
                context = context,
                mid = dynamic.author.mid,
                name = dynamic.author.author,
                face = dynamic.author.avatar
            )
        }
    }
    val followList: () -> Unit = {
        context.startActivity(Intent(context, FollowActivity::class.java))
    }

    // 视频卡：详情/点赞/收藏/投币/稍后再看全部交给现成的视频菜单
    if (video != null) {
        VideoActionMenu(
            show = show,
            aid = video.aid,
            upId = dynamic.author.mid,
            upName = dynamic.author.author,
            upFace = dynamic.author.avatar,
            onDismiss = onDismiss,
            showUpInfoAction = true,
            resourceType = FavoriteResourceType.Video,
            extraItems = listOf(
                VideoActionMenuExtraItem(
                    CardActionMenuItem(icon = Icons.Rounded.People, text = "已关注 UP 列表") {
                        followList()
                    }
                )
            )
        )
        return
    }

    val isDraw = dynamic.type == DynamicType.Draw && dynamic.blocked == null &&
        dynamic.id?.toLongOrNull() != null
    // 菜单打开时对登录态取快照，不响应运行时登出
    val isLogin = remember { Prefs.isLogin }
    var liked by remember(dynamic.id) { mutableStateOf(dynamic.footer?.liked == true) }
    var favorited by remember(dynamic.id) { mutableStateOf(dynamic.footer?.favorited == true) }

    val detail: () -> Unit = {
        if (isDraw) {
            dynamic.id?.toLongOrNull()?.let { ArticleActivity.actionStart(context, it) }
        } else {
            dynamic.id?.let { DynamicDetailActivity.actionStart(context, it) }
        }
    }
    val toggleLike: () -> Unit = like@{
        val id = dynamic.id ?: return@like
        scope.launch {
            val target = !liked
            runCatching {
                withContext(Dispatchers.IO) { likeRepository.setDynamicLike(id, target) }
            }.onSuccess {
                liked = target
            }.onFailure { error ->
                logger.warn(error) { "Set dynamic like failed" }
            }
        }
    }
    val toggleFavorite: () -> Unit = {
        val id = dynamic.id?.toLongOrNull()
        if (id != null) {
            scope.launch {
                val target = !favorited
                runCatching {
                    withContext(Dispatchers.IO) {
                        favoriteRepository.setOpusFavorite(opusId = id, favorite = target)
                    }
                }.onSuccess {
                    favorited = target
                }.onFailure { error ->
                    logger.warn(error) { "Set opus favorite failed" }
                }
            }
        }
    }

    CardActionMenu(
        show = show,
        onDismiss = onDismiss,
        items = buildList {
            add(CardActionMenuItem(icon = Icons.Rounded.Person, text = "UP 主页") { upInfo() })
            add(CardActionMenuItem(icon = Icons.Rounded.Info, text = "详情") { detail() })
            if (isLogin) {
                add(
                    CardActionMenuItem(
                        icon = if (liked) Icons.Rounded.ThumbUp else Icons.Outlined.ThumbUp,
                        text = if (liked) "已点赞" else "点赞",
                        tint = if (liked) ActionedColor else null
                    ) {
                        toggleLike()
                    }
                )
                if (isDraw) {
                    add(
                        CardActionMenuItem(
                            icon = if (favorited) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                            text = if (favorited) "已收藏" else "收藏",
                            tint = if (favorited) ActionedColor else null
                        ) {
                            toggleFavorite()
                        }
                    )
                }
            }
            add(CardActionMenuItem(icon = Icons.Rounded.People, text = "已关注 UP 列表") { followList() })
        }
    )
}
