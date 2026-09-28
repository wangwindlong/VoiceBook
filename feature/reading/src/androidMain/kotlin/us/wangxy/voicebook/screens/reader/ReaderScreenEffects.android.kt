package us.wangxy.voicebook.screens.reader

import android.app.Activity
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import android.content.Context
import android.content.ContextWrapper

internal actual val SupportsBrightness: Boolean = true

@Composable
internal actual fun ScreenBrightnessEffect(brightness: Float, keepScreenOn: Boolean) {
    val context = LocalContext.current
    DisposableEffect(context, brightness, keepScreenOn) {
        val window = context.findActivity()?.window
        window?.let { w ->
            w.attributes = w.attributes.apply {
                screenBrightness = if (brightness < 0f) {
                    WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                } else {
                    brightness.coerceIn(0.05f, 1f)
                }
            }
            if (keepScreenOn) {
                w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                w.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
        onDispose {
            // 离开阅读页:恢复系统亮度、清除常亮
            window?.let { w ->
                w.attributes = w.attributes.apply {
                    screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                }
                w.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
