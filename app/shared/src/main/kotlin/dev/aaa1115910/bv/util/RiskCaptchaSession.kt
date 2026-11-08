package dev.aaa1115910.bv.util

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** 一次风控验证：一次性 v_voucher + 触发它的请求快照（快照由宿主定义，这里只透传） */
data class RiskCaptchaRequest<R>(val vVoucher: String, val retry: R)

/**
 * 风控验证的请求生命周期：只关心"待验证凭证 + 重试快照 + 次数预算"。
 * 不持有网络、不持有重试动作、不持有错误文案——那些由各端自己的重试逻辑负责。
 */
class RiskCaptchaSession<R>(private val maxVerifyCount: Int = 2) {
    var request: RiskCaptchaRequest<R>? by mutableStateOf(null)
        private set

    private var verifyCount = 0

    /** 登记待验证请求；返回 false 表示凭证为空，或本会话的验证次数已用完 */
    fun begin(vVoucher: String, retry: R): Boolean {
        val normalized = vVoucher.trim()
        if (normalized.isEmpty()) return false
        if (verifyCount >= maxVerifyCount) return false
        request = RiskCaptchaRequest(normalized, retry)
        return true
    }

    /** 验证通过：计数 +1 并取出重试快照，同时清空待验证状态 */
    fun consume(): R? {
        val current = request ?: return null
        verifyCount++
        request = null
        return current.retry
    }

    /** 取消或失败：清空状态，不消耗预算（未验证就不算一次尝试） */
    fun cancel() {
        request = null
    }

    /** 用户主动切换播放/详情目标：重置次数预算 */
    fun resetBudget() {
        verifyCount = 0
    }
}
