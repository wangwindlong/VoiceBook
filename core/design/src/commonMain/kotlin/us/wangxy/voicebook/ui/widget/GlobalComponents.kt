package us.wangxy.voicebook.ui.widget

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.Alignment
import kotlinx.coroutines.launch

/** Available to every page below the application host. Callbacks run after the overlay closes. */
class GlobalUiState internal constructor() {
    var searchVisible by mutableStateOf(false)
    var navigationVisible by mutableStateOf(true)
    internal var sheet by mutableStateOf<(@Composable ColumnScope.() -> Unit)?>(null)
    private val menus = mutableStateOf<List<ContextAction>>(emptyList())
    internal val menuActions get() = menus.value
    fun showMenu(actions: List<ContextAction>) { menus.value = actions }
    fun dismissMenu() { menus.value = emptyList() }
    fun showSheet(content: @Composable ColumnScope.() -> Unit) { sheet = content }
    fun dismissSheet() { sheet = null }
    val scrollConnection = object : NestedScrollConnection {
        private var distance = 0f
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (source == NestedScrollSource.UserInput) {
                if (distance * available.y < 0) distance = 0f
                distance += available.y
                if (distance < -32f) navigationVisible = false
                if (distance > 32f) navigationVisible = true
            }
            return Offset.Zero
        }
    }
}
val LocalGlobalUi = staticCompositionLocalOf<GlobalUiState> { error("GlobalUiHost is missing") }
val LocalGlobalNotice = staticCompositionLocalOf<ReadingNoticeHostState> { error("GlobalUiHost is missing") }
data class ContextAction(val label: String, val enabled: Boolean = true, val onClick: () -> Unit)
data class FloatingTab(val id: String, val label: String, val icon: ImageVector)

@Composable
fun rememberGlobalUiState() = remember { GlobalUiState() }

@Composable
fun FloatingNavigation(tabs: List<FloatingTab>, selected: String?, visible: Boolean, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    AnimatedVisibility(visible, enter = fadeIn() + slideInVertically { it }, exit = fadeOut() + slideOutVertically { it }, modifier = modifier) {
        GlassSurface(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).widthIn(max = 600.dp).fillMaxWidth(), opacity = 0.62f) {
            Row(Modifier.padding(4.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                tabs.forEach { tab ->
                    val active = selected == tab.id
                    TextButton(onClick = { onSelect(tab.id) }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(vertical = 8.dp, horizontal = 0.dp)) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(tab.icon, tab.label, tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(tab.label, style = MaterialTheme.typography.labelSmall, color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

/** Native-style floating contextual toolbar, dismissed by outside tap or back. */
@Composable
fun CardContextMenu(actions: List<ContextAction>, onDismiss: () -> Unit) {
    if (actions.isEmpty()) return
    Popup(alignment = Alignment.Center, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        GlassSurface(Modifier.padding(16.dp).widthIn(max = 520.dp), opacity = 0.94f) {
            FlowRow(Modifier.padding(8.dp)) {
                actions.forEach { action ->
                    TextButton(enabled = action.enabled, onClick = { onDismiss(); action.onClick() }) { Text(action.label) }
                }
            }
        }
    }
}

/** Any composable content; supports dragging, scrim dismissal and safe system/IME insets. */
@Composable
fun GlobalBottomSheet(visible: Boolean, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    if (visible) BloomSheet(visible = true, onDismiss = onDismiss) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = 20.dp), content = content)
    }
}

@Composable
fun GlobalUiOverlays(state: GlobalUiState) {
    CardContextMenu(state.menuActions, state::dismissMenu)
    state.sheet?.let { content -> GlobalBottomSheet(true, state::dismissSheet, content) }
}

/** Fire-and-forget notice helper. Action can navigate; all notices can be closed manually. */
@Composable
fun rememberGlobalReminder(): (String, String?, (() -> Unit)?) -> Unit {
    val host = LocalGlobalNotice.current
    val scope = rememberCoroutineScope()
    return remember(host, scope) { { message, label, action ->
        scope.launch { if (host.show(message, label, 5_000) == SnackbarResult.ActionPerformed) action?.invoke() }
    } }
}
