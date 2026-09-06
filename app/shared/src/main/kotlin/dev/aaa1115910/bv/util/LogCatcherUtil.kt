package dev.aaa1115910.bv.util

import android.content.Context
import androidx.core.content.pm.PackageInfoCompat
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object LogCatcherUtil {
    private val logger = KotlinLogging.logger("LogCatcher")
    const val LOG_DIR = "crash_logs"
    private const val MANUAL_LOG_PREFIX = "logs_manual"
    private const val CRASH_LOG_PREFIX = "logs_crash"
    private const val MAX_LOG_COUNT = 10
    private const val PREFS_READ_TIMEOUT_MS = 1500L

    private lateinit var appContext: Context
    var manualFiles: List<File> = emptyList()
    var crashFiles: List<File> = emptyList()

    fun installLogCatcher(context: Context) {
        appContext = context.applicationContext ?: context
        runCatching {
            Runtime.getRuntime().exec("logcat -c")
            logger.info { "clear logcat" }
        }
        val originHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, exception ->
            // 无论写日志是否成功，都必须调用 originHandler（Crashlytics 与系统的
            // 兜底处理都在这条链上），否则进程会卡死在崩溃现场
            runCatching {
                logger.error(exception) { "======== UncaughtException ========" }
                writeLogFile(manual = false, thread = thread, exception = exception)
            }
            originHandler?.uncaughtException(thread, exception)
        }
        runCatching { clearOldLogFiles() }
    }

    fun logLogcat(manual: Boolean = false) {
        writeLogFile(manual = manual)
    }

    private fun writeLogFile(
        manual: Boolean,
        thread: Thread? = null,
        exception: Throwable? = null
    ) {
        runCatching {
            val logDir = File(appContext.filesDir, LOG_DIR)
            if (!logDir.exists()) logDir.mkdirs()

            val logFile = File(logDir, createFilename(manual))
            logger.info { "Log file: $logFile" }

            logFile.writer().use { writer ->
                // 堆栈是崩溃日志中最重要的内容，最先写入并立即落盘；
                // 各段独立容错，任何一段失败都不影响其余内容
                if (thread != null && exception != null) {
                    runCatching { writer.writeExceptionInfo(thread, exception) }
                    writer.flush()
                }
                runCatching { writer.writeDeviceInfo() }
                runCatching { writer.writeAppInfo() }
                writer.flush()
                runCatching { writer.writeLogcat() }
            }
        }.onFailure {
            logger.error(it) { "write log to file failed" }
        }
    }

    private fun OutputStreamWriter.writeExceptionInfo(thread: Thread, exception: Throwable) {
        appendLine("======== Exception Info ========")
        appendLine("Thread: ${thread.name} (${thread.id})")
        appendLine("Exception Type: ${exception::class.qualifiedName ?: exception.javaClass.name}")
        appendLine("Message: ${exception.message ?: "<no message>"}")
        appendLine("======== Stack Trace ========")
        appendLine(exception.stackTraceToString())
    }

    private fun OutputStreamWriter.writeDeviceInfo() {
        val info = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        appendLine("======== Device info ========")
        appendLine("App Version: ${info.versionName} (${PackageInfoCompat.getLongVersionCode(info)})")
        appendLine("Android Version: ${android.os.Build.VERSION.RELEASE} (${android.os.Build.VERSION.SDK_INT})")
        appendLine("Device: ${android.os.Build.DEVICE}")
        appendLine("Model: ${android.os.Build.MODEL}")
        appendLine("Manufacturer: ${android.os.Build.MANUFACTURER}")
        appendLine("Brand: ${android.os.Build.BRAND}")
        appendLine("Product: ${android.os.Build.PRODUCT}")
        appendLine("Type: ${android.os.Build.TYPE}")
    }

    private fun OutputStreamWriter.writeAppInfo() {
        appendLine("======== App Prefs ========")
        appendLine(readPrefsSnapshot() ?: "<prefs unavailable>")
    }

    /**
     * Prefs 的 getter 是 runBlocking 的 DataStore 读取，在崩溃现场直接调用可能
     * 抛异常甚至永久挂起（例如崩溃本身就发生在协程/DataStore 中，或 Prefs 尚未
     * 完成初始化），因此放到独立线程限时读取，超时或失败则放弃该段内容
     */
    private fun readPrefsSnapshot(): String? {
        var snapshot: String? = null
        val readThread = Thread {
            snapshot = runCatching {
                buildString {
                    appendLine("Login: ${Prefs.isLogin}")
                    appendLine("Incognito Mode: ${Prefs.incognitoMode}")
                    appendLine("Api Type: ${Prefs.apiType.name}")
                    appendLine("Default Resolution: ${Prefs.defaultQuality}")
                    appendLine("Default Codec: ${Prefs.defaultVideoCodec.name}")
                    appendLine("Default Audio: ${Prefs.defaultAudio.name}")
                    appendLine("Enabled Proxy: ${Prefs.enableProxy}")
                }.trimEnd()
            }.getOrNull()
        }
        readThread.isDaemon = true
        readThread.start()
        readThread.join(PREFS_READ_TIMEOUT_MS)
        return snapshot
    }

    private fun OutputStreamWriter.writeLogcat() {
        appendLine("======== Logs ========")
        val process = ProcessBuilder("logcat", "-t", "10000", "-v", "threadtime")
            .redirectErrorStream(true)
            .start()
        try {
            process.inputStream.bufferedReader().use { reader ->
                reader.forEachLine { appendLine(it) }
            }
        } finally {
            runCatching { process.destroy() }
        }
    }

    private fun createFilename(manual: Boolean): String {
        var filename = ""
        filename += if (manual) MANUAL_LOG_PREFIX else CRASH_LOG_PREFIX
        // 毫秒精度，避免同一秒内多次崩溃时文件互相覆盖
        val date = SimpleDateFormat("yyyy-MM-dd_HH:mm:ss.SSS", Locale.US).format(Date())
        filename += "_$date.log"
        return filename
    }

    fun updateLogFiles() {
        val files = File(appContext.filesDir, LOG_DIR).listFiles()
        manualFiles = files
            ?.filter { it.name.startsWith(MANUAL_LOG_PREFIX) }
            ?.sortedBy { it.lastModified() }
            ?: emptyList()
        crashFiles = files
            ?.filter { it.name.startsWith(CRASH_LOG_PREFIX) }
            ?.sortedBy { it.lastModified() }
            ?: emptyList()
    }

    private fun clearOldLogFiles() {
        updateLogFiles()

        if (manualFiles.size > MAX_LOG_COUNT) {
            manualFiles.take(manualFiles.size - MAX_LOG_COUNT).forEach { it.delete() }
        }
        if (crashFiles.size > MAX_LOG_COUNT) {
            crashFiles.take(crashFiles.size - MAX_LOG_COUNT).forEach { it.delete() }
        }
    }
}