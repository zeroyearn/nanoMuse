package io.github.nanomuse.remote.net

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@Serializable
data class HermesRpcRequest(
    val jsonrpc: String = "2.0",
    val id: Long,
    val method: String,
    val params: JsonElement? = null
)

@Serializable
data class HermesRpcResponse(
    val jsonrpc: String = "2.0",
    val id: Long? = null,
    val result: JsonElement? = null,
    val error: HermesRpcError? = null
)

@Serializable
data class HermesRpcError(
    val code: Int,
    val message: String,
    val data: JsonElement? = null
)

@Serializable
data class HermesRpcEvent(
    val jsonrpc: String = "2.0",
    val method: String,
    val params: JsonElement? = null
)

sealed class GatewayEvent {
    data class Ready(val agentId: String, val profile: String) : GatewayEvent()
    data class ChatDelta(val sessionId: String, val delta: String) : GatewayEvent()
    data class ChatMessage(val sessionId: String, val messageId: String, val role: String, val text: String) : GatewayEvent()
    data class ChatComplete(val sessionId: String, val finishReason: String?) : GatewayEvent()
    data class ToolCall(val sessionId: String, val toolName: String, val callId: String, val argsJson: String) : GatewayEvent()
    data class ToolResult(val sessionId: String, val callId: String, val output: String, val isError: Boolean) : GatewayEvent()
    data class ApprovalRequest(val approvalId: String, val action: String, val detail: String) : GatewayEvent()
    data class StatusChange(val status: String) : GatewayEvent()
}
