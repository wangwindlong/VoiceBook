package us.wangxy.voicebook.screens.mine

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.koinInject
import us.wangxy.voicebook.theme.*
import us.wangxy.voicebook.reader.store.ReaderStateController
import us.wangxy.voicebook.reader.store.ReaderFontFamily
import us.wangxy.voicebook.ui.widget.ReferenceFilters
import us.wangxy.voicebook.ui.widget.ThemeBar

@Composable
internal fun AppearancePanel() {
    val controller = LocalThemeController.current
    val preference by controller.preference.collectAsStateWithLifecycle()
    val reader = koinInject<ReaderStateController>()
    val readerPrefs by reader.state.collectAsStateWithLifecycle()
    var advanced by remember { mutableStateOf(false) }
    SectionTitle("外观")
    Surface(shape = RoundedCornerShape(14.dp), color = Color.Transparent, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("主题风格", style = MaterialTheme.typography.titleSmall)
            ReferenceFilters(listOf("自动", "明亮", "深色"), when (preference.mode) { ThemeMode.System -> "自动"; ThemeMode.Light -> "明亮"; ThemeMode.Dark -> "深色" }, {
                controller.setMode(when (it) { "明亮" -> ThemeMode.Light; "深色" -> ThemeMode.Dark; else -> ThemeMode.System })
            })
            Text("皮肤", style = MaterialTheme.typography.titleSmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val ids = listOf("system", "fold", "tide", "neon")
                val colors = listOf(Color(0xFFE4ECFF), Color(0xFFE4D4BC), Color(0xFF4E86AF), Color(0xFF302C65))
                ids.forEachIndexed { index, id ->
                    Surface(onClick = { controller.setSkin(id) }, modifier = Modifier.weight(1f).height(52.dp), shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(if (preference.skin == id) 2.dp else 1.dp, if (preference.skin == id) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)) {
                        Box(Modifier.background(Brush.verticalGradient(listOf(colors[index], colors[index].copy(alpha = 0.25f)))), contentAlignment = Alignment.Center) {
                            Text(listOf("默认", "折纸", "潮汐", "霓虹")[index], style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
            Text("颜色主题", style = MaterialTheme.typography.titleSmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                listOf(0xFF3478F6.toInt(), 0xFFEE6875.toInt(), 0xFFECAE42.toInt(), 0xFFEF9854.toInt(), 0xFF35B5CE.toInt(), 0xFF9461E8.toInt()).forEach { argb ->
                    Surface(onClick = { controller.setAccent(argb) }, color = Color(argb), shape = CircleShape, modifier = Modifier.size(30.dp),
                        border = BorderStroke(2.dp, if (MaterialTheme.colorScheme.primary == Color(argb)) MaterialTheme.colorScheme.onSurface else Color.Transparent)) {}
                }
            }
            TextButton(onClick = { advanced = !advanced }, contentPadding = PaddingValues(0.dp)) { Text("更多皮肤与导入", style = MaterialTheme.typography.labelMedium) }
            if (advanced) ThemeBar()
        }
    }
    SectionTitle("功能设置")
    Surface(shape = RoundedCornerShape(14.dp), color = Color.Transparent, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 14.dp)) {
            PreferenceSwitch("夜间模式", preference.mode == ThemeMode.Dark) { controller.setMode(if (it) ThemeMode.Dark else ThemeMode.Light) }
            PreferenceSwitch("封面动态取色", preference.skin == "dynamic") { controller.setSkin(if (it) "dynamic" else "system") }
            PreferenceSwitch("使用系统字体", readerPrefs.fontFamily == ReaderFontFamily.Default) { reader.setFontFamily(if (it) ReaderFontFamily.Default else ReaderFontFamily.Serif) }
        }
    }
}

@Composable
private fun PreferenceSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked, onChange)
    }
}
