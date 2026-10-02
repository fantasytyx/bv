package dev.aaa1115910.bv.util

import dev.aaa1115910.biliapi.http.BiliHttpApi
import dev.aaa1115910.biliapi.repositories.AuthRepository

/** gaia vgate register 下发的极验参数 */
internal data class GeetestChallengeData(
    val gt: String,
    val challenge: String,
)

/** 极验结果提交的结果 */
internal sealed interface GeetestSubmitResult {
    /** 回传的 challenge 与本次注册不一致，对应的 token 已被服务端丢弃 */
    data class StaleChallenge(val expected: String, val actual: String) : GeetestSubmitResult
    data object Success : GeetestSubmitResult
    data class Failure(val message: String, val cause: Throwable) : GeetestSubmitResult
}

/**
 * 播放页与详情页共用的极验（gaia vgate）会话：voucher 去重注册、challenge 校验、
 * 提交 validate 并写入 gaiaVtoken。重试载荷与错误上报通道两端不同，由调用方处理。
 */
internal class GeetestSession(private val authRepository: AuthRepository) {
    private val voucherRegistry = GeetestVoucherRegistry()
    private var pendingToken: String? = null
    private var expectedChallenge: String = ""

    /** 用 v_voucher 换取极验 gt/challenge，同一个 voucher 只会注册一次 */
    suspend fun start(vVoucher: String): GeetestChallengeData {
        val registerResponse = voucherRegistry.registerOnce(vVoucher) { reservedVoucher ->
            BiliHttpApi.gaiaVgateRegister(
                vVoucher = reservedVoucher,
                sessData = authRepository.sessionData,
                csrf = authRepository.biliJct
            ).getResponseData()
        }
        val token = registerResponse.token
        val gt = registerResponse.geetest.gt
        val challenge = registerResponse.geetest.challenge
        if (token.isBlank() || gt.isBlank() || challenge.isBlank()) {
            error("gaia_vgate_register 返回数据不完整")
        }
        pendingToken = token
        expectedChallenge = challenge
        return GeetestChallengeData(gt = gt, challenge = challenge)
    }

    /** 提交极验结果；成功时写入 [AuthRepository.gaiaVtoken] */
    suspend fun submit(
        challenge: String,
        validate: String,
        seccode: String,
    ): GeetestSubmitResult {
        val token = pendingToken ?: return GeetestSubmitResult.Failure(
            message = "验证会话已失效",
            cause = IllegalStateException("极验会话已失效"),
        )
        val resultChallenge = validatedGeetestResultChallengeOrNull(
            expectedChallenge = expectedChallenge,
            resultChallenge = challenge,
        ) ?: return GeetestSubmitResult.StaleChallenge(
            expected = expectedChallenge,
            actual = challenge,
        )
        return runCatching {
            val validateResponse = BiliHttpApi.gaiaVgateValidate(
                token = token,
                geetestChallenge = resultChallenge,
                validate = validate,
                seccode = seccode,
                sessData = authRepository.sessionData,
                csrf = authRepository.biliJct
            ).getResponseData()
            if (validateResponse.isValid != 1) {
                error("验证未通过")
            }
            val griskId = validateResponse.griskId
            if (griskId.isBlank()) {
                error("grisk_id 为空")
            }
            authRepository.gaiaVtoken = griskId
            GeetestSubmitResult.Success
        }.getOrElse { GeetestSubmitResult.Failure(it.localizedMessage ?: "未知错误", it) }
    }

    fun clear() {
        pendingToken = null
        expectedChallenge = ""
    }
}
