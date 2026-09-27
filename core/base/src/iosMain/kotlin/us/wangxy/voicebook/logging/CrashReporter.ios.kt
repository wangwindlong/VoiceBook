@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package us.wangxy.voicebook.logging

import kotlin.concurrent.AtomicReference
import kotlinx.cinterop.staticCFunction
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import platform.Foundation.NSException
import platform.Foundation.NSSetUncaughtExceptionHandler
import platform.Foundation.NSUserDefaults

actual fun createCrashLogStore(): CrashLogStore = IosCrashLogStore

/** Crash ring in standard user defaults — same storage pattern as the other iOS stores. */
private object IosCrashLogStore : CrashLogStore {
    private const val KEY = "voicebook.crashlog"
    private val defaults = NSUserDefaults.standardUserDefaults
    private val json = Json { ignoreUnknownKeys = true }

    override fun append(entry: String) {
        runCatching {
            val lines = (readRaw() + entry).takeLast(CrashLogStore.MaxEntries)
            defaults.setObject(
                json.encodeToString(ListSerializer(String.serializer()), lines),
                forKey = KEY,
            )
        }
    }

    override fun readAll(): List<String> = readRaw()

    override fun clear() {
        runCatching { defaults.removeObjectForKey(KEY) }
    }

    private fun readRaw(): List<String> = runCatching {
        defaults.stringForKey(KEY)
            ?.let { json.decodeFromString(ListSerializer(String.serializer()), it) }
            .orEmpty()
    }.getOrDefault(emptyList())
}

/** Holds the app callback so the C-level handler (which cannot capture state) can reach it. */
private object IosCrashHandlerSlot {
    val callback = AtomicReference<((Throwable) -> Unit)?>(null)
}

actual fun installCrashHandler(onCrash: (Throwable) -> Unit) {
    IosCrashHandlerSlot.callback.value = onCrash
    NSSetUncaughtExceptionHandler(
        staticCFunction { exception: NSException? ->
            val handler = IosCrashHandlerSlot.callback.value ?: return@staticCFunction
            runCatching {
                handler(RuntimeException("NSException: ${exception?.reason ?: exception?.name ?: "unknown"}"))
            }
            Unit
        },
    )
}
