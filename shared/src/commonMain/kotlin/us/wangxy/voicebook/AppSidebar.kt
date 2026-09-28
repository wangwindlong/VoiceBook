package us.wangxy.voicebook

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import us.wangxy.voicebook.data.BookRepository
import us.wangxy.voicebook.reader.api.CalibreServer
import kotlin.math.abs
import kotlin.math.roundToInt

/** 左侧侧边栏宽度上限（宽屏时生效），覆盖层与触发区共用。 */
val SidebarWidth = 300.dp

/** 左侧侧边栏占屏宽比例：略小于 2/3，窄屏时优先按此比例收窄。 */
private const val SidebarWidthFraction = 0.62f

/**
 * 松手结算的 fling 速度阈值：低于系统默认（Compose 吸附组件一般取 400dp/s），
 * 轻扫即可按速度方向展开/收起，避免黏滞感。
 */
val SidebarVelocityThreshold = 160.dp

/** 松手结算阈值：拖出可见部分超过该比例则自动展开，否则收起。 */
internal const val SidebarSettleThreshold = 0.4f

/**
 * 侧边栏贴附的一侧。左右侧边栏的收起方向相反：
 * 左侧边栏向左收起（向右展开），右侧边栏向右收起（向左展开）。
 */
enum class SidebarSide { Left, Right }

/**
 * 侧边栏开关/拖拽状态：[progress] 0=收起 1=展开。拖拽期间 snapTo 跟手，
 * 松手按速度或 [SidebarSettleThreshold] 结算；程序化开关走 [animateTo]。
 * [side] 决定速度方向到「展开/收起」的映射，供 [settle] 与面板内拖拽结算共用。
 */
class SidebarState(val side: SidebarSide = SidebarSide.Left) {
    internal val progress = Animatable(0f)

    var open by mutableStateOf(false)
        private set

    /** 侧边栏像素宽度，App 启动时按密度写入，供 dragBy 换算比例。 */
    internal var widthPx = 1f

    /** fling 判定阈值（px/s），App 启动时按密度写入；未写入前速度不参与结算。 */
    internal var velocityThresholdPx = Float.POSITIVE_INFINITY

    /** 拖动 [deltaPx]（正值=向展开方向）。 */
    suspend fun dragBy(deltaPx: Float) {
        progress.snapTo((progress.value + deltaPx / widthPx.coerceAtLeast(1f)).coerceIn(0f, 1f))
    }

    /**
     * 把屏幕方向速度（像素/秒）归一成「向展开方向」的速度：左侧边栏向右为正，
     * 右侧边栏向左为正。速度换算统一走这里，[settle] 的方向判定才不会打架。
     */
    internal fun openVelocity(velocityX: Float): Float =
        if (side == SidebarSide.Left) velocityX else -velocityX

    /**
     * 松手结算：[velocityPxPerSec] 超过阈值时按速度方向主动展开/收起（快速轻扫即可
     * 翻转状态），否则回落到位置阈值。速度先按 [side] 归一，左右侧边栏共用同一套判定。
     */
    suspend fun settle(velocityPxPerSec: Float = 0f) {
        val velocity = openVelocity(velocityPxPerSec)
        val target = when {
            velocity > velocityThresholdPx -> true
            velocity < -velocityThresholdPx -> false
            else -> progress.value >= SidebarSettleThreshold
        }
        open = target
        progress.animateTo(if (target) 1f else 0f, tween(220, easing = FastOutSlowInEasing))
    }

    /** 程序化开/关（顶栏按钮、快捷面板入口等）。 */
    suspend fun animateTo(target: Boolean) {
        open = target
        progress.animateTo(if (target) 1f else 0f, tween(260, easing = FastOutSlowInEasing))
    }
}

/**
 * 左侧侧边栏覆盖层：带背景色的侧边栏面板 + 渐变 scrim。展开后可横拖面板跟手
 * 移动，松手超过阈值主动展开/收起；点击或横拖侧边栏外部（scrim）区域也可收起。
 * 面板宽度按比例自适应：min(62% 屏宽, 300dp)，始终不到 2/3 屏宽。
 */
@Composable
fun SidebarOverlay(
    state: SidebarState,
    onOpenMine: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    // 展开中或收起动画未结束时保持组合，避免拖拽中途被移出
    val visible = state.open || state.progress.value > 0f
    if (!visible) return

    Box(Modifier.fillMaxSize()) {
        // scrim：透明度跟随拖拽进度；点击收起；横拖同样跟手（顺带阻断下层 pager 手势）
        Box(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f * state.progress.value))
                .pointerInput(Unit) {
                    detectTapGestures { scope.launch { state.animateTo(false) } }
                }
                .pointerInput(Unit) {
                    val tracker = VelocityTracker()
                    detectHorizontalDragGestures(
                        onDragStart = { tracker.resetTracking() },
                        onDragEnd = {
                            scope.launch { state.settle(tracker.calculateVelocity().x) }
                        },
                        onDragCancel = { scope.launch { state.settle() } },
                    ) { change, amount ->
                        if (change.isConsumed) return@detectHorizontalDragGestures
                        change.consume()
                        tracker.addPosition(change.uptimeMillis, change.position)
                        scope.launch { state.dragBy(amount) }
                    }
                },
        )

        Surface(
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 8.dp,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .fillMaxHeight()
                .fillMaxWidth(SidebarWidthFraction)
                .widthIn(max = SidebarWidth)
                .onSizeChanged { if (it.width > 0) state.widthPx = it.width.toFloat() }
                .offset { IntOffset(-((1f - state.progress.value) * state.widthPx).roundToInt(), 0) }
                .pointerInput(Unit) {
                    // 面板内横拖跟手：右拖收起、左拖回开，松手按速度/拖动比例结算。
                    // 关键：面板自身跟随位移，change.position 是相对「移动中的面板」的坐标，
                    // 用 addPosition 记录速度会得到近似 0（快速右扫因此收不起）。改用
                    // addPointerInputChange：它基于屏幕绝对坐标并含历史采样，速度才可信。
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val tracker = VelocityTracker()
                        tracker.addPointerInputChange(down)
                        var dragging = false
                        var accumX = 0f
                        var accumY = 0f
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            tracker.addPointerInputChange(change)
                            if (!change.pressed) break
                            val delta = change.positionChange()
                            if (!dragging) {
                                accumX += delta.x
                                accumY += delta.y
                                if (abs(accumX) > viewConfiguration.touchSlop && abs(accumX) > abs(accumY)) {
                                    dragging = true
                                    change.consume()
                                    // 补上越界判定期间的位移，避免起步跳变
                                    scope.launch { state.dragBy(accumX) }
                                }
                            } else {
                                change.consume()
                                scope.launch { state.dragBy(delta.x) }
                            }
                        }
                        if (dragging) {
                            // 面板自身横拖结算：直接交给 state.settle。方向按 state.side 归一
                            //（左侧边栏右滑展开/左滑收起，右侧边栏相反），速度不足阈值时按拖动比例兜底。
                            scope.launch { state.settle(tracker.calculateVelocity().x) }
                        } else {
                            scope.launch { state.animateTo(state.progress.value >= SidebarSettleThreshold) }
                        }
                    }
                },
        ) {
            // 内容避开状态栏，背景色仍铺满全高
            Box(Modifier.statusBarsPadding()) {
                AppSidebarContent(onOpenMine = onOpenMine)
            }
        }
    }
}

/**
 * 全局侧边栏内容（[SidebarOverlay] 的面板主体）：
 * 账号信息区 + 可折叠分组（小工具/小游戏，均为占位）。
 */
@Composable
fun AppSidebarContent(
    onOpenMine: () -> Unit,
) {
    val repository = koinInject<BookRepository>()
    var calibreServer by remember { mutableStateOf<CalibreServer?>(null) }
    LaunchedEffect(Unit) { calibreServer = repository.server() }

    var toolsExpanded by rememberSaveable { mutableStateOf(true) }
    var gamesExpanded by rememberSaveable { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxHeight()
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
    ) {
        Text(
            "工具箱",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(16.dp),
        )
        HorizontalDivider()

        SidebarRow(
            icon = { Icon(Icons.Filled.Person, contentDescription = null) },
            title = "calibre 账号",
            subtitle = calibreServer?.baseUrl ?: "未配置",
            onClick = onOpenMine,
        )
        SidebarRow(
            icon = { Icon(Icons.Filled.AccountCircle, contentDescription = null) },
            title = "应用账号",
            subtitle = "未登录",
            onClick = onOpenMine,
        )
        HorizontalDivider(Modifier.padding(vertical = 8.dp))

        GroupHeader("小工具", toolsExpanded) { toolsExpanded = !toolsExpanded }
        if (toolsExpanded) {
            listOf("计时器", "便签", "随机数").forEach { PlaceholderRow(it) }
        }
        GroupHeader("小游戏", gamesExpanded) { gamesExpanded = !gamesExpanded }
        if (gamesExpanded) {
            listOf("2048", "贪吃蛇").forEach { PlaceholderRow(it) }
        }
    }
}

@Composable
private fun SidebarRow(
    icon: @Composable () -> Unit,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon()
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun GroupHeader(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        Icon(
            if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
            contentDescription = if (expanded) "收起" else "展开",
        )
    }
}

@Composable
private fun PlaceholderRow(label: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Text(
            "开发中",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
