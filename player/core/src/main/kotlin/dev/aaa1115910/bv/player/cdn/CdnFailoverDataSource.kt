package dev.aaa1115910.bv.player.cdn

import android.util.Log
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException

private const val TAG = "CdnFailover"

/**
 * 请求是否是被取消的：换播放地址时旧的 MediaSource 会被丢掉，在途请求随之 cancel，
 * OkHttp 抛的是裸的 `InterruptedIOException`。这不是厂商的问题，记成硬失败会把好节点误降权。
 *
 * `SocketTimeoutException` 是 `InterruptedIOException` 的子类，代表连接/读取真的超时，照常算失败。
 */
private fun Throwable.isCancelled(): Boolean =
    generateSequence(this) { it.cause }
        .any { it is InterruptedIOException && it !is SocketTimeoutException }

/**
 * 候选 CDN 地址的失败切换包装。
 *
 * 每个播放会话（一次 [androidx.media3.exoplayer.source.MediaSource]）创建一个实例：
 * 优先使用 [CdnFailoverState.preferredIndex] 指向的候选，`open` 失败则依次换下一个，
 * 成功的下标会被记住，供同一会话后续分片继续使用。
 *
 * 同时在起播阶段采集一次实测吞吐上报给 [CdnSpeedRecorder]。
 */
internal class CdnFailoverDataSourceFactory(
    private val upstreamFactory: DataSource.Factory,
    candidates: List<String>,
    private val recorder: CdnSpeedRecorder? = null,
) : DataSource.Factory {
    private val state = CdnFailoverState(candidates)
    private val sampler = CdnSpeedSampler(recorder)

    init {
        Log.i(TAG, "candidates=${state.candidates}")
    }

    override fun createDataSource(): DataSource =
        CdnFailoverDataSource(
            upstreamFactory = upstreamFactory,
            state = state,
            sampler = sampler,
            recorder = recorder,
        )
}

/** 候选列表与当前首选下标，被同一播放会话内的所有 DataSource 共享。 */
private class CdnFailoverState(candidates: List<String>) {
    val candidates: List<String> = candidates.filter { it.isNotBlank() }

    @Volatile
    var preferredIndex: Int = 0
        private set

    fun prefer(index: Int) {
        preferredIndex = index.coerceIn(0, (candidates.size - 1).coerceAtLeast(0))
    }

    /** 当前候选不可用时推进到下一个，供下次 open 换节点重试。 */
    fun advance() {
        if (candidates.size > 1) preferredIndex = (preferredIndex + 1) % candidates.size
    }
}

/**
 * 起播阶段的一次性吞吐采样。
 *
 * 只统计首个成功打开的候选：累计读取耗时达到 [SAMPLE_BYTES] 或超过 [SAMPLE_WINDOW_NANOS] 即结束，
 * 样本量不足 [MIN_SAMPLE_BYTES] 则丢弃。一次播放会话最多上报一个样本。
 */
private class CdnSpeedSampler(private val recorder: CdnSpeedRecorder?) {
    private val lock = Any()
    private var host: String? = null
    private var bytes: Long = 0L
    private var readNanos: Long = 0L
    private var startedAtNanos: Long = 0L
    private var finished: Boolean = false

    /** 记录采样起点；已有起点或已结束时忽略。 */
    fun begin(host: String) {
        synchronized(lock) {
            if (finished || this.host != null) return
            this.host = host
            bytes = 0L
            readNanos = 0L
            startedAtNanos = System.nanoTime()
        }
    }

    /** 累加一次读取；达到采样条件时结束并上报。 */
    fun add(byteCount: Int, elapsedNanos: Long) {
        synchronized(lock) {
            if (finished || host == null) return
            bytes += byteCount
            readNanos += elapsedNanos
            if (bytes >= SAMPLE_BYTES || System.nanoTime() - startedAtNanos >= SAMPLE_WINDOW_NANOS) {
                finishLocked()
            }
        }
    }

    private fun finishLocked() {
        finished = true
        val host = host ?: return
        if (bytes < MIN_SAMPLE_BYTES || readNanos <= 0L) return
        recorder?.onSample(host, bytes * 1_000_000_000L / readNanos)
    }

    private companion object {
        /** 累计读满这么多字节即结束采样。 */
        const val SAMPLE_BYTES = 2L * 1024 * 1024

        /** 采样窗口上限，避免长视频一直统计。 */
        const val SAMPLE_WINDOW_NANOS = 10L * 1_000_000_000L

        /** 低于这个样本量视为无效（短会话、秒切）。 */
        const val MIN_SAMPLE_BYTES = 512L * 1024
    }
}

private class CdnFailoverDataSource(
    private val upstreamFactory: DataSource.Factory,
    private val state: CdnFailoverState,
    private val sampler: CdnSpeedSampler,
    private val recorder: CdnSpeedRecorder?,
) : DataSource {
    private var upstream: DataSource? = null
    private val transferListeners = ArrayList<TransferListener>(2)

    override fun addTransferListener(transferListener: TransferListener) {
        transferListeners.add(transferListener)
        upstream?.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        closeQuietly()
        val candidates = state.candidates
        if (candidates.isEmpty()) throw IOException("没有可用的 CDN 候选地址")

        // 只有请求的就是候选地址本身（HLS 的 m3u8、progressive 的整文件）才换厂商。
        // HLS 分片是相对 m3u8 解析出来的、地址不在候选列表里，必须原样放行，
        // 否则会把分片请求也改写成 m3u8 地址，直播直接放不出来。
        if (candidates.none { android.net.Uri.parse(it) == dataSpec.uri }) {
            val dataSource = upstreamFactory.createDataSource()
            transferListeners.forEach { dataSource.addTransferListener(it) }
            upstream = dataSource
            return dataSource.open(dataSpec)
        }

        val start = state.preferredIndex
        var lastException: IOException? = null
        for (attempt in candidates.indices) {
            val index = (start + attempt) % candidates.size
            val url = candidates[index]
            val dataSource = upstreamFactory.createDataSource()
            transferListeners.forEach { dataSource.addTransferListener(it) }
            val spec = dataSpec.buildUpon().setUri(android.net.Uri.parse(url)).build()
            try {
                val openedLength = dataSource.open(spec)
                upstream = dataSource
                state.prefer(index)
                cdnHostOf(url)?.let(sampler::begin)
                Log.i(TAG, "open ok [$index/${candidates.size}] $url length=$openedLength")
                return openedLength
            } catch (e: Exception) {
                runCatching { dataSource.close() }
                Log.w(TAG, "open failed [$index/${candidates.size}] $url", e)
                // 失败原因不一定是 IOException（如被包在 ExecutionException 里），统一转成 IOException
                lastException = e as? IOException ?: IOException(e)
                if (!e.isCancelled()) {
                    // 连接被拒、证书校验失败等硬失败：立即降权，避免下次继续优先选中它
                    cdnHostOf(url)?.let { host -> recorder?.onFailure(host) }
                }
            }
        }
        Log.e(TAG, "all candidates failed, giving up")
        throw lastException ?: IOException("所有 CDN 候选地址均无法打开")
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val dataSource = upstream ?: throw IllegalStateException("read() 在 open() 之前被调用")
        val startedAtNanos = System.nanoTime()
        val read =
            try {
                dataSource.read(buffer, offset, length)
            } catch (e: Exception) {
                // 读取中途失败（连接被断、重连时又撞上坏证书等）：换下一个候选，
                // 让 ExoPlayer 的重试落到别的厂商；这里不降权，避免把偶发抖动记成厂商不可用
                state.advance()
                Log.w(TAG, "read failed, next candidate index=${state.preferredIndex}", e)
                throw e
            }
        if (read > 0) sampler.add(read, System.nanoTime() - startedAtNanos)
        return read
    }

    override fun getUri(): android.net.Uri? = upstream?.uri

    override fun close() {
        closeQuietly()
    }

    private fun closeQuietly() {
        runCatching { upstream?.close() }
        upstream = null
    }
}
