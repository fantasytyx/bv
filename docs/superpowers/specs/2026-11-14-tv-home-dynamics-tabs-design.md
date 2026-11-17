# TV 首页「动态」页：视频 / 全部 双 tab

> 目标端：Android TV（`app/tv`）
> 数据源：`/x/polymer/web-dynamic/v1/feed/all`（`type=video` / `type=all`）与 gRPC `dynVideo` / `dynAll`
> 状态：待评审
> 范围：本文档覆盖 Stage 1–3。Stage 4–6（左侧已关注 UP 面板、按 UP 过滤）见[设计文档](./2026-11-14-tv-home-dynamics-up-panel-design.md)。
> 校正记录：已按 `d0853f0db`（UP 空间改版 + 动态卡片 + 图文/动态详情）核对现状；新增 `PagingState` 复用与 `DynamicVideo` 映射入口两处结论。

## 1. 需求

首页一级导航「动态」页内新增二级 tab：

- **视频**：保持现状——`SmallVideoCard` 4 列网格。
- **全部**：新增 3 列瀑布流，卡片外观、点击路由、焦点手感与 UP 空间页「动态」tab 一致；**支持长按操作菜单**（见 5.6）。

## 2. 非目标

- 不改 `app/mobile`。
- 不动首页一级 `TopNav`（推荐 / 热门 / 动态 / …）。
- UP 空间页「动态」tab 不加长按菜单（本次只给首页「全部」）。
- 不做图片预览、不做卡片内直出互动按钮（互动只经长按菜单）。
- 两套接口（Web / App gRPC）的选择仍由 `Prefs.apiType` 决定，本次不改。

## 3. 现状

| 位置 | 现状 |
|---|---|
| `app/tv/.../screens/main/home/DynamicsScreen.kt` | 只有视频网格（`LazyVerticalGrid` 4 列 + `SmallVideoCard` + 长按 `VideoActionMenu`），顶部一行「关注」小字；`HomeContent` 已负责登录态判断 |
| `app/tv/.../screens/user/UpInfoScreen.kt` | 「动态」tab 的 3 列瀑布流；网格、同列焦点接管、待复焦锚点、加载更多阈值、底部状态全部内联在本文件（`dynamicItemBoxes`/`pendingVerticalMove`/`onDynamicVerticalKey` 约 L185–280，网格在 L650） |
| `app/tv/.../screens/user/UpSpaceDynamicCards.kt` | `SpaceDynamicCard(dynamic, modifier, onClick, onFocus)`：`Surface` 支持 `onLongClick`，但**当前没有该参数**；卡片判定/文案纯函数已可复用 |
| `app/tv/.../screens/user/UpSpaceCards.kt` | `SpaceContentStatus(loading, error, hasMore, onRetry)`：公用的底部状态行 |
| `app/tv/.../util/DynamicGridFocus.kt` | 同列邻居/滚动揭示/待复焦的纯函数，已有单测 |
| `app/shared/.../viewmodel/home/DynamicViewModel.kt` | `dynamicAllList` + `loadMoreAll()` 已存在，但分页状态是手写字段 |
| `app/shared/.../viewmodel/user/PagingState.kt` | **新增**：`PagingState` / `PageResult` / `newItems`，UP 空间页五个列表已收敛到它（加载中/错误/`hasMore`/页码/游标/丢弃过期响应） |
| `bili-api/.../repositories/UserRepository.kt` | `getDynamics()` 已支持 `type=all` 与 gRPC `dynAll`；`getDynamicsByUp(mid, offset)` 已存在 |
| `bili-api/.../entity/user/Dynamic.kt` | `DynamicVideo.fromDynamicVideoItem(...)` 只接受**原始** HTTP / gRPC item，没有从已映射的 `DynamicItem` 取视频的入口 |

复用障碍只有两处：瀑布流的「网格 + 焦点胶水」写死在 `UpInfoScreen` 里；动态点击路由也写死在两个页面里。

## 4. 方案（已选）

**抽出共用组件，两端传参。** 备选是「首页复制一份瀑布流」：改动更集中、风险更低，但把同一套 TV 上下键手感变成两份，以后调手感要改两处，与「尽量少写代码」相悖，未采用。

## 5. 设计

### 5.1 新组件 `DynamicWaterfall`

`app/tv/.../screens/user/DynamicWaterfall.kt`（与 `SpaceDynamicCard` 同包）

```kotlin
@Composable
fun DynamicWaterfall(
    items: List<DynamicItem>,
    state: LazyStaggeredGridState,
    onItemClick: (DynamicItem) -> Unit,
    onLoadMore: () -> Unit,
    onItemLongClick: (DynamicItem) -> Unit = {},
    modifier: Modifier = Modifier,
    columns: Int = 3,
    contentPadding: PaddingValues = PaddingValues(24.dp),
    footer: (@Composable () -> Unit)? = null
)
```

内部承接从 `UpInfoScreen` 原样搬迁的状态与逻辑（行为等价，不改手感）：

- `focusRequesters` 按 index 缓存、`focusedIndex`、`viewportHeightPx`、`pendingAnchor` / `pendingDirection`；
- `snapshotFlow { itemBoxes() }` 在布局更新后把焦点交给同列目标卡；
- `onPreviewKeyEvent` 只接管 UP/DOWN（`verticalMove` 的三条分支：先揭示被截断卡、换焦点、滚过一卡高）；
- `staggeredItemsIndexed(key = { _, d -> d.id ?: "dynamic-$index" })` + `SpaceDynamicCard`（新增 `onLongClick` 参数，交给卡片 `Surface`）；
- 长按 / `MENU` 键都落到 `onItemLongClick(items[focusedIndex])`（与视频 tab 现有 `onMenuKeyDown` 行为一致）；
- 聚焦到 `index + LoadMoreThreshold > items.size` 时回调 `onLoadMore()`（`LoadMoreThreshold = 6`，即 UP 空间现有阈值）；
- `footer` 作为 `StaggeredGridItemSpan.FullLine` 项渲染；footer 获得焦点时把 `focusedIndex` 置 -1，避免上下键被卡片逻辑劫持。

返回键、`focusRestorer`、`BringIntoViewSpec` 仍由调用方通过 `modifier` 决定（两个页面的返回语义不同）。

### 5.2 点击路由 `openDynamic`

`app/tv/.../screens/user/DynamicNavigation.kt`

```kotlin
fun openDynamic(
    context: Context,
    dynamic: DynamicItem,
    videoInfoRepository: VideoInfoRepository,
    preloadFrom: List<DynamicItem>
)
```

内部即 `UpInfoScreen` 里现有的 `when (dynamic.cardAction())`：

- `Video` → 用 `preloadFrom` 里的视频（`item.video`，字段映射与现有 `VideoCardData(...)` 一致：aid / title / cover / playCount / danmakuCount / durationSeconds / `item.author.pubTime`）设置预加载列表，再进 `VideoInfoActivity`（带 `ProxyArea.checkProxyArea`）；
- `Article` → `ArticleActivity.actionStart(context, dynamic.id.toLong())`；
- `Detail` → `DynamicDetailActivity.actionStart(context, dynamic.id)`。

UP 空间页传入 `tvSpaceDynamics`，首页「全部」传入 `dynamicAllList`，行为自然一致。

### 5.3 `UpInfoScreen` 改造

删掉 5.1 列出的内联状态与两个 `LaunchedEffect` / 键处理，`selectedTab == UserSpaceTab.Dynamics` 分支改为：

```kotlin
DynamicWaterfall(
    items = userSpaceViewModel.tvSpaceDynamics,
    state = dynamicGridState,
    modifier = contentModifier.fillMaxSize().then(listModifier),
    onItemClick = { openDynamic(context, it, videoInfoRepository, userSpaceViewModel.tvSpaceDynamics) },
    onLoadMore = { userSpaceViewModel.load(UserSpaceTab.Dynamics) },
    footer = { SpaceContentStatus(isLoading, error, hasMore) { userSpaceViewModel.load(UserSpaceTab.Dynamics) } }
)
```

`currentIndex` 不再由动态卡片写入：它只服务于 4 列网格的 `blockDownFocusExitAtGridEnd`，动态分支里那行 `currentIndex = index`（L700 附近）没有消费者，迁移时一并去掉；其余 tab 的写入保持不变。

### 5.4 首页「动态」页

`DynamicsScreen` 改为 `Column`：

1. 二级 `TopNav(useSmallSize = true, paddingTop = 0.dp)`，item 为 `enum class DynamicTab : TopNavItem { Video("视频"), All("全部") }`（定义在同文件）；
2. 内容区按二级 tab 切换：
   - 视频：现有网格整体保留（含长按 `VideoActionMenu`），加 `Modifier.weight(1f)`；
   - 全部：`DynamicWaterfall(items = dynamicAllList, state = allState, ...)`，footer 用 `SpaceContentStatus(loadingAll, null, allHasMore, retry)`，长按/`MENU` 打开 5.6 的菜单。

其他约定：

- **焦点与返回**：列表内按 Back → 焦点回到二级 tab 行；在 tab 行按 Back → 交回 `HomeContent` 现有逻辑（回左侧抽屉）。实现为 `DynamicsScreen` 内一个仅在「焦点在内容区」时启用的 `BackHandler`。
- **状态提升**：`HomeContent` 现在持有 `dynamicState`（视频网格）；新增 `dynamicAllState = rememberLazyStaggeredGridState()` 一起传下去，切首页一级 tab 再回来时两套滚动位置都保留。
- **首次加载**：二级 tab 切到「全部」且列表为空且 `allHasMore` 时触发 `loadMoreAll()`；视频 tab 的加载入口不变。
- **刷新**：一级导航「动态」被再次点击时，现有逻辑清空视频列表并重载，同时补上 `clearAllData()` + `loadMoreAll()`（仅当当前二级 tab 是「全部」）。
- **去掉「关注」小字**：它原本是这块内容区唯一的标题，现在由二级 tab 行承担（`entry_follow_screen` 在本页不再使用）。

### 5.5 `DynamicViewModel` 改造（与本次改动紧耦合）

现有实现里，分页状态是手写字段（`loadingVideo` / `videoHasMore` / `loadingAll` / `allHasMore` + 页码 + 游标 + `updateBaseline`），并且有两处会直接破坏「全部」tab：

1. `loadAllData()` 用 `currentVideoPage` 递增（`page = ++currentVideoPage`），会把视频分页串乱；
2. `dynamicAllList.addAll(...)` 在 `Dispatchers.IO` 上改 Compose 状态列表（视频那侧用的是 `addAllWithMainContext`）。

既然 Stage 6 还要再加一份「按 UP」的分页，这里直接把三份分页都收敛到已有的 `PagingState`（`app/shared/.../viewmodel/user/PagingState.kt`，UP 空间页五个列表已在用）：`load()` 内部在 IO 请求、回来后在调用方上下文 `append`，上面两个问题随之消失。

```kotlin
val dynamicVideoList = mutableStateListOf<DynamicVideo>()
val dynamicAllList = mutableStateListOf<DynamicItem>()
val videos = PagingState()
val all = PagingState()
private var videoUpdateBaseline: String? = null
private var allUpdateBaseline: String? = null

suspend fun loadMoreVideo()   // 保持现有名字：videos.load { … }，fetch 里 getDynamicVideos(page, offset, baseline)
suspend fun loadMoreAll()     // all.load { … }，fetch 里 getDynamics(...)
fun clearVideoData()          // videos.reset() + 清列表 + baseline = null
fun clearAllData()            // all.reset()    + 清列表 + baseline = null
```

- `PagingState` 自带页码与 `offset`，`PageResult(items, hasMore, nextOffset = data.historyOffset)`；去重用已有的 `newItems`（key = `DynamicItem.id`，视频列表用 `it.aid`）。
- 重置时**必须**把 `updateBaseline` 一并清空：现有 `clearVideoData()` 漏了这一步，再次点一级「动态」时会带着旧 baseline 请求。
- UI 侧读取的 `loadingVideo / videoHasMore / loadingAll / allHasMore` 改为 `videos.loading / videos.hasMore / all.loading / all.hasMore`；调用点只有 `DynamicsScreen` 与 `HomeContent` 两处（合计约 8 行）。
- 移动端 `DynamicScreen` 走 `loadMoreAll()` 同一条路径，因此这条修复对移动端也是修 bug，不改其行为语义。

### 5.6 长按菜单（全部 tab）

`app/tv/.../component/DynamicActionMenu.kt`（新文件）：唯一入口按卡片类型分派，视频卡直接复用现成的 `VideoActionMenu`，其余用 `CardActionMenu` 拼动态专属项。视觉上两者本来就同源（`CardActionMenu`），不需要新样式。

| 菜单项 | 视频卡 | 图文卡 | 其它（文章 / 转发 / 直播 / 充电专属） |
|---|---|---|---|
| UP 主页 | ✓ | ✓ | ✓ |
| 详情 | ✓ 视频详情 | ✓ 图文详情（`ArticleActivity`，与点击一致） | ✓ 动态详情 |
| 点赞 | ✓ 点赞视频 | ✓ 点赞动态 | ✓ 点赞动态 |
| 收藏 | ✓ 收藏视频（收藏夹弹窗） | ✓ 图文收藏 | — |
| 投币 | ✓ | — | — |
| 稍后再看 | ✓ | — | — |
| 已关注 UP 列表 | ✓ | ✓ | ✓ |

- **视频卡**：原样复用 `VideoActionMenu`（`showUpInfoAction = true`、`resourceType = Video`、`onDetailClick` = 视频详情、`extraItems = [已关注 UP 列表]`），不改该组件，「稍后再看」照旧出现。点赞/收藏/投币继续走 `VideoUserActionManager`（状态惰性拉取 + 收藏夹弹窗）。
- **图文卡**：`LikeRepository.setDynamicLike(dynamic.id, like)` 与 `FavoriteRepository.setOpusFavorite(dynamic.id.toLong(), favorite)` —— 图文详情页（`ArticleDetailScreen`）已在用同一对接口。初始状态取列表数据里的 `footer.liked` / `footer.favorited`（Web 接口的 `module_stat.like.status` / `favorite.status`），点击后只做本地覆盖，不额外发查询请求。
- **其它卡**：只有 UP 主页 / 详情 / 点赞 / 已关注 UP 列表；点赞同样走 `setDynamicLike`。文案与视频卡菜单保持一致（`UP 主页`、`详情`、`点赞` / `已点赞` 等）。
- 互动项（点赞/收藏/投币）与现有菜单一致，仅 `Prefs.isLogin` 时出现。
- 已知限制：App(gRPC) 分支的 `DynamicFooterModule` 不映射 `favorited`，该接口下图文收藏初值恒为「未收藏」，点击后才正确。本次不新增查询接口。

## 6. 验证

- `./gradlew :bili-api:compileKotlin :app:shared:compileDebugKotlin :app:tv:compileDebugKotlin`
- `./gradlew :app:tv:testDebugUnitTest :app:shared:testDebugUnitTest`（`DynamicGridFocusTest`、`SpaceDynamicCardTest`、`PagingStateTest` 必须仍通过）
- 真机（TV）：二级 tab 切换与焦点往返、瀑布流上下键手感与 UP 空间页一致、加载更多/到底提示、三类点击路由、长按菜单逐类卡片的项与动作（点赞态、图文收藏、视频投币/收藏夹）、切一级 tab 再回来滚动位置保留、再次点一级「动态」时两个 tab 都真正重载（baseline 已清）。

## 7. 留给实现者

- `DynamicWaterfall` 的默认参数（列数、padding、阈值）按两个调用点收敛，不额外加配置项。
- 瀑布流内部状态若出现「必须让调用方知道聚焦 index」的需求，优先在组件内解决，不开口子。
