# 1. Video action state lazy loading and race protection

**Date**: 2026-07-25

## Context

视频操作状态（点赞/收藏/投币）在 TV 端 3 个 UI 表面显示：卡片长按菜单（`VideoActionMenu`）、详情页（`VideoInfoScreen`）、播放器控制条（`VideoPlayerV3Screen`）。三处通过 `VideoUserActionManager` object 单例 + `StateFlow` 共享状态。

问题：
1. 菜单在卡片列表场景下打开时，尚无 `ViewReply` gRPC 数据，状态为默认 `false`——用户看到的是错误状态。
2. 详情页通过 `updateFromLoadedData`（取自 gRPC `ViewReply.userActions`）填充状态，但如果用户先在菜单中操作了状态，`updateFromLoadedData` 的后续调用会覆盖用户的操作结果（竞态）。
3. 未登录时不应显示操作按钮。

## Decision

### 1. Lazy loading via check APIs

新增 `ensureStateLoaded(aid, uid)` 方法，通过独立的 HTTP check API（`checkVideoLike` / `checkVideoCoin` / `checkVideoFavoured`）惰性加载状态。第一条消费方触发，后续消费方读已缓存的 `StateFlow`。

### 2. LoadedKeys race protection

引入 `loadedKeys: MutableSet<Pair<uid, aid>>`。两条状态填充路径互斥：

| 路径 | 触发时机 | loadedKeys 检查 |
|---|---|---|
| `ensureStateLoaded` | 惰性加载（菜单 LaunchedEffect） | 跳过已加载的键；成功后才标记 |
| `updateFromLoadedData` | 详情页 gRPC 数据到达 | 如已标记则跳过（不覆盖用户操作） |

只有装载成功的键会被标记，失败时可重试。

### 3. Login snapshot

菜单打开时对 `Prefs.isLogin` 取快照（`remember { Prefs.isLogin }`），不响应运行时登出。未登录时隐藏点赞/收藏/投币/稍后再看按钮及其关联的收藏夹对话框。

## Consequences

- TV 端三处 UI 共享同一份 `StateFlow`，只需一次 API 请求。
- 竞态消除：先加载的路径标记 `loadedKeys`，后加载的直接跳过。
- 菜单打开时对 API 失败有容错：失败时不标记 `loadedKeys`，后续 `updateFromLoadedData` 可正常填充。
- 登录快照确保菜单打开期间体验一致。
- 成本：菜单首次打开时多 3 次 HTTP check 请求（可并行但不在此次实现中优化，影响极微）。
