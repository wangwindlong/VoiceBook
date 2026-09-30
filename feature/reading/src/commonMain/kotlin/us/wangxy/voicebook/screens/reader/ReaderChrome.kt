package us.wangxy.voicebook.screens.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.outlined.Comment
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import us.wangxy.voicebook.theme.LocalThemeController
import us.wangxy.voicebook.theme.ThemeMode


@Composable
internal fun ReaderTopBar(
    title: String,
    chapterTitle: String,
    onBack: () -> Unit,
    onToc: (() -> Unit)?,
    onSettings: () -> Unit,
    /** Starts reading aloud from the current page; null where listening isn't available (PDF). */
    onListen: (() -> Unit)? = null,
    /** 打开本书评论列表；null 时不显示入口。 */
    onComments: (() -> Unit)? = null,
    /** 该书评论总数，驱动角标；0（或未知）时不显示角标。 */
    commentCount: Int = 0,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.95f))
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
        Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, modifier = Modifier.weight(1f))
        if (onListen != null) {
            IconButton(onClick = onListen) {
                Icon(Icons.Outlined.Headphones, "听书")
            }
        }
        IconButton(onClick = onSettings) {
            Icon(Icons.Default.MoreVert, "阅读设置")
        }
        if (onComments != null) {
            IconButton(onClick = onComments) {
                // material-icons-core 里没有评论图标，与「听」「Aa」一致用字符按钮；角标 0 时隐藏
                BadgedBox(
                    badge = {
                        if (commentCount > 0) {
                            Badge { Text(if (commentCount > 99) "99+" else commentCount.toString()) }
                        }
                    },
                ) {
                    Icon(Icons.Outlined.Comment, "评论")
                }
            }
        }
        if (onToc != null) {
            IconButton(onClick = onToc) { Icon(Icons.Filled.List, "目录") }
        }
    }
}

@Composable
internal fun ReaderBottomBar(
    page: Int,
    pageCount: Int,
    chapter: Int,
    chapterCount: Int,
    percent: Int,
    pageFraction: Float,
    onSeek: (Float) -> Unit,
    onSeekFinished: () -> Unit,
    fontSize: Int,
    onFontSmaller: () -> Unit,
    onFontLarger: () -> Unit,
    onToc: (() -> Unit)? = null,
    onSettings: (() -> Unit)? = null,
    onListen: (() -> Unit)? = null,
) {
    val theme = LocalThemeController.current
    Surface(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f), shadowElevation = 8.dp) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("$page/$pageCount", style = MaterialTheme.typography.labelSmall)
                Slider(value = pageFraction.coerceIn(0f, 1f), onValueChange = onSeek, onValueChangeFinished = onSeekFinished, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
                Text("$percent%", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                if (onToc != null) TextButton(onClick = onToc) { Text("目录", style = MaterialTheme.typography.labelMedium) }
                if (onSettings != null) TextButton(onClick = onSettings) { Text("设置", style = MaterialTheme.typography.labelMedium) }
                if (onListen != null) TextButton(onClick = onListen) { Text("听书", style = MaterialTheme.typography.labelMedium) }
                TextButton(onClick = { theme.setMode(if (theme.preference.value.mode == ThemeMode.Dark) ThemeMode.Light else ThemeMode.Dark) }) { Text("夜间", style = MaterialTheme.typography.labelMedium) }
                TextButton(onClick = onFontSmaller, enabled = fontSize > ReaderFontSizes.first()) { Text("A−", style = MaterialTheme.typography.labelMedium) }
                TextButton(onClick = onFontLarger, enabled = fontSize < ReaderFontSizes.last()) { Text("A+", style = MaterialTheme.typography.labelMedium) }
            }
        }
    }
}
