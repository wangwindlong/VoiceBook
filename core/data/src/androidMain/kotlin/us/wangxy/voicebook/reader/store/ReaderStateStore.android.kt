package us.wangxy.voicebook.reader.store

import android.content.Context
import androidx.core.content.edit
import org.koin.mp.KoinPlatform

actual fun createReaderStateStore(): ReaderStateStore {
    val context = KoinPlatform.getKoin().get<Context>()
    return AndroidReaderStateStore(context.applicationContext)
}

/** One JSON blob in SharedPreferences — app-private storage, same trust level as the theme store. */
private class AndroidReaderStateStore(context: Context) : ReaderStateStore {
    private val prefs = context.getSharedPreferences("reader_state", Context.MODE_PRIVATE)

    override fun load(): ReaderState? =
        prefs.getString(KEY_STATE, null)?.let(::decodeReaderState)

    override fun save(state: ReaderState) {
        prefs.edit { putString(KEY_STATE, state.encode()) }
    }

    private companion object {
        const val KEY_STATE = "state_json"
    }
}
