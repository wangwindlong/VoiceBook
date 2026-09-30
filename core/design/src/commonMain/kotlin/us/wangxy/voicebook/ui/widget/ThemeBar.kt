package us.wangxy.voicebook.ui.widget

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import us.wangxy.voicebook.bloom.BloomChip
import us.wangxy.voicebook.theme.BuiltinSkinId
import us.wangxy.voicebook.theme.LocalThemeController
import us.wangxy.voicebook.theme.SkinData
import us.wangxy.voicebook.theme.ThemeMode
import us.wangxy.voicebook.theme.label


/** 主题模式 + 皮肤两排选择条；原本在模板首页，现移到书城设置面板。 */
@Composable
fun ThemeBar(modifier: Modifier = Modifier) {
    val controller = LocalThemeController.current
    val preference by controller.preference.collectAsStateWithLifecycle()
    val customSkins by controller.customSkins.collectAsStateWithLifecycle()
    val clipboardManager = LocalClipboardManager.current
    val showImportDialog = remember { mutableStateOf(false) }
    val importText = remember { mutableStateOf("") }
    val importError = remember { mutableStateOf<String?>(null) }
    val exportedHint = remember { mutableStateOf<String?>(null) }

    Column(modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ChipRow {
            ThemeMode.entries.forEach { mode ->
                BloomChip(
                    selected = preference.mode == mode,
                    onClick = { controller.setMode(mode) },
                    label = mode.label,
                )
            }
        }
        // 内置皮肤
        ChipRow {
            listOf("system" to "系统色", "dynamic" to "封面取色", BuiltinSkinId.Fold to "折纸", BuiltinSkinId.Tide to "潮汐", BuiltinSkinId.Neon to "霓虹").forEach { (id, label) ->
                BloomChip(
                    selected = preference.skin == id,
                    onClick = { controller.setSkin(id) },
                    label = label,
                )
            }
        }
        // 自定义皮肤 + 导入按钮
        ChipRow {
            customSkins.forEach { skin ->
                CustomSkinChip(
                    skin = skin,
                    selected = preference.skin == skin.id,
                    onClick = { controller.setSkin(skin.id) },
                    onExport = {
                        controller.exportSkin(skin.id)?.let { json ->
                            clipboardManager.setText(AnnotatedString(json))
                            exportedHint.value = "「${skin.name}」已复制到剪贴板"
                        }
                    },
                    onDelete = { controller.removeCustomSkin(skin.id) },
                )
            }
            // 导入皮肤按钮
            ImportSkinChip(onClick = {
                importText.value = ""
                importError.value = null
                showImportDialog.value = true
            })
        }
        exportedHint.value?.let { hint ->
            Text(
                hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }

    // 导入皮肤对话框
    if (showImportDialog.value) {
        BloomDialog(
            onDismissRequest = { showImportDialog.value = false },
            title = { Text("导入皮肤") },
            text = {
                Text(
                    "粘贴皮肤 JSON 文本（从导出功能获得）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = importText.value,
                    onValueChange = { s: String -> importText.value = s; importError.value = null },
                    label = { Text("皮肤 JSON") },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 8,
                    singleLine = false,
                    keyboardOptions = KeyboardOptions.Default.copy(imeAction = ImeAction.Done),
                    isError = importError.value != null,
                )
                importError.value?.let { err ->
                    Text(
                        err,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        controller.importSkin(importText.value).fold(
                            onFailure = { e: Throwable -> importError.value = e.message ?: "导入失败" },
                            onSuccess = { showImportDialog.value = false },
                        )
                    },
                    enabled = importText.value.isNotBlank(),
                ) { Text("导入") }
            },
            dismissButton = { TextButton(onClick = { showImportDialog.value = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun CustomSkinChip(
    skin: SkinData,
    selected: Boolean,
    onClick: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    val controller = LocalThemeController.current
    val scheme = MaterialTheme.colorScheme
    val showMenu = remember { mutableStateOf(false) }
    DropdownMenu(
        expanded = showMenu.value,
        onDismissRequest = { showMenu.value = false },
    ) {
        DropdownMenuItem(
            text = { Text("导出") },
            onClick = {
                onExport()
                showMenu.value = false
            },
        )
        DropdownMenuItem(
            text = { Text("删除", color = scheme.error) },
            onClick = { onDelete(); showMenu.value = false },
        )
    }
    // Wrap BloomChip with menu button in a Row
    Row(
        Modifier.padding(end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BloomChip(
            selected = selected,
            onClick = onClick,
            label = skin.name,
        )
        IconButton(onClick = { showMenu.value = !showMenu.value }) {
            Icon(Icons.Filled.MoreVert, contentDescription = "更多")
        }
    }
}

@Composable
private fun ImportSkinChip(onClick: () -> Unit) {
    Row(
        Modifier.padding(end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BloomChip(
            selected = false,
            onClick = onClick,
            label = "导入皮肤",
        )
    }
}

@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = { content() },
    )
}