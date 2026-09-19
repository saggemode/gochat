package com.example.gochat.core.haptic

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * HapticEngine provides tailored vibration patterns using Android's VibrationEffect API.
 * - Message Sent: Crisp, responsive light tap.
 * - Payment Confirmed: Distinctive, celebratory double-pulse.
 * - Incoming Call: Continuous rhythmic ringing waveform until accepted or dismissed.
 */
object HapticEngine {

    @Volatile
    private var isCallVibrating = false

    private fun getVibrator(context: Context): Vibrator? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * "Message Sent" - Snappy, crisp light tap (e.g. 25ms / EFFECT_CLICK).
     */
    fun playMessageSent(context: Context) {
        try {
            val vibrator = getVibrator(context) ?: return
            if (!vibrator.hasVibrator()) return

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
                } catch (_: Exception) {
                    vibrator.vibrate(VibrationEffect.createOneShot(25, 90))
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(25, 90))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(25)
            }
        } catch (_: Exception) {}
    }

    fun playLightTap(context: Context) {
        playMessageSent(context)
    }

    fun playCancel(context: Context) {
        try {
            val vibrator = getVibrator(context) ?: return
            if (!vibrator.hasVibrator()) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
                } catch (_: Exception) {
                    vibrator.vibrate(VibrationEffect.createOneShot(15, 60))
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(15, 60))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(15)
            }
        } catch (_: Exception) {}
    }

    /**
     * "Payment Confirmed" - Success double-pulse (70ms tap, 80ms pause, 120ms confident tap).
     */
    fun playPaymentConfirmed(context: Context) {
        try {
            val vibrator = getVibrator(context) ?: return
            if (!vibrator.hasVibrator()) return

            val timings = longArrayOf(0, 70, 80, 120)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val amplitudes = intArrayOf(0, 160, 0, 255)
                vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(timings, -1)
            }
        } catch (_: Exception) {}
    }

    /**
     * "Incoming Call" - Continuous rhythmic looping pattern (800ms vibration, 600ms pause, 800ms vibration, 1000ms pause).
     */
    fun startIncomingCall(context: Context) {
        try {
            val vibrator = getVibrator(context) ?: return
            if (!vibrator.hasVibrator()) return
            isCallVibrating = true

            val timings = longArrayOf(0, 800, 600, 800, 1000)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val amplitudes = intArrayOf(0, 200, 0, 255, 0)
                vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, 0)) // repeat starting at index 0
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(timings, 0)
            }
        } catch (_: Exception) {}
    }

    /**
     * Stops any ongoing incoming call vibration.
     */
    fun stopIncomingCall(context: Context) {
        try {
            val vibrator = getVibrator(context) ?: return
            vibrator.cancel()
            isCallVibrating = false
        } catch (_: Exception) {}
    }
}
