package dev.aaa1115910.bv.player.audio

import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.tan

/**
 * ITU-R BS.1770-4 响度计。
 *
 * 先用两级 K 加权（高架 + RLB 高通）模拟人耳对频率的敏感度，再按固定时长分块统计
 * 均方，最后用绝对门限与相对门限做门控平均，得到综合响度（LUFS）。
 *
 * 与直接统计 RMS 相比，K 加权不会把低频能量当成响度，因此对低频重的素材不会过度衰减。
 * 滤波系数用双线性变换按实际采样率生成，不依赖 48 kHz 常量表。
 */
internal class LoudnessMeter(
    sampleRateHz: Int,
    private val channelCount: Int,
    windowSeconds: Double = 4.0,
) {
    private val shelfB0: Double
    private val shelfB1: Double
    private val shelfB2: Double
    private val shelfA1: Double
    private val shelfA2: Double

    private val hpA1: Double
    private val hpA2: Double

    /** 每个声道两级滤波的状态，依次为 x1 / x2 / y1 / y2 */
    private val shelfState = Array(channelCount) { DoubleArray(4) }
    private val hpState = Array(channelCount) { DoubleArray(4) }

    private val framesPerBlock: Int
    private val blockMeanSquares: DoubleArray
    private var blockWriteIndex = 0
    private var validBlockCount = 0

    private var blockSumSquares = 0.0
    private var blockFrameCount = 0

    init {
        val fs = max(sampleRateHz, 8_000).toDouble()

        // 第一级：高架滤波器（+4 dB 转折约 1682 Hz）
        val shelfK = tan(PI * SHELF_F0 / fs)
        val vh = 10.0.pow(SHELF_GAIN_DB / 20.0)
        val vb = vh.pow(0.4996667741545416)
        val shelfA0 = 1.0 + shelfK / SHELF_Q + shelfK * shelfK
        shelfB0 = (vh + vb * shelfK / SHELF_Q + shelfK * shelfK) / shelfA0
        shelfB1 = 2.0 * (shelfK * shelfK - vh) / shelfA0
        shelfB2 = (vh - vb * shelfK / SHELF_Q + shelfK * shelfK) / shelfA0
        shelfA1 = 2.0 * (shelfK * shelfK - 1.0) / shelfA0
        shelfA2 = (1.0 - shelfK / SHELF_Q + shelfK * shelfK) / shelfA0

        // 第二级：RLB 高通滤波器（约 38 Hz）
        val hpK = tan(PI * HP_F0 / fs)
        val hpA0 = 1.0 + hpK / HP_Q + hpK * hpK
        hpA1 = 2.0 * (hpK * hpK - 1.0) / hpA0
        hpA2 = (1.0 - hpK / HP_Q + hpK * hpK) / hpA0

        framesPerBlock = max(1, (fs * BLOCK_MS / 1000.0).roundToInt())
        blockMeanSquares = DoubleArray(max(1, (windowSeconds * 1000.0 / BLOCK_MS).roundToInt()))
    }

    /** 对单个采样做 K 加权，返回加权后的值 */
    fun weight(channel: Int, sample: Double): Double {
        val s = shelfState[channel]
        val sx1 = s[0]
        val sx2 = s[1]
        val sy1 = s[2]
        val sy2 = s[3]
        val shelfOut = shelfB0 * sample + shelfB1 * sx1 + shelfB2 * sx2 - shelfA1 * sy1 - shelfA2 * sy2
        s[0] = sample
        s[1] = sx1
        s[2] = shelfOut
        s[3] = sy1

        val h = hpState[channel]
        val hx1 = h[0]
        val hx2 = h[1]
        val hy1 = h[2]
        val hy2 = h[3]
        val hpOut = shelfOut - 2.0 * hx1 + hx2 - hpA1 * hy1 - hpA2 * hy2
        h[0] = shelfOut
        h[1] = hx1
        h[2] = hpOut
        h[3] = hy1
        return hpOut
    }

    /** 提交一帧：参数为该帧所有声道加权值的平方和。返回 true 表示刚凑满一个统计块 */
    fun endFrame(frameSumSquares: Double): Boolean {
        blockSumSquares += frameSumSquares
        blockFrameCount++
        if (blockFrameCount < framesPerBlock) return false
        blockMeanSquares[blockWriteIndex] = blockSumSquares / blockFrameCount
        blockWriteIndex = (blockWriteIndex + 1) % blockMeanSquares.size
        if (validBlockCount < blockMeanSquares.size) validBlockCount++
        blockSumSquares = 0.0
        blockFrameCount = 0
        return true
    }

    /** 当前窗口内的门控综合响度（LUFS），数据不足时返回 null */
    fun loudnessLufs(): Double? {
        if (validBlockCount == 0) return null

        var gatedSum = 0.0
        var gatedCount = 0
        for (i in 0 until validBlockCount) {
            val meanSquare = blockMeanSquares[i]
            if (meanSquare <= 0.0) continue
            if (toLufs(meanSquare) <= ABSOLUTE_GATE_LUFS) continue
            gatedSum += meanSquare
            gatedCount++
        }
        if (gatedCount == 0) return null

        val gatedMeanSquare = gatedSum / gatedCount
        val relativeGate = toLufs(gatedMeanSquare) - RELATIVE_GATE_LU

        var finalSum = 0.0
        var finalCount = 0
        for (i in 0 until validBlockCount) {
            val meanSquare = blockMeanSquares[i]
            if (meanSquare <= 0.0) continue
            val lufs = toLufs(meanSquare)
            if (lufs <= ABSOLUTE_GATE_LUFS || lufs <= relativeGate) continue
            finalSum += meanSquare
            finalCount++
        }
        return if (finalCount == 0) toLufs(gatedMeanSquare) else toLufs(finalSum / finalCount)
    }

    fun reset() {
        shelfState.forEach { it.fill(0.0) }
        hpState.forEach { it.fill(0.0) }
        blockMeanSquares.fill(0.0)
        blockWriteIndex = 0
        validBlockCount = 0
        blockSumSquares = 0.0
        blockFrameCount = 0
    }

    /**
     * 当前窗口内最响的一个统计块（LUFS）。
     *
     * 起始阶段用它代替门控综合响度：宁可估得更响（多衰减一点），
     * 也不要因为开头几块偏安静而先把音量抬上去。
     */
    fun peakBlockLufs(): Double? {
        var peak = Double.NEGATIVE_INFINITY
        for (i in 0 until validBlockCount) {
            val meanSquare = blockMeanSquares[i]
            if (meanSquare <= 0.0) continue
            val lufs = toLufs(meanSquare)
            if (lufs <= ABSOLUTE_GATE_LUFS) continue
            if (lufs > peak) peak = lufs
        }
        return if (peak.isFinite()) peak else null
    }

    private fun toLufs(meanSquare: Double) = LOUDNESS_OFFSET + 10.0 * log10(meanSquare)

    private companion object {
        /** 分块长度，BS.1770 规定为 400 ms，这里取 100 ms 以缩短窗口内响应延迟 */
        const val BLOCK_MS = 100.0

        /** 单声道/立体声的声道加权均为 1.0，因此综合响度只需减去该偏移 */
        const val LOUDNESS_OFFSET = -0.691
        const val ABSOLUTE_GATE_LUFS = -70.0
        const val RELATIVE_GATE_LU = 10.0

        const val SHELF_F0 = 1681.974450955533
        const val SHELF_GAIN_DB = 3.999843853973347
        const val SHELF_Q = 0.7071752369554196

        const val HP_F0 = 38.13547087602444
        const val HP_Q = 0.5003270373238773
    }
}
