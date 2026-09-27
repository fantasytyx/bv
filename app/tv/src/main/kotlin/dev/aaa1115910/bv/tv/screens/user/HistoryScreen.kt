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
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material3.Scaffold
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
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import dev.aaa1115910.bv.R
import dev.aaa1115910.bv.entity.carddata.VideoCardData
import dev.aaa1115910.bv.entity.proxy.ProxyArea
import dev.aaa1115910.bv.tv.activities.video.SeasonInfoActivity
import dev.aaa1115910.bv.tv.activities.video.VideoInfoActivity
import dev.aaa1115910.bv.tv.component.TvAlertDialog
import dev.aaa1115910.bv.tv.component.VideoActionMenu
import dev.aaa1115910.bv.tv.component.CardActionMenuItem
import dev.aaa1115910.bv.tv.component.VideoActionMenuExtraItem
import dev.aaa1115910.bv.tv.component.videocard.SmallVideoCard
import dev.aaa1115910.bv.tv.util.blockDownFocusExitAtGridEnd
import dev.aaa1115910.bv.tv.util.onMenuKeyDown
import dev.aaa1115910.bv.tv.util.ProvideListBringIntoViewSpec
import dev.aaa1115910.bv.tv.util.stableItemKey
import dev.aaa1115910.bv.util.requestFocus
import dev.aaa1115910.bv.repository.VideoInfoRepository
import dev.aaa1115910.bv.viewmodel.user.HistoryViewModel
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

@Composable
fun HistoryScreen(
    modifier: Modifier = Modifier,
    historyViewModel: HistoryViewModel = koinViewModel(),
    showPageTitle: Boolean = true,
    topTabFocusRequester: FocusRequester? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val videoInfoRepository: VideoInfoRepository = koinInject()
    val lazyGridState = rememberLazyGridState()
    var currentIndex by remember { mutableIntStateOf(0) }
    val showLargeTitle by remember { derivedStateOf { currentIndex < 4 } }
    val titleFontSize by animateFloatAsState(
        targetValue = if (showLargeTitle) 48f else 24f,
        label = "title font size"
    )

    var deleteMode by remember { mutableStateOf(false) }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }
    var showClearConfirmDialog by remember { mutableStateOf(false) }
    var selectedVideo by remember { mutableStateOf<VideoCardData?>(null) }
    var selectedIndex by remember { mutableIntStateOf(0) }
    var focusTopTabWhenListEmpty by remember { mutableStateOf(false) }

    // 长按菜单状态
    var showVideoActionMenu by remember { mutableStateOf(false) }
    var menuAid by remember { mutableLongStateOf(0L) }
    var menuUpId by remember { mutableLongStateOf(0L) }
    var menuUpName by remember { mutableStateOf("") }
    var menuUpFace by remember { mutableStateOf("") }
    var menuHistoryBusiness by remember { mutableStateOf<String?>(null) }
    var menuHistoryKid by remember { mutableLongStateOf(0L) }

    val focusRequesters = remember { mutableMapOf<Int, FocusRequester>() }
    fun getFocusRequester(index: Int): FocusRequester {
        return focusRequesters.getOrPut(index) { FocusRequester() }
    }

    val openVideoMenu: (VideoCardData) -> Unit = { video ->
        menuAid = video.avid
        menuUpId = video.upId
        menuUpName = video.upName
        menuUpFace = video.upFace
        menuHistoryBusiness = video.historyBusiness
        menuHistoryKid = video.historyKid ?: 0L
        showVideoActionMenu = true
    }

    LaunchedEffect(Unit) {
        if (historyViewModel.histories.isEmpty()) {
            historyViewModel.clearData()
            historyViewModel.update()
        }
    }

    LaunchedEffect(historyViewModel.deleting, historyViewModel.histories.size, focusTopTabWhenListEmpty) {
        if (!focusTopTabWhenListEmpty || historyViewModel.deleting) return@LaunchedEffect
        focusTopTabWhenListEmpty = false
        if (historyViewModel.histories.isEmpty()) {
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
                        Text(
                            text = stringResource(R.string.user_homepage_recent),
                            fontSize = titleFontSize.sp
                        )
                        if (historyViewModel.noMore) {
                            Text(
                                text = stringResource(
                                    R.string.load_data_count_no_more,
                                    historyViewModel.histories.size
                                ),
                                color = Color.White.copy(alpha = 0.6f)
                            )
                        } else {
                            Text(
                                text = stringResource(
                                    R.string.load_data_count,
                                    historyViewModel.histories.size
                                ),
                                color = Color.White.copy(alpha = 0.6f)
                            )
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding)) {
            Text(
                modifier = Modifier.fillMaxWidth().offset(x = (-20).dp, y = (-2).dp),
                text = if (deleteMode) stringResource(R.string.delete_mode_action_hint) else stringResource(R.string.delete_mode_hint),
                color = if (deleteMode) Color.Red.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                fontSize = 11.sp,
                textAlign = TextAlign.End
            )
            ProvideListBringIntoViewSpec(padding = 24.dp) {
                LazyVerticalGrid(
                    modifier = Modifier
                        .focusRestorer()
                        .blockDownFocusExitAtGridEnd(
                            currentIndexProvider = { currentIndex },
                            itemCount = historyViewModel.histories.size,
                            columnCount = 4
                        )
                        .onMenuKeyDown {
                            if (!deleteMode) {
                                historyViewModel.histories.getOrNull(currentIndex)?.let(openVideoMenu)
                            }
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
                    columns = GridCells.Fixed(4),
                    state = lazyGridState,
                    contentPadding = PaddingValues(
                        top = if (showPageTitle) 20.dp else 4.dp,
                        bottom = 20.dp,
                        start = 20.dp,
                        end = 20.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(13.dp)
                ) {
                    itemsIndexed(
                        items = historyViewModel.histories,
                        key = { _, history -> history.historyKid ?: history.hashCode() }
                    ) { index, history ->
                        Box(
                            contentAlignment = Alignment.Center
                        ) {
                            SmallVideoCard(
                                modifier = Modifier.focusRequester(getFocusRequester(index)),
                                data = history,
                                onClick = {
                                    if (deleteMode) {
                                        selectedVideo = history
                                        selectedIndex = index
                                        showDeleteConfirmDialog = true
                                    } else {
                                        videoInfoRepository.setPreloadedVideoList(historyViewModel.histories)
                                        if (history.jumpToSeason) {
                                            SeasonInfoActivity.actionStart(
                                                context = context,
                                                epId = history.epId,
                                                seasonId = history.seasonId,
                                                proxyArea = ProxyArea.checkProxyArea(history.title)
                                            )
                                        } else {
                                            VideoInfoActivity.actionStart(
                                                context = context,
                                                aid = history.avid,
                                                proxyArea = ProxyArea.checkProxyArea(history.title)
                                            )
                                        }
                                    }
                                },
                                onLongClick = {
                                    if (deleteMode) {
                                        selectedIndex = index
                                        showClearConfirmDialog = true
                                    } else {
                                        openVideoMenu(history)
                                    }
                                },
                                onFocus = {
                                    currentIndex = index
                                    //预加载
                                    if (index + 12 > historyViewModel.histories.size) {
                                        historyViewModel.update()
                                    }
                                }
                            )
                        }
                    }

                    if (historyViewModel.histories.isEmpty() && historyViewModel.noMore) {
                        item(span = { GridItemSpan(4) }) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = stringResource(R.string.no_data),
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showDeleteConfirmDialog && selectedVideo != null) {
        DeleteHistoryConfirmDialog(
            show = showDeleteConfirmDialog,
            videoTitle = selectedVideo!!.title,
            onConfirm = {
                if (topTabFocusRequester != null) {
                    focusTopTabWhenListEmpty = true
                }
                val nextIndex = if (selectedIndex < historyViewModel.histories.size - 1) selectedIndex + 1 else selectedIndex - 1
                if (nextIndex >= 0) runCatching { getFocusRequester(nextIndex).requestFocus() }
                historyViewModel.deleteHistory(
                    business = selectedVideo!!.historyBusiness,
                    kid = selectedVideo!!.historyKid
                )
                showDeleteConfirmDialog = false
                selectedVideo = null
            },
            onDismiss = {
                showDeleteConfirmDialog = false
                scope.launch {
                    runCatching { getFocusRequester(selectedIndex).requestFocus() }
                }
                selectedVideo = null
            }
        )
    }

    if (showClearConfirmDialog) {
        ClearHistoryConfirmDialog(
            show = showClearConfirmDialog,
            onConfirm = {
                if (topTabFocusRequester != null) {
                    focusTopTabWhenListEmpty = true
                }
                historyViewModel.clearHistory()
                deleteMode = false
                showClearConfirmDialog = false
            },
            onDismiss = {
                showClearConfirmDialog = false
                scope.launch {
                    runCatching { getFocusRequester(selectedIndex).requestFocus() }
                }
            }
        )
    }

    VideoActionMenu(
        show = showVideoActionMenu,
        aid = menuAid,
        upId = menuUpId,
        upName = menuUpName,
        upFace = menuUpFace,
        onDismiss = { showVideoActionMenu = false },
        extraItems = listOf(
            VideoActionMenuExtraItem(
                CardActionMenuItem(icon = Icons.Rounded.DeleteSweep, text = "批量删除") { deleteMode = true }
            )
        ),
        onDelete = {
            val nextIndex = if (currentIndex < historyViewModel.histories.size - 1) currentIndex + 1 else currentIndex - 1
            if (nextIndex >= 0) runCatching { getFocusRequester(nextIndex).requestFocus() }
            historyViewModel.deleteHistory(
                business = menuHistoryBusiness,
                kid = menuHistoryKid
            )
        }
    )
}

@Composable
private fun DeleteHistoryConfirmDialog(
    show: Boolean,
    videoTitle: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(show) {
        if (show) focusRequester.requestFocus()
    }

    TvAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.history_delete_confirm_dialog_title)) },
        text = {
            Text(
                text = stringResource(
                    R.string.history_delete_confirm_dialog_text,
                    videoTitle
                )
            )
        },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text(text = stringResource(R.string.history_delete_confirm_dialog_confirm))
            }
        },
        dismissButton = {
            OutlinedButton(
                modifier = Modifier.focusRequester(focusRequester),
                onClick = onDismiss
            ) {
                Text(text = stringResource(R.string.history_delete_confirm_dialog_dismiss))
            }
        }
    )
}

@Composable
private fun ClearHistoryConfirmDialog(
    show: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val focusRequester = remember { FocusRequester() }
    var consumeInitialConfirmKeyUp by remember { mutableStateOf(false) }

    fun handleInitialConfirmKeyUp(keyEvent: androidx.compose.ui.input.key.KeyEvent): Boolean {
        if (!consumeInitialConfirmKeyUp) return false
        val nativeKeyEvent = keyEvent.nativeKeyEvent
        val isConfirmKey = nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
            nativeKeyEvent.keyCode == KeyEvent.KEYCODE_ENTER ||
            nativeKeyEvent.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
        if (nativeKeyEvent.action == KeyEvent.ACTION_UP && isConfirmKey) {
            consumeInitialConfirmKeyUp = false
            return true
        }
        return false
    }

    LaunchedEffect(show) {
        if (show) {
            consumeInitialConfirmKeyUp = true
            focusRequester.requestFocus()
        }
    }

    TvAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.history_clear_confirm_dialog_title)) },
        text = {
            Text(text = stringResource(R.string.history_clear_confirm_dialog_text))
        },
        confirmButton = {
            Button(
                modifier = Modifier.onPreviewKeyEvent { handleInitialConfirmKeyUp(it) },
                onClick = onConfirm
            ) {
                Text(text = stringResource(R.string.history_delete_confirm_dialog_confirm))
            }
        },
        dismissButton = {
            OutlinedButton(
                modifier = Modifier
                    .focusRequester(focusRequester)
                    .onPreviewKeyEvent { handleInitialConfirmKeyUp(it) },
                onClick = onDismiss
            ) {
                Text(text = stringResource(R.string.history_delete_confirm_dialog_dismiss))
            }
        }
    )
}
