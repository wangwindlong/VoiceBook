package us.wangxy.voicebook.ui

import java.util.prefs.Preferences

actual fun createUiPrefsStore(): UiPrefsStore = JvmUiPrefsStore

private object JvmUiPrefsStore : UiPrefsStore {
    private val prefs = Preferences.userRoot().node("us.wangxy.voicebook.ui")

    override fun load(): UiPrefsState = UiPrefsState(
        bottomTab = prefs.get(KEY_BOTTOM_TAB, null).toEnum(BottomTab.READING),
        readingTab = prefs.get(KEY_READING_TAB, null).toEnum(ReadingTab.SHELF),
        quickPanelOffsetY = prefs.getFloat(KEY_PANEL_Y, -1f),
        quickPanelItems = prefs.get(KEY_PANEL_ITEMS, null)?.parseItems().orEmpty(),
    )

    override fun save(state: UiPrefsState) {
        prefs.put(KEY_BOTTOM_TAB, state.bottomTab.name)
        prefs.put(KEY_READING_TAB, state.readingTab.name)
        prefs.putFloat(KEY_PANEL_Y, state.quickPanelOffsetY)
        prefs.put(KEY_PANEL_ITEMS, state.quickPanelItems.joinItems())
    }

    private const val KEY_BOTTOM_TAB = "bottom_tab"
    private const val KEY_READING_TAB = "reading_tab"
    private const val KEY_PANEL_Y = "quick_panel_y"
    private const val KEY_PANEL_ITEMS = "quick_panel_items"
}
