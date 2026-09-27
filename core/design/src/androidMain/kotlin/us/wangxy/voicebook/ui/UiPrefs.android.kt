package us.wangxy.voicebook.ui

import android.content.Context
import androidx.core.content.edit
import org.koin.mp.KoinPlatform

actual fun createUiPrefsStore(): UiPrefsStore {
    val context = KoinPlatform.getKoin().get<Context>()
    return AndroidUiPrefsStore(context.applicationContext)
}

private class AndroidUiPrefsStore(context: Context) : UiPrefsStore {
    private val prefs = context.getSharedPreferences("ui_prefs", Context.MODE_PRIVATE)

    override fun load(): UiPrefsState = UiPrefsState(
        bottomTab = prefs.getString(KEY_BOTTOM_TAB, null).toEnum(BottomTab.READING),
        readingTab = prefs.getString(KEY_READING_TAB, null).toEnum(ReadingTab.SHELF),
        quickPanelOffsetY = prefs.getFloat(KEY_PANEL_Y, -1f),
        quickPanelItems = prefs.getString(KEY_PANEL_ITEMS, null)?.parseItems().orEmpty(),
    )

    override fun save(state: UiPrefsState) {
        prefs.edit {
            putString(KEY_BOTTOM_TAB, state.bottomTab.name)
                .putString(KEY_READING_TAB, state.readingTab.name)
                .putFloat(KEY_PANEL_Y, state.quickPanelOffsetY)
                .putString(KEY_PANEL_ITEMS, state.quickPanelItems.joinItems())
        }
    }

    private companion object {
        const val KEY_BOTTOM_TAB = "bottom_tab"
        const val KEY_READING_TAB = "reading_tab"
        const val KEY_PANEL_Y = "quick_panel_y"
        const val KEY_PANEL_ITEMS = "quick_panel_items"
    }
}
