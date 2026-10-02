package dev.aaa1115910.bv.player.entity

import dev.aaa1115910.biliapi.entity.VolumeInfo

/**
 * 本集的响度元数据，供增益策略计算静态增益。
 *
 * 与 [VolumeInfo] 的区别是「服务端未给」的表示：bili-api 侧是 NaN，这里可空字段是 null；
 * 另外**刻意不带 `target_offset`**——它不随档位变化，采用它会抹掉档位差异，且 B 站两端
 * 实际生效的都是「档位目标 - `measuredI`」。原始值仍可在 `VolumeInfo` 上读到。
 *
 * 非空字段保留原始值（可能是 NaN），有效性判定留给策略层——它还要区分「缺失」与「存在但越界」。
 */
data class AudioLoudness(
    val measuredI: Double,
    val measuredTp: Double,
    val targetI: Double,
    val targetTp: Double,
    val normalTargetI: Double?,
    val highDynamicTargetI: Double?,
    val undersizedTargetI: Double?,
)

/**
 * 把服务端元数据映射为播放器侧值类型。
 *
 * 放在 `player/shared` 而不是 `player/core`：扩展的接收者是 bili-api 类型，
 * 而 `player/core` 刻意不依赖 bili-api（见 AGENTS.md）。
 */
fun VolumeInfo.toAudioLoudness() = AudioLoudness(
    measuredI = measuredI,
    measuredTp = measuredTp,
    targetI = targetI,
    targetTp = targetTp,
    normalTargetI = multiSceneArgs?.normalTargetI?.takeIf { !it.isNaN() },
    highDynamicTargetI = multiSceneArgs?.highDynamicTargetI?.takeIf { !it.isNaN() },
    undersizedTargetI = multiSceneArgs?.undersizedTargetI?.takeIf { !it.isNaN() },
)
