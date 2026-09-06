package dev.aaa1115910.bv.util

import io.github.oshai.kotlinlogging.KLogger

fun KLogger.fInfo(msg: () -> Any?) {
    info(msg)
}

fun KLogger.fWarn(msg: () -> Any?) {
    warn(msg)
}

fun KLogger.fDebug(msg: () -> Any?) {
    info(msg)
}

fun KLogger.fError(msg: () -> Any?) {
    error(msg)
}

fun KLogger.fException(throwable: Throwable, msg: () -> Any?) {
    // Coroutine cancellation is expected control flow, not an actionable failure.
    if (throwable.isCancellationFailure()) return
    // Connectivity failures are recoverable and only need a short message.
    if (throwable.isExpectedNetworkFailure()) {
        warn { "$msg: ${throwable.localizedMessage}" }
        return
    }
    warn { "$msg: ${throwable.stackTraceToString()}" }
}

@Suppress("NOTHING_TO_INLINE")
internal inline fun (() -> Any?).toStringSafe(): String {
    return try {
        invoke().toString()
    } catch (e: Exception) {
        ErrorMessageProducer.getErrorLog(e)
    }
}

internal object ErrorMessageProducer {
    fun getErrorLog(e: Exception): String {
        if (System.getProperties().containsKey("kotlin-logging.throwOnMessageError")) {
            throw e
        } else {
            return "Log message invocation failed: $e"
        }
    }
}
