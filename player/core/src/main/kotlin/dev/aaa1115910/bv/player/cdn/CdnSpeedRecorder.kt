package dev.aaa1115910.bv.player.cdn

/**
 * 接收播放过程中实测到的 CDN 吞吐样本，供下次播放时按速度挑选节点。
 *
 * 实现方负责聚合与缓存；播放器只上报原始样本，不做任何决策。
 */
interface CdnSpeedRecorder {
    /**
     * 上报一次实测吞吐。
     *
     * @param host CDN 主机名，由 [cdnHostOf] 从播放地址中解析
     * @param bytesPerSecond 实测吞吐（字节/秒），只统计读取耗时，不含连接建立
     */
    fun onSample(host: String, bytesPerSecond: Long)

    /** 上报一次硬失败（连接被拒、证书校验失败等），用于立即降权，避免下次继续优先选中。 */
    fun onFailure(host: String)
}

/** 从播放地址中解析 CDN 主机名；解析失败返回 null。 */
fun cdnHostOf(url: String): String? = runCatching { android.net.Uri.parse(url).host }.getOrNull()
