package us.wangxy.voicebook.screens.reader

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals

class PdfToneTest {
    @Test
    fun blackWhiteAndGrayMapToThemeColors() {
        val themes = listOf(
            Color(0.96f, 0.94f, 0.89f) to Color(0.12f, 0.15f, 0.18f),
            Color(0.08f, 0.10f, 0.13f) to Color(0.91f, 0.88f, 0.84f),
        )
        for ((paper, ink) in themes) {
            val matrix = paperToneMatrix(paper, ink)
            val paperChannels = listOf(paper.red, paper.green, paper.blue)
            val inkChannels = listOf(ink.red, ink.green, ink.blue)
            for (channel in 0..2) {
                for (input in listOf(0f, 127.5f, 255f)) {
                    val expected = inkChannels[channel] * 255f +
                        input * (paperChannels[channel] - inkChannels[channel])
                    val actual = matrix[channel, channel] * input + matrix[channel, 4]
                    assertEquals(expected, actual, 0.001f)
                }
            }
            assertEquals(1f, matrix[3, 3])
            assertEquals(0f, matrix[3, 4])
        }
    }
}
