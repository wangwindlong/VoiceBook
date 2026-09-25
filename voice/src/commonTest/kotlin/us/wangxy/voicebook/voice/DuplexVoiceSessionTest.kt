package us.wangxy.voicebook.voice

import us.wangxy.voicebook.voice.duplex.DuplexVoiceSession
import us.wangxy.voicebook.voice.duplex.SpeakResult
import us.wangxy.voicebook.voice.duplex.VoiceEvent
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DuplexVoiceSessionTest {
    private val ttsText = "你好，我是你的语音助手，很高兴为你服务。"
    private val userText = "帮我查一下明天的航班"

    private class Harness(synthChunks: Int, userText: String, ttsText: String) {
        val capture = FakeCapture()
        val player = FakePlayer()
        val recognizer = FakeRecognizer(userText, echoText = ttsText)
        val session = DuplexVoiceSession(
            capture = capture,
            player = player,
            recognizer = recognizer,
            synthesizer = FakeSynthesizer(synthChunks),
            vad = MarkerVad,
        )
        val events = Channel<VoiceEvent>(Channel.UNLIMITED)
        val seen = mutableListOf<VoiceEvent>()

        suspend inline fun <reified T : VoiceEvent> await(): T = withTimeout(5_000) {
            var found: T? = null
            while (found == null) {
                val e = events.receive()
                seen += e
                if (e is T) found = e
            }
            found
        }

        fun drainSeen(): List<VoiceEvent> {
            while (true) seen += events.tryReceive().getOrNull() ?: break
            return seen
        }
    }

    @Test
    fun bargeInStopsTtsAndKeepsFirstWord() = runTest {
        withContext(Dispatchers.Default) {
            val h = Harness(synthChunks = 200, userText = userText, ttsText = ttsText)
            val collector = launch(start = CoroutineStart.UNDISPATCHED) { h.session.events.collect { h.events.send(it) } }
            h.session.start()

            val speak = async { h.session.speak(ttsText) }
            h.await<VoiceEvent.SpeakStarted>()
            delay(100)

            // AEC removed the echo: the mic hears silence, then the user starts talking.
            h.capture.send(Marker.SILENCE, 200)
            h.capture.send(Marker.USER_FIRST_WORD, 20)
            h.capture.send(Marker.USER, 600)
            h.capture.send(Marker.SILENCE, 1_000)

            val bargeIn = h.await<VoiceEvent.BargeIn>()
            val final = h.await<VoiceEvent.Final>()
            val result = withTimeout(5_000) { speak.await() }

            assertIs<SpeakResult.Interrupted>(result)
            assertEquals(bargeIn.utteranceId, result.bargeInUtteranceId)
            assertTrue(h.player.stoppedImmediately, "TTS must be stopped immediately on barge-in")
            assertTrue(h.player.everDucked, "TTS should be ducked at speech onset")
            assertEquals(userText, final.text)
            assertTrue(h.player.chunksPlayed < 200, "playback must not run to completion")

            val utteranceSession = h.recognizer.sessions.single()
            assertTrue(utteranceSession.heard(Marker.USER_FIRST_WORD), "first word must reach the recognizer via pre-roll")

            collector.cancel()
            h.session.close()
        }
    }

    @Test
    fun leakedEchoIsNeitherRecognizedNorInterrupting() = runTest {
        withContext(Dispatchers.Default) {
            val h = Harness(synthChunks = 50, userText = userText, ttsText = ttsText)
            val collector = launch(start = CoroutineStart.UNDISPATCHED) { h.session.events.collect { h.events.send(it) } }
            h.session.start()

            val speak = async { h.session.speak(ttsText) }
            h.await<VoiceEvent.SpeakStarted>()
            delay(100)

            // AEC failed: our own TTS leaks into the mic loudly enough to trip the VAD.
            h.capture.send(Marker.ECHO, 600)
            h.capture.send(Marker.SILENCE, 1_000)

            h.await<VoiceEvent.EchoSuppressed>()
            val result = withTimeout(5_000) { speak.await() }
            delay(200)
            val events = h.drainSeen()

            assertEquals(SpeakResult.Completed, result)
            assertTrue(events.none { it is VoiceEvent.BargeIn }, "echo must not interrupt TTS")
            assertTrue(events.none { it is VoiceEvent.Partial || it is VoiceEvent.Final }, "echo must not be reported as user speech: $events")

            collector.cancel()
            h.session.close()
        }
    }

    @Test
    fun fillerWordNeitherDucksNorInterrupts() = runTest {
        withContext(Dispatchers.Default) {
            val h = Harness(synthChunks = 50, userText = userText, ttsText = ttsText)
            val collector = launch(start = CoroutineStart.UNDISPATCHED) { h.session.events.collect { h.events.send(it) } }
            h.session.start()

            val speak = async { h.session.speak(ttsText) }
            h.await<VoiceEvent.SpeakStarted>()
            delay(100)

            // A long, loud "嗯——" that easily passes the VAD confirmation during playback.
            h.capture.send(Marker.FILLER, 500)
            h.capture.send(Marker.SILENCE, 1_000)

            val backchannel = h.await<VoiceEvent.Backchannel>()
            val result = withTimeout(5_000) { speak.await() }
            delay(200)
            val events = h.drainSeen()

            assertEquals("嗯", backchannel.text)
            assertEquals(SpeakResult.Completed, result)
            assertTrue(!h.player.everDucked, "a filler word must not duck the TTS")
            assertTrue(!h.player.stoppedImmediately)
            assertTrue(events.none { it is VoiceEvent.BargeIn || it is VoiceEvent.Final }, "$events")

            collector.cancel()
            h.session.close()
        }
    }

    @Test
    fun fillerFollowedByRealSpeechStillBargesIn() = runTest {
        withContext(Dispatchers.Default) {
            val h = Harness(synthChunks = 200, userText = userText, ttsText = ttsText)
            val collector = launch(start = CoroutineStart.UNDISPATCHED) { h.session.events.collect { h.events.send(it) } }
            h.session.start()

            val speak = async { h.session.speak(ttsText) }
            h.await<VoiceEvent.SpeakStarted>()
            delay(100)

            h.capture.send(Marker.FILLER, 300)
            h.capture.send(Marker.USER, 600)
            h.capture.send(Marker.SILENCE, 1_000)

            h.await<VoiceEvent.BargeIn>()
            assertIs<SpeakResult.Interrupted>(withTimeout(5_000) { speak.await() })
            assertEquals(userText, h.await<VoiceEvent.Final>().text)

            collector.cancel()
            h.session.close()
        }
    }

    @Test
    fun fillerWithoutTtsIsAnAnswer() = runTest {
        withContext(Dispatchers.Default) {
            val h = Harness(synthChunks = 0, userText = userText, ttsText = ttsText)
            val collector = launch(start = CoroutineStart.UNDISPATCHED) { h.session.events.collect { h.events.send(it) } }
            h.session.start()

            h.capture.send(Marker.FILLER, 300)
            h.capture.send(Marker.SILENCE, 1_000)

            assertEquals("嗯", h.await<VoiceEvent.Final>().text)

            collector.cancel()
            h.session.close()
        }
    }

    /** Plays a short TTS to completion, then lets [afterMs] of wall-clock time pass. */
    private suspend fun Harness.speakToEnd(afterMs: Long) {
        session.speak(ttsText)
        await<VoiceEvent.SpeakFinished>()
        capture.send(Marker.SILENCE, afterMs.toInt())
        delay(afterMs)
    }

    @Test
    fun quietResidueAfterPlaybackIsNotSpeech() = runTest {
        withContext(Dispatchers.Default) {
            val h = Harness(synthChunks = 10, userText = userText, ttsText = ttsText)
            val collector = launch(start = CoroutineStart.UNDISPATCHED) { h.session.events.collect { h.events.send(it) } }
            h.session.start()
            h.speakToEnd(afterMs = 500)
            val before = h.drainSeen().size

            h.capture.send(Marker.RESIDUE, 600)
            h.capture.send(Marker.SILENCE, 1_000)
            delay(500)
            val after = h.drainSeen().drop(before)

            assertTrue(after.none { it is VoiceEvent.SpeechStarted || it is VoiceEvent.Final }, "$after")
            assertTrue(h.recognizer.sessions.isEmpty(), "residue must not reach the recognizer")

            collector.cancel()
            h.session.close()
        }
    }

    @Test
    fun unconfirmedBlipShortlyAfterPlaybackIsDropped() = runTest {
        withContext(Dispatchers.Default) {
            val h = Harness(synthChunks = 10, userText = userText, ttsText = ttsText)
            val collector = launch(start = CoroutineStart.UNDISPATCHED) { h.session.events.collect { h.events.send(it) } }
            h.session.start()
            h.speakToEnd(afterMs = 500) // past the echo tail, within the post-playback guard

            h.capture.send(Marker.USER, 80) // onset, but too short to confirm
            h.capture.send(Marker.SILENCE, 1_000)
            h.await<VoiceEvent.SpeechEnded>().let { assertTrue(it.discarded) }
            delay(300)

            assertTrue(h.drainSeen().none { it is VoiceEvent.Final }, "${h.seen}")

            collector.cancel()
            h.session.close()
        }
    }

    @Test
    fun quickAnswerRightAfterPlaybackIsRecognized() = runTest {
        withContext(Dispatchers.Default) {
            val h = Harness(synthChunks = 10, userText = userText, ttsText = ttsText)
            val collector = launch(start = CoroutineStart.UNDISPATCHED) { h.session.events.collect { h.events.send(it) } }
            h.session.start()
            h.speakToEnd(afterMs = 500)

            h.capture.send(Marker.USER, 300)
            h.capture.send(Marker.SILENCE, 1_000)

            assertEquals(userText, h.await<VoiceEvent.Final>().text)

            collector.cancel()
            h.session.close()
        }
    }

    @Test
    fun shortBlipWithoutRecentPlaybackStillGoesToAsr() = runTest {
        withContext(Dispatchers.Default) {
            val h = Harness(synthChunks = 0, userText = userText, ttsText = ttsText)
            val collector = launch(start = CoroutineStart.UNDISPATCHED) { h.session.events.collect { h.events.send(it) } }
            h.session.start()

            h.capture.send(Marker.USER, 80) // a clipped "好"
            h.capture.send(Marker.SILENCE, 1_000)

            assertEquals(userText, h.await<VoiceEvent.Final>().text)

            collector.cancel()
            h.session.close()
        }
    }

    @Test
    fun speechWithoutTtsIsRecognizedFromTheFirstFrame() = runTest {
        withContext(Dispatchers.Default) {
            val h = Harness(synthChunks = 0, userText = userText, ttsText = ttsText)
            val collector = launch(start = CoroutineStart.UNDISPATCHED) { h.session.events.collect { h.events.send(it) } }
            h.session.start()

            h.capture.send(Marker.SILENCE, 300)
            h.capture.send(Marker.USER_FIRST_WORD, 20)
            h.capture.send(Marker.USER, 400)
            h.capture.send(Marker.SILENCE, 1_000)

            val final = h.await<VoiceEvent.Final>()
            assertEquals(userText, final.text)
            assertTrue(h.recognizer.sessions.single().heard(Marker.USER_FIRST_WORD))

            collector.cancel()
            h.session.close()
        }
    }
}
