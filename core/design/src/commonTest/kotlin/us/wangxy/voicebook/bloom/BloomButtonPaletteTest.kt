package us.wangxy.voicebook.bloom

import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BloomButtonPaletteTest {
    private val scheme = lightColorScheme(
        primary = Color(0xFF006688),
        onPrimary = Color.White,
        error = Color(0xFFBA1A1A),
        onError = Color.White,
        secondaryContainer = Color(0xFFD4E3FF),
        onSecondaryContainer = Color(0xFF001B3D),
    )

    @Test
    fun highlightUsesPrimaryAndDefaultGlow() {
        val palette = bloomButtonPalette(BloomButtonStyle.Highlight, enabled = true, scheme)
        assertEquals(scheme.primary, palette.container)
        assertEquals(scheme.onPrimary, palette.content)
        assertTrue(palette.glowDefault)
        assertEquals(0, palette.borderWidth.value.toInt())
    }

    @Test
    fun disabledOverridesAnyStyle() {
        val palette = bloomButtonPalette(BloomButtonStyle.Highlight, enabled = false, scheme)
        assertEquals(scheme.onSurface.copy(alpha = 0.38f), palette.content)
        assertFalse(palette.glowDefault)
        assertFalse(palette.sheen)
    }

    @Test
    fun errorUsesErrorRole() {
        val palette = bloomButtonPalette(BloomButtonStyle.Error, enabled = true, scheme)
        assertEquals(scheme.error, palette.container)
        assertEquals(scheme.onError, palette.content)
    }

    @Test
    fun selectedKeepsARing() {
        val palette = bloomButtonPalette(BloomButtonStyle.Selected, enabled = true, scheme)
        assertTrue(palette.borderWidth.value > 0f)
        assertTrue(palette.glowDefault)
    }
}
