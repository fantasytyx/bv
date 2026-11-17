package dev.aaa1115910.bv.viewmodel.user

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.aaa1115910.biliapi.entity.user.FollowedUser
import dev.aaa1115910.biliapi.repositories.UserRepository
import dev.aaa1115910.bv.util.FollowedUpCache
import dev.aaa1115910.bv.util.Prefs
import dev.aaa1115910.bv.util.fInfo
import dev.aaa1115910.bv.util.sortedFollowedUpOrder
import dev.aaa1115910.bv.util.swapListWithMainContext
import dev.aaa1115910.bv.util.toFollowedUps
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.core.annotation.KoinViewModel

@KoinViewModel
class FollowViewModel(
    private val userRepository: UserRepository
) : ViewModel() {
    companion object {
        private val logger = KotlinLogging.logger { }
    }

    var followedUsers = mutableStateListOf<FollowedUser>()
    var updating by mutableStateOf(true)

    init {
        viewModelScope.launch(Dispatchers.IO) {
            initFollowedUsers()
        }
    }

    private suspend fun initFollowedUsers() {
        runCatching {
            logger.fInfo { "Init followed users" }
            val followedUserList = userRepository.getFollowedUsers(
                mid = Prefs.uid,
                preferApiType = Prefs.apiType
            )
            logger.fInfo { "Followed user count: ${followedUserList.size}" }
            // 这里拿到的就是服务端最新的完整关注列表：顺带覆盖动态页面板的缓存并重置缓存时间。
            // 存服务端顺序（不是下面的展示排序），面板按原样展示时才与直接请求的结果一致。
            // 缓存写失败不影响页面展示
            runCatching { FollowedUpCache.save(Prefs.uid, followedUserList.toFollowedUps()) }
                .onFailure { logger.fInfo { "Update followed ups cache failed: ${it.message}" } }
            val sortedUserList = followedUserList.sortedFollowedUpOrder()
            followedUsers.swapListWithMainContext(sortedUserList)
            logger.fInfo { "Load followed user finish" }
        }
        updating = false
    }
}