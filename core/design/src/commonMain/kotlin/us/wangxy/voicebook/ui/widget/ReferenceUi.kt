package us.wangxy.voicebook.ui.widget

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Shared geometry from the supplied mobile reference; colors follow the selected theme. */
@Composable
fun ReferenceSearch(value: String, onValueChange: (String) -> Unit, hint: String, modifier: Modifier = Modifier, glass: Boolean = false) {
    val searchTextStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
    val content: @Composable () -> Unit = {
        Row(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Search, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            BasicTextField(value, onValueChange, modifier = Modifier.weight(1f).fillMaxHeight(), singleLine = true,
                textStyle = searchTextStyle,
                decorationBox = { input ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                        if (value.isEmpty()) Text(hint, style = searchTextStyle, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        input()
                    }
                })
        }
    }
    if (glass) {
        GlassSurface(modifier.fillMaxWidth().height(48.dp)) { content() }
    } else {
        Surface(modifier.fillMaxWidth().height(42.dp), shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, content = content)
    }
}

@Composable
fun ReferenceFilters(labels: List<String>, selected: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        labels.forEach { label ->
            Surface(onClick = { onSelect(label) }, shape = RoundedCornerShape(12.dp),
                color = if (selected == label) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface.copy(alpha = 0f)) {
                Text(label, Modifier.padding(horizontal = 12.dp, vertical = 7.dp), fontSize = 12.sp,
                    color = if (selected == label) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
