package io.github.nanomuse.remote

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AgentBackend {
    OPENMINIS_LOCAL,
    HERMES_REMOTE
}

sealed class HermesConnectionState {
    object Disconnected : HermesConnectionState()
    object Connecting : HermesConnectionState()
    data class Connected(val gatewayUrl: String, val mainBotProfile: String) : HermesConnectionState()
    data class Error(val message: String) : HermesConnectionState()
}

data class HermesConfig(
    val gatewayUrl: String = "http://100.100.160.32:8000",
    val token: String = "",
    val mainBotProfile: String = "default",
    val autoConnect: Boolean = true
)

object HermesRemoteMode {
    private const val PREF_NAME = "hermes_remote_preferences"
    private const val KEY_BACKEND = "active_backend"
    private const val KEY_GATEWAY_URL = "gateway_url"
    private const val KEY_TOKEN = "session_token"
    private const val KEY_MAIN_BOT = "main_bot_profile"
    private const val KEY_AUTO_CONNECT = "auto_connect"

    private val _backend = MutableStateFlow(AgentBackend.HERMES_REMOTE)
    val backend: StateFlow<AgentBackend> = _backend.asStateFlow()

    private val _connectionState = MutableStateFlow<HermesConnectionState>(HermesConnectionState.Disconnected)
    val connectionState: StateFlow<HermesConnectionState> = _connectionState.asStateFlow()

    private val _currentMainBot = MutableStateFlow("default")
    val currentMainBot: StateFlow<String> = _currentMainBot.asStateFlow()

    private fun prefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    fun init(context: Context) {
        val sp = prefs(context)
        val modeStr = sp.getString(KEY_BACKEND, AgentBackend.HERMES_REMOTE.name)
        val mode = runCatching { AgentBackend.valueOf(modeStr ?: AgentBackend.HERMES_REMOTE.name) }
            .getOrDefault(AgentBackend.HERMES_REMOTE)
        _backend.value = mode

        val bot = sp.getString(KEY_MAIN_BOT, "default") ?: "default"
        _currentMainBot.value = bot
    }

    fun isRemote(context: Context): Boolean {
        return backend.value == AgentBackend.HERMES_REMOTE
    }

    fun setBackend(context: Context, mode: AgentBackend) {
        _backend.value = mode
        prefs(context).edit().putString(KEY_BACKEND, mode.name).apply()
    }

    fun getConfig(context: Context): HermesConfig {
        val sp = prefs(context)
        return HermesConfig(
            gatewayUrl = sp.getString(KEY_GATEWAY_URL, "http://100.100.160.32:8000") ?: "http://100.100.160.32:8000",
            token = sp.getString(KEY_TOKEN, "") ?: "",
            mainBotProfile = sp.getString(KEY_MAIN_BOT, "default") ?: "default",
            autoConnect = sp.getBoolean(KEY_AUTO_CONNECT, true)
        )
    }

    fun saveConfig(context: Context, config: HermesConfig) {
        prefs(context).edit()
            .putString(KEY_GATEWAY_URL, config.gatewayUrl)
            .putString(KEY_TOKEN, config.token)
            .putString(KEY_MAIN_BOT, config.mainBotProfile)
            .putBoolean(KEY_AUTO_CONNECT, config.autoConnect)
            .apply()
        _currentMainBot.value = config.mainBotProfile
    }

    fun setConnectionState(state: HermesConnectionState) {
        _connectionState.value = state
    }

    fun setMainBotProfile(context: Context, profile: String) {
        _currentMainBot.value = profile
        prefs(context).edit().putString(KEY_MAIN_BOT, profile).apply()
    }
}
