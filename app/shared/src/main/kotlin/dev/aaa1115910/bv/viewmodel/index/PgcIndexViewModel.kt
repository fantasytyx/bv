package dev.aaa1115910.bv.viewmodel.index

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import dev.aaa1115910.biliapi.entity.pgc.PgcItem
import dev.aaa1115910.biliapi.entity.pgc.PgcType
import dev.aaa1115910.biliapi.entity.pgc.index.PGC_INDEX_ORDER_FIELD
import dev.aaa1115910.biliapi.entity.pgc.index.PgcIndexData
import dev.aaa1115910.biliapi.entity.pgc.index.PgcIndexOption
import dev.aaa1115910.biliapi.entity.pgc.index.PgcIndexSection
import dev.aaa1115910.biliapi.repositories.PgcRepository
import dev.aaa1115910.bv.BVApp
import dev.aaa1115910.bv.util.addAllWithMainContext
import dev.aaa1115910.bv.util.fError
import dev.aaa1115910.bv.util.toast
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.core.annotation.KoinViewModel

@KoinViewModel
class PgcIndexViewModel(
    private val pgcRepository: PgcRepository,
) : ViewModel() {
    companion object {
        private val logger = KotlinLogging.logger { }
    }

    val indexResultItems = mutableStateListOf<PgcItem>()

    private var updating = false
    private var nextPage = PgcIndexData.PgcIndexPage()
    val noMore get() = nextPage.hasNext.not()

    var pgcType by mutableStateOf(PgcType.Anime)

    var filterSections by mutableStateOf<List<PgcIndexSection>>(emptyList())
    val selectedFilters = mutableStateMapOf<String, PgcIndexOption>()

    val isFilterReady get() = filterSections.isNotEmpty()

    val activeFilterTags: List<String>
        get() = filterSections.mapNotNull { section ->
            val selectedOption = selectedFilters[section.field] ?: return@mapNotNull null
            val defaultOption = section.options.firstOrNull() ?: return@mapNotNull null
            selectedOption
                .takeIf { it.keyword != defaultOption.keyword || it.sort != defaultOption.sort }
                ?.name
        }

    val filterSignature: String
        get() = filterSections.joinToString("&") { section ->
            val selectedOption = selectedFilters[section.field]
            "${section.field}=${selectedOption?.keyword.orEmpty()}:${selectedOption?.sort.orEmpty()}"
        }

    suspend fun changePgcType(pgcType: PgcType) {
        this.pgcType = pgcType
        clearData()
        filterSections = emptyList()
        selectedFilters.clear()

        runCatching {
            pgcRepository.getPgcIndexCondition(pgcType)
        }.onSuccess { conditionData ->
            val sections = conditionData.buildSections()
            selectedFilters.putAll(buildDefaultFilters(sections))
            filterSections = sections
        }.onFailure {
            logger.fError { "Load $pgcType index conditions failed: ${it.stackTraceToString()}" }
            withContext(Dispatchers.Main) {
                "加载 $pgcType 筛选条件失败: ${it.localizedMessage}".toast(BVApp.context)
            }
        }
    }

    fun updateFilter(option: PgcIndexOption) {
        val currentOption = selectedFilters[option.field]
        if (currentOption == option) return
        selectedFilters[option.field] = option
    }

    fun resetFilters() {
        selectedFilters.clear()
        selectedFilters.putAll(buildDefaultFilters(filterSections))
    }

    suspend fun loadMore() {
        if (!isFilterReady) return
        if (!updating) loadData()
    }

    private suspend fun loadData() {
        updating = true
        if (!nextPage.hasNext) {
            updating = false
            return
        }
        runCatching {
            val selectedOrder = selectedFilters[PGC_INDEX_ORDER_FIELD]
                ?: error("PGC index order is not initialized")
            val result = pgcRepository.getPgcIndex(
                pgcType = pgcType,
                order = selectedOrder.keyword,
                sort = selectedOrder.sort ?: "0",
                filters = selectedFilters.entries
                    .asSequence()
                    .filter { it.key != PGC_INDEX_ORDER_FIELD }
                    .associate { it.key to it.value.keyword },
                page = nextPage
            )
            indexResultItems.addAllWithMainContext(result.list)
            nextPage = result.nextPage
            logger.info { "load more $pgcType list success, size: ${result.list.size}" }
        }.onFailure {
            logger.fError { "Load $pgcType index list failed: ${it.stackTraceToString()}" }
            withContext(Dispatchers.Main) {
                "加载 $pgcType 索引失败: ${it.localizedMessage}".toast(BVApp.context)
            }
        }
        updating = false
    }

    private fun buildDefaultFilters(sections: List<PgcIndexSection>): Map<String, PgcIndexOption> {
        return sections.mapNotNull { section ->
            section.options.firstOrNull()?.let { option -> section.field to option }
        }.toMap()
    }

    fun clearData() {
        indexResultItems.clear()
        nextPage = PgcIndexData.PgcIndexPage()
        updating = false
    }
}