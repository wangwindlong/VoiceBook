package us.wangxy.voicebook.logging

import java.io.File

actual fun createCrashLogStore(): CrashLogStore = JvmCrashLogStore

private object JvmCrashLogStore : CrashLogStore {

    private val file: File? by lazy {
        runCatching {
            val dir = File(System.getProperty("user.home"), ".voicebook").apply { mkdirs() }
            File(dir, "crash_log.txt")
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
