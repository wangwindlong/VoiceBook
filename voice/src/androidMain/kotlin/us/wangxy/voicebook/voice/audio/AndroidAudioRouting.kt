package us.wangxy.voicebook.voice.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build

/**
 * Hardware AEC only uses our TTS as its echo reference when both capture and playback run on the
 * voice-communication path, so the audio mode is switched to MODE_IN_COMMUNICATION while either
 * side is active. Routed to the loudspeaker unless a headset is connected.
 */
internal class AndroidAudioRouting(
    context: Context?,
    private val settings: AudioProcessingSettings,
) {
    private val audioManager = context?.getSystemService(AudioManager::class.java)
    private var users = 0
    private var entered = false
    private var savedMode = AudioManager.MODE_NORMAL
    private var savedSpeakerphone = false

    /** Whether the current capture/playback pair runs on the voice-communication path. */
    val communicationPath: Boolean
        @Synchronized get() = if (users > 0) entered else settings.systemAec.value

    @Synchronized
    fun acquire() {
        if (users++ == 0 && settings.systemAec.value) enter()
    }

    @Synchronized
    fun release() {
        if (users > 0 && --users == 0 && entered) exit()
    }

    private fun enter() {
        entered = true
        val am = audioManager ?: return
        savedMode = am.mode
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val devices = am.availableCommunicationDevices
            if (devices.none { it.type in HeadsetTypes }) {
                devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }?.let(am::setCommunicationDevice)
            }
        } else {
            @Suppress("DEPRECATION")
            savedSpeakerphone = am.isSpeakerphoneOn
            @Suppress("DEPRECATION")
            if (!am.isWiredHeadsetOn && !am.isBluetoothScoOn) am.isSpeakerphoneOn = true
        }
    }

    private fun exit() {
        entered = false
        val am = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            am.clearCommunicationDevice()
        } else {
            @Suppress("DEPRECATION")
            am.isSpeakerphoneOn = savedSpeakerphone
        }
        am.mode = savedMode
    }

    private companion object {
        val HeadsetTypes = setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
        )
    }
}
