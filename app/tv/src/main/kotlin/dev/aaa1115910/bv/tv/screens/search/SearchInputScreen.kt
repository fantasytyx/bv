package dev.aaa1115910.bv.tv.screens.search

import androidx.activity.compose.BackHandler
import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import dev.aaa1115910.bv.tv.screens.main.drawerItemFocusRequesters
import dev.aaa1115910.bv.tv.screens.main.DrawerItem
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.IconButton
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.aaa1115910.biliapi.entity.search.Hotword
import dev.aaa1115910.bv.R
import dev.aaa1115910.bv.entity.db.SearchHistoryDB
import dev.aaa1115910.bv.tv.activities.search.SearchResultActivity
import dev.aaa1115910.bv.tv.component.TvAlertDialog
import dev.aaa1115910.bv.tv.component.search.SearchKeyword
import dev.aaa1115910.bv.tv.component.search.SoftKeyboard
import dev.aaa1115910.bv.tv.util.rememberDelayedImeEditable
import dev.aaa1115910.bv.ui.theme.BVTheme
import dev.aaa1115910.bv.util.Prefs
import dev.aaa1115910.bv.viewmodel.search.SearchInputViewModel
import kotlinx.coroutines.delay
import org.koin.androidx.compose.koinViewModel

@Composable
fun SearchInputScreen(
    modifier: Modifier = Modifier,
    defaultFocusRequester: FocusRequester,
    searchInputViewModel: SearchInputViewModel = koinViewModel()
) {
    val context = LocalContext.current

    val searchKeyword = searchInputViewModel.keyword
    val hotwords = searchInputViewModel.hotwords
    val searchHistories = searchInputViewModel.searchHistories
    val suggests = searchInputViewModel.suggests

    var enableProxy by remember { mutableStateOf(false) }

    var focusOnContent by remember { mutableStateOf(false) }

    val onSearch: (String) -> Unit = { keyword ->
        SearchResultActivity.actionStart(context, keyword, enableProxy)
        searchInputViewModel.keyword = keyword
        searchInputViewModel.addSearchHistory(keyword)
    }

    LaunchedEffect(searchKeyword) {
        searchInputViewModel.updateSuggests()
    }

    BackHandler(enabled = focusOnContent) {
        drawerItemFocusRequesters[DrawerItem.Search]?.requestFocus()
    }

    val showProxyOptions = remember { Prefs.enableProxy }

    SearchInputScreenContent(
        modifier = modifier
            .onFocusChanged { focusOnContent = it.hasFocus },
        defaultFocusRequester = defaultFocusRequester,
        searchKeyword = searchKeyword,
        onSearchKeywordChange = { searchInputViewModel.keyword = it },
        onSearch = onSearch,
        showProxyOptions = showProxyOptions,
        enableProxy = enableProxy,
        onEnableProxyChange = { enableProxy = it },
        hotwords = hotwords,
        suggests = suggests,
        histories = searchHistories,
        onDeleteHistory = { searchInputViewModel.deleteSearchHistory(it) },
        onDeleteAllHistories = { searchInputViewModel.deleteAllSearchHistories() }
    )
}

@Composable
private fun SearchInputScreenContent(
    modifier: Modifier = Modifier,
    defaultFocusRequester: FocusRequester,
    searchKeyword: String,
    onSearchKeywordChange: (String) -> Unit,
    onSearch: (String) -> Unit,
    showProxyOptions: Boolean,
    enableProxy: Boolean,
    onEnableProxyChange: (Boolean) -> Unit,
    hotwords: List<Hotword>,
    suggests: List<String>,
    histories: List<SearchHistoryDB>,
    onDeleteHistory: (SearchHistoryDB) -> Unit,
    onDeleteAllHistories: () -> Unit
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            Box(
                modifier = Modifier.padding(start = 48.dp, top = 24.dp, bottom = 8.dp, end = 48.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = stringResource(R.string.search_input_title),
                        fontSize = 48.sp
                    )
                }
            }
        }
    ) { innerPadding ->
        Row(
            modifier = Modifier
                .padding(innerPadding)
                .padding(vertical = 8.dp)
                .padding(start = 24.dp)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            SearchInput(
                firstButtonFocusRequester = defaultFocusRequester,
                searchKeyword = searchKeyword,
                onSearchKeywordChange = onSearchKeywordChange,
                onSearch = { onSearch(searchKeyword) },
                showProxyOptions = showProxyOptions,
                enableProxy = enableProxy,
                onEnableProxyChange = onEnableProxyChange
            )

            if (searchKeyword.isEmpty()) {
                SearchHotwords(
                    hotwords = hotwords,
                    onSearch = onSearch
                )
            } else {
                SearchSuggestion(
                    suggests = suggests,
                    onSearch = onSearch
                )
            }

            SearchHistory(
                modifier = Modifier
                    .padding(end = 10.dp),
                histories = histories,
                onSearch = onSearch,
                onDelete = onDeleteHistory,
                onDeleteAll = onDeleteAllHistories
            )
        }
    }
}

@Composable
private fun SearchInput(
    modifier: Modifier = Modifier,
    firstButtonFocusRequester: FocusRequester,
    searchKeyword: String,
    onSearchKeywordChange: (String) -> Unit,
    onSearch: (String) -> Unit,
    showProxyOptions: Boolean,
    enableProxy: Boolean,
    onEnableProxyChange: (Boolean) -> Unit
) {
    var textFieldValue by remember {
        mutableStateOf(
            TextFieldValue(
                text = searchKeyword,
                selection = TextRange(searchKeyword.length)
            )
        )
    }
    var textFieldFocused by remember { mutableStateOf(false) }
    // 从软键盘 / 列表上移到输入框只是路过时不该弹输入法：停留够久才允许
    val textFieldEditable = rememberDelayedImeEditable(textFieldFocused)

    LaunchedEffect(searchKeyword) {
        if (searchKeyword != textFieldValue.text) {
            textFieldValue = textFieldValue.copy(
                text = searchKeyword,
                selection = TextRange(searchKeyword.length)
            )
        }
    }

    Box(
        modifier = modifier
            .width(280.dp)
            .fillMaxHeight()
            .focusGroup(),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            OutlinedTextField(
                modifier = Modifier
                    .width(258.dp)
                    .onFocusChanged {
                        textFieldFocused = it.isFocused
                        if (it.isFocused && textFieldValue.selection.end != textFieldValue.text.length) {
                            textFieldValue = textFieldValue.copy(
                                selection = TextRange(textFieldValue.text.length)
                            )
                        }
                    },
                // 聚焦后 300ms 内保持只读：Compose 不建立输入会话，路过输入框不会被输入法打断
                readOnly = !textFieldEditable,
                value = textFieldValue,
                onValueChange = {
                    textFieldValue = it
                    onSearchKeywordChange(it.text)
                },
                maxLines = 1,
                shape = MaterialTheme.shapes.medium,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch(searchKeyword) }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.inverseSurface,
                    cursorColor = MaterialTheme.colorScheme.inverseSurface
                )
            )
            SoftKeyboard(
                firstButtonFocusRequester = firstButtonFocusRequester,
                showSearchWithProxy = showProxyOptions,
                enableSearchWithProxy = enableProxy,
                onClick = { onSearchKeywordChange(searchKeyword + it) },
                onClear = { onSearchKeywordChange("") },
                onDelete = {
                    if (searchKeyword.isNotEmpty()) {
                        onSearchKeywordChange(searchKeyword.dropLast(1))
                    }
                },
                onSearch = { onSearch(searchKeyword) },
                onEnableSearchWithProxyChange = onEnableProxyChange
            )
        }
    }
}

@Composable
private fun SearchHotwords(
    modifier: Modifier = Modifier,
    hotwords: List<Hotword>,
    onSearch: (String) -> Unit
) {
    Column(
        modifier = modifier
            .width(250.dp)
            .fillMaxHeight()
            .focusGroup(),
    ) {
        Text(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            text = stringResource(R.string.search_input_hotword),
            style = MaterialTheme.typography.titleLarge
        )
        LazyColumn(
            modifier = Modifier,
            contentPadding = PaddingValues(vertical = 4.dp)
        ) {
            itemsIndexed(
                items = hotwords,
                key = { index, hotword -> "$index-hotword-${hotword.showName}" }
            ) { _, hotword ->
                SearchKeyword(
                    modifier = Modifier,
                    keyword = hotword.showName,
                    leadingIcon = hotword.icon ?: "",
                    onClick = { onSearch(hotword.showName) }
                )
            }
        }
    }
}


@Composable
private fun SearchSuggestion(
    modifier: Modifier = Modifier,
    suggests: List<String>,
    onSearch: (String) -> Unit
) {
    Column(
        modifier = modifier
            .width(250.dp)
            .fillMaxHeight()
            .focusGroup(),
    ) {
        Text(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            text = stringResource(R.string.search_input_suggest),
            style = MaterialTheme.typography.titleLarge
        )
        LazyColumn(
            modifier = Modifier,
            contentPadding = PaddingValues(vertical = 4.dp)
        ) {
            itemsIndexed(
                items = suggests,
                key = { index, suggest -> "$index-suggest-$suggest" }
            ) { _, suggest ->
                SearchKeyword(
                    modifier = Modifier,
                    keyword = suggest,
                    leadingIcon = "",
                    onClick = { onSearch(suggest) }
                )
            }
        }
    }
}

@Composable
private fun SearchHistory(
    modifier: Modifier = Modifier,
    histories: List<SearchHistoryDB>,
    onSearch: (String) -> Unit,
    onDelete: (SearchHistoryDB) -> Unit,
    onDeleteAll: () -> Unit
) {
    var deleteMode by remember { mutableStateOf(false) }
    var showDeleteAllConfirmDialog by remember { mutableStateOf(false) }

    val deleteModeButtonFocusRequester = remember { FocusRequester() }
    // 每条历史一个 FocusRequester，删除后把焦点交给下一条（按 id 复用，列表刷新后依然有效）
    val historyFocusRequesters = remember { mutableMapOf<Int, FocusRequester>() }
    // 删除后待聚焦的历史 id（默认是下一条，删的是最后一条时用上一条）
    var pendingFocusHistoryId by remember { mutableStateOf<Int?>(null) }
    // 历史被删空退出删除模式后需要聚焦回删除模式按钮
    var pendingFocusDeleteModeButton by remember { mutableStateOf(false) }

    // 历史删除后列表是异步刷新的，等目标项重新组合出来再请求焦点
    LaunchedEffect(pendingFocusHistoryId) {
        val historyId = pendingFocusHistoryId ?: return@LaunchedEffect
        repeat(10) {
            if (histories.none { it.id == historyId }) {
                pendingFocusHistoryId = null
                return@LaunchedEffect
            }
            val focusRequester = historyFocusRequesters[historyId]
            if (focusRequester != null &&
                runCatching { focusRequester.requestFocus() }.getOrDefault(false)
            ) {
                pendingFocusHistoryId = null
                return@LaunchedEffect
            }
            delay(50)
        }
        pendingFocusHistoryId = null
    }

    LaunchedEffect(pendingFocusDeleteModeButton) {
        if (!pendingFocusDeleteModeButton) return@LaunchedEffect
        repeat(10) {
            if (runCatching { deleteModeButtonFocusRequester.requestFocus() }.getOrDefault(false)) {
                pendingFocusDeleteModeButton = false
                return@LaunchedEffect
            }
            delay(50)
        }
        pendingFocusDeleteModeButton = false
    }

    Column(
        modifier = modifier
            .width(250.dp)
            .fillMaxHeight()
            .focusGroup(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                text = stringResource(R.string.search_input_history),
                style = MaterialTheme.typography.titleLarge
            )
            Row {
                if (deleteMode) {
                    IconButton(
                        onClick = { showDeleteAllConfirmDialog = true },
                        colors = ButtonDefaults.colors(
                            containerColor = MaterialTheme.colorScheme.surface,
                        )
                    ) {
                        Icon(imageVector = Icons.Default.DeleteSweep, contentDescription = null)
                    }
                }
                IconButton(
                    modifier = Modifier.focusRequester(deleteModeButtonFocusRequester),
                    onClick = { deleteMode = !deleteMode },
                    colors = ButtonDefaults.colors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    )
                ) {
                    if (deleteMode) {
                        Icon(imageVector = Icons.Default.Close, contentDescription = null)
                    } else {
                        Icon(imageVector = Icons.Default.Delete, contentDescription = null)
                    }
                }
            }
        }

        LazyColumn(
            modifier = Modifier,
            contentPadding = PaddingValues(vertical = 4.dp)
        ) {
            itemsIndexed(
                items = histories,
                // key 不能带 index，否则删除后位置变化会重建节点并丢失焦点
                key = { _, searchHistory -> "history-${searchHistory.id ?: searchHistory.keyword}" }
            ) { index, searchHistory ->
                val itemFocusRequester = searchHistory.id?.let { id ->
                    historyFocusRequesters.getOrPut(id) { FocusRequester() }
                }
                SearchKeyword(
                    modifier = itemFocusRequester?.let { Modifier.focusRequester(it) } ?: Modifier,
                    keyword = searchHistory.keyword,
                    leadingIcon = "",
                    onClick = {
                        if (deleteMode) {
                            val nextHistoryId = histories.getOrNull(index + 1)?.id
                            val prevHistoryId = histories.getOrNull(index - 1)?.id
                            when {
                                // 删除后聚焦到下一条历史
                                nextHistoryId != null -> pendingFocusHistoryId = nextHistoryId
                                // 删除的是最后一条历史时聚焦到上一条
                                prevHistoryId != null -> pendingFocusHistoryId = prevHistoryId
                                // 列表会被删空，退出删除模式并回到删除模式按钮
                                else -> {
                                    deleteMode = false
                                    pendingFocusDeleteModeButton = true
                                }
                            }
                            onDelete(searchHistory)
                        } else {
                            onSearch(searchHistory.keyword)
                        }
                    },
                    trailingIcon = (@Composable {
                        Icon(
                            modifier = Modifier.size(16.dp),
                            imageVector = Icons.Default.Delete,
                            contentDescription = null
                        )
                    }).takeIf { deleteMode }
                )
            }
        }
    }

    if (showDeleteAllConfirmDialog) {
        TvAlertDialog(
            onDismissRequest = { showDeleteAllConfirmDialog = false },
            title = {
                Text(text = stringResource(R.string.search_input_history_delete_all_confirm_dialog_title))
            },
            text = {
                Text(text = stringResource(R.string.search_input_history_delete_all_confirm_dialog_text))
            },
            confirmButton = {
                Button(onClick = {
                    onDeleteAll()
                    showDeleteAllConfirmDialog = false
                }) {
                    Text(text = stringResource(R.string.search_input_history_delete_all_confirm_dialog_confirm_button))
                }
            },
            dismissButton = {
                Button(onClick = {
                    showDeleteAllConfirmDialog = false
                }) {
                    Text(text = stringResource(R.string.search_input_history_delete_all_confirm_dialog_cancel_button))
                }
            }
        )
    }
}

@Preview(device = "id:tv_1080p")
@Preview(device = "id:tv_1080p", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun SearchInputScreenContentPreview() {
    BVTheme {
        Row {
            Spacer(
                modifier = Modifier
                    .width(80.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            )
            SearchInputScreenContent(
                modifier = Modifier,
                defaultFocusRequester = FocusRequester.Default,
                searchKeyword = "测试",
                onSearchKeywordChange = {},
                onSearch = {},
                showProxyOptions = true,
                enableProxy = false,
                onEnableProxyChange = {},
                hotwords = listOf(
                    Hotword("热搜1", "热搜1", null),
                    Hotword("热搜2", "热搜2", null)
                ),
                suggests = listOf("建议1", "建议2"),
                histories = listOf(
                    SearchHistoryDB(keyword = "历史1"),
                    SearchHistoryDB(keyword = "历史2")
                ),
                onDeleteHistory = {},
                onDeleteAllHistories = {}
            )
        }
    }
}
