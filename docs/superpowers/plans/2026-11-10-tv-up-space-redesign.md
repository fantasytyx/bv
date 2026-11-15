# TV UP 空间页改造 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 TV 端 UP 空间页拥有统一的 tab 样式与正确的三级返回焦点、合集收藏、4:3 合集封面与更新时间、精简的图文卡片、以及带类型标签和视频数据的动态卡片。

**Architecture:** 判定逻辑（更新时间、动态视频类型、收藏点击决策）下沉到 `bili-api` / `app/shared` 的纯函数并配单测；`app/tv` 只做渲染与焦点编排，卡片从 `UpInfoScreen.kt` 拆到 `UpSpaceCards.kt`（仅此一个文件）。

**Tech Stack:** Kotlin / Jetpack Compose for TV (`androidx.tv.material3`) / Koin / kotlinx.serialization / kotlin.test

**Spec:** [docs/superpowers/specs/2026-11-10-tv-up-space-redesign-design.md](../specs/2026-11-10-tv-up-space-redesign-design.md)

## Global Constraints

- 只改 TV 端：`app/tv` + 共享层 `app/shared` / `bili-api`。**不要动 `app/mobile`**。
- `JAVA_HOME` 必须指向 JDK 21 本体，否则 Gradle 报 toolchain 错误；构建命令用 `.\gradlew.bat`。
- compileSdk 37 / minSdk 23 / targetSdk 36，不得升级或新增依赖。
- Kotlin 代码风格 `official`；Koin 注解统一用 `org.koin.core.annotation.*`。
- 新增界面文案沿用现有文件的写法：直接中文字面量，不新增 `strings.xml` 条目。
- `UserSpaceTab` 的枚举值与 `label` 文案不变（`UserSpaceTabTest` 守着）。
- `app/tv` 没有 Compose UI 测试环境；UI 任务的自动化验证是「编译通过」，其余验收走 spec 的真机清单。

## Review Focus

1. **在合集详情里、焦点落在 grid 或头部按钮上按返回**：必须关闭详情，不能变成"聚焦 tab 栏"或"直接退出"。grid 上的 Back 拦截必须排除 `selectedArchive` 为 true，且详情头部不能再挂 `onBackPressed`，否则 `BackHandler` 收不到事件。（纯焦点接线，没有单测宿主；由 Task 12 的编译 + 真机验收项 4 覆盖。）
2. **既有合集又有系列的 UP（如 巫小萤 `15183062`）返回后聚焦**：系列卡在 grid 里的下标 = `合集数量 + 系列内下标`；算错就会聚焦到别的卡。
3. **未登录或收藏夹列表为空时点收藏**：不能发请求、不能对空列表取 `first()`、不能崩。
4. **动态视频的播放/弹幕文本是 `1.2万` 或空串**：解析失败会得到 `-1`，视频卡必须显示为空而不是 `0` 或 `-1`。
5. **动态里的 `UgcSeason` 及其它非视频类型**：不能因为字段缺失抛异常（现状 `DynamicType.UgcSeason -> TODO()` 会抛 `NotImplementedError`）。

---

### Task 1: 合集/系列更新时间（bili-api）

**Files:**
- Modify: `bili-api/src/main/kotlin/dev/aaa1115910/biliapi/http/entity/user/SpaceSeasonSeriesData.kt`
- Modify: `bili-api/src/main/kotlin/dev/aaa1115910/biliapi/entity/user/SpaceSeasonSeries.kt`
- Test: `bili-api/src/test/kotlin/dev/aaa1115910/biliapi/entity/user/SpaceSeasonSeriesUpdateTimeTest.kt`

**Interfaces:**
- Consumes: nothing
- Produces:
  - `data class SpaceCollection(seasonId, title, cover, description, videoCount, val updateTime: Long? = null)`
  - `data class SpaceSeries(seriesId, title, cover, description, videoCount, val updateTime: Long? = null)`
  - `internal fun SpaceSeasonSeriesEntry.resolveUpdateTime(): Long?`（秒级时间戳，`null` 表示无数据）

- [ ] **Step 1: 写失败测试**

```kotlin
package dev.aaa1115910.biliapi.entity.user

import dev.aaa1115910.biliapi.http.entity.user.SpaceArchiveItem
import dev.aaa1115910.biliapi.http.entity.user.SpaceSeasonSeriesData
import dev.aaa1115910.biliapi.http.entity.user.SpaceSeasonSeriesEntry
import dev.aaa1115910.biliapi.http.entity.user.SpaceSeasonSeriesMeta
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SpaceSeasonSeriesUpdateTimeTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `mtime from meta wins and is deserialized`() {
        val data = json.decodeFromString<SpaceSeasonSeriesData>(
            """{"items_lists":{"page":{"page_num":1,"page_size":10,"total":1},
            "seasons_list":[{"meta":{"season_id":11,"title":"合集A","cover":"c","description":"d","total":3,"mtime":1700000000},
            "archives":[{"pubdate":1600000000,"ctime":1650000000}]}]}}"""
        )
        val collection = SpaceSeasonSeriesPage.fromWebData(data).seasons.single()
        assertEquals(1700000000L, collection.updateTime)
    }

    @Test
    fun `falls back to newest archive time when meta time is absent`() {
        val entry = SpaceSeasonSeriesEntry(
            meta = SpaceSeasonSeriesMeta(seriesId = 7, name = "系列B", cover = "c", total = 2),
            archives = listOf(
                SpaceArchiveItem(pubdate = 1600000000, ctime = 1500000000),
                SpaceArchiveItem(pubdate = 1700000000, ctime = 1750000000)
            )
        )
        assertEquals(1750000000L, entry.resolveUpdateTime())
    }

    @Test
    fun `null when there is no time information at all`() {
        val entry = SpaceSeasonSeriesEntry(meta = SpaceSeasonSeriesMeta(seasonId = 5, title = "空合集"))
        assertNull(entry.resolveUpdateTime())
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `.\gradlew.bat :bili-api:test --tests "dev.aaa1115910.biliapi.entity.user.SpaceSeasonSeriesUpdateTimeTest"`
Expected: 编译失败——`updateTime` / `resolveUpdateTime` / `mtime` 不存在。

- [ ] **Step 3: 实现**

在 `SpaceSeasonSeriesMeta` 末尾追加两个字段（服务端没有时保持 0）：

```kotlin
    val ctime: Long = 0,
    val mtime: Long = 0
```

在 `SpaceSeasonSeries.kt` 中给两个数据类追加 `updateTime: Long? = null`，并在 `toSpaceCollection()` / `toSpaceSeries()` 里传 `updateTime = resolveUpdateTime()`。新增：

```kotlin
internal fun SpaceSeasonSeriesEntry.resolveUpdateTime(): Long? =
    meta?.mtime?.takeIf { it > 0 }
        ?: archives.maxOfOrNull { maxOf(it.pubdate, it.ctime) }?.takeIf { it > 0 }
```

- [ ] **Step 4: 跑测试确认通过**

Run: `.\gradlew.bat :bili-api:test --tests "dev.aaa1115910.biliapi.entity.user.SpaceSeasonSeriesUpdateTimeTest"`
Expected: PASS（3 个测试）

- [ ] **Step 5: 提交**

```bash
git add bili-api/src/main/kotlin/dev/aaa1115910/biliapi/entity/user/SpaceSeasonSeries.kt bili-api/src/main/kotlin/dev/aaa1115910/biliapi/http/entity/user/SpaceSeasonSeriesData.kt bili-api/src/test/kotlin/dev/aaa1115910/biliapi/entity/user/SpaceSeasonSeriesUpdateTimeTest.kt
git commit -m "feat(api): expose update time for space collections and series"
```

---

### Task 2: 动态视频的类型与数值（bili-api）

**Files:**
- Modify: `bili-api/src/main/kotlin/dev/aaa1115910/biliapi/entity/user/Dynamic.kt`
- Test: `bili-api/src/test/kotlin/dev/aaa1115910/biliapi/entity/user/DynamicVideoKindTest.kt`

**Interfaces:**
- Consumes: nothing
- Produces:
  - `enum class DynamicVideoKind { Normal, DynamicVideo, Collab }`
  - `internal fun classifyDynamicVideoKind(badgeTexts: List<String>, title: String, isDynamicVideoFromApi: Boolean = false): DynamicVideoKind`
  - `DynamicVideoModule` 追加 `playCount: Long = 0L`、`danmakuCount: Int = 0`、`durationSeconds: Int = 0`、`kind: DynamicVideoKind = DynamicVideoKind.Normal`（全部带默认值，加在参数列表末尾）

- [ ] **Step 1: 写失败测试**

```kotlin
package dev.aaa1115910.biliapi.entity.user

import dev.aaa1115910.biliapi.entity.user.DynamicItem.DynamicVideoModule
import dev.aaa1115910.biliapi.http.entity.dynamic.DynamicItem as HttpDynamicItem
import kotlin.test.Test
import kotlin.test.assertEquals

class DynamicVideoKindTest {
    @Test
    fun `collab badge wins over dynamic video badge`() {
        assertEquals(
            DynamicVideoKind.Collab,
            classifyDynamicVideoKind(listOf("联合创作"), "标题")
        )
        assertEquals(
            DynamicVideoKind.Collab,
            classifyDynamicVideoKind(listOf("联合创作", "动态视频"), "标题")
        )
    }

    @Test
    fun `dynamic video is detected from badge, title prefix or api flag`() {
        assertEquals(
            DynamicVideoKind.DynamicVideo,
            classifyDynamicVideoKind(listOf("动态视频"), "标题")
        )
        assertEquals(
            DynamicVideoKind.DynamicVideo,
            classifyDynamicVideoKind(emptyList(), "动态视频｜标题")
        )
        assertEquals(
            DynamicVideoKind.DynamicVideo,
            classifyDynamicVideoKind(emptyList(), "标题", isDynamicVideoFromApi = true)
        )
    }

    @Test
    fun `plain video and unrelated badges stay normal`() {
        assertEquals(DynamicVideoKind.Normal, classifyDynamicVideoKind(emptyList(), "标题"))
        assertEquals(
            DynamicVideoKind.Normal,
            classifyDynamicVideoKind(listOf("充电专属"), "标题")
        )
    }

    @Test
    fun `web archive mapping keeps numeric stats`() {
        val archive = HttpDynamicItem.Modules.Major.Archive(
            aid = 100L,
            title = "视频",
            cover = "c",
            durationText = "01:02",
            stat = HttpDynamicItem.Modules.Major.Archive.Stat(play = "1.2万", danmaku = "34")
        )
        val module = DynamicVideoModule.fromModuleArchive(archive)
        assertEquals(12000L, module.playCount)
        assertEquals(34, module.danmakuCount)
        assertEquals(62, module.durationSeconds)
        assertEquals(DynamicVideoKind.Normal, module.kind)
    }

    @Test
    fun `unparsable play count stays negative so the card renders nothing`() {
        val archive = HttpDynamicItem.Modules.Major.Archive(
            aid = 1L,
            title = "视频",
            cover = "c",
            stat = HttpDynamicItem.Modules.Major.Archive.Stat(play = "", danmaku = "")
        )
        val module = DynamicVideoModule.fromModuleArchive(archive)
        assertEquals(-1L, module.playCount)
        assertEquals(-1, module.danmakuCount)
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `.\gradlew.bat :bili-api:test --tests "dev.aaa1115910.biliapi.entity.user.DynamicVideoKindTest"`
Expected: 编译失败——`DynamicVideoKind` / `classifyDynamicVideoKind` / `playCount` 不存在。

- [ ] **Step 3: 实现**

在 `Dynamic.kt` 顶层（`DynamicType` 附近）加：

```kotlin
enum class DynamicVideoKind { Normal, DynamicVideo, Collab }

internal fun classifyDynamicVideoKind(
    badgeTexts: List<String>,
    title: String,
    isDynamicVideoFromApi: Boolean = false
): DynamicVideoKind {
    if (badgeTexts.any { it.contains("联合创作") }) return DynamicVideoKind.Collab
    if (isDynamicVideoFromApi ||
        badgeTexts.any { it.contains("动态视频") } ||
        title.startsWith("动态视频｜")
    ) return DynamicVideoKind.DynamicVideo
    return DynamicVideoKind.Normal
}
```

`DynamicVideoModule` 追加 4 个带默认值的字段；两个 `fromModuleArchive` 里补上：

- web：`playCount = convertStringPlayCountToNumberPlayCount(moduleArchive.stat?.play ?: "")`、`danmakuCount = convertStringPlayCountToNumberPlayCount(moduleArchive.stat?.danmaku ?: "").toInt()`、`durationSeconds = convertStringTimeToSeconds(moduleArchive.durationText)`、`kind = classifyDynamicVideoKind(listOfNotNull(moduleArchive.badge?.text), moduleArchive.title)`
- app：`badgeTexts = moduleArchive.badgeList.map { it.text }`；`durationSeconds = moduleArchive.duration.toInt().takeIf { it > 0 } ?: convertStringTimeToSeconds(moduleArchive.coverLeftText1)`；`isDynamicVideoFromApi = moduleArchive.stype == bilibili.app.dynamic.v2.VideoType.video_type_dynamic`（用全限定名，避免和 playurl 的 `VideoType` 撞名）

- [ ] **Step 4: 跑测试确认通过**

Run: `.\gradlew.bat :bili-api:test --tests "dev.aaa1115910.biliapi.entity.user.DynamicVideoKindTest"`
Expected: PASS（5 个测试）

- [ ] **Step 5: 提交**

```bash
git add bili-api/src/main/kotlin/dev/aaa1115910/biliapi/entity/user/Dynamic.kt bili-api/src/test/kotlin/dev/aaa1115910/biliapi/entity/user/DynamicVideoKindTest.kt
git commit -m "feat(api): classify dynamic videos and expose numeric stats"
```

---

### Task 3: 修掉 `DynamicType.UgcSeason -> TODO()`（bili-api）

**Files:**
- Modify: `bili-api/src/main/kotlin/dev/aaa1115910/biliapi/entity/user/Dynamic.kt`
- Test: `bili-api/src/test/kotlin/dev/aaa1115910/biliapi/entity/user/DynamicUgcSeasonMappingTest.kt`

**Interfaces:**
- Consumes: `DynamicVideoKind`（Task 2）
- Produces: `DynamicVideoModule.fromModuleUgcSeason(ugcSeason: HttpDynamicItem.Modules.Major.UgcSeason): DynamicVideoModule` 与 `fromModuleUgcSeason(ugcSeason: bilibili.app.dynamic.v2.MdlDynUGCSeason): DynamicVideoModule`

- [ ] **Step 1: 写失败测试**

```kotlin
package dev.aaa1115910.biliapi.entity.user

import dev.aaa1115910.biliapi.http.entity.dynamic.DynamicItem as HttpDynamicItem
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class DynamicUgcSeasonMappingTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `ugc season dynamic is mapped to a video module`() {
        val webItem = json.decodeFromString<HttpDynamicItem>(
            """{"id_str":"1","type":"DYNAMIC_TYPE_UGC_SEASON","modules":{
            "module_author":{"name":"up","face":"f","mid":1,"pub_time":"1小时前","pub_action":""},
            "module_dynamic":{"major":{"ugc_season":{
            "aid":100,"bvid":"BV1","cover":"c","desc":"d","duration_text":"01:02",
            "stat":{"play":"1.2万","danmaku":"34"},"title":"合集更新"}}},
            "module_stat":{"like":{"count":1},"comment":{"count":2},"forward":{"count":3}}}}"""
        )

        val item = DynamicItem.fromDynamicItem(webItem)

        assertEquals(DynamicType.UgcSeason, item.type)
        assertEquals(100L, item.video?.aid)
        assertEquals("合集更新", item.video?.title)
        assertEquals(12000L, item.video?.playCount)
        assertEquals(DynamicVideoKind.Normal, item.video?.kind)
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `.\gradlew.bat :bili-api:test --tests "dev.aaa1115910.biliapi.entity.user.DynamicUgcSeasonMappingTest"`
Expected: FAIL——抛 `NotImplementedError`（`TODO()`）。

- [ ] **Step 3: 实现**

1. 两个 `DynamicType.UgcSeason -> TODO()` 改为：

```kotlin
DynamicType.UgcSeason -> dynamicItem.video =
    DynamicVideoModule.fromModuleUgcSeason(item.modules.moduleDynamic.major!!.ugcSeason!!)
```

app 路径用 `DynamicVideoModule.fromModuleUgcSeason(item.getDynamicModule()!!.dynUgcSeason)`。

2. 在 `DynamicVideoModule.Companion` 里加两个 `fromModuleUgcSeason`，字段映射与 `fromModuleArchive` 一致，`kind = DynamicVideoKind.Normal`；app 版本 `bvid = null`、`cid = ugcSeason.cid`、`durationSeconds = ugcSeason.duration.toInt().takeIf { it > 0 } ?: convertStringTimeToSeconds(ugcSeason.coverLeftText1)`。

- [ ] **Step 4: 跑测试确认通过**

Run: `.\gradlew.bat :bili-api:test --tests "dev.aaa1115910.biliapi.entity.user.DynamicUgcSeasonMappingTest"`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add bili-api/src/main/kotlin/dev/aaa1115910/biliapi/entity/user/Dynamic.kt bili-api/src/test/kotlin/dev/aaa1115910/biliapi/entity/user/DynamicUgcSeasonMappingTest.kt
git commit -m "fix(api): map ugc season dynamics instead of crashing"
```

---

### Task 4: `FavoriteResourceType.VideoCollection`（bili-api）

**Files:**
- Modify: `bili-api/src/main/kotlin/dev/aaa1115910/biliapi/entity/Favorite.kt`
- Test: `bili-api/src/test/kotlin/dev/aaa1115910/biliapi/entity/FavoriteResourceTypeTest.kt`

**Interfaces:**
- Consumes: nothing
- Produces: `FavoriteResourceType.VideoCollection`（`value = 21`），`fromFavoriteItemType(FavoriteItemType.VideoCollection) == VideoCollection`

- [ ] **Step 1: 写失败测试**

```kotlin
package dev.aaa1115910.biliapi.entity

import kotlin.test.Test
import kotlin.test.assertEquals

class FavoriteResourceTypeTest {
    @Test
    fun `video collection maps to favorite type 21`() {
        assertEquals(21, FavoriteResourceType.VideoCollection.value)
        assertEquals(
            FavoriteResourceType.VideoCollection,
            FavoriteResourceType.fromFavoriteItemType(FavoriteItemType.VideoCollection)
        )
    }

    @Test
    fun `existing mappings are unchanged`() {
        assertEquals(FavoriteResourceType.Video, FavoriteResourceType.fromFavoriteItemType(FavoriteItemType.Video))
        assertEquals(FavoriteResourceType.Pgc, FavoriteResourceType.fromFavoriteItemType(FavoriteItemType.Pgc))
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `.\gradlew.bat :bili-api:test --tests "dev.aaa1115910.biliapi.entity.FavoriteResourceTypeTest"`
Expected: 编译失败——`VideoCollection` 未定义。

- [ ] **Step 3: 实现**

`FavoriteResourceType` 加 `VideoCollection(21);`，`fromFavoriteItemType` 加分支 `FavoriteItemType.VideoCollection -> VideoCollection`。`FavoriteRepository.getAllFavoriteFolderMetadataList` 的 `listType = if (resourceType == Pgc) 42 else resourceType.value` 无需改动。

- [ ] **Step 4: 跑测试确认通过**

Run: `.\gradlew.bat :bili-api:test --tests "dev.aaa1115910.biliapi.entity.FavoriteResourceTypeTest"`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add bili-api/src/main/kotlin/dev/aaa1115910/biliapi/entity/Favorite.kt bili-api/src/test/kotlin/dev/aaa1115910/biliapi/entity/FavoriteResourceTypeTest.kt
git commit -m "feat(api): support favoring ugc video collections"
```

---

### Task 5: 卡片通用右上角角标（app/shared）

**Files:**
- Modify: `app/shared/src/main/kotlin/dev/aaa1115910/bv/entity/carddata/VideoCardData.kt`
- Test: `app/shared/src/test/kotlin/dev/aaa1115910/bv/entity/carddata/VideoCardDataTest.kt`

**Interfaces:**
- Consumes: nothing
- Produces: `VideoCardData.topRightBadge: String = ""`，非空时出现在 `coverBadges` 末尾

- [ ] **Step 1: 写失败测试**

```kotlin
package dev.aaa1115910.bv.entity.carddata

import kotlin.test.Test
import kotlin.test.assertEquals

class VideoCardDataTest {
    @Test
    fun `top right badge is rendered as a cover badge`() {
        val data = VideoCardData(
            avid = 1, title = "t", cover = "", upName = "",
            topRightBadge = "投稿视频"
        )
        assertEquals(listOf("投稿视频"), data.coverBadges)
    }

    @Test
    fun `blank top right badge renders nothing`() {
        val data = VideoCardData(avid = 1, title = "t", cover = "", upName = "")
        assertEquals(emptyList(), data.coverBadges)
    }

    @Test
    fun `charging and interactive badges keep their priority`() {
        val data = VideoCardData(
            avid = 1, title = "t", cover = "", upName = "",
            isInteractive = true, isChargingArc = true, badgeText = "充电专属",
            topRightBadge = "动态视频"
        )
        assertEquals(listOf("互动视频", "⚡充电专属", "动态视频"), data.coverBadges)
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `.\gradlew.bat :app:shared:testDebugUnitTest --tests "dev.aaa1115910.bv.entity.carddata.VideoCardDataTest"`
Expected: 编译失败——`topRightBadge` 不存在。

- [ ] **Step 3: 实现**

`VideoCardData` 参数列表末尾加 `val topRightBadge: String = ""`；`coverBadges` 的 `listOfNotNull(...)` 末尾加 `topRightBadge.takeIf { it.isNotBlank() }`。

- [ ] **Step 4: 跑测试确认通过**

Run: `.\gradlew.bat :app:shared:testDebugUnitTest --tests "dev.aaa1115910.bv.entity.carddata.VideoCardDataTest"`
Expected: PASS（3 个测试）

- [ ] **Step 5: 提交**

```bash
git add app/shared/src/main/kotlin/dev/aaa1115910/bv/entity/carddata/VideoCardData.kt app/shared/src/test/kotlin/dev/aaa1115910/bv/entity/carddata/VideoCardDataTest.kt
git commit -m "feat(card): add generic top-right badge to video cards"
```

---

### Task 6: 收藏点击决策 + 合集收藏状态（app/shared）

**Files:**
- Create: `app/shared/src/main/kotlin/dev/aaa1115910/bv/viewmodel/user/CollectionFavoritePlanner.kt`
- Modify: `app/shared/src/main/kotlin/dev/aaa1115910/bv/viewmodel/user/UserSpaceViewModel.kt`
- Test: `app/shared/src/test/kotlin/dev/aaa1115910/bv/viewmodel/user/CollectionFavoritePlannerTest.kt`

**Interfaces:**
- Consumes: `FavoriteResourceType.VideoCollection`（Task 4）
- Produces:
  - `enum class CollectionFavoriteAction { AddToDefaultFolder, OpenDialog }`
  - `fun planCollectionFavoriteClick(isFavorited: Boolean, folderCount: Int): CollectionFavoriteAction`
  - `UserSpaceViewModel`：`collectionFavoriteFolders: List<FavoriteFolderMetadata>`、`collectionFavoriteFolderIds: List<Long>`、`collectionFavoriteLoading: Boolean`、`fun updateCollectionFavoriteFolders(folderIds: List<Long>)`、`fun addCollectionToDefaultFavoriteFolder()`

- [ ] **Step 1: 写失败测试**

```kotlin
package dev.aaa1115910.bv.viewmodel.user

import kotlin.test.Test
import kotlin.test.assertEquals

class CollectionFavoritePlannerTest {
    @Test
    fun `unfavorited collection with a single folder is added directly`() {
        assertEquals(
            CollectionFavoriteAction.AddToDefaultFolder,
            planCollectionFavoriteClick(isFavorited = false, folderCount = 1)
        )
    }

    @Test
    fun `unfavorited collection with several folders opens the dialog`() {
        assertEquals(
            CollectionFavoriteAction.OpenDialog,
            planCollectionFavoriteClick(isFavorited = false, folderCount = 3)
        )
    }

    @Test
    fun `already favorited always opens the dialog`() {
        assertEquals(
            CollectionFavoriteAction.OpenDialog,
            planCollectionFavoriteClick(isFavorited = true, folderCount = 1)
        )
    }

    @Test
    fun `empty folder list never triggers a direct add`() {
        assertEquals(
            CollectionFavoriteAction.OpenDialog,
            planCollectionFavoriteClick(isFavorited = false, folderCount = 0)
        )
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `.\gradlew.bat :app:shared:testDebugUnitTest --tests "dev.aaa1115910.bv.viewmodel.user.CollectionFavoritePlannerTest"`
Expected: 编译失败——`CollectionFavoriteAction` 未定义。

- [ ] **Step 3: 实现 planner**

```kotlin
package dev.aaa1115910.bv.viewmodel.user

enum class CollectionFavoriteAction { AddToDefaultFolder, OpenDialog }

fun planCollectionFavoriteClick(
    isFavorited: Boolean,
    folderCount: Int
): CollectionFavoriteAction =
    if (!isFavorited && folderCount == 1) CollectionFavoriteAction.AddToDefaultFolder
    else CollectionFavoriteAction.OpenDialog
```

- [ ] **Step 4: 跑测试确认通过**

Run: `.\gradlew.bat :app:shared:testDebugUnitTest --tests "dev.aaa1115910.bv.viewmodel.user.CollectionFavoritePlannerTest"`
Expected: PASS（4 个测试）

- [ ] **Step 5: 实现 ViewModel 状态**

`UserSpaceViewModel` 构造函数加 `private val favoriteRepository: FavoriteRepository`（Koin 注解处理器会注入）。新增状态与行为：

- `openCollection(collection)` 在 `resetArchives(); updateArchives()` 之后调用 `syncCollectionFavoriteState(collection.seasonId)`；`openSeries` 调用 `clearCollectionFavoriteState()`；`closeArchives()` 与 `initialize()` 也清空。
- `syncCollectionFavoriteState(seasonId: Long)`：`!Prefs.isLogin || seasonId <= 0` 时直接清空并返回；否则用 `archiveGeneration` 快照做竞态保护，`favoriteRepository.getAllFavoriteFolderMetadataList(mid = Prefs.uid, resourceType = FavoriteResourceType.VideoCollection, rid = seasonId, preferApiType = Prefs.apiType)`，结果写入 `collectionFavoriteFolders`，`collectionFavoriteFolderIds = folders.filter { it.videoInThisFav }.map { it.id }`；`finally` 里按 generation 复位 `collectionFavoriteLoading`。
- `updateCollectionFavoriteFolders(folderIds)`：`selectedCollection?.seasonId ?: return`；`FavoriteFolderChange.between(collectionFavoriteFolderIds, folderIds.toList())`，空则 return；先乐观更新 `collectionFavoriteFolderIds`，再在 `viewModelScope` 里调用 `favoriteRepository.updateVideoToFavoriteFolder(aid = seasonId, addMediaIds = change.addMediaIds, delMediaIds = change.delMediaIds, resourceType = FavoriteResourceType.VideoCollection, preferApiType = Prefs.apiType)`；失败时重新 `syncCollectionFavoriteState(seasonId)`。
- `addCollectionToDefaultFavoriteFolder()`：`collectionFavoriteFolders.singleOrNull()?.let { updateCollectionFavoriteFolders(listOf(it.id)) }`。

- [ ] **Step 6: 编译并提交**

Run: `.\gradlew.bat :app:shared:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

```bash
git add app/shared/src/main/kotlin/dev/aaa1115910/bv/viewmodel/user/CollectionFavoritePlanner.kt app/shared/src/main/kotlin/dev/aaa1115910/bv/viewmodel/user/UserSpaceViewModel.kt app/shared/src/test/kotlin/dev/aaa1115910/bv/viewmodel/user/CollectionFavoritePlannerTest.kt
git commit -m "feat(space): track ugc collection favorite state"
```

---

### Task 7: 返回后聚焦下标的纯函数（app/tv）

**Files:**
- Create: `app/tv/src/main/kotlin/dev/aaa1115910/bv/tv/util/UpSpaceArchiveNavigation.kt`
- Test: `app/tv/src/test/kotlin/dev/aaa1115910/bv/tv/util/UpSpaceArchiveNavigationTest.kt`

**Interfaces:**
- Consumes: nothing
- Produces: `fun archiveFocusIndex(key: String?, seasonIds: List<Long>, seriesIds: List<Long>): Int`（返回合并 grid 里的下标，找不到返回 `-1`）

- [ ] **Step 1: 写失败测试**

```kotlin
package dev.aaa1115910.bv.tv.util

import kotlin.test.Test
import kotlin.test.assertEquals

class UpSpaceArchiveNavigationTest {
    private val seasonIds = listOf(11L, 12L)
    private val seriesIds = listOf(21L, 22L)

    @Test
    fun `season index is used as-is`() {
        assertEquals(1, archiveFocusIndex("season-12", seasonIds, seriesIds))
    }

    @Test
    fun `series index is offset by the season count`() {
        assertEquals(2, archiveFocusIndex("series-21", seasonIds, seriesIds))
        assertEquals(3, archiveFocusIndex("series-22", seasonIds, seriesIds))
    }

    @Test
    fun `unknown keys return minus one`() {
        assertEquals(-1, archiveFocusIndex(null, seasonIds, seriesIds))
        assertEquals(-1, archiveFocusIndex("season-99", seasonIds, seriesIds))
        assertEquals(-1, archiveFocusIndex("series-99", seasonIds, seriesIds))
        assertEquals(-1, archiveFocusIndex("bogus", seasonIds, seriesIds))
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `.\gradlew.bat :app:tv:testDebugUnitTest --tests "dev.aaa1115910.bv.tv.util.UpSpaceArchiveNavigationTest"`
Expected: 编译失败——`archiveFocusIndex` 未定义。

- [ ] **Step 3: 实现**

```kotlin
package dev.aaa1115910.bv.tv.util

fun archiveFocusIndex(
    key: String?,
    seasonIds: List<Long>,
    seriesIds: List<Long>
): Int {
    if (key == null) return -1
    return when {
        key.startsWith("season-") -> {
            val id = key.removePrefix("season-").toLongOrNull() ?: return -1
            seasonIds.indexOf(id)
        }
        key.startsWith("series-") -> {
            val id = key.removePrefix("series-").toLongOrNull() ?: return -1
            val index = seriesIds.indexOf(id)
            if (index < 0) -1 else seasonIds.size + index
        }
        else -> -1
    }
}
```

（`key.startsWith` / `toLongOrNull` 是软关键字，可以直接当函数名用；若 Kotlin 版本报歧义，写成 `key.startsWith(prefix = "season-")`。）

- [ ] **Step 4: 跑测试确认通过**

Run: `.\gradlew.bat :app:tv:testDebugUnitTest --tests "dev.aaa1115910.bv.tv.util.UpSpaceArchiveNavigationTest"`
Expected: PASS（3 个测试）

- [ ] **Step 5: 提交**

```bash
git add app/tv/src/main/kotlin/dev/aaa1115910/bv/tv/util/UpSpaceArchiveNavigation.kt app/tv/src/test/kotlin/dev/aaa1115910/bv/tv/util/UpSpaceArchiveNavigationTest.kt
git commit -m "feat(tv): compute archive focus index after leaving a collection"
```

---

### Task 8: 拆出 `UpSpaceCards.kt`（纯移动，无行为变化）

**Files:**
- Create: `app/tv/src/main/kotlin/dev/aaa1115910/bv/tv/screens/user/UpSpaceCards.kt`
- Modify: `app/tv/src/main/kotlin/dev/aaa1115910/bv/tv/screens/user/UpInfoScreen.kt`

**Interfaces:**
- Consumes: nothing
- Produces: 同包可见的 `SpaceArticleCard`、`SpaceDynamicCard`、`SpaceCollectionCard`、`SpaceContentStatus`（去掉 `private`，签名保持现状）

- [ ] **Step 1: 原样搬移**

把 `UpInfoScreen.kt` 里 `@Composable private fun SpaceArticleCard` 到文件末尾的 4 个 Composable 整体移到新文件，包名保持 `dev.aaa1115910.bv.tv.screens.user`，去掉 `private`。`UpInfoScreen.kt` 删掉只被这些函数使用的 import（`TextOverflow`、`ContentScale`、`AsyncImage`、`sp`、`Alignment` 等按编译报错清理），保留 `UpFacePreview` 在 `UpInfoScreen.kt`。

- [ ] **Step 2: 编译**

Run: `.\gradlew.bat :app:tv:compileDebugKotlin`
Expected: BUILD SUCCESSFUL（`UpInfoScreen.kt` 从 931 行降到约 700 行）

- [ ] **Step 3: 提交**

```bash
git add app/tv/src/main/kotlin/dev/aaa1115910/bv/tv/screens/user/UpInfoScreen.kt app/tv/src/main/kotlin/dev/aaa1115910/bv/tv/screens/user/UpSpaceCards.kt
git commit -m "refactor(tv): extract up space cards into their own file"
```

---

### Task 9: 合集/系列卡 4:3 + 更新时间；图文卡只显示点赞

**Files:**
- Modify: `app/tv/src/main/kotlin/dev/aaa1115910/bv/tv/screens/user/UpSpaceCards.kt`
- Modify: `app/tv/src/main/kotlin/dev/aaa1115910/bv/tv/screens/user/UpInfoScreen.kt`

**Interfaces:**
- Consumes: `SpaceCollection.updateTime` / `SpaceSeries.updateTime`（Task 1）
- Produces: `SpaceCollectionCard(title, cover, description, videoCount, typeLabel, updateTime: Long?, onClick, onFocus)`

- [ ] **Step 1: 改 `SpaceCollectionCard`**

签名加 `updateTime: Long?`。封面 `Modifier.fillMaxWidth().aspectRatio(4f / 3f)` 替换固定 `height(180.dp)`；`updateTime?.let { remember(it) { it.toSmartDate() } }` 非空时，在描述下方追加一行 `Text("$it 更新", color = onSurface.copy(alpha = 0.6f), fontSize = 13.sp)`。`toSmartDate()` 来自 `dev.aaa1115910.biliapi.entity.ugc`。

- [ ] **Step 2: 改 `SpaceArticleCard` 的 meta 行**

把 `"${article.viewCount} 播放 · ${article.likeCount} 赞 · ${article.replyCount} 评论"` 换成点赞数：

```kotlin
val likeText = if (article.likeCount >= 10000) "${article.likeCount / 10000}万" else "${article.likeCount}"
Text("$likeText 赞", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f), fontSize = 13.sp)
```

- [ ] **Step 3: 传参**

`UpInfoScreen.kt` 里两个 `SpaceCollectionCard(...)` 调用加 `updateTime = collection.updateTime` / `updateTime = series.updateTime`。

- [ ] **Step 4: 编译**

Run: `.\gradlew.bat :app:tv:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: 提交**

```bash
git add app/tv/src/main/kotlin/dev/aaa1115910/bv/tv/screens/user/UpInfoScreen.kt app/tv/src/main/kotlin/dev/aaa1115910/bv/tv/screens/user/UpSpaceCards.kt
git commit -m "feat(tv): 4:3 collection covers with update time and leaner article cards"
```

---

### Task 10: 动态卡（聚焦配色、类型标签、视频卡复用）

**Files:**
- Modify: `app/tv/src/main/kotlin/dev/aaa1115910/bv/tv/screens/user/UpSpaceCards.kt`
- Modify: `app/tv/src/main/kotlin/dev/aaa1115910/bv/tv/screens/user/UpInfoScreen.kt`

**Interfaces:**
- Consumes: `DynamicVideoModule.kind/playCount/danmakuCount/durationSeconds`（Task 2）、`VideoCardData.topRightBadge`（Task 5）
- Produces: `fun DynamicItem.upSpaceTypeLabel(): String`（`UpSpaceCards.kt` 内公开，供 `UpInfoScreen` 判断是否视频卡）

- [ ] **Step 1: 类型标签**

在 `UpSpaceCards.kt` 加：

```kotlin
fun DynamicItem.upSpaceTypeLabel(): String = when {    video != null -> when (video?.kind) {
        DynamicVideoKind.Collab -> "联合创作"
        DynamicVideoKind.DynamicVideo -> "动态视频"
        else -> "投稿视频"
    }
    draw != null || word != null || article != null -> "图文"
    pgc != null -> "番剧"
    liveRcmd != null -> "直播"
    type == DynamicType.Forward -> "转发"
    else -> "动态"
}
```

- [ ] **Step 2: 视频类走 `SmallVideoCard`**

`SpaceDynamicCard` 开头（`UpSpaceCards.kt` 需要 import `dev.aaa1115910.biliapi.entity.user.DynamicType`、`DynamicVideoKind`、`dev.aaa1115910.bv.entity.carddata.VideoCardData`、`SmallVideoCard`）：

```kotlin
val video = dynamic.video
if (video != null) {
    SmallVideoCard(
        modifier = modifier,
        data = remember(video, dynamic.id) {
            VideoCardData(
                avid = video.aid,
                title = video.title,
                cover = video.cover,
                upName = "",
                play = video.playCount,
                danmaku = video.danmakuCount,
                time = video.durationSeconds * 1000L,
                pubTime = dynamic.author.pubTime,
                topRightBadge = dynamic.upSpaceTypeLabel()
            )
        },
        onClick = onClick,
        onFocus = onFocus
    )
    return
}
```

- [ ] **Step 3: 非视频卡修聚焦配色 + 右上角标签**

`Surface` 的 `colors` 改为：

```kotlin
colors = ClickableSurfaceDefaults.colors(
    containerColor = Color.Transparent,
    focusedContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
    contentColor = MaterialTheme.colorScheme.onSurface,
    focusedContentColor = MaterialTheme.colorScheme.onSurface
)
```

封面用 `Box` 包住 `AsyncImage`，右上角 `Text(dynamic.upSpaceTypeLabel())` 使用「黑色 30% 底 + 白字 + `RoundedCornerShape(4.dp)` + `padding(horizontal = 4.dp, vertical = 1.dp)` + `Modifier.align(Alignment.TopEnd).padding(5.dp)`」；无封面时在内容列首行放一个右对齐的同款标签。

- [ ] **Step 4: 点击行为分流**

`UpInfoScreen.kt` 的「动态」tab：

```kotlin
onClick = {
    val video = dynamic.video
    if (video != null) {
        videoInfoRepository.setPreloadedVideoList(
            userSpaceViewModel.tvSpaceDynamics.mapNotNull { item ->
                item.video?.let { v ->
                    VideoCardData(
                        avid = v.aid, title = v.title, cover = v.cover, upName = "",
                        play = v.playCount, danmaku = v.danmakuCount,
                        time = v.durationSeconds * 1000L, pubTime = item.author.pubTime
                    )
                }
            }
        )
        VideoInfoActivity.actionStart(
            context = context,
            aid = video.aid,
            proxyArea = ProxyArea.checkProxyArea(video.title)
        )
    } else {
        dynamic.id?.let { DynamicDetailActivity.actionStart(context, it) }
    }
}
```

- [ ] **Step 5: 编译**

Run: `.\gradlew.bat :app:tv:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: 提交**

```bash
git add app/tv/src/main/kotlin/dev/aaa1115910/bv/tv/screens/user/UpInfoScreen.kt app/tv/src/main/kotlin/dev/aaa1115910/bv/tv/screens/user/UpSpaceCards.kt
git commit -m "feat(tv): dynamic cards show type badge, stats and open videos"
```

---

### Task 11: tab 换 `TopNav` + 初始焦点

**Files:**
- Modify: `app/tv/src/main/kotlin/dev/aaa1115910/bv/tv/screens/user/UpInfoScreen.kt`

**Interfaces:**
- Consumes: `TopNav` / `TopNavItem`（`dev.aaa1115910.bv.tv.component`）
- Produces: 页面内的 `tabFocusRequester`、`restoreFocusRequester`（Task 12 使用）

- [ ] **Step 1: 加适配器**

文件内新增：

```kotlin
private class UpSpaceTopNavItem(val tab: UserSpaceTab) : TopNavItem {
    override fun getDisplayName(context: Context): String = tab.label
}
```

并在页面内 `val tabItems = remember { UserSpaceTab.entries.map(::UpSpaceTopNavItem) }`。

- [ ] **Step 2: 替换 chip 行**

删掉 `Row { UserSpaceTab.entries.forEach { Surface(...) } }`，换成：

```kotlin
val navSwitchMode by Prefs.navSwitchModeFlow.collectAsStateLazily { Prefs.navSwitchMode }
val tabFocusRequester = remember { FocusRequester() }

TopNav(
    paddingTop = 0.dp,
    items = tabItems,
    initialSelectedItem = tabItems.first { it.tab == selectedTab },
    navSwitchMode = navSwitchMode,
    tabFocusRequester = tabFocusRequester,
    onSelectedChanged = { item ->
        val tab = (item as UpSpaceTopNavItem).tab
        if (selectedTab != tab) {
            selectedTab = tab
            currentIndex = 0
            val shouldLoad = when (tab) {
                UserSpaceTab.Videos -> userSpaceViewModel.tvSpaceVideos.isEmpty() &&
                    userSpaceViewModel.videosError == null
                UserSpaceTab.Articles -> userSpaceViewModel.tvSpaceArticles.isEmpty() &&
                    userSpaceViewModel.articlesError == null
                UserSpaceTab.Dynamics -> userSpaceViewModel.tvSpaceDynamics.isEmpty() &&
                    userSpaceViewModel.dynamicsError == null
                UserSpaceTab.Collections -> userSpaceViewModel.tvSpaceCollections.isEmpty() &&
                    userSpaceViewModel.tvSpaceSeries.isEmpty() &&
                    userSpaceViewModel.collectionsError == null
            }
            if (shouldLoad) userSpaceViewModel.load(tab)
            scope.launch { lazyGridState.scrollToItem(0) }
        }
    }
)
```

这里用到的 `lazyGridState` 在下一步声明。`import dev.aaa1115910.bv.util.collectAsStateLazily` 与 `dev.aaa1115910.bv.tv.component.TopNav` / `TopNavItem` 需要补上。

- [ ] **Step 3: 初始焦点与旧焦点逻辑**

- 删掉 `val listFocusRequester = remember { FocusRequester() }`、`LaunchedEffect(userSpaceViewModel.tvSpaceVideos.isNotEmpty()) { … }`，以及卡片上的 `Modifier.focusRequester(listFocusRequester)`。
- 加 `val lazyGridState = rememberLazyGridState()`（Task 12 复用），并把 `LazyVerticalGrid` 的 `state = lazyGridState` 接上。
- 加：

```kotlin
LaunchedEffect(Unit) {
    repeat(20) {
        if (runCatching { tabFocusRequester.requestFocus() }.isSuccess) return@LaunchedEffect
        delay(50)
    }
}
```

- [ ] **Step 4: 编译**

Run: `.\gradlew.bat :app:tv:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: 提交**

```bash
git add app/tv/src/main/kotlin/dev/aaa1115910/bv/tv/screens/user/UpInfoScreen.kt
git commit -m "feat(tv): use the shared TopNav pill tabs on the up space page"
```

---

### Task 12: 三级返回 + 焦点恢复 + 合集收藏 UI

**Files:**
- Modify: `app/tv/src/main/kotlin/dev/aaa1115910/bv/tv/screens/user/UpInfoScreen.kt`

**Interfaces:**
- Consumes: `archiveFocusIndex`（Task 7）、`planCollectionFavoriteClick`（Task 6）、`FavoriteFolderDialog`
- Produces: nothing

- [ ] **Step 1: 状态与恢复逻辑**

```kotlin
val restoreFocusRequester = remember { FocusRequester() }
var pendingFocusKey by remember { mutableStateOf<String?>(null) }
var openedArchiveKey by remember { mutableStateOf<String?>(null) }
var showCollectionFavoriteDialog by remember { mutableStateOf(false) }

val closeArchiveAndRestoreFocus: () -> Unit = {
    currentIndex = 0
    pendingFocusKey = openedArchiveKey
    userSpaceViewModel.closeArchives()
}

LaunchedEffect(pendingFocusKey) {
    val key = pendingFocusKey ?: return@LaunchedEffect
    val index = archiveFocusIndex(
        key = key,
        seasonIds = userSpaceViewModel.tvSpaceCollections.map { it.seasonId },
        seriesIds = userSpaceViewModel.tvSpaceSeries.map { it.seriesId }
    )
    if (index < 0) {
        pendingFocusKey = null
        return@LaunchedEffect
    }
    delay(50)
    runCatching { lazyGridState.scrollToItem(index) }
    repeat(20) {
        if (runCatching { restoreFocusRequester.requestFocus() }.isSuccess) {
            pendingFocusKey = null
            return@LaunchedEffect
        }
        delay(50)
    }
    pendingFocusKey = null
}
```

打开详情时（两个 `onClick`）先 `openedArchiveKey = "season-${collection.seasonId}"` / `"series-${series.seriesId}"`，再 `openCollection` / `openSeries`。

- [ ] **Step 2: 三层返回**

```kotlin
BackHandler(enabled = selectedTab == UserSpaceTab.Collections && selectedArchive) {
    closeArchiveAndRestoreFocus()
}
```

grid 增加（注意排除详情态，否则 `BackHandler` 收不到事件）：

```kotlin
.onPreviewKeyEvent {
    if (it.key == Key.Back && !selectedArchive) {
        if (it.type == KeyEventType.KeyUp) tabFocusRequester.requestFocus()
        true
    } else false
}
```

- [ ] **Step 3: 列表与详情的卡片接上焦点 requester**

- 合集列表两个 `itemsIndexed` 的 `Surface` modifier 加：`if ("season-${collection.seasonId}" == pendingFocusKey) Modifier.focusRequester(restoreFocusRequester) else Modifier`（系列同理用 `"series-${series.seriesId}"`）。
- 详情头部的「返回合集和系列」按钮改为调用 `closeArchiveAndRestoreFocus()`（它会自己按 `openedArchiveKey` 设好 `pendingFocusKey`）。详情头部**不要**再加 `onBackPressed`：详情态由 `BackHandler` 统一处理，额外的按键拦截会把第一层劫持成第二层。

- [ ] **Step 4: 收藏按钮与对话框**

详情头部 `Row` 内追加（`selectedCollection != null && Prefs.isLogin` 时；需要 import `planCollectionFavoriteClick` / `CollectionFavoriteAction`（`dev.aaa1115910.bv.viewmodel.user`）、`FavoriteFolderDialog`（`dev.aaa1115910.bv.tv.component`）、`Icons.Rounded.Favorite` / `FavoriteBorder`）：

```kotlin
val favoriteIds = userSpaceViewModel.collectionFavoriteFolderIds
val isFavorited = favoriteIds.isNotEmpty()
Surface(
    onClick = {
        when (planCollectionFavoriteClick(isFavorited, userSpaceViewModel.collectionFavoriteFolders.size)) {
            CollectionFavoriteAction.AddToDefaultFolder ->
                userSpaceViewModel.addCollectionToDefaultFavoriteFolder()
            CollectionFavoriteAction.OpenDialog -> showCollectionFavoriteDialog = true
        }
    }
) {
    Row(
        modifier = Modifier.padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(
            modifier = Modifier.size(20.dp),
            imageVector = if (isFavorited) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
            contentDescription = null,
            tint = if (isFavorited) Color(0xfffb7299) else MaterialTheme.colorScheme.onSurface
        )
        Text(if (isFavorited) "已收藏" else "收藏")
    }
}
```

页面末尾：

```kotlin
FavoriteFolderDialog(
    show = showCollectionFavoriteDialog,
    onDismiss = { showCollectionFavoriteDialog = false },
    userFavoriteFolders = userSpaceViewModel.collectionFavoriteFolders,
    favoriteFolderIds = userSpaceViewModel.collectionFavoriteFolderIds,
    onUpdateFavoriteFolders = { userSpaceViewModel.updateCollectionFavoriteFolders(it) }
)
```

（`FavoriteFolderDialog` 已在 `dev.aaa1115910.bv.tv.component`，直接 import。）

- [ ] **Step 5: 编译并跑 app/tv 单测**

Run: `.\gradlew.bat :app:tv:compileDebugKotlin :app:tv:testDebugUnitTest`
Expected: BUILD SUCCESSFUL，`GridFocusTest` / `UpSpaceArchiveNavigationTest` 全绿

- [ ] **Step 6: 提交**

```bash
git add app/tv/src/main/kotlin/dev/aaa1115910/bv/tv/screens/user/UpInfoScreen.kt
git commit -m "feat(tv): three-level back with focus restore and collection favoriting"
```

---

### Task 13: 文档与整体验证

**Files:**
- Modify: `docs/CONTEXT.md`

**Interfaces:**
- Consumes: 全部前序任务
- Produces: nothing

- [ ] **Step 1: 同步术语**

在 `docs/CONTEXT.md` 的「内容类型」段落追加：

```markdown
**动态视频（Dynamic Video）**：
以动态形式发布的视频，播放入口与普通投稿一致，动态卡片上标注「动态视频」。
_Avoid_: 动态稿件

**联合创作（Collab Video）**：
由多个 UP 主联合投稿的视频，动态卡片上标注「联合创作」。
_Avoid_: 联合投稿
```

- [ ] **Step 2: 全量编译**

Run: `.\gradlew.bat :bili-api:compileKotlin :app:shared:compileDebugKotlin :app:tv:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 单测**

Run: `.\gradlew.bat :app:shared:testDebugUnitTest :app:tv:testDebugUnitTest`
Expected: 全绿（含本次新增的 4 个测试类）

- [ ] **Step 4: 真机走一遍 spec 的验收清单**

按 [spec「验收」](../specs/2026-11-10-tv-up-space-redesign-design.md) 的 7 条逐项确认，重点是可自动化不了的三条：三级返回焦点、合集收藏状态、真实「动态视频/联合创作」卡片的角标文案。

- [ ] **Step 5: 提交**

```bash
git add docs/CONTEXT.md
git commit -m "docs: record dynamic video and collab video terms"
```
