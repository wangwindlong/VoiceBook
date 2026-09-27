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
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.ui.input.pointer.util.VelocityTracker
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
private val PanelVelocityThreshold = 600.dp

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

    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { if (it.height > 0) containerHeightPx = it.height },
    ) {
        if (!expanded) {
            QuickPanelHandle(
                initialRatio = prefs.quickPanelOffsetY.takeIf { it in 0f..1f } ?: 0.5f,
                containerHeightPx = containerHeightPx,
                onHeightChanged = { handleHeightPx = it },
                onExpand = { onExpandedChange(true) },
                onPositionSettled = { ratio -> uiPrefs.setQuickPanelOffsetY(ratio) },
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
    initialRatio: Float,
    containerHeightPx: Int,
    onHeightChanged: (Int) -> Unit,
    onExpand: () -> Unit,
    onPositionSettled: (Float) -> Unit,
) {
    var ratio by remember { mutableFloatStateOf(initialRatio) }
    var handleHeightPx by remember { mutableIntStateOf(1) }
    var leftwardAccum by remember { mutableFloatStateOf(0f) }

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
                    onDragEnd = { onPositionSettled(ratio) },
                    onDragCancel = { onPositionSettled(ratio) },
                ) { change, amount ->
                    change.consume()
                    val dx = amount.x
                    val dy = amount.y
                    if (abs(dy) > abs(dx)) {
                        val span = (containerHeightPx - handleHeightPx).coerceAtLeast(1).toFloat()
                        ratio = (ratio + dy / span).coerceIn(0.02f, 0.98f)
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
                .width(300.dp)
                .fillMaxHeight(0.85f)
                .onSizeChanged { if (it.width > 0) panelWidthPx = it.width }
                .offset { IntOffset((panelWidthPx * progress.value).roundToInt(), 0) }
                .pointerInput(Unit) {
                    // 面板内任意位置横拖跟手：右滑收起、左滑拉回，松手按速度/阈值结算
                    val tracker = VelocityTracker()
                    val velocityThresholdPx = with(density) { PanelVelocityThreshold.toPx() }
                    detectHorizontalDragGestures(
                        onDragStart = { tracker.resetTracking() },
                        onDragEnd = {
                            scope.launch {
                                // 正速度=向右（收起方向）
                                val vx = tracker.calculateVelocity().x
                                val target = when {
                                    vx > velocityThresholdPx -> 1f
                                    vx < -velocityThresholdPx -> 0f
                                    else -> if (progress.value > 0.35f) 1f else 0f
                                }
                                progress.animateTo(target, tween(200, easing = FastOutSlowInEasing))
                                if (target == 1f) onCollapse()
                            }
                        },
                        onDragCancel = {
                            scope.launch { progress.animateTo(0f, tween(200, easing = FastOutSlowInEasing)) }
                        },
                    ) { change, amount ->
                        if (change.isConsumed) return@detectHorizontalDragGestures
                        change.consume()
                        tracker.addPosition(change.uptimeMillis, change.position)
                        val delta = amount / panelWidthPx.coerceAtLeast(1)
                        scope.launch { progress.snapTo((progress.value + delta).coerceIn(0f, 1f)) }
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
