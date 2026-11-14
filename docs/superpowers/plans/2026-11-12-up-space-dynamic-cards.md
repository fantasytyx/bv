# UP 空间动态卡片 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 TV 端 UP 空间「动态」tab 落地 4 列瀑布流 + 完整社交卡片（5 类），并修复部分 UP（如 咝小喵 `2009929`）动态加载失败。

**Architecture:** 数据层在 `bili-api` 做「单条动态容错 + 缺失字段兜底」，保证一条畸形卡片不会让整页失败；UI 层新增 `UpSpaceDynamicCards.kt` 承载卡片渲染与纯函数，`UpInfoScreen` 为动态 tab 单独使用 `LazyVerticalStaggeredGrid`（焦点交给 Compose 默认搜索）。

**Tech Stack:** Kotlin、Jetpack Compose（`androidx.compose.foundation.lazy.staggeredgrid`）、Ktor、kotlinx.serialization、kotlin.test。

**Spec:** `docs/superpowers/specs/2026-11-12-up-space-dynamic-cards-design.md`

## Global Constraints

- 目标端只有 TV：`app/tv` + 其依赖的 `bili-api` / `app/shared`；**不改 `app/mobile`**。
- 动态 tab 固定 **4 列瀑布流**；其他 tab 保持 4 列 `LazyVerticalGrid`。
- 图文与引用块最多 **3 张图**，`n > 3` 时第 3 张右下角显示 `+${n - 3}张`。
- 视频封面：播放/弹幕在左下、**时长在右下**（复用 `CardCover`，不改它）。
- 卡片内**不出现**「关注 / +关注」按钮。
- 头部副标题 = `pub_time` + ` · ` + `pub_action`（接口原值，空值跳过）。
- 整卡是唯一焦点；卡内统计栏、按钮、图片均不可聚焦。
- 不改 `UserRepository.getDynamicsByUp` 之外的动态端点；不改移动端。
- Kotlin 代码风格 `official`；注释只在需要解释「为什么」时写。
- 验证命令必须用 JDK 21：`$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21.0.9.10-hotspot"`。

## Review Focus

- **单条动态载荷畸形**（`DYNAMIC_TYPE_AV` 无 `archive`、`LIVE_RCMD` 只有 `major.live`、`live_rcmd.content` 缺 `live_play_info`）：整页仍要渲染其余动态，不能抛异常。
- **`pub_time` 为空**（直播动态）：头部只显示 `pub_action`，不能出现前后多余的分隔符。
- **图片数量** 0/1/2/3/4/20：布局与 `+(n-3)张` 文案必须精确，且 3 张以内不出现计数。
- **瀑布流方向键**：`LEFT/RIGHT` 必须落在同一「行」（`index / 3` 相同），`UP/DOWN` 固定 ±3，不按几何最近邻跳列。
- **末行 + 底部状态项**：`hasMore`/`error` 时按 DOWN 必须能落到「加载更多/重试」，不能被焦点守卫吞掉；没有底部状态时必须消费 DOWN，避免焦点甩到导航栏。

---

### Task 1: 修复 UP 空间动态加载失败

**Files:**
- Modify: `bili-api/src/main/kotlin/dev/aaa1115910/biliapi/http/BiliHttpApi.kt`（`configureDynamicFeedRequest` 保持原样；新增 `configureSpaceDynamicFeedRequest` + `getUpSpaceDynamicList`）
- Modify: `bili-api/src/main/kotlin/dev/aaa1115910/biliapi/repositories/UserRepository.kt:36`、`:289-310`
- Modify: `bili-api/src/main/kotlin/dev/aaa1115910/biliapi/entity/user/Dynamic.kt`（`fromDynamicData`、`fromDynamicItem`、`DynamicLiveRcmdModule`）
- Test: `bili-api/src/test/kotlin/dev/aaa1115910/biliapi/http/BiliHttpApiDynamicRequestTest.kt`
- Test: `bili-api/src/test/kotlin/dev/aaa1115910/biliapi/entity/user/DynamicPayloadResilienceTest.kt`（新建）

**Interfaces:**
- Consumes: `BiliResponse.getResponseData()`、`DynamicType.webValue`。
- Produces:
  - `internal const val BiliHttpApi.UpSpaceDynamicFeedPath = "/x/polymer/web-dynamic/v1/feed/space"`
  - `HttpRequestBuilder.configureSpaceDynamicFeedRequest(timezoneOffset, offset, sessData, hostMid, dedeUserID, dedeUserIDCkMd5, features?, spaceLoginGroup = 2, platform = "web")`
  - `BiliHttpApi.getUpSpaceDynamicList(mid, offset, timezoneOffset, sessData, dedeUserID, dedeUserIDCkMd5, features)`
  - `DynamicData.fromDynamicData(data)` 永不抛异常；单条失败时 `logger.warn` 并跳过
  - `DynamicType.fromWebValueOrNull(value: String?): DynamicType?`
  - `DynamicLiveRcmdModule.fromModuleLive(live: Major.Live): DynamicLiveRcmdModule`

- [ ] **Step 1: 写失败测试 —— 端点与参数**

在 `BiliHttpApiDynamicRequestTest` 增加：

```kotlin
@Test
fun `UP space feed uses the space endpoint instead of the followed feed`() {
    assertEquals("/x/polymer/web-dynamic/v1/feed/space", BiliHttpApi.UpSpaceDynamicFeedPath)
}

@Test
fun `UP space feed pages by offset only`() {
    val request = HttpRequestBuilder()
    with(BiliHttpApi) {
        request.configureSpaceDynamicFeedRequest(
            timezoneOffset = -480, offset = "cursor-1", sessData = "s",
            hostMid = 2009929L, dedeUserID = 1L, dedeUserIDCkMd5 = "h",
            features = "itemOpusStyle", spaceLoginGroup = 2, platform = "web"
        )
    }
    assertNull(request.url.parameters["page"])
    assertNull(request.url.parameters["type"])
    assertEquals("cursor-1", request.url.parameters["offset"])
    assertEquals("2", request.url.parameters["space_login_group"])
    assertEquals("web", request.url.parameters["platform"])
}
```

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :bili-api:test --tests "*BiliHttpApiDynamicRequestTest"`
Expected: FAIL（`UpSpaceDynamicFeedPath` / `configureSpaceDynamicFeedRequest` 均不存在）

- [ ] **Step 3: 实现端点与参数**

`/feed/space` 只接受 `timezone_offset`、`host_mid`、`offset`、`space_login_group`、`platform`、`features`，**不接受** `page`/`type`；首屏也带 `offset=`（空串）。

```kotlin
parameter("host_mid", hostMid)
parameter("offset", offset.orEmpty())
spaceLoginGroup?.let { parameter("space_login_group", it) }
platform?.let { parameter("platform", it) }
```

`UserRepository.getDynamicsByUp` 改调 `getUpSpaceDynamicList(mid = mid, offset = offset, ...)`，并把 `SpaceDynamicFeatures` 换成网页端完整列表（`itemOpusStyle,listOnlyfans,opusBigCover,onlyfansVote,forwardListHidden,decorationCard,commentsNewVersion,onlyfansAssetsV2,ugcDelete,onlyfansQaCard,avatarAutoTheme,sunflowerStyle,cardsEnhance,eva3CardOpus,eva3CardVideo,eva3CardComment,eva3CardUser`）。`getDynamicList` 恢复成纯 `/feed/all`（首页关注流）。

- [ ] **Step 4: 写失败测试 —— 畸形载荷**

新建 `DynamicPayloadResilienceTest`，逐条断言「不抛异常」：

```kotlin
private fun item(json: String) = DynamicItem.fromDynamicItem(json.decodeFromString<HttpDynamicItem>(json))

@Test fun `AV without archive payload does not throw`()   // major.type=MAJOR_TYPE_ARCHIVE, archive 缺失
@Test fun `LIVE_RCMD backed by major.live does not throw`() // major.live 有 title/cover/id
@Test fun `LIVE_RCMD content without live_play_info does not throw`()
@Test fun `PGC without pgc payload does not throw`()
@Test fun `missing module_author does not throw`()
```

- [ ] **Step 5: 运行确认失败**

Run: `./gradlew :bili-api:test --tests "*DynamicPayloadResilienceTest"`
Expected: FAIL（`NullPointerException` / `MissingFieldException`）

- [ ] **Step 6: 实现容错**

1. `DynamicData.fromDynamicData` 里把 `DynamicItem.fromDynamicItem(it)` 包成：
   ```kotlin
   runCatching { DynamicItem.fromDynamicItem(it) }
       .onFailure { e -> logger.warn(e) { "bad dynamic ${it.idStr} type ${it.type}" } }
       .getOrNull()
   ```
2. `fromDynamicItem` 内所有 `major!!` 改为 `major?`；AV/PGC/Article/None 缺失内容模块时保持对应字段为 null。
3. AV 且 `archive == null` 时，用 `DynamicWordModule.fromModuleDynamic(item.modules.moduleDynamic)` 兜底正文。
4. `LiveRcmd` 分支：`major?.liveRcmd?.let { runCatching { fromModuleDynamic(it) }.getOrNull() } ?: major?.live?.let { fromModuleLive(it) }`。
5. `LiveRcmdContent` 的 `live_play_info` 改为可空 + 默认值；`fromModuleDynamic` 在 `livePlayInfo == null` 时返回 null。
6. `author`：`item.modules.moduleAuthor?.let(::fromModuleAuthor) ?: DynamicAuthorModule("", "", 0, "", "")`。
7. `DynamicType.fromWebValueOrNull` 供内部使用，未知类型返回 null。

- [ ] **Step 7: 运行全部相关测试**

Run: `./gradlew :bili-api:test --tests "*DynamicPayloadResilienceTest" --tests "*DynamicBlockedMappingTest" --tests "*BiliHttpApiDynamicRequestTest"`
Expected: PASS

---

### Task 2: 补齐直播与充电专属字段

**Files:**
- Modify: `bili-api/src/main/kotlin/dev/aaa1115910/biliapi/entity/user/Dynamic.kt`
- Test: `bili-api/src/test/kotlin/dev/aaa1115910/biliapi/entity/user/DynamicExtraModuleMappingTest.kt`（新建）

**Interfaces:**
- Produces:
  ```kotlin
  data class DynamicLiveRcmdModule(
      val title: String, val cover: String, val roomId: Int,
      val areaName: String = "", val parentAreaName: String = "",
      val online: Int = 0, val liveStatus: Int = 0,
      val watchedText: String = "", val link: String = ""
  )
  data class DynamicBlockedModule(
      val title: String, val hintMessage: String, val iconUrl: String,
      val bgImageUrl: String = "", val buttonText: String = "", val jumpUrl: String = ""
  )
  ```

- [ ] **Step 1: 写失败测试**

`blocked.button.text/jump_url/bg_img` 映射；`live_rcmd.content` 的 `area_name/online/live_status/watched_show.text_large/link` 映射；缺字段时为默认值。

- [ ] **Step 2: 运行确认失败** — `./gradlew :bili-api:test --tests "*DynamicExtraModuleMappingTest"`

- [ ] **Step 3: 实现字段与映射**（两处 `fromModuleDynamic` 同步）

- [ ] **Step 4: 运行确认通过**

---

### Task 3: 卡片纯函数与单测

**Files:**
- Create: `app/tv/src/main/kotlin/dev/aaa1115910/bv/tv/screens/user/UpSpaceDynamicCards.kt`
- Modify: `app/tv/src/main/kotlin/dev/aaa1115910/bv/tv/screens/user/UpSpaceCards.kt`（迁出 `upSpaceTypeLabel` / `cardAction`）
- Test: `app/tv/src/test/kotlin/dev/aaa1115910/bv/tv/screens/user/SpaceDynamicCardTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  fun DynamicItem.upSpaceTypeLabel(): String
  fun DynamicItem.cardAction(): DynamicCardAction
  fun DynamicItem.headerSubtitle(): String
  fun DynamicItem.drawTextBlocks(): Pair<String?, String?>   // title, body（已去重）
  fun formatDynamicCount(count: Int): String
  fun DynamicItem.imagePreview(limit: Int = 3): DynamicImagePreview
  data class DynamicImagePreview(val urls: List<String>, val moreCount: Int)
  fun DynamicItem.quoted(): QuotedDynamic?
  data class QuotedDynamic(val author: String, val text: String, val video: DynamicVideoModule?, val imageUrls: List<String>, val moreCount: Int)
  enum class DynamicCardAction { Video, Article, Detail }
  ```

- [ ] **Step 1: 写失败测试**（在现有 `SpaceDynamicCardTest` 追加）

```kotlin
@Test fun `header subtitle joins time and action`()          // "3天前 · 投稿了视频"
@Test fun `header subtitle keeps action when time is blank`()  // "直播了"
@Test fun `header subtitle is blank when both are blank`()     // ""
@Test fun `image preview keeps three images and counts the rest`() // 7 -> 3 + 4; 20 -> 3 + 17
@Test fun `image preview below four has no counter`()          // 3 -> moreCount 0
@Test fun `count formats ten-thousands`()                      // 10000 "1万", 16300 "1.6万", 165000 "16万"
@Test fun `draw text blocks drop the duplicated title`()
@Test fun `quoted content prefers draw text and caps images at three`()
```

- [ ] **Step 2: 运行确认失败** — `./gradlew :app:tv:testDebugUnitTest --tests "*SpaceDynamicCardTest"`

- [ ] **Step 3: 实现纯函数**（新建文件，`SpaceDynamicCardTest` 同包无需改 import）

- [ ] **Step 4: 运行确认通过**

---

### Task 4: 卡片 UI

**Files:**
- Modify: `app/tv/src/main/kotlin/dev/aaa1115910/bv/tv/screens/user/UpSpaceDynamicCards.kt`
- Modify: `app/tv/src/main/kotlin/dev/aaa1115910/bv/tv/screens/user/UpSpaceCards.kt`

**Interfaces:**
- Consumes: Task 3 的纯函数；`CardCover`、`SpaceContentStatus`、`DynamicDetailActivity`、`ArticleActivity`、`VideoInfoActivity`。
- Produces: `@Composable fun SpaceDynamicCard(dynamic: DynamicItem, modifier: Modifier, onClick: () -> Unit, onFocus: () -> Unit)`。

- [ ] **Step 1: 实现 `SpaceDynamicCard` 与 5 个 body**

`DynamicCardShell`（焦点 Surface + 头部 + 尾部 slot）→ `DynamicVideoBody` / `DynamicLiveBody` / `DynamicDrawBody` / `DynamicForwardBody` / `DynamicBlockedBody`；辅助 `DynamicImageRow(images, moreCount)`、`DynamicStatBar(footer)`、`DynamicTypeBadge(text)`。

- [ ] **Step 2: 删除旧实现**

`UpSpaceCards.kt` 移除旧 `SpaceDynamicCard` 与仅其使用的私有组件；保留 `SpaceArticleCard`、`SpaceCollectionCard`、`SpaceContentStatus`。

- [ ] **Step 3: 编译**

Run: `./gradlew :app:tv:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

---

### Task 5: 4 列瀑布流 + 同列上下移动 + 卡片高度收紧

**Files:**
- Create: `app/tv/src/main/kotlin/dev/aaa1115910/bv/tv/util/DynamicGridFocus.kt`
- Test: `app/tv/src/test/kotlin/dev/aaa1115910/bv/tv/util/DynamicGridFocusTest.kt`（新建）
- Modify: `app/tv/src/main/kotlin/dev/aaa1115910/bv/tv/screens/user/UpSpaceDynamicCards.kt`（`mediaMaxHeight`）
- Modify: `app/tv/src/main/kotlin/dev/aaa1115910/bv/tv/screens/user/UpInfoScreen.kt`

**Interfaces:**
- Produces:
  ```kotlin
  data class GridItemBox(val index: Int, val left: Int, val top: Int, val right: Int, val bottom: Int)
  enum class GridVertical(val step: Int) { Up(-1), Down(1) }
  fun sameColumnNeighbor(current: GridItemBox, items: List<GridItemBox>, direction: GridVertical): Int?
  fun verticalScrollStep(current: GridItemBox, spacing: Int): Int
  fun dynamicMediaMaxHeight(viewportHeight: Dp, listPadding: Dp = 24.dp, nonMediaReserve: Dp = 200.dp): Dp
  ```

> 决策记录：v3 的 `RowAlignedGridFocus`（按 `index/3` 强行分行）实测「按 DOWN 焦点卡住」且迟滞，已删除。现在只接管 UP/DOWN 做**同列**移动，左右交给默认搜索：
> - 同列目标可见 → 直接 `requestFocus`（零延迟）；
> - 目标不可见 → 滚过「卡高 + 间距」，由 `snapshotFlow(layoutInfo)` 驱动复焦；
> - 焦点在底部状态 → `focusedDynamicIndex = -1`，不拦截。

- [ ] **Step 1: 写失败测试**

`DynamicGridFocusTest`：同列上/下选择、相邻列排除、无候选返回 null、滚动后锚点行（`top` 相等）可选中、`verticalScrollStep`。
`SpaceDynamicCardTest`：`dynamicMediaMaxHeight(340.dp) == 92.dp`、`(300.dp) == 80.dp`、`(540.dp) == 260.dp`。

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :app:tv:testDebugUnitTest --tests "*DynamicGridFocusTest" --tests "*SpaceDynamicCardTest"`
Expected: FAIL（符号不存在）

- [ ] **Step 3: 实现纯函数**

- `DynamicGridFocus.kt`：列判定用水平重叠 ≥ 较窄者一半；Down 取 `top >= current.top` 中最小、Up 取 `top <= current.top` 中最大。
- `dynamicMediaMaxHeight`：`(viewportHeight - listPadding*2 - nonMediaReserve).coerceIn(80.dp, 260.dp)`。

- [ ] **Step 4: 接入 `UpInfoScreen` / `UpSpaceDynamicCards`**

- 动态 tab 外层 `BoxWithConstraints`，把 `dynamicMediaMaxHeight(maxHeight)` 传给卡片；单图高度取 `min(宽/比例, mediaMaxHeight)`。
- `showLargeTitle = currentIndex < 4 && selectedTab != UserSpaceTab.Dynamics`。
- 容器挂 `.onPreviewKeyEvent(onDynamicVerticalKey)`；卡片挂 `Modifier.focusRequester(...)`。
- `focusedDynamicIndex` 由卡片 `onFocus` 写入，底部状态 `onFocusChanged { hasFocus }` 置 -1。
- `pendingVerticalMove` + `LaunchedEffect { snapshotFlow { layoutInfo.visibleItemsInfo } }` 复焦。
- 状态项用 `item(span = StaggeredGridItemSpan.FullLine)`；加载阈值 `index + 6 >= size`。

- [ ] **Step 5: 编译与测试**

Run: `./gradlew :app:tv:compileDebugKotlin`、`./gradlew :app:tv:testDebugUnitTest`
Expected: 33 tests / 0 failures

> **后续变更（spec v14）**：图文详情改为只请求动态详情一个接口（删除 `/opus/detail`、`/x/article/view` 及 `ArticleParagraphParser`、`mergedDynamic` 等半套实体）；分页状态收敛为 `PagingState`；合集详情移出主网格、头部固定，返回时按 key 复原焦点。
>
> **后续变更（spec v13）**：媒体区高度上限已移除——`dynamicMediaMaxHeight` / `DefaultMediaMaxHeight` / `SpaceDynamicCard.mediaMaxHeight` 不再存在，单图高度 = 卡宽 / 图片比例；聚焦卡被截断时按上下键滚动半屏（`revealScrollStep(viewportHeight)`）。本 Task 中与 `mediaMaxHeight`、`BoxWithConstraints`、`showLargeTitle` 特判相关的步骤以 spec v13 为准。

---

### Task 6: 全量验证与收尾

- [ ] **Step 1:** `./gradlew :bili-api:compileKotlin :app:shared:compileDebugKotlin :app:tv:compileDebugKotlin`
- [ ] **Step 2:** `./gradlew :bili-api:test --tests "*Dynamic*"`、`./gradlew :app:tv:testDebugUnitTest`
- [ ] **Step 3:** 更新 spec（v6、6.8、6.9、7.2、8.1、8.4、9）
- [ ] **Step 4:** 提交（含 spec、plan、代码）
