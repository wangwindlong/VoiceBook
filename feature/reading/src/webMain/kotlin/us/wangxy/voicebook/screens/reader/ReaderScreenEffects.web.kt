package us.wangxy.voicebook.screens.reader

import androidx.compose.runtime.Composable

internal actual val SupportsBrightness: Boolean = false

@Composable
internal actual fun ScreenBrightnessEffect(brightness: Float, keepScreenOn: Boolean) = Unit
