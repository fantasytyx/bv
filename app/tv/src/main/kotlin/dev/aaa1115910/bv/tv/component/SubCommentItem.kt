package dev.aaa1115910.bv.tv.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import dev.aaa1115910.biliapi.entity.reply.Comment
import dev.aaa1115910.bv.util.focusedBorder
import dev.aaa1115910.bv.util.isDpadDown
import dev.aaa1115910.bv.util.isKeyDown
import dev.aaa1115910.bv.util.isDpadRight
import kotlinx.coroutines.launch

/**
 * 子评论项组件
 *
 * 子评论有焦点边框，但不响应点击
 *
 * @param comment 评论数据
 * @param modifier 修饰符
 * @param onLongClick 长按回调
 */
@Composable
fun SubCommentItem(
    comment: Comment,
    modifier: Modifier = Modifier,
    onLongClick: () -> Unit = {}
) {
    // 子评论有焦点边框，但不响应点击
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .focusedBorder(MaterialTheme.shapes.small),
        onClick = { /* 空回调，不执行任何操作 */ },
        onLongClick = onLongClick,
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
            pressedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        ),
        scale = ClickableSurfaceDefaults.scale(
            focusedScale = 1f,
            pressedScale = 1f
        ),
        shape = ClickableSurfaceDefaults.shape(shape = MaterialTheme.shapes.small)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = androidx.compose.ui.Alignment.Top
        ) {
            // 头像
            AsyncImage(
                model = comment.member.avatar,
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape),
                contentDescription = null,
                contentScale = ContentScale.Crop
            )

            // 内容
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // 用户名
                Text(
                    text = comment.member.name,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.7f)
                )

                // 评论内容（支持富文本表情）
                CommentContent(
                    content = comment.content,
                    emotes = comment.emotes
                )

                // 评论图片
                if (comment.pictures.isNotEmpty()) {
                    CommentPictures(
                        pictures = comment.pictures,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }

                // 底部信息
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = comment.timeDesc,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.5f)
                    )
                    Text(
                        text = "${comment.like} 赞",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.5f)
                    )
                }
            }
        }
    }
}

/**
 * 子评论根评论显示组件（只读，右键展开/收起，展开后下键滚动）
 *
 * @param comment 评论数据
 * @param onLongClick 长按回调
 * @param modifier 外部修饰符
 * @param focusRequester 外部焦点请求器（进入子评论时立即聚焦到这里）
 */
@Composable
fun SubCommentRootItem(
    comment: Comment,
    onLongClick: () -> Unit = {},
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester = remember { FocusRequester() }
) {
    var expanded by remember { mutableStateOf(false) }
    var contentExceedsLimit by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    Surface(
        onClick = { /* 右键展开/收起 */ },
        onLongClick = onLongClick,
        modifier = modifier
            .fillMaxWidth()
            .focusRequester(focusRequester)
            .onPreviewKeyEvent { event ->
                when {
                    // 右键展开/收起（仅内容超出高度限制时）
                    event.isKeyDown() && event.isDpadRight() && contentExceedsLimit -> {
                        expanded = !expanded
                        if (expanded) {
                            scope.launch { scrollState.scrollTo(0) }
                        }
                        true
                    }
                    // 展开状态下，下键滚动内容，不允许焦点转移
                    event.isKeyDown() && event.isDpadDown() && expanded -> {
                        scope.launch {
                            val scrollAmount = with(density) { 100.dp.toPx() }
                            scrollState.animateScrollBy(scrollAmount)
                        }
                        true // 始终拦截事件，防止焦点转移
                    }
                    else -> false
                }
            },
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f),
            pressedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
        ),
        scale = ClickableSurfaceDefaults.scale(
            focusedScale = 1f,
            pressedScale = 1f
        ),
        shape = ClickableSurfaceDefaults.shape(shape = MaterialTheme.shapes.small)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier
                    .then(
                        if (expanded) {
                            Modifier.heightIn(max = 300.dp).verticalScroll(scrollState)
                        } else {
                            Modifier.heightIn(max = 150.dp)
                        }
                    )
                    .onSizeChanged { size ->
                        if (!expanded && !contentExceedsLimit) {
                            val limitPx = with(density) { 150.dp.toPx() }
                            contentExceedsLimit = size.height >= limitPx.toInt()
                        }
                    },
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = androidx.compose.ui.Alignment.Top
            ) {
                AsyncImage(
                    model = comment.member.avatar,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape),
                    contentDescription = null,
                    contentScale = ContentScale.Crop
                )

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = comment.member.name,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.7f)
                    )

                    // 评论内容（支持富文本表情，最多3行）
                    CommentContent(
                        content = comment.content,
                        emotes = comment.emotes,
                        maxLines = if (expanded) Int.MAX_VALUE else 3,
                        overflow = TextOverflow.Ellipsis
                    )

                    // 评论图片
                    if (comment.pictures.isNotEmpty()) {
                        CommentPictures(
                            pictures = comment.pictures,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text(
                            text = comment.timeDesc,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.5f)
                        )
                        Text(
                            text = "${comment.like} 赞",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.5f)
                        )
                    }
                }
            }

            // 展开/收起提示（仅内容超出高度限制时显示）
            if (contentExceedsLimit) {
                Text(
                    text = if (expanded) "右键收起 <<" else "右键展开 >>",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White,
                    modifier = Modifier.padding(start = 48.dp)
                )
            }
        }
    }
}
