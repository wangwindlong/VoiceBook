package us.wangxy.voicebook.twine

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import us.wangxy.voicebook.theme.LocalTwineTokens
import kotlin.math.roundToInt

/** Story map 上的一个段落节点;坐标是画布世界坐标(未缩放的像素)。 */
data class TwineStoryNode(
    val id: String,
    val title: String,
    val x: Float,
    val y: Float,
    val linkCount: Int = 0,
)

/** 节点间的一条分支连线。 */
data class TwineStoryLink(val fromId: String, val toId: String)

/**
 * Twine 式 story map:段落卡片 + 贝塞尔分支线,画布可拖拽平移/双指缩放,
 * 节点可拖动([onNodeMove] 上报新世界坐标,位置由调用方持有)。
 * 参考 klembot/twinejs 的 story map 交互;纯 foundation 实现。
 *
 * v1 限制:节点命中即整卡拖拽(无连线编辑),单击回调 [onNodeClick]。
 */
@Composable
fun TwineStoryMap(
    nodes: List<TwineStoryNode>,
    links: List<TwineStoryLink>,
    modifier: Modifier = Modifier,
    onNodeMove: (id: String, x: Float, y: Float) -> Unit = { _, _, _ -> },
    onNodeClick: (TwineStoryNode) -> Unit = {},
) {
    val tokens = LocalTwineTokens.current
    val cardWidth = 140.dp
    val cardHeight = 56.dp
    val cardWidthPx = with(LocalDensity.current) { cardWidth.toPx() }
    val cardHeightPx = with(LocalDensity.current) { cardHeight.toPx() }

    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val transformable = rememberTransformableState { zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(0.4f, 2.5f)
        offset += pan
    }

    Box(
        modifier = modifier
            .clipToBounds()
            .transformable(transformable),
    ) {
        Box(
            Modifier.graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val bend = 36.dp.toPx()
                links.forEach { link ->
                    if (link.fromId == link.toId) return@forEach
                    val from = nodes.firstOrNull { it.id == link.fromId } ?: return@forEach
                    val to = nodes.firstOrNull { it.id == link.toId } ?: return@forEach
                    val startX = from.x + cardWidthPx / 2
                    val startY = from.y + cardHeightPx
                    val endX = to.x + cardWidthPx / 2
                    val endY = to.y
                    val path = Path().apply {
                        moveTo(startX, startY)
                        cubicTo(startX, startY + bend, endX, endY - bend, endX, endY)
                    }
                    drawPath(path, tokens.inkAccentMuted, style = Stroke(1.5.dp.toPx()))
                    drawCircle(tokens.inkAccent, radius = 3.dp.toPx(), center = Offset(endX, endY))
                }
            }
            nodes.forEach { node ->
                TwineStoryNodeCard(
                    node = node,
                    scale = scale,
                    modifier = Modifier
                        .offset { IntOffset(node.x.roundToInt(), node.y.roundToInt()) }
                        .size(cardWidth, cardHeight),
                    onMove = onNodeMove,
                    onClick = onNodeClick,
                )
            }
        }
    }
}

@Composable
private fun TwineStoryNodeCard(
    node: TwineStoryNode,
    scale: Float,
    modifier: Modifier = Modifier,
    onMove: (String, Float, Float) -> Unit,
    onClick: (TwineStoryNode) -> Unit,
) {
    val tokens = LocalTwineTokens.current
    val interaction = remember(node.id) { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val shape = RoundedCornerShape(tokens.passageCorner / 2)
    Box(
        modifier = modifier
            .pointerInput(node.id, scale) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    onMove(node.id, node.x + dragAmount.x / scale, node.y + dragAmount.y / scale)
                }
            }
            .clickable(interactionSource = interaction, indication = null) { onClick(node) }
            .background(if (pressed) tokens.highlight else tokens.paper, shape)
            .border(1.dp, if (pressed) tokens.inkAccent else tokens.paperEdge, shape)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Column {
            BasicText(
                text = node.title,
                style = TextStyle(color = tokens.ink, fontSize = 13.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (node.linkCount > 0) {
                BasicText(
                    text = "${node.linkCount} 条分支",
                    style = TextStyle(color = tokens.inkFaded, fontSize = 11.sp),
                )
            }
        }
    }
}
