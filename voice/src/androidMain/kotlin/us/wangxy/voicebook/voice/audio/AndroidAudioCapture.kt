package us.wangxy.voicebook.voice.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.NoiseSuppressor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import android.media.AudioFormat as AndroidAudioFormat

internal class AndroidAudioCapture(
    private val context: Context?,
    private val routing: AndroidAudioRouting,
    override val format: AudioFormat = AudioFormat.Speech16k,
    private val frameMs: Int = 20,
) : AudioCapture {
    override val hasPlatformEchoCancellation: Boolean
        get() = routing.communicationPath && AcousticEchoCanceler.isAvailable()

    @SuppressLint("MissingPermission")
    override fun frames(): Flow<AudioChunk> = flow {
        if (context != null &&
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        ) {
            throw SecurityException("RECORD_AUDIO permission not granted")
        }
        val frameSamples = format.samplesFor(frameMs)
        val channelMask = if (format.channels == 1) AndroidAudioFormat.CHANNEL_IN_MONO else AndroidAudioFormat.CHANNEL_IN_STEREO
        val minBuffer = AudioRecord.getMinBufferSize(format.sampleRate, channelMask, AndroidAudioFormat.ENCODING_PCM_16BIT)
        routing.acquire()
        val systemAec = routing.communicationPath
        val record = AudioRecord(
            // VOICE_COMMUNICATION enables the platform AEC / NS pipeline on most devices.
            if (systemAec) MediaRecorder.AudioSource.VOICE_COMMUNICATION else MediaRecorder.AudioSource.MIC,
            format.sampleRate,
            channelMask,
            AndroidAudioFormat.ENCODING_PCM_16BIT,
            // ~1 s, so a stall of the consumer never makes AudioRecord drop audio.
            maxOf(minBuffer, format.samplesFor(1_000) * 2),
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            routing.release()
            error("AudioRecord failed to initialize")
        }

        val effects = mutableListOf<AudioEffect>()
        if (systemAec && AcousticEchoCanceler.isAvailable()) {
            AcousticEchoCanceler.create(record.audioSessionId)?.apply { enabled = true }?.let(effects::add)
        }
        if (systemAec && NoiseSuppressor.isAvailable()) {
            NoiseSuppressor.create(record.audioSessionId)?.apply { enabled = true }?.let(effects::add)
        }

        try {
            record.startRecording()
            while (currentCoroutineContext().isActive) {
                val buffer = ShortArray(frameSamples)
                var read = 0
                while (read < frameSamples) {
                    val n = record.read(buffer, read, frameSamples - read)
                    if (n < 0) error("AudioRecord.read failed: $n")
                    read += n
                }
                emit(AudioChunk(buffer, format))
            }
        } finally {
            runCatching { record.stop() }
            record.release()
            effects.forEach { it.release() }
            routing.release()
        }
    }.flowOn(Dispatchers.IO)
}
