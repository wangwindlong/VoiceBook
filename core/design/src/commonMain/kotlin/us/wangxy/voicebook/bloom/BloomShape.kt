package us.wangxy.voicebook.bloom

import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin

/**
 * 超椭圆 squircle：介于圆角矩形与正圆之间，接近 iOS 图标那种连续曲率。
 *
 * [smoothing] 0 时每个角是四分之一椭圆（接近 [androidx.compose.foundation.shape.RoundedCornerShape]），
 * 1 时更“饱满”、边到角的过渡更长。[BloomSmoothing] 给了几档常用值。
 *
 * 继承 [CornerBasedShape]，可直接塞进 [androidx.compose.material3.Shapes]。
 */
class BloomShape(
    topStart: CornerSize,
    topEnd: CornerSize,
    bottomEnd: CornerSize,
    bottomStart: CornerSize,
    val smoothing: Float = BloomSmoothing.Lively,
) : CornerBasedShape(topStart, topEnd, bottomEnd, bottomStart) {

    override fun copy(
        topStart: CornerSize,
        topEnd: CornerSize,
        bottomEnd: CornerSize,
        bottomStart: CornerSize,
    ) = BloomShape(topStart, topEnd, bottomEnd, bottomStart, smoothing)

    override fun createOutline(
        size: Size,
        topStart: Float,
        topEnd: Float,
        bottomEnd: Float,
        bottomStart: Float,
        layoutDirection: LayoutDirection,
    ): Outline {
        val topLeft = if (layoutDirection == LayoutDirection.Ltr) topStart else topEnd
        val topRight = if (layoutDirection == LayoutDirection.Ltr) topEnd else topStart
        val bottomRight = if (layoutDirection == LayoutDirection.Ltr) bottomEnd else bottomStart
        val bottomLeft = if (layoutDirection == LayoutDirection.Ltr) bottomStart else bottomEnd
        return Outline.Generic(
            bloomPath(
                width = size.width,
                height = size.height,
                topLeft = topLeft,
                topRight = topRight,
                bottomRight = bottomRight,
                bottomLeft = bottomLeft,
                smoothing = smoothing,
            ),
        )
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BloomShape) return false
        return topStart == other.topStart &&
            topEnd == other.topEnd &&
            bottomEnd == other.bottomEnd &&
            bottomStart == other.bottomStart &&
            smoothing == other.smoothing
    }

    override fun hashCode(): Int {
        var result = topStart.hashCode()
        result = 31 * result + topEnd.hashCode()
        result = 31 * result + bottomEnd.hashCode()
        result = 31 * result + bottomStart.hashCode()
        result = 31 * result + smoothing.hashCode()
        return result
    }
}

fun BloomShape(
    corner: CornerSize,
    smoothing: Float = BloomSmoothing.Lively,
) = BloomShape(corner, corner, corner, corner, smoothing)

fun BloomShape(
    size: Dp,
    smoothing: Float = BloomSmoothing.Lively,
) = BloomShape(CornerSize(size), smoothing)

fun BloomShape(
    percent: Int,
    smoothing: Float = BloomSmoothing.Lively,
) = BloomShape(CornerSize(percent), smoothing)

fun BloomShape(
    topStart: Dp,
    topEnd: Dp,
    bottomEnd: Dp,
    bottomStart: Dp,
    smoothing: Float = BloomSmoothing.Lively,
) = BloomShape(
    CornerSize(topStart),
    CornerSize(topEnd),
    CornerSize(bottomEnd),
    CornerSize(bottomStart),
    smoothing,
)

object BloomSmoothing {
    /** 四分之一椭圆，接近普通圆角。 */
    const val Round = 0f
    const val Soft = 0.35f
    /** 默认：接近 iOS 连续圆角。 */
    const val Lively = 0.68f
    /** 更饱满、边到角几乎摸平。 */
    const val Pillowy = 1f
}

/** smoothing 0→n=2（椭圆），1→n≈5.2（squircle）。 */
internal fun smoothingToExponent(smoothing: Float): Float =
    2f + smoothing.coerceIn(0f, 1f) * 3.2f

@Composable
fun rememberBloomShape(
    radius: Dp = 20.dp,
    smoothing: Float = LocalBloomTokens.current.smoothing,
): BloomShape = remember(radius, smoothing) { BloomShape(radius, smoothing) }

@Composable
fun rememberBloomShape(
    percent: Int,
    smoothing: Float = LocalBloomTokens.current.smoothing,
): BloomShape = remember(percent, smoothing) { BloomShape(percent, smoothing) }

/**
 * 超椭圆路径。四个角独立半径；相邻角半径之和超过边长时按比例收缩，避免自交。
 */
fun bloomPath(
    width: Float,
    height: Float,
    topLeft: Float,
    topRight: Float,
    bottomRight: Float,
    bottomLeft: Float,
    smoothing: Float,
    stepsPerCorner: Int = 16,
): Path {
    val (tl, tr, br, bl) = clampCornerRadii(width, height, topLeft, topRight, bottomRight, bottomLeft)
    val exponent = smoothingToExponent(smoothing)
    val steps = stepsPerCorner.coerceAtLeast(4)
    return Path().apply {
        moveTo(tl, 0f)
        lineTo(width - tr, 0f)
        addSuperellipseQuadrant(
            cx = width - tr,
            cy = tr,
            rx = tr,
            ry = tr,
            startRad = -HALF_PI,
            endRad = 0.0,
            exponent = exponent,
            steps = steps,
        )
        lineTo(width, height - br)
        addSuperellipseQuadrant(
            cx = width - br,
            cy = height - br,
            rx = br,
            ry = br,
            startRad = 0.0,
            endRad = HALF_PI,
            exponent = exponent,
            steps = steps,
        )
        lineTo(bl, height)
        addSuperellipseQuadrant(
            cx = bl,
            cy = height - bl,
            rx = bl,
            ry = bl,
            startRad = HALF_PI,
            endRad = PI,
            exponent = exponent,
            steps = steps,
        )
        lineTo(0f, tl)
        addSuperellipseQuadrant(
            cx = tl,
            cy = tl,
            rx = tl,
            ry = tl,
            startRad = PI,
            endRad = PI + HALF_PI,
            exponent = exponent,
            steps = steps,
        )
        close()
    }
}

private fun Path.addSuperellipseQuadrant(
    cx: Float,
    cy: Float,
    rx: Float,
    ry: Float,
    startRad: Double,
    endRad: Double,
    exponent: Float,
    steps: Int,
) {
    if (rx <= 0.5f && ry <= 0.5f) {
        lineTo(cx, cy)
        return
    }
    val power = 2f / exponent
    for (i in 1..steps) {
        val t = startRad + (endRad - startRad) * i / steps
        val cosT = cos(t).toFloat()
        val sinT = sin(t).toFloat()
        val x = rx * sign(cosT) * abs(cosT).pow(power)
        val y = ry * sign(sinT) * abs(sinT).pow(power)
        lineTo(cx + x, cy + y)
    }
}

internal fun clampCornerRadii(
    width: Float,
    height: Float,
    topLeft: Float,
    topRight: Float,
    bottomRight: Float,
    bottomLeft: Float,
): FloatArray {
    var tl = topLeft.coerceAtLeast(0f)
    var tr = topRight.coerceAtLeast(0f)
    var br = bottomRight.coerceAtLeast(0f)
    var bl = bottomLeft.coerceAtLeast(0f)
    val top = tl + tr
    if (top > width && top > 0f) {
        val s = width / top
        tl *= s
        tr *= s
    }
    val bottom = bl + br
    if (bottom > width && bottom > 0f) {
        val s = width / bottom
        bl *= s
        br *= s
    }
    val left = tl + bl
    if (left > height && left > 0f) {
        val s = height / left
        tl *= s
        bl *= s
    }
    val right = tr + br
    if (right > height && right > 0f) {
        val s = height / right
        tr *= s
        br *= s
    }
    return floatArrayOf(tl, tr, br, bl)
}

private const val PI = kotlin.math.PI
private const val HALF_PI = PI / 2.0
private operator fun FloatArray.component1() = this[0]
private operator fun FloatArray.component2() = this[1]
private operator fun FloatArray.component3() = this[2]
private operator fun FloatArray.component4() = this[3]
