package io.github.nanomuse.remote.chat

import android.content.Context
import android.util.Log
import io.github.nanomuse.remote.net.GatewayEvent
import io.github.nanomuse.remote.net.HermesGatewayClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
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
        private const val TIMEOUT_MS = 60_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _messages = MutableStateFlow<List<RemoteMessage>>(emptyList())
    val messages: StateFlow<List<RemoteMessage>> = _messages.asStateFlow()

    private val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

    private val _activeApproval = MutableStateFlow<ActiveApproval?>(null)
    val activeApproval: StateFlow<ActiveApproval?> = _activeApproval.asStateFlow()

    private var currentSessionId: String? = null
    private var streamingTimeoutJob: Job? = null

    init {
        scope.launch {
            gatewayClient.events.collect { event ->
                handleGatewayEvent(event)
            }
        }
    }

    private fun resetTimeout() {
        streamingTimeoutJob?.cancel()
        streamingTimeoutJob = scope.launch {
            delay(TIMEOUT_MS)
            if (_isStreaming.value) {
                Log.w(TAG, "Streaming timed out after 60s")
                _isStreaming.value = false
                val list = _messages.value.toMutableList()
                val last = list.lastOrNull()
                if (last != null && last.isStreaming) {
                    list[list.lastIndex] = last.copy(isStreaming = false)
                    _messages.value = list
                }
            }
        }
    }

    private fun cancelTimeout() {
        streamingTimeoutJob?.cancel()
        streamingTimeoutJob = null
    }

    private fun handleGatewayEvent(event: GatewayEvent) {
        when (event) {
            is GatewayEvent.SessionCreated -> {
                Log.i(TAG, "Session created/bound: ${event.sessionId}")
                currentSessionId = event.sessionId
            }
            is GatewayEvent.Ready -> {
                Log.i(TAG, "Gateway ready for profile: ${event.profile}")
            }
            is GatewayEvent.MessageStart -> {
                _isStreaming.value = true
                resetTimeout()
                val list = _messages.value.toMutableList()
                val last = list.lastOrNull()
                if (last == null || last.role != MessageRole.ASSISTANT || !last.isStreaming) {
                    list.add(
                        RemoteMessage(
                            role = MessageRole.ASSISTANT,
                            text = "",
                            isStreaming = true
                        )
                    )
                    _messages.value = list
                }
            }
            is GatewayEvent.ChatDelta -> {
                _isStreaming.value = true
                resetTimeout()
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
            is GatewayEvent.ThinkingDelta -> {
                _isStreaming.value = true
                resetTimeout()
            }
            is GatewayEvent.StatusUpdate -> {
                val list = _messages.value.toMutableList()
                val last = list.lastOrNull()
                if (last != null && last.role == MessageRole.ASSISTANT && last.isStreaming) {
                    val newText = if (last.text.isBlank()) event.text else "${last.text}\n${event.text}"
                    list[list.lastIndex] = last.copy(text = newText)
                    _messages.value = list
                }
            }
            is GatewayEvent.ChatComplete -> {
                cancelTimeout()
                _isStreaming.value = false
                val list = _messages.value.toMutableList()
                val last = list.lastOrNull()
                if (last != null && last.role == MessageRole.ASSISTANT) {
                    val finalMsg = if (last.text.isBlank() && event.text.isNotBlank()) event.text else last.text
                    list[list.lastIndex] = last.copy(text = finalMsg, isStreaming = false)
                } else if (event.text.isNotBlank()) {
                    list.add(
                        RemoteMessage(
                            role = MessageRole.ASSISTANT,
                            text = event.text,
                            isStreaming = false
                        )
                    )
                }
                _messages.value = list
            }
            is GatewayEvent.ToolCall -> {
                val list = _messages.value.toMutableList()
                list.add(
                    RemoteMessage(
                        role = MessageRole.TOOL,
                        text = "Tool Call: ${event.toolName}",
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
            is GatewayEvent.Error -> {
                cancelTimeout()
                _isStreaming.value = false
                val list = _messages.value.toMutableList()
                val last = list.lastOrNull()
                if (last != null && last.role == MessageRole.ASSISTANT && last.isStreaming) {
                    list[list.lastIndex] = last.copy(text = "${last.text}\n[Error: ${event.message}]", isStreaming = false, isError = true)
                } else {
                    list.add(
                        RemoteMessage(
                            role = MessageRole.SYSTEM,
                            text = "Error: ${event.message}",
                            isError = true
                        )
                    )
                }
                _messages.value = list
            }
        }
    }

    fun sendMessage(text: String) {
        if (text.isBlank()) return
        val list = _messages.value.toMutableList()
        list.add(RemoteMessage(role = MessageRole.USER, text = text))
        _messages.value = list
        _isStreaming.value = true
        resetTimeout()

        gatewayClient.submitPrompt(currentSessionId, text)
    }

    fun resolveApproval(id: String, approved: Boolean) {
        gatewayClient.resolveApproval(id, approved)
        _activeApproval.value = null
    }

    fun resolveApproval(approved: Boolean) {
        val current = _activeApproval.value ?: return
        resolveApproval(current.id, approved)
    }

    fun clearMessages() {
        _messages.value = emptyList()
        _isStreaming.value = false
        cancelTimeout()
    }
}
