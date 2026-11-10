# 历史卡片角标实施计划

> **致自动化执行者：** 必须使用子技能：`superpowers:subagent-driven-development`（推荐）或 `superpowers:executing-plans`，按任务逐步实施本计划。使用复选框（`- [ ]`）跟踪进度。

**目标：** 把每条历史记录中非空的 API 角标显示在其卡片封面右上角。

**架构：** 让 HTTP 的 `badge` 穿过归一化后的 `bili-api` 历史实体，再映射到 `VideoCardData` 专用的 `historyBadgeText` 属性并加入 `coverBadges`。`SmallVideoCard` 已经在所需位置渲染该列表。

**技术栈：** Kotlin、Jetpack Compose、`kotlin.test`（配合 JUnit4）。

**设计文档：** [2026-11-09-history-card-badge-design.md](../specs/2026-11-09-history-card-badge-design.md)

## 全局约束

- 历史角标文本与现有的充电 `badgeText` 保持分开。
- 映射到归一化历史实体时保留 HTTP 的角标；gRPC 历史项默认使用空角标。
- 空的历史角标文本不得渲染。
- 复用现有的 `coverBadges` 渲染；不改变卡片布局或 API 模型。
- `historyBusiness` 为 null 的未知历史项仍必须显示其角标。

## 审查重点

- **未知历史项：** `historyBusiness` 为 null 不得抑制角标；用 `historyBadgeAppearsWithoutHistoryBusiness` 覆盖。
- **空角标：** 空白的 `historyBadgeText` 不得生成空角标；用 `blankHistoryBadgeIsOmitted` 覆盖。
- **充电卡片：** 现有充电角标的格式不得改变；用 `chargingBadgeBehaviorIsUnchanged` 覆盖。
- **PGC 卡片：** 现有 PGC 角标必须保留；用 `pgcBadgeBehaviorIsUnchanged` 覆盖。
- **HTTP 映射：** 服务端角标必须在转换为归一化历史实体后仍然存在；用 `httpHistoryBadgeIsPreservedByMapping` 覆盖。

---

### 任务 1：在归一化 API 实体中保留历史角标

**涉及文件：**
- 修改：`bili-api/src/main/kotlin/dev/aaa1115910/biliapi/entity/user/History.kt`
- 新建：`bili-api/src/test/kotlin/dev/aaa1115910/biliapi/entity/user/HistoryItemMappingTest.kt`

**接口：**
- 消费：`dev.aaa1115910.biliapi.http.entity.history.HistoryItem.badge: String`。
- 产出：`dev.aaa1115910.biliapi.entity.user.HistoryItem.badge: String = ""`；HTTP 映射复制该角标，gRPC 映射使用空默认值。

- [x] **步骤 1：新增失败的 HTTP 到归一化映射测试**

  新增 `httpHistoryBadgeIsPreservedByMapping`：构造一个完整的 HTTP 历史项并设 `badge = "互动视频"`，断言映射后的归一化项 `badge` 为 `"互动视频"`。

- [x] **步骤 2：运行聚焦测试并确认失败**

  运行：`.\gradlew.bat :bili-api:test --tests dev.aaa1115910.biliapi.entity.user.HistoryItemMappingTest`

  预期：失败（FAIL），因为归一化历史项尚未暴露 `badge`。

- [x] **步骤 3：在归一化实体中保留角标文本**

  在归一化的 `HistoryItem` 中新增 `badge: String = ""`，并在 HTTP 转换时赋值为 `item.badge`。gRPC 转换保持默认值。

- [x] **步骤 4：运行聚焦测试**

  运行：`.\gradlew.bat :bili-api:test --tests dev.aaa1115910.biliapi.entity.user.HistoryItemMappingTest`

  预期：通过（PASS）。

### 任务 2：把历史角标数据加入视频卡片

**涉及文件：**
- 修改：`app/shared/src/main/kotlin/dev/aaa1115910/bv/entity/carddata/VideoCardData.kt`
- 修改：`app/shared/src/main/kotlin/dev/aaa1115910/bv/viewmodel/user/HistoryViewModel.kt`
- 新建：`app/shared/src/test/kotlin/dev/aaa1115910/bv/entity/carddata/VideoCardDataTest.kt`

**接口：**
- 消费：任务 1 归一化后的 `HistoryItem.badge: String`，以及现有的 `VideoCardData.coverBadges: List<String>`。
- 产出：`VideoCardData.historyBadgeText: String = ""`，非空时追加到 `coverBadges`；`HistoryViewModel` 用 `historyItem.badge` 填充它。

- [x] **步骤 1：新增失败的 `VideoCardDataTest` 用例**

  新增测试 `historyBadgeAppearsInCoverBadges`、`historyBadgeAppearsWithoutHistoryBusiness`、`blankHistoryBadgeIsOmitted`、`chargingBadgeBehaviorIsUnchanged`、`pgcBadgeBehaviorIsUnchanged`。断言：历史角标恰好出现一次（包括 `historyBusiness` 为 null 时）；空白历史角标文本被省略；充电角标保留其 `⚡` 格式；PGC 类型角标仍然存在。

- [x] **步骤 2：运行聚焦测试并确认失败**

  运行：`.\gradlew.bat :app:shared:testDebugUnitTest --tests dev.aaa1115910.bv.entity.carddata.VideoCardDataTest`

  预期：失败（FAIL），因为 `VideoCardData` 尚未暴露历史专用角标属性。

- [x] **步骤 3：实现并映射历史角标**

  在 `VideoCardData` 中新增 `historyBadgeText: String = ""`，仅把非空值追加到 `coverBadges`，并更新 `HistoryViewModel`：传入 `historyBadgeText = historyItem.badge`，而不是把历史值赋给充电用的 `badgeText`。

- [x] **步骤 4：运行聚焦测试**

  运行：`.\gradlew.bat :app:shared:testDebugUnitTest --tests dev.aaa1115910.bv.entity.carddata.VideoCardDataTest`

  预期：五个测试全部通过（PASS）。