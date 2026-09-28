package us.wangxy.voicebook.screens.reader

import androidx.compose.runtime.Composable

/** 当前平台是否支持应用内亮度/屏幕常亮(仅 Android 有 window 控制权)。 */
internal expect val SupportsBrightness: Boolean

/**
 * 把阅读设置应用到平台窗口:亮度(<0 表示跟随系统)与屏幕常亮。
 * Android 解析 Activity window 设置,离开阅读页时由调用方的 dispose 复位;
 * 其余平台为 no-op。
 */
@Composable
internal expect fun ScreenBrightnessEffect(brightness: Float, keepScreenOn: Boolean)
