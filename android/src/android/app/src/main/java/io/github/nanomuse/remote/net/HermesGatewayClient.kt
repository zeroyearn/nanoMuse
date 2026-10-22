package io.github.nanomuse.remote.net

import android.content.Context
import android.util.Log
import io.github.nanomuse.remote.HermesConnectionState
import io.github.nanomuse.remote.HermesRemoteMode
import io.github.nanomuse.remote.auth.HermesAuthManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class HermesGatewayClient(private val context: Context) {
    companion object {
        private const val TAG = "HermesGatewayClient"
    }

    private val authManager = HermesAuthManager(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val nextRpcId = AtomicLong(1)

    private val httpClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS) // WS keeps alive
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private var isIntentionalClose = false

    private val _events = MutableSharedFlow<GatewayEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<GatewayEvent> = _events.asSharedFlow()

    fun connect() {
        isIntentionalClose = false
        scope.launch {
            HermesRemoteMode.setConnectionState(HermesConnectionState.Connecting)
            val config = HermesRemoteMode.getConfig(context)
            val ticketRes = authManager.mintWsTicket()
            val ticket = ticketRes.getOrNull()

            var wsUrl = config.gatewayUrl
                .replace("http://", "ws://")
                .replace("https://", "wss://")
                .trimEnd('/') + "/api/ws"

            if (!ticket.isNullOrBlank()) {
                wsUrl += "?ticket=" + java.net.URLEncoder.encode(ticket, "UTF-8")
            }

            Log.i(TAG, "Connecting to Hermes Gateway WS: $wsUrl")
            val requestBuilder = Request.Builder().url(wsUrl)
            if (config.token.isNotBlank()) {
                requestBuilder.addHeader("X-Hermes-Session-Token", config.token)
            }
            // Subprotocol negotiation
            if (!ticket.isNullOrBlank()) {
                requestBuilder.addHeader("Sec-WebSocket-Protocol", "hermes-gateway-v1, hermes-gateway-ticket.$ticket")
            } else {
                requestBuilder.addHeader("Sec-WebSocket-Protocol", "hermes-gateway-v1")
            }

            webSocket = httpClient.newWebSocket(requestBuilder.build(), object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Log.i(TAG, "Hermes Gateway WS opened")
                    HermesRemoteMode.setConnectionState(
                        HermesConnectionState.Connected(config.gatewayUrl, config.mainBotProfile)
                    )
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    handleIncomingMessage(text)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.w(TAG, "Hermes Gateway WS error: ${t.message}")
                    HermesRemoteMode.setConnectionState(HermesConnectionState.Error(t.message ?: "Connection failure"))
                    if (!isIntentionalClose) {
                        scheduleReconnect()
                    }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    Log.i(TAG, "Hermes Gateway WS closed: code=$code, reason=$reason")
                    HermesRemoteMode.setConnectionState(HermesConnectionState.Disconnected)
                    if (!isIntentionalClose) {
                        scheduleReconnect()
                    }
                }
            })
        }
    }

    fun disconnect() {
        isIntentionalClose = true
        webSocket?.close(1000, "User disconnect")
        webSocket = null
        HermesRemoteMode.setConnectionState(HermesConnectionState.Disconnected)
    }

    private fun scheduleReconnect() {
        scope.launch {
            delay(5000)
            if (!isIntentionalClose && HermesRemoteMode.isRemote(context)) {
                Log.i(TAG, "Attempting reconnect...")
                connect()
            }
        }
    }

    private fun handleIncomingMessage(text: String) {
        try {
            val json = JSONObject(text)
            val method = json.optString("method")
            val params = json.optJSONObject("params") ?: JSONObject()

            when (method) {
                "gateway.ready" -> {
                    val agentId = params.optString("agent_id")
                    val profile = params.optString("profile", HermesRemoteMode.currentMainBot.value)
                    _events.tryEmit(GatewayEvent.Ready(agentId, profile))
                }
                "chat.delta" -> {
                    val sessionId = params.optString("session_id")
                    val delta = params.optString("delta")
                    _events.tryEmit(GatewayEvent.ChatDelta(sessionId, delta))
                }
                "chat.message" -> {
                    val sessionId = params.optString("session_id")
                    val messageId = params.optString("id")
                    val role = params.optString("role")
                    val body = params.optString("text")
                    _events.tryEmit(GatewayEvent.ChatMessage(sessionId, messageId, role, body))
                }
                "chat.complete" -> {
                    val sessionId = params.optString("session_id")
                    val finishReason = params.optString("finish_reason")
                    _events.tryEmit(GatewayEvent.ChatComplete(sessionId, finishReason))
                }
                "chat.tool_call" -> {
                    val sessionId = params.optString("session_id")
                    val toolName = params.optString("tool")
                    val callId = params.optString("call_id")
                    val args = params.optString("args")
                    _events.tryEmit(GatewayEvent.ToolCall(sessionId, toolName, callId, args))
                }
                "approval.request" -> {
                    val id = params.optString("id")
                    val action = params.optString("action")
                    val detail = params.optString("detail")
                    _events.tryEmit(GatewayEvent.ApprovalRequest(id, action, detail))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling message: $text", e)
        }
    }

    fun submitPrompt(sessionId: String, prompt: String, attachmentIds: List<String> = emptyList()): Long {
        val id = nextRpcId.getAndIncrement()
        val payload = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", "prompt.submit")
            put("params", JSONObject().apply {
                put("session_id", sessionId)
                put("prompt", prompt)
                if (attachmentIds.isNotEmpty()) {
                    put("attachments", org.json.JSONArray(attachmentIds))
                }
            })
        }
        webSocket?.send(payload.toString())
        return id
    }

    fun resolveApproval(approvalId: String, approved: Boolean): Long {
        val id = nextRpcId.getAndIncrement()
        val payload = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", "approval.resolve")
            put("params", JSONObject().apply {
                put("id", approvalId)
                put("approved", approved)
            })
        }
        webSocket?.send(payload.toString())
        return id
    }
}
