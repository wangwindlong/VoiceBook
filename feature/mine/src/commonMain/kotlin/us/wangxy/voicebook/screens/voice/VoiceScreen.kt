package us.wangxy.voicebook.screens.voice

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import us.wangxy.voicebook.voice.duplex.DuplexReport
import us.wangxy.voicebook.voice.model.ModelSetupState
import us.wangxy.voicebook.voice.routing.RoutingPolicy
import org.koin.compose.viewmodel.koinViewModel
import kotlin.math.roundToInt

@Composable
fun VoiceScreen(navigateBack: () -> Unit) {
    val viewModel = koinViewModel<VoiceViewModel>()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val asrPolicy by viewModel.asrPolicy.collectAsStateWithLifecycle()
    val ttsPolicy by viewModel.ttsPolicy.collectAsStateWithLifecycle()
    val systemAec by viewModel.systemAec.collectAsStateWithLifecycle()
    val report by viewModel.report.collectAsStateWithLifecycle()
    val recordingPath by viewModel.recordingPath.collectAsStateWithLifecycle()
    val partial by viewModel.partial.collectAsStateWithLifecycle()
    val log by viewModel.log.collectAsStateWithLifecycle()
    val modelState by viewModel.modelState.collectAsStateWithLifecycle()
    var text by remember { mutableStateOf("你好，我是你的语音助手。你可以在我说话的时候随时打断我，也可以只是嗯一声，我会继续说下去。") }

    LazyColumn(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = navigateBack) { Text("返回") }
                Text("全双工语音", style = MaterialTheme.typography.titleLarge)
            }
        }
        item { ModelCard(modelState, onRetry = viewModel::retryModelDownload) }
        item { PolicyRow("识别", asrPolicy, viewModel::setAsrPolicy) }
        item { PolicyRow("播报", ttsPolicy, viewModel::setTtsPolicy) }
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("系统回声消除")
                Switch(checked = systemAec, onCheckedChange = viewModel::setSystemAec)
                if (viewModel.canRecord) {
                    OutlinedButton(onClick = viewModel::toggleRecording) {
                        Text(if (recordingPath == null) "录制调试音频" else "停止录制")
                    }
                }
            }
        }
        item {
            Text(
                buildString {
                    append(if (state.listening) "监听中" else "未监听")
                    if (state.userSpeaking) append(" · 用户说话")
                    if (state.speaking) append(" · 播报中")
                    if (partial.isNotEmpty()) append(" · $partial")
                },
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = viewModel::toggleListening) { Text(if (state.listening) "停止监听" else "开始监听") }
                OutlinedButton(onClick = viewModel::runEchoTest) { Text("回声测试") }
            }
        }
        item {
            OutlinedTextField(value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth(), maxLines = 3)
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { viewModel.speak(text) }) { Text("播报") }
                OutlinedButton(onClick = viewModel::stopSpeaking) { Text("停止播报") }
            }
        }
        item { ReportCard(report, onReset = viewModel::resetReport) }
        items(log) { line -> Text(line, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun ReportCard(report: DuplexReport, onReset: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("互扰诊断", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = onReset) { Text("清零") }
            }
            val leak = report.echoLeakDb
            val vadHits = report.playbackVadHitRatio ?: 0f
            Text(
                "回声泄漏: ${leak?.let { "${it.fmt()} dB" } ?: "-"}" +
                    when {
                        leak == null -> ""
                        leak >= 15 || vadHits >= 0.2f -> "（严重: TTS 被录入）"
                        leak >= 6 || vadHits >= 0.05f -> "（轻微）"
                        else -> "（正常）"
                    },
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "底噪 ${report.idleLevelDb.fmtDb()} · 播报期均值 ${report.playbackLevelDb.fmtDb()} · 峰值 ${report.playbackPeakDb.fmtDb()}",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "播报 ${report.playbackSeconds.fmt()}s · VAD 超阈 ${report.playbackVadHitRatio?.let { "${(it * 100).roundToInt()}%" } ?: "-"}" +
                    " · VAD 最大 ${report.playbackMaxVad.fmt()}",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "播报中起音 ${report.onsetsDuringPlayback} · 丢弃 ${report.discardedDuringPlayback} · 回声过滤 ${report.echoSuppressed}" +
                    " · 口头禅 ${report.backchannels} · 打断 ${report.bargeIns} · 识别 ${report.finals}",
                style = MaterialTheme.typography.bodySmall,
            )
            report.lastBargeInLatencyMs?.let {
                Text("上次打断延迟（起音→停播）: $it ms", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** Hidden once the local models are installed (or when the platform has none). */
@Composable
private fun ModelCard(state: ModelSetupState, onRetry: () -> Unit) {
    if (state == ModelSetupState.Ready || state == ModelSetupState.Unsupported) return
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("本地语音模型", style = MaterialTheme.typography.titleSmall)
            when (state) {
                is ModelSetupState.Missing -> Text("准备下载 ${state.downloadBytes.mb()} MB…")
                is ModelSetupState.Downloading -> {
                    val fraction = state.total?.let { (state.bytes.toFloat() / it).coerceIn(0f, 1f) }
                    Text("下载 ${state.model}（${state.index}/${state.count}）${state.bytes.mb()}/${state.total?.mb() ?: "?"} MB")
                    if (fraction != null) LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                    else LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                is ModelSetupState.Extracting -> {
                    Text("解压 ${state.model}（${state.index}/${state.count}）${(state.fraction * 100).roundToInt()}%")
                    LinearProgressIndicator(progress = { state.fraction }, modifier = Modifier.fillMaxWidth())
                }
                is ModelSetupState.Failed -> {
                    Text("下载失败：${state.message}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    Text("已下载部分会保留，重试时断点续传。", style = MaterialTheme.typography.bodySmall)
                    Button(onClick = onRetry) { Text("重试") }
                }
                ModelSetupState.Ready, ModelSetupState.Unsupported -> Unit
            }
            Text("下载完成前识别和播报走云端（如已配置）。", style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun Long.mb(): Long = this / (1024 * 1024)

private fun Float.fmt(): String = ((this * 10).roundToInt() / 10f).toString()

private fun Float?.fmtDb(): String = this?.let { "${it.fmt()}dB" } ?: "-"

@Composable
private fun PolicyRow(label: String, selected: RoutingPolicy, onSelect: (RoutingPolicy) -> Unit) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label)
        RoutingPolicy.entries.forEach { policy ->
            FilterChip(
                selected = policy == selected,
                onClick = { onSelect(policy) },
                label = { Text(policy.label) },
            )
        }
    }
}

private val RoutingPolicy.label: String
    get() = when (this) {
        RoutingPolicy.LocalOnly -> "本地"
        RoutingPolicy.RemoteOnly -> "云端"
        RoutingPolicy.PreferLocal -> "本地优先"
        RoutingPolicy.PreferRemote -> "云端优先"
    }
