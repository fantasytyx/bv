# PGC 播放地址（Web）：改用 `/ogv/player/playview`

**日期**：2026-10-05
**状态**：已实现（请求形态与响应解析已实网验证；真机播放验收待做）

## 背景

`VideoPlayRepository.getPgcPlayData(preferApiType = Web)` 原有两个分支：

- 直连：`BiliHttpApi.getPgcVideoPlayUrlV2` → `GET /pgc/player/web/v2/playurl`
- 代理：`BiliHttpProxyApi.getPgcVideoPlayUrlV2` → 同上路径，打到用户自建代理

`GET /ogv/player/playview` 是官方 web 播放器当前的 PGC 播放地址接口，本次把**直连分支**换到它。

驱动因素（均为实测，非推测）：

1. **旧端点不下发响度元数据、新端点下发**。新端点响应的 `data.video_info.volume` 带 `multi_scene_args`，三挡完整可用，切过来后 PGC 的 Web 线路就能参与音量均衡。
   > 注：`docs/specs/2026-10-02-volume-balance-metadata-driven-design.md` 曾假设 PGC v2 有 `volume`（位于 `data.video_info.volume`）；2026-10-03 的探针实测该端点并不下发，两处记录矛盾，以实测为准。
2. 新端点响应是**纯 snake_case**，字段比旧端点更少，且 `video_info` 按 `type` 分成两种结构——迁移的绝大部分工作量在解析层。

## 目标

- Web 直连的 PGC 播放地址改走 `/ogv/player/playview`，请求参数与鉴权语义尽量与旧调用对齐
- 对外行为不变：`getPgcPlayData` 的返回值语义、`PlayData` 各字段含义不变
- 保住 `PlayData.clipInfoList`（跳过片头/片尾）
- 顺带让 PGC 的 Web 直连线路拿到 `volume`

**非目标**：

- **不改代理分支**：代理服务是否支持新路径未知，保持 `BiliHttpProxyApi` 原样（代价：代理线路仍无 `volume`）
- **不删旧接口**：`getPgcVideoPlayUrlV2` 仍被 `getSeasonIdByAvid` / `getAidCidByEpid` 使用
- **不接 `video_info.durls`**：见「关键决策 5」
- 不改 `App(gRPC)` 分支

## 现状调研

### 请求形态

固定参数：`biz_type=ogv`、`scene=normal`、`version_name=4.10.2`、`app_id=100`。
业务参数：`ep_id` 必填，`season_id` 可选；`qn` / `fnval` / `fnver` 沿用旧调用的取值（`qn=127`、`fnval=4048`、`fnver=0`）。

实测矩阵（2026-10-05，未登录）：

| 组合 | 结果 |
|---|---|
| `ep_id` + `buvid3` cookie + wbi 签名 | `code=0` |
| 只给 `ep_id`（不给 `season_id`） | `code=0`，响应回带 `supplement.ogv_season_info.season_id` |
| 有 wbi 但**无 cookie** | `code=-400` |
| 有 cookie 但**无 wbi** | `code=0` |
| POST | `code=-400`（只支持 GET） |

两点结论：

- **`buvid3` cookie 是硬性要求**，缺失直接 `-400`。本项目的 `encApiSign` 会为 web 请求自动补 `buvid3`（并带上 `WebCookieManager` 维护的 `buvid4` / `b_nut` / `bili_ticket`），因此函数内不需要手动塞。
- wbi 不是硬校验，但**仍然加签名**：风控是累计行为，单次探针 `code=0` 不能说明不带签名长期安全。

> 探针本身踩了一个坑：node 连续请求该端点约 10 次后被 `412` 风控页挡住（集成浏览器用真实指纹仍可正常访问），之后只能靠浏览器/用户提供的报文继续核对。

### 响应结构

`BiliResponse<OgvPlayViewData>`，数据在 `data`（不是 `result`）。用到的只有两块：

```
data.video_info    → PlayUrlData 形状（dash / durl / support_formats / volume / ...）
data.video_extra   → { clip_info: [{ clip_type, start, end, toast_text }] }
```

`video_info.type` 决定 `video_info` 的两种结构：

| `type` | 形态 | 关键字段 | 实测样本 |
|---|---|---|---|
| `2` | DASH | 有 `dash`（video/audio/dolby），无 `durl` | `ep_id=4823121`（season 119017） |
| `3` | 非 DASH（分段 MP4） | 无 `dash`，只有 `durl` / `durls`；`format="mp4"`、`is_preview=1`、`data.play_video_type="preview"` | `ep_id=6084344`（season 307572） |

`type=3` 是会员专享内容的试看形态：`durl` 是服务端选中的那一档，`durls` 是按清晰度分组的多个分段列表。

### 与现有模型的差异

旧端点是 **snake_case + camelCase 双份**（`base_url` 与 `baseUrl` 同时存在），现有模型是照着它写的；新端点只有 snake_case，于是有 6 处必须放宽：

| 位置 | 旧端点 | 新端点 | 处理 |
|---|---|---|---|
| `video_info.type` | 字符串 `"DASH"` | 数字 `2` / `3` | 新增 `LenientStringSerializer`，两种都读成字符串 |
| `dash.min_buffer_time` | 另有 `minBufferTime` 双份 | 只有 `min_buffer_time` | 补 `@SerialName` + 默认值 |
| `dash.audio[*]` | 带 `width`/`height`/`frame_rate`/`sar`/`start_with_sap`/`codecid`（全为 0 / 空串） | **完全不返回** | 全部给默认值 |
| `support_formats[*].superscript` | 每档都有（空串） | 仅部分档位有 | 默认值 `""` |
| `durl[*].ahead` / `vhead` | 有（空串） | **不返回** | 默认值 `""` |
| 跳过片头/片尾 | `video_info.clip_info_list`，`clip_type` 为字符串 `"CLIP_TYPE_OP"` | `data.video_extra.clip_info`，`clip_type` 为**数字** | 新增 `OgvVideoExtra` / `OgvClipInfo` 并映射 |

其余字段（`durl` 的 `order`/`length`/`size`/`url`/`backup_url`、`support_formats` 的 `quality`/`description`/`display_desc`/`codecs`/`need_login`/`need_vip`、`volume`、`record_info` 等）与 `PlayUrlData` 兼容，可以直接复用。

## 关键决策

### 1. 复用 `PlayUrlData`，不新造一套 DTO

字段形状几乎一致，复用即可共用 `PlayData.fromPlayUrlData` 这条映射，改动集中在「放宽 6 处」。

放宽方式全部**向后兼容**：给默认值 / 补 `@SerialName`（`backup_url` 也是这次补的——它原本靠旧的 camelCase 双份才碰巧能解析，没有 `@SerialName` 时新端点的 `backup_url` 会**静默变成空列表**）。旧端点与两个代理 DTO 的行为不受影响。

代价是共享模型带上若干「为兼容而生」的默认值，用 `OgvPlayViewDataTest` 把它们固化成回归用例。

### 2. `season_id` 可选，由上层透传

既然只给 `ep_id` 也能解析，就不额外请求 season 详情接口去凑 `season_id`。改为把 `VideoPlayerV3ViewModel.seasonId`（> 0 时）透传下来；拿不到就不传。

`getPgcPlayData` 因此新增 `seasonId: Int? = null`（有默认值，调用方与测试不受影响）。

### 3. wbi 走 `encApiSign` 的统一路径

把 `/ogv/player/playview` 加进 `ApiSign.encApiSign` 的 `isWbiRequest`，而不在函数里手写签名。这样自动获得两件事：重试时移除上一次的 `wts`/`w_rid`、以及指纹 cookie 的自动补齐（缺 `buvid3` 会 `-400`，所以这一条是必需的）。

### 4. 跳过片头/片尾改从 `video_extra.clip_info` 取

数字 → 枚举的映射与 `bilibili.pgc.gateway.player.v2.ClipType` proto 的定义逐字一致（1=OP、2=ED、3=HE、4=MULTI_VIEW、5=AD），因此不是猜的。

映射放在 `PlayData.fromOgvPlayViewData`，让 `fromPlayUrlData` 保持「只认 `video_info`」的单一职责；`PlayData` 仍是唯一承载映射的实体。

**不映射这条就是回归**：旧端点走 `video_info.clip_info_list`，新端点换了位置与类型，漏了会直接丢掉跳过 OP/ED。

### 5. 不接 `durls`

`durls` 提供多清晰度的试看流，但：

- 旧端点的 `durl` 路径本来就是「只用服务端选中的那一档」，不接 `durls` 才是**行为一致**（不是能力回退）
- 接它必须同时给这些清晰度补 `codec` 映射（否则切档时 `availableVideoCodec` 取不到编码），而 `type=3` 的 `support_formats[].codecs` 是「该清晰度支持的编码列表」，与实际 mux 进 MP4 的编码并不一一对应，容易造出错误选项

代价：试看画面只能到服务端默认档（实测为 480P），切不了 1080P。如后续要放开，按上面两条一起改。

### 6. 代理分支保持旧端点

`enableProxy` 是区域限制场景的少数路径，代理服务是否实现新路径未知，冒然切换会把这条兜底路径打断。代价：代理线路仍然没有 `volume`。

## 组件与接口

**`bili-api`**

- `BiliHttpApi.getOgvVideoPlayUrl(epid, seasonId?, qn, fnval, fnver, sessData, gaiaVtoken, tryLook): BiliResponse<OgvPlayViewData>`
  - 命名避开已有的 UGC/PGC-v1 `getPgcVideoPlayUrl`（三者同名会重载歧义）
  - Cookie / `gaia_vtoken` / `checkForVVoucher` 的处理与 `getPgcVideoPlayUrlV2` 对齐
- `http/entity/video/PlayUrlResponse.kt`：新增 `OgvPlayViewData` / `OgvVideoExtra` / `OgvClipInfo` / `LenientStringSerializer`；放宽上一张表里的 6 处
- `entity/PlayData.kt`：新增 `fromOgvPlayViewData()`（`fromPlayUrlData(videoInfo)` + 用 `video_extra.clip_info` 覆盖 `clipInfoList`）
- `http/util/ApiSign.kt`：`isWbiRequest` 增加 `/ogv/player/playview`
- `repositories/VideoPlayRepository.kt`：`getPgcPlayData` 加 `seasonId?`，Web 直连分支改调新接口

**`app/shared`**

- `VideoPlayerV3ViewModel`：`loadPlayUrl` 与 `reloadPlayUrl` 两处调用透传 `seasonId`（> 0 才传）

**未改动**：`BiliHttpProxyApi`、`getPgcVideoPlayUrlV2`、`VideoPlayRepository` 的 App 分支、`PlayData.fromPlayUrlV2Data`。

## 验证

**单测**（`bili-api/src/test/.../entity/OgvPlayViewDataTest.kt`，4 例全绿）

- `type=2` 解析：`type` 数字读成 `"2"`、`min_buffer_time`、音频流缺字段走默认值、`support_formats` 缺 `superscript`
- `type=2` → `PlayData`（走 `fromOgvPlayViewData`）
- `type=3` 解析：无 `dash`、`durl` 缺 `ahead`/`vhead`、`video_extra.clip_info`
- `type=3` → `PlayData`：`needPay=true`、`dashVideos` 取 `durl`、`clipInfoList` 为 OP/ED

**编译**：`:bili-api:compileKotlin`、`:app:shared:compileDebugKotlin`、`:app:tv:compileDebugKotlin` 通过。

**实网**：见「请求形态」实测矩阵。

**未验证**（如实记录）

- 真机播放：`type=2` 正常集与 `type=3` 试看各跑一次
- 仓库实际传的 `qn=127` 与 `tryLook` 下的 `try_look=1`：探测被 `412` 打断，未单独确认（`qn=0`/`qn=80`、`ep_id`-only、带 wbi 均确认 `code=0`）

## 遗留

- 代理分支、App(gRPC) 分支仍无 `volume`（gRPC 的 PGC proto 里根本没有该字段）
- `durls` 未使用 → 试看画质上限为服务端默认档
- `getPgcVideoPlayUrlV2` 仍被 `getSeasonIdByAvid` / `getAidCidByEpid` 使用；若将来这两个 helper 也用新端点替代，旧接口才可能整体下线
