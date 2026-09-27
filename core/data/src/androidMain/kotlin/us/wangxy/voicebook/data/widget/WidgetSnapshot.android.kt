package us.wangxy.voicebook.data.widget

import android.content.Context
import org.koin.core.context.GlobalContext

actual fun createWidgetSnapshotStore(): WidgetSnapshotStore = AndroidWidgetSnapshotStore

/** SharedPreferences: readable from the Glance widget's own process. */
private object AndroidWidgetSnapshotStore : WidgetSnapshotStore {
    private const val PREFS = "rss_widget"
    private const val KEY = "snapshot_json"

    override fun write(json: String) {
        runCatching {
            val context = GlobalContext.get().get<Context>()
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY, json)
                .apply()
        }
    }

    override fun read(): String? = runCatching {
        val context = GlobalContext.get().get<Context>()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
    }.getOrNull()
}
