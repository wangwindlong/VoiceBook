package us.wangxy.voicebook.screens.book

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import us.wangxy.voicebook.ui.widget.CenteredProgress
import us.wangxy.voicebook.ui.widget.EndOfListMarker

/** 分页网格尾部：正在加载下一页，或已经到底。 */
fun LazyGridScope.pagingAppendRows(
    append: LoadState,
    itemCount: Int,
    loadingKey: Any? = null,
    endKey: Any? = null,
) {
    if (append is LoadState.Loading) {
        item(key = loadingKey, span = { GridItemSpan(maxLineSpan) }) {
            CenteredProgress(Modifier.fillMaxWidth().padding(12.dp), indicatorSize = 24.dp)
        }
    }
    if (append is LoadState.NotLoading && append.endOfPaginationReached && itemCount > 6) {
        item(key = endKey, span = { GridItemSpan(maxLineSpan) }) {
            EndOfListMarker()
        }
    }
}
