# TV UP 空间页改造设计

## 意图

让 TV 端 UP 空间页（`app/tv` 的 `UpSpaceScreen`）在导航、卡片信息密度和操作完整度上对齐其它页面：

- tab 栏换成项目统一的胶囊样式，进入页面时焦点落在第一个 tab。
- 合集/系列详情有正确的「逐级返回」手感，并能收藏合集。
- 卡片只保留用户关心的信息：合集/系列看封面比例与更新时间，图文看点赞数，动态看类型与视频数据。

## 目标端与范围

- 只改 TV 端 `app/tv` 与其依赖的共享层（`app/shared`、`bili-api`）。移动端的 UP 空间页不动。
- 不新增 ViewModel、不引入页面内路由层。

## 现状

- `app/tv/.../screens/user/UpInfoScreen.kt`（约 930 行）同时承担页面骨架、tab 栏、四类卡片的渲染。
- tab 栏是自绘 `Surface` chip，与 `TopNav`（首页/搜索结果页）的胶囊样式不一致。
- 进入页面时 `LaunchedEffect(tvSpaceVideos.isNotEmpty())` 会把焦点打到第一张卡片。
- 合集/系列详情只有一个 `BackHandler` 关闭详情，不恢复焦点；在内容区按返回会直接退出页面。
- 合集/系列卡片封面是固定 180dp 高度，没有更新时间。`SpaceSeasonSeriesEntry` 已经带了 `archives`，但映射时被丢弃。
- 空间动态走 web 端点（`UserRepository.getDynamicsByUp` → `BiliHttpApi.getDynamicList` → `/x/polymer/web-dynamic/v1/feed/all`），`DynamicVideoModule` 只有字符串形式的播放量/弹幕量/时长，且没有类型信息。
- 收藏体系里 `FavoriteResourceType` 只有 `Video(2)` 和 `Pgc(42)`，没有合集（21）。

## 方案

**选定：就地改造 + 必要的文件拆分。**

- 复用 `TopNav`、`SmallVideoCard`、`FavoriteFolderDialog` 等既有组件，把判定逻辑下沉到 `bili-api` 以便单测。
- 只新拆出 1 个卡片文件，避免把页面拆得过散。

否决的备选：

- 在 ViewModel 里引入 `sealed UpSpaceRoute` + 显式返回栈：返回栈天然可测，但要重做 `selectedCollection/selectedSeries` 这套已有状态，改动面远大于需求本身，且本项目其它页面不是这么组织的。
- 最小改动（不拆文件、UI 内解析字符串、收藏挂长按菜单）：动态类型判定与数字解析散在 UI 无法单测，且「进合集后显示收藏」要求详情页有操作位，长按菜单不满足。

## 设计

### 1. tab 栏

- `UserSpaceTab`（`app/shared`）保持不变，`UserSpaceTabTest` 继续守着标签顺序。
- 在 TV 侧加适配器 `UpSpaceTopNavItem(val tab: UserSpaceTab) : TopNavItem`，`getDisplayName` 返回 `tab.label`（`TopNavItem` 在 `app/tv`，`shared` 不能反向依赖）。
- 渲染：`TopNav(paddingTop = 0.dp, items = …, initialSelectedItem = 当前 tab, navSwitchMode = Prefs.navSwitchModeFlow, tabFocusRequester = tabFocusRequester, onSelectedChanged = …)`。
- `onSelectedChanged` 内保留现有行为：切换 `selectedTab` → `currentIndex = 0` → grid 回顶 → 按需 `load(tab)`。

### 2. 初始焦点

- 进入页面用重试循环把焦点交给第一个 tab（`TopNav` 首次组合后 tab 才可聚焦，参考 `SearchResultScreen` 的 `repeat(10) { delay(50) }` 写法）。
- 删除「第一张卡片抢焦点」的 `LaunchedEffect` 与卡片上的 `focusRequester(listFocusRequester)`；卡片上的 requester 只服务于返回栈恢复。

### 3. 返回与焦点恢复

返回键按三层处理：

| 当前状态 | 行为 |
|---|---|
| 合集/系列详情内（`selectedArchive` 非空） | 关闭详情 → 回到列表 → 滚动到并聚焦刚才打开的合集/系列卡片 |
| 焦点在内容区（grid、详情头部按钮、收藏按钮） | 聚焦 tab 栏当前选中 tab |
| 焦点已在 tab 栏 | 不拦截，正常退出页面 |

- 第 1 层：根级 `BackHandler(enabled = selectedTab == Collections && selectedArchive)`。
- 第 2 层：grid 上的 `onPreviewKeyEvent` 拦截 `Key.Back`（`KeyUp` 时聚焦 tab 并消费事件），与 `SearchResultScreen.backToTabRow` 同款。**必须加 `!selectedArchive` 条件**：详情内 grid 是同一个，若在详情态消费 Back，`BackHandler` 收不到事件，表现为"按返回没反应"。详情头部的按钮（返回/收藏）同理交给 `BackHandler`，不要再单独挂 `onBackPressed`，否则第一层会被劫持成第二层。
- 焦点恢复：
  - `lazyGridState` 提升到页面级。
  - 打开详情时记录 `pendingFocusKey`，值与 grid item key 一致（`season-$seasonId` / `series-$seriesId`）。
  - 关闭后 `LaunchedEffect(pendingFocusKey)`：`scrollToItem(目标下标)`（合集区 `0..n-1`，系列区从 `collections.size` 起算），再用重试循环把焦点打到 `if (key == pendingFocusKey) Modifier.focusRequester(restore)` 的那张卡，成功后清空。
  - 详情头部的「返回合集和系列」按钮走同一套恢复逻辑（现状只 `closeArchives()`，不恢复焦点）。
- 取舍：第 2 层是「从内容区按返回 → 聚焦 tab」，而不是「第一次返回必定聚焦 tab」。焦点本来就在 tab 上时再按返回应直接退出，否则会出现「按返回没反应」。

### 4. 合集 / 系列卡片

- 封面从固定 180dp 高改为 `aspectRatio(4f / 3f)`。
- 文案顺序：`合集 · N 个视频`（primary）→ 标题（2 行）→ 描述（2 行）→ 更新时间（次要色，无数据时整行不显示）。
- 时间格式复用 `Long.toSmartDate()`（自动识别秒/毫秒）。

### 5. 图文卡片

- `SpaceArticleCard` 的 meta 从「播放 · 赞 · 评论」改为只显示点赞数（≥1 万走万位），与 `VideoCardData` 的展示口径一致。

### 6. 动态卡片

- 视频类（`Av`、`UgcSeason`）改用 `SmallVideoCard` + `VideoCardData`：
  - 播放量/弹幕量/时长来自新增的数值字段，由 `SmallVideoCard` 渲染在封面下部。
  - 右上角类型标签走角标：`VideoCardData` 新增 `topRightBadge: String = ""` 并加入 `coverBadges`（现有 `badgeText` 只在 `isChargingArc` 时渲染，语义被充电角标占用）。标签值：`投稿视频` / `联合创作` / `动态视频`。
  - `upName` 置空（这是 UP 自己的空间），保留 `pubTime`。
  - 点击进 `VideoInfoActivity`，并把当前「动态」tab 内所有视频类动态做成 preloaded list 传给 `VideoInfoRepository`（与「视频」「合集详情」tab 一致）。
- 非视频类保留自绘卡片，修两处：
  - 聚焦反色：显式设置 `ClickableSurfaceDefaults.colors(containerColor = Transparent, focusedContainerColor = onSurface(0.12f), contentColor = onSurface, focusedContentColor = onSurface)`，聚焦只让背景变亮，文字不再被反色。
  - 右上角类型标签：`图文`（Draw/Word/Article）/ `转发` / `直播` / `番剧` / `动态`；有封面时压在封面右上角（黑色 30% 底 + 白字，与 `SmallVideoCard` 角标同款），无封面时放内容区首行右对齐。
- 非视频类的点击行为不变（`DynamicDetailActivity`）。

### 7. 合集收藏

- `FavoriteResourceType` 增加 `VideoCollection(21)`（B 站收藏合集即 `type=21`），补 `fromFavoriteItemType` 映射。
- `FavoriteRepository` 不改：
  - 读：`getAllFavoriteFolderMetadataList(mid = Prefs.uid, resourceType = VideoCollection, rid = seasonId)` 一次拿到收藏夹列表 + 该合集是否被收藏。
  - 写：`updateVideoToFavoriteFolder(aid = seasonId, resourceType = VideoCollection)`。
- 状态放 `UserSpaceViewModel`：`collectionFavoriteFolders`、`collectionFavoriteFolderIds`、`collectionFavoriteLoading`；`openCollection()` 后拉取，`closeArchives()` 清空，用 `archiveGeneration` 丢弃过期响应。
- UI：合集详情头部一行，只在 `selectedCollection != null && Prefs.isLogin` 时显示（系列在 B 站不可收藏）。
  - 未收藏且只有 1 个收藏夹 → 直接加默认收藏夹；否则弹 `FavoriteFolderDialog`。
  - 已收藏 → 点开收藏夹选择框，行为与 `FavoriteButton` 一致。
  - 图标 `Icons.Rounded.Favorite / FavoriteBorder`，已收藏用 `0xfffb7299`。

### 8. 文件拆分

只新增 1 个文件：

- `app/tv/.../screens/user/UpInfoScreen.kt`：页面骨架、tab、焦点/返回编排、合集详情头部（含收藏）。
- `app/tv/.../screens/user/UpSpaceCards.kt`：`SpaceArticleCard`、`SpaceDynamicCard`、`SpaceCollectionCard`、`SpaceContentStatus`。

不新建 component 目录、不新增 ViewModel。

## 数据层改动

### `bili-api`

- `SpaceSeasonSeriesMeta`：新增可选 `mtime`、`ctime`。
- `SpaceCollection` / `SpaceSeries`：新增 `updateTime: Long?`（秒）。
  - 取值：`meta.mtime` → entry `archives` 里 `max(pubdate, ctime)` → `null`。
  - 判定写成纯函数并配单测（有 `mtime`、只有 `archives`、都没有三种情况）。
- `DynamicVideoModule`：新增 `playCount: Long`、`danmakuCount: Int`、`durationSeconds: Int`、`kind: DynamicVideoKind`，复用文件内已有的 `convertStringPlayCountToNumberPlayCount` / `convertStringTimeToSeconds`。
- 新增 `enum class DynamicVideoKind { Normal, DynamicVideo, Collab }` 与纯函数判定：
  1. 角标文本含「联合创作」→ `Collab`
  2. 角标文本含「动态视频」或标题以 `动态视频｜` 开头 → `DynamicVideo`
  3. App(gRPC) 路径 `stype == video_type_dynamic` → `DynamicVideo`
  4. 其余 → `Normal`
- `DynamicType.UgcSeason` 的 `TODO()`（web 与 app 两条映射各一处）改为映射成视频模块（web 取 `major.ugcSeason`，app 取 `dynUgcSeason`），`kind = Normal`，字段缺失时不再抛异常。这是本次改动直接经过的代码路径，属于崩溃点修复而非无关重构。
  - 注意：空间动态的 web 列表会用 `availableDynamicTypes` 过滤掉 `UGC_SEASON`，所以这条映射目前只在动态详情等路径生效；本次不放开该过滤，避免影响移动端「动态」页对该类型的渲染。

### `app/shared`

- `VideoCardData`：新增 `topRightBadge: String = ""` 并加入 `coverBadges`。
- `UserSpaceViewModel`：新增合集收藏状态与方法（见 7）。

### `app/tv`

- 新增 `UpSpaceTopNavItem` 适配器；新增 `UpSpaceCards.kt`。

## 风险与待确认

- **「动态视频」在 web 接口上无法精确判定**。空间动态走 `/x/polymer/web-dynamic/v1/feed/all`，没有 `stype`；探针被风控拦截（HTTP 412），无法离线确认真实角标文案。当前规则靠角标文本/标题前缀，真机上若有遗漏，这类卡片会显示为「投稿视频」。**修正点只有判定函数里的第 2 条规则**，其余逻辑不受影响。验收时必须用真实的「动态视频」卡片确认角标文案。
- `SpaceSeasonSeriesMeta` 的 `mtime` 是否真实下发未知；拿不到时回退到 `archives` 的最新时间，再拿不到就不显示，不会出错。
- 合集收藏依赖 `/x/v3/fav/folder/created/list-all` 的 `type=21&rid=seasonId` 组合；若该组合不返回 `fav_state`，表现是收藏后按钮状态不更新（写入本身仍成功）。

## 验收

编译：

```sh
./gradlew :bili-api:compileKotlin :app:shared:compileDebugKotlin :app:tv:compileDebugKotlin
```

单测：

- 新增纯逻辑测试：合集/系列更新时间推导、`DynamicVideoKind` 判定、`FavoriteResourceType.VideoCollection` 映射。
- `bili-api` 只跑新增的测试类（其余是联网集成测试）。
- `:app:shared:testDebugUnitTest`。

真机（TV）清单：

1. 进入 UP 空间，焦点在第一个 tab；方向键下移进入卡片区。
2. tab 样式与首页/搜索结果页一致；切换 tab 会回顶并按需加载。
3. 合集列表：封面 4:3、显示更新时间；图文 tab 只显示点赞数。
4. 进入合集详情 → 返回：回到列表并聚焦刚才那个合集；再返回：聚焦 tab 栏；再返回：退出页面。既有合集又有系列的 UP（如 巫小萤 `15183062`）要聚焦到正确的那一项。
5. 合集详情头部显示收藏；未收藏/已收藏状态正确；多收藏夹时弹出选择框。
6. 动态 tab：聚焦时文字不再反色；右上角类型标签正确；视频类卡片显示播放量/弹幕量/时长，点击进视频详情页。
7. 用真实的「动态视频」「联合创作」卡片确认标签文案。

## 不做

- 动态卡不长按菜单（现状也没有，不在本次需求内）。
- 不改移动端 UP 空间页。
- 不改 tab 的枚举与文案。
