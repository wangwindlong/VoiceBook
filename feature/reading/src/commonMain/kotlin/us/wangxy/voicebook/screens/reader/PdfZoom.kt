package us.wangxy.voicebook.screens.reader

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import us.wangxy.voicebook.reader.store.PdfPageFit

// ---------- 纯几何工具(无状态,可 JVM 单测) ----------

/** 内容缩放后超出视口的平移余量一半;内容未超出视口时为 0(锁定居中)。 */
internal fun pdfPanLimit(content: Float, viewport: Float): Float =
    ((content - viewport) / 2f).coerceAtLeast(0f)

/** 把平移偏移钳制到内容边界;内容未超出视口时恒为 0。 */
internal fun pdfClampPan(offset: Float, content: Float, viewport: Float): Float {
    val limit = pdfPanLimit(content, viewport)
    return if (limit <= 0f) 0f else offset.coerceIn(-limit, limit)
}

/** 整页适屏系数:min(视宽/页宽, 视高/页高)。 */
internal fun pdfFitPageScale(pageW: Float, pageH: Float, viewportW: Float, viewportH: Float): Float =
    minOf(viewportW / pageW, viewportH / pageH)

/** 适宽系数:视宽/页宽(高度通常超出视口,纵向可拖动)。 */
internal fun pdfFitWidthScale(pageW: Float, viewportW: Float): Float = viewportW / pageW

/** 适配方式 → 显示系数。 */
internal fun PdfPageFit.displayScale(pageW: Float, pageH: Float, viewportW: Float, viewportH: Float): Float =
    when (this) {
        PdfPageFit.FitPage -> pdfFitPageScale(pageW, pageH, viewportW, viewportH)
        PdfPageFit.FitWidth -> pdfFitWidthScale(pageW, viewportW)
    }

/** 把连续的手势倍率量化到离散渲染档位,避免重渲染抖动;上限 3(平台 MaxRenderScale)。 */
internal fun pdfRenderBucket(scale: Float): Float = when {
    scale <= 1.15f -> 1f
    scale <= 1.75f -> 1.5f
    scale <= 2.5f -> 2f
    else -> 3f
}

// ---------- 状态与手势 ----------

/**
 * PDF 页面缩放状态。[scale] 是适配系数之外的用户缩放倍率;[offsetX/Y] 是内容
 * 相对视口中心的平移(px),与 graphicsLayer 的 translation 语义一致。
 */
internal class PdfZoomState {
    var scale by mutableFloatStateOf(1f)
        private set
    var offsetX by mutableFloatStateOf(0f)
        private set
    var offsetY by mutableFloatStateOf(0f)
        private set

    /** 倍率归 1、停在内容顶部:适宽下内容超高时对齐上边缘,其余情况居中。 */
    fun resetToTop(contentH: Float, viewportH: Float) {
        scale = 1f
        offsetX = 0f
        offsetY = pdfPanLimit(contentH, viewportH)
    }

    /** 页面/适配/视口变化后按新尺寸重新钳制:保留倍率,偏移收敛回合法域。 */
    fun reclamp(contentW: Float, contentH: Float, viewportW: Float, viewportH: Float) {
        offsetX = pdfClampPan(offsetX, contentW * scale, viewportW)
        offsetY = pdfClampPan(offsetY, contentH * scale, viewportH)
    }

    /** 以视口中心系下的焦点 (focusX, focusY) 为锚缩放到 [target],偏移同步钳制。 */
    fun zoomTo(
        target: Float,
        focusX: Float,
        focusY: Float,
        contentW: Float,
        contentH: Float,
        viewportW: Float,
        viewportH: Float,
    ) {
        val next = target.coerceIn(1f, MaxUserZoom)
        val ratio = next / scale
        offsetX = pdfClampPan(focusX - (focusX - offsetX) * ratio, contentW * next, viewportW)
        offsetY = pdfClampPan(focusY - (focusY - offsetY) * ratio, contentH * next, viewportH)
        scale = next
    }

    fun zoomBy(
        factor: Float,
        focusX: Float,
        focusY: Float,
        contentW: Float,
        contentH: Float,
        viewportW: Float,
        viewportH: Float,
    ) = zoomTo(scale * factor, focusX, focusY, contentW, contentH, viewportW, viewportH)

    fun panBy(
        dx: Float,
        dy: Float,
        contentW: Float,
        contentH: Float,
        viewportW: Float,
        viewportH: Float,
    ) {
        offsetX = pdfClampPan(offsetX + dx, contentW * scale, viewportW)
        offsetY = pdfClampPan(offsetY + dy, contentH * scale, viewportH)
    }

    companion object {
        internal const val MaxUserZoom = 4f
        internal const val DoubleTapZoom = 2.5f
    }
}

/**
 * PDF 页面缩放手势,跑在 Main pass(子层先于 Pager/pagecurl 父层,可安全消费):
 * - 双指捏合始终消费,绕质心缩放 + 平移;
 * - 已放大(scale>1)接管单指拖动;scale=1 时横向拖动不消费,放行给翻页;
 * - 纵向超高内容(适宽)时纵向为主的拖动归缩放层,横向仍放行;
 * - 轻点后检测双击,1x ⇄ [PdfZoomState.DoubleTapZoom] 带锚点动画切换;
 *   全程不消费 down/up,与父层 Initial pass 点击区互不干扰,单击动作靠
 *   readerTapZones 的 waitForDoubleTap 延迟触发来给双击让路。
 */
internal fun Modifier.pdfZoomable(
    state: PdfZoomState,
    scope: CoroutineScope,
    contentW: Float,
    contentH: Float,
    viewportW: Float,
    viewportH: Float,
): Modifier = pointerInput(state, scope, contentW, contentH, viewportW, viewportH) {
    awaitEachGesture {
        val down = awaitFirstDown(pass = PointerEventPass.Main, requireUnconsumed = false)
        var claimed = state.scale > 1f
        var accX = 0f
        var accY = 0f
        var moveDistance = 0f
        var maxPointers = 1
        var lastTime = down.uptimeMillis
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Main)
            lastTime = event.changes.maxOf { it.uptimeMillis }
            val pressed = event.changes.filter { it.pressed }
            if (pressed.isEmpty()) break
            maxPointers = maxOf(maxPointers, pressed.size)
            if (pressed.size >= 2) {
                val zoomChange = event.calculateZoom()
                val panChange = event.calculatePan()
                if (zoomChange != 1f || panChange != Offset.Zero) {
                    event.changes.forEach { change -> if (change.pressed) change.consume() }
                    if (zoomChange != 1f) {
                        val centroid = event.calculateCentroid()
                        state.zoomBy(
                            zoomChange,
                            centroid.x - viewportW / 2f,
                            centroid.y - viewportH / 2f,
                            contentW, contentH, viewportW, viewportH,
                        )
                    }
                    state.panBy(panChange.x, panChange.y, contentW, contentH, viewportW, viewportH)
                    claimed = true
                }
            } else {
                val change = pressed[0].positionChange()
                moveDistance += change.getDistance()
                if (change != Offset.Zero) {
                    if (claimed) {
                        pressed[0].consume()
                        state.panBy(change.x, change.y, contentW, contentH, viewportW, viewportH)
                    } else {
                        accX += change.x
                        accY += change.y
                        if (contentH > viewportH + 1f &&
                            abs(accY) > viewConfiguration.touchSlop &&
                            abs(accY) > abs(accX)
                        ) {
                            claimed = true
                        }
                        if (claimed) {
                            pressed[0].consume()
                            state.panBy(change.x, change.y, contentW, contentH, viewportW, viewportH)
                        }
                    }
                }
            }
        }
        // 未接管的轻点 → 双击检测(不消费任何事件,不干扰父层翻页/chrome)
        val isTap = !claimed && maxPointers == 1 &&
            moveDistance <= viewConfiguration.touchSlop &&
            lastTime - down.uptimeMillis < viewConfiguration.longPressTimeoutMillis
        if (!isTap) return@awaitEachGesture
        val secondDown = withTimeoutOrNull(viewConfiguration.doubleTapTimeoutMillis) {
            awaitFirstDown(pass = PointerEventPass.Main, requireUnconsumed = false)
        } ?: return@awaitEachGesture
        val secondUp = waitForUpOrCancellation(pass = PointerEventPass.Main)
            ?: return@awaitEachGesture
        if ((secondDown.position - secondUp.position).getDistance() > viewConfiguration.touchSlop ||
            secondUp.uptimeMillis - secondDown.uptimeMillis >= viewConfiguration.longPressTimeoutMillis
        ) {
            return@awaitEachGesture
        }
        val target = if (state.scale > 1.01f) 1f else PdfZoomState.DoubleTapZoom
        val focusX = secondUp.position.x - viewportW / 2f
        val focusY = secondUp.position.y - viewportH / 2f
        scope.launch {
            animate(state.scale, target, animationSpec = tween(DoubleTapAnimMillis)) { value, _ ->
                state.zoomTo(value, focusX, focusY, contentW, contentH, viewportW, viewportH)
            }
        }
    }
}

private const val DoubleTapAnimMillis = 200
