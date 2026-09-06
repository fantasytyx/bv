package dev.aaa1115910.bv.util

/**
 * 播放/详情接口返回的 v_voucher 是"一次性"凭证，同一个 voucher 只能用于一次
 * gaia_vgate register。若服务端重复返回同一个 v_voucher（例如 register 已消耗、
 * 但验证未完成重试仍被风控），不应再次弹窗注册，否则会陷入"验证-重试-再验证"死循环。
 */
internal class VVoucherAlreadyAttemptedException :
    IllegalStateException("播放接口返回了已使用的 v_voucher")

/**
 * 在 gaia register 前预留 voucher。预留后不回收：超时或被取消的请求
 * 仍可能已在服务端消耗掉这个一次性 voucher。
 */
internal fun reserveFreshVVoucher(
    attemptedVVouchers: MutableSet<String>,
    candidate: String,
): String {
    val normalized = candidate.trim()
    check(normalized.isNotEmpty()) { "播放接口返回了空的 v_voucher" }
    if (!attemptedVVouchers.add(normalized)) {
        throw VVoucherAlreadyAttemptedException()
    }
    return normalized
}