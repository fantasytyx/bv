package dev.aaa1115910.bv.viewmodel.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.aaa1115910.biliapi.entity.CollectedFavorite
import dev.aaa1115910.biliapi.entity.CollectedFavoriteType
import dev.aaa1115910.biliapi.entity.ugc.toSmartDate
import dev.aaa1115910.biliapi.entity.user.SpaceArticle
import dev.aaa1115910.biliapi.entity.user.SpaceVideo
import dev.aaa1115910.biliapi.repositories.FavoriteRepository
import dev.aaa1115910.biliapi.repositories.UserRepository
import dev.aaa1115910.bv.entity.carddata.VideoCardData
import dev.aaa1115910.bv.entity.carddata.toVideoCardDataOrNull
import dev.aaa1115910.bv.viewmodel.user.PageResult
import dev.aaa1115910.bv.viewmodel.user.PagingState
import dev.aaa1115910.bv.viewmodel.user.newItems
import kotlinx.coroutines.launch
import org.koin.core.annotation.KoinViewModel

enum class OtherFavoriteTab(val label: String) {
    Articles("图文"),
    Collections("合集/收藏夹")
}

/** 合集与收藏夹的 id 各自独立编号，列表 key 要把类型带上 */
fun CollectedFavorite.itemKey(): String = "${type.name}-$id"

/**
 * 首页「其它收藏」：图文收藏 + 收藏的合集/收藏夹
 *
 * 三份列表各自分页；展开某个合集/收藏夹后，[selectedFolder] 不为空，[contentVideos] 才是当前内容。
 */
@KoinViewModel
class OtherFavoriteViewModel(
    private val favoriteRepository: FavoriteRepository,
    private val userRepository: UserRepository
) : ViewModel() {

    val articles = mutableStateListOf<SpaceArticle>()
    val articlesPaging = PagingState()

    val folders = mutableStateListOf<CollectedFavorite>()
    val foldersPaging = PagingState()

    val contentVideos = mutableStateListOf<VideoCardData>()
    val contentPaging = PagingState()

    var currentTab by mutableStateOf(OtherFavoriteTab.Articles)
        private set
    var selectedFolder by mutableStateOf<CollectedFavorite?>(null)
        private set

    fun selectTab(tab: OtherFavoriteTab) {
        if (currentTab == tab) return
        currentTab = tab
        loadIfEmpty()
    }

    /** 首次进入 tab 或返回列表时调用；已有数据或上次失败的不会重复请求 */
    fun loadIfEmpty() {
        if (selectedFolder != null) {
            if (contentVideos.isEmpty() && contentPaging.error == null) loadContent()
            return
        }
        when (currentTab) {
            OtherFavoriteTab.Articles -> if (articles.isEmpty() && articlesPaging.error == null) {
                loadArticles()
            }

            OtherFavoriteTab.Collections -> if (folders.isEmpty() && foldersPaging.error == null) {
                loadFolders()
            }
        }
    }

    fun loadMore() {
        if (selectedFolder != null) loadContent() else when (currentTab) {
            OtherFavoriteTab.Articles -> loadArticles()
            OtherFavoriteTab.Collections -> loadFolders()
        }
    }

    fun openFolder(folder: CollectedFavorite) {
        // 失效的合集/收藏夹点进来没有内容，界面也不该留下空内容页
        if (folder.invalid) return
        contentVideos.clear()
        contentPaging.reset()
        selectedFolder = folder
        loadContent()
    }

    fun closeContent() {
        contentVideos.clear()
        contentPaging.reset()
        selectedFolder = null
    }

    /** 再次点一级「其它收藏」时重载当前二级 tab */
    fun refresh() {
        closeContent()
        when (currentTab) {
            OtherFavoriteTab.Articles -> {
                articles.clear()
                articlesPaging.reset()
            }

            OtherFavoriteTab.Collections -> {
                folders.clear()
                foldersPaging.reset()
            }
        }
        loadIfEmpty()
    }

    private fun loadArticles() {
        viewModelScope.launch {
            articlesPaging.load(
                fetch = { page, _ ->
                    val data = favoriteRepository.getOpusFavoriteFeed(page)
                    val knownIds = articles.mapTo(mutableSetOf()) { it.id.toString() }
                    PageResult(
                        items = data.articles.newItems(knownIds) { it.id.toString() },
                        hasMore = data.hasMore,
                        nextPage = page + 1
                    )
                },
                append = articles::addAll
            )
        }
    }

    private fun loadFolders() {
        viewModelScope.launch {
            foldersPaging.load(
                fetch = { page, _ ->
                    val data = favoriteRepository.getCollectedFavoriteFolders(page)
                    val knownIds = folders.mapTo(mutableSetOf()) { it.itemKey() }
                    PageResult(
                        items = data.items.newItems(knownIds) { it.itemKey() },
                        hasMore = data.hasMore,
                        nextPage = page + 1
                    )
                },
                append = folders::addAll
            )
        }
    }

    private fun loadContent() {
        val folder = selectedFolder ?: return
        viewModelScope.launch {
            contentPaging.load(
                fetch = { page, _ ->
                    when (folder.type) {
                        // 合集复用 UP 空间页的合集稿件接口：收藏的合集同样按「创建者 mid + season id」取
                        CollectedFavoriteType.Season -> {
                            val data = userRepository.getSeasonArchives(
                                mid = folder.ownerMid,
                                seasonId = folder.id,
                                page = page
                            )
                            val knownIds = contentVideos.mapTo(mutableSetOf()) { it.avid.toString() }
                            PageResult(
                                items = data.videos.newItems(knownIds) { it.aid.toString() }
                                    .map { it.toVideoCardData(folder.upperName) },
                                hasMore = data.hasMore,
                                nextPage = page + 1
                            )
                        }

                        CollectedFavoriteType.Folder -> {
                            val data = favoriteRepository.getFavoriteFolderData(
                                mediaId = folder.id,
                                pageSize = 20,
                                pageNumber = page
                            )
                            val knownIds = contentVideos.mapTo(mutableSetOf()) { it.avid.toString() }
                            PageResult(
                                items = data.medias
                                    .mapNotNull { it.toVideoCardDataOrNull(it.favTime.toSmartDate()) }
                                    .newItems(knownIds) { it.avid.toString() },
                                hasMore = data.hasMore,
                                nextPage = page + 1
                            )
                        }
                    }
                },
                append = contentVideos::addAll
            )
        }
    }

    private fun SpaceVideo.toVideoCardData(upName: String) = VideoCardData(
        avid = aid,
        title = title,
        cover = cover,
        play = play,
        danmaku = danmaku,
        upName = upName,
        upId = authorId,
        time = duration * 1000L,
        pubTime = publishDate.time.toSmartDate(),
        isInteractive = isInteractive,
        isChargingArc = isChargingArc,
        badgeText = chargingArcBadge
    )
}
