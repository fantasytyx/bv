package dev.aaa1115910.bv.tv.screens.user

import android.view.KeyEvent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import dev.aaa1115910.biliapi.entity.season.FollowingSeason
import dev.aaa1115910.biliapi.entity.season.FollowingSeasonStatus
import dev.aaa1115910.biliapi.entity.season.FollowingSeasonType
import dev.aaa1115910.bv.R
import dev.aaa1115910.bv.tv.component.CardActionMenu
import dev.aaa1115910.bv.tv.component.CardActionMenuItem
import dev.aaa1115910.bv.tv.component.TvAlertDialog
import dev.aaa1115910.bv.tv.component.videocard.SeasonCard
import dev.aaa1115910.bv.entity.carddata.SeasonCardData
import dev.aaa1115910.bv.entity.proxy.ProxyArea
import dev.aaa1115910.bv.tv.activities.video.SeasonInfoActivity
import dev.aaa1115910.bv.tv.util.blockDownFocusExitAtGridEnd
import dev.aaa1115910.bv.tv.util.onMenuKeyDown
import dev.aaa1115910.bv.tv.util.ProvideListBringIntoViewSpec
import dev.aaa1115910.bv.util.ImageSize
import dev.aaa1115910.bv.util.fInfo
import dev.aaa1115910.bv.util.getDisplayName
import dev.aaa1115910.bv.util.requestFocus
import dev.aaa1115910.bv.util.resizedImageUrl
import dev.aaa1115910.bv.viewmodel.user.FollowingDramaViewModel
import dev.aaa1115910.bv.viewmodel.user.FollowingSeasonViewModel
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel

@Composable
fun FollowingSeasonScreen(
    modifier: Modifier = Modifier,
    followingSeasonViewModel: FollowingSeasonViewModel = koinViewModel(),
    showPageTitle: Boolean = true,
    topTabFocusRequester: FocusRequester? = null,
    followingSeasonType: FollowingSeasonType = FollowingSeasonType.Bangumi
) {
    val context = LocalContext.current
    val logger = KotlinLogging.logger { }
    val scope = rememberCoroutineScope()

    var currentIndex by remember { mutableIntStateOf(0) }
    val showLargeTitle by remember { derivedStateOf { currentIndex < 6 } }
    val titleFontSize by animateFloatAsState(
        targetValue = if (showLargeTitle) 48f else 24f,
        label = "title font size"
    )
    val subtitleFontSize by animateFloatAsState(
        targetValue = if (showLargeTitle) 36f else 24f,
        label = "subtitle font size"
    )

    var showFilter by remember { mutableStateOf(false) }
    var showActionMenu by remember { mutableStateOf(false) }

    var deleteMode by remember { mutableStateOf(false) }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }
    var selectedSeason by remember { mutableStateOf<FollowingSeason?>(null) }
    var selectedIndex by remember { mutableIntStateOf(0) }
    var focusTopTabWhenListEmpty by remember { mutableStateOf(false) }

    val focusRequesters = remember { mutableMapOf<Int, FocusRequester>() }
    fun getFocusRequester(index: Int): FocusRequester {
        return focusRequesters.getOrPut(index) { FocusRequester() }
    }

    val followingSeasons = followingSeasonViewModel.followingSeasons
    var followingSeasonStatus by remember { mutableStateOf(followingSeasonViewModel.followingSeasonStatus) }
    val noMore = followingSeasonViewModel.noMore

    val updateStatus: (FollowingSeasonStatus) -> Unit = {
        if (followingSeasonStatus != it) {
            followingSeasonStatus = it
            followingSeasonViewModel.followingSeasonStatus = it
            followingSeasonViewModel.clearData()
            followingSeasonViewModel.loadMore()
        }
    }

    val onLongClickSeason: (FollowingSeason, Int) -> Unit = { season, index ->
        if (deleteMode) {
            if (topTabFocusRequester != null) {
                focusTopTabWhenListEmpty = true
            }
            val nextIndex = if (index < followingSeasons.size - 1) index + 1 else index - 1
            if (nextIndex >= 0) runCatching { getFocusRequester(nextIndex).requestFocus() }
            followingSeasonViewModel.unfollowSeason(seasonId = season.seasonId)
        } else {
            showActionMenu = true
        }
    }

    LaunchedEffect(followingSeasonType) {
        if (followingSeasonViewModel.followingSeasonType != followingSeasonType) {
            followingSeasonViewModel.followingSeasonType = followingSeasonType
            followingSeasonViewModel.clearData()
        }
        if (followingSeasons.isEmpty()) {
            logger.fInfo { "Start update search result because filter updated" }
            followingSeasonViewModel.loadMore()
        }
    }

    LaunchedEffect(followingSeasonViewModel.deleting, followingSeasons.size, focusTopTabWhenListEmpty) {
        if (!focusTopTabWhenListEmpty || followingSeasonViewModel.deleting) return@LaunchedEffect
        focusTopTabWhenListEmpty = false
        if (followingSeasons.isEmpty()) {
            deleteMode = false
            topTabFocusRequester?.requestFocus(scope)
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            if (showPageTitle) {
                Box(
                    modifier = Modifier.padding(
                        start = 48.dp,
                        top = 24.dp,
                        bottom = 8.dp,
                        end = 48.dp
                    )
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.Bottom
                        ) {
                            Text(
                                text = stringResource(R.string.title_activity_following_season),
                                fontSize = titleFontSize.sp
                            )
                            Text(
                                text = followingSeasonType.getDisplayName(context),
                                fontSize = subtitleFontSize.sp
                            )
                            Text(
                                text = "(${followingSeasonStatus.getDisplayName(context)})",
                                fontSize = subtitleFontSize.sp
                            )
                        }
                        Column(
                            horizontalAlignment = Alignment.End,
                        ) {
                            Text(
                                text = if (deleteMode) stringResource(R.string.delete_mode_action_hint) else stringResource(R.string.following_season_hint),
                                color = if (deleteMode) Color.Red.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                fontSize = 11.sp
                            )
                            if (noMore) {
                                Text(
                                    text = stringResource(
                                        R.string.load_data_count_no_more,
                                        followingSeasonViewModel.followingSeasons.size
                                    ),
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                    fontSize = 11.sp
                                )
                            } else {
                                Text(
                                    text = stringResource(
                                        R.string.load_data_count,
                                        followingSeasonViewModel.followingSeasons.size
                                    ),
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(end = 24.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    Text(
                        text = if (deleteMode) stringResource(R.string.delete_mode_action_hint) else stringResource(R.string.following_season_hint),
                        color = if (deleteMode) Color.Red.copy(alpha = 0.8f) else androidx.tv.material3.MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        fontSize = 11.sp
                    )
                }
            }
        }
    ) { innerPadding ->
        ProvideListBringIntoViewSpec {
            LazyVerticalGrid(
                modifier = Modifier
                    .padding(innerPadding)
                    .focusRestorer()
                    .blockDownFocusExitAtGridEnd(
                        currentIndexProvider = { currentIndex },
                        itemCount = followingSeasons.size,
                        columnCount = 6
                    )
                    .onMenuKeyDown {
                        if (!deleteMode) showActionMenu = true
                    }
                    .onPreviewKeyEvent { keyEvent ->
                        if (deleteMode && keyEvent.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_BACK) {
                            if (keyEvent.nativeKeyEvent.action == KeyEvent.ACTION_UP) {
                                deleteMode = false
                            }
                            return@onPreviewKeyEvent true
                        }
                        false
                    },
                columns = GridCells.Fixed(6),
                contentPadding = PaddingValues(24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                itemsIndexed(
                    items = followingSeasons,
                    key = { _, followingSeason -> "season-${followingSeason.seasonId}" }
                ) { index, followingSeason ->
                    SeasonCard(
                        modifier = Modifier.focusRequester(getFocusRequester(index)),
                        data = SeasonCardData(
                            seasonId = followingSeason.seasonId,
                            title = followingSeason.title,
                            cover = followingSeason.cover.resizedImageUrl(ImageSize.SeasonCoverThumbnail),
                            rating = null
                        ),
                        onFocus = {
                            currentIndex = index
                            if (index + 12 > followingSeasons.size) {
                                println("load more by focus")
                                followingSeasonViewModel.loadMore()
                            }
                        },
                        onClick = {
                            if (deleteMode) {
                                selectedSeason = followingSeason
                                selectedIndex = index
                                showDeleteConfirmDialog = true
                            } else {
                                SeasonInfoActivity.actionStart(
                                    context = context,
                                    seasonId = followingSeason.seasonId,
                                    proxyArea = ProxyArea.checkProxyArea(followingSeason.title)
                                )
                            }
                        },
                        onLongClick = { onLongClickSeason(followingSeason, index) }
                    )
                }
                if (followingSeasons.isEmpty() && noMore) {
                    item(
                        span = { GridItemSpan(6) }
                    ) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(text = stringResource(R.string.no_data))
                                OutlinedButton(onClick = { showFilter = true }) {
                                    Text(text = stringResource(R.string.filter_dialog_open_tip_click))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    FollowingSeasonFilter(
        show = showFilter,
        onHideFilter = { showFilter = false },
        selectedStatus = followingSeasonStatus,
        onSelectedStatusChange = updateStatus
    )

    // 卡片操作菜单：长按确认键或按菜单键打开
    CardActionMenu(
        show = showActionMenu,
        onDismiss = { showActionMenu = false },
        items = listOf(
            CardActionMenuItem(icon = Icons.Rounded.Tune, text = "筛选") { showFilter = true },
            CardActionMenuItem(icon = Icons.Rounded.DeleteSweep, text = "批量删除") { deleteMode = true }
        )
    )

    if (showDeleteConfirmDialog && selectedSeason != null) {
        val typeName = when (followingSeasonType) {
            FollowingSeasonType.Bangumi -> "番"
            FollowingSeasonType.Cinema -> "剧"
        }
        DeleteFollowingSeasonConfirmDialog(
            show = showDeleteConfirmDialog,
            seasonTitle = selectedSeason!!.title,
            typeName = typeName,
            onConfirm = {
                if (topTabFocusRequester != null) {
                    focusTopTabWhenListEmpty = true
                }
                val nextIndex = if (selectedIndex < followingSeasons.size - 1) selectedIndex + 1 else selectedIndex - 1
                if (nextIndex >= 0) runCatching { getFocusRequester(nextIndex).requestFocus() }
                followingSeasonViewModel.unfollowSeason(seasonId = selectedSeason!!.seasonId)
                showDeleteConfirmDialog = false
                selectedSeason = null
            },
            onDismiss = {
                showDeleteConfirmDialog = false
                scope.launch {
                    runCatching { getFocusRequester(selectedIndex).requestFocus() }
                }
                selectedSeason = null
            }
        )
    }
}

@Composable
private fun DeleteFollowingSeasonConfirmDialog(
    show: Boolean,
    seasonTitle: String,
    typeName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(show) {
        if (show) focusRequester.requestFocus()
    }

    TvAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "取消追$typeName") },
        text = {
            Text(
                text = "确认取消追$typeName「$seasonTitle」吗？"
            )
        },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text(text = stringResource(R.string.following_season_delete_confirm_dialog_confirm))
            }
        },
        dismissButton = {
            OutlinedButton(
                modifier = Modifier.focusRequester(focusRequester),
                onClick = onDismiss
            ) {
                Text(text = stringResource(R.string.following_season_delete_confirm_dialog_dismiss))
            }
        }
    )
}

@Composable
fun FollowingDramaScreen(
    modifier: Modifier = Modifier,
    showPageTitle: Boolean = true,
    topTabFocusRequester: FocusRequester? = null
) {
    val viewModel: FollowingDramaViewModel = koinViewModel()
    FollowingSeasonScreen(
        modifier = modifier,
        followingSeasonViewModel = viewModel,
        showPageTitle = showPageTitle,
        topTabFocusRequester = topTabFocusRequester,
        followingSeasonType = FollowingSeasonType.Cinema
    )
}