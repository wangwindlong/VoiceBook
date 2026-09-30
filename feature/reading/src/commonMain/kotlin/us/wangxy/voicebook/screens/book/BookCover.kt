package us.wangxy.voicebook.screens.book

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import coil3.request.crossfade
import us.wangxy.voicebook.bloom.rememberBloomShape

/**
 * 书籍封面。无图时用书名前几个字占位。
 * [lockAspect] 为 true 时控件自己撑成 3:4（书城网格）；为 false 时沿用调用方传入的尺寸（书架）。
 */
@Composable
fun BookCover(
    coverUrl: String,
    title: String,
    authHeader: String?,
    modifier: Modifier = Modifier,
    placeholderChars: Int = 4,
    lockAspect: Boolean = true,
    placeholderColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    var failed by remember(coverUrl) { mutableStateOf(false) }
    val frame = if (lockAspect) Modifier.fillMaxWidth().aspectRatio(3f / 4f) else Modifier
    val coverShape = rememberBloomShape(6.dp)
    val shaped = modifier
        .then(frame)
        .clip(coverShape)
        .background(MaterialTheme.colorScheme.surfaceVariant)
    if (coverUrl.isEmpty() || failed) {
        Box(shaped, contentAlignment = Alignment.Center) {
            Text(
                title.take(placeholderChars),
                style = MaterialTheme.typography.titleMedium,
                color = placeholderColor,
            )
        }
        return
    }
    AsyncImage(
        model = ImageRequest.Builder(LocalPlatformContext.current)
            .data(coverUrl)
            .crossfade(true)
            .httpHeaders(
                NetworkHeaders.Builder().apply { authHeader?.let { set("Authorization", it) } }.build(),
            )
            .build(),
        onError = { failed = true },
        contentDescription = title,
        contentScale = ContentScale.Crop,
        modifier = shaped,
    )
}

/** 封面 + 书名 + 作者，书城网格和书架网格共用。 */
@Composable
fun BookCell(
    title: String,
    author: String,
    coverUrl: String,
    authHeader: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    placeholderChars: Int = 4,
    lockAspect: Boolean = true,
    placeholderColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Column(
        modifier.clickable(onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        BookCover(
            coverUrl = coverUrl,
            title = title,
            authHeader = authHeader,
            placeholderChars = placeholderChars,
            lockAspect = lockAspect,
            placeholderColor = placeholderColor,
        )
        Text(
            title,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            author,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
