package dev.aaa1115910.bv.network

import dev.aaa1115910.bv.util.LogCatcherUtil
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.BufferedOutputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * TV 上导出日志用的极简 HTTP 服务：设备上没有文件管理器，靠手机扫 LogsScreen 的二维码从局域网下载。
 * 只用 ServerSocket，避免引入 Ktor 服务端（会连带 kotlin-reflect、io.ktor.server/network 约 1600 个类）。
 */
object HttpServer {
    private val logger = KotlinLogging.logger("HttpServer")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private const val LOG_API_PREFIX = "/api/logs/"
    private const val MAX_REQUEST_BYTES = 8192
    private const val SOCKET_TIMEOUT_MS = 30_000

    /** 服务未启动时为 0。 */
    @Volatile
    var port: Int = 0
        private set

    fun startServer() {
        if (port != 0) return
        scope.launch {
            val serverSocket = runCatching { ServerSocket(0) }.getOrElse { e ->
                logger.warn(e) { "start log http server failed" }
                return@launch
            }
            port = serverSocket.localPort
            logger.info { "log http server started on port $port" }
            serverSocket.use { socket ->
                while (true) {
                    val client = runCatching { socket.accept() }.getOrElse { e ->
                        logger.warn(e) { "accept connection failed" }
                        return@use
                    }
                    // 单个请求异常不能影响 accept 循环
                    launch {
                        runCatching { client.use(::handle) }
                            .onFailure { logger.warn(it) { "handle request failed" } }
                    }
                }
            }
        }
    }

    private fun handle(socket: Socket) {
        socket.soTimeout = SOCKET_TIMEOUT_MS
        val requestPath = readRequestPath(socket) ?: return
        val path = runCatching { URLDecoder.decode(requestPath, "UTF-8") }.getOrDefault(requestPath)
        when {
            path == "/" -> respondText(socket, "Hello World!")
            path.startsWith(LOG_API_PREFIX) -> respondLogFile(socket, path.removePrefix(LOG_API_PREFIX))
            else -> respondStatus(socket, 404, "Not Found")
        }
    }

    private fun respondLogFile(socket: Socket, rawFileName: String) {
        val fileName = rawFileName.substringBefore('?')
        // 名字来自 URL，只允许与既有日志文件按名字匹配，禁止任何路径成分
        if (fileName.isEmpty() || fileName.any { it == '/' || it == '\\' } || fileName.contains("..")) {
            respondStatus(socket, 400, "Bad Request")
            return
        }
        LogCatcherUtil.updateLogFiles()
        val file = (LogCatcherUtil.crashFiles + LogCatcherUtil.manualFiles).find { it.name == fileName }
        if (file == null || !file.isFile) {
            respondStatus(socket, 404, "Not Found")
            return
        }
        writeResponse(
            socket = socket,
            status = "200 OK",
            headers = listOf(
                "Content-Type" to "application/octet-stream",
                "Content-Disposition" to "attachment; filename=\"${file.name}\""
            ),
            contentLength = file.length()
        ) { out -> file.inputStream().use { it.copyTo(out) } }
    }

    private fun respondText(socket: Socket, text: String) {
        val body = text.toByteArray(StandardCharsets.UTF_8)
        writeResponse(
            socket = socket,
            status = "200 OK",
            headers = listOf("Content-Type" to "text/plain; charset=utf-8"),
            contentLength = body.size.toLong()
        ) { it.write(body) }
    }

    private fun respondStatus(socket: Socket, code: Int, message: String) {
        val body = message.toByteArray(StandardCharsets.UTF_8)
        writeResponse(
            socket = socket,
            status = "$code $message",
            headers = listOf("Content-Type" to "text/plain; charset=utf-8"),
            contentLength = body.size.toLong()
        ) { it.write(body) }
    }

    private fun writeResponse(
        socket: Socket,
        status: String,
        headers: List<Pair<String, String>>,
        contentLength: Long,
        body: (OutputStream) -> Unit
    ) {
        val head = buildString {
            append("HTTP/1.1 ").append(status).append("\r\n")
            headers.forEach { (key, value) -> append(key).append(": ").append(value).append("\r\n") }
            append("Content-Length: ").append(contentLength).append("\r\n")
            append("Connection: close\r\n\r\n")
        }
        val out = BufferedOutputStream(socket.getOutputStream())
        out.write(head.toByteArray(StandardCharsets.US_ASCII))
        body(out)
        out.flush()
    }

    /** 只取请求行；读到请求头结束（空行）为止，避免半开连接把协程挂死。 */
    private fun readRequestPath(socket: Socket): String? {
        val input = socket.getInputStream()
        val buffer = StringBuilder()
        while (buffer.length < MAX_REQUEST_BYTES) {
            val byte = input.read()
            if (byte == -1) return null
            buffer.append(byte.toChar())
            if (buffer.endsWith("\r\n\r\n")) break
        }
        val requestLine = buffer.lineSequence().firstOrNull() ?: return null
        val parts = requestLine.split(' ')
        if (parts.size < 2 || parts[0] != "GET") return null
        return parts[1]
    }
}