package us.wangxy.voicebook.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import us.wangxy.voicebook.bloom.BloomButton
import us.wangxy.voicebook.bloom.BloomButtonStyle
import us.wangxy.voicebook.bloom.BloomCard
import us.wangxy.voicebook.bloom.BloomChip
import us.wangxy.voicebook.bloom.BloomShadow
import us.wangxy.voicebook.bloom.BloomSmoothing
import us.wangxy.voicebook.bloom.BloomTextButton
import us.wangxy.voicebook.bloom.bloomPath
import us.wangxy.voicebook.theme.ThemeBar

/**
 * Bloom 组件演示页：超椭圆轮廓、光晕按钮、弹簧芯片。
 * 切换皮肤后光晕色与曲率会跟着 [us.wangxy.voicebook.bloom.BloomTokens] 走。
 */
@Composable
fun BloomDemoScreen(navigateBack: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    var selected by remember { mutableStateOf("活") }
    val smoothing = when (selected) {
        "圆" -> BloomSmoothing.Round
        "柔" -> BloomSmoothing.Soft
        "软" -> BloomSmoothing.Pillowy
        else -> BloomSmoothing.Lively
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = navigateBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Text("Bloom 组件演示", style = MaterialTheme.typography.titleLarge)
        }

        Text(
            "这是 VoiceBook 自己的 chrome 语言：squircle 曲率、双色光晕、按压回弹。" +
                "Material 按钮/卡片也会吃到同一套 Shapes。下面可以改曲率预览轮廓。",
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onSurfaceVariant,
        )

        ThemeBar()

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("圆", "柔", "活", "软").forEach { label ->
                BloomChip(selected = selected == label, onClick = { selected = label }, label = label)
            }
        }

        Canvas(
            Modifier
                .fillMaxWidth()
                .height(160.dp)
                .padding(horizontal = 12.dp),
        ) {
            val path = bloomPath(
                width = size.width,
                height = size.height,
                topLeft = size.minDimension * 0.28f,
                topRight = size.minDimension * 0.22f,
                bottomRight = size.minDimension * 0.32f,
                bottomLeft = size.minDimension * 0.18f,
                smoothing = smoothing,
            )
            drawPath(path, color = scheme.primary.copy(alpha = 0.22f))
            drawPath(path, color = scheme.primary, style = Stroke(width = 3.dp.toPx()))
        }

        BloomCard(modifier = Modifier.fillMaxWidth()) {
            Text("按钮风格", style = MaterialTheme.typography.titleMedium)
            Text(
                "四角同一 squircle。手指按下立刻缩小，短点也会压一下再回弹。",
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BloomButton(onClick = {}, style = BloomButtonStyle.Normal) { Text("普通") }
                BloomButton(onClick = {}, style = BloomButtonStyle.Highlight) { Text("高亮") }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BloomButton(onClick = {}, style = BloomButtonStyle.Error) { Text("错误") }
                BloomButton(onClick = {}, style = BloomButtonStyle.Selected) { Text("选中") }
            }
            Spacer(Modifier.height(8.dp))
            BloomButton(onClick = {}, enabled = false, style = BloomButtonStyle.Highlight) {
                Text("禁用")
            }
            Spacer(Modifier.height(16.dp))
            Text("可选阴影", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BloomButton(
                    onClick = {},
                    style = BloomButtonStyle.Highlight,
                    shadow = BloomShadow.Soft,
                ) { Text("浅影") }
                BloomButton(
                    onClick = {},
                    style = BloomButtonStyle.Highlight,
                    shadow = BloomShadow.Deep,
                ) { Text("深影") }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BloomTextButton(onClick = {}) { Text("文字") }
                BloomTextButton(onClick = {}, style = BloomButtonStyle.Error) { Text("文字错误") }
            }
        }

        Text(
            "折纸皮肤光晕最克制；潮汐最圆；霓虹双色光晕最强。",
            style = MaterialTheme.typography.labelMedium,
            color = scheme.onSurfaceVariant,
        )
    }
}
