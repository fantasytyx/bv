package dev.aaa1115910.bv.util

import android.content.Context
import android.os.Build
import coil.ImageLoader
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.decode.SvgDecoder
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.CachePolicy
import kotlinx.coroutines.Dispatchers
import java.io.File

/**
 * Coil ImageLoader 配置工具类
 * 优化多线程并发加载图片性能
 */
object CoilConfig {

    private const val MEMORY_CACHE_PERCENT = 0.25 // 使用可用内存的 25%
    private const val DISK_CACHE_SIZE = 512L * 1024 * 1024 // 512MB 磁盘缓存
    private const val DISK_CACHE_DIRECTORY = "image_cache"

    // 抓取保持并发：缓存未命中时不必让每个可见卡片的 DNS/HTTP/磁盘读取串行化。
    // 只有解码是单车道，大型 ImageDecoder 任务不会互相争抢，硬件位图上传被自然间隔开，
    // 从而降低解码与纹理上传尖峰。
    private val tvImageFetchDispatcher by lazy {
        Dispatchers.IO.limitedParallelism(4)
    }

    private val tvImageDecodeDispatcher by lazy {
        Dispatchers.IO.limitedParallelism(1)
    }

    /**
     * 创建优化后的 ImageLoader
     *
     * 优化点：
     * 1. 配置多线程并发加载（使用 IO 调度器）
     * 2. 配置内存缓存策略
     * 3. 配置磁盘缓存策略
     * 4. 支持 GIF 和 SVG 解码
     * 5. 开启网络请求优化
     */
    fun createImageLoader(context: Context): ImageLoader {
        val builder = ImageLoader.Builder(context)
        if (DeviceUtil.isTvDevice(context)) {
            builder
                .fetcherDispatcher(tvImageFetchDispatcher)
                .decoderDispatcher(tvImageDecodeDispatcher)
        } else {
            // 使用 IO 调度器进行多线程并发加载
            builder.dispatcher(Dispatchers.IO)
        }

        return builder
            // 配置内存缓存
            .memoryCache {
                MemoryCache.Builder(context)
                    .maxSizePercent(MEMORY_CACHE_PERCENT)
                    .build()
            }
            // 配置磁盘缓存
            .diskCache {
                DiskCache.Builder()
                    .directory(File(context.cacheDir, DISK_CACHE_DIRECTORY))
                    .maxSizeBytes(DISK_CACHE_SIZE)
                    .build()
            }
            // 配置缓存策略
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .networkCachePolicy(CachePolicy.ENABLED)
            // 配置图片解码器
            .components {
                // GIF 解码器
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    add(ImageDecoderDecoder.Factory())
                } else {
                    add(GifDecoder.Factory())
                }
                // SVG 解码器
                add(SvgDecoder.Factory())
            }
            // 启用 crossfade 动画
            .crossfade(true)
            .crossfade(200)
            // 允许使用硬件位图以提高性能
            .allowHardware(true)
            // 尊重请求中的 CacheControl 头
            .respectCacheHeaders(true)
            .build()
    }
}

