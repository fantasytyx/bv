# TV 首页「动态」页改造 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 首页「动态」页加「视频 / 全部」二级 tab、全部 tab 用 3 列瀑布流社交卡片并支持长按菜单；新增「动态页显示已关注 UP 列表」开关，开启后左侧面板可按 UP 过滤两个 tab 的动态。

**Architecture:** 先把 UP 空间页里内联的「3 列瀑布流 + 同列上下键焦点」抽成 `DynamicWaterfall`，两端共用；动态分页统一收敛到已有的 `PagingState`；UP 面板的分页/缓存/搜索在 `FollowedUpPanelViewModel` 与纯函数 `FollowedUps.kt` 里完成，UI 只是薄壳。

**Tech Stack:** Kotlin / Jetpack Compose for TV (`androidx.tv.material3`) / Koin / DataStore preferences / `cotlin.test`（JUnit4 变体）。

**Spec:**
- `docs/superpowers/specs/2026-11-14-tv-home-dynamics-tabs-design.md`（Stage 1–3）
- `docs/superpowers/specs/2026-11-14-tv-home-dynamics-up-panel-design.md`（Stage 4–6）

## Global Constraints

- **只改 TV 端 + 共享层**：`app/tv`、`app/shared`、`bili-api`。不新增/修改 `app/mobile` 的行为（`DynamicViewModel` 的签名变化要保证移动端调用点仍能编译且语义不变）。
- **JDK 21**：跑 Gradle 前 `JAVA_HOME` 必须指向 JDK 21 本体；换过 `JAVA_HOME` 后先 `./gradlew --stop`。
- **minSdk 23**：不要用 API 24 才有的 `ConcurrentHashMap.putIfAbsent`、`Map.getOrDefault`、`Collection.removeIf`；需要线程安全集合时用 `Collections.newSetFromMap` / `synchronized`（`VideoUserActionManager` 里已有先例）。
- **Kotlin 代码风格 `official`**；Koin 注解一律 `org.koin.core.annotation.*`。
- **文案中文**，与现有 TV 页面一致（「详情」「点赞」「已点赞」「UP 主页」「已关注 UP 列表」）。
- **TV 遥控：长按 ≠ 单击**，不要用时间阈值区分；长按菜单必须沿用 `CardActionMenu` 现有的「弹窗内没收到过按住事件就吞掉抬起」处理，不要另造一套。
- 每次提交前至少跑一遍当任务对应的编译命令；最后一个任务跑全量单测。

## Review Focus

1. **缓存内容为空 / 被截断 / 含非法行** → 解析不得崩溃，返回已解析部分或空列表，随后照常请求第 1 页。（Task 5 的 `decodeFollowedUps` 测试）
2. **缓存时间正好 24h / 时钟回拨** → 超过 TTL 视为失效；未来时间戳（回拨）不能当成「永久新鲜」。（Task 5 的 `isCacheFresh` 测试）
3. **缓存里只有一页而关注数更多** → 缓存命中后必须仍能继续分页（`hasMore` 由 `total` 推导，不能因为缓存非空就当成到底）。（Task 6 的 `FollowedUserPage.from` 测试）
4. **关键字含空格/大小写/多字符** → 与 `FollowScreen` 现有行为一致：忽略空白，每个字符都要命中。（Task 5 的 `matchesUpKeyword` 测试）
5. **动态缺 `id` / 缺 `footer` / 缺 `badge`** → 列表 key 退化为 index、菜单不崩、`isChargingArc=false`。（Task 8 的 `DynamicVideo.fromDynamicItem` 测试 + 现有 `PagingStateTest` 的 null-id 用例）

---

### Task 1: 抽出 `DynamicWaterfall` 与 `openDynamic`，UP 空间页改用

**Files:**
- Create: `app/tv/src/main/kotlin/dev/aaa1115910/bv/tv/screens/user/DynamicWaterfall.kt`
- Create: `app/tv/src/main/kotlin/dev/aaa1115910/bv/tv/screens/user/DynamicNavigation.kt`
- Modify: `app/tv/src/main/kotlin/dev/aaa1115910/bv/tv/screens/user/UpInfoScreen.kt`（动态分支 L650 起、以及 L185–280 的内联焦点状态）

**Interfaces:**
- Produces: `DynamicWaterfall(items, state, onItemClick, onLoadMore, modifier, columns, contentPadding, horizontalSpacing, verticalItemSpacing, loadMoreThreshold, footer)`
- Produces: `openDynamic(context: Context, dynamic: DynamicItem, videoInfoRepository: VideoInfoRepository, preloadFrom: List<DynamicItem>)`

- [ ] **Step 1: 建 `DynamicWaterfall.kt`，把 `UpInfoScreen` 的动态网格与焦点逻辑原样搬过来**

签名（`@Composable`）：

```kotlin
fun DynamicWaterfall(
    items: List<DynamicItem>,
    state: LazyStaggeredGridState,
    onItemClick: (DynamicItem) -> Unit,
    onLoadMore: () -> Unit,
    modifier: Modifier = Modifier,
    columns: Int = 3,
    contentPadding: PaddingValues = PaddingValues(24.dp),
    horizontalSpacing: Dp = 24.dp,
    verticalItemSpacing: Dp = 20.dp,
    loadMoreThreshold: Int = 6,
    footer: (@Composable () -> Unit)? = null
)
```

搬移清单（逐项照抄，不改行为）：
- `focusRequesters: MutableMap<Int, FocusRequester>` + `focusRequesterFor(index)`；
- `focusedIndex`、`viewportHeightPx`、`pendingAnchor: GridItemBox?`、`pendingDirection: GridVertical?`；
- `itemBoxes()`（`state.layoutInfo.visibleItemsInfo` → `GridItemBox`）；
- `LaunchedEffect(state) { snapshotFlow { itemBoxes() }.collect { … sameColumnNeighbor … requestFocus } }`；
- `onVerticalKey`（`verticalMove` 三分支：`Focus` / `ScrollToReveal` / `ScrollToNeighbour` / `Delegate`）；
- 网格本体：`LazyVerticalStaggeredGrid(columns = StaggeredGridCells.Fixed(columns), contentPadding = contentPadding, verticalItemSpacing = verticalItemSpacing, horizontalArrangement = Arrangement.spacedBy(horizontalSpacing))`，`Modifier.fillMaxSize().onSizeChanged { viewportHeightPx = it.height }.onPreviewKeyEvent(onVerticalKey)`；
- `staggeredItemsIndexed(items, key = { index, d -> d.id ?: "dynamic-$index" })` → `SpaceDynamicCard(dynamic, Modifier.focusRequester(focusRequesterFor(index)), onClick = { onItemClick(d) }, onFocus = { focusedIndex = index; if (index + loadMoreThreshold > items.size) onLoadMore() })`；
- `footer` 用 `item(span = StaggeredGridItemSpan.FullLine) { Box(Modifier.onFocusChanged { if (it.hasFocus) focusedIndex = -1 }) { footer() } }`。

**不要**把 `focusRestorer()`、返回键、`ProvideListBringIntoViewSpec` 搬进来——它们留在调用方。

- [ ] **Step 2: 建 `DynamicNavigation.kt`**

```kotlin
fun openDynamic(
    context: Context,
    dynamic: DynamicItem,
    videoInfoRepository: VideoInfoRepository,
    preloadFrom: List<DynamicItem>
)
```

体内即 `UpInfoScreen` 现有的 `when (dynamic.cardAction())`：
- `Video`：`preloadFrom.mapNotNull { item -> item.video?.let { VideoCardData(avid = it.aid, title = it.title, cover = it.cover, upName = "", play = it.playCount, danmaku = it.danmakuCount, time = it.durationSeconds * 1000L, pubTime = item.author.pubTime) } }` → `videoInfoRepository.setPreloadedVideoList(...)` → `VideoInfoActivity.actionStart(context, aid = video.aid, proxyArea = ProxyArea.checkProxyArea(video.title))`
- `Article`：`dynamic.id?.toLongOrNull()?.let { ArticleActivity.actionStart(context, it) }`
- `Detail`：`dynamic.id?.let { DynamicDetailActivity.actionStart(context, it) }`

- [ ] **Step 3: `UpInfoScreen` 删掉搬走的状态与两条 `LaunchedEffect`，动态分支改用组件**

动态分支改为：

```kotlin
selectedTab == UserSpaceTab.Dynamics -> DynamicWaterfall(
    items = userSpaceViewModel.tvSpaceDynamics,
    state = dynamicGridState,
    modifier = contentModifier.fillMaxSize().then(listModifier),
    onItemClick = { openDynamic(context, it, videoInfoRepository, userSpaceViewModel.tvSpaceDynamics) },
    onLoadMore = { userSpaceViewModel.load(UserSpaceTab.Dynamics) },
    footer = {
        if (isLoading || error != null || hasMore || contentCount == 0) {
            SpaceContentStatus(isLoading, error, hasMore) { userSpaceViewModel.load(UserSpaceTab.Dynamics) }
        }
    }
)
```

同时删除：`dynamicFocusRequesters`、`dynamicFocusRequesterFor`、`focusedDynamicIndex`、`dynamicViewportHeightPx`、`pendingVerticalMove`、`pendingVerticalDirection`、`dynamicItemBoxes()`、`snapshotFlow` 那条 `LaunchedEffect`、`onDynamicVerticalKey`、`dynamicColumnCount`、`dynamicVerticalSpacingPx`，以及动态分支里 `currentIndex = index` 那一行（它只服务于 4 列网格的 `blockDownFocusExitAtGridEnd`，动态分支没有消费者）。`tab 切换` 分支里对 `focusedDynamicIndex = -1` 的重置一并删除。

- [ ] **Step 4: 编译验证**

Run: `./gradlew :app:tv:compileDebugKotlin`
Expected: BUILD SUCCESSFUL（无未使用变量告警不影响构建）

- [ ] **Step 5: 单测验证（行为不变的回归证明）**

Run: `./gradlew :app:tv:testDebugUnitTest`
Expected: `DynamicGridFocusTest`、`SpaceDynamicCardTest`、`GridFocusTest`、`UpSpaceArchiveNavigationTest` 全绿

- [ ] **Step 6: 真机确认 UP 空间页「动态」tab 手感未变**

上下键在同列移动、卡片被截断时先滚动、首行按 UP 回到 tab 栏、底部状态获得焦点后上下键不再被卡片逻辑劫持。

- [ ] **Step 7: 提交**

```bash
git add app/tv/src/main/kotlin/dev/aaa1115910/bv/tv/screens/user/DynamicWaterfall.kt \
        app/tv/src/main/kotlin/dev/aaa1115910/bv/tv/screens/user/DynamicNavigation.kt \
        app/tv/src/main/kotlin/dev/aaa1115910/bv/tv/screens/user/UpInfoScreen.kt
git commit -m "refactor(tv): 抽出动态瀑布流与动态点击路由，UP 空间页改用"
```

---

### Task 2: `DynamicViewModel` 分页收敛到 `PagingState`

**Files:**
- Modify: `app/shared/src/main/kotlin/dev/aaa1115910/bv/viewmodel/user/PagingState.kt`（加 `lastException`）
- Modify: `app/shared/src/main/kotlin/dev/aaa1115910/bv/viewmodel/home/DynamicViewModel.kt`
- Modify: `app/tv/src/main/kotlin/dev/aaa1115910/bv/tv/screens/main/home/DynamicsScreen.kt`（状态字段改名）
- Modify: `app/mobile/src/main/kotlin/dev/aaa1115910/bv/mobile/screen/home/DynamicScreen.kt`（仅 `loadingAll` → `all.loading`）

**Interfaces:**
- Produces: `DynamicViewModel.videos: PagingState`、`DynamicViewModel.all: PagingState`
- Produces: `suspend fun loadMoreVideo()`、`suspend fun loadMoreAll()`、`fun clearVideoData()`、`fun clearAllData()`（名字不变）
- Produces: `PagingState.lastException: Throwable?`（`private set`）

- [ ] **Step 1: 给 `PagingState` 补 `lastException`**

```kotlin
var lastException: Throwable? by mutableStateOf(null)
    private set
```

`reset()` 里置 `null`；`load()` 开始时置 `null`；`catch (exception: Exception)` 分支里同时赋值 `lastException = exception`（`CancellationException` 分支保持 rethrow，不记录）。

- [ ] **Step 2: 重写 `DynamicViewModel` 的两份分页**

保留 `dynamicVideoList` / `dynamicAllList` / `isLogin` / logger；用 `val videos = PagingState()`、`val all = PagingState()` 替换 `currentVideoPage`/`loadingVideo`/`videoHasMore`/`videoHistoryOffset`/`currentAllPage`/`loadingAll`/`allHasMore`/`allHistoryOffset`，保留 `videoUpdateBaseline`/`allUpdateBaseline` 两个字段。

`loadMoreVideo()` 的目标形态（`loadMoreAll()` 同构，`getDynamics` / `dynamicAllList` / `DynamicItem.id`）：

```kotlin
suspend fun loadMoreVideo() {
    if (!bvUserRepository.isLogin) return
    withContext(Dispatchers.Main) {
        val ok = videos.load(
            fetch = { page, offset ->
                val data = userRepository.getDynamicVideos(
                    page = page,
                    offset = offset,
                    updateBaseline = videoUpdateBaseline ?: "",
                    preferApiType = Prefs.apiType
                )
                videoUpdateBaseline = data.updateBaseline
                val known = dynamicVideoList.mapTo(mutableSetOf()) { it.aid.toString() }
                PageResult(
                    items = data.videos.newItems(known) { it.aid.toString() },
                    hasMore = data.hasMore,
                    nextPage = page + 1,
                    nextOffset = data.historyOffset
                )
            },
            append = { dynamicVideoList.addAll(it) }
        )
        if (!ok) reportLoadFailure(videos.lastException)
    }
}

private fun reportLoadFailure(cause: Throwable?) {
    when (cause) {
        null -> Unit
        is AuthFailureException -> logger.fInfo { "User auth failure" }
        else -> {
            logger.fWarn { "Load dynamic failed: ${cause.stackTraceToString()}" }
            "加载动态失败: ${cause.localizedMessage}".toast(BVApp.context)
        }
    }
}
```

`withContext(Dispatchers.Main)` 是必需的：调用方（`HomeContent`、`DynamicsScreen`）在 `Dispatchers.IO` 上调用它，`PagingState.load` 的 `append` 在调用方上下文执行。

`clearVideoData()` = `videos.reset(); dynamicVideoList.clear(); videoUpdateBaseline = null`；`clearAllData()` 同理。注意现有实现漏了清 `updateBaseline`，这次一并补上。

- [ ] **Step 3: 更新两个调用点的字段名**

- `DynamicsScreen`：`dynamicViewModel.loadingVideo` → `dynamicViewModel.videos.loading`；`dynamicViewModel.videoHasMore` → `dynamicViewModel.videos.hasMore`。
- `DynamicScreen`（mobile）：`dynamicViewModel.loadingAll` → `dynamicViewModel.all.loading`（只改这一处读取，其余不动）。

- [ ] **Step 4: 编译验证**

Run: `./gradlew :app:shared:compileDebugKotlin :app:tv:compileDebugKotlin :app:mobile:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: 提交**

```bash
git add app/shared/src/main/kotlin/dev/aaa1115910/bv/viewmodel/user/PagingState.kt \
        app/shared/src/main/kotlin/dev/aaa1115910/bv/viewmodel/home/DynamicViewModel.kt \
        app/tv/src/main/kotlin/dev/aaa1115910/bv/tv/screens/main/home/DynamicsScreen.kt \
        app/mobile/src/main/kotlin/dev/aaa1115910/bv/mobile/screen/home/DynamicScreen.kt
git commit -m "refactor: 动态分页收敛到 PagingState，修正全部 tab 的页码与主线程写入"
```

---

### Task 3: 首页动态页「视频 / 全部」二级 tab

**Files:**
- Modify: `app/tv/src/main/kotlin/dev/aaa1115910/bv/tv/screens/main/home/DynamicsScreen.kt`
- Modify: `app/tv/src/main/kotlin/dev/aaa1115910/bv/tv/screens/main/HomeContent.kt`（新增 `dynamicAllState`，重载时按 tab 清空）

**Interfaces:**
- Consumes: Task 1 的 `DynamicWaterfall`、Task 2 的 `videos`/`all`
- Produces: `private enum class DynamicTab(private val displayName: String) : TopNavItem { Video("视频"), All("全部") }`（`DynamicsScreen.kt` 内，包级 private 不可见 → 用 file-private `private enum class`）

- [ ] **Step 1: 加二级 tab 枚举与 tab 行**

```kotlin
private enum class DynamicTab(private val displayName: String) : TopNavItem {
    Video("视频"),
    All("全部");

    override fun getDisplayName(context: Context): String = displayName
}
```

`DynamicsScreen` 参数增加 `allState: LazyStaggeredGridState = rememberLazyStaggeredGridState()`；内部：

```kotlin
val tabItems = remember { DynamicTab.entries.toList() }
var selectedTab by remember { mutableStateOf(DynamicTab.Video) }
val tabFocusRequester = remember { FocusRequester() }
var contentHasFocus by remember { mutableStateOf(false) }
```

整体结构改成（沿用 `FavoriteScreen` 的嵌套 `TopNav` 用法）：

```kotlin
Column(modifier = modifier.fillMaxSize()) {
    TopNav(
        paddingTop = 0.dp,
        items = tabItems,
        useSmallSize = true,
        initialSelectedItem = selectedTab,
        navSwitchMode = navSwitchMode,
        tabFocusRequester = tabFocusRequester,
        onSelectedChanged = { selectedTab = it as DynamicTab }
    )
    Box(Modifier.weight(1f).onFocusChanged { contentHasFocus = it.hasFocus }) {
        when (selectedTab) { DynamicTab.Video -> …现有网格…; DynamicTab.All -> DynamicWaterfall(...) }
    }
}
```

`navSwitchMode` 用 `Prefs.navSwitchModeFlow.collectAsStateLazily { Prefs.navSwitchMode }`（与 `HomeContent` 一致）。

- [ ] **Step 2: 视频网格保持原样，只删掉顶部「关注」小字**

删除 `Text(text = stringResource(R.string.entry_follow_screen), …)` 及其 `.offset(...)`（该字符串在本页没有别的用处）。网格本身、`VideoActionMenu`、`blockDownFocusExitAtGridEnd` 全部保留。`if (dynamicViewModel.isLogin) … else "请先登录"` 的外层判断保留。

- [ ] **Step 3: 全部 tab 接 `DynamicWaterfall`**

```kotlin
DynamicWaterfall(
    items = dynamicViewModel.dynamicAllList,
    state = allState,
    onItemClick = { openDynamic(context, it, videoInfoRepository, dynamicViewModel.dynamicAllList) },
    onLoadMore = { scope.launch(Dispatchers.IO) { dynamicViewModel.loadMoreAll() } },
    modifier = Modifier.fillMaxSize(),
    footer = {
        SpaceContentStatus(
            loading = dynamicViewModel.all.loading,
            error = dynamicViewModel.all.error,
            hasMore = dynamicViewModel.all.hasMore
        ) { scope.launch(Dispatchers.IO) { dynamicViewModel.loadMoreAll() } }
    }
)
```

首次切到「全部」且列表为空且 `all.hasMore` 时触发一次加载（`LaunchedEffect(selectedTab) { if (selectedTab == DynamicTab.All && dynamicViewModel.dynamicAllList.isEmpty() && dynamicViewModel.all.hasMore) loadMoreAll() }`）。

- [ ] **Step 4: 返回键：内容区 → tab 行**

```kotlin
BackHandler(enabled = contentHasFocus) { tabFocusRequester.requestFocus() }
```

（面板分支在 Task 7 接到这里；本任务只处理无面板的情况。）

- [ ] **Step 5: `HomeContent` 状态提升与重载**

- 新增 `val dynamicAllState = rememberLazyStaggeredGridState()`，`DynamicsScreen(lazyGridState = dynamicState, allState = dynamicAllState)`。
- `initData()` 的 `HomeTopNavItem.Dynamics` 分支：`if (dynamicVideoList.isEmpty()) loadMoreVideo()` 保留，并补 `if (dynamicAllList.isEmpty() && all.hasMore && <上次停在全部分支>) loadMoreAll()`——简单做法：两个列表都空才预载视频；「全部」交给 `DynamicsScreen` 的 `LaunchedEffect`，`initData` 不动全部。
- `onClick` 的 `HomeTopNavItem.Dynamics` 分支：`clearVideoData()` 后补 `clearAllData()`，再各按需重拉（视频仍立即重拉；全部交给 `DynamicsScreen` 的 `LaunchedEffect` 在切到该 tab 时拉）。

- [ ] **Step 6: 编译验证**

Run: `./gradlew :app:tv:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: 真机确认**

切换两个 tab、各自加载更多与到底提示、进出首页其它 tab 后滚动位置保留、内容区按 Back 回到 tab 行、tab 行按 Back 回到左侧抽屉。

- [ ] **Step 8: 提交**

```bash
git add app/tv/src/main/kotlin/dev/aaa1115910/bv/tv/screens/main/home/DynamicsScreen.kt \
        app/tv/src/main/kotlin/dev/aaa1115910/bv/tv/screens/main/HomeContent.kt
git commit -m "feat(tv): 首页动态页新增视频/全部二级 tab，全部 tab 用 3 列瀑布流"
```

---

### Task 4: 全部 tab 的长按操作菜单

**Files:**
- Create: `app/tv/src/main/kotlin/dev/aaa1115910/bv/tv/component/DynamicActionMenu.kt`
- Modify: `app/tv/src/main/kotlin/dev/aaa1115910/bv/tv/screens/user/UpSpaceDynamicCards.kt`（`SpaceDynamicCard` 增加 `onLongClick`）
- Modify: `app/tv/src/main/kotlin/dev/aaa1115910/bv/tv/screens/user/DynamicWaterfall.kt`（增加 `onItemLongClick`）
- Modify: `app/tv/src/main/kotlin/dev/aaa1115910/bv/tv/screens/main/home/DynamicsScreen.kt`（接线）

**Interfaces:**
- Produces: `SpaceDynamicCard(dynamic, modifier, onClick, onFocus, onLongClick: () -> Unit = {})`
- Produces: `DynamicWaterfall(..., onItemLongClick: ((DynamicItem) -> Unit)? = null)`（null = 不接管长按）
- Produces: `@Composable fun DynamicActionMenu(show: Boolean, dynamic: DynamicItem, onDismiss: () -> Unit)`

- [ ] **Step 1: `SpaceDynamicCard` 增加长按**

在 `Surface` 上补 `onLongClick = onLongClick`，签名加 `onLongClick: () -> Unit = {}`（放最后，保持现有调用点兼容）。

- [ ] **Step 2: `DynamicWaterfall` 透传长按**

签名加 `onItemLongClick: ((DynamicItem) -> Unit)? = null`；有值时在网格上挂 `.onMenuKeyDown { focusedIndex.takeIf { it >= 0 }?.let { onItemLongClick(items.getOrNull(it) ?: return@onMenuKeyDown) } }`，并给卡片传 `onLongClick = { onItemLongClick?.invoke(d) }`。`onMenuKeyDown` 用 `dev.aaa1115910.bv.tv.util.onMenuKeyDown`（`DynamicsScreen` 已在用）。

- [ ] **Step 3: 写 `DynamicActionMenu`**

```kotlin
@Composable
fun DynamicActionMenu(
    show: Boolean,
    dynamic: DynamicItem,
    onDismiss: () -> Unit
)
```

「详情」的路由菜单内部自己决定（与点击行为一致，但视频走强制详情页）：
- 视频卡 → `VideoInfoActivity.actionStart(context, aid = video.aid, forceShowDetail = true)`
- 图文卡 → `dynamic.id?.toLongOrNull()?.let { ArticleActivity.actionStart(context, it) }`
- 其余 → `dynamic.id?.let { DynamicDetailActivity.actionStart(context, it) }`

分派规则（`when` 三支，顺序重要：先视频，再图文，其余）：

1. **视频卡**（`dynamic.video != null`）：委托给现有 `VideoActionMenu`，不改它：
   ```kotlin
   VideoActionMenu(
       show = show,
       aid = dynamic.video!!.aid,
       upId = dynamic.author.mid,
       upName = dynamic.author.author,
       upFace = dynamic.author.avatar,
       onDismiss = onDismiss,
       showUpInfoAction = true,
       resourceType = FavoriteResourceType.Video,
       extraItems = listOf(VideoActionMenuExtraItem(CardActionMenuItem(Icons.Rounded.People, "已关注 UP 列表") {
           context.startActivity(Intent(context, FollowActivity::class.java))
       }))
   )
   ```
   「详情」由 `VideoActionMenu` 自己的 `VideoInfoActivity(actionStart with forceShowDetail = true)` 提供（`onDetailClick` 传 null 即可）。
2. **图文卡**（`dynamic.type == DynamicType.Draw && dynamic.blocked == null && dynamic.id?.toLongOrNull() != null`）：`CardActionMenu` + 本地点赞/收藏覆盖：
   ```kotlin
   var liked by remember(dynamic.id) { mutableStateOf(dynamic.footer?.liked == true) }
   var favorited by remember(dynamic.id) { mutableStateOf(dynamic.footer?.favorited == true) }
   ```
   项：UP 主页（`UpInfoActivity`）→ 详情（`onDetail`）→ [登录] 点赞（`LikeRepository.setDynamicLike(id, !liked)`，成功后翻转 `liked`）→ [登录] 收藏（`FavoriteRepository.setOpusFavorite(opusId = id.toLong(), favorite = !favorited)`，成功后翻转）→ 已关注 UP 列表。异步调用放 `remember { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }`，异常只 log（`KotlinLogging`）。
3. **其余卡**：`CardActionMenu`，项为 UP 主页 → 详情（`onDetail`）→ [登录] 点赞（`setDynamicLike`）→ 已关注 UP 列表。

「UP 主页」项：`if (dynamic.author.mid > 0) UpInfoActivity.actionStart(context, mid = dynamic.author.mid, name = dynamic.author.author, face = dynamic.author.avatar)`。图标沿用 `Icons.Rounded.Person / Info / ThumbUp(Outlined 未点赞) / Favorite(或 FavoriteBorder) / People`，已操作态用 `Color(0xfffb7299)`（与 `VideoActionMenu` 一致）。

- [ ] **Step 4: `DynamicsScreen` 接线**

```kotlin
var menuDynamic by remember { mutableStateOf<DynamicItem?>(null) }
// DynamicWaterfall(..., onItemLongClick = { menuDynamic = it })
menuDynamic?.let { item ->
    DynamicActionMenu(
        show = true,
        dynamic = item,
        onDismiss = { menuDynamic = null }
    )
}
```

- [ ] **Step 5: 编译验证**

Run: `./gradlew :app:tv:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: 真机逐类验证**

视频卡：UP 主页 / 详情 / 点赞 / 收藏（收藏夹弹窗）/ 投币 / 稍后再看 / 已关注 UP 列表。图文卡：无投币、无稍后再看，收藏直接翻转。文章/转发/直播/充电专属：只有 UP 主页 / 详情 / 点赞 / 已关注 UP 列表。长按与 MENU 键都能打开；长按后松手不误触菜单项。

- [ ] **Step 7: 提交**

```bash
git add app/tv/src/main/kotlin/dev/aaa1115910/bv/tv/component/DynamicActionMenu.kt \
        app/tv/src/main/kotlin/dev/aaa1115910/bv/tv/screens/user/UpSpaceDynamicCards.kt \
        app/tv/src/main/kotlin/dev/aaa1115910/bv/tv/screens/user/DynamicWaterfall.kt \
        app/tv/src/main/kotlin/dev/aaa1115910/bv/tv/screens/main/home/DynamicsScreen.kt
git commit -m "feat(tv): 动态全部 tab 支持长按操作菜单"
```

---

### Task 5: UP 列表的共用纯函数与缓存（先测试）

**Files:**
- Create: `app/shared/src/main/kotlin/dev/aaa1115910/bv/util/FollowedUps.kt`
- Create: `app/shared/src/test/kotlin/dev/aaa1115910/bv/util/FollowedUpsTest.kt`
- Modify: `app/shared/src/main/kotlin/dev/aaa1115910/bv/util/Prefs.kt`（新增 4 个键）
- Modify: `app/shared/src/main/kotlin/dev/aaa1115910/bv/viewmodel/user/FollowViewModel.kt`（改用 `sortFollowedUps`）
- Modify: `app/tv/src/main/kotlin/dev/aaa1115910/bv/tv/screens/user/FollowScreen.kt`（改用 `matchesUpKeyword`）

**Interfaces:**
- Produces: `data class FollowedUp(val mid: Long, val name: String, val avatar: String, val sign: String)`
- Produces: `fun List<FollowedUser>.toFollowedUps(): List<FollowedUp>`（或 `FollowedUp.from`，二选一，保持一处）
- Produces: `fun <T> sortFollowedUps(users: List<T>, mid: (T) -> Long, name: (T) -> String): List<T>`
- Produces: `fun matchesUpKeyword(keyword: String, vararg texts: String): Boolean`
- Produces: `fun encodeFollowedUps(ups: List<FollowedUp>): String`、`fun decodeFollowedUps(raw: String): List<FollowedUp>`、`fun isCacheFresh(savedAtMs: Long, nowMs: Long): Boolean`（TTL 常量在本文件）
- Produces: `object FollowedUpCache { suspend fun load(): List<FollowedUp>?; suspend fun save(ups: List<FollowedUp>) }`（`load` 过期或解析空时返回 null）

- [ ] **Step 1: 写失败的测试 `FollowedUpsTest.kt`**

```kotlin
package dev.aaa1115910.bv.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FollowedUpsTest {
    @Test
    fun `cache survives encode and decode roundtrip`() {
        val ups = listOf(
            FollowedUp(1L, "张三", "http://a/1.jpg", "签名 一"),
            FollowedUp(2L, "up two", "http://a/2.jpg", "")
        )
        assertEquals(ups, decodeFollowedUps(encodeFollowedUps(ups)))
    }

    @Test
    fun `broken cache lines are skipped instead of throwing`() {
        assertEquals(emptyList(), decodeFollowedUps(""))
        assertEquals(listOf(FollowedUp(7L, "ok", "f", "s")), decodeFollowedUps("garbage\n7\tok\tf\ts\n\n1\t2"))
    }

    @Test
    fun `cache is fresh only within 24 hours`() {
        val now = 1_000_000_000_000L
        assertTrue(isCacheFresh(savedAtMs = now - 23 * 60 * 60 * 1000L, nowMs = now))
        assertFalse(isCacheFresh(savedAtMs = now - 24 * 60 * 60 * 1000L, nowMs = now))
        assertFalse(isCacheFresh(savedAtMs = now + 60 * 60 * 1000L, nowMs = now)) // 时钟回拨
        assertFalse(isCacheFresh(savedAtMs = 0L, nowMs = now))
    }

    @Test
    fun `keyword matching ignores whitespace and case`() {
        assertTrue(matchesUpKeyword("up tw", "UP Two", ""))
        assertTrue(matchesUpKeyword("张三", "张三", "签名"))
        assertTrue(matchesUpKeyword("", "任意", ""))
        assertFalse(matchesUpKeyword("abc", "ab", ""))
    }

    @Test
    fun `sort puts ascii names before chinese ones`() {
        val sorted = sortFollowedUps(
            listOf(FollowedUp(1, "阿婆", "", ""), FollowedUp(2, "up", "", "")),
            mid = { it.mid },
            name = { it.name }
        )
        assertEquals(listOf(2L, 1L), sorted.map { it.mid })
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :app:shared:testDebugUnitTest --tests "dev.aaa1115910.bv.util.FollowedUpsTest"`
Expected: 编译失败（`FollowedUp` / 各函数未定义）

- [ ] **Step 3: 写 `FollowedUps.kt`**

- 序列化格式：`mid\tname\tavatar\tsign`，一行一个；写入时把每个字段里的 `\t`/`\n` 替换成空格；解析用 `split('\t', limit = 4)`，不足 4 段或 `mid` 解析失败则跳过该行。
- `isCacheFresh`：`savedAtMs > 0 && nowMs >= savedAtMs && nowMs - savedAtMs < CACHE_TTL_MS`（未来时间戳视为不新鲜）；`CACHE_TTL_MS = 24 * 60 * 60 * 1000L`。
- `matchesUpKeyword`：`keyword.filterNot { it.isWhitespace() }` 为空→true；否则每个字符都要在任一 `texts` 上 `contains(char, ignoreCase = true)`。
- `sortFollowedUps`：把 `FollowViewModel.sortUsers` 的规则整体搬来（ASCII 开头优先，两段各自 `Collator.getInstance(Locale.CHINA)` 排序），输入输出改为 `FollowedUp`。
- `FollowedUpCache`：DataStore 读写照 `CdnSpeedStore` 的写法——
  - 读：`PrefKeys.prefFollowedUpsCacheTimeRequest` 取时间戳 → `isCacheFresh` → 否则返回 null；新鲜则 `decodeFollowedUps(BVApp.dataStoreManager.getPreference(PrefKeys.prefFollowedUpsCacheRequest))`，空结果返回 null。
  - 写：先存 `encodeFollowedUps`，再存 `System.currentTimeMillis()`。

- [ ] **Step 4: 加 `Prefs` 键**

`PrefKeys` 内：

```kotlin
val prefFollowedUpsCacheKey = stringPreferencesKey("followed_ups_cache")
val prefFollowedUpsCacheRequest = PreferenceRequest(prefFollowedUpsCacheKey, "")
val prefFollowedUpsCacheTimeKey = longPreferencesKey("followed_ups_cache_time")
val prefFollowedUpsCacheTimeRequest = PreferenceRequest(prefFollowedUpsCacheTimeKey, 0L)
```

- [ ] **Step 5: `FollowViewModel` 与 `FollowScreen` 改用共用实现**

`sortFollowedUps` 写成泛型，两处直接复用（避免 `FollowedUp` ↔ `FollowedUser` 互转）：

```kotlin
fun <T> sortFollowedUps(users: List<T>, mid: (T) -> Long, name: (T) -> String): List<T>
```

- `FollowViewModel`：删掉私有 `sortUsers`，改调 `sortFollowedUps(followedUserList, FollowedUser::mid, FollowedUser::name)`（`FollowedUser` 的 `mid`/`name` 是属性，用 `{ it.mid }` / `{ it.name }`）。
- `FollowScreen`：把 `filteredUsers` 里的字符匹配换成 `matchesUpKeyword(keyword, up.name, up.sign)`。

- [ ] **Step 6: 跑测试确认通过**

Run: `./gradlew :app:shared:testDebugUnitTest --tests "dev.aaa1115910.bv.util.FollowedUpsTest"` 然后 `./gradlew :app:tv:compileDebugKotlin`
Expected: 全绿 + BUILD SUCCESSFUL

- [ ] **Step 7: 提交**

```bash
git add app/shared/src/main/kotlin/dev/aaa1115910/bv/util/FollowedUps.kt \
        app/shared/src/test/kotlin/dev/aaa1115910/bv/util/FollowedUpsTest.kt \
        app/shared/src/main/kotlin/dev/aaa1115910/bv/util/Prefs.kt \
        app/shared/src/main/kotlin/dev/aaa1115910/bv/viewmodel/user/FollowViewModel.kt \
        app/tv/src/main/kotlin/dev/aaa1115910/bv/tv/screens/user/FollowScreen.kt
git commit -m "feat: 抽出已关注 UP 的排序/匹配/序列化纯函数与 24h 缓存"
```

---

### Task 6: `getFollowedUserPage` 与 `FollowedUpPanelViewModel`

**Files:**
- Modify: `bili-api/src/main/kotlin/dev/aaa1115910/biliapi/entity/user/FollowedUser.kt`（新增 `FollowedUserPage`）
- Create: `bili-api/src/test/kotlin/dev/aaa1115910/biliapi/entity/user/FollowedUserPageTest.kt`
- Modify: `bili-api/src/main/kotlin/dev/aaa1115910/biliapi/repositories/UserRepository.kt`
- Create: `app/shared/src/main/kotlin/dev/aaa1115910/bv/viewmodel/home/FollowedUpPanelViewModel.kt`

**Interfaces:**
- Produces: `data class FollowedUserPage(val users: List<FollowedUser>, val total: Int, val hasMore: Boolean)`
- Produces: `fun FollowedUserPage.Companion.from(users: List<FollowedUser>, page: Int, pageSize: Int, total: Int): FollowedUserPage`
- Produces: `suspend fun UserRepository.getFollowedUserPage(mid: Long, page: Int, preferApiType: ApiType = ApiType.Web): FollowedUserPage`
- Produces: `FollowedUpPanelViewModel.loadFirstPage()/loadMore()/refresh()/search(keyword)/selectUp(mid)` + 状态 `ups`/`paging`/`keyword`/`selectedMid`/`searching`

- [ ] **Step 1: 写失败的测试 `FollowedUserPageTest.kt`**

```kotlin
package dev.aaa1115910.biliapi.entity.user

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FollowedUserPageTest {
    @Test
    fun `has more only while page end is below total`() {
        assertTrue(FollowedUserPage.from(emptyList(), page = 1, pageSize = 50, total = 120).hasMore)
        assertTrue(FollowedUserPage.from(emptyList(), page = 2, pageSize = 50, total = 120).hasMore)
        assertFalse(FollowedUserPage.from(emptyList(), page = 3, pageSize = 50, total = 120).hasMore)
    }

    @Test
    fun `cached prefix keeps paging possible`() {
        // 缓存里只有第 1 页时，total 仍然大于 50，必须能继续翻页
        val page = FollowedUserPage.from(emptyList(), page = 1, pageSize = 50, total = 900)
        assertTrue(page.hasMore)
        assertTrue(page.total == 900)
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :bili-api:test --tests "dev.aaa1115910.biliapi.entity.user.FollowedUserPageTest"`
Expected: 编译失败（`FollowedUserPage` 未定义）

- [ ] **Step 3: 实现 `FollowedUserPage`**

`app/.../entity/user/FollowedUser.kt` 同文件追加：

```kotlin
data class FollowedUserPage(
    val users: List<FollowedUser>,
    val total: Int,
    val hasMore: Boolean
) {
    companion object {
        fun from(users: List<FollowedUser>, page: Int, pageSize: Int, total: Int) = FollowedUserPage(
            users = users,
            total = total,
            hasMore = page * pageSize < total
        )
    }
}
```

- [ ] **Step 4: 实现 `UserRepository.getFollowedUserPage` 并把 `getFollowedUsers` 改为循环调用**

```kotlin
suspend fun getFollowedUserPage(
    mid: Long,
    page: Int,
    preferApiType: ApiType = ApiType.Web,
    pageSize: Int = 50
): FollowedUserPage {
    val response = when (preferApiType) {
        ApiType.Web -> BiliHttpApi.getUserFollow(mid = mid, pageSize = pageSize, pageNumber = page, sessData = authRepository.sessionData!!)
        ApiType.App -> BiliHttpApi.getUserFollow(mid = mid, pageSize = pageSize, pageNumber = page, accessKey = authRepository.accessToken!!)
    }.getResponseData()
    return FollowedUserPage.from(
        users = response.list.map { FollowedUser.fromHttpFollowedUser(it) },
        page = page,
        pageSize = pageSize,
        total = response.total
    )
}

suspend fun getFollowedUsers(mid: Long, preferApiType: ApiType = ApiType.Web): List<FollowedUser> {
    val first = getFollowedUserPage(mid, page = 1, preferApiType = preferApiType)
    val result = first.users.toMutableList()
    if (!first.hasMore) return result
    val pageCount = ceil(first.total.toFloat() / 50).toInt()
    withContext(Dispatchers.IO) {
        (2..pageCount).map { page -> async { getFollowedUserPage(mid, page, preferApiType) } }
            .awaitAll()
            .forEach { result.addAll(it.users) }
    }
    return result
}
```

- [ ] **Step 5: 跑测试确认通过**

Run: `./gradlew :bili-api:test --tests "dev.aaa1115910.biliapi.entity.user.FollowedUserPageTest"`
Expected: PASS

- [ ] **Step 6: 写 `FollowedUpPanelViewModel`**

```kotlin
@KoinViewModel
class FollowedUpPanelViewModel(private val userRepository: UserRepository) : ViewModel() {
    private val pagedUps = mutableStateListOf<FollowedUp>()
    val ups = mutableStateListOf<FollowedUp>()
    val paging = PagingState()
    var keyword by mutableStateOf("")
        private set
    var selectedMid by mutableStateOf<Long?>(null)
        private set
    var searching by mutableStateOf(false)
        private set
    var loadFailed by mutableStateOf(false)  // 全量拉取失败时给面板显示重试

    fun loadFirstPage()
    fun loadMore()
    fun refresh()
    fun search(keyword: String)
    fun selectUp(mid: Long?)
    fun clearSearch()   // keyword = ""; ups 回填 pagedUps
}
```

行为约定：
- `loadFirstPage()`：已加载过（`pagedUps.isNotEmpty()`）直接 return；`FollowedUpCache.load()` 非 null → 回填 `pagedUps`/`ups`，并把 `paging` 视为还有更多（用 `paging.hasMore` 默认 true 即可，因为缓存只是前缀）；否则 `loadMore()`。
- `loadMore()`：`paging.load(fetch = { page, _ -> getFollowedUserPage(Prefs.uid, page, Prefs.apiType) 然后 PageResult(page.users.toFollowedUps(), page.hasMore, nextPage = page + 1) }, append = { pagedUps.addAll(it); if (keyword.isBlank()) ups.addAll(it) })`，成功后 `FollowedUpCache.save(pagedUps.toList())`。
- `search(keyword)`：写 `this.keyword`；空 → `ups.clear(); ups.addAll(pagedUps)`；非空 → `viewModelScope.launch { searching = true; val all = userRepository.getFollowedUsers(Prefs.uid, Prefs.apiType).toFollowedUps(); ups.clear(); ups.addAll(sortFollowedUps(all.filter { matchesUpKeyword(keyword, it.name, it.sign) })); searching = false }`（失败置 `loadFailed = true`）。
- `refresh()`：`keyword.isBlank()` → 清缓存与 `pagedUps`/`ups`、`paging.reset()`、`loadMore()`；否则 → 重新走 `search(keyword)` 的全量拉取。
- `selectUp(mid)`：`selectedMid = mid`。
- 未登录直接 return（`Prefs.isLogin`）。

- [ ] **Step 7: 编译验证**

Run: `./gradlew :bili-api:compileKotlin :app:shared:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 8: 提交**

```bash
git add bili-api/src/main/kotlin/dev/aaa1115910/biliapi/entity/user/FollowedUser.kt \
        bili-api/src/test/kotlin/dev/aaa1115910/biliapi/entity/user/FollowedUserPageTest.kt \
        bili-api/src/main/kotlin/dev/aaa1115910/biliapi/repositories/UserRepository.kt \
        app/shared/src/main/kotlin/dev/aaa1115910/bv/viewmodel/home/FollowedUpPanelViewModel.kt
git commit -m "feat: 已关注 UP 支持分页拉取与面板 ViewModel"
```

---

### Task 7: 左侧 UP 面板 UI、设置项与焦点链

**Files:**
- Modify: `app/shared/src/main/kotlin/dev/aaa1115910/bv/util/Prefs.kt`（`showFollowedUpsInDynamics` + Flow）
- Modify: `app/tv/src/main/kotlin/dev/aaa1115910/bv/tv/screens/settings/content/UISetting.kt`
- Create: `app/tv/src/main/kotlin/dev/aaa1115910/bv/tv/screens/main/home/FollowedUpPanel.kt`
- Modify: `app/tv/src/main/kotlin/dev/aaa1115910/bv/tv/screens/main/home/DynamicsScreen.kt`

**Interfaces:**
- Consumes: Task 6 的 `FollowedUpPanelViewModel`
- Produces: `Prefs.showFollowedUpsInDynamics: Boolean`、`Prefs.showFollowedUpsInDynamicsFlow: Flow<Boolean>`
- Produces: `@Composable fun FollowedUpPanel(modifier: Modifier, viewModel: FollowedUpPanelViewModel, focusRequester: FocusRequester, onSelectedUpChange: (Long?) -> Unit, onBack: () -> Unit)`
- Produces（本任务先落最小可用版本，Task 8 再补拉取）: `DynamicViewModel.selectedUp: Long?`（`private set`）+ `fun selectUp(mid: Long?) { selectedUp = mid }`
- Produces: `DynamicsScreen(..., panelViewModel: FollowedUpPanelViewModel = koinViewModel())`

- [ ] **Step 1: 设置项**

`Prefs`：`var showFollowedUpsInDynamics`（`get`/`set` 同 `showUGCVideoInfo`）+ `val showFollowedUpsInDynamicsFlow: Flow<Boolean> get() = dsm.getPreferenceFlow(PrefKeys.prefShowFollowedUpsInDynamicsRequest)`；`PrefKeys` 里加 `booleanPreferencesKey("pref_show_followed_ups_in_dynamics")` 与 `PreferenceRequest(..., false)`。

`UISetting.kt`：在 `showUGCVideoInfo` 那组之后加 `item { SettingSwitchListItem(title = "动态页显示已关注 UP 列表", supportText = "左侧显示已关注 UP，可按 UP 过滤动态（视频 tab 变 3 列）", checked = showFollowedUps, onCheckedChange = { showFollowedUps = it; Prefs.showFollowedUpsInDynamics = it }) }`，`showFollowedUps` 用 `remember { mutableStateOf(Prefs.showFollowedUpsInDynamics) }`。

- [ ] **Step 2: 写 `FollowedUpPanel`**

`Column(Modifier.width(240.dp).fillMaxHeight())`，内部自上而下：
1. 标题 `Text("已关注 UP")`；
2. 搜索行 `Row`：`OutlinedTextField(state = searchState, …照抄 FollowScreen 的尺寸/颜色/leadingIcon/imeAction)` + `Surface(onClick = { viewModel.search(searchState.text.toString()) }) { Text("搜索", Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) }` + `IconButton(onClick = { viewModel.refresh() }) { Icon(Icons.Rounded.Refresh, contentDescription = "刷新") }`；
3. `LazyColumn`：第一项 `PanelRow(text = "全部", selected = viewModel.selectedMid == null, onClick = { viewModel.selectUp(null) })`；随后 `itemsIndexed(viewModel.ups, key = { _, up -> up.mid })` → `PanelRow(avatar = up.avatar, text = up.name, selected = viewModel.selectedMid == up.mid, onClick = { … })`；
4. 列表末尾：`viewModel.paging.loading` → `Text("加载中…")`；`viewModel.paging.error != null` → 文案 + `Surface(onClick = viewModel::loadMore) { Text("重试") }`；分页且 `paging.hasMore` → 「加载更多」按钮。

`PanelRow` 为文件内私有 `@Composable`：`Surface(onClick, colors = ClickableSurfaceDefaults.colors(containerColor = if (selected) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f) else Color.Transparent, …), border = …)`，内容 `Row { AsyncImage(40.dp, CircleShape); Text(name, maxLines = 1, overflow = Ellipsis) }`。

焦点行为：
- 卡片 `onFocusChanged { if (it.hasFocus) selectWithDebounce(up.mid) }`，`selectWithDebounce` 用 `LaunchedEffect(selectedCandidate) { delay(300); viewModel.selectUp(mid); onSelectedUpChange(mid) }`（对「全部」用 `mid = null`）；
- `index + 5 > ups.size && paging.hasMore && keyword.isBlank()` → `viewModel.loadMore()`；
- `LaunchedEffect(Unit) { viewModel.loadFirstPage() }`。

- [ ] **Step 3: `DynamicsScreen` 接入面板与列数**

先给 `DynamicViewModel` 落一个最小状态（**本任务只保存选中 UP，不拉数据**；Task 8 才让它真正按 UP 过滤）：

```kotlin
var selectedUp by mutableStateOf<Long?>(null)
    private set

fun selectUp(mid: Long?) {
    selectedUp = mid
}
```

然后：

```kotlin
val showPanel by Prefs.showFollowedUpsInDynamicsFlow.collectAsStateLazily { Prefs.showFollowedUpsInDynamics }
val panelFocusRequester = remember { FocusRequester() }
Row(Modifier.weight(1f)) {
    if (showPanel) FollowedUpPanel(
        modifier = Modifier.fillMaxHeight(),
        viewModel = panelViewModel,
        focusRequester = panelFocusRequester,
        onSelectedUpChange = { dynamicViewModel.selectUp(it) },
        onBack = { tabFocusRequester.requestFocus() }
    )
    Box(Modifier.weight(1f).onFocusChanged { contentHasFocus = it.hasFocus }) {
        when (selectedTab) { … columns = if (showPanel) 3 else 4 … }
    }
}
```

视频网格的 `columnCount`/`GridCells.Fixed` 两处都换成该变量。

- [ ] **Step 4: 焦点链与返回**

职责划分是单值的：

- **父级（`DynamicsScreen`）**只跟踪**右侧内容区**的焦点（`Box(Modifier.weight(1f).onFocusChanged { contentHasFocus = it.hasFocus })`，不跟踪整个 `Row`）：
  ```kotlin
  BackHandler(enabled = contentHasFocus) {
      if (showPanel) panelFocusRequester.requestFocus() else tabFocusRequester.requestFocus()
  }
  ```
- **面板自己**跟踪根节点的焦点并声明自己的返回处理，构建时由父级传入回退动作：
  ```kotlin
  @Composable
  fun FollowedUpPanel(
      modifier: Modifier = Modifier,
      viewModel: FollowedUpPanelViewModel,
      focusRequester: FocusRequester,
      onSelectedUpChange: (Long?) -> Unit,
      onBack: () -> Unit
  )
  ```
  面板内部：`var hasFocus by remember { mutableStateOf(false) }`、根节点 `.onFocusChanged { hasFocus = it.hasFocus }`、`BackHandler(enabled = hasFocus) { onBack() }`；`focusRequester` 挂在「当前选中项」（`selectedMid` 对应行，默认「全部」）上。父级传 `onBack = { tabFocusRequester.requestFocus() }`。

这样两个 `BackHandler` 的启用条件互斥，不会同时触发。

- [ ] **Step 5: 编译验证**

Run: `./gradlew :app:tv:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: 真机验证**

开关出现在界面设置并即时生效；面板可浏览、搜关键字后「搜索」出过滤结果、「刷新」忽略缓存重拉；UP 列表滚动分页；焦点链 内容→面板→tab→抽屉；面板开时视频 tab 3 列、关掉回到 4 列。

- [ ] **Step 7: 提交**

```bash
git add app/shared/src/main/kotlin/dev/aaa1115910/bv/util/Prefs.kt \
        app/tv/src/main/kotlin/dev/aaa1115910/bv/tv/screens/settings/content/UISetting.kt \
        app/tv/src/main/kotlin/dev/aaa1115910/bv/tv/screens/main/home/FollowedUpPanel.kt \
        app/tv/src/main/kotlin/dev/aaa1115910/bv/tv/screens/main/home/DynamicsScreen.kt
git commit -m "feat(tv): 动态页左侧已关注 UP 面板与界面设置开关"
```

---

### Task 8: 按 UP 过滤两个 tab 的动态

**Files:**
- Modify: `bili-api/src/main/kotlin/dev/aaa1115910/biliapi/entity/user/Dynamic.kt`（`DynamicVideo.fromDynamicItem`）
- Create: `bili-api/src/test/kotlin/dev/aaa1115910/biliapi/entity/user/DynamicVideoItemMappingTest.kt`
- Modify: `app/shared/src/main/kotlin/dev/aaa1115910/bv/viewmodel/home/DynamicViewModel.kt`
- Modify: `app/tv/src/main/kotlin/dev/aaa1115910/bv/tv/screens/main/home/DynamicsScreen.kt`

**Interfaces:**
- Produces: `fun DynamicVideo.Companion.fromDynamicItem(item: DynamicItem): DynamicVideo`
- Produces: `DynamicViewModel.selectedUp: Long?`（Task 7 已提供最小版本，本任务改为加载数据）、`val currentAll: List<DynamicItem>`、`val currentVideos: List<DynamicVideo>`、`val currentAllPaging: PagingState`、`val currentVideoPaging: PagingState`、`fun selectUp(mid: Long?)`、`fun loadMoreForSelection()`、`fun refreshSelection()`

- [ ] **Step 1: 写失败的映射测试**

```kotlin
package dev.aaa1115910.biliapi.entity.user

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DynamicVideoItemMappingTest {
    private fun item(badgeText: String = "") = DynamicItem(
        id = "1",
        type = DynamicType.Av,
        author = DynamicItem.DynamicAuthorModule(
            author = "up", avatar = "face", mid = 42L, pubTime = "1小时前", pubAction = ""
        ),
        video = DynamicItem.DynamicVideoModule(
            aid = 100L, bvid = "BV1", cid = 0,
            title = "动态视频｜标题", text = "", cover = "cover",
            duration = "01:02", play = "1.2万", danmaku = "34",
            badgeText = badgeText, playCount = 12000L, danmakuCount = 34, durationSeconds = 62
        )
    )

    @Test
    fun `module is mapped to a playable video`() {
        val video = DynamicVideo.fromDynamicItem(item())
        assertEquals(100L, video.aid)
        assertEquals("标题", video.title)
        assertEquals(12000L, video.play)
        assertEquals(34, video.danmaku)
        assertEquals(62, video.duration)
        assertEquals(42L, video.authorId)
        assertEquals("face", video.authorFace)
        assertFalse(video.isChargingArc)
    }

    @Test
    fun `charging badge marks the video as charging arc`() {
        val video = DynamicVideo.fromDynamicItem(item(badgeText = "充电专属"))
        assertTrue(video.isChargingArc)
        assertEquals("充电专属", video.chargingArcBadge)
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :bili-api:test --tests "dev.aaa1115910.biliapi.entity.user.DynamicVideoItemMappingTest"`
Expected: 编译失败（`fromDynamicItem` 未定义）

- [ ] **Step 3: 实现 `DynamicVideo.fromDynamicItem`**

```kotlin
fun fromDynamicItem(item: DynamicItem): DynamicVideo {
    val video = requireNotNull(item.video) { "dynamic has no video module" }
    val isChargingArc = video.badgeText.contains("充电") || video.badgeText.contains("限时免费")
    return DynamicVideo(
        aid = video.aid,
        bvid = video.bvid,
        cid = video.cid,
        epid = video.epid,
        seasonId = video.seasonId,
        title = video.title.replace("动态视频｜", ""),
        cover = video.cover,
        author = item.author.author,
        authorId = item.author.mid,
        authorFace = item.author.avatar,
        duration = video.durationSeconds,
        play = video.playCount,
        danmaku = video.danmakuCount,
        avatar = item.author.avatar,
        pubTime = item.author.pubTime,
        isChargingArc = isChargingArc,
        chargingArcBadge = if (isChargingArc) video.badgeText else ""
    )
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `./gradlew :bili-api:test --tests "dev.aaa1115910.biliapi.entity.user.DynamicVideoItemMappingTest"`
Expected: PASS

- [ ] **Step 5: `DynamicViewModel` 增加按 UP 过滤**

按 spec §5.4 落地：

```kotlin
private class UpFeed {
    val items = mutableStateListOf<DynamicItem>()
    val paging = PagingState()
}
private val upFeeds = mutableMapOf<Long, UpFeed>()
var selectedUp by mutableStateOf<Long?>(null)
    private set

val currentAll: List<DynamicItem>
    get() = selectedUp?.let { upFeeds[it]?.items } ?: dynamicAllList
val currentVideos: List<DynamicVideo>
    get() = if (selectedUp == null) dynamicVideoList
    else currentAll.filter { it.video != null }.map { DynamicVideo.fromDynamicItem(it) }
val currentAllPaging: PagingState
    get() = selectedUp?.let { upFeeds[it]?.paging } ?: all
val currentVideoPaging: PagingState
    get() = if (selectedUp == null) videos else currentAllPaging

fun selectUp(mid: Long?) { … 见下 … }
fun loadMoreForSelection() { … }
fun refreshSelection() { … }
```

- `selectUp(mid)`：`selectedUp = mid`；`mid == null` 直接返回（关注流已有数据）；若 `upFeeds[mid]` 不存在则创建并 `loadUpFeed(mid, reset = true)`。
- `loadUpFeed(mid, reset)`：`withContext(Dispatchers.Main) { feed.paging.load(fetch = { _, offset -> val data = userRepository.getDynamicsByUp(mid, offset); val known = feed.items.mapNotNullTo(mutableSetOf()) { it.id }; PageResult(data.dynamics.newItems(known) { it.id }, data.hasMore, nextOffset = data.historyOffset) }, append = feed.items::addAll) }`（`reset` 时先 `feed.paging.reset(); feed.items.clear()`）。
- `loadMoreForSelection()`：`selectedUp?.let { loadUpFeed(it, reset = false) }`，`selectedUp == null` 时按当前 tab 拉关注流（由 UI 决定调 `loadMoreVideo`/`loadMoreAll`，这里不做）。
- `refreshSelection()`：`selectedUp?.let { loadUpFeed(it, reset = true) }`。

- [ ] **Step 6: `DynamicsScreen` 改用 `current*`**

- 视频网格：`items = dynamicViewModel.currentVideos`、空态/到底判断改读 `dynamicViewModel.currentVideoPaging.loading/hasMore`。
- 全部 tab：`items = dynamicViewModel.currentAll`，`onLoadMore`/footer 重试改调 `if (selectedUp == null) loadMoreAll() else loadMoreForSelection()`；footer 状态取 `currentAllPaging`。
- `FollowedUpPanel` 的 `onSelectedUpChange` 接线：`dynamicViewModel.selectUp(it)`，并在 `selectedUp != null && currentAll.isEmpty()` 时触发一次 `loadMoreForSelection()`。
- `HomeContent` 的「再次点击动态」：改为 `dynamicViewModel.refreshSelection()`（选中 UP 时重置该 UP 的分页；否则保持现有清空 + 重载关注流）。

- [ ] **Step 7: 编译与全量单测**

Run: `./gradlew :app:tv:compileDebugKotlin :app:tv:testDebugUnitTest :app:shared:testDebugUnitTest`
Expected: BUILD SUCCESSFUL，全部测试通过

- [ ] **Step 8: 真机验证**

聚焦某 UP → 两个 tab 都切到该 UP（视频 tab 只出视频、全部 tab 出全部）；切回「全部」零请求；切回已加载过的 UP 零请求；切 tab 保留各自滚动位置；某 UP 全是图文时视频 tab 显示空态且不无限加载。

- [ ] **Step 9: 提交**

```bash
git add bili-api/src/main/kotlin/dev/aaa1115910/biliapi/entity/user/Dynamic.kt \
        bili-api/src/test/kotlin/dev/aaa1115910/biliapi/entity/user/DynamicVideoItemMappingTest.kt \
        app/shared/src/main/kotlin/dev/aaa1115910/bv/viewmodel/home/DynamicViewModel.kt \
        app/tv/src/main/kotlin/dev/aaa1115910/bv/tv/screens/main/home/DynamicsScreen.kt \
        app/tv/src/main/kotlin/dev/aaa1115910/bv/tv/screens/main/HomeContent.kt
git commit -m "feat(tv): 动态页按选中 UP 过滤两个 tab"
```

---

## Self-Review

- **Spec coverage**：双 tab（Task 3）、瀑布流复用（Task 1）、长按菜单含「投币仅视频/收藏仅视频+图文」（Task 4）、设置项（Task 7）、左侧面板+搜索+刷新（Task 6/7）、24h 缓存与分页（Task 5/6）、按 UP 过滤与列数（Task 7/8）、`DynamicViewModel` 修复（Task 2）。无遗漏。- **Review Focus 对应**：解析容错/24h 边界/关键字匹配（Task 5 测试）、缓存前缀仍可翻页（Task 6 测试）、缺 `badge`/缺 `id`（Task 8 测试 + `PagingStateTest` 既有用例）。
- **类型一致性**：`FollowedUp` 在 Task 5 定义、Task 6/7 使用；`FollowedUserPage.from` Task 6 定义并使用；`currentAllPaging`/`currentVideoPaging` 在 Task 8 定义并直接使用；`DynamicActionMenu` 的 `onDetail` 已删除（详情路由在菜单内部），Task 4 与 Task 8 不再引用它。
- **比例**：计划长度与两份 spec 相当，未贴完整实现。
