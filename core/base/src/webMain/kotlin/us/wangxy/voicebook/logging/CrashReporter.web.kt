package us.wangxy.voicebook.logging

import kotlinx.browser.window
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

actual fun createCrashLogStore(): CrashLogStore = WebCrashLogStore

/** Crash ring in localStorage; private-mode failures degrade to session memory. */
private object WebCrashLogStore : CrashLogStore {

    private const val KEY = "voicebook.crashlog"
    private val json = Json { ignoreUnknownKeys = true }
    private var memory: List<String>? = null

    override fun append(entry: String) {
        val lines = (read() + entry).takeLast(CrashLogStore.MaxEntries)
        memory = lines
        try {
            window.localStorage.setItem(KEY, json.encodeToString(ListSerializer(String.serializer()), lines))
        } catch (_: Throwable) {
        }
    }

    override fun readAll(): List<String> = read()

    override fun clear() {
        memory = emptyList()
        try {
            window.localStorage.removeItem(KEY)
        } catch (_: Throwable) {
        }
    }

    private fun read(): List<String> {
        memory?.let { return it }
        return try {
            val raw = window.localStorage.getItem(KEY) ?: return emptyList()
            json.decodeFromString(ListSerializer(String.serializer()), raw)
        } catch (_: Throwable) {
            emptyList()
        }
    }
}

actual fun installCrashHandler(onCrash: (Throwable) -> Unit) {
    // Typed listeners only: the onerror property signature differs between the js
    // and wasmJs bindings, and neither exposes the error payload.
    window.addEventListener("error", { _ ->
        runCatching { onCrash(RuntimeException("uncaught window error")) }
    })
    window.addEventListener("unhandledrejection", { _ ->
        runCatching { onCrash(RuntimeException("unhandled promise rejection")) }
    })
}
