package dev.aaa1115910.bv.tv.screens.user

import android.content.Context
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.focus.onFocusChanged
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
import dev.aaa1115910.biliapi.entity.FavoriteFolderMetadata
import dev.aaa1115910.bv.R
import dev.aaa1115910.bv.entity.NavSwitchMode
import dev.aaa1115910.bv.entity.carddata.VideoCardData
import dev.aaa1115910.bv.entity.proxy.ProxyArea
import dev.aaa1115910.bv.repository.VideoInfoRepository
import dev.aaa1115910.bv.tv.activities.video.SeasonInfoActivity
import dev.aaa1115910.bv.tv.component.TvAlertDialog
import dev.aaa1115910.bv.tv.component.VideoActionMenu
import dev.aaa1115910.bv.tv.component.CardActionMenuItem
import dev.aaa1115910.bv.tv.component.VideoActionMenuExtraItem
import dev.aaa1115910.bv.tv.component.videocard.SmallVideoCard
import dev.aaa1115910.bv.tv.activities.video.VideoInfoActivity
import dev.aaa1115910.bv.tv.manager.VideoUserActionManager
import dev.aaa1115910.bv.tv.component.TopNav
import dev.aaa1115910.bv.tv.component.TopNavItem
import dev.aaa1115910.bv.tv.util.blockDownFocusExitAtGridEnd
import dev.aaa1115910.bv.tv.util.onMenuKeyDown
import dev.aaa1115910.bv.tv.util.ProvideListBringIntoViewSpec
import dev.aaa1115910.bv.tv.util.stableItemKey
import dev.aaa1115910.bv.util.Prefs
import dev.aaa1115910.bv.util.collectAsStateLazily
import dev.aaa1115910.bv.util.onDelayFocusChanged
import dev.aaa1115910.bv.util.requestFocus
import dev.aaa1115910.bv.util.toast
import dev.aaa1115910.bv.viewmodel.user.FavoriteViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.getKoin

@Composable
fun FavoriteScreen(
    modifier: Modifier = Modifier,
    favoriteViewModel: FavoriteViewModel = koinViewModel(),
    showPageTitle: Boolean = true
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val videoInfoRepository: VideoInfoRepository = getKoin().get()
    val navSwitchMode by Prefs.navSwitchModeFlow.collectAsStateLazily { Prefs.navSwitchMode }
    var currentIndex by remember { mutableIntStateOf(0) }
    val showLargeTitle by remember { derivedStateOf { currentIndex < 4 } }
    val titleFontSize by animateFloatAsState(
        targetValue = if (showLargeTitle) 48f else 24f,
        label = "title font size"
    )
    val focusRequester = remember { FocusRequester() }
    val defaultFocusRequester = remember { FocusRequester() }
    var focusOnTabs by remember { mutableStateOf(true) }
    var focusOnGrid by remember { mutableStateOf(false) }
    val lazyGridState = rememberLazyGridState()
    val favoriteTopNavItems = favoriteViewModel.favoriteFolderMetadataList.map(::FavoriteFolderTopNavItem)

    var deleteMode by remember { mutableStateOf(false) }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }
    var selectedVideo by remember { mutableStateOf<VideoCardData?>(null) }
    var selectedIndex by remember { mutableIntStateOf(0) }

    // 长按菜单状态
    var showVideoActionMenu by remember { mutableStateOf(false) }
    var menuAid by remember { mutableLongStateOf(0L) }
    var menuUpId by remember { mutableLongStateOf(0L) }
    var menuUpName by remember { mutableStateOf("") }
    var menuUpFace by remember { mutableStateOf("") }

    val focusRequesters = remember { mutableMapOf<Int, FocusRequester>() }
    fun getFocusRequester(index: Int): FocusRequester {
        return focusRequesters.getOrPut(index) { FocusRequester() }
    }

    val openVideoMenu: (VideoCardData) -> Unit = { video ->
        menuAid = video.avid
        menuUpId = video.upId
        menuUpName = video.upName
        menuUpFace = video.upFace
        showVideoActionMenu = true
    }

    val updateCurrentFavoriteFolder: (folderMetadata: FavoriteFolderMetadata) -> Unit =
        { folderMetadata ->
            favoriteViewModel.currentFavoriteFolderMetadata = folderMetadata
            favoriteViewModel.favorites.clear()
            favoriteViewModel.resetPageNumber()
            favoriteViewModel.updateFolderItems(force = true)
        }

    BackHandler(
        enabled = focusOnGrid && !deleteMode && !showPageTitle
    ) {
        scope.launch(Dispatchers.Main) {
            lazyGridState.scrollToItem(0)
            delay(100)
            focusOnGrid = false
            defaultFocusRequester.requestFocus()
        }
    }

    LaunchedEffect(Unit) {
        if (favoriteViewModel.favoriteFolderMetadataList.isEmpty()) {
            favoriteViewModel.clearData()
            favoriteViewModel.updateFoldersInfo()
            if (showPageTitle) {
                delay(100)
                defaultFocusRequester.requestFocus()
            }
        }
    }

    fun focusTopTabIfListEmpty() {
        if (favoriteViewModel.favorites.isEmpty()) {
            deleteMode = false
            focusOnGrid = false
            defaultFocusRequester.requestFocus(scope)
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
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = stringResource(R.string.user_homepage_favorite),
                            fontSize = titleFontSize.sp
                        )
                        Column (
                            modifier = Modifier.weight(1f),
                        ){
                            Text(
                                modifier = Modifier.fillMaxWidth(),
                                text = stringResource(
                                    R.string.load_data_count,
                                    favoriteViewModel.favorites.size
                                ),
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                fontSize = 11.sp,
                                textAlign = TextAlign.End,
                            )
                            Text(
                                modifier = Modifier.fillMaxWidth(),
                                text = if (deleteMode) stringResource(R.string.delete_mode_action_hint) else stringResource(R.string.delete_mode_hint),
                                color = if (deleteMode) Color.Red.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                fontSize = 11.sp,
                                textAlign = TextAlign.End
                            )
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier.padding(innerPadding)
        ) {
            TopNav(
                modifier = Modifier
                    .focusRequester(defaultFocusRequester)
                    .onFocusChanged { focusOnTabs = it.hasFocus }
                    .onDelayFocusChanged(50) {
                        if (focusOnTabs) {
                            focusRequester.requestFocus()
                        }
                    },
                paddingTop = 0.dp,
                items = favoriteTopNavItems,
                useSmallSize = !showPageTitle,
                initialSelectedItem = favoriteTopNavItems.firstOrNull {
                    it.folderMetadata == favoriteViewModel.currentFavoriteFolderMetadata
                },
                navSwitchMode = navSwitchMode,
                tabFocusRequester = focusRequester,
                onSelectedChanged = { selectedItem ->
                    val folderMetadata = (selectedItem as FavoriteFolderTopNavItem).folderMetadata
                    if (favoriteViewModel.currentFavoriteFolderMetadata != folderMetadata) {
                        updateCurrentFavoriteFolder(folderMetadata)
                    }
                }
            )

            if (!showPageTitle) {
                Text(
                    modifier = Modifier
                        .offset(y = (-6).dp)
                        .fillMaxWidth()
                        .height(14.dp),
                    text = if (deleteMode) stringResource(R.string.delete_mode_action_hint) else stringResource(R.string.delete_mode_hint),
                    color = if (deleteMode) Color.Red.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    fontSize = 11.sp,
                    textAlign = TextAlign.End,
                    lineHeight = 14.sp
                )
            }

            ProvideListBringIntoViewSpec {
                LazyVerticalGrid(
                    modifier = Modifier
                        .focusRestorer()
                        .weight(1f)
                        .blockDownFocusExitAtGridEnd(
                            currentIndexProvider = { currentIndex },
                            itemCount = favoriteViewModel.favorites.size,
                            columnCount = 4
                        )
                        .onMenuKeyDown {
                            if (!deleteMode) {
                                favoriteViewModel.favorites.getOrNull(currentIndex)?.let(openVideoMenu)
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
                    state = lazyGridState,
                    columns = GridCells.Fixed(4),
                    contentPadding = PaddingValues(
                        top = if (showPageTitle) 20.dp else 0.dp,
                        bottom = 20.dp,
                        start = 20.dp,
                        end = 20.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(13.dp)
                ) {
                    itemsIndexed(
                        items = favoriteViewModel.favorites,
                        key = { _, history -> history.stableItemKey() }
                    ) { index, history ->
                        SmallVideoCard(
                            modifier = Modifier.focusRequester(getFocusRequester(index)),
                            data = history,
                            onClick = {
                                if (deleteMode) {
                                    selectedVideo = history
                                    selectedIndex = index
                                    showDeleteConfirmDialog = true
                                } else {
                                    videoInfoRepository.setPreloadedVideoList(favoriteViewModel.favorites)
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
                                    val nextIndex = if (index < favoriteViewModel.favorites.size - 1) index + 1 else index - 1
                                    if (nextIndex >= 0) runCatching { getFocusRequester(nextIndex).requestFocus() }
                                    val aid = history.avid
                                    val folderId = favoriteViewModel.currentFavoriteFolderMetadata?.id
                                    scope.launch {
                                        if (folderId != null) {
                                            val success = VideoUserActionManager.delVideoFromFavoriteFolder(aid = aid, folderId = folderId)
                                            if (success) {
                                                favoriteViewModel.removeFavoriteFromList(aid)
                                                focusTopTabIfListEmpty()
                                                context.getString(R.string.favorite_delete_success).toast(context)
                                            } else {
                                                context.getString(R.string.favorite_delete_failed).toast(context)
                                            }
                                        }
                                    }
                                } else {
                                    openVideoMenu(history)
                                }
                            },
                            onFocus = {
                                focusOnGrid = true
                                currentIndex = index
                                //预加载
                                if (index + 12 > favoriteViewModel.favorites.size) {
                                    favoriteViewModel.updateFolderItems()
                                }
                            }
                        )
                    }

                    if (
                        favoriteViewModel.favorites.isEmpty() &&
                        favoriteViewModel.currentFavoriteFolderMetadata != null &&
                        !favoriteViewModel.updatingFolders &&
                        !favoriteViewModel.updatingFolderItems
                    ) {
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
        DeleteFavoriteConfirmDialog(
            show = showDeleteConfirmDialog,
            videoTitle = selectedVideo!!.title,
            onConfirm = {
                val nextIndex = if (selectedIndex < favoriteViewModel.favorites.size - 1) selectedIndex + 1 else selectedIndex - 1
                if (nextIndex >= 0) runCatching { getFocusRequester(nextIndex).requestFocus() }
                val aid = selectedVideo!!.avid
                val folderId = favoriteViewModel.currentFavoriteFolderMetadata?.id
                showDeleteConfirmDialog = false
                selectedVideo = null
                scope.launch {
                    if (folderId != null) {
                        val success = VideoUserActionManager.delVideoFromFavoriteFolder(aid = aid, folderId = folderId)
                        if (success) {
                            favoriteViewModel.removeFavoriteFromList(aid)
                            focusTopTabIfListEmpty()
                            context.getString(R.string.favorite_delete_success).toast(context)
                        } else {
                            context.getString(R.string.favorite_delete_failed).toast(context)
                        }
                    }
                }
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

    VideoActionMenu(
        show = showVideoActionMenu,
        aid = menuAid,
        upId = menuUpId,
        upName = menuUpName,
        upFace = menuUpFace,
        onDismiss = { showVideoActionMenu = false },
        deleteLabel = "取消收藏",
        extraItems = listOf(
            VideoActionMenuExtraItem(
                CardActionMenuItem(icon = Icons.Rounded.DeleteSweep, text = "批量删除") { deleteMode = true }
            )
        ),
        onDelete = {
            val nextIndex = if (currentIndex < favoriteViewModel.favorites.size - 1) currentIndex + 1 else currentIndex - 1
            if (nextIndex >= 0) runCatching { getFocusRequester(nextIndex).requestFocus() }
            val folderId = favoriteViewModel.currentFavoriteFolderMetadata?.id
            scope.launch {
                if (folderId != null) {
                    VideoUserActionManager.delVideoFromFavoriteFolder(aid = menuAid, folderId = folderId)
                    favoriteViewModel.removeFavoriteFromList(menuAid)
                }
            }
        }
    )
}

private data class FavoriteFolderTopNavItem(
    val folderMetadata: FavoriteFolderMetadata
) : TopNavItem {
    override fun getDisplayName(context: Context): String {
        return folderMetadata.title
    }
}

@Composable
private fun DeleteFavoriteConfirmDialog(
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
        title = { Text(text = stringResource(R.string.favorite_delete_confirm_dialog_title)) },
        text = {
            Text(
                text = stringResource(
                    R.string.favorite_delete_confirm_dialog_text,
                    videoTitle
                )
            )
        },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text(text = stringResource(R.string.favorite_delete_confirm_dialog_confirm))
            }
        },
        dismissButton = {
            OutlinedButton(
                modifier = Modifier.focusRequester(focusRequester),
                onClick = onDismiss
            ) {
                Text(text = stringResource(R.string.favorite_delete_confirm_dialog_dismiss))
            }
        }
    )
}