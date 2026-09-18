package com.example.gochat.data.websocket

import android.util.Log
import com.example.gochat.data.api.ApiConstants
import io.ktor.client.*
import io.ktor.client.plugins.websocket.*

import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Real-time WebSocket client using Ktor with automatic reconnection.
 * Designed for Kotlin Multiplatform compatibility.
 */
class GoChatWebSocket(
    private val client: HttpClient,
    private val json: Json = Json { ignoreUnknownKeys = true }
) {
    companion object {
        private const val TAG = "GoChatWebSocket"
        private const val MAX_RECONNECT_DELAY_MS = 30_000L
        private const val INITIAL_RECONNECT_DELAY_MS = 2_000L
    }

    private val _events = MutableSharedFlow<JsonObject>(extraBufferCapacity = 64)
    val events = _events.asSharedFlow()

    private val _isConnected = MutableStateFlow(false)
    val isConnected = _isConnected.asStateFlow()

    /**
     * Dynamic token provider lambda, allowing WebSocket reconnection to always
     * query a valid/refreshed token from AuthRepository.
     */
    var tokenProvider: (suspend () -> String?)? = null

    private var session: DefaultClientWebSocketSession? = null
    private var reconnectJob: Job? = null
    private var connectionJob: Job? = null
    private var reconnectAttempts = 0
    private var currentToken: String? = null
    private var manuallyDisconnected = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Open a WebSocket connection. If token is null or expired, resolves via [tokenProvider].
     * If [force] is true, cancels any existing connection job/reconnect and immediately connects.
     */
    fun connect(token: String? = null, force: Boolean = false) {
        if (force) {
            reconnectJob?.cancel()
            reconnectJob = null
            connectionJob?.cancel()
            connectionJob = null
            reconnectAttempts = 0
            manuallyDisconnected = false
        } else if (_isConnected.value || connectionJob?.isActive == true) {
            return
        }

        manuallyDisconnected = false
        if (!token.isNullOrBlank()) {
            currentToken = token
        }

        connectionJob = scope.launch {
            try {
                // Ensure we have a fresh, valid token
                val activeToken = tokenProvider?.invoke() ?: currentToken
                if (activeToken.isNullOrBlank()) {
                    Log.w(TAG, "Cannot connect WebSocket: No valid token available")
                    _isConnected.value = false
                    return@launch
                }
                currentToken = activeToken

                val wsUrl = ApiConstants.WS_URL
                val fullUrl = "$wsUrl?token=$activeToken"
                Log.d(TAG, "Connecting to $fullUrl")

                client.webSocket(urlString = fullUrl) {
                    session = this
                    _isConnected.value = true
                    reconnectAttempts = 0
                    Log.d(TAG, "Connected via Ktor")

                    for (frame in incoming) {
                        if (frame is Frame.Text) {
                            val text = frame.readText()
                            try {
                                val parsed = json.parseToJsonElement(text).jsonObject
                                _events.emit(parsed)
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed to parse WS message: ${e.message}")
                            }
                        }
                    }

                    // incoming loop finished
                    Log.d(TAG, "WebSocket session closed")
                    handleDisconnect()
                }
            } catch (e: Exception) {
                if (!manuallyDisconnected) {
                    val msg = e.message.orEmpty().lowercase()
                    Log.e(TAG, "WebSocket error: ${e.message}")
                    // If auth error / 401, clear cached token so reconnect attempts fresh token refresh
                    if (msg.contains("401") || msg.contains("unauthorized") || msg.contains("handshake")) {
                        Log.w(TAG, "WebSocket auth failed; invalidating cached token for next reconnect")
                        currentToken = null
                    }
                    handleDisconnect()
                }
            }
        }
    }

    /**
     * Gracefully close the connection.
     */
    fun disconnect() {
        manuallyDisconnected = true
        reconnectJob?.cancel()
        reconnectJob = null
        connectionJob?.cancel()
        connectionJob = null

        scope.launch {
            try {
                session?.close(CloseReason(CloseReason.Codes.NORMAL, "User logged out"))
            } catch (_: Exception) {}
            session = null
            _isConnected.value = false
            reconnectAttempts = 0
            Log.d(TAG, "Disconnected manually")
        }
    }

    /**
     * Send a JSON payload to the server.
     */
    fun send(payload: JsonObject): Boolean {
        val currentSession = session
        if (!_isConnected.value || currentSession == null) {
            Log.w(TAG, "Cannot send — not connected")
            return false
        }

        return try {
            val text = json.encodeToString(JsonObject.serializer(), payload)
            scope.launch {
                currentSession.send(Frame.Text(text))
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Send error: ${e.message}")
            false
        }
    }

    private fun handleDisconnect() {
        _isConnected.value = false
        session = null

        if (manuallyDisconnected) return

        scheduleReconnect()
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()

        reconnectAttempts++
        val delayMs = (INITIAL_RECONNECT_DELAY_MS * (1L shl (reconnectAttempts - 1).coerceAtMost(4)))
            .coerceAtMost(MAX_RECONNECT_DELAY_MS)

        Log.d(TAG, "Reconnecting in ${delayMs}ms (attempt #$reconnectAttempts)")

        reconnectJob = scope.launch {
            delay(delayMs)
            val token = tokenProvider?.invoke() ?: currentToken
            if (!token.isNullOrBlank()) {
                connect(token)
            } else {
                Log.w(TAG, "Reconnect postponed: No token obtained")
            }
        }
    }

    fun reconnectWithToken(newToken: String) {
        disconnect()
        manuallyDisconnected = false
        connect(newToken, force = true)
    }

    /**
     * Force immediate reconnection with fresh token verification.
     */
    fun reconnect(force: Boolean = true) {
        connect(force = force)
    }
}
