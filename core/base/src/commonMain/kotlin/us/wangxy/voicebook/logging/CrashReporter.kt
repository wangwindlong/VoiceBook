package us.wangxy.voicebook.logging

import co.touchlab.kermit.Logger
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Local-only crash reporting (no SaaS): errors go to the platform console via
 * Kermit and into a bounded ring file that users can inspect from settings.
 * Modeled after Twine's CrashReporter facade, minus the Bugsnag transport.
 */
@OptIn(ExperimentalTime::class)
object CrashReporter {
    private val logger = Logger.withTag("VoiceBook")
    private var store: CrashLogStore? = null

    fun install() {
        if (store != null) return
        store = createCrashLogStore()
        installCrashHandler { log(it) }
    }

    fun log(throwable: Throwable) {
        logger.e(throwable) { "error" }
        record("ERROR", throwable.message ?: throwable::class.simpleName ?: "unknown error")
    }

    fun breadcrumb(message: String) {
        logger.i { message }
        record("INFO", message)
    }

    fun readAll(): List<String> = store?.readAll().orEmpty()

    fun clear() {
        store?.clear()
    }

    private fun record(level: String, message: String) {
        val entry = buildString {
            append(Clock.System.now().toEpochMilliseconds())
            append(" [").append(level).append("] ")
            append(message.take(500))
        }
        runCatching { (store ?: return).append(entry) }
    }
}

/** Append-only bounded log; implementations trim to [MaxEntries] entries. */
interface CrashLogStore {
    fun append(entry: String)
    fun readAll(): List<String>
    fun clear()

    companion object {
        const val MaxEntries = 20
    }
}

expect fun createCrashLogStore(): CrashLogStore

/** Hooks the platform uncaught-error path so [onCrash] sees every fatal error. */
expect fun installCrashHandler(onCrash: (Throwable) -> Unit)
