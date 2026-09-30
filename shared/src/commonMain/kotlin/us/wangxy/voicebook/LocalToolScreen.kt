package us.wangxy.voicebook

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import us.wangxy.voicebook.data.LocalLibrary
import us.wangxy.voicebook.ui.widget.ReferenceFilters
import us.wangxy.voicebook.ui.widget.ReferenceSearch
import kotlinx.serialization.json.*

@Composable
internal fun LocalToolScreen(tool: String, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(18.dp)) {
        Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onBack) { Text("‹") }
            Text(when (tool) { "timer" -> "计时器"; "notes" -> "便签"; "games" -> "小游戏 · 2048"; else -> "帮助与反馈" }, style = MaterialTheme.typography.titleMedium)
        }
        when (tool) {
            "timer" -> TimerTool()
            "notes" -> NotesTool()
            "games" -> GameTool()
            else -> {
                Text("VoiceBook", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(vertical = 24.dp))
                Text("从首页进入书架或资讯；上传支持 EPUB、PDF、TXT。登录后可同步资讯、发表评论并为文章点赞。", style = MaterialTheme.typography.bodyMedium)
                Text("订阅源管理可搜索和分类，点击「已订阅」取消订阅。阅读时点击正文中央打开操作浮层。", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 18.dp))
            }
        }
    }
}

@Composable
private fun TimerTool() {
    var minutes by rememberSaveable { mutableIntStateOf(25) }
    var seconds by rememberSaveable { mutableIntStateOf(25 * 60) }
    var running by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(running) { while (running && seconds > 0) { delay(1000); seconds--; if (seconds == 0) running = false } }
    ReferenceFilters(listOf("25分钟", "50分钟", "90分钟"), "${minutes}分钟", { minutes = it.removeSuffix("分钟").toInt(); seconds = minutes * 60; running = false })
    Box(Modifier.fillMaxWidth().height(300.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(progress = { seconds.toFloat() / (minutes * 60) }, modifier = Modifier.size(240.dp), strokeWidth = 10.dp, trackColor = MaterialTheme.colorScheme.primaryContainer)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("${(seconds / 60).toString().padStart(2, '0')}:${(seconds % 60).toString().padStart(2, '0')}", fontSize = 42.sp)
            Button(onClick = { if (seconds == 0) seconds = minutes * 60; running = !running }, modifier = Modifier.padding(top = 20.dp)) { Text(if (running) "暂停" else "开始") }
        }
    }
    TextButton(onClick = { seconds = minutes * 60; running = false }) { Text("重置") }
    Text(if (seconds == 0) "本次专注已完成" else "保持专注，享受阅读", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ColumnScope.NotesTool() {
    val library = koinInject<LocalLibrary>()
    val scope = rememberCoroutineScope()
    var notes by remember { mutableStateOf<List<String>>(emptyList()) }
    var query by rememberSaveable { mutableStateOf("") }
    var draft by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(library) {
        notes = runCatching { Json.parseToJsonElement(library.settings.get("tools.notes") ?: "[]").jsonArray.map { it.jsonPrimitive.content } }.getOrDefault(emptyList())
    }
    fun persist(value: List<String>) { notes = value; scope.launch { library.settings.put("tools.notes", JsonArray(value.map(::JsonPrimitive)).toString()) } }
    ReferenceSearch(query, { query = it }, "搜索便签")
    OutlinedTextField(draft, { draft = it }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp), placeholder = { Text("记录想法…") }, shape = RoundedCornerShape(12.dp))
    Button(onClick = { persist(listOf(draft.trim()) + notes); draft = "" }, enabled = draft.isNotBlank(), modifier = Modifier.align(Alignment.End).padding(vertical = 12.dp)) { Text("保存便签") }
    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        itemsIndexed(notes) { index, note ->
            if (query.isBlank() || note.contains(query, true)) Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f)) {
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    Text(note, style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { persist(notes.filterIndexed { i, _ -> i != index }) }, modifier = Modifier.align(Alignment.End)) { Text("删除") }
                }
            }
        }
    }
}

@Composable
private fun GameTool() {
    var board by remember { mutableStateOf(List(16) { if (it == 0 || it == 5) 2 else 0 }) }
    var score by remember { mutableIntStateOf(0) }
    Text("得分 $score", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(vertical = 16.dp))
    repeat(4) { row ->
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            repeat(4) { col ->
                val value = board[row * 4 + col]
                Surface(shape = RoundedCornerShape(10.dp), color = if (value == 0) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.weight(1f).aspectRatio(1f)) {
                    Box(contentAlignment = Alignment.Center) { Text(if (value == 0) "" else "$value", style = MaterialTheme.typography.titleLarge) }
                }
            }
        }
    }
    Row(Modifier.fillMaxWidth().padding(top = 20.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
        listOf("←", "↑", "↓", "→").forEachIndexed { direction, label ->
            Button(onClick = {
                val next = board.toMutableList()
                repeat(4) { line ->
                    val indices = (0..3).map { step -> when (direction) { 0 -> line * 4 + step; 3 -> line * 4 + 3 - step; 1 -> step * 4 + line; else -> (3 - step) * 4 + line } }
                    val values = indices.map { board[it] }.filter { it > 0 }
                    val merged = mutableListOf<Int>(); var i = 0
                    while (i < values.size) {
                        if (i + 1 < values.size && values[i] == values[i + 1]) { merged += values[i] * 2; score += values[i] * 2; i += 2 }
                        else { merged += values[i]; i++ }
                    }
                    indices.forEachIndexed { position, index -> next[index] = merged.getOrElse(position) { 0 } }
                }
                if (next != board) { val empty = next.indices.filter { next[it] == 0 }; if (empty.isNotEmpty()) next[empty.random()] = 2; board = next }
            }, contentPadding = PaddingValues(12.dp)) { Text(label) }
        }
    }
    TextButton(onClick = { board = List(16) { if (it == 0 || it == 5) 2 else 0 }; score = 0 }) { Text("重新开始") }
}
