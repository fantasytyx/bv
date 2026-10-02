# 音量均衡：元数据驱动方案

**日期**：2026-10-02
**状态**：设计已确认，待实现

## 背景

现有实现（`player/core/.../audio/VolumeBalanceAudioProcessor.kt` + `LoudnessMeter.kt`）是**纯本地在线估计**：

- 用 BS.1770-4 K 加权响度计分块统计，5 秒门控窗口填满后才认为测量可代表整段；
- 之后三段推进（窗口填满前只压不抬 → `GAIN_SETTLE_STEP_DB` 限速定档 → 6/12s 慢速维持）；
- 用 `anchorLufs` 天花板、`MIN_BOOST_LUFS` 下限、`GAIN_REVOKE_STEP_DB` 快收回三道约束防「安静前奏被当成整段偏轻」；
- 出口接 -3 dBFS 起、2.5 dB 宽的指数软拐点峰值限制器。

这套逻辑本身是自洽的，但它有两个结构性上限：

1. **必须等**。窗口没填满就不敢提升，所以「不同视频音量不一」至少要几秒才收敛，起播那一刻仍是原始响度。
2. **信息不足**。整段响度、真峰值、动态范围这些量，只有离线全量分析才测得准；在线估计只能拿开头一段去猜整段。

B 站官方两端都没有这个问题——因为它们不用猜：**服务端已经把测量结果算好，随播放地址一起下发**。

## 目标

- 消除不同视频之间的响度差，且不引入抽吸感（pumping）、不改变音色；
- 起播即准，不再有「等几秒才恢复」的过程；
- 与 B 站官方两端的行为语义对齐，便于比对验证；
- 大幅简化实现（删掉在线估计与响度计）。

**非目标**：不支持元数据的来源不做任何均衡（见「关键决策」）。

## 现状调研

### 服务端下发的元数据

播放地址接口的 `video_info` 里带一个 `volume` 对象：

| 字段 | 含义 |
|---|---|
| `measured_i` | 实测综合响度（LUFS） |
| `measured_lra` | 实测响度范围（LU） |
| `measured_tp` | 实测真峰值（dBTP） |
| `measured_threshold` | 实测门限响度（LUFS） |
| `target_i` | 目标综合响度（LUFS） |
| `target_tp` | 目标真峰值（dBTP） |
| `target_offset` | 限制器之前应施加的增益（LU）。**本方案不读取**：它不随档位变化，采用会抹平档位差异 |
| `multi_scene_args.undersized_target_i` | 内容过轻的判定门限 |
| `multi_scene_args.normal_target_i` | 标准模式目标响度 |
| `multi_scene_args.high_dynamic_target_i` | 影音模式目标响度 |

**实测样本**（2026-10-02，`BV17ftE6XEgr`，未登录 web 播放器 `__playinfo__`）：

```json
"volume": {
  "measured_i": -9.8, "measured_lra": 10.8, "measured_tp": 3.8,
  "measured_threshold": -20.2, "target_offset": -0.6,
  "target_i": -14, "target_tp": -1,
  "multi_scene_args": {
    "high_dynamic_target_i": "-24", "normal_target_i": "-14", "undersized_target_i": "-28"
  }
}
```

由此确认三件事：

1. **`volume` 在响应根**（`data.volume`），不是嵌在 `video_info` 里；PGC v2 则位于 `data.video_info.volume`。两者都落在 `PlayUrlData` 形状的对象上，故字段加在 `PlayUrlData` 一处即可覆盖。
2. **`multi_scene_args` 的值是字符串**（`"-24"`），而其余字段是数字。当前 kotlinx 版本对 `Double` 字段能接受字符串形式（`VolumeInfoTest` 已把该形状固化为回归用例）。
3. **`measured_tp` 可以大于 0 dBTP**（样本为 `3.8`，即母带真峰超过满刻度）。因此真峰夹取的取值范围必须允许正值，夹取后增益为负是正确行为——这类响素材本来就要被压。
4. **`high_dynamic_target_i` 比 `normal_target_i` 更轻**（样本 `-24` vs `-14`），因为「保留动态范围」意味着更低的积分响度。所以切到影音模式在这类素材上会**变轻**而不是变响——这是 B 站两端一致的语义（web 的 `DYNAMIC` 档、TV 端菜单的「影音模式」都取该字段），不是本项目的行为偏差。

### B 站 web 端实现

从播放器 JS 的 `audioEffect` 模块挖出的核心逻辑：

```js
// 前置：音效增强生效中则互斥提示「音效增强生效中，音量均衡暂不生效」并返回
// 门限：低于 undersized_target_i 直接不做
if (!(measuredI < undersizedTargetI)) {
  const target    = option === DYNAMIC ? highDynamicTargetI : normalTargetI;
  const residualI = (target - measuredI) * 1;          // 一个固定 dB 值
  connectGain();
  gain.gain.setTargetAtTime(db2gain(residualI), ctx.currentTime, 0.015);
}
```

结论：

- 音频图是 `MediaElementSource → GainNode → destination`，**施加的是一个静态增益**，15 ms 时间常数平滑；
- 实时响度计（AudioWorklet + `IntegratedLoudnessMeter`）只用于埋点上报，**不参与增益决策**；
- 解析层就是上面的门限逻辑，不满足时 `parseLoudnessParams` 直接返回 `null`。

### B 站 TV APP 端实现

同一份服务端元数据，但交给 ijkplayer 的 `libijkffmpeg.so` 执行：

```java
// IjkAudioFilterParams.toAFiltersParamsString()
"aresample=och=2:resampler=soxr,loudnorm=measured_i=..:measured_lra=..:measured_tp=.."
+ ":measured_thresh=..:offset=..:I=..:tp=.."
+ ":fast_dynamic=false:force_linear=true:linear=true:print_format=summary"
```

`force_linear` / `fast_dynamic` 是上游 ffmpeg **没有**的选项（该 so 的选项帮助「offset.set offset gain」与 ffmpeg 4.0 逐字一致，可确定其 base 为 4.0 后打了补丁）。ffmpeg 4.0 的线性路径：

```c
static av_cold int init(AVFilterContext *ctx) {
    if (s->linear) {
        offset    = s->target_i - s->measured_i;
        offset_tp = s->measured_tp + offset;
        if (s->measured_tp != 99 && s->measured_thresh != -70 && ...) {
            if ((offset_tp <= s->target_tp) && (s->measured_lra <= s->target_lra)) {
                s->frame_type = LINEAR_MODE;
                s->offset = offset;          // 线性路径下用户传的 offset 被覆盖
            }
        }
    }
}

case LINEAR_MODE:
    dst[c] = src[c] * s->offset;             // 纯静态增益，没有限制器
```

两点关键：

1. **ffmpeg 的「线性模式」就是纯静态增益、没有限制器**，靠准入条件 `measured_tp + gain <= target_tp` 保证不越真峰。
2. B 站 `force_linear` 默认 `true`，即**强制走这条线性路径**——所以「元数据驱动的静态增益」不是折中，正是官方实际生效的语义。

### 两端共同点与对本项目的启示

| | web 端 | TV APP 端 |
|---|---|---|
| 增益来源 | 服务端 `measured_i` + 目标 | 同左 |
| 增益形态 | 静态 | 静态（线性路径） |
| 实时动态处理 | 无 | 无（`fast_dynamic` 默认关） |
| 真峰保护 | 无（靠服务端目标取值保守） | 准入条件 `offset_tp <= target_tp` |
| 无元数据时 | 不做 | 不做 |

**两端都是元数据驱动的静态增益**，这是它们没有抽吸感的根本原因。

## 关键决策

| # | 决策 | 取舍 |
|---|---|---|
| 1 | **引入服务端元数据**，元数据驱动为主 | 需改 `bili-api` 响应模型 |
| 2 | **无元数据则完全不做均衡**（不回落到在线估计） | 响应不带 `volume` 的来源失去该功能；换来实现大幅简化 |
| 3 | **挡位改为 B 站式三挡**：关闭 / 标准模式 / 影音模式 | 与两端一致；本地 LUFS 常量作废 |
| 4 | **增益算法取「静态增益 + 真峰值夹取」** | 即 ffmpeg 线性路径准入条件的等价形式，纯静态、无限制器 |
| 5 | **不使用 `target_offset`**，一律取 `档位目标 - measured_i` | 它不随档位变化，采用会让标准/影音算出同一增益；B 站两端实际生效的也都是这个式子 |
| 6 | 音轨切换后**一律照用元数据**，不区分音轨 | 与 web 端一致；残余风险见下 |

决策 4 的等价性说明：ffmpeg 是「不满足 `offset_tp <= target_tp` 就退回动态模式」，本方案是「直接把增益夹到 `target_tp - measured_tp`」。由于决策 2 已放弃动态路径，夹取是唯一可行的等价选择，且结果上更保守。

## 设计

### 数据流

```
playurl HTTP 响应 .video_info.volume
  → PlayUrlData.volume            (bili-api，新增 @Serializable DTO)
  → PlayData.volume               (bili-api，4 个构造入口分别映射)
  → AudioLoudness                 (app 层映射到玩家侧值类型)
  → AbstractVideoPlayer.setAudioLoudness()
  → VolumeBalanceAudioProcessor   (纯静态增益)
```

### 增益计算（唯一策略点）

集中在一个不依赖 Android 的纯函数里，便于单测：

```kotlin
internal object AudioBalanceGain {
    /** 返回 null 表示旁路 */
    fun gainDb(level: AudioBalanceLevel, loudness: AudioLoudness): Double?
}
```

### 有效值判定

取值范围全部对齐 ffmpeg 4.0 `loudnorm` 的选项约束（`af_loudnorm.c` 的 AVOption 表）：

| 量 | 有效范围 | 依据 |
|---|---|---|
| `measuredI` | `[-99, 0)` | `measured_i` 选项范围是 `[-99, 0]`，但默认值 `0` 表示未测量，故取半开区间 |
| `measuredTp` | `[-99, 99)` | `measured_tp` 选项范围是 `[-99, 99]`，但默认值 `99` 表示未测量，故取半开区间 |
| `targetI` / `normalTargetI` / `highDynamicTargetI` | `[-70, -5]` | `I` 选项范围 |
| `targetTp` | `[-9, 0]` | `TP` 选项范围 |
| `targetOffset` | — | 本方案不使用；DTO 仍保留该字段留档 |
| `undersizedTargetI` | `[-70, -5]` | 同目标响度（`I`） |

NaN 一律视为无效。`undersizedTargetI` 无效（缺失或越界）时视为「无门限」，不做内容过轻判定。

`targetTp` 无效时用内置默认 `-1.0` dBTP 参与夹取；`measuredTp` 无效时跳过夹取。

### 计算顺序

按顺序短路：

1. `level == Off` → `null`
2. `loudness` 为 `null` → `null`
3. `measuredI` 不在 `[-99, 0)` → `null`（含 NaN；`0` 是「未测量」默认值，同样无效）
4. `undersizedTargetI != null && measuredI < undersizedTargetI` → `null`
5. 选目标：标准模式 → `normalTargetI ?: targetI`；影音模式 → `highDynamicTargetI ?: targetI`；均无效 → `null`
6. `gainDb = 档位目标 - measuredI`
7. 真峰值夹取：`targetTp` 与 `measuredTp` 均有效时 `gainDb = min(gainDb, targetTp - measuredTp)`
8. `gainDb.coerceIn(-12.0, 12.0)`

**第 6 步为什么不读 `target_offset`**：`target_offset` 由服务端按 `video_info` 下发，是**单值**，而 `playurl` 请求里没有任何档位参数，所以它不可能随档位变化。采用它会让标准模式与影音模式算出完全相同的增益，三挡退化成单挡（`AudioLoudness` 因此刻意不带这个字段，从类型上杜绝误用）。

B 站两端实际生效的线性增益也都是 `档位目标 - measured_i`：web 端 JS 从不读 `offset`；APP 端虽然把 `offset=` 写进了 ffmpeg 串，但 ffmpeg 4.0 的线性路径会用 `target_i - measured_i` 覆盖它，而 `target_i`（即 `I=`）正是按档位从 `normal_target_i` / `high_dynamic_target_i` 选出来的。本方案与该语义逐字一致。

`target_offset` 仍保留在 `VolumeInfo`（bili-api 侧）作为留档，方便日后核对。

### 降级表

| 场景 | 行为 |
|---|---|
| 挡位 = 关闭 | 旁路 |
| `loudness == null`（响应无 `volume` / gRPC 无该字段 / 解析失败） | 旁路 |
| `measuredI` 无效（NaN / 0 / 越界） | 旁路 |
| `measuredI < undersizedTargetI` | 旁路 |
| 标准模式缺 `normalTargetI`（gRPC 路径） | 回落 `targetI` |
| 影音模式缺 `highDynamicTargetI`（gRPC 路径） | 回落 `targetI`（此时与标准模式等价） |
| 服务端给了 `target_offset` | **忽略**，仍按 `档位目标 - measuredI`（该字段不参与计算） |
| 缺 `targetTp` | 用内置默认 `-1.0` dBTP 夹取 |
| 缺 `measuredTp` | 跳过夹取，仅靠 ±12 dB 防呆 |
| 直播 | 无 `volume`，旁路 |

### 组件与接口

**`bili-api`**

- 新增 `biliapi/entity/VolumeInfo.kt`：`@Serializable data class VolumeInfo(...)` 与 `MultiSceneArgs(...)`，字段用 `@SerialName` 对应 JSON 键，缺省值用 `Double.NaN`
- `PlayUrlData` 增 `val volume: VolumeInfo? = null`
- `ProxyWebPlayUrlData` / `ProxyAppPlayUrlData` **也各增** `val volume: VolumeInfo? = null`。这两个代理 DTO 与 `PlayUrlData` 字段几乎一一对应，说明它们是同一份 JSON，很可能本就带 `volume`；声明该字段零成本（kotlinx 忽略未知键，JSON 无此键时值为 `null`），能让区域限制的代理线路也拿到元数据
- `PlayData` 增 `val volume: VolumeInfo? = null`，**6 个构造入口**全部映射：

| 入口 | 来源 | 映射方式 |
|---|---|---|
| `fromPlayUrlData(PlayUrlData)` | HTTP UGC | 直接透传 |
| `fromPlayUrlV2Data(PlayUrlV2Data)` | HTTP PGC | 取 `videoInfo.volume` 透传 |
| `fromPlayUrlData(ProxyWebPlayUrlData)` | HTTP 代理（Web） | 直接透传 |
| `fromPlayUrlData(ProxyAppPlayUrlData)` | HTTP 代理（App） | 直接透传 |
| `fromPlayViewUniteReply` | gRPC UGC | 从 `bilibili.playershared.VolumeInfo` 构造，`multiSceneArgs` 恒 `null` |
| `fromPgcPlayViewReply` | gRPC PGC | **恒为 `null`**：PGC v2 的 `VideoInfo` 只有 7 个字段，没有 `volume`（见 `pgc/gateway/player/v2/playurl.proto`），该包也未定义 `VolumeInfo` |

不新增 `PlayLoudness` 规范化实体：`PlayData` 已有直接使用 HTTP 实体的先例（`http.entity.video.ClipInfo`），复用 `VolumeInfo` 可少一个 8 字段重复类型。

**`player/shared`**

```kotlin
// entity/AudioLoudness.kt
data class AudioLoudness(
    val measuredI: Double,
    val measuredTp: Double,
    val targetI: Double,
    val targetTp: Double,
    val normalTargetI: Double?,
    val highDynamicTargetI: Double?,
    val undersizedTargetI: Double?,
)
```

刻意不带 `targetOffset`：它不随档位变化，带上它只会诱使策略去读。原始值仍在 `VolumeInfo` 上。

`AudioBalanceLevel` 改为：

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

`targetLufs` / `maxGainDb` 删除。

映射扩展 `fun VolumeInfo.toAudioLoudness(): AudioLoudness` 放这里（该模块本就依赖 bili-api）。

**`player/core`**

- 新增 `audio/AudioBalanceGain.kt`（纯函数）
- 重写 `audio/VolumeBalanceAudioProcessor.kt`
- 删除 `audio/LoudnessMeter.kt`
- `AbstractVideoPlayer` 增 `open fun setAudioLoudness(loudness: AudioLoudness?) {}`
- `ExoMediaPlayer` 实现转发到处理器
- **不改** `VideoPlayerOptions`：它在 Activity `onCreate` 构建，早于 playurl 返回，元数据那时尚不存在

处理器骨架：

```kotlin
@UnstableApi
internal class VolumeBalanceAudioProcessor(
    level: AudioBalanceLevel = AudioBalanceLevel.Off
) : BaseAudioProcessor() {

    @Volatile private var level: AudioBalanceLevel = level
    @Volatile private var loudness: AudioLoudness? = null

    private var sampleRateHz = 48_000
    private var channelCount = 2
    private var encoding = C.ENCODING_PCM_16BIT

    private var targetGain = 1.0          // 目标线性增益
    private var currentGain = 1.0         // 当前线性增益
    private var gainStepPerFrame = 0.0    // 50ms 斜坡的每帧步长

    fun setLevel(level: AudioBalanceLevel) { ...; recompute() }
    fun setLoudness(value: AudioLoudness?) { ...; recompute() }

    private fun recompute() {
        val db = loudness?.let { AudioBalanceGain.gainDb(level, it) }
        targetGain = if (db == null) 1.0 else 10.0.pow(db / 20.0)
        gainStepPerFrame = (targetGain - currentGain) / (sampleRateHz * RAMP_SECONDS)
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        // 增益恒为 1 时零拷贝直通：关闭挡位 / 无元数据不付任何逐样本开销
        if (targetGain == 1.0 && currentGain == 1.0) { ...直通...; return }
        ...逐帧 currentGain 朝 targetGain 走一步并乘上去...
    }

    override fun onConfigure(f): AudioFormat { ...; currentGain = targetGain }
    override fun onFlush(m) { currentGain = targetGain }
    override fun onReset() { ...; currentGain = targetGain }
}
```

设计说明：

- **旁路零开销**：关闭或无元数据时 `targetGain == currentGain == 1.0`，零拷贝直通，不像旧实现还要逐帧跑 K 加权；
- **起播即准**：`onConfigure` 直接 snap 到目标增益，不做斜坡——这正是相对旧实现的核心收益；
- **斜坡 50 ms、线性而非指数**：与现有代码「指数在起点斜率最大，听感上就是突然变了」的判断一致；该尺度上线性更可预测；
- **`onFlush` 不需要重置增益**：静态增益与播放位置无关，这与旧实现「只丢测量不丢增益」完全不同，逻辑简单一大块。

**app 层接线**

- `app/shared/.../VideoPlayerV3ViewModel.kt`：`playData = playData` 两处（约 574、1214 行）之后抽私有方法推送；`videoPlayer` setter（约 95 行）里也推一次——**播放器实例重建时处理器是新的，元数据会丢**；
- 直播分支推 `null`；
- mobile 端 `VideoPlayerScreen` / `VideoPlayerActivity` 同理；
- 两端都要接：挡位设置两端都有。

### 资源与文档

| 项 | 改动 |
|---|---|
| `strings.xml` | 新增 `video_player_menu_audio_balance_standard`（标准模式）、`..._cinema`（影音模式）；`_low` / `_medium` / `_high` 删除 |
| `AGENTS.md` 音频均衡条目 | **整条重写**。现条目把三段推进、`anchorLufs` 天花板、`MIN_BOOST_LUFS`、`GAIN_REVOKE_STEP_DB`、`ANCHOR_FALL_*`、`WINDOW_BLOCKS` 全部记为「定稿值，不要改回」，这些在新方案里整体作废。新条目应写：元数据驱动、`AudioBalanceGain` 是唯一策略点、验收仍是真机试听 |

## 测试

- `player/core/src/test/.../audio/LoudnessMeterTest.kt` **删除**
- `player/core/src/test/.../audio/VolumeBalanceAudioProcessorTest.kt` **重写**：旧的三段推进 / anchor / 限制器用例全部失效
- 主测试目标是纯函数 `AudioBalanceGain`：

| # | 输入 | 期望 |
|---|---|---|
| 1 | `Off` | `null` |
| 2 | `measuredI = NaN` | `null` |
| 3 | `measuredI = 0.0` | `null` |
| 4 | `measuredI=-40, undersized=-30` | `null` |
| 5 | `measuredI=-20, undersized=-30` | 放行 |
| 6 | 标准 + `normalTargetI=-16, measuredI=-20` | `+4.0` |
| 7 | 标准 + `normalTargetI=null, targetI=-16` | 回落 `+4.0` |
| 8 | 影音 + `highDynamicTargetI=-20, measuredI=-20` | `0.0` |
| 9 | `standard` + `normalTargetI=-16, measuredI=-20` | `+4.0` |
| 10 | `cinema` + `highDynamicTargetI=-16, targetI=-20, measuredI=-20` | `+4.0`，且**与标准模式不同**（守卫用例：证明档位未被任何单值字段抹平） |
| 11 | 目标 `+4`，`measuredTp=-1, targetTp=-2` | `-1.0`（TP 夹取） |
| 12 | 目标 `+30` | `+12.0`（防呆） |
| 13 | `targetTp=null` | 按默认 `-1.0` 夹取 |

- 处理器的斜坡 / 旁路行为按现有方式补单测
- **验收标准仍是真机试听**（沿用 `AGENTS.md` 的规定）；汇报时把「单测结论 / 真机结论」分开写

## 风险与残余问题

1. **音轨切换后元数据可能不匹配**。`volume` 按 `video_info` 下发，不区分音轨；用户切到杜比全景声 / Hi-Res 时 `measured_i` / `measured_tp` 对应的是另一条流，TP 夹取的前提不成立。已决策「照 web 端一律使用」，因本方案无实时限制器，此处**没有兜底**。真机试听若发现破音，对策是「非默认音轨时旁路」或「额外保守余量」。
2. **`measured_tp` 缺失时夹取失效**，理论上可能削波。实际 API 中 `measured_tp` 与 `measured_i` 同时出现，缺失概率极低，暂不处理。
3. **`target_offset` 已被刻意弃用**。它不随档位变化，采用会抹平档位差异，而 B 站两端实际生效的都是 `档位目标 - measured_i`（见「计算顺序」）。若日后发现某些内容在该式下响度明显不对，`VolumeInfo.targetOffset` 仍留档可查——但重新引入它必须同时解决档位失效问题。
4. **gRPC 路径的元数据覆盖度不一致**。gRPC UGC（`playershared.VodInfo`）有 `volume` 但无 `multi_scene_args`，三挡会退化为「标准 = 影音」；**gRPC PGC 完全没有 `volume`，音量均衡会整体旁路**。这是 proto 定义的客观限制，不是缺陷，但 UI 上两挡无差别、以及 PGC 走 App 接口时功能消失，都可能让用户困惑。走 HTTP（`ApiType.Web`，含 Web 代理）时不受影响。

## 明确不做

- 不实现任何实时动态压缩 / 限制器（含保留旧软拐点限制器）——已决策为纯静态；
- 不为无元数据的来源做在线估计兜底——已决策为直接旁路；
- 不移植 ffmpeg `loudnorm`（`player/core` 的 `ffmpegDecoder` 是解码器，Media3 的 `AudioProcessor` 链拿不到 `-af`，等价实现代价远超收益）；
- 不实现 web 端的「音效增强与音量均衡互斥」——本项目没有音效增强后处理（`Audio.ADolbyAtmos` 只是音轨选择，不是增强 DSP）。
