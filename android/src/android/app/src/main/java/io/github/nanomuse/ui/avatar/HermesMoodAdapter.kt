package io.github.nanomuse.ui.avatar

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

private const val HAPPY_AFTERGLOW_MS = 3_000L
private const val ERROR_AFTERGLOW_MS = 4_000L

@Composable
fun rememberHermesAgentMood(
    isStreaming: Boolean,
    isWaitingApproval: Boolean = false,
    error: String? = null
): AgentMood {
    var afterglow by remember { mutableStateOf<AgentMood?>(null) }
    var previousStreaming by remember { mutableStateOf(isStreaming) }

    LaunchedEffect(isStreaming) {
        if (previousStreaming && !isStreaming) {
            val failed = error != null
            afterglow = if (failed) AgentMood.ERROR else AgentMood.HAPPY
            delay(if (failed) ERROR_AFTERGLOW_MS else HAPPY_AFTERGLOW_MS)
            afterglow = null
        }
        previousStreaming = isStreaming
    }

    return when {
        isWaitingApproval -> AgentMood.WAITING
        isStreaming -> AgentMood.WORKING
        error != null -> AgentMood.ERROR
        else -> afterglow ?: AgentMood.IDLE
    }
}
