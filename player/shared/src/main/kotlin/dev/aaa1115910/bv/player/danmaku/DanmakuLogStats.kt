package dev.aaa1115910.bv.player.danmaku

import android.os.Debug
import java.util.Locale

internal object DanmakuLogStats {
    @Volatile
    var logEnabled: Boolean = true

    private const val BYTES_PER_MB = 1024f * 1024f

    fun memoryUsageSummary(): String {
        val runtime = Runtime.getRuntime()
        val heapUsedBytes = (runtime.totalMemory() - runtime.freeMemory()).coerceAtLeast(0L)
        val heapMaxBytes = runtime.maxMemory().coerceAtLeast(0L)
        val nativeUsedBytes = Debug.getNativeHeapAllocatedSize().coerceAtLeast(0L)
        return String.format(
            Locale.US,
            "heap=%.2f/%.2fMB native=%.2fMB",
            heapUsedBytes / BYTES_PER_MB,
            heapMaxBytes / BYTES_PER_MB,
            nativeUsedBytes / BYTES_PER_MB,
        )
    }
}