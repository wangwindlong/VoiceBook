package us.wangxy.voicebook.data.widget

/** No home-screen widgets on the web; nothing to persist. */
actual fun createWidgetSnapshotStore(): WidgetSnapshotStore = WebWidgetSnapshotStore

private object WebWidgetSnapshotStore : WidgetSnapshotStore {
    override fun write(json: String) = Unit
    override fun read(): String? = null
}
