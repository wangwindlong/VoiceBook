package us.wangxy.voicebook

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import us.wangxy.voicebook.ui.UiPrefsController
import kotlin.math.abs
import kotlin.math.roundToInt

/** 快捷面板的一个动作。 */
class QuickAction(
    val id: String,
    val label: String,
    val icon: ImageVector,
    val perform: () -> Unit,
)

/** 松手结算的 fling 速度阈值：超过则直接按速度方向收起/展开。 */
private val PanelVelocityThreshold = 120.dp

/** 右侧快捷面板宽度上限（宽屏时生效）。 */
private val PanelWidth = 300.dp

/** 右侧快捷面板占屏宽比例：不超过 1/2。 */
private const val PanelWidthFraction = 0.5f

/**
 * 右侧悬浮快捷面板：贴右缘的把手（单击或左拖展开；纵拖移位并把位置按屏高比例
 * 持久化），面板内右滑跟手收起（松手按速度/阈值回弹或滑出），点关闭/scrim 收起；
 * 展开状态由外部持有（[expanded]/[onExpandedChange]），便于端点手势层左滑唤出。
 * 「编辑」模式可开关动作并排序，配置持久化。
 * 手势只响应把手/面板自身区域，不与底部 tab 的翻页手势冲突。
 */
@Composable
fun QuickPanelOverlay(
    uiPrefs: UiPrefsController,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    actions: List<QuickAction>,
) {
    val prefs by uiPrefs.prefs.collectAsStateWithLifecycle()
    var containerHeightPx by remember { mutableIntStateOf(1) }
    var handleHeightPx by remember { mutableIntStateOf(1) }
    // 把手位置提升到本层：展开/收起切换会重组，若留在把手内部会随 !expanded 分支一起丢失。
    // 这里的 handleRatio 只是本层的临时状态，用来在拖动中即时跟随手指（不读会持续刷新的 prefs，
    // 避免每帧回写 Store 导致把手跟随抖动/失灵）；真正的持久化在松手时一次性写入。
    var handleRatio by remember { mutableFloatStateOf(0.5f) }
    var ratioInitialized by remember { mutableStateOf(false) }
    LaunchedEffect(prefs.quickPanelOffsetY) {
        if (!ratioInitialized) {
            handleRatio = prefs.quickPanelOffsetY.takeIf { it in 0f..1f } ?: 0.5f
            ratioInitialized = true
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { if (it.height > 0) containerHeightPx = it.height },
    ) {
        if (!expanded) {
            QuickPanelHandle(
                ratio = handleRatio,
                onRatioChange = { handleRatio = it },
                onRatioSettled = { uiPrefs.setQuickPanelOffsetY(it) },
                containerHeightPx = containerHeightPx,
                onHeightChanged = { handleHeightPx = it },
                onExpand = { onExpandedChange(true) },
            )
        } else {
            QuickPanelBody(
                uiPrefs = uiPrefs,
                actions = actions,
                onCollapse = { onExpandedChange(false) },
            )
        }
    }
}

@Composable
private fun BoxScope.QuickPanelHandle(
    ratio: Float,
    onRatioChange: (Float) -> Unit,
    onRatioSettled: (Float) -> Unit,
    containerHeightPx: Int,
    onHeightChanged: (Int) -> Unit,
    onExpand: () -> Unit,
) {
    var handleHeightPx by remember { mutableIntStateOf(1) }
    var leftwardAccum by remember { mutableFloatStateOf(0f) }
    var currentRatio by remember { mutableFloatStateOf(ratio) }
    currentRatio = ratio

    Box(
        Modifier
            .align(Alignment.CenterEnd)
            .onSizeChanged { handleHeightPx = it.height; onHeightChanged(it.height) }
            .offset {
                // 顶部留 2% 余量；ratio 以把手中心近似
                IntOffset(0, ((ratio - 0.06f) * containerHeightPx).roundToInt().coerceAtLeast(0))
            }
            .width(20.dp)
            .height(96.dp)
            .background(
                MaterialTheme.colorScheme.secondaryContainer,
                RoundedCornerShape(topStart = 10.dp, bottomStart = 10.dp),
            )
            .pointerInput(Unit) { detectTapGestures { onExpand() } }
            .pointerInput(containerHeightPx) {
                detectDragGestures(
                    onDragStart = { leftwardAccum = 0f },
                    onDragEnd = { onRatioSettled(currentRatio) },
                    onDragCancel = { onRatioSettled(currentRatio) },
                ) { change, amount ->
                    change.consume()
                    val dx = amount.x
                    val dy = amount.y
                    if (abs(dy) > abs(dx)) {
                        currentRatio = (currentRatio + dy / spanPx(containerHeightPx, handleHeightPx)).coerceIn(0.02f, 0.98f)
                        onRatioChange(currentRatio)
                    } else if (dx < 0) {
                        leftwardAccum += dx
                        if (leftwardAccum < -60f) onExpand()
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "‹",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

private fun spanPx(containerHeightPx: Int, handleHeightPx: Int): Float =
    (containerHeightPx - handleHeightPx).coerceAtLeast(1).toFloat()

@Composable
private fun BoxScope.QuickPanelBody(
    uiPrefs: UiPrefsController,
    actions: List<QuickAction>,
    onCollapse: () -> Unit,
) {
    val prefs by uiPrefs.prefs.collectAsStateWithLifecycle()
    var editMode by remember { mutableStateOf(false) }
    var panelWidthPx by remember { mutableIntStateOf(1) }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    // 0=完全展开，1=完全滑出屏幕；面板内滑动跟手，松手按阈值回弹或滑出
    val progress = remember { Animatable(0f) }

    fun slideOutAndCollapse() {
        scope.launch {
            progress.animateTo(1f, tween(220, easing = FastOutSlowInEasing))
            onCollapse()
        }
    }

    // scrim：透明度跟随拖拽进度，点击收起并阻断下层 pager 手势
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.25f * (1f - progress.value)))
            .pointerInput(Unit) { detectTapGestures { slideOutAndCollapse() } },
    )

    AnimatedVisibility(
        visible = true,
        enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(),
        exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut(),
        modifier = Modifier.align(Alignment.CenterEnd),
    ) {
        Surface(
            shape = RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp),
            tonalElevation = 4.dp,
            modifier = Modifier
                .fillMaxWidth(PanelWidthFraction)
                .widthIn(max = PanelWidth)
                .fillMaxHeight(0.85f)
                .onSizeChanged { if (it.width > 0) panelWidthPx = it.width }
                .offset { IntOffset((panelWidthPx * progress.value).roundToInt(), 0) }
                .pointerInput(Unit) {
                    // 面板内任意位置横拖跟手：右滑收起、左滑拉回，松手按速度/拖动比例结算。
                    // 与左侧边栏一致：正速度（向右，即收起方向）直接收起；反向需超过阈值才拉回；
                    // 无速度时用拖动比例兜底。
                    // 关键：面板跟随手指位移，change.position 是相对「移动中的面板」的坐标，
                    // 直接 addPosition 会得到近似 0 的速度（快速轻甩因此收不起）。改用
                    // addPointerInputChange：它基于屏幕绝对坐标并包含历史采样，速度才可信。
                    val velocityThresholdPx = with(density) { PanelVelocityThreshold.toPx() }
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
                                    // 补上越界判定期间累计的位移，避免起步跳变
                                    val step = accumX / panelWidthPx.coerceAtLeast(1)
                                    scope.launch { progress.snapTo((progress.value + step).coerceIn(0f, 1f)) }
                                }
                            } else {
                                change.consume()
                                val step = delta.x / panelWidthPx.coerceAtLeast(1)
                                scope.launch { progress.snapTo((progress.value + step).coerceIn(0f, 1f)) }
                            }
                        }
                        if (dragging) {
                            scope.launch {
                                val vx = tracker.calculateVelocity().x
                                val target = when {
                                    vx > 0f -> 1f
                                    vx < -velocityThresholdPx -> 0f
                                    else -> if (progress.value > 0.35f) 1f else 0f
                                }
                                progress.animateTo(target, tween(200, easing = FastOutSlowInEasing))
                                if (target == 1f) onCollapse()
                            }
                        } else {
                            scope.launch { progress.animateTo(0f, tween(200, easing = FastOutSlowInEasing)) }
                        }
                    }
                },
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 8.dp),
                ) {
                    Text("快捷功能", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Text(
                        if (editMode) "完成" else "编辑",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clickable { editMode = !editMode }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                    IconButton(onClick = { slideOutAndCollapse() }) {
                        Icon(Icons.Filled.Close, contentDescription = "收起")
                    }
                }
                val enabledIds = prefs.quickPanelItems.ifEmpty { actions.map { it.id } }
                val enabledActions = enabledIds.mapNotNull { id -> actions.firstOrNull { it.id == id } }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    if (!editMode) {
                        enabledActions.forEach { action ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        action.perform()
                                        onCollapse()
                                    }
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Icon(action.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                Text(action.label, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    } else {
                        EditableList(uiPrefs, actions)
                    }
                }
                Text(
                    "右滑可收起面板",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                )
            }
        }
    }
}

/** 编辑模式：全部动作目录，开关启用 + 上移/下移排序；结果即时持久化。 */
@Composable
private fun EditableList(
    uiPrefs: UiPrefsController,
    actions: List<QuickAction>,
) {
    val prefs by uiPrefs.prefs.collectAsStateWithLifecycle()
    val current = prefs.quickPanelItems.ifEmpty { actions.map { it.id } }

    Column {
        actions.forEach { action ->
            val enabled = action.id in current
            val index = current.indexOf(action.id)
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    action.label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .weight(1f)
                        .clickable {
                            uiPrefs.setQuickPanelItems(
                                if (enabled) current - action.id else current + action.id,
                            )
                        },
                )
                if (enabled) {
                    IconButton(
                        onClick = {
                            if (index > 0) {
                                uiPrefs.setQuickPanelItems(
                                    current.toMutableList().apply { add(index - 1, removeAt(index)) },
                                )
                            }
                        },
                        enabled = index > 0,
                    ) { Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "上移", Modifier.size(18.dp)) }
                    IconButton(
                        onClick = {
                            if (index < current.size - 1) {
                                uiPrefs.setQuickPanelItems(
                                    current.toMutableList().apply { add(index + 1, removeAt(index)) },
                                )
                            }
                        },
                        enabled = index < current.size - 1,
                    ) { Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "下移", Modifier.size(18.dp)) }
                }
            }
        }
    }
}
