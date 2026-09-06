package dev.aaa1115910.bv.util

import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.CancellationException

private const val MAX_CAUSE_CHAIN_DEPTH = 32

/** Returns whether this failure, or one of its causes, is a recoverable connectivity error. */
fun Throwable.isExpectedNetworkFailure(): Boolean = anyCause { error ->
    error is UnknownHostException ||
        error is SocketTimeoutException ||
        error is ConnectException ||
        error is NoRouteToHostException
}

internal fun Throwable.isCancellationFailure(): Boolean = anyCause { error ->
    error is CancellationException
}

private inline fun Throwable.anyCause(predicate: (Throwable) -> Boolean): Boolean {
    var current: Throwable? = this
    repeat(MAX_CAUSE_CHAIN_DEPTH) {
        val error = current ?: return false
        if (predicate(error)) return true
        current = error.cause?.takeUnless { it === error }
    }
    return false
}
