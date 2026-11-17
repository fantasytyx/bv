package dev.aaa1115910.bv.tv.screens.main.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.People
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import dev.aaa1115910.bv.tv.util.rememberDelayedImeEditable
import dev.aaa1115910.bv.tv.util.requestFocusWithRetry
import dev.aaa1115910.bv.util.FollowedUp
import dev.aaa1115910.bv.util.ImageSize
import dev.aaa1115910.bv.util.resizedImageUrl
import dev.aaa1115910.bv.util.toast
import dev.aaa1115910.bv.viewmodel.home.FollowedUpPanelViewModel
import kotlinx.coroutines.launch

/**
 * 动态页左侧「已关注 UP」面板。
 *
 * 面板自己跟踪根节点焦点并声明返回处理，与内容区的 `BackHandler` 互斥。
 * [focusRequester] 挂在列表外固定的「全部」行上：进入面板时焦点固定落在它上面，列表位置不动。
 * 内容区左侧按 LEFT 由 `redirectLeftFocusAtLeftEdge` 直接交给它，不走默认的几何焦点搜索，
 * 否则会先落到面板里某个 UP 行、把列表滚走一段再跳回来。二级 Tab 第一项按 LEFT 同样由
 * `DynamicsScreen` 的 `onLeftKeyEvent` 直接交给它（面板隐藏时不处理，保持原样）。
 * 在「全部」上按 ↓ 才跳到之前选中过的 UP（没选中过就交给默认焦点搜索落到第一个 UP）。
 * 列表项只在按 OK 时切换选中 UP，聚焦移动不会触发请求。
 * 顶部刷新按钮再按 ↑ 交给 [onFocusSelectedTab]，由调用方把焦点交回二级 Tab 的当前选中项。
 * @param onFocusSelectedTab 面板已顶到最上面还要按 ↑ 时调用：请求聚焦二级 Tab 的当前选中项
 */
@Composable
fun FollowedUpPanel(
    modifier: Modifier = Modifier,
    viewModel: FollowedUpPanelViewModel,
    selectedMid: Long?,
    focusRequester: FocusRequester,
    onSelectedUpChange: (Long?) -> Unit,
    onBack: () -> Unit,
    onFocusSelectedTab: () -> Unit
) {
    var hasFocus by remember { mutableStateOf(false) }
    val searchState = rememberTextFieldState(viewModel.keyword)
    // 不用 rememberLazyListState：面板列表不需要跨页面恢复位置，恢复出来的残留偏移
    // 会把「全部」下面第一个 UP 的顶边裁掉
    val listState = remember { LazyListState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // 只在「从全部下移到列表」时用到：把焦点交给之前选中过的 UP
    val selectedUpFocusRequester = remember { FocusRequester() }

    // 首屏阶段（用户还没把焦点移进面板）把首项钉在顶部。
    // 空列表 → 数据到齐的那个布局帧里，LazyColumn 自己有可能把位置挪到中途：此时焦点还停在
    // 二级 Tab，没有任何代码显式滚动，一次性 scrollToItem 拦不住，只能持续纠正
    // （列表为空时索引恒为 0，这个 watchdog 不会误触发）。
    // 一旦用户真的进了面板（下面这个 effect），位置就完全交回给用户
    var scrollTakenOver by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.load()
        snapshotFlow { listState.firstVisibleItemIndex }.collect { index ->
            if (index != 0 && !scrollTakenOver && selectedMid == null) {
                listState.scrollToItem(0)
            }
        }
    }
    // 有选择UP首屏阶段就定位到选中的UP
    LaunchedEffect(selectedMid, scrollTakenOver) {
        if (selectedMid != null && !scrollTakenOver) {
            scrollTakenOver = true
            val index = selectedMid.let { mid -> viewModel.ups.indexOfFirst { it.mid == mid } }
            listState.scrollToItem(index)
        }
    }

    // 关键字只有 ViewModel 一个来源：搜索会 trim、清空也会改它，输入框跟着回写
    LaunchedEffect(viewModel.keyword) {
        if (searchState.text.toString() != viewModel.keyword) {
            searchState.setTextAndPlaceCursorAtEnd(viewModel.keyword)
        }
    }

    // 进入面板时焦点落在「全部」：内容区按 LEFT / 二级 Tab 第一项按 LEFT 都已经直接请求过它，
    // 这里只是兜住其它入口。第一次真的进面板时把列表位置交回用户 —— 若这次进入是几何焦点搜索
    // 先落到某个 UP 行、把它滚进视口，这里顺手补一次回顶；之后再进出面板都保留用户的位置
    LaunchedEffect(hasFocus) {
        if (!hasFocus) return@LaunchedEffect
        focusRequester.requestFocusWithRetry()
        if (!scrollTakenOver) {
            scrollTakenOver = true
        }
    }

    BackHandler(enabled = hasFocus) {
        onBack()
    }

    Column(
        modifier = modifier
            .width(176.dp)
            .fillMaxHeight()
            .onFocusChanged { hasFocus = it.hasFocus }
            // 右侧只留 5dp，配合网格减半的起始内边距，面板与内容之间的间距刚好是原来的一半
            .padding(start = 10.dp, end = 5.dp, top = 8.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            PanelIconButton(
                icon = Icons.Rounded.Sync,
                contentDescription = "刷新",
                size = 22.dp,
                modifier = Modifier
                    // ↑ 交给二级 Tab 当前选中项：交给默认焦点搜索会落到内容区最上面一张卡片
                    .onPreviewKeyEvent { event ->
                        if (event.key != Key.DirectionUp) {
                            false
                        } else {
                            // 按键的 Up 也要一起吞掉，避免松开时落到刚获得焦点的 Tab 上
                            if (event.type == KeyEventType.KeyDown) onFocusSelectedTab()
                            true
                        }
                    },
                onClick = {
                    viewModel.refresh { success ->
                        // 关注关系可能已经变了：成功后把筛选重置回「全部」
                        if (success) {
                            scope.launch { listState.scrollToItem(0) }
                            onSelectedUpChange(null)
                            "关注列表已同步".toast(context)
                        }
                    }
                }
            )
            Text(text = "已关注 UP", style = MaterialTheme.typography.titleSmall)
        }
        PanelSearchBar(
            state = searchState,
            onClear = {
                searchState.setTextAndPlaceCursorAtEnd("")
                viewModel.clearKeyword()
            },
            onSearch = { viewModel.search(searchState.text.toString()) }
        )
        Column(
            modifier = Modifier.fillMaxWidth(),
            // 与列表的 1dp 顶部留白合起来，和列表项之间的 2dp 间距一致；1dp 也够聚焦边框外溢
            verticalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            // 「全部」固定在列表外：不会被滚走，聚焦边框也不会被列表按主轴裁剪掉上边
            PanelRow(
                text = "全部",
                icon = Icons.Rounded.People,
                selected = selectedMid == null,
                modifier = Modifier
                    .focusRequester(focusRequester)
                    // ↓ 下移到列表：选中过 UP 就跳到那一项，否则交给默认焦点搜索落到第一个 UP
                    .onPreviewKeyEvent { event ->
                        val index = selectedMid
                            ?.let { mid -> viewModel.ups.indexOfFirst { it.mid == mid } }
                            ?: -1
                        if (event.type != KeyEventType.KeyDown ||
                            event.key != Key.DirectionDown ||
                            index < 0
                        ) {
                            false
                        } else {
                            scope.launch {
                                if (listState.layoutInfo.visibleItemsInfo.none { it.index == index }) {
                                    listState.scrollToItem(index)
                                }
                                selectedUpFocusRequester.requestFocusWithRetry()
                            }
                            true
                        }
                    },
                onClick = {
                    scope.launch { listState.scrollToItem(0) }
                    onSelectedUpChange(null)
                }
            )
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                state = listState,
                // 列表滚动时按主轴裁剪内容，首尾留 1dp 才不会切掉聚焦边框
                contentPadding = PaddingValues(vertical = 1.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                items(
                    items = viewModel.ups,
                    key = { up -> up.mid }
                ) { up ->
                    PanelRow(
                        text = up.name,
                        avatar = up.avatar,
                        selected = selectedMid == up.mid,
                        modifier = if (selectedMid == up.mid) {
                            Modifier.focusRequester(selectedUpFocusRequester)
                        } else {
                            Modifier
                        },
                        onClick = { onSelectedUpChange(up.mid) }
                    )
                }
                // 状态行（加载中 / 加载失败重试）是列表最后一项：随列表一起滚动，只有滚到底部才看得到
                if (viewModel.hasStatus) {
                    item(key = PanelStatusItemKey) { PanelStatus(viewModel) }
                }
            }
        }
    }
}

/** 列表末尾状态行的 key，唯一即可（UP 项的 key 是 mid） */
private const val PanelStatusItemKey = "panel-status"

@Composable
private fun PanelSearchBar(
    state: TextFieldState,
    onClear: () -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.small
    val searchFocusRequester = remember { FocusRequester() }
    var hasFocus by remember { mutableStateOf(false) }
    var textFieldFocused by remember { mutableStateOf(false) }
    // 搜索框夹在「全部」和刷新按钮之间，D-pad 去刷新按钮时会先经过它：停留够久才允许弹输入法
    val textFieldEditable = rememberDelayedImeEditable(textFieldFocused)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(34.dp)
            .clip(shape)
            .border(
                width = 2.dp,
                color = if (hasFocus) colorScheme.border else colorScheme.borderVariant,
                shape = shape
            )
            .onFocusChanged { hasFocus = it.hasFocus },
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 输入框必须和按钮是兄弟节点：放进 TextField 的 trailingIcon 里会被输入框的按键处理吞掉，永远聚焦不到
        BasicTextField(
            state = state,
            // 聚焦后 300ms 内保持只读：Compose 不建立输入会话，路过搜索框不会被输入法打断
            readOnly = !textFieldEditable,
            modifier = Modifier
                .weight(1f)
                .padding(start = 8.dp, end = 4.dp)
                .onFocusChanged { textFieldFocused = it.isFocused },
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = colorScheme.onSurface),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            onKeyboardAction = { onSearch() },
            lineLimits = TextFieldLineLimits.SingleLine,
            cursorBrush = SolidColor(colorScheme.primary)
        )
        if (state.text.isNotEmpty()) {
            PanelIconButton(
                icon = Icons.Rounded.Close,
                contentDescription = "清空",
                size = 20.dp,
                containerColor = colorScheme.surfaceVariant,
                // 清空按钮会随文案消失，焦点必须先交给常驻的搜索按钮，否则会丢失焦点
                onClick = {
                    onClear()
                    runCatching { searchFocusRequester.requestFocus() }
                }
            )
        }
        Box(
            modifier = Modifier
                .padding(horizontal = 3.dp)
                .width(1.dp)
                .height(14.dp)
                .background(colorScheme.borderVariant)
        )
        PanelIconButton(
            icon = Icons.Rounded.Search,
            contentDescription = "搜索",
            size = 22.dp,
            modifier = Modifier.focusRequester(searchFocusRequester),
            onClick = onSearch
        )
        Spacer(modifier = Modifier.width(2.dp))
    }
}

@Composable
private fun PanelIconButton(
    icon: ImageVector,
    contentDescription: String,
    size: Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = Color.Transparent
) {
    val colorScheme = MaterialTheme.colorScheme
    Surface(
        modifier = modifier.size(size),
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(shape = CircleShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = containerColor,
            contentColor = colorScheme.onSurfaceVariant,
            focusedContainerColor = colorScheme.border,
            focusedContentColor = colorScheme.surface,
            pressedContainerColor = colorScheme.border,
            pressedContentColor = colorScheme.surface
        ),
        scale = ClickableSurfaceDefaults.scale(scale = 1f, focusedScale = 1f, pressedScale = 0.92f)
    ) {
        Icon(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(size * 0.15f),
            imageVector = icon,
            contentDescription = contentDescription
        )
    }
}

@Composable
private fun PanelStatus(viewModel: FollowedUpPanelViewModel) {
    val loading = viewModel.loading
    // 「加载失败 → 加载中」复用同一个可聚焦节点，切换时只换文案和动作：换成另一种 Composable 时，
    // Compose 会直接清掉当前焦点（`FocusTargetNode.onDetach` → `clearFocus`），遥控器就按不动了，
    // 而这里恰恰是列表末尾唯一可聚焦的项。行高也顺带不跳。
    PanelStatusButton(
        text = if (loading) "加载中…" else "加载失败，重试",
        onClick = { if (!loading) viewModel.retry() }
    )
}

@Composable
private fun PanelStatusButton(text: String, onClick: () -> Unit) {
    Surface(onClick = onClick) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun PanelRow(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    avatar: String? = null,
    icon: ImageVector? = null
) {
    Surface(
        modifier = modifier
            .fillMaxWidth(),
        onClick = onClick,
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
            } else {
                Color.Transparent
            },
            focusedContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f),
            contentColor = MaterialTheme.colorScheme.onSurface,
            focusedContentColor = MaterialTheme.colorScheme.onSurface,
            pressedContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f)
        ),
        shape = ClickableSurfaceDefaults.shape(shape = MaterialTheme.shapes.small),
        scale = ClickableSurfaceDefaults.scale(scale = 1f, focusedScale = 1f),
        border = ClickableSurfaceDefaults.border(
            border = Border(
                border = BorderStroke(2.dp, Color.Transparent),
                shape = MaterialTheme.shapes.small
            ),
            focusedBorder = Border(
                border = BorderStroke(
                    width = 2.dp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.65f)
                ),
                shape = MaterialTheme.shapes.small
            )
        )
    ) {
        Row(
            modifier = Modifier
                .height(36.dp)
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (avatar != null) {
                AsyncImage(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape),
                    model = avatar.resizedImageUrl(ImageSize.Icon),
                    contentDescription = null,
                    contentScale = ContentScale.Crop
                )
            } else if (icon != null) {
                // 「全部」没有头像，用同样 28dp 的圆形图标占位，行高才和 UP 项一致
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        modifier = Modifier.size(16.dp),
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Text(
                text = text,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = 12.sp
            )
        }
    }
}
