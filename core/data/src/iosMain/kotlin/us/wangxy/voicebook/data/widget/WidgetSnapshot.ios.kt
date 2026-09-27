package us.wangxy.voicebook.data.widget

import platform.Foundation.NSUserDefaults

actual fun createWidgetSnapshotStore(): WidgetSnapshotStore = IosWidgetSnapshotStore

/**
 * App Group user defaults so the WidgetKit extension reads the same snapshot;
 * the group entitlement must be added in Xcode for the widget to see it.
 */
private object IosWidgetSnapshotStore : WidgetSnapshotStore {
    private const val KEY = "rss_snapshot_json"
    private val defaults = NSUserDefaults(suiteName = "group.us.wangxy.voicebook")

    override fun write(json: String) {
        defaults?.setObject(json, forKey = KEY)
    }

    override fun read(): String? = defaults?.stringForKey(KEY)
}
