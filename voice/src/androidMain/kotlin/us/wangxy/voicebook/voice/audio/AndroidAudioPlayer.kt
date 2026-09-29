package us.wangxy.voicebook.voice.audio

import android.media.AudioAttributes
import android.media.AudioTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import android.media.AudioFormat as AndroidAudioFormat

internal class AndroidAudioPlayer(
    private val routing: AndroidAudioRouting,
    private val duckedVolume: Float = 0.25f,
    /**
     * Long-form playback (audiobooks): stays on the media path (A2DP, media volume) and never
     * switches the audio mode, since there is no concurrent capture to cancel echo for.
     */
    private val mediaOnly: Boolean = false,
) : AudioPlayer {
    @Volatile
    private var activeTrack: AudioTrack? = null

    @Volatile
    private var ducked = false

    override suspend fun play(chunks: Flow<AudioChunk>) = withContext(Dispatchers.IO) {
        val output = TrackOutput()
        if (!mediaOnly) routing.acquire()
        try {
            chunks.collect { chunk ->
                if (chunk.samples.isNotEmpty()) output.write(chunk)
            }
            output.drain()
        } finally {
            output.release()
            if (!mediaOnly) routing.release()
        }
    }

    override fun stopImmediately() {
        activeTrack?.let { track ->
            runCatching {
                track.pause()
                track.flush()
            }
        }
    }

    override fun setDucked(ducked: Boolean) {
        this.ducked = ducked
        activeTrack?.let { runCatching { it.setVolume(volume()) } }
    }

    private fun volume() = if (ducked) duckedVolume else 1f

    /** One AudioTrack per format; re-created if the engine switches formats mid-stream. */
    private inner class TrackOutput {
        private var track: AudioTrack? = null
        private var format: AudioFormat? = null
        private var framesWritten = 0L

        suspend fun write(chunk: AudioChunk) {
            val current = track
            val target = if (current == null || chunk.format != format) {
                current?.let {
                    drain()
                    release()
                }
                createTrack(chunk.format).also {
                    track = it
                    format = chunk.format
                    framesWritten = 0
                    activeTrack = it
                    it.setVolume(volume())
                    it.play()
                }
            } else {
                current
            }
            writeFully(target, chunk.samples)
            framesWritten += chunk.samples.size / chunk.format.channels
        }

        /** Waits until everything written has been rendered. */
        suspend fun drain() {
            val t = track ?: return
            val f = format ?: return
            // A streaming track may not start until its buffer is full; pad with silence.
            writeFully(t, ShortArray(t.bufferSizeInFrames * f.channels))
            val timeoutMs = framesWritten * 1000 / f.sampleRate + 2_000
            withTimeoutOrNull(timeoutMs) {
                while ((t.playbackHeadPosition.toLong() and 0xFFFFFFFFL) < framesWritten) delay(10)
            }
        }

        fun release() {
            val t = track ?: return
            if (activeTrack === t) activeTrack = null
            runCatching {
                t.pause()
                t.flush()
            }
            t.release()
            track = null
            format = null
        }
    }

    private suspend fun writeFully(track: AudioTrack, samples: ShortArray) {
        var offset = 0
        while (offset < samples.size) {
            currentCoroutineContext().ensureActive()
            // Non-blocking so cancellation (barge-in) takes effect within a few milliseconds.
            val n = track.write(samples, offset, samples.size - offset, AudioTrack.WRITE_NON_BLOCKING)
            if (n < 0) error("AudioTrack.write failed: $n")
            if (n == 0) delay(5) else offset += n
        }
    }

    private fun createTrack(format: AudioFormat): AudioTrack {
        val channelMask = if (format.channels == 1) AndroidAudioFormat.CHANNEL_OUT_MONO else AndroidAudioFormat.CHANNEL_OUT_STEREO
        val minBuffer = AudioTrack.getMinBufferSize(format.sampleRate, channelMask, AndroidAudioFormat.ENCODING_PCM_16BIT)
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    // The HAL AEC only uses playback on the voice-communication path as its reference.
                    .setUsage(
                        if (!mediaOnly && routing.communicationPath) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_MEDIA,
                    )
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AndroidAudioFormat.Builder()
                    .setSampleRate(format.sampleRate)
                    .setChannelMask(channelMask)
                    .setEncoding(AndroidAudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(minBuffer, format.samplesFor(100) * 2))
            .build()
    }
}
