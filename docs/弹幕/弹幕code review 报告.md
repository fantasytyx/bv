
# 弹幕系统 Code Review 报告

## 总览

整体架构为 **三线程 + 双缓冲快照** 设计（Main Thread 绘制 / ActionThread 逻辑 / CacheThread 缓存构建），是一套高性能、精心设计的弹幕引擎。核心代码质量较高，ECS-like 的分离设计合理。以下内容按 **已实施 / 尝试后放弃 / 未实施** 分类整理，尽量保留原始问题描述，并将重复问题合并到对应条目中。

---

## 一、已实施

### 优化 1：消除时间链路 Int 截断（亚像素抖动修复）

将整个时间数据链路从 `Long`/`Int` 精度提升到 `Double`：
- DanmakuTimer.kt — `step()` 和 `currentPositionMs()` 返回 `Double`
- DanmakuEngine.kt — `currentPositionMs`、`stepTime()`、`scrollX()` 全部使用 `Double`
- RenderSnapshot.kt — `positionMs` 改为 `Double`

**效果**：60fps 下每帧位移从 4.8\~5.4px 交替抖动变为稳定 5.3px；120Hz 设备改善更明显。

### 2. Mobile 端 `initDanmakuConfig` 未同步 `minLevel`（FilterLevel）

**文件**：[BvPlayer.kt (Mobile)](d:\code\bv\player\mobile\src\main\kotlin\dev\aaa1115910\bv\player\mobile\BvPlayer.kt#L121-L131)

```kotlin
val initDanmakuConfig: () -> Unit = {
    danmakuConfig = danmakuConfig.copy(
        enabled = true,   // ← 硬编码 true，不读 showDanmaku
        // 缺少: minLevel = filterLevel
    )
}
```

对比 TV 端的 `syncDanmakuConfig`：
- TV 端读取 `videoPlayerConfigData.showDanmaku` 作为 `enabled`
- TV 端设置了 `minLevel = filterLevel`
- Mobile 端硬编码 `enabled = true`，且未同步 `minLevel`

**结果**：Mobile 端弹幕等级过滤设置无法生效；初始化时 `enabled` 不受 `showDanmaku` 控制。

### 3. Mobile 端缺少 `speedLevel` / `rollingDurationFactor` 映射

**文件**：[BvPlayer.kt (Mobile)](d:\code\bv\player\mobile\src\main\kotlin\dev\aaa1115910\bv\player\mobile\BvPlayer.kt)

Mobile 端的 `DanmakuMenu` 只暴露了 4 项设置（类型、不透明度、区域、大小），但 `DanmakuConfig.speedLevel` 始终保持默认值 `4`。即使 ViewModel 中存在 `currentDanmakuRollingDurationFactor`，Mobile 端也没有同步这个值到 `DanmakuConfig`。

### 6. `opacity` 和 `area` 通过 `configProvider` lambda 动态提供，但未触发 `updateConfig`

**文件**：[BvPlayer.kt (TV)](d:\code\bv\player\tv\src\main\kotlin\dev\aaa1115910\bv\player\tv\BvPlayer.kt#L559-L564)

```kotlin
setConfigProvider {
    danmakuConfig.copy(
        opacity = currentConfigData.currentDanmakuOpacity,
        area = currentConfigData.currentDanmakuArea,
    )
}
```

`DanmakuView.onDraw()` 每帧调用 `configProvider` 获取 config，然后与 `lastConfig` 比较来决定是否 `player.updateConfig(cfg)`。由于 `opacity` 和 `area` 通过 `copy()` 注入，**只要 opacity/area 变化就会触发 config 更新**。但 `updateConfig()` 内部会调 `seekTo(currentPositionMs())`——这意味着**每次修改不透明度的滑块，都会导致所有弹幕重新生成（清屏+重新 spawn）**。这在用户拖动滑块调整透明度时会造成弹幕闪烁。

**建议**：`opacity` 不影响布局，不应该触发 `seekTo`。在 `DanmakuPlayer` 的 `MSG_OP_CONFIG` handler 中，应区分「仅外观变化」和「布局变化」。如果只是 opacity 变了，不需要 seekTo。

### 7. `syncDanmakuConfig` 未同步 `speedLevel`

**文件**：[BvPlayer.kt (TV)](d:\code\bv\player\tv\src\main\kotlin\dev\aaa1115910\bv\player\tv\BvPlayer.kt#L254-L267)

TV 端的 `syncDanmakuConfig()` 同步了 `enabled`, `textSizeScale`, `allowScroll/Top/Bottom`, `minLevel`，但**没有同步 `speedLevel`**（来自 `currentDanmakuRollingDurationFactor`）。这意味着如果用户切换视频后重新 sync，speedLevel 会回到默认值 4。

### 8. Mobile 端弹幕区域用 `Modifier.fillMaxHeight(area)` 而非 `DanmakuConfig.area`

**文件**：[BvPlayer.kt (Mobile)](d:\code\bv\player\mobile\src\main\kotlin\dev\aaa1115910\bv\player\mobile\BvPlayer.kt)

```kotlin
Modifier.fillMaxHeight(videoPlayerConfigData.currentDanmakuArea)
```

Mobile 通过裁切 View 高度来限制弹幕区域，这与 TV 端使用 `DanmakuConfig.area` 在引擎内部控制 `usableHeight` 的方式不同。**两者行为不一致**：Mobile 裁切 View 会影响蒙版绘制区域，TV 则不会。建议统一方式。

### 9. **[高优先级]** `drawTextDirect()` 每条弹幕逐条切换 `textSize` 导致高频 Paint 重建

**文件**：DanmakuEngine.kt

```kotlin
private fun drawTextDirect(...) {
    val effectiveTs = (textSizePx * clampedSize / 25f * scaleFactor).coerceAtLeast(1f)
    if (drawFill.textSize != effectiveTs) { drawFill.textSize = effectiveTs; drawStroke.textSize = effectiveTs }
    drawFill.getFontMetrics(drawFontMetrics)  // ← 每条都调 getFontMetrics
    // ...
    if (drawFill.textSize != baseTs) { drawFill.textSize = baseTs; drawStroke.textSize = baseTs }  // ← 恢复
}
```

`drawTextDirect` 是**缓存未命中时的 fallback**。当大量弹幕首次出现且缓存还没准备好时，每条弹幕都会触发：
1. Paint.textSize 切换 × 2（设置 + 恢复）
2. `getFontMetrics()` 调用
3. `drawText()` × 2（stroke + fill）

在弹幕密集场景（如高能弹幕段）可能一帧有 20-30 条 fallback 绘制。

**建议**：
- 将 `drawTextDirect` 中的弹幕按 `effectiveTs` 分组批量绘制，减少 Paint 切换次数
- 或者提高缓存构建优先级 `MAX_CACHE_REQUESTS_PER_FRAME`（当前仅 8），减少 fallback 频率

#### 原后续优化记录：3. 提高缓存构建速率

测试过，MAX_CACHE_REQUESTS_PER_FRAME 8个就够用了，加大一点 改12吧

`MAX_CACHE_REQUESTS_PER_FRAME` 从 8 → 12，减少弹幕密集段的 `drawTextDirect` fallback 频率，降低 Paint 频繁切换导致的 GPU pipeline flush。

### 10. **[高优先级]** 蒙版 `saveLayer()` 每帧触发离屏渲染

**文件**：DanmakuView.kt

```kotlin
val saveCount = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
player.draw(canvas, rawPos, isPlaying, speed, cfg)
drawMaskBitmap(canvas, maskBitmap, videoAspectRatio, areaRatio)
canvas.restoreToCount(saveCount)
```

`saveLayer()` 会触发 GPU 离屏缓冲区分配，这是 Android 绘制管线中**最昂贵的操作之一**。每帧都调用此操作（只要蒙版启用），在低端设备上会导致明显掉帧。

**建议**：
- 考虑将蒙版应用改为 `canvas.clipPath()` + `Region.Op.INTERSECT`，避免离屏渲染
- 或使用 `setLayerType(LAYER_TYPE_HARDWARE, ...)` 将整个 View 做硬件层缓存，在蒙版 bitmap 不变时可跳过重建

#### 原后续优化记录：优化 5：蒙版 saveLayer 优化

**改动文件**：DanmakuView.kt、DanmakuMaskModifiers.kt

- **DanmakuView**：蒙版激活时通过 `setLayerType(LAYER_TYPE_HARDWARE)` 让 View 渲染到持久 GPU FBO，DstIn 操作直接在硬件层内完成，不需要每帧 `saveLayer`；蒙版移除时恢复 `LAYER_TYPE_NONE`。保留 saveLayer 作为硬件层尚未生效时的 fallback
- **DanmakuMaskModifiers (Compose)**：将手动 `canvas.saveLayer()` + `canvas.restore()` 替换为 `Modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }.drawWithContent { ... }`，由 Compose 框架管理离屏缓冲生命周期
- **效果**：避免每帧分配/释放 GPU 离屏缓冲区，持久 FBO 跨帧复用，减少 GPU pipeline flush

### 11. **[中等]** `appendDanmakus()` 重置 `index=0` 和 `clearActives()` 导致弹幕闪烁

**文件**：DanmakuEngine.kt

```kotlin
fun appendDanmakus(list: List<Danmaku>, maxItems: Int, alreadySorted: Boolean) {
    // ...
    rebuildFilteredItems()
    index = 0; clearActives(); pending.clear(); lastNowMs = 0  // ← 重置一切
    publishEmptySnapshot()
}
```

追加弹幕时清空所有活跃弹幕并从头开始 → 正在屏幕上滚动的弹幕会瞬间消失。如果段切换场景使用 `appendDanmakus`，实际上每次段切换都会清屏。

**建议**：`appendDanmakus()` 应使用 `lowerBound()` 重新定位 `index` 到当前播放位置附近，而不是重置为 0。活跃弹幕可通过时间范围检查保留仍在屏幕中的项。

**实施结果**：中间插入时改为重新计算 `index`，同时保留 `actives` / `pending`，不再清空 snapshot。

---

## 二、尝试后放弃

### 12. **[中等]** `act()` 中的 snapshot 发布阻塞在 `drawSemaphore.acquire()`

**文件**：DanmakuPlayer.kt

```kotlin
MSG_FRAME_UPDATE -> {
    drawSemaphore.acquire()  // ← 如果 main 线程还没 draw 就一直阻塞
    engine.act()
    view.postInvalidateOnAnimation()
}
```

ActionThread 会在 `drawSemaphore.acquire()` 上阻塞，直到上一帧 main 线程的 `draw()` 调用 `releaseSemaphoreIfNeeded()`。如果 main 线程因其他 UI 工作延迟了 `onDraw`，ActionThread 就白白等待，**浪费了一个 vsync 周期**。

**建议**：改用 `tryAcquire(frameTimeout)` 带超时，或使用 AtomicReference 交换 snapshot 引用的无锁方案替代 Semaphore。

#### 原后续优化记录：优化 4：Semaphore → 无锁三缓冲

> 试过了，效果不好

**改动文件**：DanmakuEngine.kt、DanmakuPlayer.kt

- 完全移除 `Semaphore`，ActionThread 不再阻塞等待 Main Thread 完成绘制
- 双缓冲改为**三缓冲**（`snapshotA/B/C`），通过 `@Volatile readingSnapshot` 追踪 Main Thread 正在读取的 buffer，`writableSnapshot()` 始终选择一个既不是最新发布、也不是正在读取的 buffer
- Main Thread 调用 `acquireSnapshot()` 标记正在读取，`releaseSnapshot()` 释放，全程无锁
- **效果**：消除原来 ActionThread 12ms 超时等待的帧丢弃问题；Main Thread 即使偶尔延迟也不会阻塞 ActionThread 的帧计算

#### 原理说明

#### 原方案：Semaphore 同步双缓冲

原来有两个线程交替操作两个 snapshot buffer（A/B）：

```text
ActionThread（生产者）                MainThread（消费者）
      │                                    │
      ▼                                    ▼
 drawSemaphore.acquire(12ms)          drawSemaphore.tryAcquire()
      │  ← 阻塞等待 Main 读完 ──────  读 snapshot
      ▼                                    │
 engine.act() 写入 snapshot              draw(canvas, snapshot)
      │                                    │
      ▼                                    ▼
 [写完了，等下一个 vsync]           releaseSemaphoreIfNeeded()
                                     └→ release() 给 ActionThread 解阻
```

**问题**：

1. **ActionThread 被阻塞** — 每帧开始时必须 `acquire(12ms)` 等 Main Thread 上一帧读完，如果 Main Thread 因布局/GC 等原因延迟 `onDraw`，ActionThread 白等 12ms 后超时，**整帧计算被跳过**
2. **双缓冲的限制** — 只有 A/B 两个 buffer，写之前必须确认读方不在用，否则数据竞争

#### 新方案：无锁三缓冲

三个 buffer（A/B/C）+ 两个 `@Volatile` 指针：

```kotlin
@Volatile var latestSnapshot: RenderSnapshot   // 最近一次写完的（ActionThread 发布）
@Volatile var readingSnapshot: RenderSnapshot?  // Main Thread 正在读的（可能为 null）
```

**写入（ActionThread）**：

```kotlin
fun act() {
    val out = writableSnapshot()  // 选一个安全的 buffer
    // ... 填充 out ...
    latestSnapshot = out          // volatile 写 → 原子发布
}

fun writableSnapshot(): RenderSnapshot {
    // 选一个既不是 latest（刚发布的）也不是 reading（正在被读的）的 buffer
    return when {
        A !== latest && A !== reading -> A
        B !== latest && B !== reading -> B
        else -> C
    }
}
```

**读取（Main Thread）**：

```kotlin
fun draw(canvas, ...) {
    val snapshot = engine.acquireSnapshot()  // reading = latest
    engine.draw(canvas, snapshot, ...)
    engine.releaseSnapshot()                // reading = null
}
```

- ActionThread **永远不阻塞**，每个 vsync 都执行 `act()` 计算新一帧
- Main Thread 延迟了也无所谓，ActionThread 继续往第三个 buffer 写
- Main Thread 恢复后读到的是最新的 snapshot，中间的旧帧自然被跳过（和显示器跳帧一样）

**核心收益**：再也没有"ActionThread 等 12ms 超时后丢弃整帧"的情况，弹幕位置更新不会因 Main Thread 偶尔繁忙而中断。

---

## 三、未实施

本节包含允许的不改、误判，以及尚未继续推进的项。

### 4. `DanmakuEngine.act()` 中 `actionPaint.textSize` 被 Spawn 循环中的 `measureTextWidth()` 篡改

> 允许的，不改

**文件**：DanmakuEngine.kt

```kotlin
private fun measureTextWidth(item: DanmakuItem, outlinePad: Float, cfg: DanmakuConfig): Float {
    val clampedSize = min(item.data.textSize, 25)
    val effectiveTextSizePx = (textSizePx * clampedSize / 25f * scaleFactor).coerceAtLeast(1f)
    actionPaint.textSize = effectiveTextSizePx  // ← 修改了共享 Paint
    return actionPaint.measureText(text) + outlinePad * 2f
}
```

`act()` 开头将 `actionPaint.textSize = layoutTextSizePx` 用于计算 `textBoxHeight`、`laneHeight` 等布局参数。但之后 spawn 循环中调用 `measureTextWidth()` 修改了 `actionPaint.textSize` 为每条弹幕自己的 effective size。**如果有弹幕 textSize != 25，布局计算的 laneHeight 就和实际文字高度不一致**。当前 B 站弹幕 textSize 绝大多数为 25，允许存在字体大小不同的弹幕。

### 5. `DanmakuPlayer.draw()` 中的信号量使用可能导致帧丢失

> 这个逻辑才是对的。是AI误判。

**文件**：DanmakuPlayer.kt

```kotlin
fun draw(canvas: Canvas, ...) {
    // ...
    drawSemaphore.tryAcquire()     // non-blocking，可能获取不到
    val snapshot = engine.renderSnapshot()
    releaseSemaphoreIfNeeded()     // 无条件 release
    engine.draw(canvas, snapshot, config)
}
```

`ActionHandler.MSG_FRAME_UPDATE` 使用 `drawSemaphore.acquire()`（阻塞），`draw()` 使用 `tryAcquire()`（非阻塞）。这个逻辑**意图是双缓冲同步**：main 线程读完 snapshot 后 release 让 action 线程继续写。但当前实现有一个微妙问题：`releaseSemaphoreIfNeeded()` 检查 `availablePermits() == 0` 才 release，但 `tryAcquire()` 如果失败了 permits 仍为 0，此时 `releaseSemaphoreIfNeeded()` 仍会 release —— 这在逻辑上是正确的（解阻 action 线程），但属于"偶然正确"，不够健壮。

### 优化 2：Draw-time 位置插值（消除 pipeline 延迟）

> 当前是信号量锁步同步的架构，这个修改没必要，不改

`engine.draw()` 不再使用 snapshot 中预计算的 x 坐标，而是用当前帧的 `smoothPositionMs` 实时计算 SCROLL 弹幕的水平位置：

```kotlin
val x = if (item.kind == DanmakuKind.SCROLL)
    scrollX(drawWidth, smoothPositionMs, item.startTimeMs, item.pxPerMs)
else snapshot.x[i]
```

**效果**：彻底消除 act→draw 之间 \~16ms 的 pipeline 延迟，弹幕位置精确到当前 vsync 时刻。

### 13. **[低等]** `RenderSnapshot` 未使用对象池

**文件**：RenderSnapshot.kt

当前双缓冲的 `snapshotA/B` 内部的 `items/x/yTop/textWidth` 数组会在 `ensureCapacity` 时创建新数组。高弹幕密度下频繁扩容会产生 GC 压力。当前实现已有 `cap = max(required, items.size * 2 + 8)` 的倍增策略，问题不大，但可以考虑在 init 时预分配一个合理大小（如 256）。

### 14. `DanmakuTimer.EXTREME_DRIFT_REANCHOR_THRESHOLD_MS = 2000` 可能过于宽容

如果播放器 buffering 2 秒后恢复，smooth position 最多偏移 2 秒。建议收紧到 `500`~`1000ms`。

### 15. `loadDanmakuSegment` 中 `Color(it.color).toArgb()` 存在不必要的对象创建

每条弹幕都创建一个 `androidx.compose.ui.graphics.Color` 对象再 `toArgb()`，可直接使用位运算 `0xFF000000.toInt() or (it.color and 0xFFFFFF)` 避免装箱。