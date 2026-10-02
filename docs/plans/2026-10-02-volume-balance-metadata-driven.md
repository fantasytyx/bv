# 音量均衡元数据驱动改造 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把音量均衡从「本地在线响度估计」改为「服务端响度元数据驱动的静态增益」，起播即准、零实时 DSP、删除 BS.1770 响度计。

**Architecture:** `playurl` 响应的 `video_info.volume` 经 `bili-api` 解析为 `VolumeInfo`，随 `PlayData` 上浮到 app 层映射成 `AudioLoudness`，再由 ViewModel 推给 `AbstractVideoPlayer`；`player/core` 里的纯函数 `AudioBalanceGain` 算出唯一一个静态增益 dB，`VolumeBalanceAudioProcessor` 只做「乘增益 + 50ms 斜坡」。无元数据即旁路。

**Tech Stack:** Kotlin、kotlinx.serialization、Media3 `BaseAudioProcessor`、Koin、Jetpack Compose（菜单层不改动逻辑）、JUnit5（`player/core`）/ kotlin-test（`bili-api`）。

**Spec:** [docs/specs/2026-10-02-volume-balance-metadata-driven-design.md](../specs/2026-10-02-volume-balance-metadata-driven-design.md)

## Global Constraints

- **JDK 21 强制**。跑 gradle 前先设好 `JAVA_HOME`（本机为 `D:\Program Files\Android\Android Studio\jbr`），否则报 `Cannot find a Java installation ... matching: {languageVersion=21}`。
- **元数据无效即旁路，永不产生 NaN 增益**。NaN / 越界一律视为「未测量」。
- 取值有效区间**必须对齐 ffmpeg 4.0 `loudnorm` 的 AVOption 约束**：`measured_i ∈ [-99,0]`、`measured_tp ∈ [-99,99]`、`I ∈ [-99,0]`、`TP ∈ [-9,0]`、`offset ∈ [-99,99]`。
- 防呆上限/下限：增益钳制在 **±12 dB**。
- `targetTp` 缺失时用内置默认 **-1.0 dBTP** 参与夹取。
- 斜坡时长 **50 ms**，线性推进（非指数）。
- **不改** `VideoPlayerOptions`（它在 Activity `onCreate` 构建，早于 playurl 返回）。
- **不改** `player/core` 对 `bili-api` 的边界：`player/core` 不得 `import dev.aaa1115910.biliapi.*`（现状为 0 处），映射放在 `player/shared`。
- **不改** TV / Mobile 菜单组件本身的遍历逻辑（它们用 `AudioBalanceLevel.ordered`，枚举改动自动跟随）。
- Kotlin 代码风格 `official`（`gradle.properties` 已配）。
- 音频均衡的**验收标准是真机试听**；汇报时把「单测结论 / 真机结论」分开写。

## Review Focus

以下是最可能咬到用户的输入类别，各自的测试已挂到对应任务的步骤里：

1. **旁路路径的 buffer 处理**——增益为 1.0 时必须零拷贝直通且正确推进 `position`/`limit`，写错会导致变声或丢帧。
2. **`volume` 对象存在但 `measured_i = 0` 或全为 NaN**——必须旁路，绝不能算出 NaN 增益把整条音轨变成静音/噪声。
3. **PCM 编码差异**——`ENCODING_PCM_FLOAT` 与 `ENCODING_PCM_16BIT` 都要正确缩放。
4. **自动连播换集**——必须重新推送元数据，否则第二集沿用第一集的增益。
5. **播放器实例重建**——`videoPlayer` setter 被赋值时处理器是新的，元数据会丢，必须补推。

---

### Task 1: bili-api 解析 volume 元数据

**Files:**
- Create: `bili-api/src/main/kotlin/dev/aaa1115910/biliapi/entity/VolumeInfo.kt`
- Modify: `bili-api/src/main/kotlin/dev/aaa1115910/biliapi/http/entity/video/PlayUrlResponse.kt`（`PlayUrlData`）
- Modify: `bili-api/src/main/kotlin/dev/aaa1115910/biliapi/http/entity/proxy/PlayUrl.kt`（`ProxyWebPlayUrlData` 与 `ProxyAppPlayUrlData`）
- Modify: `bili-api/src/main/kotlin/dev/aaa1115910/biliapi/entity/PlayData.kt`
- Test: `bili-api/src/test/kotlin/dev/aaa1115910/biliapi/entity/VolumeInfoTest.kt`

**Interfaces:**
- Produces: `dev.aaa1115910.biliapi.entity.VolumeInfo`（`measuredI`/`measuredLra`/`measuredTp`/`measuredThreshold`/`targetOffset`/`targetI`/`targetTp`: `Double`，`multiSceneArgs`: `MultiSceneArgs?`）；`MultiSceneArgs`（`undersizedTargetI`/`normalTargetI`/`highDynamicTargetI`: `Double`）；`PlayData.volume: VolumeInfo?`。

- [ ] **Step 1: 写失败测试**

`VolumeInfoTest.kt`：用 `Json { ignoreUnknownKeys = true }` 反序列化一段真实形状的 JSON，断言字段落位。

```kotlin
class VolumeInfoTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `parses volume with multi scene args`() {
        val raw = """
        {"measured_i":-23.5,"measured_lra":7.2,"measured_tp":-1.4,
         "measured_threshold":-33.1,"target_offset":3.5,"target_i":-20.0,"target_tp":-2.0,
         "multi_scene_args":{"undersized_target_i":-40.0,"normal_target_i":-20.0,
                             "high_dynamic_target_i":-16.0}}
        """.trimIndent()
        val volume = json.decodeFromString<VolumeInfo>(raw)
        assertEquals(-23.5, volume.measuredI)
        assertEquals(-1.4, volume.measuredTp)
        assertEquals(3.5, volume.targetOffset)
        assertEquals(-20.0, volume.targetI)
        assertEquals(-16.0, volume.multiSceneArgs!!.highDynamicTargetI)
    }

    @Test
    fun `absent fields default to NaN`() {
        val volume = json.decodeFromString<VolumeInfo>("""{"measured_i":-23.5}""")
        assertTrue(volume.targetI.isNaN())
        assertTrue(volume.targetOffset.isNaN())
        assertNull(volume.multiSceneArgs)
    }

    @Test
    fun `playurl data carries volume`() {
        val raw = """{"quality":80,"volume":{"measured_i":-23.5,"target_i":-20.0}}"""
        val data = json.decodeFromString<PlayUrlData>(raw)
        assertEquals(-23.5, data.volume!!.measuredI)
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :bili-api:test --tests "dev.aaa1115910.biliapi.entity.VolumeInfoTest"`
Expected: 编译失败，`VolumeInfo` 未定义。

- [ ] **Step 3: 新建 `VolumeInfo.kt`**

所有数值字段 `@SerialName` 对应 JSON 键，缺省值 `Double.NaN`（NaN 表示「服务端未给」）。`multiSceneArgs` 默认 `null`。

- [ ] **Step 4: 给三个 DTO 加 `volume` 字段**

`PlayUrlData`、`ProxyWebPlayUrlData`、`ProxyAppPlayUrlData` 各加一行：

```kotlin
val volume: VolumeInfo? = null
```

代理 DTO 与 `PlayUrlData` 字段几乎一一对应，是同一份 web playurl JSON；声明该字段零成本（kotlinx 忽略未知键，无此键时为 `null`）。

- [ ] **Step 5: `PlayData` 增加字段与映射**

`PlayData` 主构造加 `val volume: VolumeInfo? = null`，然后：

- `fromPlayUrlData(PlayUrlData)`：构造调用处加 `volume = playUrlData.volume`
- `fromPlayUrlData(ProxyWebPlayUrlData)`：加 `volume = playUrlData.volume`
- `fromPlayUrlData(ProxyAppPlayUrlData)`：加 `volume = playUrlData.volume`
- `fromPlayViewUniteReply`：加 `volume = vodInfo.volumeOrNull?.toVolumeInfo()`
- `fromPgcPlayViewReply`：加 `volume = pgcPlayViewReply.videoInfo.volumeOrNull?.toVolumeInfo()`
- `fromPlayUrlV2Data` 无需改（它委托给 `fromPlayUrlData(PlayUrlData)`）
- `operator fun plus`：加 `volume = volume ?: other.volume`（App 路径合并多编码响应，漏掉会丢元数据）

gRPC proto 的 `VolumeInfo` 没有 `multi_scene_args`，因此 `toVolumeInfo()` 里 `multiSceneArgs = null`：

```kotlin
private fun bilibili.playershared.VolumeInfo.toVolumeInfo() = VolumeInfo(
    measuredI = measuredI, measuredLra = measuredLra, measuredTp = measuredTp,
    measuredThreshold = measuredThreshold, targetOffset = targetOffset,
    targetI = targetI, targetTp = targetTp, multiSceneArgs = null,
)
```

PGC 的 proto 类型是 `bilibili.pgc.gateway.player.v2.VolumeInfo`，同名字段，再写一个同形重载。`volumeOrNull` 由 protobuf-kotlin 为 message 字段自动生成（与现有 `dolbyOrNull` / `dashVideoOrNull` 同一机制），无需手写。

- [ ] **Step 6: 跑测试确认通过**

Run: `./gradlew :bili-api:test --tests "dev.aaa1115910.biliapi.entity.VolumeInfoTest"`
Expected: PASS（3 个用例）。

- [ ] **Step 7: 提交**

```bash
git add bili-api/src/main/kotlin/dev/aaa1115910/biliapi/entity/VolumeInfo.kt \
        bili-api/src/main/kotlin/dev/aaa1115910/biliapi/entity/PlayData.kt \
        bili-api/src/main/kotlin/dev/aaa1115910/biliapi/http/entity/video/PlayUrlResponse.kt \
        bili-api/src/main/kotlin/dev/aaa1115910/biliapi/http/entity/proxy/PlayUrl.kt \
        bili-api/src/test/kotlin/dev/aaa1115910/biliapi/entity/VolumeInfoTest.kt
git commit -m "feat(bili-api): 解析 playurl volume 响度元数据"
```

---

### Task 2: 播放器侧类型与三挡模型

**Files:**
- Create: `player/shared/src/main/kotlin/dev/aaa1115910/bv/player/entity/AudioLoudness.kt`
- Modify: `player/shared/src/main/kotlin/dev/aaa1115910/bv/player/entity/AudioBalanceLevel.kt`
- Modify: `player/shared/src/main/res/values/strings.xml`
- Modify: `app/shared/src/main/kotlin/dev/aaa1115910/bv/util/Prefs.kt`

**Interfaces:**
- Consumes: Task 1 的 `VolumeInfo`。
- Produces: `dev.aaa1115910.bv.player.entity.AudioLoudness`；`dev.aaa1115910.bv.player.entity.AudioBalanceLevel.{Off, Standard, Cinema}`；`fun VolumeInfo.toAudioLoudness(): AudioLoudness`。

- [ ] **Step 1: 新建 `AudioLoudness`**

```kotlin
data class AudioLoudness(
    val measuredI: Double,
    val measuredTp: Double,
    val targetI: Double,
    val targetTp: Double,
    val targetOffset: Double?,
    val normalTargetI: Double?,
    val highDynamicTargetI: Double?,
    val undersizedTargetI: Double?,
)

fun VolumeInfo.toAudioLoudness() = AudioLoudness(
    measuredI = measuredI,
    measuredTp = measuredTp,
    targetI = targetI,
    targetTp = targetTp,
    targetOffset = targetOffset.takeIf { !it.isNaN() },
    normalTargetI = multiSceneArgs?.normalTargetI?.takeIf { !it.isNaN() },
    highDynamicTargetI = multiSceneArgs?.highDynamicTargetI?.takeIf { !it.isNaN() },
    undersizedTargetI = multiSceneArgs?.undersizedTargetI?.takeIf { !it.isNaN() },
)
```

放在 `player/shared`（该模块依赖 `bili-api`），以保住 `player/core` 不 import bili-api 的边界。

- [ ] **Step 2: 改 `AudioBalanceLevel` 为三挡**

```kotlin
enum class AudioBalanceLevel(private val strRes: Int) {
    Off(R.string.video_player_menu_audio_balance_off),
    Standard(R.string.video_player_menu_audio_balance_standard),
    Cinema(R.string.video_player_menu_audio_balance_cinema);

    fun getDisplayName(context: Context) = context.getString(strRes)

    companion object {
        val ordered: List<AudioBalanceLevel> = entries.toList()
    }
}
```

删除 `targetLufs` / `maxGainDb`。已确认全仓库只有枚举自身、`VolumeBalanceAudioProcessor` 和旧测试引用 `Low`/`Medium`/`High`、`targetLufs`、`maxGainDb`，删除后无其他编译影响。

- [ ] **Step 3: 改字符串资源**

`player/shared/src/main/res/values/strings.xml`：删 `video_player_menu_audio_balance_low` / `_medium` / `_high`，加：

```xml
<string name="video_player_menu_audio_balance_standard">标准模式</string>
<string name="video_player_menu_audio_balance_cinema">影音模式</string>
```

`video_player_menu_audio_balance_off`（关闭）与 `video_player_menu_others_audio_balance`（音量均衡）保持不变。

- [ ] **Step 4: 偏好设置迁移到新键**

`Prefs.playerAudioBalanceLevel` 按 **ordinal** 存取。旧序 `Off=0/Low=1/Medium=2/High=3` 与新序 `Off=0/Standard=1/Cinema=2` 不兼容：旧值 `3`（高）会被 `getOrElse` 静默降级为 `Off`，旧值 `2`（中）会变成「影音模式」，与用户原本的选择不符。

因此换新键，让旧值被忽略、统一从默认 `Off` 起步：

```kotlin
val prefPlayerAudioBalanceLevelKey = intPreferencesKey("player_audio_balance_level_v2")
```

`prefPlayerAudioBalanceLevelRequest` 的默认值同步指向新键。`Prefs` 的 getter/setter 代码本身不变。旧键 `player_audio_balance_level` 不再引用即可（残留数据无害）。

- [ ] **Step 5: 编译验证**

Run: `./gradlew :player:shared:compileDebugKotlin`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 6: 提交**

```bash
git add player/shared/src/main/kotlin/dev/aaa1115910/bv/player/entity/AudioLoudness.kt \
        player/shared/src/main/kotlin/dev/aaa1115910/bv/player/entity/AudioBalanceLevel.kt \
        player/shared/src/main/res/values/strings.xml \
        app/shared/src/main/kotlin/dev/aaa1115910/bv/util/Prefs.kt
git commit -m "feat(player): 音量均衡改三挡并新增 AudioLoudness 值类型"
```

---

### Task 3: 纯函数 `AudioBalanceGain`

**Files:**
- Create: `player/core/src/main/kotlin/dev/aaa1115910/bv/player/audio/AudioBalanceGain.kt`
- Test: `player/core/src/test/kotlin/dev/aaa1115910/bv/player/audio/AudioBalanceGainTest.kt`

**Interfaces:**
- Consumes: `AudioBalanceLevel`、`AudioLoudness`。
- Produces: `internal object AudioBalanceGain { fun gainDb(level: AudioBalanceLevel, loudness: AudioLoudness): Double? }`，`null` 表示旁路。

- [ ] **Step 1: 写失败测试**

覆盖 Spec「测试」表全部用例：

```kotlin
class AudioBalanceGainTest {
    private fun loudness(
        measuredI: Double = -20.0, measuredTp: Double = -3.0,
        targetI: Double = -16.0, targetTp: Double = -2.0, targetOffset: Double? = null,
        normalTargetI: Double? = null, highDynamicTargetI: Double? = null,
        undersizedTargetI: Double? = null,
    ) = AudioLoudness(measuredI, measuredTp, targetI, targetTp,
        targetOffset, normalTargetI, highDynamicTargetI, undersizedTargetI)

    @Test fun `off level is bypassed`()
    @Test fun `nan measured i is bypassed`()              // measuredI = NaN
    @Test fun `zero measured i is bypassed`()             // measuredI = 0.0
    @Test fun `under the undersized threshold is bypassed`()   // measuredI=-40, undersized=-30 -> null
    @Test fun `above the undersized threshold is processed`()  // measuredI=-20, undersized=-30 -> 4.0
    @Test fun `standard mode uses normal target`()             // normalTargetI=-16, measuredI=-20 -> 4.0
    @Test fun `standard mode falls back to target i`()         // normalTargetI=null, targetI=-16 -> 4.0
    @Test fun `out of range normal target falls back to target i`()  // normalTargetI=0.0, targetI=-16 -> 4.0
    @Test fun `cinema mode uses high dynamic target`()         // highDynamicTargetI=-20, measuredI=-20 -> 0.0
    @Test fun `target offset wins over target i difference`()  // targetOffset=3.0 -> 3.0（非 4.0）
    @Test fun `falls back to target i difference when offset missing`()  // targetOffset=null -> 4.0
    @Test fun `true peak caps the gain`()                      // measuredTp=-1, targetTp=-2 -> -1.0
    @Test fun `gain is clamped to twelve db`()                 // 目标 +30 -> 12.0
    @Test fun `default target tp caps when target tp missing`()  // measuredTp=-0.5, targetTp=NaN -> -0.5
    @Test fun `default target tp does not cap when harmless`()   // measuredTp=-6, targetTp=NaN -> 4.0
}
```

数值断言统一用 `assertEquals(expected, actual!!, 1e-6)`。

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :player:core:testDebugUnitTest --tests "dev.aaa1115910.bv.player.audio.AudioBalanceGainTest"`
Expected: 编译失败，`AudioBalanceGain` 未定义。

- [ ] **Step 3: 实现 `AudioBalanceGain`**

按 Spec「计算顺序」1–8 步短路实现。私有判定谓词各自对齐 ffmpeg 4.0 的 AVOption 区间：

```kotlin
private const val MAX_GAIN_DB = 12.0
private const val DEFAULT_TARGET_TP = -1.0

private fun Double.inRange(lo: Double, hi: Double) = !isNaN() && this in lo..hi
private fun Double?.validTargetTp(): Double =
    if (this != null && inRange(-9.0, 0.0)) this else DEFAULT_TARGET_TP
```

**目标选择要同时处理「缺失」与「存在但越界」两种无效。** `toAudioLoudness()` 已把 NaN 归一成 `null`，但服务端仍可能给出越界值（如 `normalTargetI = 0.0` 或 `5.0`），此时必须视为无效而不是照用。统一写法：

```kotlin
val target = when (level) {
    AudioBalanceLevel.Standard -> normalTargetI.validTargetI() ?: targetI.validTargetI()
    AudioBalanceLevel.Cinema -> highDynamicTargetI.validTargetI() ?: targetI.validTargetI()
    AudioBalanceLevel.Off -> null
} ?: return null

private fun Double?.validTargetI(): Double? =
    this?.takeIf { !it.isNaN() && it in -99.0..0.0 }
```

第 6 步再取增益：`targetOffset` 通过 `validOffset()`（`[-99,99]`）后优先，否则 `target - measuredI`。第 7 步在 `targetTp` 与 `measuredTp` 均有效时夹取（`targetTp` 缺失走 `DEFAULT_TARGET_TP`，`measuredTp` 无效则跳过夹取）。第 8 步 `coerceIn(-MAX_GAIN_DB, MAX_GAIN_DB)`。

- [ ] **Step 4: 跑测试确认通过**

Run: `./gradlew :player:core:testDebugUnitTest --tests "dev.aaa1115910.bv.player.audio.AudioBalanceGainTest"`
Expected: PASS（15 个用例）。

- [ ] **Step 5: 提交**

```bash
git add player/core/src/main/kotlin/dev/aaa1115910/bv/player/audio/AudioBalanceGain.kt \
        player/core/src/test/kotlin/dev/aaa1115910/bv/player/audio/AudioBalanceGainTest.kt
git commit -m "feat(player): 新增音量均衡静态增益纯函数"
```

---

### Task 4: 重写 `VolumeBalanceAudioProcessor`

**Files:**
- Modify: `player/core/src/main/kotlin/dev/aaa1115910/bv/player/audio/VolumeBalanceAudioProcessor.kt`（整体重写）
- Delete: `player/core/src/main/kotlin/dev/aaa1115910/bv/player/audio/LoudnessMeter.kt`
- Delete: `player/core/src/test/kotlin/dev/aaa1115910/bv/player/audio/LoudnessMeterTest.kt`
- Modify: `player/core/src/test/kotlin/dev/aaa1115910/bv/player/audio/VolumeBalanceAudioProcessorTest.kt`（整体重写）
- Modify: `AGENTS.md`（音频均衡条目）

**Interfaces:**
- Consumes: `AudioBalanceGain.gainDb`、`AudioLoudness`、`AudioBalanceLevel`。
- Produces: `VolumeBalanceAudioProcessor(level: AudioBalanceLevel = AudioBalanceLevel.Off)`，新增 `fun setLoudness(loudness: AudioLoudness?)`，保留 `fun setLevel(level: AudioBalanceLevel)`。

- [ ] **Step 1: 重写测试**

旧用例（三段推进 / anchor / 限制器）全部失效。新用例：

```kotlin
@Test fun `off level passes audio through untouched`()          // 与旧用例同名保留，字节级相等
@Test fun `null loudness passes audio through untouched`()      // setLoudness(null) 后仍直通
@Test fun `invalid measured i never produces a gain`()          // measuredI=NaN -> 输出与输入相等
@Test fun `applies static gain from the first sample`()         // onConfigure 后 snap，首样本即目标增益
@Test fun `ramps to a new level instead of stepping`()          // 换挡后无单样本跳变
@Test fun `handles pcm 16 bit input`()                          // short 路径缩放正确
@Test fun `preserves trailing bytes that do not fill a frame`() // 不足一帧的尾部原样透传
```

数值断言用「输出/输入」幅度比对照 `10^(gainDb/20)`，容差 1%。斜坡用例断言相邻样本差值不超过 `目标增益 * 0.01`。沿用现有文件的辅助函数写法（合成正弦 + `FloatArray` 输入 + 读 `processor.output`）。

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :player:core:testDebugUnitTest --tests "dev.aaa1115910.bv.player.audio.VolumeBalanceAudioProcessorTest"`
Expected: FAIL（旧实现没有 `setLoudness`，编译不过）。

- [ ] **Step 3: 重写处理器**

行为要求：

- `@Volatile` 保护 `level` / `loudness` / `targetGain`（沿用现有风格）。
- `recompute()`：`gainDb = loudness?.let { AudioBalanceGain.gainDb(level, it) }`；`targetGain` 取 `10^(db/20)` 或 `1.0`；`gainStepPerFrame = (targetGain - currentGain) / (sampleRateHz * 0.05)`。
- `queueInput`：`targetGain == 1.0 && currentGain == 1.0` 时**零拷贝直通**；否则逐帧把 `currentGain` 朝 `targetGain` 推进一步（不越过）后乘上去，按 `encoding` 写 float 或 short，并 `coerceIn` 到 ±1.0 / `Short` 范围。不足一帧的尾字节原样透传，最后 `out.flip()`。
- `onConfigure`：记录 `encoding` / `sampleRateHz` / `channelCount`，**`currentGain = targetGain`**（起播即准，不做斜坡），重算步长；仅接受 `ENCODING_PCM_16BIT` 与 `ENCODING_PCM_FLOAT`，其余返回 `AudioProcessor.AudioFormat.NOT_SET`。
- `onFlush`：**`currentGain = targetGain`**（seek/变速后本就是听觉不连续点）。
- `onReset`：`currentGain = targetGain`，清空其余状态。

- [ ] **Step 4: 删除 `LoudnessMeter` 与其测试**

```bash
git rm player/core/src/main/kotlin/dev/aaa1115910/bv/player/audio/LoudnessMeter.kt
git rm player/core/src/test/kotlin/dev/aaa1115910/bv/player/audio/LoudnessMeterTest.kt
```

- [ ] **Step 5: 跑测试确认通过**

Run: `./gradlew :player:core:testDebugUnitTest --tests "dev.aaa1115910.bv.player.audio.*"`
Expected: PASS（`AudioBalanceGainTest` + `VolumeBalanceAudioProcessorTest` 全绿）。

- [ ] **Step 6: 重写 `AGENTS.md` 音频均衡条目**

现条目把三段推进、`anchorLufs`、`MIN_BOOST_LUFS`、`GAIN_REVOKE_STEP_DB`、`ANCHOR_FALL_*`、`WINDOW_BLOCKS` 全部记为「定稿值，不要改回」——这些在新方案里整体作废。改写为：音量均衡是**服务端响度元数据驱动的静态增益**；`AudioBalanceGain` 是唯一策略点（纯函数、有单测）；无元数据即旁路；处理器只做「乘增益 + 50ms 斜坡」；**验收标准仍是真机试听**，汇报时区分单测与真机结论。同时删除 `WINDOW_BLOCKS` / `LoudnessMeter` 的同步约束那一句。

- [ ] **Step 7: 提交**

```bash
git add -A player/core/src/main/kotlin/dev/aaa1115910/bv/player/audio \
           player/core/src/test/kotlin/dev/aaa1115910/bv/player/audio AGENTS.md
git commit -m "refactor(player): 音量均衡改为元数据驱动的静态增益，删除 BS.1770 响度计"
```

---

### Task 5: 播放器接线

**Files:**
- Modify: `player/core/src/main/kotlin/dev/aaa1115910/bv/player/AbstractVideoPlayer.kt:86`
- Modify: `player/core/src/main/kotlin/dev/aaa1115910/bv/player/impl/exo/ExoMediaPlayer.kt:263`

**Interfaces:**
- Consumes: Task 4 的 `setLoudness`。
- Produces: `AbstractVideoPlayer.setAudioLoudness(loudness: AudioLoudness?)`，`ExoMediaPlayer` 覆写它。

- [ ] **Step 1: 加抽象层方法**

`AbstractVideoPlayer` 中 `setAudioBalanceLevel` 之后并列一行：

```kotlin
/** 推送本集的响度元数据，null 表示无元数据（旁路）。可播放中多次调用。 */
open fun setAudioLoudness(loudness: AudioLoudness?) {}
```

- [ ] **Step 2: `ExoMediaPlayer` 覆写转发**

```kotlin
override fun setAudioLoudness(loudness: AudioLoudness?) {
    volumeBalanceProcessor.setLoudness(loudness)
}
```

- [ ] **Step 3: 编译验证**

Run: `./gradlew :player:core:compileDebugKotlin`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 4: 提交**

```bash
git add player/core/src/main/kotlin/dev/aaa1115910/bv/player/AbstractVideoPlayer.kt \
        player/core/src/main/kotlin/dev/aaa1115910/bv/player/impl/exo/ExoMediaPlayer.kt
git commit -m "feat(player): 播放器暴露 setAudioLoudness 接口"
```

---

### Task 6: app 层元数据推送

**Files:**
- Modify: `app/shared/src/main/kotlin/dev/aaa1115910/bv/viewmodel/VideoPlayerV3ViewModel.kt`

**Interfaces:**
- Consumes: Task 1 的 `PlayData.volume`、Task 2 的 `toAudioLoudness()`、Task 5 的 `setAudioLoudness`。

TV 与 Mobile 共用 `VideoPlayerV3ViewModel`（两端的 `VideoPlayerScreen` 都用 `koinViewModel()` 取它），因此只改这一个文件即覆盖两端。

- [ ] **Step 1: 加私有推送方法**

```kotlin
/** 把当前 PlayData 的响度元数据推给播放器；无元数据推 null 以清除上一集的值。 */
private fun pushAudioLoudness(data: PlayData?) {
    videoPlayer?.setAudioLoudness(data?.volume?.toAudioLoudness())
}
```

- [ ] **Step 2: 在 `videoPlayer` setter 内补推**

setter（约 95 行）里赋值后追加一行 `pushAudioLoudness(playData)`。**播放器实例重建时处理器是新的，不补推会丢元数据**。

- [ ] **Step 3: 在两处 `playData` 赋值后推送**

- 约 574 行 `withContext(Dispatchers.Main) { this@VideoPlayerV3ViewModel.playData = playData }` 之后
- 约 1214 行 `withContext(Dispatchers.Main) { this@VideoPlayerV3ViewModel.playData = playData }` 之后

各加一行 `pushAudioLoudness(playData)`。第二处是自动连播/重载路径，**漏掉会让第二集沿用第一集的增益**。

- [ ] **Step 4: 直播路径推 null**

`videoPlayer?.isLive = true`（约 1696 行）所在方法内加 `pushAudioLoudness(null)`，避免直播沿用点播的增益。

- [ ] **Step 5: 编译验证**

Run: `./gradlew :bili-api:compileKotlin :player:shared:compileDebugKotlin :app:shared:compileDebugKotlin :app:tv:compileDebugKotlin :app:mobile:compileDebugKotlin`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 6: 跑既有单测确认无回归**

Run: `./gradlew :player:core:testDebugUnitTest`
Expected: PASS。

- [ ] **Step 7: 提交**

```bash
git add app/shared/src/main/kotlin/dev/aaa1115910/bv/viewmodel/VideoPlayerV3ViewModel.kt
git commit -m "feat(app): 换集与重建时推送响度元数据"
```

---

## 真机验证（不属于任何任务的自动化步骤）

单测只覆盖纯函数与处理器算术。以下五项**必须真机试听**，结论与单测结论分开汇报：

1. 同一 UP 的多个稿件之间切换，响度应基本一致，且**起播即准**（不再有前几秒的爬升）。
2. 切到「影音模式」应整体更响，切回「标准模式」应回落，切换过程无爆音。
3. 各切一次「杜比全景声 / Hi-Res / 普通音轨」，确认**没有削波破音**——这是 Spec「风险 1」的已知残余风险，破音则按 Spec 的对策处理。
4. **自动连播到下一集**：响度应立刻对应该集的元数据，不能沿用上一集的增益。这是 Review Focus #4 的唯一验证手段（该接线点在 ViewModel 里，没有单测源集可挂）。
5. **切换清晰度触发播放器重建**，响度仍应正确——验证 Review Focus #5 的 setter 补推。
