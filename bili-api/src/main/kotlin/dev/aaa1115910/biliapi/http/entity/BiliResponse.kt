package dev.aaa1115910.biliapi.http.entity

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.Serializable

/**
 * @param code 0：成功 -101：账号未登录 -400：参数错误 -401：非法访问 -403：访问权限不足
 */
@Serializable
data class BiliResponse<T>(
    val code: Int,
    val message: String,
    val ttl: Int? = null,
    val data: T? = null,
    val result: T? = null
) {
    companion object {
        private val logger = KotlinLogging.logger {}
    }
    
    init {
        when (code) {
            0 -> {}
            -101 -> {
                logger.error { "请求失败，账号未登录: $message (code: $code)" }
                // 会话失效时统一回调，由 App 层执行自动登出
                BiliAuthFailureHandler.notify(message)
            }
            -352 -> logger.error { "请求失败，风控异常: $message (code: $code)" }
            else -> logger.error { "请求失败: $message (code: $code)" }
        }
    }

    @Throws()
    fun getResponseData(): T {
        when (code) {
            0 -> {}
            -101 -> throw AuthFailureException(message)
            -352 -> throw RiskControlException(message)
            87008 -> throw IllegalStateException("该视频为专属视频，需要充电才能观看 (code: $code)")
            else -> throw IllegalStateException(message)
        }
        check(data != null || result != null) { "response data and result are both null" }
        data?.let { return it }
        result?.let { return it }
        error("response data and result are both null, and code should not run here")
    }
}

object BiliAuthFailureHandler {
    private val logger = KotlinLogging.logger {}

    @Volatile
    var onAuthFailure: ((String) -> Unit)? = null

    fun notify(message: String) {
        runCatching {
            onAuthFailure?.invoke(message)
        }.onFailure {
            logger.warn(it) { "Handle auth failure failed" }
        }
    }
}

@Serializable
data class BiliResponseWithoutData(
    val code: Int,
    val message: String,
    val ttl: Int? = null
) {
    companion object {
        private val logger = KotlinLogging.logger {}
    }
    
    init {
        when (code) {
            0 -> {}
            -101 -> {
                logger.error { "请求失败，账号未登录: $message (code: $code)" }
                // 会话失效时统一回调，由 App 层执行自动登出
                BiliAuthFailureHandler.notify(message)
            }
            -352 -> logger.error { "请求失败，风控异常: $message (code: $code)" }
            else -> logger.error { "请求失败: $message (code: $code)" }
        }
    }

    @Throws()
    fun requireSuccess() {
        when (code) {
            0 -> {}
            -101 -> throw AuthFailureException(message)
            -352 -> throw RiskControlException(message)
            else -> throw IllegalStateException(message)
        }
    }
}

@Suppress("unused")
class AuthFailureException : RuntimeException {
    constructor() : super()
    constructor(message: String?) : super(message)
    constructor(message: String?, cause: Throwable?) : super(message, cause)
    constructor(cause: Throwable?) : super(cause)
}

@Suppress("unused")
class RiskControlException : RuntimeException {
    constructor() : super()
    constructor(message: String?) : super(message)
    constructor(message: String?, cause: Throwable?) : super(message, cause)
    constructor(cause: Throwable?) : super(cause)
}

/**
 * 风控 v_voucher 异常
 *
 * 当 API 返回 code=0 但 data 中仅包含 v_voucher 时抛出，
 * 需要通过 Geetest 验证后使用返回的 grisk_id 作为 gaia_vtoken 重试请求。
 */
class VVoucherException(val vVoucher: String) : RuntimeException("risk control v_voucher: $vVoucher")