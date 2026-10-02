package dev.aaa1115910.bv.viewmodel.user

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.aaa1115910.biliapi.entity.ugc.toSmartDate
import dev.aaa1115910.biliapi.http.entity.AuthFailureException
import dev.aaa1115910.biliapi.repositories.ToViewRepository
import dev.aaa1115910.bv.BVApp
import dev.aaa1115910.bv.R
import dev.aaa1115910.bv.entity.carddata.VideoCardData
import dev.aaa1115910.bv.repository.UserRepository
import dev.aaa1115910.bv.util.Prefs
import dev.aaa1115910.bv.util.fInfo
import dev.aaa1115910.bv.util.fWarn
import dev.aaa1115910.bv.util.formatHourMinSec
import dev.aaa1115910.bv.util.toast
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.annotation.KoinViewModel

@KoinViewModel
class ToViewViewModel(
    private val userRepository: UserRepository,
    private val ToViewRepository: ToViewRepository
) : ViewModel() {
    companion object {
        private val logger = KotlinLogging.logger { }
    }

    var histories by mutableStateOf<List<VideoCardData>>(emptyList())
    var noMore by mutableStateOf(false)

    private var cursor = 0L
    private var updating = false
    var deleting by mutableStateOf(false)
        private set

    fun update() {
        viewModelScope.launch(Dispatchers.IO) {
            updateToView()
        }
    }

    private suspend fun updateToView(context: Context = BVApp.context) {
        if (updating || noMore) return
        logger.fInfo { "Updating histories with params [cursor=$cursor, apiType=${Prefs.apiType}]" }
        withContext(Dispatchers.Main) {
            updating = true
        }
        runCatching {
            val data = ToViewRepository.getToView(
                cursor = cursor,
                preferApiType = Prefs.apiType
            )

            data.data.forEach { ToViewItem ->
                val newCard = 
                    VideoCardData(
                        avid = ToViewItem.oid,
                        title = ToViewItem.title,
                        cover = ToViewItem.cover,
                        play = ToViewItem.play,
                        // danmaku = ToViewItem.danmaku, // 视频时长>1小时时 显示不全，所以不显示弹幕数
                        pubTime = ToViewItem.pubdate.toSmartDate(),
                        upName = ToViewItem.author,
                        upId = ToViewItem.authorId,
                        upFace = ToViewItem.authorFace,
                        timeString = if (ToViewItem.progress == -1) context.getString(R.string.play_time_finish)
                        else context.getString(
                            R.string.play_time_history,
                            (ToViewItem.progress * 1000L).formatHourMinSec(),
                            (ToViewItem.duration * 1000L).formatHourMinSec()
                        )
                    )
                withContext(Dispatchers.Main) { histories = histories + newCard }
            }
            //update cursor
            cursor = data.cursor
            logger.fInfo { "Update toview cursor: [cursor=$cursor]" }
            logger.fInfo { "Update histories success" }
            if (cursor == 0L) {
                withContext(Dispatchers.Main) { noMore = true }
                logger.fInfo { "No more toview" }
            }
        }.onFailure {
            logger.fWarn { "Update histories failed: ${it.stackTraceToString()}" }
            when (it) {
                // 会话失效的 toast 与登出由 AuthFailureDetection 全局回调统一处理
                is AuthFailureException -> logger.fInfo { "User auth failure" }

                else -> {}
            }
        }
        withContext(Dispatchers.Main) {
            updating = false
        }
    }

    fun clearData() {
        histories = emptyList()
        cursor = 0L
        noMore = false
        logger.fInfo { "ToView data cleared" }
    }

    fun deleteToView(avid: Long) {
        if (deleting) return
        deleting = true
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val success = ToViewRepository.deleteToView(
                    avid = avid,
                    preferApiType = Prefs.apiType
                )
                if (success) {
                    withContext(Dispatchers.Main) {
                        histories = histories.filter { it.avid != avid }
                    }
                    logger.fInfo { "Delete toview success: avid=$avid" }
                    withContext(Dispatchers.Main) {
                        BVApp.context.getString(R.string.toview_delete_success)
                            .toast(BVApp.context)
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        BVApp.context.getString(R.string.toview_delete_failed)
                            .toast(BVApp.context)
                    }
                }
            }.onFailure {
                logger.fWarn { "Delete toview failed: ${it.stackTraceToString()}" }
                withContext(Dispatchers.Main) {
                    BVApp.context.getString(R.string.toview_delete_failed)
                        .toast(BVApp.context)
                }
            }
            withContext(Dispatchers.Main) {
                deleting = false
            }
        }
    }

    fun clearToView() {
        if (deleting) return
        deleting = true
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val success = ToViewRepository.clearToView(
                    preferApiType = Prefs.apiType
                )
                if (success) {
                    withContext(Dispatchers.Main) {
                        clearData()
                        noMore = true
                    }
                    logger.fInfo { "Clear toview success" }
                    withContext(Dispatchers.Main) {
                        BVApp.context.getString(R.string.toview_clear_success)
                            .toast(BVApp.context)
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        BVApp.context.getString(R.string.toview_clear_failed)
                            .toast(BVApp.context)
                    }
                }
            }.onFailure {
                logger.fWarn { "Clear toview failed: ${it.stackTraceToString()}" }
                withContext(Dispatchers.Main) {
                    BVApp.context.getString(R.string.toview_clear_failed)
                        .toast(BVApp.context)
                }
            }
            withContext(Dispatchers.Main) {
                deleting = false
            }
        }
    }
}