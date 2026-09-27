package us.wangxy.voicebook.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import us.wangxy.voicebook.logging.CrashReporter
import us.wangxy.voicebook.reader.store.ReaderStateStore

/**
 * Runs the legacy [ReaderStateStore] import exactly once per process and lets
 * consumers ([us.wangxy.voicebook.screens.library.LibraryViewModel] etc.) await
 * it, so a cold start never shows the setup dialog before the saved server
 * config has been moved into [LocalLibrary].
 */
class LibraryInitializer(
    private val legacy: ReaderStateStore,
    private val library: LocalLibrary,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val ready = CompletableDeferred<Unit>()
    private val mutex = Mutex()
    private var started = false

    suspend fun awaitReady() {
        val launchNeeded = mutex.withLock {
            if (started) false else {
                started = true
                true
            }
        }
        if (launchNeeded) {
            scope.launch {
                runCatching { LibraryMigration.migrate(legacy, library) }
                    .onFailure { CrashReporter.log(it) }
                ready.complete(Unit)
            }
        }
        ready.await()
    }
}
