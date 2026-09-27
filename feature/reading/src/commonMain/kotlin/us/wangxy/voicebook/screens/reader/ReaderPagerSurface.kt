package us.wangxy.voicebook.screens.reader

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

internal enum class ReaderTapZone { Left, Right, Center }

/**
 * 阅读翻页面板抽象:Android 用 pagecurl 卷页,其余平台用 HorizontalPager。
 * 点击区(左 1/3 上一页、右 1/3 下一页、中间切换 chrome)与翻页手势在实现内
 * 处理;到达首页/末页再点击时经 onLeftEdgeTap/onRightEdgeTap 交回调用方(跨章节)。
 *
 * [onSettledPage] 在翻页落定后上报当前页码(调用方据此持久化进度);
 * [jumpToPage] 变化时无动画跳页(章节切换/字号重排后的位置恢复)。
 */
@Composable
internal expect fun ReaderPagerSurface(
    pageCount: Int,
    initialPage: Int,
    jumpToPage: Int,
    modifier: Modifier,
    onSettledPage: (Int) -> Unit,
    onLeftEdgeTap: () -> Unit,
    onRightEdgeTap: () -> Unit,
    onCenterTap: () -> Unit,
    page: @Composable (Int) -> Unit,
)
