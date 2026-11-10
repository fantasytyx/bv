package dev.aaa1115910.bv.tv.manager

import dev.aaa1115910.biliapi.entity.FavoriteFolderChange
import dev.aaa1115910.biliapi.entity.FavoriteFolderMetadata
import dev.aaa1115910.biliapi.entity.FavoriteResourceType
import dev.aaa1115910.biliapi.repositories.CoinRepository
import dev.aaa1115910.biliapi.repositories.FavoriteRepository
import dev.aaa1115910.biliapi.repositories.LikeRepository
import dev.aaa1115910.biliapi.repositories.ToViewRepository
import dev.aaa1115910.bv.util.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.koin.java.KoinJavaComponent.get
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

data class VideoActionState(
    val liked: Boolean = false,
    val coin: Boolean = false,
    val favoriteFolderIds: List<Long> = emptyList()
) {
    /** 收藏状态由归属收藏夹是否为空推导，避免维护两份可能不一致的状态 */
    val favorited: Boolean get() = favoriteFolderIds.isNotEmpty()
}

/**
 * In-memory manager for video user actions (like/favorite/coin).
 * State is keyed by user and canonical aid; favorite resource types are only used for API requests.
 * Network operations delegate to repositories from Koin and update the corresponding flow on success.
 */
object VideoUserActionManager {
    private val stateMap = ConcurrentHashMap<Pair<Long, Long>, MutableStateFlow<VideoActionState>>()
    private val favoriteFoldersMap = ConcurrentHashMap<Long, MutableStateFlow<List<FavoriteFolderMetadata>>>()
    private val fetchMutexMap = ConcurrentHashMap<Long, Mutex>()
    /** Tracks which (uid, aid) pairs already have action state loaded. */
    // ConcurrentHashMap.newKeySet 需 API 24，minSdk 23 上会 NoSuchMethodError，改用 API 9 起的 newSetFromMap
    private val loadedKeys: MutableSet<Pair<Long, Long>> =
        Collections.newSetFromMap(ConcurrentHashMap<Pair<Long, Long>, Boolean>())
    private val stateFetchMutexMap = ConcurrentHashMap<Pair<Long, Long>, Mutex>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun key(uid: Long, aid: Long) = uid to aid

    private fun ensure(aid: Long, uid: Long = Prefs.uid): MutableStateFlow<VideoActionState> {
        val k = key(uid, aid)
        stateMap[k]?.let { return it }
        // putIfAbsent 需 API 24，minSdk 23 上会 NoSuchMethodError
        return synchronized(stateMap) {
            stateMap.getOrPut(k) { MutableStateFlow(VideoActionState()) }
        }
    }

    fun getStateFlow(aid: Long, uid: Long = Prefs.uid): StateFlow<VideoActionState> = ensure(aid, uid)

    fun getFavoriteFoldersFlow(
        uid: Long = Prefs.uid,
        resourceType: FavoriteResourceType = FavoriteResourceType.Video
    ): StateFlow<List<FavoriteFolderMetadata>> {
        val flow = favoriteFoldersMap.getOrPut(uid) { MutableStateFlow(emptyList()) }
        if (flow.value.isEmpty()) {
            scope.launch {
                val mutex = fetchMutexMap.getOrPut(uid) { Mutex() }
                mutex.withLock {
                    if (flow.value.isNotEmpty()) return@launch
                    runCatching {
                        flow.value = get<FavoriteRepository>(FavoriteRepository::class.java)
                            .getAllFavoriteFolderMetadataList(
                                mid = uid,
                                resourceType = resourceType,
                                preferApiType = Prefs.apiType
                            )
                    }
                }
            }
        }
        return flow
    }

    /**
     * Lazy-load video action state (like/favorite/coin) and favorite folder membership.
     * Only the first consumer triggers the request; subsequent consumers read the cached StateFlow.
     *
     * 收藏状态与收藏夹归属都取自带 `rid` 的收藏夹列表（`favState` 标记内容是否在该收藏夹中），
     * 因此不再单独请求 `checkVideoFavoured`；该列表同时写入收藏夹缓存，供收藏夹选择弹窗复用。
     */
    suspend fun ensureStateLoaded(
        aid: Long,
        uid: Long = Prefs.uid,
        resourceType: FavoriteResourceType = FavoriteResourceType.Video
    ) {
        val k = key(uid, aid)
        if (aid <= 0 || !Prefs.isLogin) return
        if (k in loadedKeys) return

        val mutex = stateFetchMutexMap.getOrPut(k) { Mutex() }
        mutex.withLock {
            if (k in loadedKeys) return
            val success = runCatching {
                val likeRepo: LikeRepository = get(LikeRepository::class.java)
                val coinRepo: CoinRepository = get(CoinRepository::class.java)
                val favRepo: FavoriteRepository = get(FavoriteRepository::class.java)

                withContext(Dispatchers.IO) {
                    val liked = likeRepo.checkVideoLike(aid)
                    val coin = coinRepo.checkVideoCoin(aid)
                    val folders = favRepo.getAllFavoriteFolderMetadataList(
                        mid = uid,
                        rid = aid,
                        resourceType = resourceType,
                        preferApiType = Prefs.apiType
                    )
                    val folderIds = folders.filter { it.videoInThisFav }.map { it.id }

                    favoriteFoldersMap.getOrPut(uid) { MutableStateFlow(emptyList()) }.value = folders
                    val flow = ensure(aid, uid)
                    flow.value = flow.value.copy(
                        liked = liked,
                        coin = coin,
                        favoriteFolderIds = folderIds
                    )
                }
            }.isSuccess
            if (success) {
                loadedKeys.add(k)
            }
        }
    }

    suspend fun addLike(
        aid: Long,
        uid: Long = Prefs.uid
    ): Boolean {
        if (aid <= 0) return false
        val likeRepository: LikeRepository = get(LikeRepository::class.java)
        return try {
            withContext(Dispatchers.IO) { likeRepository.addVideoLike(aid = aid) }
            ensure(aid, uid).value = ensure(aid, uid).value.copy(liked = true)
            true
        } catch (_: Exception) {
            false
        }
    }

    suspend fun delLike(
        aid: Long,
        uid: Long = Prefs.uid
    ): Boolean {
        if (aid <= 0) return false
        val likeRepository: LikeRepository = get(LikeRepository::class.java)
        return try {
            withContext(Dispatchers.IO) { likeRepository.delVideoLike(aid = aid) }
            ensure(aid, uid).value = ensure(aid, uid).value.copy(liked = false)
            true
        } catch (_: Exception) {
            false
        }
    }

    suspend fun addCoin(
        aid: Long,
        uid: Long = Prefs.uid
    ): Boolean {
        if (aid <= 0) return false
        val coinRepository: CoinRepository = get(CoinRepository::class.java)
        return try {
            withContext(Dispatchers.IO) { coinRepository.addVideoCoin(aid = aid) }
            ensure(aid, uid).value = ensure(aid, uid).value.copy(coin = true)
            true
        } catch (_: Exception) {
            false
        }
    }

    suspend fun addToView(aid: Long, uid: Long = Prefs.uid): Boolean {
        if (aid <= 0 || uid <= 0) return false
        val toViewRepository: ToViewRepository = get(ToViewRepository::class.java)
        return try {
            withContext(Dispatchers.IO) {
                toViewRepository.addToView(
                    avid = aid,
                    preferApiType = Prefs.apiType
                )
            }
        } catch (_: Exception) {
            false
        }
    }

    suspend fun updateVideoFavoriteFolders(
        aid: Long,
        folderIds: List<Long>,
        uid: Long = Prefs.uid,
        resourceType: FavoriteResourceType = FavoriteResourceType.Video
    ): Boolean {
        if (aid <= 0) return false
        val favoriteRepository: FavoriteRepository = get(FavoriteRepository::class.java)
        val flow = ensure(aid, uid)
        // 调用方可能传入可变列表（弹窗的 SnapshotStateList），必须快照后再比较与保存，
        // 否则状态里会持有调用方的活列表，后续 UI 增删会就地改写"当前收藏夹"，增量变更永远算不出来
        val targetFolderIds = folderIds.toList()
        val change = FavoriteFolderChange.between(
            currentFolderIds = flow.value.favoriteFolderIds.toList(),
            targetFolderIds = targetFolderIds
        )
        if (change.isEmpty) return true
        return try {
            withContext(Dispatchers.IO) {
                favoriteRepository.updateVideoToFavoriteFolder(
                    aid = aid,
                    addMediaIds = change.addMediaIds,
                    delMediaIds = change.delMediaIds,
                    resourceType = resourceType
                )
            }
            flow.value = flow.value.copy(favoriteFolderIds = targetFolderIds)
            true
        } catch (_: Exception) {
            false
        }
    }

    suspend fun delVideoFromFavoriteFolder(
        aid: Long,
        folderId: Long,
        resourceType: FavoriteResourceType = FavoriteResourceType.Video,
        uid: Long = Prefs.uid
    ): Boolean {
        if (aid <= 0) return false
        val favoriteRepository: FavoriteRepository = get(FavoriteRepository::class.java)
        return try {
            withContext(Dispatchers.IO) {
                favoriteRepository.delVideoFromFavoriteFolder(
                    aid = aid,
                    delMediaIds = listOf(folderId),
                    resourceType = resourceType,
                    preferApiType = Prefs.apiType
                )
            }
            val flow = ensure(aid, uid)
            flow.value = flow.value.copy(
                favoriteFolderIds = flow.value.favoriteFolderIds - folderId
            )
            true
        } catch (_: Exception) {
            false
        }
    }

    suspend fun addToDefaultFavoriteFolder(
        aid: Long,
        uid: Long = Prefs.uid,
        resourceType: FavoriteResourceType = FavoriteResourceType.Video
    ): Boolean {
        if (aid <= 0) return false
        val default = getFavoriteFoldersFlow(uid, resourceType).value
            .firstOrNull { it.title == "默认收藏夹" } ?: return false
        return updateVideoFavoriteFolders(aid, listOf(default.id), uid, resourceType)
    }
}
