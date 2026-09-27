package dev.aaa1115910.bv.tv.component.videocard

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.aaa1115910.bv.entity.carddata.VideoCardData
import dev.aaa1115910.bv.tv.component.VideoActionMenu
import dev.aaa1115910.bv.tv.util.onMenuKeyDown
import dev.aaa1115910.bv.tv.util.stableItemKey

@Composable
fun VideosRow(
    modifier: Modifier = Modifier,
    header: String,
    hideShowMore: Boolean = true,
    videos: List<VideoCardData>,
    showMore: () -> Unit,
    onOpenSeasonInfo: (VideoCardData) -> Unit = {},
    onOpenVideoInfo: (VideoCardData) -> Unit = {},
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    var hasFocus by remember { mutableStateOf(false) }
    val titleFontSize by animateFloatAsState(
        targetValue = if (hasFocus) 30f else 14f,
        label = "title font size",
        animationSpec = tween(
            durationMillis = 120
        )
    )
    var rowHeight by remember { mutableStateOf(0.dp) }
    var currentIndex by remember { mutableIntStateOf(0) }

    // 长按菜单状态
    var showVideoActionMenu by remember { mutableStateOf(false) }
    var menuAid by remember { mutableLongStateOf(0L) }
    var menuUpId by remember { mutableLongStateOf(0L) }
    var menuUpName by remember { mutableStateOf("") }
    var menuUpFace by remember { mutableStateOf("") }

    val onLongClickVideo: (VideoCardData) -> Unit = { videoCard ->
        menuAid = videoCard.avid
        menuUpId = videoCard.upId
        menuUpName = videoCard.upName
        menuUpFace = videoCard.upFace
        showVideoActionMenu = true
    }

    Column(
        modifier = modifier
            .onFocusChanged { hasFocus = it.hasFocus }
    ) {
        Text(
            modifier = Modifier.padding(start = 36.dp, top = 3.dp, bottom = 3.dp),
            text = header,
            fontSize = titleFontSize.sp
        )
        LazyRow(
            modifier = Modifier
                .padding(vertical = 15.dp)
                .onGloballyPositioned {
                    rowHeight = with(density) {
                        it.size.height.toDp()
                    }
                }
                .focusRestorer()
                .onMenuKeyDown {
                    videos.getOrNull(currentIndex)?.let(onLongClickVideo)
                },
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            contentPadding = PaddingValues(horizontal = 36.dp)
        ) {
            itemsIndexed(
                items = videos,
                key = { index, videoData -> "$index-${videoData.stableItemKey()}" }
            ) { index, videoData ->
                SmallVideoCard(
                    modifier = Modifier.width(200.dp),
                    data = videoData,
                    onClick = {
                        if (videoData.jumpToSeason) {
                            onOpenSeasonInfo(videoData)
                        } else {
                            onOpenVideoInfo(videoData)
                        }
                    },
                    onLongClick = { onLongClickVideo(videoData) },
                    onFocus = { currentIndex = index }
                )
            }
            if (!hideShowMore) {
                item {
                    Button(
                        modifier = Modifier.height(rowHeight),
                        shape = ButtonDefaults.shape(shape = MaterialTheme.shapes.medium),
                        onClick = showMore
                    ) {
                        Column(
                            modifier = Modifier.fillMaxHeight(),
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text(text = "显示更多")
                        }
                    }
                }
            }
        }
    }

    VideoActionMenu(
        show = showVideoActionMenu,
        aid = menuAid,
        upId = menuUpId,
        upName = menuUpName,
        upFace = menuUpFace,
        onDismiss = { showVideoActionMenu = false }
    )
}
