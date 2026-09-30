package us.wangxy.voicebook.screens.reader

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import us.wangxy.voicebook.twine.TwineSheet

/**
 * 本书全部评论的底部抽屉（风格与 ReaderSettingsSheet 一致）。
 * 未登录只给一句「登录后可看评论」；空列表给引导文案；点赞走乐观更新 + 验证码重发。
 */
@Composable
internal fun CommentsSheet(
    state: ReaderCommentsUiState,
    onLike: (Long) -> Unit,
    onRetry: () -> Unit,
    onCaptchaSubmit: (String) -> Unit,
    onCaptchaDismiss: () -> Unit,
    onNoticeDismissed: () -> Unit,
    onDismiss: () -> Unit,
) {
    TwineSheet(
        visible = true,
        onDismiss = onDismiss,
        peekFraction = 0.8f,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("全部评论", style = MaterialTheme.typography.titleMedium)
            if (state.total > 0) {
                Text(
                    " · 共 ${state.total} 条",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        val notice = state.notice
        if (notice != null) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    notice,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onNoticeDismissed) { Text("知道了") }
            }
        }
        when {
            state.signedOut -> SheetHint("登录后可看评论")
            state.loading && state.comments.isEmpty() -> SheetLoading()
            state.error != null && state.comments.isEmpty() -> Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    state.error.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onRetry) { Text("重试") }
            }
            state.comments.isEmpty() -> SheetHint("还没有评论，来说点什么")
            else -> LazyColumn(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 560.dp)
                    .padding(bottom = 12.dp),
            ) {
                items(state.comments, key = { it.id }) { row ->
                    CommentItem(row, onLike)
                    HorizontalDivider(
                        Modifier.padding(horizontal = 20.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                    )
                }
            }
        }
        val captcha = state.captcha
        if (captcha != null) {
            CaptchaDialog(captcha, onCaptchaSubmit, onCaptchaDismiss)
        }
    }
}

@Composable
private fun SheetHint(text: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SheetLoading() {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CircularProgressIndicator(Modifier.size(22.dp))
        Text("正在加载评论…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun CommentItem(row: CommentRow, onLike: (Long) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Avatar(row)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    row.nick,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    displayDate(row.date),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // 进度/时长/笔记当前是假数据（CommentExtrasProvider），真实接口就绪后此行自动变真
            Text(
                "读到 ${row.extras.progressPercent}% · 阅读 ${row.extras.readMinutes} 分钟 · 笔记 ${row.extras.noteCount} 条",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
            Text(
                row.content,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 6.dp),
            )
            LikeButton(row, onLike, Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun Avatar(row: CommentRow) {
    val url = row.avatarUrl
    if (url == null) {
        AvatarFallback(row.nick)
    } else {
        // 加载中/失败都落到首字母色块占位
        SubcomposeAsyncImage(
            model = url,
            contentDescription = row.nick,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(32.dp).clip(CircleShape),
            loading = { AvatarFallback(row.nick) },
            error = { AvatarFallback(row.nick) },
        )
    }
}

@Composable
private fun AvatarFallback(nick: String) {
    Box(
        Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(avatarColor(nick)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            nick.take(1).uppercase(),
            style = MaterialTheme.typography.labelLarge,
            color = Color.White,
        )
    }
}

/** 首字母色块的底色：按昵称确定性取色，滚动不跳变。 */
private fun avatarColor(seed: String): Color {
    val palette = listOf(
        0xFF8D6E63, 0xFF7E57C2, 0xFF5C6BC0, 0xFF29B6F6, 0xFF26A69A,
        0xFFEF5350, 0xFFEC407A, 0xFF66BB6A, 0xFF9575CD, 0xFFFFA726,
    )
    val hash = seed.fold(0) { acc, c -> acc * 31 + c.code }
    return Color(palette[hash.mod(palette.size)])
}

@Composable
private fun LikeButton(row: CommentRow, onLike: (Long) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = !row.voting) { onLike(row.id) }
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            Icons.Filled.ThumbUp,
            contentDescription = if (row.liked) "取消点赞" else "点赞",
            tint = if (row.liked) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.size(16.dp),
        )
        Text(
            "${row.votes}",
            style = MaterialTheme.typography.labelMedium,
            color = if (row.liked) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

@Composable
private fun CaptchaDialog(
    captcha: PendingCaptcha,
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var answer by remember(captcha) { mutableStateOf("") }
    val bitmap = remember(captcha.imgData) { decodeDataImage(captcha.imgData) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("需要验证码") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    captcha.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = "验证码图片",
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Text(
                        "验证码图片加载失败，请取消后重试",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                OutlinedTextField(
                    value = answer,
                    onValueChange = { answer = it },
                    label = { Text("输入图中答案") },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSubmit(answer) }, enabled = answer.isNotBlank()) { Text("提交") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/** `data:image/png;base64,…` → Compose 位图；解不出来返回 null 由 UI 提示。 */
@OptIn(ExperimentalEncodingApi::class)
private fun decodeDataImage(imgData: String?): ImageBitmap? {
    val base64 = imgData?.substringAfter("base64,", "").orEmpty()
    if (base64.isEmpty()) return null
    return runCatching { decodeImageBitmap(Base64.decode(base64)) }.getOrNull()
}

/** Artalk 的 ISO8601 时间串太长，截成 `yyyy-MM-dd HH:mm` 展示。 */
private fun displayDate(iso: String): String = iso.replace('T', ' ').take(16)
