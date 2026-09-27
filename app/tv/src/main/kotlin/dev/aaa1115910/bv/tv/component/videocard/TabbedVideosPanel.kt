package dev.aaa1115910.bv.tv.component.videocard

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Tab
import androidx.tv.material3.TabDefaults
import androidx.tv.material3.TabRow
import androidx.tv.material3.Text
import dev.aaa1115910.bv.entity.carddata.VideoCardData
import dev.aaa1115910.bv.tv.component.VideoActionMenu
import dev.aaa1115910.bv.tv.util.onMenuKeyDown
import dev.aaa1115910.bv.tv.util.stableItemKey

@Composable
fun TabbedVideosPanel(
    modifier: Modifier = Modifier,
    relatedVideos: List<VideoCardData>,
    preloadedVideos: List<VideoCardData>,
    currentAid: Long,
    focusRequester: FocusRequester,
    onOpenSeasonInfo: (VideoCardData, Boolean) -> Unit = { _, _ -> },
    onOpenVideoInfo: (VideoCardData, Boolean) -> Unit = { _, _ -> },
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    var selectedTabIndex by remember { mutableIntStateOf(0) }
    var hasFocus by remember { mutableStateOf(false) }
    val titleFontSize by animateFloatAsState(
        targetValue = 24f,
        label = "title font size",
        animationSpec = tween(durationMillis = 120)
    )
    var rowHeight by remember { mutableStateOf(0.dp) }
    var currentIndex by remember { mutableIntStateOf(0) }

    // Build tabs: always show "推荐视频", show "视频列表" only when preloaded is not empty
    val tabs = remember(relatedVideos.size, preloadedVideos.size) {
        buildList {
            add("推荐视频" to relatedVideos)
            if (preloadedVideos.isNotEmpty()) {
                add("列表视频" to preloadedVideos)
            }
        }
    }

    // Clamp selectedTabIndex
    LaunchedEffect(tabs.size) {
        if (selectedTabIndex >= tabs.size) selectedTabIndex = 0
    }

    val currentVideos = tabs.getOrNull(selectedTabIndex)?.second ?: emptyList()

    // Find index of current video in the preloaded list tab
    val currentVideoIndexInPreloaded = remember(preloadedVideos, currentAid) {
        preloadedVideos.indexOfFirst { it.avid == currentAid }
    }

    val relatedListState = rememberLazyListState()
    val preloadedListState = rememberLazyListState()

    // When switching to "视频列表" tab and current video is in the list, scroll to it
    val isPreloadedTab = tabs.size > 1 && selectedTabIndex == 1
    val lazyListState = if (isPreloadedTab) preloadedListState else relatedListState
    LaunchedEffect(selectedTabIndex) {
        if (isPreloadedTab && currentVideoIndexInPreloaded >= 0) {
            preloadedListState.scrollToItem(currentVideoIndexInPreloaded)
        }
    }

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
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color.Transparent,
                        Color.Black.copy(alpha = 0.7f)
                    )
                )
            )
    ) {
        // Tab row - only show if more than one tab
        if (tabs.size > 1) {
            TabRow(
                modifier = Modifier.padding(start = 36.dp, top = 3.dp, bottom = 3.dp),
                selectedTabIndex = selectedTabIndex,
                separator = { Text("  ") }
            ) {
                tabs.forEachIndexed { index, (title, _) ->
                    Tab(
                        selected = selectedTabIndex == index,
                        onFocus = { selectedTabIndex = index },
                        onClick = { selectedTabIndex = index },
                        colors = TabDefaults.pillIndicatorTabColors(),
                    ) {
                        Text(
                            text = title,
                            fontSize = titleFontSize.sp,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
                        )
                    }
                }
            }
        } else {
            Text(
                modifier = Modifier.padding(start = 36.dp, top = 3.dp, bottom = 3.dp),
                text = tabs.firstOrNull()?.first ?: "",
                fontSize = titleFontSize.sp
            )
        }
        LazyRow(
            modifier = Modifier
                .padding(vertical = 15.dp)
                .onGloballyPositioned {
                    rowHeight = with(density) { it.size.height.toDp() }
                }
                .focusRestorer()
                .onMenuKeyDown {
                    currentVideos.getOrNull(currentIndex)?.let(onLongClickVideo)
                },
            state = lazyListState,
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            contentPadding = PaddingValues(horizontal = 36.dp)
        ) {
            itemsIndexed(
                items = currentVideos,
                key = { index, videoData -> "${selectedTabIndex}-${index}-${videoData.stableItemKey()}" }
            ) { index, videoData ->
                val isCurrentVideo = isPreloadedTab && index == currentVideoIndexInPreloaded
                SmallVideoCard(
                    modifier = Modifier
                        .width(200.dp)
                        .then(if (index == 0) Modifier.focusRequester(focusRequester) else Modifier),
                    data = videoData,
                    unfocusedBorderColor = if (isCurrentVideo) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f) else null,
                    onClick = {
                        val fromUGCList = selectedTabIndex == 1
                        if (videoData.jumpToSeason) {
                            onOpenSeasonInfo(videoData, fromUGCList)
                        } else {
                            onOpenVideoInfo(videoData, fromUGCList)
                        }
                    },
                    onLongClick = { onLongClickVideo(videoData) },
                    onFocus = { currentIndex = index }
                )
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
