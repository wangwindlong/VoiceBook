package us.wangxy.voicebook.logging

import android.content.Context
import java.io.File
import org.koin.core.context.GlobalContext

actual fun createCrashLogStore(): CrashLogStore = AndroidCrashLogStore

private object AndroidCrashLogStore : CrashLogStore {

    // Resolved lazily: first use happens after initKoin has registered the Context.
    private val file: File? by lazy {
        runCatching {
            val context = GlobalContext.get().get<Context>()
            File(context.filesDir, "crash_log.txt")
        }.getOrNull()
    }

    override fun append(entry: String) {
        val target = file ?: return
        runCatching {
            val lines = target.takeIf { it.exists() }?.readLines().orEmpty() + entry
            target.writeText(lines.takeLast(CrashLogStore.MaxEntries).joinToString("\n"))
        }
    }

    override fun readAll(): List<String> =
        file?.takeIf { it.exists() }?.readLines().orEmpty()

    override fun clear() {
        runCatching { file?.delete() }
    }
}

actual fun installCrashHandler(onCrash: (Throwable) -> Unit) {
    val previous = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
        runCatching { onCrash(throwable) }
        previous?.uncaughtException(thread, throwable)
    }
}
