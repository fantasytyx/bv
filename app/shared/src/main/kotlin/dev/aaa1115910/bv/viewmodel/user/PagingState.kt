package dev.aaa1115910.bv.viewmodel.user

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 一页数据：`items` 已按列表当前内容去重，可直接追加；`hasMore` 为服务端给出的结论 */
class PageResult<T>(
    val items: List<T>,
    val hasMore: Boolean,
    val nextPage: Int = 0,
    val nextOffset: String = ""
)

/**
 * 新一页里还没出现过的条目。
 *
 * 没有 id 的条目无法判重，直接保留；[knownIds] 会被就地更新，便于在一次请求内去重。
 */
fun <T> List<T>.newItems(knownIds: MutableSet<String>, id: (T) -> String?): List<T> =
    filter { item -> id(item)?.let(knownIds::add) ?: true }

/**
 * 一个列表的分页状态：加载中、错误、是否还有下一页，以及页码与游标。
 *
 * 首页、图文、动态、合集、合集内稿件的分页逻辑完全一样，差别只在请求本身，
 * 所以串行化、丢弃过期响应、落错误都收在这里，调用方只提供「怎么请求」和「追加到哪」。
 */
class PagingState {
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var hasMore by mutableStateOf(true)
        private set

    private var page by mutableIntStateOf(1)
    private var offset by mutableStateOf("")
    private var token = 0

    val canLoad get() = !loading && hasMore
    val currentPage get() = page

    /** 清空列表内容与游标；进行中的请求回来后会因为 token 变化被丢弃 */    fun reset() {
        token++
        loading = false
        error = null
        hasMore = true
        page = 1
        offset = ""
    }

    /**
     * 加载下一页。返回是否成功拿到数据。
     *
     * 一整页都是重复内容时视为到底（`items` 为空 → `hasMore = false`），
     * 避免服务端持续返回 `has_more` 时空转。
     */
    suspend fun <T> load(
        fetch: suspend (page: Int, offset: String) -> PageResult<T>,
        append: (List<T>) -> Unit
    ): Boolean {
        if (!canLoad) return false
        val requestToken = token
        loading = true
        error = null
        try {
            val result = withContext(Dispatchers.IO) { fetch(page, offset) }
            if (requestToken != token) return false
            append(result.items)
            page = result.nextPage
            offset = result.nextOffset
            hasMore = result.hasMore && result.items.isNotEmpty()
            return true
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            if (requestToken == token) error = exception.message ?: "加载失败"
            return false
        } finally {
            if (requestToken == token) loading = false
        }
    }
}
