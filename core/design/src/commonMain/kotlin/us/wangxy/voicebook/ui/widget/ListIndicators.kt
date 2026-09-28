package us.wangxy.voicebook.ui.widget

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 居中的加载圈。不传 [indicatorSize] 时用组件默认尺寸。 */
@Composable
fun CenteredProgress(
    modifier: Modifier = Modifier,
    indicatorSize: Dp? = null,
) {
    Box(modifier, contentAlignment = Alignment.Center) {
        if (indicatorSize == null) {
            CircularProgressIndicator()
        } else {
            CircularProgressIndicator(Modifier.size(indicatorSize))
        }
    }
}

/** 分页列表滚到底的提示。 */
@Composable
fun EndOfListMarker(
    modifier: Modifier = Modifier.fillMaxWidth().padding(12.dp),
) {
    Text(
        "— 到底了 —",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
        textAlign = TextAlign.Center,
    )
}
