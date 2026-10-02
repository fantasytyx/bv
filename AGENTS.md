# AGENTS.md

> 本文件为 AI 编码 Agent 提供项目速览。详细功能/修改清单见 [README.md](README.md)。

## 项目概述

BV（B 站第三方 Android 客户端），fork 自 [aaa1115910/bv](https://github.com/aaa1115910/bv)，适配 Android Mobile 与 Android TV，使用 **Jetpack Compose**。项目在原 fork 基础上做了大量个人化修改（详情见 README），包括首页标签整合、自定义导航排序、UGC/PGC 详情评论、播放器控制条扩展、合集/分P 跳转、互动视频、画面旋转、弹幕引擎重写等。

## 模块结构

- `app/` - 主应用
  - `app/mobile/` - 移动端入口
  - `app/tv/` - TV 端入口
  - `app/shared/` - 共享业务（共用 ViewModel、Repository、Prefs、`VideoPlayerV3ViewModel`）
- `bili-api/` - HTTP + gRPC API
  - `bili-api/grpc/` - Protobuf 生成代码（含 `proto/` 源文件）
- `bili-subtitle/` - 字幕解析库（含单元测试）
- `player/` - 播放器
  - `player/core/` - 播放器核心（基于 Media3 + ffmpegDecoder 解码器）
  - `player/mobile/` - 移动端播放器实现
  - `player/tv/` - TV 端播放器实现
  - `player/shared/` - 共享播放器逻辑（含**自定义弹幕引擎** `danmaku/`）
- `utils/` - 通用工具
- `symbols/` - 共享 Compose/Symbol 资源
- `libs/` - **预编译 AAR**（非 Maven 依赖）：`av1Decoder`、`ffmpegDecoder`（详见 [libs/README.md](libs/README.md)）
  - 实际集成进 player/core 的只有 `ffmpegDecoder`；`av1Decoder` 虽在 `settings.gradle.kts` 登记但当前没有任何模块依赖，**不要**默认它会被使用
  - libVLC 仅有 TV 端的可选运行时下载器（`app/tv/.../LibVLCDownloaderDialog.kt`），并未集成到播放器内核；Gradle 9 要求 include 的模块目录必须存在，因此 `:libs:libVLC` 已从 `settings.gradle.kts` 移除
- `buildSrc/` - Gradle build logic（`AppConfiguration.kt`、`ProtobufConfiguration.kt`）

## 包命名空间约定

所有模块 namespace 以 `AppConfiguration.appId`（`dev.aaa1115910.bv`）为根，按模块添加子包：

| 模块 | namespace |
|---|---|
| `app` | `dev.aaa1115910.bv` |
| `app/mobile` | `dev.aaa1115910.bv.mobile` |
| `app/tv` | `dev.aaa1115910.bv.tv` |
| `app/shared` | `dev.aaa1115910.bv`（与 `app` 同包） |
| `player` | `dev.aaa1115910.bv.player` |
| `player/core` | `dev.aaa1115910.bv.player.core` |
| `player/mobile` | `dev.aaa1115910.bv.player.mobile` |
| `player/tv` | `dev.aaa1115910.bv.player.tv` |
| `player/shared` | `dev.aaa1115910.bv.player.shared` |
| `utils` | `dev.aaa1115910.bv.utils` |

最终 `applicationId` 为 `AppConfiguration.applicationId`（`dev.aaa1115910.bv2`），debug 构建加 `.debug` 后缀，`r8Test` 加 `.r8test` 后缀。

## 构建命令

```sh
# Debug 构建（universal apk，applicationId 会变为 .dev.aaa1115910.bv2.debug）
./gradlew assembleDefaultDebug

# Release 构建（需要根目录 signing.properties）
./gradlew assembleRelease

# Alpha 构建（带 R8 混淆 + 签名）
./gradlew assembleDefaultAlpha

# R8 混淆测试构建
./gradlew assembleDefaultR8Test

# 单元测试
./gradlew testDefaultDebugUnitTest

# 单模块测试
./gradlew :bili-subtitle:test

# Lint
./gradlew lintDefaultDebug
```

## 构建前置

1. **JDK 21**（强制，`AppConfiguration.jdk`）。跑 gradle 前先设好 `JAVA_HOME`，否则报 `Cannot find a Java installation ... matching: {languageVersion=21}`
2. **Android SDK**：`local.properties` 中设置 `sdk.dir`
3. **compileSdk = 37**、**minSdk = 23**、**targetSdk = 36**（见 `AppConfiguration`）
4. **Release/Alpha/R8Test 构建**：根目录需 `signing.properties`（含 `keystore.path`、`keystore.pwd`、`keystore.alias`、`keystore.alias_pwd`）
5. **blacklist.bin**：preBuild 任务从 `blacklistUrl` 自动下载到 `app/shared/src/main/res/raw/`

**单模块快速验证**（只改接口层/播放逻辑时不必打 APK）：

```sh
./gradlew :bili-api:compileKotlin :app:shared:compileDebugKotlin :app:tv:compileDebugKotlin
```

- `bili-api` 是 `kotlin.jvm` 模块，编译任务是 `:bili-api:compileKotlin`（**没有** `compileDebugKotlin` / `assembleDebug` 这类变体任务）
- `app:shared`、`app:tv`、`app:mobile` 是 Android Library，用 `compileDebugKotlin`
- `bili-api` 的单测是**联网集成测试**（如 `BiliHttpApiTest`），需在 `local.properties` 配 `test.sessdata`、`test.uid`、`test.buvid`

## 版本号

在 `buildSrc/.../AppConfiguration.kt` 定义：

- `versionCode` = `git rev-list --count HEAD`（int）
- `versionName` = `major.minor.patch[.hotFix].r{versionCode}.{shortGitHash}`，例：`0.3.0.r1234.abcdef0`
- 修改版本号：改 `AppConfiguration` 的 `major`/`minor`/`patch`/`hotFix`

## 技术栈

- **DI**：Koin 4.2.2（**Compiler Plugin** `io.insert-koin.compiler.plugin`；旧的 `koin-ksp-compiler` 已移除，注解统一用 `org.koin.core.annotation.*`）
- **数据库**：Room（KSP）
- **网络**：Ktor Client + OkHttp
- **序列化**：Kotlinx Serialization
- **播放器**：Media3（已集成 ffmpegDecoder；libVLC/media3Container 库在 `libs/` 但未集成）
- **UI**：Jetpack Compose（Kotlin official 代码风格）
- **K2 Compiler** 已启用（`android.lint.useK2Uast=true`）

## Protobuf

- 源文件在 `bili-api/grpc/proto/`
- `buildSrc/.../ProtobufConfiguration.kt` 的 `usedProtoFiles` 控制**编译时包含**的 proto（未列出自动排除）
- 新增/移除 proto 后需要同步更新 `usedProtoFiles`
- 生成代码随构建自动执行，通常无需手动触发

## 弹幕（Danmaku）

**当前使用自研引擎**（`player/shared/src/main/kotlin/dev/aaa1115910/bv/player/danmaku/`），3 线程架构（Main/ActionThread/CacheThread），Choreographer 帧驱动。

- 历史背景与重构需求：[doc/弹幕/弹幕重构需求.md](doc/弹幕/弹幕重构需求.md)
- 性能优化记录：[doc/弹幕/弹幕库优化.md](doc/弹幕/弹幕库优化.md)
- Code review 报告：[doc/弹幕/弹幕code%20review%20报告.md](doc/弹幕/弹幕code%20review%20报告.md)
- **不要**引用 `akdanmaku` 模块或文件（已从 `settings.gradle.kts` 和各 `build.gradle.kts` 移除，部分注释残留是历史遗迹）
- 集成入口：`app/shared/.../viewmodel/VideoPlayerV3ViewModel.kt`
- 直播弹幕通过 WebSocket 接收（`LiveDataWebSocket`）
- 点播弹幕分片加载（6 分钟/段），15 秒轮询一次
- 当前**不支持发送弹幕**和弹幕评论

## 风控（Geetest / Gaia）

B 站对**未登录 Web 请求**风控最严。改 `bili-api` 的请求形态或极验 UI 前，先看这几条：

- **流程链**：接口返回 `v_voucher`（`code=0` 或 `-352` 且 `data.v_voucher` 非空）→ `POST /x/gaia-vgate/v1/register` 换极验 `gt`/`challenge` → 用户过极验 → `POST /x/gaia-vgate/v1/validate` 换 `grisk_id`（存 `AuthRepository.gaiaVtoken`）→ 带 `gaia_vtoken` / `x-bili-gaia-vtoken` 重试原请求。`v_voucher` 是**一次性**凭证，见 [GeetestChallenge.kt](app/shared/src/main/kotlin/dev/aaa1115910/bv/util/GeetestChallenge.kt)
- **极验 `product: 'bind'` 可能下发点击，也可能下发滑块**。滑块必须发 `DOWN → MOVE → UP`，只发一次点击永远过不了
- **未登录播放**走 `/x/player/wbi/playurl`，并带 `dm_img_list` / `dm_img_str` / `dm_cover_img_str` / `dm_img_inter` 与 `web_location` / `gaia_source` / `isGaiaAvoided`。**不要**给普通游客带 `try_look`——那是"未登录索取受限清晰度"的绕过参数，只在付费视频试看兜底时用；且它只在非 wbi 端点生效
- **指纹要自洽**：`buvid3` 与 `buvid4` 必须来自同一次 `/x/frontend/finger/spi`（`WebCookieManager` 会把服务端签发的 buvid3 回写到 `Prefs.buvid3`）。web 接口要带 `buvid3`，详情接口另加 `Referer: https://www.bilibili.com/`，不要用 `skipAddBuvid3Cookie()` 把指纹清掉
- **风控是累计行为**：单次请求探针（换 cookie 组合、换 UA）通常都返回 `code=0`，不能据此判断"不会触发"；也不要只看 1~2 个文件就下结论，下结论前先把关键词在两个仓库里搜全

参照实现：同源 fork `leonwu85-bv`（工作区另一个根目录）在这条链路上更完整，可对照其 `BiliHttpApi.getVideoWbiPlayUrl` 与 `GeetestTvVerifyDialog`。

## CI / Git 工作流

- `develop` → Alpha Build
- `feature/*` → Feature Build
- `release` tag → Release Build

## 开发注意事项

- **改动前先判定目标端**：TV 入口在 `app/tv`，Mobile 入口在 `app/mobile`，两端的 UI/交互是各自实现的。用户说"TV 版"时默认只改 `app/tv` 与共享层，不要顺手动 `app/mobile`；另一端的对应实现只在明确要求时改
- **TV 遥控：长按 ≠ 单击**，不要用时间阈值区分（"弹窗后 400ms 内忽略点击"这类改法会被否掉——超过阈值再松手仍会误触发，且手感变差）。按按键序列判断：长按的 `isLongPress` KeyDown repeat 与随后那次 KeyUp 必须成对处理，"跳过下一个 KeyUp"的标记要在**同一个键**的 KeyUp 上无条件清除，不能只在某个条件分支里清——残留标记会吞掉下一次真实按键，此处已回归过一次。涉及 `VideoPlayerV3Screen.kt`（`skipNextKeyUpCancel`）、`VideoPlayerController.kt`、长按卡片弹出的操作菜单时，先枚举「长按中 / 松手 / 无倒计时」三种组合
- 极验/风控 UI 有**三处**实现，别只找到一处就下结论：`app/mobile` 的 `LoginScreen`、移动端播放页（内联官方 Sensebot SDK）、`app/tv` 的 `GeetestTvVerifyDialog`（自绘 WebView）+ `SmsLoginContent`
- Kotlin 代码风格 `official`（已在 `gradle.properties` 配置）
- `nonTransitiveRClass=true` 已启用
- Compose Compiler Reports 输出到 `build/compose_build_reports`
- ProGuard 规则集中在各模块 `proguard-rules.pro` / `consumer-rules.pro`
- **音频均衡改动的验收标准是真机试听**（`player/core/src/main/kotlin/dev/aaa1115910/bv/player/audio/`）。离线仿真和单测只能算辅助证据，汇报时把「仿真结论 / 单测结论 / 真机结论」分开写。`VolumeBalanceAudioProcessor` 的三段增益（窗口填满前只压不抬 → `GAIN_SETTLE_STEP_DB` 限速定档 → 6/12s 维持）与限制器软拐点参数都是定稿值，"指数逼近"和"给包络加 ms 级起音"已被量化否定，不要改回。另有三道防止「把安静前奏/安静段落当成整段偏轻」的约束，同样是定稿值：提升天花板（节目级参照 `anchorLufs`，需连续 `ANCHOR_RISE_BLOCKS` 块才采纳，避免单块爆点把参照钉死）、提升下限 `MIN_BOOST_LUFS`、快速收回 `GAIN_REVOKE_STEP_DB`。参照下移只能用固定慢速率 + 死区（`ANCHOR_FALL_DB_PER_BLOCK` / `ANCHOR_FALL_DEADBAND_LU`）——改回「按当前水平指数逼近」时，20 秒的安静段就足以让天花板打开约 2 dB，安静段落重新被抬起来（已回归，见 `VolumeBalanceAudioProcessorTest`）。`WINDOW_BLOCKS`（5 秒 = 50 块）必须与 `LoudnessMeter` 的 `windowSeconds` 同步
- 偏好设置统一在 [app/shared/.../util/Prefs.kt](app/shared/src/main/kotlin/dev/aaa1115910/bv/util/Prefs.kt)

## 常见坑

- 修改 `AppConfiguration` 的版本号常量会立即影响所有变体，无需同步其他文件
- 升级 AndroidX 前先确认 minSdk 上限：Compose 1.12+、Navigation 2.10+、WebKit 1.16+、Material3 alpha19+ 都要求 **minSdk 24**，本项目 minSdk 23 只能停在 Compose 1.11.x / Material3 1.5.0-alpha18；Compose 1.13+ 还需要预览版 `android-37.1`（本机只有 `android-37.0`）
- 调整 protobuf 后必须确认 `ProtobufConfiguration.usedProtoFiles` 同步更新，否则会被静默排除
- Koin：新增 `@Module` 类要一并加 `@Configuration`，否则 `@KoinApplication`（`BVKoinApp`）不会跨 Gradle 模块发现它；`@KoinViewModel` 的包是 `org.koin.core.annotation`
- 弹幕引擎改动需同时关注 `player/mobile` 和 `player/tv` 两端的 `BvPlayer.kt`（分别集成 `DanmakuView` 和 `DanmakuLayer`）
- 依赖的 AAR 在 `libs/`，不是从 Maven 拉取的；版本变动需重新打包并替换
