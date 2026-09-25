package us.wangxy.voicebook.voice

import us.wangxy.voicebook.voice.asr.AsrConfig
import us.wangxy.voicebook.voice.local.sherpa.SherpaOfflineAsrEngine
import us.wangxy.voicebook.voice.local.sherpa.SherpaOfflineRecognizerBackend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SherpaOfflineAsrEngineTest {
    /** "Recognizes" one character per 100 ms of audio it is given. */
    private class LengthBackend : SherpaOfflineRecognizerBackend {
        var calls = 0
        override fun decode(samples: FloatArray, sampleRate: Int): String {
            calls++
            return "字".repeat(samples.size / (sampleRate / 10))
        }
    }

    @Test
    fun emitsPartialsWhileStreamingAndFinalOverEverything() = runTest {
        withContext(Dispatchers.Default) {
            val backend = LengthBackend()
            val session = SherpaOfflineAsrEngine({ backend }, partialIntervalMs = 300).startSession(AsrConfig())
            val results = async2 { session.results.toList() }
            session.sendAudio(frame(Marker.USER, 800)) // pre-roll: no partial for it alone
            repeat(40) {
                session.sendAudio(frame(Marker.USER))
                delay(5)
            }
            session.finish()
            val all = withTimeout(5_000) { results() }

            val partials = all.filter { !it.isFinal }
            assertTrue(partials.isNotEmpty(), "offline engine must produce partials for barge-in")
            assertEquals("字".repeat(16), all.last { it.isFinal }.text) // 800 ms + 40 x 20 ms
        }
    }

    private fun <T> kotlinx.coroutines.CoroutineScope.async2(block: suspend () -> T): suspend () -> T {
        val d = kotlinx.coroutines.CompletableDeferred<T>()
        launch { d.complete(block()) }
        return { d.await() }
    }
}
