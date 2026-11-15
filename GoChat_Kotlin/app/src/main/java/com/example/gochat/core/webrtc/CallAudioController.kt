package com.example.gochat.core.webrtc

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build

/**
 * Puts the device into call audio mode for the duration of a call and routes audio
 * between the earpiece, the loudspeaker and a connected Bluetooth headset.
 */
class CallAudioController(context: Context) {

    enum class AudioRoute { EARPIECE, SPEAKER, BLUETOOTH }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var previousMode = AudioManager.MODE_NORMAL
    private var focusRequest: AudioFocusRequest? = null
    private var started = false

    var route: AudioRoute = AudioRoute.EARPIECE
        private set

    /** Back-compat: true when the loudspeaker is the active route. */
    val isSpeakerOn: Boolean get() = route == AudioRoute.SPEAKER

    fun start(speakerOn: Boolean) {
        if (started) return
        started = true
        previousMode = audioManager.mode

        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .build()
        audioManager.requestAudioFocus(request)
        focusRequest = request

        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.isMicrophoneMute = false

        // Prefer a connected Bluetooth headset; else honor the speaker preference.
        setRoute(
            when {
                isBluetoothAvailable() -> AudioRoute.BLUETOOTH
                speakerOn -> AudioRoute.SPEAKER
                else -> AudioRoute.EARPIECE
            }
        )
    }

    fun isBluetoothAvailable(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return audioManager.availableCommunicationDevices.any {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
            }
        }
        @Suppress("DEPRECATION")
        return audioManager.isBluetoothScoAvailableOffCall
    }

    /** Cycles earpiece → speaker → bluetooth (if available) → earpiece. Returns the new route. */
    fun cycleRoute(): AudioRoute {
        val next = when (route) {
            AudioRoute.EARPIECE -> AudioRoute.SPEAKER
            AudioRoute.SPEAKER -> if (isBluetoothAvailable()) AudioRoute.BLUETOOTH else AudioRoute.EARPIECE
            AudioRoute.BLUETOOTH -> AudioRoute.EARPIECE
        }
        setRoute(next)
        return route
    }

    fun setRoute(target: AudioRoute) {
        route = target
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            when (target) {
                AudioRoute.SPEAKER -> selectCommunicationDevice(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
                AudioRoute.BLUETOOTH -> {
                    if (!selectCommunicationDevice(AudioDeviceInfo.TYPE_BLUETOOTH_SCO) &&
                        !selectCommunicationDevice(AudioDeviceInfo.TYPE_BLE_HEADSET)
                    ) {
                        audioManager.clearCommunicationDevice()
                        route = AudioRoute.EARPIECE
                    }
                }
                AudioRoute.EARPIECE -> {
                    if (!selectCommunicationDevice(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)) {
                        audioManager.clearCommunicationDevice()
                    }
                }
            }
        } else {
            @Suppress("DEPRECATION")
            when (target) {
                AudioRoute.SPEAKER -> {
                    stopSco()
                    audioManager.isSpeakerphoneOn = true
                }
                AudioRoute.BLUETOOTH -> {
                    audioManager.isSpeakerphoneOn = false
                    audioManager.startBluetoothSco()
                    audioManager.isBluetoothScoOn = true
                }
                AudioRoute.EARPIECE -> {
                    stopSco()
                    audioManager.isSpeakerphoneOn = false
                }
            }
        }
    }

    private fun selectCommunicationDevice(type: Int): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        val device = audioManager.availableCommunicationDevices.firstOrNull { it.type == type } ?: return false
        return audioManager.setCommunicationDevice(device)
    }

    @Suppress("DEPRECATION")
    private fun stopSco() {
        try {
            if (audioManager.isBluetoothScoOn) {
                audioManager.isBluetoothScoOn = false
                audioManager.stopBluetoothSco()
            }
        } catch (_: Exception) {}
    }

    /** Back-compat helper used by existing call screens. */
    fun setSpeaker(on: Boolean) = setRoute(if (on) AudioRoute.SPEAKER else AudioRoute.EARPIECE)

    fun stop() {
        if (!started) return
        started = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.clearCommunicationDevice()
        } else {
            stopSco()
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = false
        }
        audioManager.mode = previousMode
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null
    }
}
