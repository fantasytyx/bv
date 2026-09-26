package dev.aaa1115910.bv.tv.screens.main.home

import android.content.Intent
import android.view.KeyEvent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import dev.aaa1115910.biliapi.entity.user.DynamicVideo
import dev.aaa1115910.bv.R as SharedR
import dev.aaa1115910.bv.tv.component.LoadingTip
import dev.aaa1115910.bv.entity.carddata.VideoCardData
import dev.aaa1115910.bv.entity.proxy.ProxyArea
import dev.aaa1115910.bv.tv.R
import dev.aaa1115910.bv.tv.activities.user.FollowActivity
import dev.aaa1115910.bv.tv.activities.video.SeasonInfoActivity
import dev.aaa1115910.bv.tv.activities.video.VideoInfoActivity
import dev.aaa1115910.bv.tv.component.VideoActionMenu
import dev.aaa1115910.bv.tv.component.videocard.SmallVideoCard
import dev.aaa1115910.bv.tv.util.blockDownFocusExitAtGridEnd
import dev.aaa1115910.bv.tv.util.ProvideListBringIntoViewSpec
import dev.aaa1115910.bv.repository.VideoInfoRepository
import dev.aaa1115910.bv.viewmodel.home.DynamicViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

@Composable
fun DynamicsScreen(
    modifier: Modifier = Modifier,
    lazyGridState: LazyGridState = rememberLazyGridState(),
    dynamicViewModel: DynamicViewModel = koinViewModel()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val videoInfoRepository: VideoInfoRepository = koinInject()
    var currentFocusedIndex by remember { mutableIntStateOf(-1) }
    val shouldLoadMore by remember {
        derivedStateOf { dynamicViewModel.dynamicVideoList.isNotEmpty() && currentFocusedIndex + 12 > dynamicViewModel.dynamicVideoList.size }
    }
    val onClickVideo: (DynamicVideo) -> Unit = { dynamic ->
        val proxyArea = ProxyArea.checkProxyArea(dynamic.title)
        val hasSeasonHint = dynamic.seasonId != null || dynamic.epid != null

        videoInfoRepository.setPreloadedVideoList(
            dynamicViewModel.dynamicVideoList.map { item ->
                VideoCardData(
                    avid = item.aid,
                    title = item.title,
                    cover = item.cover,
                    upName = item.author,
                    upId = item.authorId,
                    play = item.play,
                    danmaku = item.danmaku,
                    time = item.duration * 1000L,
                    pubTime = item.pubTime
                )
            }
        )

        if (hasSeasonHint) {
            SeasonInfoActivity.actionStart(
                context = context,
                epId = dynamic.epid,
                seasonId = dynamic.seasonId,
                proxyArea = proxyArea
            )
        } else {
            VideoInfoActivity.actionStart(
                context = context,
                aid = dynamic.aid,
                proxyArea = proxyArea
            )
        }
    }

    // 长按菜单状态
    var showVideoActionMenu by remember { mutableStateOf(false) }
    var menuAid by remember { mutableLongStateOf(0L) }
    var menuUpId by remember { mutableLongStateOf(0L) }
    var menuUpName by remember { mutableStateOf("") }
    var menuUpFace by remember { mutableStateOf("") }

    val onLongClickVideo: (DynamicVideo) -> Unit = { dynamic ->
        menuAid = dynamic.aid
        menuUpId = dynamic.authorId
        menuUpName = dynamic.author
        menuUpFace = dynamic.authorFace
        showVideoActionMenu = true
    }

    //不能直接使用 LaunchedEffect(currentFocusedIndex)，会导致整个页面重组
    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore) {
            scope.launch(Dispatchers.IO) {
                dynamicViewModel.loadMoreVideo()
            }
        }
    }

    if (dynamicViewModel.isLogin) {
        val padding = dimensionResource(R.dimen.grid_padding)
        val spacedBy = dimensionResource(R.dimen.grid_spacedBy)
        Text(
            modifier = Modifier.fillMaxWidth().offset(x = (-20).dp, y = (-8).dp),
            text = stringResource(R.string.entry_follow_screen),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            fontSize = 11.sp,
            textAlign = TextAlign.End
        )
        ProvideListBringIntoViewSpec {
            LazyVerticalGrid(
                modifier = modifier.fillMaxSize().focusRestorer()
                    .blockDownFocusExitAtGridEnd(
                        currentIndexProvider = { currentFocusedIndex },
                        itemCount = dynamicViewModel.dynamicVideoList.size,
                        columnCount = 4
                    )
                    .onFocusChanged{
                        if (!it.isFocused) {
                            currentFocusedIndex = -1
                        }
                    }
                    .onPreviewKeyEvent { keyEvent ->
                        if (
                            keyEvent.nativeKeyEvent.action == KeyEvent.ACTION_UP &&
                            keyEvent.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_MENU
                        ) {
                            context.startActivity(Intent(context, FollowActivity::class.java))
                            return@onPreviewKeyEvent true
                        }
                        false
                    },
                columns = GridCells.Fixed(4),
                state = lazyGridState,
                contentPadding = PaddingValues(padding),
                verticalArrangement = Arrangement.spacedBy(spacedBy),
                horizontalArrangement = Arrangement.spacedBy(spacedBy)
            ) {
                itemsIndexed(
                    items = dynamicViewModel.dynamicVideoList,
                    key = { index, item -> "$index-av-${item.aid}" }
                ) { index, item ->
                    SmallVideoCard(
                        data = remember(item.aid) {
                            VideoCardData(
                                avid = item.aid,
                                title = item.title,
                                cover = item.cover,
                                play = item.play,
                                danmaku = item.danmaku,
                                upName = item.author,
                                time = item.duration * 1000L,
                                pubTime = item.pubTime,
                                isChargingArc = item.isChargingArc,
                                badgeText = item.chargingArcBadge
                            )
                        },
                        onClick = { onClickVideo(item) },
                        onLongClick = {onLongClickVideo(item) },
                        onFocus = { currentFocusedIndex = index }
                    )
                }

                if (
                    dynamicViewModel.dynamicVideoList.isEmpty() &&
                    !dynamicViewModel.loadingVideo &&
                    !dynamicViewModel.videoHasMore
                ) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = stringResource(SharedR.string.no_data),
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }
                    }
                }

                if (dynamicViewModel.loadingVideo) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            LoadingTip()
                        }
                    }
                }

                if (!dynamicViewModel.videoHasMore && dynamicViewModel.dynamicVideoList.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            text = "没有更多了捏",
                            color = Color.White
                        )
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
    } else {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(text = "请先登录")
        }
    }
}
