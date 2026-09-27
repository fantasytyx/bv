package dev.aaa1115910.bv.util

import android.content.Context
import dev.aaa1115910.bv.BVApp
import dev.aaa1115910.bv.Blacklist.BlacklistNano
import dev.aaa1115910.bv.BuildConfig
import dev.aaa1115910.bv.R
import dev.aaa1115910.bv.network.AppHttpClient
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.call.body
import io.ktor.client.request.get
import java.io.File
import kotlin.system.exitProcess

object BlacklistUtil {
    private const val BLACKLIST_DIR = "blacklist"
    private const val BLACKLIST_FILENAME = "blacklist.bin"
    private val logger = KotlinLogging.logger {}

    private suspend fun downloadBlacklist(): ByteArray {
        return AppHttpClient.client.get(BuildConfig.BLACKLIST_URL).body()
    }

    suspend fun updateBlacklist(context: Context = BVApp.context) {
        logger.fInfo { "updating blacklist" }
        var blacklistBinary: ByteArray? = null

        // download blacklist binary
        runCatching {
            blacklistBinary = downloadBlacklist()
            check(blacklistBinary != null) { "blacklist is null" }
        }.onFailure {
            logger.warn { "download blacklist failed: ${it.stackTraceToString()}" }
            return
        }

        // validate blacklist binary
        runCatching {
            val blacklist = BlacklistNano.parseFrom(blacklistBinary!!)
            logger.info { "blacklist: [version=${blacklist.version}, count=${blacklist.count}]" }
        }.onFailure {
            logger.warn { "check blacklist validate failed: ${it.stackTraceToString()}" }
            return
        }

        // save blacklist binary
        val blacklistDir = File(context.filesDir, BLACKLIST_DIR)
        if (!blacklistDir.exists()) blacklistDir.mkdirs()
        val blacklistFile = File(blacklistDir, BLACKLIST_FILENAME)
        runCatching {
            blacklistFile.delete()
            blacklistFile.writeBytes(blacklistBinary!!)
            logger.info { "blacklist saved to ${blacklistFile.absolutePath}" }
        }.onFailure {
            logger.warn { "save blacklist failed: ${it.stackTraceToString()}" }
        }
    }

    private fun getBlacklistData(context: Context = BVApp.context): BlacklistNano? {
        var data = context.resources.openRawResource(R.raw.blacklist).readBytes()
        val blacklistFile = File(File(context.filesDir, BLACKLIST_DIR), BLACKLIST_FILENAME)
        if (blacklistFile.exists()) {
            data = blacklistFile.readBytes()
        } else {
            logger.warn { "blacklist file not found" }
        }
        return runCatching {
            BlacklistNano.parseFrom(data)
        }.getOrElse {
            logger.warn { "parse blacklist failed: ${it.stackTraceToString()}" }
            null
        }
    }

    fun checkUid(uid: Long) {
        val blacklist = getBlacklistData()
        if (blacklist == null) {
            logger.warn { "blacklist is null" }
            return
        }
        val uidList = blacklist.uidsList
        if (uidList.isEmpty()) {
            logger.warn { "blacklist uid list is empty" }
            return
        }
        val isBlacklisted = uidList.contains(uid)
        if (isBlacklisted) {
            logger.fInfo { "Uid $uid is blacklisted" }
            Prefs.blacklistUser = true
            exitProcess(0)
        }
    }
}