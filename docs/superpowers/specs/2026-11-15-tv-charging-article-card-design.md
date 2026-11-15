# TV 图文/动态详情：充电专属提示卡片 + 充电二维码

- 日期：2026-11-15
- 范围：`app/tv`（图文详情 / 动态详情）与 `bili-api` 的 opus 详情解析
- 状态：已确认

## 意图

充电专属（`MAJOR_TYPE_BLOCKED` / `MODULE_TYPE_BLOCKED`）的图文与动态，在**未充电**时服务端只下发一块
锁定面板（图标、标题、提示、按钮文案与跳转地址），没有正文。之前：

- 图文详情（`/x/polymer/web-dynamic/v1/opus/detail`）**根本没解析** `module_blocked`，进去是一块空白；
- 动态详情虽然渲染了 `DynamicBlockedCard`，但不可聚焦、点击没反应。

目标：两处都显示同一张充电提示卡片；按 OK 弹出**充电地址二维码**（TV 上无法完成支付，二维码是唯一可落地的方式）。

## 非目标

- 不做充电支付、不做登录/充电状态刷新。
- 不改 `app/mobile`（移动端图文/动态详情不在本次范围）。
- 不改转发引用里的充电专属内容（仍按 `hint_message` 当正文展示）。

## 约束与既有事实

- 接口下发的充电地址就是 `module_blocked.button.jump_url`（形如 `https://www.bilibili.com/h5/upower/index?...`），
  动态侧 `major.blocked` 与 opus 侧 `module_blocked` **载荷完全同构**。
- **只有未充电时服务端才下发 `blocked`**：有 `blocked` 即视为未充电，无需额外状态判断。
- 两页共用 `ArticleDetailComponents.kt` 的 `DetailShareDialog`（`QrImage`）与
  `itemFocus` / `restoreListFocus` 焦点恢复机制。
- TV 遥控：卡片整张可聚焦可点击，不额外加焦点停留点。

## 方案（已选定）

1. **数据层**：`OpusDetailModule` 增加 `module_blocked`（复用动态侧同构载荷类型）；
   `ArticleViewData` 增加 `blocked: DynamicBlockedModule?`，在 `fromOpusDetail` 中映射。
2. **图文详情**：`blocked != null` 时在 `LazyColumn` 最前插入充电提示卡（与动态详情同一张卡），
   并注册进 `itemFocus`，让左右键整页跳转后焦点能回到卡片。
3. **动态详情**：`DynamicBlockedCard` 改为可聚焦、可点击，同样注册进 `itemFocus`；卡片改横向布局
   （图标在左、标题/提示/CTA 在右，左对齐）。
4. **二维码弹窗**：把 `DetailShareDialog` 的弹窗主体抽成带 `title` 的公共 composable，
   新增 `ChargeQrCodeDialog`；二维码内容取 `jump_url`，地址为空时 toast「暂无充电地址」而不弹空码。
   返回键关闭，与分享弹窗一致。弹窗宽度上限 320dp（二维码 220dp），链接文本 11sp / 行高 14sp、最多 3 行。
5. **测试**：`ArticleViewOpusDetailMappingTest` 补一条充电专属 opus 的映射断言。

## 备选与否决

| 方案 | 否决原因 |
| --- | --- |
| 充电地址用 `https://space.bilibili.com/{mid}/?tab=charge` 自行拼接 | 与接口给定地址可能不一致（`businessId`/档位参数不同），且 `jump_url` 本来就有 |
| 直接 `ACTION_VIEW` 打开充电 H5 | TV 上没有可用浏览器/无法支付，用户拿不到可扫码的地址 |
| 只在图文详情做、动态详情保持现状 | 两处是同一类卡片，交互不一致 |

## 留给实现者

- 卡片文案：标题为空时沿用现有兜底（`充电专属动态`）；按钮文案有则拼进 CTA 行。
- 图标沿用 `blocked.iconUrl`（dark 优先、day 兜底），不额外渲染 `bg_img`。