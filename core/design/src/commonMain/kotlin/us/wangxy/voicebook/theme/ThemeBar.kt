package us.wangxy.voicebook.theme

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** 主题模式 + 皮肤两排选择条；原本在模板首页，现移到书城设置面板。 */
@Composable
fun ThemeBar(modifier: Modifier = Modifier) {
    val controller = LocalThemeController.current
    val preference by controller.preference.collectAsStateWithLifecycle()
    Column(modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ChipRow {
            ThemeMode.entries.forEach { mode ->
                FilterChip(
                    selected = preference.mode == mode,
                    onClick = { controller.setMode(mode) },
                    label = { Text(mode.label) },
                )
            }
        }
        ChipRow {
            AppSkin.entries.forEach { skin ->
                FilterChip(
                    selected = preference.skin == skin,
                    onClick = { controller.setSkin(skin) },
                    label = { Text(skin.label) },
                )
            }
        }
    }
}

@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = { content() },
    )
}
