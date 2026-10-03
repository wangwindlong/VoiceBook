package us.wangxy.voicebook

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import us.wangxy.voicebook.ui.rememberShareText
import us.wangxy.voicebook.ui.widget.*

/** Interactive usage examples, using the same application hosts as production pages. */
@Composable
internal fun GlobalComponentsDemoScreen(navigateBack: () -> Unit, onOpenMine: () -> Unit) {
    val ui = LocalGlobalUi.current
    val remind = rememberGlobalReminder()
    val clipboard = LocalClipboardManager.current
    val share = rememberShareText()
    var selected by remember { mutableStateOf("home") }
    var immersive by remember { mutableStateOf(false) }
    var menuResult by remember { mutableStateOf("长按下方卡片，或点击菜单按钮") }
    val actions = listOf(
        ContextAction("打开") { menuResult = "已执行打开操作"; remind("已打开示例卡片", null, null) },
        ContextAction("复制") { clipboard.setText(AnnotatedString("全局组件示例卡片")); menuResult = "已复制卡片标题"; remind("已复制", null, null) },
        ContextAction("分享") { share("全局组件示例卡片", "VoiceBook 全局组件演示"); menuResult = "已调用系统分享" },
        ContextAction("不可用", enabled = false) {},
    )
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = navigateBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
            Text("全局组件演示", style = MaterialTheme.typography.titleLarge)
        }
        Text("所有示例使用当前皮肤。切换外观后，可继续打开菜单、搜索和浮层检查颜色。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        ThemeBar()
        DemoSection("1. 底部悬浮导航", "滚动本页面，观察屏幕底部导航上滑隐藏、下滑显示。下面是可自定义 Tab 的独立示例。", "FloatingNavigation(tabs, selected, visible, onSelect)") {
            FloatingNavigation(
                listOf(FloatingTab("home", "首页", Icons.Default.Home), FloatingTab("search", "搜索", Icons.Default.Search), FloatingTab("mine", "我的", Icons.Default.Person)),
                selected = selected, visible = true, onSelect = { selected = it },
            )
            Text("示例选中：${when (selected) { "home" -> "首页"; "search" -> "搜索"; else -> "我的" }}")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { ui.navigationVisible = false }) { Text("隐藏主导航") }
                OutlinedButton(onClick = { ui.navigationVisible = true }) { Text("显示主导航") }
                OutlinedButton(onClick = { immersive = true }) { Text("沉浸预览") }
            }
        }
        DemoSection("2. 卡片长按菜单", "长按卡片后显示上下文操作；点击外部或返回可关闭。", "ui.showMenu(listOf(ContextAction(\"复制\") { ... }))") {
            GlassSurface(Modifier.fillMaxWidth().combinedClickable(onClick = { menuResult = "已点击卡片；长按可打开菜单" }, onLongClick = { ui.showMenu(actions) })) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("全局组件示例卡片", style = MaterialTheme.typography.titleMedium)
                    Text(menuResult, style = MaterialTheme.typography.bodyMedium)
                }
            }
            OutlinedButton(onClick = { ui.showMenu(actions) }) { Text("打开上下文菜单") }
        }
        DemoSection("3. 全局搜索", "打开真实全局搜索：最近记录、推荐词、快捷操作及书籍/资讯结果。提交搜索或打开结果会记录历史。", "ui.searchVisible = true") {
            Button(onClick = { ui.searchVisible = true }) { Text("打开全局搜索") }
        }
        DemoSection("4. 底部浮层", "可放入表单、开关、滑块等组件。支持拖动、遮罩点击和返回关闭。", "ui.showSheet { YourContent() }") {
            Button(onClick = {
                ui.showSheet { DemoSheetContent(onClose = ui::dismissSheet, onSave = { ui.dismissSheet(); remind("示例设置已保存", null, null) }) }
            }) { Text("打开表单浮层") }
        }
        DemoSection("5. 轻量提醒", "共享全局提醒队列，自动消失，也可以手动关闭或点击动作跳转。", "val remind = rememberGlobalReminder()\nremind(\"已保存\", \"查看\") { navigate() }") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { remind("这是一条轻量提醒", null, null) }) { Text("普通提醒") }
                OutlinedButton(onClick = { remind("设置已更新", "查看设置", onOpenMine) }) { Text("带跳转提醒") }
                OutlinedButton(onClick = { repeat(3) { index -> remind("队列提醒 ${index + 1}", null, null) } }) { Text("提醒队列") }
            }
        }
        Text("继续上滑可验证导航隐藏，再下滑让导航重新出现。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(80.dp))
    }
    if (immersive) {
        Dialog(onDismissRequest = { immersive = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text("沉浸页面预览", style = MaterialTheme.typography.headlineSmall)
                Text("此全屏预览覆盖导航。实际阅读和文章页面会按路由自动隐藏导航。", Modifier.padding(vertical = 24.dp))
                Button(onClick = { immersive = false }) { Text("退出沉浸预览") }
            }
        }
    }
}

@Composable
private fun DemoSection(title: String, description: String, usage: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.medium) {
            Text(usage, Modifier.fillMaxWidth().padding(12.dp), style = MaterialTheme.typography.bodySmall, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
        }
        content()
        HorizontalDivider(Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun DemoSheetContent(onClose: () -> Unit, onSave: () -> Unit) {
    var name by remember { mutableStateOf("示例设置") }
    var enabled by remember { mutableStateOf(true) }
    var progress by remember { mutableFloatStateOf(0.5f) }
    Column(Modifier.fillMaxWidth().heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("可组合的底部浮层", style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("名称") }, singleLine = true)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("启用示例选项", Modifier.weight(1f)); Switch(enabled, { enabled = it })
        }
        Text("示例进度：${(progress * 100).toInt()}%")
        Slider(progress, { progress = it })
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onSave, enabled = name.isNotBlank()) { Text("保存示例") }
            TextButton(onClick = onClose) { Text("关闭") }
        }
    }
}
