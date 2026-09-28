package us.wangxy.voicebook.bloom

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BloomShapeTest {
    @Test
    fun pathBoundsMatchRequestedSize() {
        val path = bloomPath(
            width = 200f,
            height = 80f,
            topLeft = 20f,
            topRight = 24f,
            bottomRight = 18f,
            bottomLeft = 22f,
            smoothing = BloomSmoothing.Lively,
        )
        val bounds = path.getBounds()
        assertEquals(0f, bounds.left, 0.6f)
        assertEquals(0f, bounds.top, 0.6f)
        assertEquals(200f, bounds.right, 0.6f)
        assertEquals(80f, bounds.bottom, 0.6f)
    }

    @Test
    fun overlappingCornersAreClamped() {
        val radii = clampCornerRadii(
            width = 100f,
            height = 40f,
            topLeft = 80f,
            topRight = 80f,
            bottomRight = 80f,
            bottomLeft = 80f,
        )
        assertTrue(radii[0] + radii[1] <= 100.01f)
        assertTrue(radii[3] + radii[2] <= 100.01f)
        assertTrue(radii[0] + radii[3] <= 40.01f)
        assertTrue(radii[1] + radii[2] <= 40.01f)
    }

    @Test
    fun outlineIsGenericSquircle() {
        val density = Density(1f)
        val shape = BloomShape(16.dp, BloomSmoothing.Pillowy)
        val outline = shape.createOutline(Size(120f, 48f), LayoutDirection.Ltr, density)
        assertTrue(outline is Outline.Generic)
        assertTrue(smoothingToExponent(0f) == 2f)
        assertTrue(smoothingToExponent(1f) > 5f)
    }
}
