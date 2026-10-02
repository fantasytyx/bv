package dev.aaa1115910.bv.util

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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

/**
 * 校验极验结果是否属于当前这次注册。极验面板在失败重试后可能回传另一个
 * challenge，这类结果对应的 token 已被服务端丢弃，提交必然失败，直接丢弃。
 */
internal fun validatedGeetestResultChallengeOrNull(
    expectedChallenge: String,
    resultChallenge: String,
): String? {
    val normalizedResult = resultChallenge.trim()
    if (normalizedResult.isEmpty()) return null
    if (normalizedResult != expectedChallenge.trim()) return null
    return normalizedResult
}

/**
 * v_voucher → gaia register 的串行注册器：同一 voucher 只允许注册一次，
 * 并保证注册请求不会并发发出。播放页与详情页各持有一个实例。
 */
internal class GeetestVoucherRegistry {
    private val mutex = Mutex()
    private val attemptedVVouchers = mutableSetOf<String>()

    /** 持锁跨越 register 网络调用：同一 registry 的注册请求串行发出 */
    suspend fun <T> registerOnce(
        candidate: String,
        register: suspend (vVoucher: String) -> T,
    ): T = mutex.withLock {
        register(reserveFreshVVoucher(attemptedVVouchers, candidate))
    }
}

