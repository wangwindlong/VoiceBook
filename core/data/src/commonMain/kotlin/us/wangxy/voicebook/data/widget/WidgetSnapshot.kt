package us.wangxy.voicebook.data.widget

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * One-off JSON snapshot the home-screen widgets read (they cannot reach the
 * database). Written by the RSS repository after every sync.
 */
@Serializable
data class WidgetSnapshot(
    val unread: Int = 0,
    val items: List<Item> = emptyList(),
) {
    @Serializable
    data class Item(val id: String, val title: String, val feed: String)

    fun encode(): String = Json.encodeToString(WidgetSnapshot.serializer(), this)

    companion object {
        val Empty = WidgetSnapshot()

        fun decode(text: String): WidgetSnapshot = runCatching {
            Json.decodeFromString(WidgetSnapshot.serializer(), text)
        }.getOrDefault(Empty)
    }
}

/** Platform key-value handoff between the app process and the widget process. */
interface WidgetSnapshotStore {
    fun write(json: String)
    fun read(): String?
}

expect fun createWidgetSnapshotStore(): WidgetSnapshotStore

fun writeWidgetSnapshot(snapshot: WidgetSnapshot) {
    runCatching { createWidgetSnapshotStore().write(snapshot.encode()) }
}

fun readWidgetSnapshot(): WidgetSnapshot =
    createWidgetSnapshotStore().read()?.let { WidgetSnapshot.decode(it) } ?: WidgetSnapshot.Empty
