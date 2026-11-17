package dev.aaa1115910.bv.tv.screens.user

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import dev.aaa1115910.biliapi.entity.ugc.toSmartDate
import dev.aaa1115910.biliapi.entity.user.SpaceArticle
import dev.aaa1115910.biliapi.entity.user.SpaceCollection
import dev.aaa1115910.biliapi.entity.user.SpaceSeries
import dev.aaa1115910.bv.util.ImageSize
import dev.aaa1115910.bv.util.resizedImageUrl

@Composable
fun SpaceArticleCard(
    article: SpaceArticle,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onFocus: () -> Unit
) {
    val likeText = formatCount(article.likeCount)
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { if (it.isFocused) onFocus() },
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(shape = MaterialTheme.shapes.medium)
    ) {
        Column {
            if (article.cover.isNotBlank()) {
                Box {
                    AsyncImage(
                        model = article.cover.resizedImageUrl(ImageSize.ArticleCardCover),
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp),
                        contentScale = ContentScale.Crop
                    )
                    DynamicBadge(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(5.dp),
                        text = likeText,
                        icon = Icons.Outlined.ThumbUp
                    )
                }
            }
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(article.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium.copy(fontSize = 15.sp))
                // 没有封面时没有图片区可以压角标，点赞数仍然放在标题下面
                if (article.cover.isBlank()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            modifier = Modifier.size(14.dp),
                            imageVector = Icons.Outlined.ThumbUp,
                            contentDescription = null,
                            tint = LocalContentColor.current.copy(alpha = 0.65f)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            likeText,
                            color = LocalContentColor.current.copy(alpha = 0.65f),
                            fontSize = 13.sp
                        )
                    }
                }
            }
        }
    }
}

fun archiveDisplayTitle(collection: SpaceCollection?, series: SpaceSeries?): String = when {
    collection != null -> "合集·${collection.title}"
    series != null -> "系列·${series.title}"
    else -> ""
}

@Composable
fun SpaceCollectionCard(
    title: String,
    cover: String,
    videoCount: Int,
    typeLabel: String,
    updateTime: Long?,
    modifier: Modifier = Modifier,
    badgeText: String? = null,
    onClick: () -> Unit,
    onFocus: () -> Unit
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { if (it.isFocused) onFocus() },
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(shape = MaterialTheme.shapes.medium)
    ) {
        Column {
            if (cover.isNotBlank()) {
                Box {
                    AsyncImage(
                        model = cover.resizedImageUrl(ImageSize.CollectionCardCover),
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(16f / 9f),
                        contentScale = ContentScale.Crop
                    )
                    DynamicBadge(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(4.dp),
                        text = badgeText ?: "$typeLabel · $videoCount 个视频"
                    )
                }
            }
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium.copy(fontSize = 15.sp))
                val updateText = remember(updateTime) { updateTime?.toSmartDate() }
                if (updateText != null) {
                    Text(
                        text = "$updateText 更新",
                        color = LocalContentColor.current.copy(alpha = 0.6f),
                        fontSize = 13.sp
                    )
                }
            }
        }
    }
}

@Composable
fun SpaceContentStatus(
    loading: Boolean,
    error: String?,
    hasMore: Boolean,
    onRetry: () -> Unit,
    onFocused: () -> Unit = {}
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp)
            .onFocusChanged { if (it.hasFocus) onFocused() },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // 每种状态只占一行：加载中再挂一个「加载更多」按钮会让底部出现两条重复的提示
        when {
            error != null -> {
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
                SpaceContentStatusButton(text = "重试", onClick = onRetry)
            }

            loading -> Row(
                modifier = Modifier.focusable(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // 加载态必须持续产生帧：静止的纯文本在部分机型上会让「加载中 → 内容」那一帧被丢掉，
                // 页面就停在「加载中…」直到按一下遥控器才刷新（详见 ArticleStatusBox 的说明）
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
                Text(
                    text = "加载中…",
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            }

            hasMore -> SpaceContentStatusButton(text = "加载更多", onClick = onRetry)

            // 到底文案也要可聚焦：列表到底后按 DOWN 才能把这一行滚进视口（否则会被网格末尾的 DOWN 守卫吞掉）
            else -> Text(
                modifier = Modifier.focusable(),
                text = "没有更多内容",
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
        }
    }
}

@Composable
private fun SpaceContentStatusButton(text: String, onClick: () -> Unit) {
    Surface(onClick = onClick) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
        )
    }
}
