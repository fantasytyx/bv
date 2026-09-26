package dev.aaa1115910.bv.tv.screens.main.ugc

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.aaa1115910.bv.viewmodel.ugc.UgcViewModel

/**
 * 通用UGC内容组件，用于替代所有重复的*Content.kt文件
 */
@Composable
fun GenericUgcContent(
    modifier: Modifier = Modifier,
    lazyGridState: LazyGridState = rememberLazyGridState(),
    ugcViewModel: UgcViewModel
) {
    UgcRegionScaffold(
        modifier = modifier,
        lazyGridState = lazyGridState,
        ugcViewModel = ugcViewModel
    )
}
