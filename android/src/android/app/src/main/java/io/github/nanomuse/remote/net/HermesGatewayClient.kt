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
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

class HermesGatewayClient(private val context: Context) {
    companion object {
        private const val TAG = "HermesGatewayClient"
    }

    private val authManager = HermesAuthManager(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val nextRpcId = AtomicLong(1)
    private val httpClient = HermesHttpClient.newClient(isWebSocket = true)

    private var webSocket: WebSocket? = null
    private var isIntentionalClose = false

    @Volatile
    var activeSessionId: String? = null
        private set

    private val _events = MutableSharedFlow<GatewayEvent>(extraBufferCapacity = 128)
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
                    Log.i(TAG, "Hermes Gateway WS connection established")
                    HermesRemoteMode.setConnectionState(
                        HermesConnectionState.Connected(config.gatewayUrl, config.mainBotProfile)
                    )
                    // Automatically establish/bind a live session for this client
                    createSession(HermesRemoteMode.currentMainBot.value)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    handleIncomingMessage(text)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.w(TAG, "Hermes Gateway WS failure: ${t.message}, response code: ${response?.code}")
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
        activeSessionId = null
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

            // 1. Handle JSON-RPC method reply frames (with "id")
            if (json.has("id") && (json.has("result") || json.has("error"))) {
                val reqId = json.optLong("id")
                if (json.has("error")) {
                    val err = json.optJSONObject("error")
                    val msg = err?.optString("message") ?: "RPC Error"
                    Log.w(TAG, "RPC error frame for req $reqId: $msg")
                    _events.tryEmit(GatewayEvent.Error(msg))
                    return
                }

                val resObj = json.optJSONObject("result")
                if (resObj != null && resObj.has("session_id")) {
                    val sid = resObj.getString("session_id")
                    Log.i(TAG, "Attached live session: $sid")
                    activeSessionId = sid
                    _events.tryEmit(GatewayEvent.SessionCreated(sid))
                }
                return
            }

            // 2. Handle JSON-RPC server events (method == "event")
            val method = json.optString("method")
            val params = json.optJSONObject("params") ?: JSONObject()
            val eventType = if (method == "event") params.optString("type") else method
            val sid = params.optString("session_id", activeSessionId.orEmpty())
            val payload = params.optJSONObject("payload")

            when (eventType) {
                "gateway.ready" -> {
                    val profile = HermesRemoteMode.currentMainBot.value
                    _events.tryEmit(GatewayEvent.Ready("hermes-agent", profile))
                }
                "message.start" -> {
                    _events.tryEmit(GatewayEvent.MessageStart(sid))
                }
                "message.delta" -> {
                    val delta = payload?.optString("text").orEmpty()
                    if (delta.isNotEmpty()) {
                        _events.tryEmit(GatewayEvent.ChatDelta(sid, delta))
                    }
                }
                "thinking.delta", "reasoning.delta" -> {
                    val delta = payload?.optString("text").orEmpty()
                    if (delta.isNotEmpty()) {
                        _events.tryEmit(GatewayEvent.ThinkingDelta(sid, delta))
                    }
                }
                "status.update" -> {
                    val updateText = payload?.optString("text").orEmpty()
                    if (updateText.isNotEmpty()) {
                        _events.tryEmit(GatewayEvent.StatusUpdate(sid, updateText))
                    }
                }
                "message.complete" -> {
                    val summary = payload?.optString("text").orEmpty()
                    _events.tryEmit(GatewayEvent.ChatComplete(sid, summary))
                }
                "tool.start" -> {
                    val toolName = payload?.optString("tool") ?: payload?.optString("name").orEmpty()
                    val callId = payload?.optString("call_id").orEmpty()
                    val args = payload?.optString("args").orEmpty()
                    _events.tryEmit(GatewayEvent.ToolCall(sid, toolName, callId, args))
                }
                "tool.complete" -> {
                    val callId = payload?.optString("call_id").orEmpty()
                    val output = payload?.optString("output").orEmpty()
                    _events.tryEmit(GatewayEvent.ToolResult(sid, callId, output, false))
                }
                "approval.request" -> {
                    val id = payload?.optString("id").orEmpty()
                    val action = payload?.optString("action").orEmpty()
                    val detail = payload?.optString("detail").orEmpty()
                    _events.tryEmit(GatewayEvent.ApprovalRequest(id, action, detail))
                }
                "error" -> {
                    val errorMsg = params.optString("message", payload?.optString("message").orEmpty())
                    if (errorMsg.isNotBlank()) {
                        _events.tryEmit(GatewayEvent.Error(errorMsg))
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing incoming frame: $text", e)
        }
    }

    fun createSession(profile: String = HermesRemoteMode.currentMainBot.value): Long {
        val id = nextRpcId.getAndIncrement()
        val payload = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", "session.create")
            put("params", JSONObject().apply {
                put("source", "tool")
                put("profile", profile)
            })
        }
        val sent = webSocket?.send(payload.toString()) ?: false
        Log.i(TAG, "createSession sent (id=$id, profile=$profile, success=$sent)")
        return id
    }

    fun submitPrompt(sessionId: String?, text: String): Long {
        val id = nextRpcId.getAndIncrement()
        val sid = sessionId?.takeIf { it.isNotBlank() } ?: activeSessionId.orEmpty()
        val payload = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", "prompt.submit")
            put("params", JSONObject().apply {
                put("session_id", sid)
                put("text", text)
            })
        }
        val sent = webSocket?.send(payload.toString()) ?: false
        Log.i(TAG, "submitPrompt sent (id=$id, session_id=$sid, success=$sent)")
        return id
    }

    fun resolveApproval(approvalId: String, approved: Boolean): Long {
        val id = nextRpcId.getAndIncrement()
        val sid = activeSessionId.orEmpty()
        val payload = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", "approval.respond")
            put("params", JSONObject().apply {
                put("session_id", sid)
                put("execution_generation", 1)
                put("prompt_id", approvalId)
                put("choice", if (approved) "allow" else "deny")
            })
        }
        webSocket?.send(payload.toString())
        return id
    }
}
