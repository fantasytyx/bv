package dev.aaa1115910.bv.network

import android.os.Build
import dev.aaa1115910.bv.network.entity.Release
import io.ktor.client.call.body
import io.ktor.client.content.ProgressListener
import io.ktor.client.plugins.onDownload
import io.ktor.client.request.get
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.url
import io.ktor.client.statement.bodyAsChannel
import io.ktor.util.cio.writeChannel
import io.ktor.utils.io.copyAndClose
import java.io.File

object VlcLibsApi {
    private val client = AppHttpClient.client

    suspend fun getReleases(): List<Release> {
        val result = mutableListOf<Release>()

        runCatching {
            result.addAll(
                client.get("https://api.github.com/repos/aaa1115910/bv-libs/releases")
                    .body<List<Release>>()
            )
        }

        return result
    }

    suspend fun getRelease(vlcVersion: String): Release? {
        return getReleases().firstOrNull { it.tagName == "libvlc-${vlcVersion}" }
    }

    suspend fun downloadFile(
        releaseItem: Release,
        file: File,
        downloadListener: ProgressListener
    ) {
        val fileName = getFileName()
        if (fileName == "") throw IllegalStateException("Not supported abi")

        val downloadUrl = releaseItem.assets
            .firstOrNull { it.name == fileName }?.browserDownloadUrl
            ?: throw IllegalStateException("Not found download url")
        client.prepareRequest {
            url(downloadUrl)
            onDownload(downloadListener)
        }.execute { response ->
            response.bodyAsChannel().copyAndClose(file.writeChannel())
        }
    }

    private fun getFileName(): String {
        return if (Build.SUPPORTED_ABIS.contains("x86_64")) {
            "x86_64.zip"
        } else if (Build.SUPPORTED_ABIS.contains("x86")) {
            "x86.zip"
        } else if (Build.SUPPORTED_ABIS.contains("arm64-v8a")) {
            "arm64-v8a.zip"
        } else if (Build.SUPPORTED_ABIS.contains("armeabi-v7a")) {
            "armeabi-v7a.zip"
        } else {
            ""
        }
    }
}

