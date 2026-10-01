package us.wangxy.voicebook.screens.reader

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import coil3.compose.SubcomposeAsyncImage
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import us.wangxy.voicebook.ui.widget.BloomDialog
import us.wangxy.voicebook.ui.widget.BloomSheet

/**
 * 本书全部评论的底部抽屉（风格与 ReaderSettingsSheet 一致）。
 * 未登录只给一句「登录后可看评论」；空列表给引导文案；点赞走乐观更新 + 验证码重发。
 */
@Composable
internal fun CommentsSheet(
    state: ReaderCommentsUiState,
    onLike: (Long) -> Unit,
    onReply: (Long, String) -> Unit,
    onCancelReply: () -> Unit,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    onCaptchaSubmit: (String) -> Unit,
    onCaptchaDismiss: () -> Unit,
    onNoticeDismissed: () -> Unit,
    onDismiss: () -> Unit,
) {
    var dismissRequested by remember { mutableStateOf(false) }
    BloomSheet(
        visible = true,
        onDismiss = onDismiss,
        peekFraction = 0.9f,
        dismissRequested = dismissRequested,
    ) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).systemBarsPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton({ dismissRequested = true }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
                Text("评论（${state.total}）", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onRetry) { Text("刷新") }
            }
            state.notice?.let { notice ->
                Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(notice, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                    TextButton(onNoticeDismissed) { Text("知道了") }
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    state.signedOut -> SheetHint("登录后可看评论")
                    state.loading && state.comments.isEmpty() -> SheetLoading()
                    state.error != null && state.comments.isEmpty() -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(state.error.orEmpty(), style = MaterialTheme.typography.bodyMedium)
                        TextButton(onRetry) { Text("重试") }
                    }
                    state.comments.isEmpty() -> SheetHint("还没有评论，来说点什么")
                    else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
                        val featured = state.featured
                        if (featured.isNotEmpty()) {
                            item { Text("精彩评论", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) }
                            items(featured, key = { "featured_${it.id}" }) { row -> CommentItem(row, onLike, onReply) }
                        }
                        item { Text("全部评论", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) }
                        items(state.comments, key = { it.id }) { row -> CommentItem(row, onLike, onReply) }
                        if (state.comments.size < state.total) item {
                            TextButton(onClick = onLoadMore, enabled = !state.loadingMore, modifier = Modifier.fillMaxWidth()) {
                                Text(if (state.loadingMore) "加载中…" else "加载更多评论")
                            }
                        }
                    }
                }
            }
            ComposeBar(state, onDraftChange, onSend, onCancelReply)
            Spacer(Modifier.height(12.dp))
            state.captcha?.let { CaptchaDialog(it, onCaptchaSubmit, onCaptchaDismiss) }
        }
    }
}

/** 底部发言栏：回复目标提示 + 输入框 + 发送。未登录时整条禁用并给出提示。 */
@Composable
private fun ComposeBar(
    state: ReaderCommentsUiState,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onCancelReply: () -> Unit,
) {
    val reply = state.replyTarget
    if (reply != null) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "回复 @${reply.nick}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onCancelReply) { Text("取消") }
        }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = state.draft,
            onValueChange = onDraftChange,
            enabled = !state.signedOut && !state.sending,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(18.dp),
            placeholder = { Text(if (state.signedOut) "登录后可发表评论" else "说点什么…") },
            maxLines = 4,
        )
        // 发送中显示转圈，否则是发送图标；空草稿禁用
        if (state.sending) {
            CircularProgressIndicator(Modifier.padding(horizontal = 12.dp).size(22.dp))
        } else {
            IconButton(
                onClick = onSend,
                enabled = !state.signedOut && state.draft.isNotBlank(),
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "发送")
            }
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
private fun CommentItem(row: CommentRow, onLike: (Long) -> Unit, onReply: (Long, String) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            // 楼中楼按层级缩进；封顶避免深层回复把内容挤成一条
            .padding(start = 20.dp + (row.depth.coerceAtMost(4) * 16).dp, end = 20.dp, top = 12.dp, bottom = 12.dp),
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
            Text(
                row.content,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 6.dp),
            )
            Row(
                Modifier.padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LikeButton(row, onLike)
                TextButton(
                    onClick = { onReply(row.id, row.nick) },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                ) {
                    Text("回复", style = MaterialTheme.typography.labelMedium)
                }
                if (row.replyToNick != null) {
                    Text(
                        "回复 @${row.replyToNick}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
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
            color = Color.White, // On dynamic avatar background: readable contrast
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
    BloomDialog(
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
