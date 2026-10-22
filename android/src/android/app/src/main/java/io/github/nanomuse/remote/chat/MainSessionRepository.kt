package io.github.nanomuse.remote.chat

import android.content.Context
import android.util.Log
import io.github.nanomuse.remote.net.GatewayEvent
import io.github.nanomuse.remote.net.HermesGatewayClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

enum class MessageRole {
    USER,
    ASSISTANT,
    SYSTEM,
    TOOL
}

data class RemoteMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: MessageRole,
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isStreaming: Boolean = false,
    val isError: Boolean = false,
    val toolName: String? = null,
    val toolArgs: String? = null
)

data class ActiveApproval(
    val id: String,
    val action: String,
    val detail: String
)

class MainSessionRepository(
    private val context: Context,
    private val gatewayClient: HermesGatewayClient
) {
    companion object {
        private const val TAG = "MainSessionRepository"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _messages = MutableStateFlow<List<RemoteMessage>>(emptyList())
    val messages: StateFlow<List<RemoteMessage>> = _messages.asStateFlow()

    private val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

    private val _activeApproval = MutableStateFlow<ActiveApproval?>(null)
    val activeApproval: StateFlow<ActiveApproval?> = _activeApproval.asStateFlow()

    private var currentSessionId: String = "main-session"

    init {
        scope.launch {
            gatewayClient.events.collect { event ->
                handleGatewayEvent(event)
            }
        }
    }

    private fun handleGatewayEvent(event: GatewayEvent) {
        when (event) {
            is GatewayEvent.Ready -> {
                Log.i(TAG, "Gateway ready for profile: ${event.profile}")
            }
            is GatewayEvent.ChatDelta -> {
                _isStreaming.value = true
                val list = _messages.value.toMutableList()
                val last = list.lastOrNull()
                if (last != null && last.role == MessageRole.ASSISTANT && last.isStreaming) {
                    list[list.lastIndex] = last.copy(text = last.text + event.delta)
                } else {
                    list.add(
                        RemoteMessage(
                            role = MessageRole.ASSISTANT,
                            text = event.delta,
                            isStreaming = true
                        )
                    )
                }
                _messages.value = list
            }
            is GatewayEvent.ChatMessage -> {
                val role = when (event.role.lowercase()) {
                    "user" -> MessageRole.USER
                    "assistant" -> MessageRole.ASSISTANT
                    "tool" -> MessageRole.TOOL
                    else -> MessageRole.SYSTEM
                }
                val list = _messages.value.toMutableList()
                val existingIndex = list.indexOfFirst { it.id == event.messageId }
                if (existingIndex >= 0) {
                    list[existingIndex] = list[existingIndex].copy(text = event.text, isStreaming = false)
                } else {
                    list.add(
                        RemoteMessage(
                            id = event.messageId,
                            role = role,
                            text = event.text,
                            isStreaming = false
                        )
                    )
                }
                _messages.value = list
            }
            is GatewayEvent.ChatComplete -> {
                _isStreaming.value = false
                val list = _messages.value.toMutableList()
                val last = list.lastOrNull()
                if (last != null && last.isStreaming) {
                    list[list.lastIndex] = last.copy(isStreaming = false)
                    _messages.value = list
                }
            }
            is GatewayEvent.ToolCall -> {
                val list = _messages.value.toMutableList()
                list.add(
                    RemoteMessage(
                        role = MessageRole.TOOL,
                        text = "Running ${event.toolName}...",
                        toolName = event.toolName,
                        toolArgs = event.argsJson
                    )
                )
                _messages.value = list
            }
            is GatewayEvent.ToolResult -> {
                val list = _messages.value.toMutableList()
                list.add(
                    RemoteMessage(
                        role = MessageRole.TOOL,
                        text = event.output,
                        isError = event.isError
                    )
                )
                _messages.value = list
            }
            is GatewayEvent.ApprovalRequest -> {
                _activeApproval.value = ActiveApproval(event.approvalId, event.action, event.detail)
            }
            is GatewayEvent.StatusChange -> {
                Log.i(TAG, "Gateway status changed: ${event.status}")
            }
        }
    }

    fun sendMessage(text: String, attachmentIds: List<String> = emptyList()) {
        if (text.isBlank()) return
        val userMsg = RemoteMessage(
            role = MessageRole.USER,
            text = text
        )
        _messages.value = _messages.value + userMsg
        _isStreaming.value = true
        gatewayClient.submitPrompt(currentSessionId, text, attachmentIds)
    }

    fun resolveApproval(approvalId: String, approved: Boolean) {
        gatewayClient.resolveApproval(approvalId, approved)
        if (_activeApproval.value?.id == approvalId) {
            _activeApproval.value = null
        }
    }

    fun clearMessages() {
        _messages.value = emptyList()
        _isStreaming.value = false
        _activeApproval.value = null
    }
}
