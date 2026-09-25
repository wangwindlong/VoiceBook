@file:OptIn(ExperimentalForeignApi::class)

package us.wangxy.voicebook.voice.audio

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.get
import kotlinx.cinterop.set
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import platform.AVFAudio.AVAudioPCMBuffer
import platform.AVFAudio.AVAudioPlayerNodeCompletionDataPlayedBack

internal class IosAudioCapture(
    private val audio: IosAudioEngine,
    override val format: AudioFormat = AudioFormat.Speech16k,
) : AudioCapture {
    override val hasPlatformEchoCancellation: Boolean
        get() = audio.voiceProcessingActive

    override fun frames(): Flow<AudioChunk> = callbackFlow {
        if (!audio.requestRecordPermission()) throw IllegalStateException("Microphone permission denied")
        var resampler: StreamingResampler? = null
        var resamplerRate = 0
        audio.micListener = { buffer ->
            val data = buffer.floatChannelData?.get(0)
            val frames = buffer.frameLength.toInt()
            val rate = buffer.format.sampleRate.toInt()
            if (data != null && frames > 0) {
                if (rate != resamplerRate) {
                    resampler = StreamingResampler(rate, format.sampleRate)
                    resamplerRate = rate
                }
                val samples = resampler!!.process(FloatArray(frames) { data[it] })
                if (samples.isNotEmpty()) trySend(AudioChunk(samples.toShortPcm(), format))
            }
        }
        try {
            audio.acquire(needsInput = true)
        } catch (e: Throwable) {
            audio.micListener = null
            throw e
        }
        awaitClose {
            audio.micListener = null
            audio.release()
        }
    }.buffer(Channel.UNLIMITED)
}

/** Plays through the shared engine's player node so voice processing sees the echo reference. */
internal class IosAudioPlayer(
    private val audio: IosAudioEngine,
    private val duckedVolume: Float = 0.25f,
) : AudioPlayer {
    @kotlin.concurrent.Volatile
    private var ducked = false

    override suspend fun play(chunks: Flow<AudioChunk>) {
        audio.acquire(needsInput = false)
        val node = audio.player
        val played = Channel<Unit>(Channel.UNLIMITED)
        var scheduled = 0
        try {
            node.volume = volume()
            node.play()
            var resampler: StreamingResampler? = null
            var rate = 0
            chunks.collect { chunk ->
                if (chunk.samples.isEmpty()) return@collect
                if (chunk.format.sampleRate != rate) {
                    rate = chunk.format.sampleRate
                    resampler = StreamingResampler(rate, IosAudioEngine.PlayerSampleRate)
                }
                val samples = resampler!!.process(chunk.monoFloats())
                if (samples.isEmpty()) return@collect
                val buffer = AVAudioPCMBuffer(pCMFormat = audio.playerFormat, frameCapacity = samples.size.toUInt())
                    ?: error("Could not allocate an audio buffer")
                buffer.frameLength = samples.size.toUInt()
                val out = buffer.floatChannelData!![0]!!
                for (i in samples.indices) out[i] = samples[i]
                // Also called when the node is stopped, so waiting below ends on barge-in too.
                node.scheduleBuffer(
                    buffer,
                    completionCallbackType = AVAudioPlayerNodeCompletionDataPlayedBack,
                    completionHandler = { _ -> played.trySend(Unit) },
                )
                scheduled++
            }
            repeat(scheduled) { played.receive() }
        } finally {
            node.stop()
            audio.release()
        }
    }

    override fun stopImmediately() {
        audio.player.stop()
    }

    override fun setDucked(ducked: Boolean) {
        this.ducked = ducked
        audio.player.volume = volume()
    }

    private fun volume() = if (ducked) duckedVolume else 1f

    private fun AudioChunk.monoFloats(): FloatArray {
        val ch = format.channels
        if (ch == 1) return samples.toFloatPcm()
        return FloatArray(samples.size / ch) { i ->
            var sum = 0f
            for (c in 0 until ch) sum += samples[i * ch + c]
            sum / ch / 32768f
        }
    }
}
