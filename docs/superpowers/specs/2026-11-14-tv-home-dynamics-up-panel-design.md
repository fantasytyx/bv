# TV 首页「动态」页：左侧已关注 UP 面板 + 按 UP 过滤

> 目标端：Android TV（`app/tv`）与共享层（`app/shared`、`bili-api`）
> 前置文档：[视频 / 全部 双 tab](./2026-11-14-tv-home-dynamics-tabs-design.md)（Stage 1–3，本文档是 Stage 4–6）
> 状态：待评审
> 校正记录：已按 `d0853f0db` 核对。两处结论有变：**分页复用新增的 `PagingState`**；**缓存沿用 `CdnSpeedStore` 的 DataStore 字符串 + 手写序列化，不用 JSON DTO**。另据 `FollowScreen` 现状修正搜索框与匹配规则（见 5.3）。

## 1. 需求

新增界面配置项 **「动态页显示已关注 UP 列表」**（默认关闭）。开启后：

1. 动态页**左侧**出现已关注 UP 面板：搜索框（带「搜索」按钮）、「全部」、UP 列表。
2. 面板显示时，**视频 tab 改为 3 列**；**全部 tab 无论面板是否显示都保持 3 列瀑布流**。
3. 为减少请求：
   - UP 列表**初始只加载一页**，滚动/聚焦到末尾再加载下一页；
   - UP 列表**本地缓存 72 小时**（按登录用户 uid 分键）；
   - 搜索框右侧「刷新」按钮：**忽略缓存**重新按规则请求；成功后把 UP 筛选重置为「全部」（关注关系可能已变），右侧视频 / 全部两个 tab 的列表一并回到顶部；
   - **有关键字且执行搜索或刷新**时，拉全量 UP 列表后**在本地匹配**。
4. **聚焦某个 UP** 时，右侧动态列表（视频、全部两个 tab）都切成**该 UP 的动态**。

## 2. 非目标

- 不改 `app/mobile`。
- 不缓存动态内容本身（只有 UP 列表有 72h 缓存）。
- 不做按分区/关键词搜动态、不做 UP 列表分组（不按关注分组、不用 `tagid`）。
- 不做面板折叠/拖动/记忆宽度。
- 不做「关注/取关」入口（仍在 `FollowScreen`）。

## 3. 家底

| 需要的东西 | 现状 | 位置 |
|---|---|---|
| UP 列表数据 | `getFollowedUsers(mid, preferApiType)` 一次性拉**全部**（内部并行翻页，`ceil(total/50)`） | `bili-api/.../UserRepository.kt` L426 |
| 单页 UP 列表 | `BiliHttpApi.getUserFollow(mid, orderType, pageSize = 50, pageNumber, accessKey, sessData)` 已支持分页，响应带 `total` | `bili-api/.../BiliHttpApi.kt` |
| UP 排序 | 英文/数字开头优先，再按 `Collator(Locale.CHINA)`；逻辑在 `FollowViewModel.sortUsers`（private） | `app/shared/.../viewmodel/user/FollowViewModel.kt` |
| 关键字匹配 | `FollowScreen` 内联在 `derivedStateOf` 里：关键字每个非空白字符都要出现在 `name` 或 `sign` 中，不区分大小写 | `app/tv/.../screens/user/FollowScreen.kt` |
| UP 卡片 | `UpCard(modifier, face, sign, username, onFocusChange, onClick, onLongClick)`，固定 `280.dp × 100.dp`，含 1 行名 + 2 行签名 | 同上 |
| 搜索输入 | `FollowScreen` 用 `rememberTextFieldState()` + `OutlinedTextField(state = …)`（实时过滤）；`SoftKeyboard` 只被 `SearchInputScreen` 使用 | 同上 / `component/search/SoftKeyboard.kt` |
| 单 UP 的动态 | `getDynamicsByUp(mid, offset)` → `DynamicData`（`/x/polymer/web-dynamic/v1/feed/space`） | `bili-api/.../UserRepository.kt` L295 |
| 分页状态 | `PagingState` / `PageResult` / `newItems`（加载中/错误/`hasMore`/页码/游标/丢弃过期响应） | `app/shared/.../viewmodel/user/PagingState.kt` |
| 本地缓存范式 | `CdnSpeedStore`：DataStore **字符串**键 + 手写行式序列化 + 内存态 + `TTL_MS` | `app/shared/.../util/CdnSpeedStore.kt` |
| 设置项 | `SettingSwitchListItem` + `Prefs`（`booleanPreferencesKey` + `PreferenceRequest`；需要 Flow 的项用 `getPreferenceFlow(...)`，枚举型才加 `transform`） | `app/tv/.../settings/content/UISetting.kt`、`app/shared/.../util/Prefs.kt` |
| 单测 | `app/shared/src/test` 已有 `PagingStateTest`，用 `kotlin.test`，测纯函数 | — |

## 4. 方案

### 4.1 缓存介质（已选 DataStore 字符串，沿用现有范式）

| 方案 | 代价 |
|---|---|
| **A. DataStore 字符串（选中）** | 照 `CdnSpeedStore` 的做法：一个字符串键存全量列表、手写行式序列化（`mid\tname\tavatar`，首行放时间戳）。不加表、不动 Room schema、不引入 `@Serializable` DTO |
| B. DataStore + kotlinx JSON | 要多一个 `@Serializable` DTO 与 Json 配置，收益仅是「少写一个解析函数」，对本项目不划算 |
| C. Room 新表 | 要加 entity + dao + schema 迁移，只为一个 72h 缓存 |
| D. `cacheDir` 文件 | 要自己管 I/O 与清理，和 A 一样没有查询能力，却多了线程细节 |

### 4.2 选中 UP 后的数据源（已选共用分页）

- **A. 共用一份分页（选中）**：一次 `getDynamicsByUp` 的分页结果同时喂两个 tab；视频 tab 只渲染其中 `video != null` 的项。请求最省，代价是一页里视频可能只有几张，视频 tab 的「加载更多」会更快触发（但那时拉的是同一份下一页，不发新请求）。
- B. 两个 tab 各自请求：填充均匀，但同一个 UP 的翻页请求翻倍，与「减少请求」的目标相反。

## 5. 设计

### 5.1 设置项

- `Prefs.showFollowedUpsInDynamics: Boolean` + `showFollowedUpsInDynamicsFlow: Flow<Boolean>`（默认 `false`），按现有范式加：`PrefKeys` 里一个 `booleanPreferencesKey("pref_show_followed_ups_in_dynamics")` + 同名 `PreferenceRequest(..., false)`；`Flow` 直接 `dsm.getPreferenceFlow(request)`（不需要 `transform`，参照 `prefShowUGCVideoInfoKey/Request`）。
- `UISetting.kt` 在 `showUGCVideoInfo` 那一组 `item { }` 旁加一行 `SettingSwitchListItem`，标题「动态页显示已关注 UP 列表」，副标题「左侧显示已关注 UP，可按 UP 过滤动态（视频 tab 变 3 列）」。

### 5.2 UP 列表与缓存：`FollowedUpPanelViewModel`

`app/shared/.../viewmodel/home/FollowedUpPanelViewModel.kt`（新文件，Koin `@KoinViewModel`）。

```kotlin
private val pagedUps = mutableStateListOf<FollowedUp>()  // 分页/缓存累积的真实序列
var ups = mutableStateListOf<FollowedUp>()               // 展示列表：分页模式 == pagedUps，过滤模式 = 过滤结果
val paging = PagingState()                                // 分页模式下的加载中/错误/hasMore
var keyword by mutableStateOf("")                         // 搜索/过滤模式由它判定
var selectedMid by mutableStateOf<Long?>(null)            // null = 全部
var searching by mutableStateOf(false)                    // 全量拉取（搜索/刷新）进行中

fun loadFirstPage()        // 缓存新鲜 → 用缓存回填 pagedUps；否则请求第 1 页
fun loadMore()             // 下一页（每页 50）
fun refresh()              // 忽略缓存；有关键字 → 全量拉取后本地过滤
fun search(keyword: String)
fun selectUp(mid: Long?)
```

- `FollowedUp(mid, name, avatar, sign)`：轻量 DTO，从 `FollowedUser` 映射。带 `sign` 是为了与 `FollowScreen` 共用同一套匹配规则（否则面板只能按名字匹配，两处行为不一致）。
- **分页**：`UserRepository` 新增 `getFollowedUserPage(mid, page, preferApiType=Web): FollowedUserPage(users, total, pageSize)`，包一层现有 `BiliHttpApi.getUserFollow`；现有 `getFollowedUsers()` 改为循环调用它，两处不再各写一套翻页。
- **缓存**（`app/shared/.../util/FollowedUpCache.kt`，仿 `CdnSpeedStore`）：
  - 键：`PrefKeys.prefFollowedUpsCacheKey(uid)`（string）+ `prefFollowedUpsCacheTimeKey(uid)`（long，`longPreferencesKey`），uid 为当前登录用户，实现上由 `FollowedUpsCacheKeyPrefix` / `FollowedUpsCacheTimeKeyPrefix` 拼出；
  - 格式：一行一个 `mid\tname\tavatar\tsign`（URL 与签名都不含 tab/换行；写入前把 `\t`/`\n` 替换成空格，解析时按 4 段 split limit）；
  - TTL 常量 `72 * 60 * 60 * 1000L`；**每次成功加载完一页后**把「当前已加载的前缀 + 时间戳」整体写回；搜索/刷新不写缓存。
  - 纯函数拆出来便于单测：`encodeFollowedUps(list)` / `decodeFollowedUps(raw): List<FollowedUp>` / `isCacheFresh(savedAtMs, nowMs, ttlMs)`。
- **排序**：把 `FollowViewModel.sortUsers` 抽成 `sortFollowedUps(list)` 放同一文件，两处共用。
- **搜索/刷新**：关键字非空时走 `getFollowedUsers()`（全量、已并行）→ 本地 `matchesUpKeyword` 过滤 → 结果整体替换 `ups`。
- **两种模式的切换**用 `keyword` 判断，不去改 `PagingState` 的内部状态（`hasMore` 是 `private set`）：`keyword.isBlank()` → 分页模式（UI 读 `paging`，滚动触发 `loadMore`）；否则 → 过滤模式（UI 不显示「加载更多」，也不调 `loadMore`）。
- **列表分两份**，避免过滤结果污染缓存与后续分页：`pagedUps` 是分页/缓存累积的真实序列，`ups` 是**展示列表**——分页模式下 `ups` 就是 `pagedUps`，过滤模式下 `ups` 是 `pagedUps` 的过滤结果。清空关键字 → `ups = pagedUps`（不重新请求）。
- **缓存过期不占位**：超过 72h 的缓存直接丢弃，首屏显示加载态并请求第 1 页（不先显示可能已过期的旧列表）。
- **缓存按账号分键**：键名带上登录 uid，换账号看不到上一个账号的关注列表；登出时清掉该账号的缓存。残留（登录后长期不登出的多账号场景）在**每次写缓存时**于同一个 `dataStore.edit` 事务里清掉，不额外增加一次 IO，读路径保持只读。
- **失败**：错误落到 `paging.error`；面板显示错误行 + 重试（`SpaceContentStatus` 是给网格底部用的，这里用一行文案 + 重试按钮）。

### 5.3 左侧面板 UI：`FollowedUpPanel`

`app/tv/.../screens/main/home/FollowedUpPanel.kt`（新文件），宽度 **176dp**（面板只放头像 + 单行名字，太宽会挤占 3 列卡片；不用 `UpCard`，它固定 280×100 且带 2 行签名，只借用它的头像 + 名字排版）。

```
┌────────────────────────┐
│ ⟳ 已关注 UP             │   ← 刷新（同步）按钮在标题左侧
│ [🔍 搜索 UP…       ][🔍]│   ← 搜索框（2dp 边框）+ 搜索按钮
│ ────────────────────── │
│ [全部]                  │   ← 固定在滚动列表外，selectedMid == null
│ ◯ 某某UP                │   ← 滚动区；头像 + 单行名字，按 OK 选中
│ ◯ 另一个UP              │
│ 加载更多 / 状态          │   ← 列表最后一项，随列表滚动，滚到底部才可见
└────────────────────────┘
```

- 列表滚动位置：不用 `rememberLazyListState`（不跨页面恢复，避免残留偏移把首项顶边裁掉）。回顶时机：首屏阶段（用户第一次把焦点移进面板之前，`snapshotFlow { firstVisibleItemIndex }` 持续把首项钉在顶部，进面板时把位置交回用户）、点刷新成功、点「全部」（含本来就选中「全部」）。之后再进/出面板都不动列表位置。
- 状态行（加载更多 / 加载中 / 加载失败重试 / 到底「没有更多内容」）作为 `LazyColumn` 的最后一项（key = `"panel-status"`，`FollowedUpPanelViewModel.hasStatus` 为假时不生成该项），随列表一起滚动，只有滚到底部才看得到，不占常驻高度。
  - 四种状态复用**同一个** `Surface(onClick)` 节点，只换文案与动作（`PanelStatus`）。不能按状态分支渲染成不同 Composable：`FocusTargetNode.onDetach` 会直接 `clearFocus()`，而这个位置恰恰是列表末尾——「加载更多 → 加载中 → 到底」每次都在它身上发生，一换节点焦点就丢，遥控器随即按不动；共用节点顺带避免了行高跳动。
  - 该节点必须可聚焦：它在视口下方时，按 ↓ 要有落点才能把它滚进来（与 `SpaceContentStatus` 的到底文案同理）。
  - `atEnd` 要求列表非空，避免空面板里挂一句「没有更多内容」。
- 焦点向上出口：面板最上面的刷新按钮按 ↑ → 交回二级 Tab 的当前选中项（`DynamicsScreen` 把 `tabFocusRequester.requestFocus()` 传进 `onFocusSelectedTab`，`TopNav` 的共享 requester 就挂在选中项上）；刷新按钮同时吞掉该键的 Down/Up，避免松开时落到刚获得焦点的 Tab。
- 焦点向左入口：面板可见时，二级 Tab 第一项按 ← → `onLeftKeyEvent` 直接 `panelFocusRequester.requestFocus()`（落在「全部」）；面板隐藏时不处理，维持原来的「最左侧按 ← 无动作」。

- 搜索框：`rememberTextFieldState()` + `OutlinedTextField(state = …)`，参数（宽度/高度/圆角/`leadingIcon = Search`/`imeAction = Search`）**照抄 `FollowScreen`**；右侧「搜索」按钮（`Surface(onClick)`）与「刷新」按钮（`IconButton`，`Icons.Rounded.Refresh`）。
  - 搜索框夹在「全部」与「刷新」之间，D-pad 去刷新按钮必然先经过它：聚焦后 300ms 内保持 `readOnly`（`rememberDelayedImeEditable`，`app/tv/.../util/DelayedImeFocus.kt`），停留够久才恢复可编辑并弹输入法，路过时不弹，避免输入法窗口抢焦点、吞掉后续按键。`SearchInputScreen` 的搜索框同理。
  - 待真机确认：`FollowScreen` 依赖系统 IME，若 TV 上没有可用输入法，则补一个内嵌 `SoftKeyboard` 的弹层（本次先不加，免得写两套输入）。
- 匹配规则：把 `FollowScreen` 里那段字符匹配抽成 `matchesUpKeyword(keyword: String, vararg texts: String): Boolean`（每个非空白字符都要出现在任一 `texts` 中，不区分大小写），面板传 `name, sign`，`FollowScreen` 传 `name, sign`，两处行为一致。
- 焦点/分页：
  - 聚焦 UP 项只做预取，**按 OK 才切换选中**（`selectedMid = mid`）；这样浏览列表不会连发动态请求，也不需要防抖；
  - 聚焦到倒数第 5 项 → `loadMore()`；
  - 选中项高亮（`selectedContainerColor` + 聚焦 `border`，参照 `UpCard`）。
- 同步按钮（标题左侧）：成功后 toast「关注列表已同步」，并把筛选重置回「全部」、右侧两个 tab 的列表回到顶部（关注关系可能已经变了）。

### 5.4 按 UP 过滤的动态数据

`DynamicViewModel` 增量（不新开 VM，二者本来就是同一页面的数据）。**Stage 2 已把关注流的两份分页收敛到 `PagingState`**，这里同样用它：

```kotlin
val selectedUp by mutableStateOf<Long?>(null)   // null = 关注流
private val upFeeds = mutableMapOf<Long, UpFeed>()

private class UpFeed {
    val items = mutableStateListOf<DynamicItem>()
    val paging = PagingState()
}

val currentAll: List<DynamicItem>      // get() = selectedUp?.let { upFeeds[it]?.items } ?: dynamicAllList
val currentVideos: List<DynamicVideo>  // get() = 当前 all 列表里 video != null 的项映射而来
val currentAllPaging: PagingState      // get() = selectedUp?.let { upFeeds[it]?.paging } ?: all
val currentVideoPaging: PagingState    // get() = if (selectedUp == null) videos else currentAllPaging

fun selectUp(mid: Long?)               // 切到未加载过的 UP 时拉第 1 页
fun loadMoreForSelection()             // 两个 tab 共用：拉该 UP 的下一页
fun refreshSelection()                 // 一级「动态」再次点击时调用
```

- 单 UP 数据源：`UserRepository.getDynamicsByUp(mid, offset)`；`offset` 由 `PagingState` 管理，`PageResult(items, hasMore = data.hasMore, nextOffset = data.historyOffset)`，去重用 `newItems`（key = `DynamicItem.id`）——与 `UserSpaceViewModel.loadDynamics` 完全同构，实现时照抄那四行。
- 视频 tab 的项：`items.filter { it.video != null }` + **新增** `DynamicVideo.fromDynamicItem(item: DynamicItem): DynamicVideo`（`bili-api`，`Dynamic.kt` 的 `DynamicVideo` companion）。字段映射照现有 `fromDynamicVideoItem(item: http…DynamicItem)`：aid / bvid / title（去掉 `动态视频｜`）/ cover / `author.name` / `author.mid` / `author.avatar` / `durationSeconds` / `playCount` / `danmakuCount` / `author.pubTime`；`isChargingArc` 由 `video.badgeText` 含「充电」或「限时免费」推出。
- 加载更多：**两个 tab 共享同一个 `PagingState`**——哪边触发都拉同一份下一页（4.2 定案）。触发阈值沿用各列表现有值：视频 tab `index + 12`，瀑布流 `index + 6`。
- 切回「全部」：直接用关注流的两份列表，**零请求**；UP 的数据留在 `upFeeds`（本次会话），切回该 UP 也是零请求。
- 一级「动态」再次点击：调用 `refreshSelection()`——选中 UP 时 `upFeeds[mid]` 重置并重拉第 1 页；否则维持现有的清空 + 重载关注流（`clearVideoData/clearAllData` + 依当前 tab 重载）。

### 5.5 布局与列数

`DynamicsScreen` 内容区：

```kotlin
Row {
    if (showFollowedUpPanel) FollowedUpPanel(modifier = Modifier.width(240.dp), ...)
    when (selectedTab) {
        Video -> 现有网格 (columns = if (showFollowedUpPanel) 3 else 4)
        All   -> DynamicWaterfall(columns = 3)
    }
}
```

- 面板开关取 `Prefs.showFollowedUpsInDynamicsFlow`，运行中改设置后回到动态页即生效。
- 视频 tab 从 4 列变 3 列只改 `GridCells.Fixed(columns)` 与 `blockDownFocusExitAtGridEnd(columnCount = columns)` 的入参（两处都在 `DynamicsScreen` 的现有网格里）。
- 二级 tab 行沿用 `TopNav(useSmallSize = true)`，与 `FavoriteScreen` 把 `TopNav` 放在内容区的做法一致。

### 5.6 焦点与返回

焦点链（自内向外）：

```
二级 tab 行  ←Back←  左侧面板  ←Back←  右侧内容（网格/瀑布流）
     └─Back→ 左侧抽屉（HomeContent 现有逻辑）
```

- 内容区按 `Back`：面板开启 → 焦点给面板的「全部」行；未开启 → 焦点给二级 tab 行。
- 进入面板（Back 或左右方向键）：焦点固定落在「全部」；列表位置不动，初始态第一个 UP 就显示在「全部」下面。
- 在「全部」上按 ↓：选中过 UP 就直接聚焦到该项（滚出可视区时先滚过去），否则落到第一个 UP。
- 面板按 `Back`：焦点给二级 tab 行。
- 面板与内容之间：右/左方向键交给默认焦点搜索（面板在左、内容在右，几何关系足够明确）；若实测不稳，再补 `focusProperties(right = …)`。
- 进入页面默认焦点仍在二级 tab 行（现状不变），不自动跳进面板。

### 5.7 请求量预算

| 场景 | 请求 |
|---|---|
| 首次进入（面板开、缓存新鲜） | UP 列表 0 + 关注流动态 1 |
| 首次进入（缓存过期） | UP 列表 1（50 条）+ 关注流动态 1 |
| 面板滚一页 | +1 |
| 聚焦一个没加载过的 UP | 该 UP 动态 1 |
| 切回「全部」/ 切回已加载的 UP | 0 |
| 执行搜索 / 刷新（有关键字） | 全量 follow（`ceil(total/50)` 次，内部并行） |

## 6. 分阶段实施

| 阶段 | 内容 | 可独立验收 |
|---|---|---|
| 1 | `DynamicWaterfall` 抽取，UP 空间页改用（行为不变） | 编译 + 单测 + UP 空间页手感不变 |
| 2 | 首页动态页双 tab + `DynamicViewModel` 收敛到 `PagingState` + `HomeContent` 状态提升 + 返回键 | 双 tab 可切、加载更多、滚动位置保留 |
| 3 | 长按菜单 `DynamicActionMenu` + `SpaceDynamicCard.onLongClick` | 三类卡片菜单项与动作 |
| 4 | 设置项 + `FollowedUpPanelViewModel`（分页/缓存/搜索/刷新）+ `FollowedUpCache` 纯函数与单测 | 开关出现在界面设置；单测通过 |
| 5 | `FollowedUpPanel` UI + 焦点/返回 | 面板可浏览、搜索、刷新、聚焦切换 |
| 6 | 按 UP 过滤动态 + 视频 tab 列数 | 选中 UP 后两个 tab 都变该 UP；切回全部零请求 |

## 7. 验证

- `./gradlew :bili-api:compileKotlin :app:shared:compileDebugKotlin :app:tv:compileDebugKotlin`
- `./gradlew :app:tv:testDebugUnitTest`（沿用 `DynamicGridFocusTest`、`SpaceDynamicCardTest`）
- 新增单测（`app/shared/src/test`，`kotlin.test`，只测纯函数，与 `PagingStateTest` 同款）：
  - `isCacheFresh`：72h 内命中、超时失效（含边界）；
  - `staleCacheUids`：过期/缺时间戳的 uid 会进清理集合；
  - `encodeFollowedUps` / `decodeFollowedUps` 往返（含空列表、名字带空格）；
  - `matchesUpKeyword`：大小写不敏感、多字符全命中、空关键字；  - `DynamicVideo.fromDynamicItem`：正常映射、缺 `badge` 时 `isChargingArc = false`。
- 真机（TV）：面板焦点链（内容→面板→tab→抽屉）、聚焦防抖不连发请求、缓存过期后重新请求、搜索/刷新、选中 UP 后两个 tab、视频 tab 3 列、关开关后回到 4 列。

## 8. 留给实现者

- 面板宽度（240dp）、防抖时长（300ms）、加载更多阈值（5/6）、提示文案：按现有页面习惯取值，不新增配置项。
- 缓存键名与序列化转义细节：实现时定，不对外暴露。
- 若 `getDynamicsByUp` 的 `offset` 在末页不返回，按 `hasMore = false` 处理（`PagingState` 也会在整页去重后为空时判定到底），不额外探测。
- 真机上若 `OutlinedTextField` 拿不到输入法，再补 `SoftKeyboard` 弹层；这是待验证项，不在本次实现范围内。
