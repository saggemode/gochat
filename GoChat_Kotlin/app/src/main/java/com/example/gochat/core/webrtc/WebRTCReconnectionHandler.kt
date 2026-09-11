package com.example.gochat.core.webrtc

import android.util.Log
import kotlinx.coroutines.*
import org.webrtc.PeerConnection

/**
 * Handles WebRTC call reconnection logic for network transitions.
 */
class WebRTCReconnectionHandler(
    private val rtcClient: WebRTCClient,
    private val onRestartNeeded: () -> Unit
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var isReconnecting = false

    fun handleConnectionChange(state: PeerConnection.IceConnectionState?) {
        Log.d("WebRTCReconnection", "ICE State: $state")
        
        when (state) {
            PeerConnection.IceConnectionState.DISCONNECTED -> {
                // Potential temporary drop, wait and see
                attemptReconnection()
            }
            PeerConnection.IceConnectionState.FAILED -> {
                // Connection failed, force ICE restart
                forceIceRestart()
            }
            PeerConnection.IceConnectionState.CONNECTED,
            PeerConnection.IceConnectionState.COMPLETED -> {
                isReconnecting = false
            }
            else -> {}
        }
    }

    private fun attemptReconnection() {
        if (isReconnecting) return
        isReconnecting = true
        
        scope.launch {
            delay(2000)
            if (isReconnecting) {
                forceIceRestart()
            }
        }
    }

    private fun forceIceRestart() {
        Log.i("WebRTCReconnection", "Forcing ICE Restart...")
        onRestartNeeded()
    }

    fun cancel() {
        scope.cancel()
    }
}
