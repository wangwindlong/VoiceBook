package us.wangxy.voicebook.voice.local.sherpa

import java.io.File
import kotlin.concurrent.thread

/*
 * Desktop JVM's contribution to local inference. Same sherpa-onnx Kotlin API and JNI runtime as
 * Android, but the native libraries are not packaged: they live in
 * `desktopApp/native/sherpa-onnx-linux-x64/lib` (gitignored; fetched from the sherpa-onnx
 * releases) and must be on `java.library.path` at JVM startup — the engine classes load
 * `sherpa-onnx-jni` from their static initializers, so a runtime `System.load` is not enough.
 * Model root defaults to `~/.voicebook/models`, mirroring the Android `files/models` layout.
 *
 * system properties:
 *   voicebook.models      model installation root
 */
internal object JvmSherpa {
    private val nativeLibraryLoaded: Boolean by lazy {
        try {
            System.loadLibrary("sherpa-onnx-jni")
            true
        } catch (e: UnsatisfiedLinkError) {
            System.err.println(
                "[JvmSherpa] sherpa-onnx-jni not loadable; local engines disabled. " +
                    "Add -Djava.library.path=<repo>/desktopApp/native/sherpa-onnx-linux-x64/lib " +
                    "(or set LD_LIBRARY_PATH). Cause: ${e.message}",
            )
            false
        }
    }

    fun isSupported(): Boolean = nativeLibraryLoaded

    /** Model install root, or null when the native runtime is unavailable (nothing to download for). */
    fun modelsDir(): File? {
        if (!nativeLibraryLoaded) return null
        val override = System.getProperty("voicebook.models")
        return File(override ?: "${System.getProperty("user.home")}/.voicebook/models")
    }

    fun create(): SherpaBackends {
        if (!nativeLibraryLoaded) return SherpaBackends()
        val dir = modelsDir() ?: return SherpaBackends()
        val stack = buildSherpaStack(findInstalledSherpaModels(listOf(dir)))
        // Warm up OFF the caller thread: this runs during Koin resolution on the UI thread
        // (voice-screen navigation), and loading the acoustic/TTS models takes seconds —
        // blocking there freezes the window (Android does the same on a sherpa-warmup thread).
        // Backend fields are lazy+synchronized, so early use simply joins the load.
        thread(name = "sherpa-warmup", isDaemon = true) {
            stack.warmup.forEach { load -> runCatching(load) }
        }
        return stack.backends
    }
}
