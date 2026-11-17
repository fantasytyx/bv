# TV 首页「其它收藏」：图文 / 合集收藏夹

> 目标端：Android TV（`app/tv`），共享逻辑放 `app/shared`，网络层放 `bili-api`
> 数据源：`/x/polymer/web-dynamic/v1/opus/feed/fav`、`/x/v3/fav/folder/collected/list`、`/x/space/fav/season/list`、`/x/v3/fav/resource/list`
> 状态：已实现（2026-11-16）
> 方案：A（新增独立页面 + 专用 ViewModel，复用现有卡片/分页组件）

## 1. 需求

TV 首页一级导航新增「其它收藏」，未登录时沿用 `LoginRequiredScreen`（"请先登录"）。页内两个二级 tab：

- **图文**：图文收藏列表。卡片与点击路由复用 UP 空间页图文列表（`SpaceArticleCard` / `ArticleActivity`），分页取 `page` + `page_size=20`。
- **合集/收藏夹**：一个混排网格，卡片复用 `SpaceCollectionCard`（角标区分"合集"/"收藏夹"）。点击进入内容列表，头部"返回 + 标题"，内容为 `SmallVideoCard` 4 列网格，返回后焦点回到原卡片——与 UP 空间页「合集和系列」一致。
  - 合集与收藏夹内容都走现有分页（合集复用 UP 空间合集接口）。
  - **失效项可聚焦，但点击不做任何响应，不能进入内容列表**（视觉上角标显示"已失效"）。

## 2. 非目标

- 不改 `app/mobile`。
- 不做取消收藏 / 取消订阅 / 收藏夹编辑；内容页头部只有返回与标题。
- 不抽公共"合集浏览器"组件，不改 `UpInfoScreen` 现有实现（仅给 `SpaceCollectionCard` 加一个可选角标参数）。
- 不改现有「收藏」一级 tab（用户自己的收藏夹）。
- 不改 Web / App 接口选择（本次三个新接口都是 Web 专用，不接 gRPC）。

## 3. 现状

| 位置 | 现状 |
|---|---|
| `app/tv/.../component/TopNav.kt` | `HomeTopNavItem` 枚举 8 项，`ordinal` 即持久化标识 |
| `app/tv/.../util/NavItemsExtensions.kt` | `parseHomeNavItemsOrder` 用 `defaultHomeNavOrder` 的**下标**当 ordinal 解析；保存过排序的用户看不到新增枚举项 |
| `app/tv/.../screens/main/HomeContent.kt` | 一级 tab 的加载 / 下拉刷新 / 未登录分支都在这一个 `when` 里；每个 tab 的网格滚动状态在 `HomeContent` 提升 |
| `app/tv/.../screens/user/UpSpaceCards.kt` | `SpaceArticleCard`、`SpaceCollectionCard`、`SpaceContentStatus` 均为 public，可直接复用 |
| `app/tv/.../screens/user/UpInfoScreen.kt` | 「合集和系列」的列表→内容→返回复焦、底部状态、DOWN 键守卫的参考实现 |
| `app/shared/.../viewmodel/user/PagingState.kt` | `PagingState` / `PageResult` / `newItems`，串行化 + 丢弃过期响应 + 错误落地 |
| `bili-api/.../repositories/FavoriteRepository.kt` | 已有 `getFavoriteFolderData` / `getAllFavoriteFolderMetadataList` / `setOpusFavorite`，**没有** opus 收藏列表与收藏的合集/收藏夹列表 |
| `bili-api/.../http/entity/user/favorite/` | 已有 `FavoriteFolderInfoListData`、`FavoriteItem`、`Upper`；无本次三个接口的 DTO |
| `app/shared/.../entity/carddata/FavoriteItemCardData.kt` | `FavoriteItem.toVideoCardDataOrNull()` 已处理视频与 PGC 跳转 |

## 4. 方案

**A. 新增独立页面 + 专用 ViewModel。** 「其它收藏」有自己的三份分页状态（图文 / 合集收藏夹 / 内容）与一个"当前展开项"，与「收藏」页的选中夹、删除模式、`FavoriteViewModel` 生命周期都不相交。复用点放在**纯展示组件**和 **`PagingState`** 上，不做跨页面抽取。

B（扩展 `FavoriteViewModel`）会让两个一级 tab 共享 activity 级 VM 的选中态与删除模式，语义也不同（"我的收藏夹" vs "我收藏的别人的合集/收藏夹"），不采用。
C（抽通用合集浏览器供 `UpInfoScreen` 共用）回归面过大，与本次收益不成比例，不采用。

## 5. 设计

### 5.1 接口与模型（`bili-api`）

三个新 HTTP 方法，全部 Web + 登录 Cookie（`appendWebCookie`），Referer 用 `https://space.bilibili.com`：

| 方法 | 路径 | 关键参数 | 响应 |
|---|---|---|---|
| `getOpusFavoriteFeed` | `/x/polymer/web-dynamic/v1/opus/feed/fav` | `page`、`page_size`、`timezone_offset=-480` | `data.items` / `data.has_more`（无 offset） |
| `getCollectedFavoriteFolders` | `/x/v3/fav/folder/collected/list` | `up_mid=Prefs.uid`、`pn`、`ps=50`、`platform=web`、`space_login_group=2` | `data.count` / `data.list` / `data.has_more` |

合集内容**不新增接口**，直接复用 UP 空间页的 `/x/polymer/web-space/seasons_archives_list`（`UserRepository.getSeasonArchives`）：
收藏项自带创建者 `mid` 与 `season_id`（就是 `id`），已实测可取到内容且分页正常，顺带白拿现成的 `SpaceVideo → VideoCardData` 映射。

实测结论（匿名可复现，已用 curl 验证）：

- `collected/list` 在 `platform=web` 时**同一 list 混排** `type=21`（合集）与 `type=11`（收藏夹），`count` 是总数，`has_more` 正常翻页；遇到其它 `type` 直接跳过，不当作收藏夹渲染。
- 失效项判据是 **`state != 0`**：合集 `title` 变"该合集已失效"、`mid=0`、`media_count=0`；收藏夹 `title` 变"收藏夹失效"，但 `media_count` 仍是原值。**不能**用 `attr` 判失效（正常项 `attr` 出现 2/22/38）。
- `/x/space/fav/season/list` 会忽略 `pn` / `ps` 一次吐全部，因此**不采用**。
- `opus/feed/fav` 未登录返回 `-101`，响应结构按同族 opus feed 与本地 PiliPlus 实现（`FavArticleData.items/has_more`）确认，真机再兜底。
- 收藏夹内容复用现有 `getFavoriteFolderData`（`/x/v3/fav/resource/list`，`platform=web`），无需新接口；实测不带 `space_login_group` 也能取到他人公开收藏夹。

DTO（`bili-api/.../http/entity/user/favorite/CollectedFavoriteData.kt`，字段全带默认值，只需两个类）：

```kotlin
CollectedFavoriteListData(count, list, has_more)
CollectedFavoriteItem(id, fid, mid, attr, title, cover, upper: Upper?, mtime, state, type, media_count)
```

领域模型（`bili-api/.../entity/CollectedFavorite.kt`）：

```kotlin
enum class CollectedFavoriteType { Season, Folder }   // 21=合集 11=收藏夹，其余 type 跳过

data class CollectedFavorite(
    val id: Long,
    val type: CollectedFavoriteType,
    val title: String,
    val cover: String,
    val ownerMid: Long = 0,      // 取合集内容用
    val upperName: String = "",
    val mediaCount: Int = 0,
    val updateTime: Long = 0,    // 秒级，UI 交给 toSmartDate
    val invalid: Boolean = false,
)

data class CollectedFavoritePage(val items: List<CollectedFavorite>, val hasMore: Boolean)
```

映射规则：`type` 21→`Season`、11→`Folder`、其它返回 null 被 `mapNotNull` 丢弃；`invalid = state != 0`。

`FavoriteRepository` 新增两个方法：`getOpusFavoriteFeed(page, pageSize = 20)`（复用 `SpaceArticlePage`）、
`getCollectedFavoriteFolders(page, pageSize = 50)`。合集内容由 ViewModel 直接调 `UserRepository.getSeasonArchives(ownerMid, id, page)`。

### 5.2 ViewModel（`app/shared/.../viewmodel/home/OtherFavoriteViewModel.kt`）

```kotlin
enum class OtherFavoriteTab(val label: String) { Articles("图文"), Collections("合集/收藏夹") }

@KoinViewModel
class OtherFavoriteViewModel(private val favoriteRepository: FavoriteRepository) : ViewModel() {
    val articles = mutableStateListOf<SpaceArticle>();      val articlesPaging = PagingState()
    val folders = mutableStateListOf<CollectedFavorite>();  val foldersPaging = PagingState()
    val contentVideos = mutableStateListOf<VideoCardData>(); val contentPaging = PagingState()

    var currentTab by mutableStateOf(OtherFavoriteTab.Articles); private set
    var selectedFolder by mutableStateOf<CollectedFavorite?>(null); private set

    fun selectTab(tab: OtherFavoriteTab)      // 记录并 loadIfEmpty()
    fun loadIfEmpty()                          // 当前 tab / 已展开内容为空时拉第一页
    fun loadMore()                             // 已展开内容 → loadContent()，否则按 tab 分发
    fun openFolder(folder: CollectedFavorite)  // invalid 直接 return；重置内容分页后拉取
    fun closeContent()
    fun refresh()                              // 清空并重载当前 tab，关闭已展开内容
}
```

- 图文：`articlesPaging.load` → `PageResult(newItems, hasMore = page.hasMore, nextPage = page + 1)`。
- 合集收藏夹：同上，key = `"${type}-${id}"`。
- 内容：`contentPaging` 先 reset，再按类型取：
  - `Season`：`userRepository.getSeasonArchives(ownerMid, seasonId = id, page)` → `SpaceVideo.toVideoCardData()`，`hasMore` 透传（与 UP 空间页同一套分页）。
  - `Folder`：`favoriteRepository.getFavoriteFolderData(id, pageSize = 20, pageNumber = page)` + `FavoriteItem.toVideoCardDataOrNull(favTime.toSmartDate())`，`hasMore` 透传。
- `SpaceArticlePage` 的 `offset` 不使用；分页只靠页码。

### 5.3 UI（`app/tv/.../screens/main/home/OtherFavoriteScreen.kt`）

```
Column
├─ TopNav(useSmallSize = true, paddingTop = 0.dp)   // 图文 / 合集收藏夹
└─ Box(weight 1f)
   ├─ 图文：LazyVerticalGrid(4 列, SpaceArticleCard, 底部 SpaceContentStatus)
   ├─ 合集收藏夹（未展开）：LazyVerticalGrid(4 列, SpaceCollectionCard, 底部状态)
   └─ 已展开：Column(Header, LazyVerticalGrid(4 列, SmallVideoCard, 底部状态))
```

- 数据网格三项都沿用 UP 空间页的既有做法：`ProvideListBringIntoViewSpec`、`blockDownFocusExitAtGridEnd` + `gridFocusItemCount` + `currentIndex`、聚焦到倒数第 12 个时 `loadMore()`、底部 `SpaceContentStatus`（`statusLoading = paging.loading || (list.isEmpty() && paging.hasMore)`）。
- 卡片 key：图文 `article.id`；收藏夹 `"${type}-${id}"`；内容 `VideoCardData.stableItemKey()`。
- `SpaceCollectionCard` 增加可选参数 `badgeText: String? = null`（null 时保持现有 `"$typeLabel · $videoCount 个视频"`）。失效项传 `badgeText = "已失效"` 且 `onClick = {}`；ViewModel 内也再挡一层。
- 内容头部是本页内的小 composable（返回胶囊 + 标题，返回按钮聚焦时把 `currentIndex` 置 0）；不抽到公共文件。
- 返回：`BackHandler(enabled = selectedFolder != null)` → 记录 `returnKey`、`closeContent()`；`LaunchedEffect` 在 `selectedFolder == null` 且 `returnKey != null` 时按 key 找回下标、`scrollToItem` 并 `requestFocusWithRetry()`。列表内的 Back（无展开项）交回 `HomeContent` 现有逻辑。
- 打开内容后把焦点放到第一张卡片（`FocusRequester` + `requestFocusWithRetry`），与 UP 空间页一致。
- 点击：图文 → `ArticleActivity.actionStart`；内容卡片 → `videoInfoRepository.setPreloadedVideoList(contentVideos)` 后按 `jumpToSeason` 分流 `SeasonInfoActivity` / `VideoInfoActivity`（与 `FavoriteScreen.openDetail` 一致，带 `ProxyArea.checkProxyArea`）。

### 5.4 首页接入与导航顺序

`HomeTopNavItem` **末尾追加** `OtherFavorite("其它收藏")`（ordinal 8，不动已有 ordinal）：

- `defaultHomeNavOrder` 里把「其它收藏」插到「收藏」之后（沿用现有"仅调整默认展示顺序"的做法）。
- `parseHomeNavItemsOrder` 改为：
  1. 用 `HomeTopNavItem.entries` 做 ordinal→枚举映射（修掉现在用 `defaultHomeNavOrder` 下标导致「追剧/稍后再看」互换的问题）；
  2. 保存的排序字符串里**未出现**的 ordinal（新增项）按默认位置插入，`-n` 隐藏项保持隐藏。
- `HomeContent`：
  - 注入 `OtherFavoriteViewModel`，新增三个 `rememberLazyGridState`（图文 / 列表 / 内容）并传入，切一级 tab 再回来保留位置；
  - `initData` / `onClick` / `currentListOnTop` / `AnimatedContent` 各补一个分支；未登录走 `LoginRequiredScreen`；
  - 再次点击一级「其它收藏」时 `refresh()`（关闭已展开内容、重载当前二级 tab）。

### 5.5 测试

- `bili-api/src/test/.../entity/CollectedFavoriteDataTest.kt`：用实测 JSON 片段守住 `type` → 枚举、`state != 0` → `invalid`、`has_more`、season `medias` 字段映射。
- `app/tv/src/test/.../util/HomeNavItemsOrderTest.kt`：默认顺序包含「其它收藏」且在「收藏」后；保存的旧排序（无 8）会补到默认位置；`-6` 隐藏项不被补回；ordinal 6/7 不再互换。
- `docs/CONTEXT.md` 增补「其它收藏」「收藏的合集/收藏夹」两条术语（与「图文收藏」「合集订阅」相邻）。

## 6. 验证

```sh
./gradlew :bili-api:compileKotlin :app:shared:compileDebugKotlin :app:tv:compileDebugKotlin
./gradlew :bili-api:test --tests "dev.aaa1115910.biliapi.entity.CollectedFavoriteDataTest"
./gradlew :app:tv:testDebugUnitTest :app:shared:testDebugUnitTest
```

（`:bili-api:test` 全量是联网集成测试，需 `local.properties` 里的 `test.sessdata`；本次只跑新单测。）

真机（TV）：

- 未登录进「其它收藏」显示"请先登录"；登录后两个二级 tab 都有数据。
- 图文：滚动加载、到底提示、点击进图文详情、返回后焦点/位置保持。
- 合集/收藏夹：合集与收藏夹混排且角标正确；失效项可聚焦、点击无反应；正常项进入内容列表。
- 内容：合集与收藏夹都能滚动分页、点击进视频/番剧详情；返回后焦点回到原卡片。
- 切二级 tab、切一级 tab 再回来、再次点一级「其它收藏」时都按 5.4 的语义刷新/保位。
- 已在设置里改过首页排序的账号也能看到新 tab。

## 7. 留给实现者

- 三个网格的列数 / padding / 加载阈值直接对齐 UP 空间页与首页「动态」页的现有数值，不加新配置。
- 内容头部只保留返回与标题；后续若要加"取消收藏"再单独设计。
- 若 `opus/feed/fav` 真机响应与 `items/has_more` 不符，只调整 `SpaceArticleData` 的复用方式，不动页面结构。
