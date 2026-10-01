package us.wangxy.voicebook

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

private const val ExitPressWindowMillis = 2_000L

@Composable
internal fun HomeBackHandler(enabled: Boolean, consumeBack: () -> Boolean) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val lifecycleOwner = LocalLifecycleOwner.current
    var lastExitPress by remember { mutableStateOf<Long?>(null) }
    var hint by remember { mutableStateOf<Toast?>(null) }

    LaunchedEffect(enabled) {
        lastExitPress = null
        hint?.cancel()
        hint = null
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                lastExitPress = null
                hint?.cancel()
                hint = null
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            hint?.cancel()
        }
    }
    BackHandler(enabled = enabled && activity != null) {
        if (consumeBack()) {
            lastExitPress = null
            hint?.cancel()
            hint = null
        } else {
            val now = SystemClock.elapsedRealtime()
            val previous = lastExitPress
            if (previous != null && now - previous <= ExitPressWindowMillis) {
                hint?.cancel()
                activity?.finish()
            } else {
                lastExitPress = now
                hint?.cancel()
                hint = Toast.makeText(context, "再按一次返回退出", Toast.LENGTH_SHORT).also { it.show() }
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> if (baseContext !== this) baseContext.findActivity() else null
    else -> null
}
