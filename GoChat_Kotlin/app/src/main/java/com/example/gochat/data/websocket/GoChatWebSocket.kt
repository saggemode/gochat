package com.example.gochat.data.websocket

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.*

/**
 * Real-time WebSocket client with automatic reconnection and exponential backoff.
 * Replaces Flutter's `WebSocketService`.
 *
 * Usage:
 * ```
 * val ws = GoChatWebSocket(okHttpClient, json)
 * ws.connect(token)
 *
 * // Collect events
 * scope.launch { ws.events.collect { json -> handleEvent(json) } }
 *
 * // Send
 * ws.send(buildJsonObject { put("type", JsonPrimitive("typing")) })
 * ```
 */
class GoChatWebSocket(
    private val client: OkHttpClient,
    private val json: Json = Json { ignoreUnknownKeys = true }
) {
    companion object {
        private const val TAG = "GoChatWebSocket"
        private const val NORMAL_CLOSE_CODE = 1000
        private const val NORMAL_CLOSE_REASON = "User logged out"
        private const val MAX_RECONNECT_DELAY_MS = 30_000L
        private const val INITIAL_RECONNECT_DELAY_MS = 2_000L
    }

    // ── Public State ─────────────────────────────────────────────

    /** Emits every incoming JSON event from the server. */
    private val _events = MutableSharedFlow<JsonObject>(extraBufferCapacity = 64)
    val events = _events.asSharedFlow()

    /** Current connection state. */
    private val _isConnected = MutableStateFlow(false)
    val isConnected = _isConnected.asStateFlow()

    // ── Internals ────────────────────────────────────────────────

    private var webSocket: WebSocket? = null
    private var reconnectJob: Job? = null
    private var reconnectAttempts = 0
    private var currentToken: String? = null
    private var manuallyDisconnected = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ═══════════════════════════════════════════════════════════════
    // ── Connect / Disconnect ─────────────────────────────────────
    // ═══════════════════════════════════════════════════════════════

    /**
     * Open a WebSocket connection with the given JWT token.
     */
    fun connect(token: String) {
        if (_isConnected.value) return
        manuallyDisconnected = false
        currentToken = token

        val wsUrl = "${com.example.gochat.data.api.ApiConstants.WS_URL}?token=$token"
        Log.d(TAG, "Connecting to $wsUrl")

        val request = Request.Builder()
            .url(wsUrl)
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {

            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "Connected")
                _isConnected.value = true
                reconnectAttempts = 0
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val parsed = json.parseToJsonElement(text).jsonObject
                    _events.tryEmit(parsed)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse WS message: ${e.message}")
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "Server closing: $code $reason")
                webSocket.close(NORMAL_CLOSE_CODE, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "Closed: $code $reason")
                handleDisconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "Failure: ${t.message}", t)
                handleDisconnect()
            }
        })
    }

    /**
     * Gracefully close the connection. No automatic reconnect will happen.
     */
    fun disconnect() {
        manuallyDisconnected = true
        reconnectJob?.cancel()
        reconnectJob = null
        webSocket?.close(NORMAL_CLOSE_CODE, NORMAL_CLOSE_REASON)
        webSocket = null
        _isConnected.value = false
        reconnectAttempts = 0
        Log.d(TAG, "Disconnected manually")
    }

    // ═══════════════════════════════════════════════════════════════
    // ── Send ─────────────────────────────────────────────────────
    // ═══════════════════════════════════════════════════════════════

    /**
     * Send a JSON payload to the server. Returns true if the message was enqueued.
     */
    fun send(payload: JsonObject): Boolean {
        if (!_isConnected.value || webSocket == null) {
            Log.w(TAG, "Cannot send — not connected")
            return false
        }
        return try {
            val text = json.encodeToString(JsonObject.serializer(), payload)
            webSocket?.send(text) ?: false
        } catch (e: Exception) {
            Log.e(TAG, "Send error: ${e.message}")
            false
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // ── Reconnect Logic ──────────────────────────────────────────
    // ═══════════════════════════════════════════════════════════════

    private fun handleDisconnect() {
        _isConnected.value = false
        webSocket = null

        if (manuallyDisconnected) return

        scheduleReconnect()
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()

        reconnectAttempts++
        // Exponential backoff: 2s, 4s, 8s, 16s, capped at 30s
        val delayMs = (INITIAL_RECONNECT_DELAY_MS * (1L shl (reconnectAttempts - 1).coerceAtMost(4)))
            .coerceAtMost(MAX_RECONNECT_DELAY_MS)

        Log.d(TAG, "Reconnecting in ${delayMs}ms (attempt #$reconnectAttempts)")

        reconnectJob = scope.launch {
            delay(delayMs)
            val token = currentToken
            if (token != null) {
                connect(token)
            }
        }
    }

    /**
     * Update the token and force a reconnect (e.g. after token refresh).
     */
    fun reconnectWithToken(newToken: String) {
        disconnect()
        manuallyDisconnected = false
        connect(newToken)
    }
}
