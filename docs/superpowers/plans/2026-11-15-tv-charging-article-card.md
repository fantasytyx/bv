# TV 充电专属图文/动态提示卡片 + 充电二维码 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 充电专属图文/动态在未充电时显示充电提示卡片，按 OK 弹出充电地址二维码。

**Architecture:** `bili-api` 补解析 opus 详情的 `module_blocked` 并映射成与动态侧同一个 `DynamicBlockedModule`；`app/tv` 把动态详情独占的 `DynamicBlockedCard` 提升为共享的 `ChargeBlockedCard`（可聚焦可点击），两个详情页各自把它注册进 `itemFocus`，点击后复用二维码弹窗显示 `blocked.button.jumpUrl`。

**Tech Stack:** Kotlin、kotlinx.serialization、Jetpack Compose for TV（`androidx.tv.material3`）、Koin、JUnit4 + kotlin.test。

**Spec:** `docs/superpowers/specs/2026-11-15-tv-charging-article-card-design.md`

## Global Constraints

- 只改 TV 端（`app/tv`）与 `bili-api`，不动 `app/mobile`。
- 二维码内容固定取 `blocked.button.jumpUrl`（opus 侧即 `module_blocked.button.jump_url`），地址为空时 toast「暂无充电地址」且不弹窗。
- 卡片外观沿用动态详情已实现的 `DynamicBlockedCard`（图标 / 标题 / 提示），只增加聚焦、点击与 CTA 行。
- TV 遥控：不新增卡片内部焦点停留点，整张卡片是一个焦点目标；卡片要注册进 `itemFocus` 以参与 `restoreListFocus`。
- 不要用时间阈值区分长按/单击（见 AGENTS.md），本改动不涉及按键序列。

## Review Focus

- 充电专属 opus 的 `module_blocked` 缺失（老接口/风控返回）时页面不能空白崩溃，且不显示空卡片。
- `module_blocked` 只有 `hint_message`、`title` 为空时，卡片仍然可读（标题兜底）。
- `button.jump_url` 为空时不能弹出空二维码。
- 二维码弹窗打开期间机构滚动/焦点复原不能把焦点抢回列表（否则返回键行为异常）。
- 图文详情页只有充电卡片这一个列表项时，初始焦点必须落在卡片上（不能出现无焦点）。

---

### Task 1: bili-api 解析 opus 的 `module_blocked`

**Files:**
- Modify: `bili-api/src/main/kotlin/dev/aaa1115910/biliapi/http/entity/user/OpusDetailData.kt`
- Modify: `bili-api/src/main/kotlin/dev/aaa1115910/biliapi/entity/user/Dynamic.kt:604-620`（新增 `fromOpusBlocked`）
- Modify: `bili-api/src/main/kotlin/dev/aaa1115910/biliapi/entity/user/ArticleView.kt`
- Test: `bili-api/src/test/kotlin/dev/aaa1115910/biliapi/entity/user/ArticleViewOpusDetailMappingTest.kt`

**Interfaces:**
- Consumes: 现有 `DynamicBlockedModule`（`title` / `hintMessage` / `iconUrl` / `bgImageUrl` / `buttonText` / `jumpUrl`）与 `fromModuleBlocked`。
- Produces:
  - `OpusDetailModule.blocked: Blocked?`（`Blocked` = `dev.aaa1115910.biliapi.http.entity.dynamic.DynamicItem.Modules.Dynamic.Major.Blocked`）
  - `DynamicItem.DynamicBlockedModule.Companion.fromOpusBlocked(Blocked): DynamicBlockedModule`
  - `ArticleViewData.blocked: DynamicBlockedModule?`

- [ ] **Step 1: 写失败测试**

在 `ArticleViewOpusDetailMappingTest` 里新增（沿用现有 `opusDetail(...)` 构造器）：

```kotlin
@Test
fun `charging-only opus maps the blocked panel`() {
    val article = ArticleViewData.fromOpusDetail(opusDetail(content = BLOCKED_MODULE))

    val blocked = assertNotNull(article.blocked)
    assertEquals("开通可看专属动态", blocked.title)
    assertEquals("加入当前UP主的「6元档包月充电」即可解锁观看", blocked.hintMessage)
    assertEquals("https://i0.hdslb.com/icon-dark.png", blocked.iconUrl)
    assertEquals("6元充电", blocked.buttonText)
    assertEquals("https://www.bilibili.com/h5/upower/index?mid=2009929", blocked.jumpUrl)
    assertTrue(article.paragraphs.isEmpty())
}

@Test
fun `charging-only opus without any copy still yields a readable card`() {
    val article = ArticleViewData.fromOpusDetail(
        opusDetail(content = """{"module_type": "MODULE_TYPE_BLOCKED", "module_blocked": {"blocked_type": 3}}""")
    )

    assertEquals("充电专属图文", assertNotNull(article.blocked).title)
}
```

并在 companion 里加：

```kotlin
val BLOCKED_MODULE = """
{
  "module_type": "MODULE_TYPE_BLOCKED",
  "module_blocked": {
    "blocked_type": 3,
    "icon": {"img_day": "https://i0.hdslb.com/icon-day.png", "img_dark": "https://i0.hdslb.com/icon-dark.png"},
    "title": "开通可看专属动态",
    "hint_message": "加入当前UP主的「6元档包月充电」即可解锁观看",
    "button": {
      "text": "6元充电",
      "jump_url": "https://www.bilibili.com/h5/upower/index?mid=2009929",
      "handle_type": 2
    }
  }
}
""".trimIndent()
```

在既有 `opus detail without content or stat keeps safe defaults` 测试里补一行 `assertNull(article.blocked)`。

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :bili-api:test --tests "*ArticleViewOpusDetailMappingTest*"`
Expected: 编译失败/断言失败（`ArticleViewData` 没有 `blocked`）

- [ ] **Step 3: 实现解析与映射**

`OpusDetailData.kt`：给 `OpusDetailModule` 增加字段并 import 同构载荷：

```kotlin
import dev.aaa1115910.biliapi.http.entity.dynamic.DynamicItem.Modules.Dynamic.Major.Blocked
...
    /** 充电专属等被拦下的正文；有它就一定没有 `module_content` */
    @SerialName("module_blocked")
    val blocked: Blocked? = null
```

`Dynamic.kt`：在 `DynamicBlockedModule.Companion` 里加：

```kotlin
/**
 * opus 详情的 `module_blocked`：没有外层 `MAJOR_TYPE_BLOCKED` 可判断，只看载荷本身。
 * `title` 常为空、文案主要落在 `hint_message`；两块都缺时兜底一个可读标题。
 */
fun fromOpusBlocked(
    blocked: dev.aaa1115910.biliapi.http.entity.dynamic.DynamicItem.Modules.Dynamic.Major.Blocked
): DynamicBlockedModule = fromModuleBlocked(blocked).let { module ->
    if (module.title.isBlank() && module.hintMessage.isBlank()) {
        module.copy(title = "充电专属图文")
    } else {
        module
    }
}
```

`ArticleView.kt`：`ArticleViewData` 增加 `val blocked: DynamicBlockedModule? = null`（放在 `video` 之后），
`fromOpusDetail` 里加：

```kotlin
blocked = modules.firstNotNullOfOrNull { it.blocked }
    ?.let(DynamicBlockedModule::fromOpusBlocked),
```

- [ ] **Step 4: 跑测试确认通过**

Run: `./gradlew :bili-api:test --tests "*ArticleViewOpusDetailMappingTest*"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add bili-api/src/main/kotlin/dev/aaa1115910/biliapi/http/entity/user/OpusDetailData.kt bili-api/src/main/kotlin/dev/aaa1115910/biliapi/entity/user/Dynamic.kt bili-api/src/main/kotlin/dev/aaa1115910/biliapi/entity/user/ArticleView.kt bili-api/src/test/kotlin/dev/aaa1115910/biliapi/entity/user/ArticleViewOpusDetailMappingTest.kt
git commit -m "feat(api): 解析图文详情的充电专属锁定面板"
```

---

### Task 2: 共享充电卡片与二维码弹窗

**Files:**
- Modify: `app/tv/src/main/kotlin/dev/aaa1115910/bv/tv/screens/user/ArticleDetailComponents.kt`

**Interfaces:**
- Consumes: `DynamicItem.DynamicBlockedModule`、`FocusRequester`、`QrImage`。
- Produces:
  - `@Composable fun ChargeBlockedCard(blocked: DynamicItem.DynamicBlockedModule, focusRequester: FocusRequester, onClick: () -> Unit, modifier: Modifier = Modifier)`
  - `@Composable fun ChargeQrCodeDialog(url: String, onDismiss: () -> Unit)`
  - `DetailShareDialog` 签名不变。

- [ ] **Step 1: 加共享卡片**

在 `ArticleDetailComponents.kt` 末尾加 `ChargeBlockedCard`：外壳用 `Surface(onClick = onClick)`，
`colors` / `scale` / `border` 抄 `DetailVideoCard`（聚焦时 surfaceVariant 0.7 + border 高亮），
内部 `Row(Modifier.padding(20.dp), verticalAlignment = CenterVertically)`：图标在左（96dp，可缺省），
`Spacer(width = 20.dp)`，右侧 `Column(Modifier.weight(1f), verticalArrangement = spacedBy(8.dp))` 放标题/提示/CTA：

```kotlin
if (blocked.iconUrl.isNotBlank()) {
    AsyncImage(model = blocked.iconUrl, contentDescription = null,
        modifier = Modifier.size(96.dp), contentScale = ContentScale.Fit)
    Spacer(Modifier.width(20.dp))
}
Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    if (blocked.title.isNotBlank()) Text(text = blocked.title, fontSize = 22.sp)
    if (blocked.hintMessage.isNotBlank()) {
        Text(text = blocked.hintMessage, fontSize = 16.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
    }
    Text(
        text = listOf(blocked.buttonText.takeIf { it.isNotBlank() }, "按 OK 显示充电二维码")
            .filterNotNull().joinToString(" · "),
        color = MaterialTheme.colorScheme.primary,
        fontSize = 14.sp
    )
}
```

补齐 import：`dev.aaa1115910.biliapi.entity.user.DynamicItem`。

- [ ] **Step 2: 抽公共二维码弹窗**

把 `DetailShareDialog` 的函数体整体搬进新的 `private fun DetailQrCodeDialog(title: String, url: String, onDismiss: () -> Unit)`，
标题文本改用 `title`，并收窄尺寸：`Column(Modifier.widthIn(max = 320.dp).padding(24.dp))`、
`QrImage(Modifier.size(220.dp), borderWidth = 8.dp)`、链接文本 `fontSize = 11.sp / lineHeight = 14.sp / maxLines = 3`；然后：

```kotlin
@Composable
fun DetailShareDialog(url: String, onDismiss: () -> Unit) =
    DetailQrCodeDialog(title = "分享", url = url, onDismiss = onDismiss)

@Composable
fun ChargeQrCodeDialog(url: String, onDismiss: () -> Unit) =
    DetailQrCodeDialog(title = "充电地址", url = url, onDismiss = onDismiss)
```

- [ ] **Step 3: 编译验证**

Run: `./gradlew :app:tv:compileDebugKotlin`
Expected: BUILD SUCCESSFUL（两个新 composable 还未被调用，不应有 unused 错误）

- [ ] **Step 4: Commit**

```bash
git add app/tv/src/main/kotlin/dev/aaa1115910/bv/tv/screens/user/ArticleDetailComponents.kt
git commit -m "feat(tv): 抽出共享充电卡片与二维码弹窗"
```

---

### Task 3: 图文详情接入充电卡片

**Files:**
- Modify: `app/tv/src/main/kotlin/dev/aaa1115910/bv/tv/screens/user/ArticleDetailScreen.kt`

**Interfaces:**
- Consumes: `ChargeBlockedCard`、`ChargeQrCodeDialog`、`ArticleViewData.blocked`、`DetailItemFocus`。
- Produces: 列表项 key 常量 `ArticleChargeItemKey = "article-charge"`。

- [ ] **Step 1: 状态与焦点**

在文件顶部常量区加 `private const val ArticleChargeItemKey = "article-charge"`；
在 `ArticleDetailScreen` 里加：

```kotlin
var chargeUrl by remember(articleId) { mutableStateOf<String?>(null) }
val chargeFocusRequester = remember { FocusRequester() }
```

并把 `itemFocus` 的 `remember { mapOf(...) }` 里加一项：

```kotlin
ArticleChargeItemKey to DetailItemFocus(chargeFocusRequester),
```

- [ ] **Step 2: 渲染卡片**

在 `LazyColumn` 内、`bodyText` 的 item 之前：

```kotlin
article?.blocked?.let { blocked ->
    item(key = ArticleChargeItemKey) {
        ChargeBlockedCard(
            blocked = blocked,
            focusRequester = chargeFocusRequester,
            onClick = {
                if (blocked.jumpUrl.isBlank()) {
                    "暂无充电地址".toast(context)
                } else {
                    chargeUrl = blocked.jumpUrl
                }
            }
        )
    }
}
```

- [ ] **Step 3: 弹窗与焦点保护**

页面末尾与 `showShare` 同级加：

```kotlin
chargeUrl?.let { url ->
    ChargeQrCodeDialog(url = url, onDismiss = { chargeUrl = null })
}
```

并把「弹窗期间不抢焦点」的三处都带上 `chargeUrl`：
`Menu` 的守卫条件加 `&& chargeUrl == null`；`scrollToPage` 的提前 return 加 `|| chargeUrl != null`；
`LaunchedEffect(showComments, showShare, imageIndex)` 的 key 加上 `chargeUrl`，条件加 `&& chargeUrl == null`。

- [ ] **Step 4: 编译验证**

Run: `./gradlew :app:tv:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add app/tv/src/main/kotlin/dev/aaa1115910/bv/tv/screens/user/ArticleDetailScreen.kt
git commit -m "feat(tv): 图文详情显示充电提示卡片并可扫码充电"
```

---

### Task 4: 动态详情卡片可点击

**Files:**
- Modify: `app/tv/src/main/kotlin/dev/aaa1115910/bv/tv/screens/user/DynamicDetailScreen.kt`

**Interfaces:**
- Consumes: `ChargeBlockedCard`、`ChargeQrCodeDialog`。
- Produces: 无（仅页面内部）。

- [ ] **Step 1: 替换卡片并接焦点**

删掉文件末尾的 `private fun DynamicBlockedCard`（逻辑并入 `ChargeBlockedCard`），
在 `DynamicDetailScreen` 里加 `var chargeUrl`、`val blockedFocusRequester = remember { FocusRequester() }`，
`itemFocus` 增加 `DynamicBlockedItemKey to DetailItemFocus(blockedFocusRequester)`，并把列表里的调用改成：

```kotlin
currentDynamic.blocked?.let { blocked ->
    item(key = DynamicBlockedItemKey) {
        ChargeBlockedCard(
            blocked = blocked,
            focusRequester = blockedFocusRequester,
            onClick = {
                if (blocked.jumpUrl.isBlank()) {
                    "暂无充电地址".toast(context)
                } else {
                    chargeUrl = blocked.jumpUrl
                }
            }
        )
    }
}
```

- [ ] **Step 2: 弹窗与焦点保护**

在 `showShare` 弹窗旁边加 `chargeUrl?.let { ChargeQrCodeDialog(...) }`；
`Menu` 守卫、`scrollToPage` 的提前 return、`LaunchedEffect(showComments, showShare, imageIndex, quotedImageIndex)`
三处同样补上 `chargeUrl`。

- [ ] **Step 3: 清理 import**

删除已无引用的 `androidx.compose.ui.text.style.TextAlign`（`AsyncImage` 仍被 `DetailLiveCard` 使用，保留）。

- [ ] **Step 4: 编译验证**

Run: `./gradlew :app:tv:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add app/tv/src/main/kotlin/dev/aaa1115910/bv/tv/screens/user/DynamicDetailScreen.kt
git commit -m "feat(tv): 动态详情充电卡片支持按 OK 显示二维码"
```

---

### Task 5: 全量校验

- [ ] **Step 1: 单元测试 + 编译**

Run: `./gradlew :bili-api:test --tests "*ArticleViewOpusDetailMappingTest*" :bili-api:test --tests "*DynamicBlockedMappingTest*" :app:tv:compileDebugKotlin`
Expected: BUILD SUCCESSFUL，两个测试类全绿

- [ ] **Step 2: 汇报**

把「单测结论」与「真机/模拟器未验证」分开写清楚：TV 上没有可用的自动 UI 测试，二维码显示需要真机确认。
