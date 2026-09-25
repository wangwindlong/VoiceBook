package us.wangxy.voicebook.voice.local.sherpa

import android.content.Context
import android.util.Log
import java.io.File
import kotlin.concurrent.thread

private const val TAG = "VoiceSherpa"

/**
 * Android's contribution to local inference: the sherpa-onnx JNI library (packaged in
 * `androidApp/src/main/jniLibs`) and the model roots. Model resolution and backend construction
 * are shared with desktop in `jvmShared` (`findInstalledSherpaModels` / `buildSherpaStack`).
 * Missing models or native libs simply leave the corresponding backend null, so routing falls
 * through to the cloud engines.
 */
internal object AndroidSherpa {
    private val nativeLibraryLoaded: Boolean by lazy {
        try {
            System.loadLibrary("sherpa-onnx-jni")
            true
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "sherpa-onnx JNI not available on this ABI; local engines disabled", e)
            false
        }
    }

    fun isSupported(): Boolean = nativeLibraryLoaded

    fun create(context: Context?): SherpaBackends {
        if (context == null || !nativeLibraryLoaded) return SherpaBackends()
        val roots = listOfNotNull(context.getExternalFilesDir("models"), File(context.filesDir, "models"))
        val stack = buildSherpaStack(findInstalledSherpaModels(roots))
        val count = listOfNotNull(
            stack.backends.vad,
            stack.backends.offlineRecognizer,
            stack.backends.tts,
        ).size
        Log.i(TAG, "models under $roots: $count engine(s) ready")

        thread(name = "sherpa-warmup", isDaemon = true) {
            stack.warmup.forEach { load ->
                runCatching(load).onFailure { Log.e(TAG, "model warm-up failed", it) }
            }
        }
        return stack.backends
    }
}
