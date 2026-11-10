# 1. Video action state lazy loading and race protection

**Date**: 2026-07-25

## Context

视频操作状态（点赞/收藏/投币）在 TV 端 3 个 UI 表面显示：卡片长按菜单（`VideoActionMenu`）、详情页（`VideoInfoScreen`）、播放器控制条（`VideoPlayerV3Screen`）。三处通过 `VideoUserActionManager` object 单例 + `StateFlow` 共享状态。

问题：
1. 菜单在卡片列表场景下打开时，尚无 `ViewReply` gRPC 数据，状态为默认 `false`——用户看到的是错误状态。
2. 详情页拿到详情数据后会二次填充状态，如果用户先在菜单中操作了状态，这次填充会覆盖用户的操作结果（竞态）。
3. 未登录时不应显示操作按钮。

## Decision

### 1. Lazy loading via check APIs

新增 `ensureStateLoaded(aid, uid)` 方法，通过 HTTP check API（`checkVideoLike` / `checkVideoCoin`）以及带 `rid` 的收藏夹列表查询（`getAllFavoriteFolderMetadataList`）惰性加载状态。收藏状态与收藏夹归属都取自收藏夹列表响应中每个收藏夹的 `favState`，因此不再单独请求 `checkVideoFavoured`。第一条消费方触发，后续消费方读已缓存的 `StateFlow`。

与之对应，`VideoDetailRepository.getVideoDetail` 不再查询点赞/收藏/投币状态（移除了 `withUserActions` 参数与 `userActionsCache`），`VideoDetail.userActions` 字段及 `UserActions` 类型也一并删除：这些状态只由 `VideoUserActionManager` 持有，gRPC 的 `ViewReply.reqUser` 不再映射到详情实体。

### 2. Single entry point + LoadedKeys race protection

状态填充只有 `ensureStateLoaded` 一条路径：菜单打开（`VideoActionMenu` 的 `LaunchedEffect`）、详情加载完成（`VideoInfoScreen`）与播放器打开（`VideoPlayerV3Screen`，此前只有前两处，从剧集页直接起播时状态会停留在默认值）都调用它。播放器按 `fromSeason` 传 `FavoriteResourceType.Pgc`，其余传 `Video`。

去重由 `loadedKeys` + 每键 `Mutex` 双重保证：详情页 `onVideoDetailLoaded` 会先 `await` 本次加载再起播，因此「打开详情页后立即跳播放器」的常见路径中，播放器的调用必然命中 `loadedKeys` 直接返回，不会产生第二组请求；两处并发时后者会在同一把锁上等待而不是重复发请求。

`loadedKeys: MutableSet<Pair<uid, aid>>` 负责去重与竞态保护：命中即返回，因此先加载的状态（含用户已在菜单中做过的新操作）不会被详情页的二次调用覆盖。只有装载成功的键才会被标记，失败时可重试。

### 3. Login snapshot

菜单打开时对 `Prefs.isLogin` 取快照（`remember { Prefs.isLogin }`），不响应运行时登出。未登录时隐藏点赞/收藏/投币/稍后再看按钮及其关联的收藏夹对话框。

## Consequences

- TV 端三处 UI 共享同一份 `StateFlow`，每个 `(uid, aid)` 只请求一次。
- 竞态消除：先加载的一方标记 `loadedKeys`，后加载的直接跳过。
- 对 API 失败有容错：失败时不标记 `loadedKeys`，后续调用可重试。
- 登录快照确保菜单打开期间体验一致。
- 成本：每次首次加载状态需要 3 次 HTTP 请求（点赞状态、投币状态、收藏夹列表），并且只在首次（未命中 `loadedKeys`）发生。详情请求本身不再附带点赞/收藏/投币查询，Web 路径反而少 3 次请求。
