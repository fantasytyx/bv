package dev.aaa1115910.bv.tv.screens.main.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.unit.dp
import dev.aaa1115910.biliapi.entity.ugc.UgcItem
import dev.aaa1115910.bv.tv.component.LoadingTip
import dev.aaa1115910.bv.entity.carddata.VideoCardData
import dev.aaa1115910.bv.tv.R
import dev.aaa1115910.bv.tv.activities.video.UpInfoActivity
import dev.aaa1115910.bv.tv.activities.video.VideoInfoActivity
import dev.aaa1115910.bv.tv.component.VideoActionMenu
import dev.aaa1115910.bv.tv.component.videocard.SmallVideoCard
import dev.aaa1115910.bv.tv.util.blockDownFocusExitAtGridEnd
import dev.aaa1115910.bv.tv.util.onMenuKeyDown
import dev.aaa1115910.bv.tv.util.ProvideListBringIntoViewSpec
import dev.aaa1115910.bv.repository.VideoInfoRepository
import dev.aaa1115910.bv.viewmodel.home.RecommendViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

@Composable
fun RecommendScreen(
    modifier: Modifier = Modifier,
    lazyGridState: LazyGridState = rememberLazyGridState(),
    recommendViewModel: RecommendViewModel = koinViewModel()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val videoInfoRepository: VideoInfoRepository = koinInject()
    var currentFocusedIndex by remember { mutableIntStateOf(0) }
    val shouldLoadMore by remember {
        derivedStateOf { recommendViewModel.recommendVideoList.isNotEmpty() && currentFocusedIndex + 12 > recommendViewModel.recommendVideoList.size }
    }

    val onClickVideo: (UgcItem) -> Unit = { ugcItem ->
        videoInfoRepository.setPreloadedVideoList(
            recommendViewModel.recommendVideoList.map { item ->
                VideoCardData(
                    avid = item.aid,
                    title = item.title,
                    cover = item.cover,
                    upName = item.author,
                    upId = item.authorId,
                    play = if (item.play == -1L) null else item.play,
                    danmaku = if (item.danmaku == -1) null else item.danmaku,
                    time = item.duration * 1000L,
                    pubTime = item.pubTime,
                    isInteractive = item.isInteractive
                )
            }
        )
        VideoInfoActivity.actionStart(context, ugcItem.aid)
    }

    // 长按菜单状态
    var showVideoActionMenu by remember { mutableStateOf(false) }
    var menuAid by remember { mutableLongStateOf(0L) }
    var menuUpId by remember { mutableLongStateOf(0L) }
    var menuUpName by remember { mutableStateOf("") }
    var menuUpFace by remember { mutableStateOf("") }

    val onLongClickVideo: (UgcItem) -> Unit = { ugcItem ->
        menuAid = ugcItem.aid
        menuUpId = ugcItem.authorId
        menuUpName = ugcItem.author
        menuUpFace = ugcItem.authorFace
        showVideoActionMenu = true
    }

    //不能直接使用 LaunchedEffect(currentFocusedIndex)，会导致整个页面重组
    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore) {
            scope.launch(Dispatchers.IO) {
                recommendViewModel.loadMore()
            }
        }
    }

    val padding = dimensionResource(R.dimen.grid_padding)
    val spacedBy = dimensionResource(R.dimen.grid_spacedBy)
    ProvideListBringIntoViewSpec {
        LazyVerticalGrid(
            modifier = modifier
                .fillMaxSize()
                .focusRestorer()
                .blockDownFocusExitAtGridEnd(
                    currentIndexProvider = { currentFocusedIndex },
                    itemCount = recommendViewModel.recommendVideoList.size,
                    columnCount = 4
                )
                .onMenuKeyDown {
                    recommendViewModel.recommendVideoList.getOrNull(currentFocusedIndex)?.let(onLongClickVideo)
                },
            columns = GridCells.Fixed(4),
            state = lazyGridState,
            contentPadding = PaddingValues(padding),
            verticalArrangement = Arrangement.spacedBy(spacedBy),
            horizontalArrangement = Arrangement.spacedBy(spacedBy)
        ) {
            itemsIndexed(
                items = recommendViewModel.recommendVideoList,
                key = { index, item -> "$index-av-${item.aid}" }
            ) { index, item ->
                SmallVideoCard(
                    data = remember(item.aid) {
                        VideoCardData(
                            avid = item.aid,
                            title = item.title,
                            cover = item.cover,
                            play = with(item.play) { if (this == -1L) null else this },
                            danmaku = with(item.danmaku) { if (this == -1) null else this },
                            upName = item.author,
                            time = item.duration * 1000L,
                            pubTime = item.pubTime,
                            isInteractive = item.isInteractive
                        )
                    },
                    onClick = { onClickVideo(item) },
                    onLongClick = {onLongClickVideo(item) },
                    onFocus = { currentFocusedIndex = index }
                )
            }

            if (recommendViewModel.loading) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        LoadingTip()
                    }
                }
            }
        }
    }

    // 长按操作菜单
    VideoActionMenu(
        show = showVideoActionMenu,
        aid = menuAid,
        upId = menuUpId,
        upName = menuUpName,
        upFace = menuUpFace,
        onDismiss = { showVideoActionMenu = false }
    )
}