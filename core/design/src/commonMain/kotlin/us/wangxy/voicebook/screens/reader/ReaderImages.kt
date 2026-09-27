package us.wangxy.voicebook.screens.reader

import androidx.compose.ui.graphics.ImageBitmap

/** Decodes PNG/JPEG/GIF/WebP bytes into a Compose bitmap, per platform decoder. */
expect fun decodeImageBitmap(bytes: ByteArray): ImageBitmap?
