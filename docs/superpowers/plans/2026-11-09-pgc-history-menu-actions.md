# PGC 历史记录菜单操作实施计划

> **致自动化执行者：** 必须使用子技能：`superpowers:subagent-driven-development`（推荐）或 `superpowers:executing-plans`，按任务逐步实施本计划。使用复选框（`- [ ]`）跟踪进度。

**目标：** 对 PGC 卡片只在 TV 操作菜单中隐藏“UP 主页”，保留“详情”，并让“详情”像点击卡片一样打开 PGC 季详情页。

**架构：** 为 `VideoActionMenu` 增加“UP 主页”条目的可见性开关，以及“详情”的可选卡片点击回调。`HistoryScreen` 对 PGC 历史记录禁用“UP 主页”，并为“详情”传入卡片点击导航；已经知道自己点击目标的卡片列表也照此处理。

**技术栈：** Kotlin、Jetpack Compose（通过编译与现有 `:app:shared` 单元测试验证）。

**设计文档：** [2026-11-09-pgc-history-menu-actions-design.md](../specs/2026-11-09-pgc-history-menu-actions-design.md)

## 全局约束

- 仅对业务类型为 `"pgc"` 的选中历史记录隐藏“UP 主页”。
- 保留“详情”；对 PGC 而言它必须打开季详情页（与点击卡片一致）。
- 保持现有菜单资源类型不变，其余操作均不变。
- 不要改变其他调用点的菜单行为或资源类型。

## 审查重点

- **PGC 历史项：** `"pgc"` 只能隐藏“UP 主页”；实现方式是在调用点写 `showUpInfoAction = menuHistoryBusiness != "pgc"`。
- **番剧或未知历史项：** 非 PGC 取值（包括 null）必须保留“UP 主页”；同一条比较即可保持其可见。
- **“详情”目标：** PGC 卡片必须打开季详情，UGC 卡片打开视频详情；各页面把“详情”映射到自己的卡片点击导航。
- **现有菜单调用点：** 未传 `onDetailClick` 的页面必须保留旧的 `VideoInfoActivity` 行为。

---

### 任务 1：对 PGC 历史记录只隐藏“UP 主页”

**涉及文件：**
- 修改：`app/tv/src/main/kotlin/dev/aaa1115910/bv/tv/component/VideoActionMenu.kt`
- 修改：`app/tv/src/main/kotlin/dev/aaa1115910/bv/tv/screens/user/HistoryScreen.kt`

**接口：**
- 消费：`menuHistoryBusiness: String?`。
- 产出：`VideoActionMenu(showUpInfoAction: Boolean = true)`，只控制“UP 主页”条目。

- [x] **步骤 1：加入可见性开关**

  给 `VideoActionMenu` 增加 `showUpInfoAction: Boolean = true`，只用它控制“UP 主页”条目，让“详情”不在该开关控制范围内。

- [x] **步骤 2：在调用点内联传入比较表达式**

  在 `HistoryScreen` 中传入 `showUpInfoAction = menuHistoryBusiness != "pgc"`，`resourceType` 保持默认值，从而保留“详情”。不写辅助函数、不搭测试脚手架，因为该规则在其唯一调用点上只是一次比较。

- [x] **步骤 3：编译 TV 模块**

  运行：`.\gradlew.bat :app:tv:compileDebugKotlin`

  预期：BUILD SUCCESSFUL。

### 任务 2：让“详情”走卡片点击导航

**涉及文件：**
- 修改：`app/tv/src/main/kotlin/dev/aaa1115910/bv/tv/component/VideoActionMenu.kt`
- 修改：`app/tv/src/main/kotlin/dev/aaa1115910/bv/tv/screens/user/HistoryScreen.kt`
- 修改：`app/tv/src/main/kotlin/dev/aaa1115910/bv/tv/screens/user/FavoriteScreen.kt`
- 修改：`app/tv/src/main/kotlin/dev/aaa1115910/bv/tv/component/videocard/VideosRow.kt`
- 修改：`app/tv/src/main/kotlin/dev/aaa1115910/bv/tv/component/videocard/TabbedVideosPanel.kt`

**接口：**
- 消费：各页面现有的卡片点击导航（`jumpToSeason` → 季页面，否则视频页面）。
- 产出：`VideoActionMenu` 新增 `onDetailClick: (() -> Unit)? = null`；“详情”对所有资源类型显示，并在提供该回调时使用它。

- [x] **步骤 1：保存菜单对应的卡片**

  在每个拥有菜单的页面/行（`menuVideo`）中保存已打开的 `VideoCardData`，与长按处理里现有的菜单状态一并设置。

- [x] **步骤 2：把页面现有的点击导航抽成局部 lambda**

  在 `HistoryScreen` 与 `FavoriteScreen` 中，把现有的季/视频跳转从 `SmallVideoCard.onClick` 中移出，改为局部 `openDetail` lambda，并在 `onClick` 与菜单两处调用。

- [x] **步骤 3：接入 `onDetailClick`**

  在 `VideoActionMenu` 中让“详情”脱离仅视频可用的开关，并优先使用 `onDetailClick`。从 `HistoryScreen`、`FavoriteScreen`、`VideosRow`、`TabbedVideosPanel` 传入 `onDetailClick = { menuVideo?.let { ... } }`，在可用处复用其现有导航回调。

- [x] **步骤 4：编译 app 模块并运行聚焦单元测试**

  运行：`.\gradlew.bat :app:tv:compileDebugKotlin :app:shared:compileDebugKotlin :app:mobile:compileDebugKotlin :app:shared:testDebugUnitTest --tests dev.aaa1115910.bv.entity.carddata.VideoCardDataTest`

  预期：BUILD SUCCESSFUL，且历史角标测试仍然通过。

不新增辅助文件或 sealed 类型：导航仍留在本来就持有它的调用点。