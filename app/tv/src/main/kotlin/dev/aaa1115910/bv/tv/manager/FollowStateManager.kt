package dev.aaa1115910.bv.tv.manager

import dev.aaa1115910.biliapi.repositories.UserRepository
import dev.aaa1115910.bv.util.FollowedUp
import dev.aaa1115910.bv.util.FollowedUpCache
import dev.aaa1115910.bv.util.Prefs
import dev.aaa1115910.bv.util.toFollowedUps
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koin.java.KoinJavaComponent.get
import java.util.concurrent.ConcurrentHashMap

/**
 * 关注状态管理器，用于在不同页面间同步用户关注状态
 * 避免重复调用API获取关注状态
 */
object FollowStateManager {
    // 存储用户关注状态的Map，key为用户mid，value为关注状态
    private val _followStateMap = MutableStateFlow<Map<Long, Boolean>>(emptyMap())
    val followStateMap: StateFlow<Map<Long, Boolean>> = _followStateMap.asStateFlow()

    // 每个 mid 一把锁，保证同一个 mid 只会有一个在途请求
    private val fetchLocks = ConcurrentHashMap<Long, Mutex>()

    /** 缓存同步（可能要拉整条关注列表）用的后台作用域，不随调用方界面销毁而取消 */
    private val cacheScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val userRepository: UserRepository by lazy { get(UserRepository::class.java) }
    
    /**
     * 获取指定用户的关注状态
     * @param mid 用户mid
     * @return 关注状态，null表示未知状态（需要调用API获取）
     */
    fun getFollowState(mid: Long): Boolean? {
        return _followStateMap.value[mid]
    }

    /**
     * 获取或请求指定用户的关注状态，自动去重。
     * 多个调用方对同一 mid 并发调用时，只有第一个会执行 API 请求，
     * 后续调用方等待锁释放后直接读取缓存。
     */
    suspend fun ensureFollowState(mid: Long): Boolean? {
        if (mid <= 0) return null
        getFollowState(mid)?.let { return it }

        val lock = fetchLocks.getOrPut(mid) { Mutex() }
        return lock.withLock {
            // 拿到锁后再查一次缓存，前一个持锁者可能已经写入
            getFollowState(mid)?.let { return@withLock it }

            val result = runCatching {
                userRepository.checkIsFollowing(
                    mid = mid,
                    preferApiType = Prefs.apiType
                )
            }.getOrNull()
            if (result != null) {
                updateFollowState(mid, result)
            }
            result
        }
    }
    
    /**
     * 更新用户关注状态
     * @param mid 用户mid
     * @param isFollowing 是否关注
     */
    fun updateFollowState(mid: Long, isFollowing: Boolean) {
        _followStateMap.value = _followStateMap.value.toMutableMap().apply {
            this[mid] = isFollowing
        }
    }
    
    /**
     * 移除指定用户的关注状态缓存
     * @param mid 用户mid
     */
    fun removeFollowState(mid: Long) {
        _followStateMap.value = _followStateMap.value.toMutableMap().apply {
            remove(mid)
        }
    }

    /**
     * 关注/取消关注成功后的统一入口：立即更新关注状态，并在后台把改动同步进「已关注 UP」缓存，
     * 让动态页左侧面板不必等下一次同步就与关注关系一致。
     *
     * 缓存里存的是完整关注列表，所以关注要带上 [up]（面板展示需要的名字/头像/签名，
     * 调用方页面本来就有这些数据）；拿不到时只更新关注状态，等面板下次同步再补齐。
     * 缓存已过期时 [FollowedUpCache] 会先拉一次服务端列表做基准，所以要提供整条关注列表的取数；
     * 这段可能要发好几个请求，放到后台做，不拖住关注按钮的反馈。
     */
    fun applyFollowChange(mid: Long, isFollowing: Boolean, up: FollowedUp? = null) {
        updateFollowState(mid, isFollowing)
        val uid = Prefs.uid
        if (uid <= 0) return
        cacheScope.launch {
            try {
                val fetch: suspend () -> List<FollowedUp> = {
                    userRepository.getFollowedUsers(
                        mid = uid,
                        preferApiType = Prefs.apiType
                    ).toFollowedUps()
                }
                if (isFollowing) {
                    up?.let { FollowedUpCache.add(uid, it, fetch) }
                } else {
                    FollowedUpCache.remove(uid, mid, fetch)
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                // 缓存同步失败不影响关注动作本身；下次打开面板会按缓存是否过期重新拉取
            }
        }
    }
    
    /**
     * 清空所有关注状态缓存
     */
    fun clearAll() {
        _followStateMap.value = emptyMap()
    }
}
