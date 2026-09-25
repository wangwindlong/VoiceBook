package us.wangxy.voicebook.voice

import us.wangxy.voicebook.voice.audio.Pcm16LeDecoder
import us.wangxy.voicebook.voice.audio.PcmRingBuffer
import us.wangxy.voicebook.voice.audio.toLittleEndianBytes
import us.wangxy.voicebook.voice.duplex.BackchannelFilter
import us.wangxy.voicebook.voice.duplex.EchoTextFilter
import us.wangxy.voicebook.voice.duplex.EchoVerdict
import us.wangxy.voicebook.voice.vad.SpeechDetector
import us.wangxy.voicebook.voice.vad.SpeechDetectorConfig
import us.wangxy.voicebook.voice.vad.SpeechEvent
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PcmRingBufferTest {
    @Test
    fun keepsMostRecentSamplesInOrder() {
        val buffer = PcmRingBuffer(5)
        buffer.write(shortArrayOf(1, 2, 3))
        buffer.write(shortArrayOf(4, 5, 6, 7))
        assertContentEquals(shortArrayOf(3, 4, 5, 6, 7), buffer.snapshot())
        assertContentEquals(shortArrayOf(6, 7), buffer.snapshot(2))
    }

    @Test
    fun oversizedWriteKeepsTail() {
        val buffer = PcmRingBuffer(3)
        buffer.write(shortArrayOf(1, 2, 3, 4, 5))
        assertContentEquals(shortArrayOf(3, 4, 5), buffer.snapshot())
    }
}

class Pcm16LeDecoderTest {
    @Test
    fun handlesOddSplits() {
        val samples = shortArrayOf(1, -2, 300, -32768, 32767)
        val bytes = samples.toLittleEndianBytes()
        val decoder = Pcm16LeDecoder()
        val a = decoder.decode(bytes.copyOfRange(0, 3))
        val b = decoder.decode(bytes.copyOfRange(3, 7))
        val c = decoder.decode(bytes.copyOfRange(7, bytes.size))
        assertContentEquals(samples, a + b + c)
    }
}

class SpeechDetectorTest {
    private val config = SpeechDetectorConfig()

    private fun SpeechDetector.feed(p: Float, ms: Int, playback: Boolean = false): List<SpeechEvent> =
        (0 until ms / 20).mapNotNull { update(p, 20, playback) }

    @Test
    fun startConfirmEnd() {
        val d = SpeechDetector(config)
        assertEquals(listOf(SpeechEvent.Start, SpeechEvent.Confirmed), d.feed(0.9f, 400))
        assertEquals(listOf(SpeechEvent.End), d.feed(0f, config.endSilenceMs))
    }

    @Test
    fun shortBlipIsFalseAlarm() {
        val d = SpeechDetector(config)
        assertEquals(listOf(SpeechEvent.Start), d.feed(0.9f, 60))
        assertEquals(listOf(SpeechEvent.FalseAlarm), d.feed(0f, config.falseAlarmSilenceMs))
    }

    @Test
    fun playbackUsesStricterThresholdAndLongerConfirmation() {
        val d = SpeechDetector(config)
        assertEquals(emptyList(), d.feed(0.6f, 400, playback = true))
        assertEquals(listOf(SpeechEvent.Start), d.feed(0.9f, 200, playback = true))
        assertEquals(listOf(SpeechEvent.Confirmed), d.feed(0.9f, 60, playback = true))
    }

    @Test
    fun noEventOnSilence() {
        assertNull(SpeechDetector(config).update(0f, 20, false))
    }
}

class BackchannelFilterTest {
    private val filter = BackchannelFilter()

    @Test
    fun fillerWords() {
        listOf("嗯", "哦", "嗯嗯", "哦哦哦", "啊？", "呃…", "嗯哼", "恩").forEach {
            assertTrue(filter.isBackchannel(it), it)
        }
    }

    @Test
    fun realRequests() {
        listOf("停", "等一下", "不对", "嗯我想问一下", "哦对了明天呢", "啊啊啊啊啊").forEach {
            assertFalse(filter.isBackchannel(it), it)
        }
    }
}

class EchoTextFilterTest {
    private val filter = EchoTextFilter()
    private val spoken = "你好，我是你的语音助手，很高兴为你服务。"

    @Test
    fun exactFragmentOfPromptIsEcho() {
        assertEquals(EchoVerdict.Echo, filter.judge("我是你的语音助手", spoken))
    }

    @Test
    fun slightlyMisrecognizedPromptIsEcho() {
        assertEquals(EchoVerdict.Echo, filter.judge("很高兴为您服务", spoken))
    }

    @Test
    fun userSpeechIsUser() {
        assertEquals(EchoVerdict.User, filter.judge("帮我查一下明天的航班", spoken))
    }

    @Test
    fun shortTextContainedInPromptIsUndetermined() {
        assertEquals(EchoVerdict.Undetermined, filter.judge("你好", spoken))
        assertEquals(EchoVerdict.User, filter.judge("停下", spoken))
    }

    @Test
    fun nothingPlayingMeansUser() {
        assertEquals(EchoVerdict.User, filter.judge("你好", null))
    }
}
