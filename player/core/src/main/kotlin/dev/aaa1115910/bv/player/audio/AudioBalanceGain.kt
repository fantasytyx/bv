package dev.aaa1115910.bv.player.audio

import dev.aaa1115910.bv.player.entity.AudioBalanceLevel
import dev.aaa1115910.bv.player.entity.AudioLoudness

/**
 * 音量均衡的增益策略：由服务端响度元数据算出一个静态增益（dB）。
 *
 * 返回 null 表示本次不处理（旁路）。纯函数，不依赖 Android，因此可以直接单测。
 *
 * 判定顺序刻意与 loudnorm 的线性路径一致：先否掉「不值得处理」的内容，再选目标响度，
 * 取增益，最后用真峰值夹住——夹取是唯一的安全阀，因为本方案没有实时限制器。
 * 各取值范围全部对齐 ffmpeg 4.0 `af_loudnorm.c` 的 AVOption 约束。
 */
internal object AudioBalanceGain {

    fun gainDb(level: AudioBalanceLevel, loudness: AudioLoudness): Double? {
        if (level == AudioBalanceLevel.Off) return null

        val measuredI = loudness.measuredI.usableMeasuredI() ?: return null

        // 内容整体过轻：不抬，避免把小音量素材的底噪一起放大
        val undersized = loudness.undersizedTargetI.usableTargetI()
        if (undersized != null && measuredI < undersized) return null

        val modeTargetI = when (level) {
            AudioBalanceLevel.Standard ->
                loudness.normalTargetI.usableTargetI() ?: loudness.targetI.usableTargetI()

            AudioBalanceLevel.Cinema ->
                loudness.highDynamicTargetI.usableTargetI() ?: loudness.targetI.usableTargetI()

            AudioBalanceLevel.Off -> null
        } ?: return null

        // 一律按「档位目标 - measured_i」算，与 B 站 web 端逐字一致：web 端从不读服务端的
        // target_offset；APP 端虽把它写进 ffmpeg 串，线性路径也会用 target_i - measured_i 覆盖它。
        // 何况 target_offset 是单值、不随档位变化，采用它会让标准/影音算出同一增益、三挡失效。
        val gainDb = modeTargetI - measuredI

        val measuredTp = loudness.measuredTp.usableMeasuredTp()
        val capped =
            if (measuredTp == null) gainDb
            else minOf(gainDb, loudness.targetTp.usableTargetTp() - measuredTp)

        return capped.coerceIn(-MAX_GAIN_DB, MAX_GAIN_DB)
    }

    private const val MAX_GAIN_DB = 12.0

    /** `target_tp` 缺失或越界时用的保守默认真峰值 */
    private const val DEFAULT_TARGET_TP = -1.0

    /** `measured_i` 范围 [-99, 0]，且 0 是「未测量」的默认值 */
    private fun Double.usableMeasuredI(): Double? =
        takeIf { !it.isNaN() && it >= -99.0 && it < 0.0 }

    /** `measured_tp` 范围 [-99, 99]，且 99 是「未测量」的默认值 */
    private fun Double.usableMeasuredTp(): Double? =
        takeIf { !it.isNaN() && it >= -99.0 && it < 99.0 }

    /** 目标响度（`I`）范围 [-70, -5] */
    private fun Double?.usableTargetI(): Double? =
        this?.takeIf { !it.isNaN() && it >= -70.0 && it <= -5.0 }

    /** `target_tp` 范围 [-9, 0] */
    private fun Double?.usableTargetTp(): Double =
        this?.takeIf { !it.isNaN() && it >= -9.0 && it <= 0.0 } ?: DEFAULT_TARGET_TP
}
