package com.example.gochat.shared.websocket

import com.example.gochat.shared.model.Message
import io.ktor.client.*
import io.ktor.client.plugins.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

class GoChatWebSocket(
    private val client: HttpClient,
    private val json: Json = Json { ignoreUnknownKeys = true }
) {
    private val _events = MutableSharedFlow<JsonObject>(extraBufferCapacity = 64)
    val events = _events.asSharedFlow()

    private val _isConnected = MutableStateFlow(false)
    val isConnected = _isConnected.asStateFlow()

    private var session: DefaultClientWebSocketSession? = null
    private var reconnectJob: Job? = null
    private var connectionJob: Job? = null
    private var reconnectAttempts = 0
    private var currentToken: String? = null
    private var manuallyDisconnected = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun connect(wsUrl: String, token: String) {
        if (_isConnected.value || connectionJob?.isActive == true) return
        manuallyDisconnected = false
        currentToken = token

        val fullUrl = "$wsUrl?token=$token"

        connectionJob = scope.launch {
            try {
                client.webSocket(urlString = fullUrl) {
                    session = this
                    _isConnected.value = true
                    reconnectAttempts = 0

                    for (frame in incoming) {
                        if (frame is Frame.Text) {
                            val text = frame.readText()
                            try {
                                val parsed = json.parseToJsonElement(text).jsonObject
                                _events.emit(parsed)
                            } catch (_: Exception) {}
                        }
                    }
                    _isConnected.value = false
                    session = null
                    if (!manuallyDisconnected) scheduleReconnect(wsUrl)
                }
            } catch (e: Exception) {
                _isConnected.value = false
                session = null
                if (!manuallyDisconnected) scheduleReconnect(wsUrl)
            }
        }
    }

    fun disconnect() {
        manuallyDisconnected = true
        reconnectJob?.cancel()
        connectionJob?.cancel()
        scope.launch {
            try {
                session?.close(CloseReason(CloseReason.Codes.NORMAL, "User logged out"))
            } catch (_: Exception) {}
            session = null
            _isConnected.value = false
        }
    }

    fun send(payload: JsonObject): Boolean {
        val currentSession = session
        if (!_isConnected.value || currentSession == null) return false
        
        return try {
            val text = json.encodeToString(JsonObject.serializer(), payload)
            scope.launch {
                currentSession.send(Frame.Text(text))
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun scheduleReconnect(wsUrl: String) {
        reconnectJob?.cancel()
        reconnectAttempts++
        val delayMs = (2000L * (1L shl (reconnectAttempts - 1).coerceAtMost(4))).coerceAtMost(30000L)

        reconnectJob = scope.launch {
            delay(delayMs)
            val token = currentToken
            if (token != null) connect(wsUrl, token)
        }
    }
}
