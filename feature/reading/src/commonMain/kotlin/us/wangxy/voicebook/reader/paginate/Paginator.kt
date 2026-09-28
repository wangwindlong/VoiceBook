package us.wangxy.voicebook.reader.paginate

import us.wangxy.voicebook.reader.epub.Block
import us.wangxy.voicebook.reader.render.ParagraphLayout
import us.wangxy.voicebook.reader.render.ReaderStyle

/** One laid-out line: its height and the char offset (paragraph-relative) where it starts. */
class LineBox(val heightPx: Float, val startChar: Int)

/**
 * Abstraction over paragraph measurement so the pagination math is unit-testable
 * without Compose. The production implementation wraps Compose's TextMeasurer and must
 * produce the same line breaking the renderer will later draw.
 */
interface LineMeasurer {
    /** Laid-out lines (px heights + paragraph-relative start offsets) at [maxWidthPx]. */
    fun lines(
        paragraph: Block.Paragraph,
        style: ReaderStyle,
        maxWidthPx: Float,
        fontScale: Float = 1f,
    ): List<LineBox>
}

/** One placed piece of content on a page. */
sealed interface PageEntry {
    /** A run of the paragraph's lines [fromLine, toLine). */
    data class Text(val paragraph: Block.Paragraph, val fromLine: Int, val toLine: Int) : PageEntry

    data class Picture(val image: Block.Image, val widthPx: Float, val heightPx: Float) : PageEntry

    data object Rule : PageEntry
}

/** A finished page: entries in draw order plus the position anchor for resume. */
class ReaderPage(
    val entries: List<PageEntry>,
    /** Chapter-relative char offset of the first text on this page. */
    val anchorOffset: Int,
)

data class PageLayout(val contentWidthPx: Float, val contentHeightPx: Float)

/**
 * Splits a chapter's blocks into fixed-size pages. Paragraphs break at line boundaries;
 * headings, images and rules move to the next page whole when they don't fit. A heading
 * additionally requires room for a body line after it, so headings never dangle at a
 * page bottom. Pagination is deterministic: the same (blocks, width, height, style)
 * always yields the same pages, so re-paginating after a font-size change only shifts
 * which page contains a given [anchorOffset].
 */
object Paginator {

    const val DefaultImageRatio = 1.4f
    const val MaxImageHeightPx = 420f
    const val ImageGapPx = 12f
    const val RulerHeightPx = 24f

    fun paginate(
        blocks: List<Block>,
        measurer: LineMeasurer,
        style: ReaderStyle,
        layout: PageLayout,
        imageAspectRatios: Map<String, Float> = emptyMap(),
    ): List<ReaderPage> {
        val pages = ArrayList<ReaderPage>()
        var entries = ArrayList<PageEntry>()
        var y = 0f
        var anchor = 0

        fun flushPage() {
            if (entries.isNotEmpty()) {
                pages += ReaderPage(entries.toList(), anchor)
            }
            entries = ArrayList()
            y = 0f
        }

        for (block in blocks) {
            when (block) {
                is Block.Paragraph -> {
                    val scale = if (block.headingLevel > 0) ParagraphLayout.headingScale(block.headingLevel) else 1f
                    val lines = measurer.lines(block, style, layout.contentWidthPx, scale)
                    if (lines.isEmpty()) continue
                    val isHeading = block.headingLevel > 0

                    if (isHeading) {
                        // Unsplittable: needs the whole heading plus a body line to follow.
                        val needed = lines.sumOf { it.heightPx.toDouble() }.toFloat() + MinBodyLinePx
                        if (y > 0f && y + needed > layout.contentHeightPx) flushPage()
                        if (entries.isEmpty()) anchor = block.startOffset
                        entries += PageEntry.Text(block, 0, lines.size)
                        y += lines.sumOf { it.heightPx.toDouble() }.toFloat() + style.paragraphGapPx
                        continue
                    }

                    var fromLine = 0
                    while (fromLine < lines.size) {
                        if (y > 0f && y + lines[fromLine].heightPx > layout.contentHeightPx) flushPage()
                        var used = 0f
                        var toLine = fromLine
                        while (toLine < lines.size) {
                            val lineH = lines[toLine].heightPx
                            if (y + used + lineH > layout.contentHeightPx + 0.5f && toLine > fromLine) break
                            used += lineH
                            toLine++
                        }
                        if (entries.isEmpty()) anchor = block.startOffset + lines[fromLine].startChar
                        entries += PageEntry.Text(block, fromLine, toLine)
                        y += used + style.paragraphGapPx
                        fromLine = toLine
                    }
                }

                is Block.Image -> {
                    val ratio = imageAspectRatios[block.src] ?: DefaultImageRatio
                    val height = (layout.contentWidthPx / ratio).coerceAtMost(MaxImageHeightPx)
                    val width = height * ratio
                    val total = height + ImageGapPx
                    if (y > 0f && y + total > layout.contentHeightPx) flushPage()
                    if (entries.isEmpty()) anchor = block.startOffset
                    entries += PageEntry.Picture(block, width, height)
                    y += total
                }

                is Block.Ruler -> {
                    if (y > 0f && y + RulerHeightPx > layout.contentHeightPx) flushPage()
                    entries += PageEntry.Rule
                    y += RulerHeightPx
                }
            }
        }
        flushPage()
        if (pages.isEmpty()) pages += ReaderPage(emptyList(), 0)
        return pages
    }

    /**
     * Page containing [offset] — the last page whose first text starts at or before it.
     * Anchors are line-granular, so the result lands on the page showing that text.
     */
    fun pageForOffset(pages: List<ReaderPage>, offset: Int): Int {
        var candidate = 0
        for ((index, page) in pages.withIndex()) {
            if (page.anchorOffset <= offset) candidate = index else break
        }
        return candidate
    }

    private const val MinBodyLinePx = 40f
}
