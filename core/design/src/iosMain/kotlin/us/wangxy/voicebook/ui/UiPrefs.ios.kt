package us.wangxy.voicebook.ui

import platform.Foundation.NSUserDefaults

actual fun createUiPrefsStore(): UiPrefsStore = IosUiPrefsStore

private object IosUiPrefsStore : UiPrefsStore {
    private val defaults = NSUserDefaults.standardUserDefaults

    override fun load(): UiPrefsState = UiPrefsState(
        bottomTab = defaults.stringForKey(KEY_BOTTOM_TAB).toEnum(BottomTab.READING),
        readingTab = defaults.stringForKey(KEY_READING_TAB).toEnum(ReadingTab.SHELF),
        quickPanelOffsetY = if (defaults.objectForKey(KEY_PANEL_Y) != null) {
            defaults.floatForKey(KEY_PANEL_Y)
        } else {
            -1f
        },
        quickPanelItems = defaults.stringForKey(KEY_PANEL_ITEMS)?.parseItems().orEmpty(),
    )

    override fun save(state: UiPrefsState) {
        defaults.setObject(state.bottomTab.name, forKey = KEY_BOTTOM_TAB)
        defaults.setObject(state.readingTab.name, forKey = KEY_READING_TAB)
        defaults.setFloat(state.quickPanelOffsetY, forKey = KEY_PANEL_Y)
        defaults.setObject(state.quickPanelItems.joinItems(), forKey = KEY_PANEL_ITEMS)
    }

    private const val KEY_BOTTOM_TAB = "ui_bottom_tab"
    private const val KEY_READING_TAB = "ui_reading_tab"
    private const val KEY_PANEL_Y = "ui_quick_panel_y"
    private const val KEY_PANEL_ITEMS = "ui_quick_panel_items"
}
