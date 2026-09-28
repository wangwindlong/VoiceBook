package us.wangxy.voicebook.ui.widget

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** 带小标题的设置分段，标题在上、内容由调用方填入。 */
@Composable
fun TitledSection(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )
        content()
    }
}

/** 分段标题 + 开关，用于「屏幕常亮」这类布尔项。 */
@Composable
fun LabeledSwitch(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    TitledSection(title) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (checked) "已开启" else "已关闭",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Switch(checked = checked, onCheckedChange = onChange)
        }
    }
}

/** 一行互斥选项，当前值高亮。 */
@Composable
fun <T> OptionChipRow(options: List<Pair<T, String>>, current: T, onSelect: (T) -> Unit) {
    Row(
        Modifier.padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEach { (value, label) ->
            FilterChip(
                selected = value == current,
                onClick = { onSelect(value) },
                label = { Text(label) },
            )
        }
    }
}
