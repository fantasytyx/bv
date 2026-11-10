# PGC 卡片标签与操作实施计划

> **致自动化执行者：** 必须使用子技能：`superpowers:subagent-driven-development`（推荐）或 `superpowers:executing-plans`，按任务逐步实施本计划。使用复选框（`- [ ]`）跟踪进度。

**目标：** 在 TV 端的每个 PGC 收藏卡片上显示服务端下发的类型名，并支持从卡片菜单执行收藏、点赞和投币。

**架构：** 将收藏列表分类（`type=24`）与收藏资源操作（`type=42`）区分开。在归一化模型中保留 `ogv.type_id`、`ogv.type_name` 和 `ogv.season_id`，并将类型名交给现有封面角标 UI 展示。复用当前视频操作菜单与状态流，把收藏资源操作参数化，并对 PGC 收藏卡片隐藏仅适用于 UGC 的导航与操作。追番卡片保持不变。

**技术栈：** Kotlin、Kotlinx Serialization、Jetpack Compose、Gradle

**设计文档：** `docs/superpowers/specs/2026-11-08-pgc-card-action-menu-design.md`

## 全局约束

- 收藏列表的 `type=24` 表示 PGC；操作资源的 `type=42` 表示 PGC 收藏。
- PGC 的 `ogv.type_id` 取值为 `1`、`2`、`3`、`4`、`5`、`7`、`12`；展示标签使用 `ogv.type_name`。
- PGC 的收藏、点赞、投币操作与普通视频操作使用相同的底层接口。
- 追番/已追番季的行为继续沿用现有 PGC 追番接口，本次不改动。
- 保持普通 UGC 卡片的菜单行为不变。
- PGC 收藏卡片菜单只暴露收藏、点赞、投币；不暴露 UGC 的 UP 主主页/详情导航，也不暴露稍后再看。
- 本次改动不给 PGC 详情页增加操作控件。
- 不要创建提交（commit）；所有改动留在工作区。

## 审查重点

- 对每个受支持的 `ogv.type_id`，PGC 收藏都应原样保留并展示 `ogv.type_name`；在任务 1 中测试。
- 季信息缺失或不可用的 PGC 收藏不得生成畸形的季导航卡片；在任务 1 中测试。
- 收藏列表类型 `24` 绝不能当作 PGC 收藏操作类型发送；在任务 2 中断言枚举值并核对仓库调用路径。
- PGC 操作状态使用由 BVID 推导出的规范 AID，因此状态以 `(uid, aid)` 为键；收藏资源类型只在 API 请求中显式传递。
- PGC 操作菜单隐藏仅适用于 UGC 的条目，而普通 UGC 菜单保持不变；在任务 3 中验证并编译 TV 模块。

---

### 任务 1：保留 PGC 元数据并展示类型标签

**涉及文件：**
- 修改：`bili-api/src/main/kotlin/dev/aaa1115910/biliapi/entity/Favorite.kt`
- 修改：`bili-api/src/main/kotlin/dev/aaa1115910/biliapi/http/entity/user/favorite/FavoriteItem.kt`
- 修改：`app/shared/src/main/kotlin/dev/aaa1115910/bv/entity/carddata/VideoCardData.kt`
- 修改：`app/shared/src/main/kotlin/dev/aaa1115910/bv/entity/carddata/FavoriteItemCardData.kt`
- 测试：`bili-api/src/test/kotlin/dev/aaa1115910/biliapi/entity/FavoriteItemMappingTest.kt`
- 测试：`app/shared/src/test/kotlin/dev/aaa1115910/bv/entity/carddata/FavoriteItemCardDataTest.kt`

**接口：**
- 产出 `FavoriteItemType.Pgc(24)`、`FavoriteItemOgv(typeId: Int, typeName: String, seasonId: Int?)` 和 `VideoCardData.pgcTypeName: String?`。
- `VideoCardData.coverBadges` 包含非空的 `pgcTypeName`，从而由现有右上角封面角标渲染器展示它。

- [ ] **步骤 1：新增失败的 API 映射用例**

  扩展收藏 JSON fixture 测试，验证 `type=24` 映射为 `FavoriteItemType.Pgc`，并保留 `ogv.type_id`、`ogv.type_name` 和 `ogv.season_id`。为每个受支持的 `type_id`（`1`、`2`、`3`、`4`、`5`、`7`、`12`）各加一个表驱动用例，各自带上字面量类型名。

- [ ] **步骤 2：运行聚焦的 API 测试并确认失败**

  运行：`.\gradlew.bat :bili-api:test --tests "dev.aaa1115910.biliapi.entity.FavoriteItemMappingTest"`

  预期：失败（FAIL），因为归一化模型尚未暴露 PGC 枚举名与完整的 OGV 元数据。

- [ ] **步骤 3：实现归一化的 PGC 元数据**

  采用 PGC 通用的列表项枚举名 `Pgc(24)`，在 HTTP 模型中注明 `type=24` 表示 PGC，并在从 HTTP DTO 转换时保留这三个 OGV 值。HTTP 侧的 OGV 保持可空，使畸形或旧版载荷仍可解码，并交由现有可空映射路径处理。

- [ ] **步骤 4：新增失败的卡片标签断言**

  扩展 `FavoriteItemCardDataTest`，断言 PGC 收藏会把服务端下发的 `type_name` 映射到 `VideoCardData.pgcTypeName` 和 `coverBadges`，而普通视频没有 PGC 标签。保留季信息缺失时的映射断言。

- [ ] **步骤 5：运行聚焦的 shared 测试并确认失败**

  运行：`.\gradlew.bat :app:shared:testDebugUnitTest --tests "dev.aaa1115910.bv.entity.carddata.FavoriteItemCardDataTest"`

  预期：失败（FAIL），因为 `VideoCardData` 尚未暴露 PGC 标签。

- [ ] **步骤 6：实现并验证卡片标签**

  在 `VideoCardData` 中新增 `pgcTypeName`，仅在非空时追加到 `coverBadges`。现有 `SmallVideoCard`/`CardCover` 已在 `Alignment.TopEnd` 渲染角标；不要再添加一套角标 UI。

  运行：

  ```powershell
  .\gradlew.bat :bili-api:test --tests "dev.aaa1115910.biliapi.entity.FavoriteItemMappingTest"
  .\gradlew.bat :app:shared:testDebugUnitTest --tests "dev.aaa1115910.bv.entity.carddata.FavoriteItemCardDataTest"
  ```

  预期：两个聚焦测试任务均通过。

### 任务 2：把收藏项类型与收藏操作资源类型分开

**涉及文件：**
- 修改：`bili-api/src/main/kotlin/dev/aaa1115910/biliapi/entity/Favorite.kt`
- 修改：`bili-api/src/main/kotlin/dev/aaa1115910/biliapi/repositories/FavoriteRepository.kt`
- 修改：`app/tv/src/main/kotlin/dev/aaa1115910/bv/tv/manager/VideoUserActionManager.kt`
- 测试：`bili-api/src/test/kotlin/dev/aaa1115910/biliapi/entity/FavoriteItemMappingTest.kt`

**接口：**
- 产出 `FavoriteResourceType.Video(2)` 和 `FavoriteResourceType.Pgc(42)`。
- 收藏仓库的添加/移除/更新与收藏夹查询接受 `FavoriteResourceType`，默认为 `Video`。现有收藏状态接口仍基于 ID，保持不变。
- `VideoUserActionManager` 以 `(uid, aid)` 作为操作状态的键，仅把 `FavoriteResourceType` 传给收藏夹 API 请求；点赞/投币仓库调用保持不变。

- [ ] **步骤 1：为不同的收藏项类型与操作类型值新增测试**

  断言 `FavoriteItemType.Pgc.value == 24`、`FavoriteResourceType.Pgc.value == 42`、`FavoriteResourceType.Video.value == 2`。保留 JSON fixture 中 PGC 列表项解码为项类型 `24` 的断言。

- [ ] **步骤 2：运行聚焦的 API 测试并确认新断言失败**

  运行：`.\gradlew.bat :bili-api:test --tests "dev.aaa1115910.biliapi.entity.FavoriteItemMappingTest"`

  预期：失败（FAIL），因为操作资源类型尚不存在。

- [ ] **步骤 3：把仓库收藏操作参数化**

  为添加、删除、更新以及收藏夹元数据查询增加带默认值的 `resourceType: FavoriteResourceType = FavoriteResourceType.Video`。把 `resourceType.value` 传给现有 HTTP 操作。`checkVideoFavoured(aid)` 仍使用现有接口，并用带资源类型的收藏夹查询（`type` 加 `rid`）确定所选内容所在的收藏夹。

- [ ] **步骤 4：以规范化 AID 作为操作状态的键**

  使用由 PGC 收藏的 BVID 推导出的 AID 作为其规范的卡片/操作 ID，同时保留列表项自带的剧集 ID 用于季导航。操作状态以 `(uid, aid)` 为键，收藏夹缓存以 `uid` 为键；收藏夹查询与变更请求保留 `FavoriteResourceType`，使 PGC 使用 `42`、Video 使用 `2`。

- [ ] **步骤 5：运行聚焦的 API 测试并编译依赖模块**

  运行：

  ```powershell
  .\gradlew.bat :bili-api:test --tests "dev.aaa1115910.biliapi.entity.FavoriteItemMappingTest"
  .\gradlew.bat :bili-api:compileKotlin :app:shared:compileDebugKotlin :app:tv:compileDebugKotlin
  ```

  预期：测试通过，且所有依赖模块在普通调用方使用默认资源类型的情况下编译成功。

### 任务 3：让 PGC 卡片复用操作菜单

**涉及文件：**
- 修改：`app/tv/src/main/kotlin/dev/aaa1115910/bv/tv/component/VideoActionMenu.kt`
- 修改：`app/tv/src/main/kotlin/dev/aaa1115910/bv/tv/component/videocard/VideosRow.kt`
- 修改：`app/tv/src/main/kotlin/dev/aaa1115910/bv/tv/screens/user/FavoriteScreen.kt`
- 修改：`app/tv/src/main/kotlin/dev/aaa1115910/bv/tv/manager/VideoUserActionManager.kt`

**接口：**
- `VideoActionMenu` 接受收藏资源类型，默认为 `Video`。
- PGC 收藏卡片调用与 UGC 卡片相同的菜单状态/操作，但只渲染收藏、点赞、投币；收藏变更使用 `FavoriteResourceType.Pgc`。
- 普通 UGC 卡片保留现有的完整菜单与 `FavoriteResourceType.Video`。

- [ ] **步骤 1：定义菜单的 PGC/UGC 操作契约**

  用 `FavoriteResourceType` 选择菜单模式。PGC 只显示收藏、点赞、投币（登录时）；UGC 保留 UP 主主页、详情、点赞、收藏、投币、稍后再看，以及可选的删除。本模块目前没有 TV 单元测试或 Compose 测试；菜单分支通过检查代码并编译来验证，不要为了菜单组合而单独搭建测试基础设施。

- [ ] **步骤 2：把共享菜单参数化并接入 PGC 卡片**

  把所选资源类型传给操作状态加载、收藏夹加载和收藏变更。移除 `VideosRow` 中对 PGC 长按的屏蔽。对 PGC 条目隐藏 UP 主主页、UGC 详情和稍后再看；保留收藏、点赞、投币。保留 `FavoriteScreen` 的批量删除操作；卡片菜单不提供取消收藏（取消收藏通过在收藏弹窗中清空所选收藏夹完成）。收藏列表条目把当前所在收藏夹作为已知收藏成员传入，使 PGC 卡片以“已收藏”状态打开；一旦取消选择当前收藏夹，页面即移除该条目。

- [ ] **步骤 3：验证 PGC 与 UGC 行为**

  运行：

  ```powershell
  .\gradlew.bat :bili-api:test --tests "dev.aaa1115910.biliapi.entity.FavoriteItemMappingTest"
  .\gradlew.bat :app:shared:testDebugUnitTest --tests "dev.aaa1115910.bv.entity.carddata.FavoriteItemCardDataTest"
  .\gradlew.bat :app:tv:compileDebugKotlin
  git diff --check
  ```

  预期：聚焦的 API 与 shared 测试、TV 编译、diff 检查均通过。逐一核对每个 `FavoriteResourceType` 调用点，确认 PGC 的添加/移除/收藏夹查询使用 `42`、普通 UGC 默认使用 `2`，且未创建提交。