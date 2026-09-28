package us.wangxy.voicebook.screens.reader

import kotlin.test.Test
import kotlin.test.assertEquals
import us.wangxy.voicebook.reader.store.PdfPageFit

/** PDF 缩放纯几何逻辑([pdfPanLimit]/适配系数/档位/PdfZoomState)的单元测试。 */
class PdfZoomTest {

    @Test
    fun panLimitZeroWhenContentFits() {
        assertEquals(0f, pdfPanLimit(100f, 200f), 0.0001f)
        assertEquals(0f, pdfPanLimit(200f, 200f), 0.0001f)
        assertEquals(50f, pdfPanLimit(300f, 200f), 0.0001f)
    }

    @Test
    fun clampPanLocksCenterWhenContentFits() {
        assertEquals(0f, pdfClampPan(42f, 100f, 200f), 0.0001f)
    }

    @Test
    fun clampPanClampsToEdges() {
        assertEquals(-50f, pdfClampPan(-80f, 300f, 200f), 0.0001f)
        assertEquals(50f, pdfClampPan(80f, 300f, 200f), 0.0001f)
        assertEquals(10f, pdfClampPan(10f, 300f, 200f), 0.0001f)
    }

    @Test
    fun fitScales() {
        // 页面 100×200,视口 200×200:适屏受高约束 → 1;适宽 → 2(纵向超出)
        assertEquals(1f, pdfFitPageScale(100f, 200f, 200f, 200f), 0.0001f)
        assertEquals(2f, pdfFitWidthScale(100f, 200f), 0.0001f)
        // 宽页面:适屏受宽约束
        assertEquals(0.5f, pdfFitPageScale(400f, 200f, 200f, 200f), 0.0001f)
    }

    @Test
    fun displayScaleFollowsFitMode() {
        assertEquals(1f, PdfPageFit.FitPage.displayScale(100f, 200f, 200f, 200f), 0.0001f)
        assertEquals(2f, PdfPageFit.FitWidth.displayScale(100f, 200f, 200f, 200f), 0.0001f)
    }

    @Test
    fun renderBucketsAreDiscrete() {
        assertEquals(1f, pdfRenderBucket(1f), 0.0001f)
        assertEquals(1f, pdfRenderBucket(1.1f), 0.0001f)
        assertEquals(1.5f, pdfRenderBucket(1.4f), 0.0001f)
        assertEquals(2f, pdfRenderBucket(2f), 0.0001f)
        assertEquals(3f, pdfRenderBucket(3.5f), 0.0001f)
    }

    @Test
    fun resetToTopAlignsTopEdgeForTallContent() {
        val state = PdfZoomState()
        // 内容高 400,视口高 300:停在页首 → offsetY = (400-300)/2 = 50
        state.resetToTop(400f, 300f)
        assertEquals(1f, state.scale, 0.0001f)
        assertEquals(0f, state.offsetX, 0.0001f)
        assertEquals(50f, state.offsetY, 0.0001f)
        // 内容放得下时居中
        state.resetToTop(200f, 300f)
        assertEquals(0f, state.offsetY, 0.0001f)
    }

    @Test
    fun zoomAroundFocusKeepsFocusPointFixed() {
        val state = PdfZoomState()
        val contentW = 300f
        val contentH = 400f
        val vw = 200f
        val vh = 300f
        state.resetToTop(contentH, vh)
        val fx = 100f
        val fy = 100f
        val contentX = fx - state.offsetX
        val contentY = fy - state.offsetY
        state.zoomTo(2f, fx, fy, contentW, contentH, vw, vh)
        assertEquals(2f, state.scale, 0.0001f)
        // 焦点下的内容点缩放前后在屏幕上重合
        assertEquals(fx, contentX * state.scale + state.offsetX, 0.001f)
        assertEquals(fy, contentY * state.scale + state.offsetY, 0.001f)
    }

    @Test
    fun zoomClampsToUserRange() {
        val state = PdfZoomState()
        state.zoomTo(99f, 0f, 0f, 100f, 100f, 100f, 100f)
        assertEquals(PdfZoomState.MaxUserZoom, state.scale, 0.0001f)
        state.zoomTo(0.1f, 0f, 0f, 100f, 100f, 100f, 100f)
        assertEquals(1f, state.scale, 0.0001f)
        assertEquals(0f, state.offsetX, 0.0001f)
        assertEquals(0f, state.offsetY, 0.0001f)
    }

    @Test
    fun reclampKeepsZoomOffsetInsideNewPageBounds() {
        val state = PdfZoomState()
        state.zoomTo(2f, 0f, 0f, 400f, 400f, 200f, 200f)
        // 翻到更小的页面后重新钳制:偏移不得越界
        state.reclamp(200f, 200f, 200f, 200f)
        assertEquals(2f, state.scale, 0.0001f)
        assertEquals(0f, state.offsetX, 0.0001f)
        assertEquals(0f, state.offsetY, 0.0001f)
    }
}
