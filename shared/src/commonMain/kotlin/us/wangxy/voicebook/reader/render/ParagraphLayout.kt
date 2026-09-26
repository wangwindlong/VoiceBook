package us.wangxy.voicebook.reader.render

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.sp
import us.wangxy.voicebook.reader.epub.Block

/** Reader typography + page geometry, all in px after density conversion by the caller. */
data class ReaderStyle(
    val fontSizeSp: Float,
    val lineHeightFactor: Float = 1.65f,
    val horizontalPaddingPx: Float,
    val topPaddingPx: Float,
    val bottomPaddingPx: Float,
) {
    val lineHeightPx: Float get() = fontSizeSp * lineHeightFactor
}

/**
 * The one place paragraphs get measured, shared by the paginator and the page renderer
 * so line breaking is computed identically in both (a page break decided from the
 * paginator's line heights must match what the renderer draws).
 */
object ParagraphLayout {

    fun annotated(paragraph: Block.Paragraph): AnnotatedString = buildAnnotatedString {
        for (span in paragraph.spans) {
            if (span.text.isEmpty()) continue
            val start = length
            append(span.text)
            if (span.bold || span.italic) {
                addStyle(
                    SpanStyle(
                        fontWeight = if (span.bold) FontWeight.SemiBold else null,
                        fontStyle = if (span.italic) FontStyle.Italic else null,
                    ),
                    start,
                    length,
                )
            }
        }
    }

    fun measure(
        paragraph: Block.Paragraph,
        measurer: TextMeasurer,
        style: ReaderStyle,
        maxWidthPx: Float,
        textColor: Color,
        fontSizeSp: Float = style.fontSizeSp,
        lineHeightPx: Float = fontSizeSp * style.lineHeightFactor,
    ): TextLayoutResult {
        val indent = if (paragraph.indent && paragraph.headingLevel == 0 && paragraph.bullet == null) {
            TextIndent(firstLine = (fontSizeSp * 2).sp)
        } else {
            null
        }
        return measurer.measure(
            text = annotated(paragraph),
            style = TextStyle(
                fontSize = fontSizeSp.sp,
                lineHeight = lineHeightPx.sp,
                color = textColor,
                textIndent = indent,
            ),
            overflow = TextOverflow.Ellipsis,
            softWrap = true,
            constraints = Constraints(maxWidth = maxWidthPx.toInt().coerceAtLeast(1)),
        )
    }

    /** Heading display sizes: h1 gets the largest scale, h6 the smallest. */
    fun headingScale(level: Int): Float = when (level) {
        1 -> 1.5f
        2 -> 1.3f
        3 -> 1.15f
        4 -> 1.05f
        5 -> 1.0f
        else -> 0.95f
    }
}
