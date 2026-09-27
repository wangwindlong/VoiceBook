package us.wangxy.voicebook.data.widget

import java.io.File

actual fun createWidgetSnapshotStore(): WidgetSnapshotStore = JvmWidgetSnapshotStore

private object JvmWidgetSnapshotStore : WidgetSnapshotStore {

    private val file: File? by lazy {
        runCatching {
            val dir = File(System.getProperty("user.home"), ".voicebook").apply { mkdirs() }
            File(dir, "widget_snapshot.json")
        }.getOrNull()
    }

    override fun write(json: String) {
        runCatching { file?.writeText(json) }
    }

    override fun read(): String? = file?.takeIf { it.exists() }?.readText()
}
