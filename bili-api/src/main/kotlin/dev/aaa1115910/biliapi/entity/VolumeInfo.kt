package dev.aaa1115910.biliapi.entity

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 播放地址响应里的响度元数据（`video_info.volume`），由服务端离线测量后下发。
 *
 * 数值字段缺省为 [Double.NaN]，表示「服务端未给」。注意 ffmpeg loudnorm 里
 * `measured_i = 0` / `measured_tp = 99` 同样是「未测量」的默认值，因此判定是否可用时
 * 既要把 NaN 视为无效，也要把越界值视为无效。
 */
@Serializable
data class VolumeInfo(
    @SerialName("measured_i")
    val measuredI: Double = Double.NaN,
    @SerialName("measured_lra")
    val measuredLra: Double = Double.NaN,
    @SerialName("measured_tp")
    val measuredTp: Double = Double.NaN,
    @SerialName("measured_threshold")
    val measuredThreshold: Double = Double.NaN,
    /**
     * 仅供留档，增益策略不使用它（它不随档位变化，采用会抹掉档位差异）。
     * 注意 gRPC 路径的 proto3 `double` 没有 presence，未填时读出来是 `0.0` 而非 NaN。
     */
    @SerialName("target_offset")
    val targetOffset: Double = Double.NaN,
    @SerialName("target_i")
    val targetI: Double = Double.NaN,
    @SerialName("target_tp")
    val targetTp: Double = Double.NaN,
    @SerialName("multi_scene_args")
    val multiSceneArgs: MultiSceneArgs? = null,
)

/**
 * 多场景目标响度。只有 HTTP web 系播放地址接口会下发，gRPC 接口的 proto 里没有这个字段，
 * 因此走 gRPC 时它恒为 null，非标准/影音两挡会退化成共用 `VolumeInfo.targetI`。
 */
@Serializable
data class MultiSceneArgs(
    @SerialName("undersized_target_i")
    val undersizedTargetI: Double = Double.NaN,
    @SerialName("normal_target_i")
    val normalTargetI: Double = Double.NaN,
    @SerialName("high_dynamic_target_i")
    val highDynamicTargetI: Double = Double.NaN,
)
