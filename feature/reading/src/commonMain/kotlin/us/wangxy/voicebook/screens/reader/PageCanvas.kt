package us.wangxy.voicebook.screens.reader

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.material3.MaterialTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import us.wangxy.voicebook.reader.epub.Block
import us.wangxy.voicebook.reader.epub.ReadableBook
import us.wangxy.voicebook.reader.paginate.LineBox
import us.wangxy.voicebook.reader.paginate.LineMeasurer
import us.wangxy.voicebook.reader.paginate.PageEntry
import us.wangxy.voicebook.reader.paginate.Paginator
import us.wangxy.voicebook.reader.render.ParagraphLayout
import us.wangxy.voicebook.reader.render.ReaderStyle
import us.wangxy.voicebook.theme.LocalTwineTokens

/** Production [LineMeasurer] over Compose's TextMeasurer — same measure call as the renderer. */
@Composable
internal fun rememberBookLineMeasurer(style: ReaderStyle, contentWidth: Float): LineMeasurer {
    val measurer = rememberTextMeasurer()
    val tokens = LocalTwineTokens.current
    val textColor = tokens.ink
    return remember(measurer, style, contentWidth, textColor) {
        object : LineMeasurer {
            override fun lines(
                paragraph: Block.Paragraph,
                style: ReaderStyle,
                maxWidthPx: Float,
                fontScale: Float,
            ): List<LineBox> {
                if (paragraph.spans.all { it.text.isEmpty() }) return emptyList()
                val layout = ParagraphLayout.measure(
                    paragraph = paragraph,
                    measurer = measurer,
                    style = style,
                    maxWidthPx = maxWidthPx,
                    textColor = textColor,
                    fontSizeSp = style.fontSizeSp * fontScale,
                    lineHeightPx = style.lineHeightPx * fontScale,
                )
                if (layout.lineCount == 0) return emptyList()
                return (0 until layout.lineCount).map { line ->
                    LineBox(
                        heightPx = layout.getLineBottom(line) - layout.getLineTop(line),
                        startChar = layout.getLineStart(line),
                    )
                }
            }
        }
    }
}

/**
 * Draws one paginated page: paints line slices, images and rules top-down. Paragraph
 * layouts and images come from the session-level [layoutCache]/[images] (owned by
 * BookPager) — the curl surface rebuilds page composition every animation frame, so
 * anything measured or loaded inside this composable would be thrown away per frame.
 * ParagraphLayout.measure (via the cache) is the same path the paginator measured
 * with, so page breaks match the drawn lines.
 */
@Composable
internal fun PageCanvas(
    entries: List<PageEntry>,
    book: ReadableBook,
    chapterHref: String?,
    style: ReaderStyle,
    layoutCache: MutableMap<Block.Paragraph, TextLayoutResult>,
    images: MutableMap<String, ImageBitmap>,
    /** Chapter-relative char range [first, last) being read aloud; drawn behind the text. */
    highlight: Pair<Int, Int>? = null,
) {
    BoxWithConstraints(Modifier.fillMaxSize().background(LocalTwineTokens.current.paper)) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }
        val contentWidth = widthPx - style.horizontalPaddingPx * 2

        val measurer = rememberTextMeasurer()
        val tokens = LocalTwineTokens.current
        val textColor = tokens.ink
        val mutedColor = tokens.inkFaded
        val highlightColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)

        for (entry in entries) {
            if (entry is PageEntry.Picture && entry.image.src !in images) {
                LaunchedEffect(entry.image.src) {
                    val bytes = book.resourceBytes(entry.image.src, chapterHref) ?: return@LaunchedEffect
                    val bitmap = withContext(Dispatchers.Default) { decodeImageBitmap(bytes) }
                    if (bitmap != null) images[entry.image.src] = bitmap
                }
            }
        }

        Canvas(Modifier.fillMaxSize()) {
            var y = style.topPaddingPx
            val left = style.horizontalPaddingPx
            val width = contentWidth

            for (entry in entries) {
                when (entry) {
                    is PageEntry.Text -> {
                        val paragraph = entry.paragraph
                        val layout = layoutCache.getOrPut(paragraph) {
                            ParagraphLayout.measure(
                                paragraph = paragraph,
                                measurer = measurer,
                                style = style,
                                maxWidthPx = contentWidth,
                                textColor = textColor,
                                fontSizeSp = style.fontSizeSp * fontScaleOf(paragraph),
                                lineHeightPx = style.lineHeightPx * fontScaleOf(paragraph),
                            )
                        }
                        if (layout.lineCount == 0) continue
                        val fromLine = entry.fromLine.coerceIn(0, layout.lineCount - 1)
                        val toLineLast = (entry.toLine - 1).coerceIn(0, layout.lineCount - 1)
                        if (fromLine > toLineLast) continue

                        val top = layout.getLineTop(fromLine)
                        val bottom = layout.getLineBottom(toLineLast)
                        val spoken = highlight?.let { (from, to) ->
                            val start = maxOf(from, paragraph.startOffset) - paragraph.startOffset
                            val end = minOf(to, paragraph.endOffset) - paragraph.startOffset
                            val maxLen = layout.layoutInput.text.length
                            if (start < end && maxLen > 0) {
                                layout.getPathForRange(start.coerceIn(0, maxLen), end.coerceIn(0, maxLen))
                            } else null
                        }
                        translate(left, y - top) {
                            clipRect(0f, top, layout.size.width.toFloat(), bottom) {
                                if (spoken != null) drawPath(spoken, color = highlightColor)
                                drawText(layout, color = textColor)
                            }
                        }
                        y += bottom - top + style.paragraphGapPx
                    }

                    is PageEntry.Picture -> {
                        val bitmap = images[entry.image.src]
                        if (bitmap != null) {
                            val boxW = entry.widthPx
                            val boxH = entry.heightPx
                            val ratio = bitmap.width.toFloat() / bitmap.height
                            val wide = ratio >= boxW / boxH
                            val dstW = if (wide) boxW else boxH * ratio
                            val dstH = if (wide) boxW / ratio else boxH
                            drawImage(
                                image = bitmap,
                                dstOffset = IntOffset(
                                    (left + (boxW - dstW) / 2).toInt(),
                                    (y + (boxH - dstH) / 2).toInt(),
                                ),
                                dstSize = IntSize(dstW.toInt().coerceAtLeast(1), dstH.toInt().coerceAtLeast(1)),
                            )
                        } else {
                            drawRoundRect(
                                color = mutedColor.copy(alpha = 0.25f),
                                topLeft = Offset(left, y),
                                size = Size(entry.widthPx, entry.heightPx),
                                cornerRadius = CornerRadius(12f, 12f),
                            )
                        }
                        y += entry.heightPx + Paginator.ImageGapPx
                    }

                    PageEntry.Rule -> {
                        drawLine(
                            color = mutedColor.copy(alpha = 0.5f),
                            start = Offset(left + width / 4, y + Paginator.RulerHeightPx / 2),
                            end = Offset(left + width * 3 / 4, y + Paginator.RulerHeightPx / 2),
                            strokeWidth = 1.5f,
                        )
                        y += Paginator.RulerHeightPx
                    }
                }
            }
        }
    }
}

private fun fontScaleOf(paragraph: Block.Paragraph): Float =
    if (paragraph.headingLevel > 0) ParagraphLayout.headingScale(paragraph.headingLevel) else 1f

