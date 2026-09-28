package us.wangxy.voicebook.ui.nested

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity

/**
 * 子节点优先的嵌套滚动父级，对应 View 体系的 NestedScrollingParent。
 *
 * LazyRow、LazyColumn、HorizontalPager、scrollable 已经是嵌套滚动子节点
 * （相当于 NestedScrollingChild）：先自己滚，再把没吃掉的位移沿 modifier 链向上交。
 * 父级挂上这个连接即可，不必按页面或某个列表写死。
 *
 * 父级还没参与时，[onPreScroll] 不抢，[onPostScroll] 只收到剩余量。
 * 一旦父级消费过剩余量，同一次手势的后续位移和 fling 都在 [onPreScroll] / [onPreFling]
 * 整段吃掉，底下的列表不再滚动。
 *
 * [onUnconsumedDrag] 的第二个参数表示父级是否已经接住这次手势。
 */
class ChildFirstNestedScrollConnection : NestedScrollConnection {
    var onUnconsumedDrag: (Offset, Boolean) -> Offset = { _, _ -> Offset.Zero }
    var onUnconsumedFling: suspend (Velocity) -> Velocity = { Velocity.Zero }

    private var dragged = false

    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        if (!dragged || source != NestedScrollSource.UserInput || available == Offset.Zero) {
            return Offset.Zero
        }
        onUnconsumedDrag(available, true)
        return available
    }

    override fun onPostScroll(
        consumed: Offset,
        available: Offset,
        source: NestedScrollSource,
    ): Offset {
        if (dragged || source != NestedScrollSource.UserInput || available == Offset.Zero) {
            return Offset.Zero
        }
        val taken = onUnconsumedDrag(available, false)
        if (taken != Offset.Zero) dragged = true
        return taken
    }

    override suspend fun onPreFling(available: Velocity): Velocity {
        if (!dragged) return Velocity.Zero
        dragged = false
        onUnconsumedFling(available)
        return available
    }

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
        if (!dragged) return Velocity.Zero
        dragged = false
        onUnconsumedFling(available)
        return available
    }
}

@Composable
fun rememberChildFirstNestedScroll(
    onUnconsumedDrag: (Offset, Boolean) -> Offset,
    onUnconsumedFling: suspend (Velocity) -> Velocity = { Velocity.Zero },
): ChildFirstNestedScrollConnection {
    val connection = remember { ChildFirstNestedScrollConnection() }
    connection.onUnconsumedDrag = onUnconsumedDrag
    connection.onUnconsumedFling = onUnconsumedFling
    return connection
}
