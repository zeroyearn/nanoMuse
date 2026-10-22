package io.github.nanomuse.remote.bot

import android.content.Context
import android.util.Log
import io.github.nanomuse.remote.HermesRemoteMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class MainBotInfo(
    val mainProfile: String,
    val agentId: String,
    val switchReason: String,
    val generation: Long,
    val switchedAt: String,
    val museInstalled: Boolean
)

class MainBotRepository(private val context: Context) {
    companion object {
        private const val TAG = "MainBotRepository"
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }

    private val httpClient = io.github.nanomuse.remote.net.HermesHttpClient.newClient()

    private val _botInfo = MutableStateFlow<MainBotInfo?>(null)
    val botInfo: StateFlow<MainBotInfo?> = _botInfo.asStateFlow()

    suspend fun fetchMainBot(): Result<MainBotInfo> = withContext(Dispatchers.IO) {
        val config = HermesRemoteMode.getConfig(context)
        val url = config.gatewayUrl.trimEnd('/') + "/api/plugins/hermes-workspace-os/main-bot"
        try {
            val reqBuilder = Request.Builder().url(url).get()
            if (config.token.isNotBlank()) {
                reqBuilder.addHeader("X-Hermes-Session-Token", config.token)
            }
            val resp = httpClient.newCall(reqBuilder.build()).execute()
            val body = resp.body?.string().orEmpty()
            if (resp.isSuccessful) {
                val json = JSONObject(body)
                val profile = json.optString("main_profile", "default")
                val info = MainBotInfo(
                    mainProfile = profile,
                    agentId = json.optString("main_agent_id", ""),
                    switchReason = json.optString("switch_reason", ""),
                    generation = json.optLong("cas_generation", 1L),
                    switchedAt = json.optString("switched_at", ""),
                    museInstalled = json.optBoolean("muse_installed", false)
                )
                _botInfo.value = info
                HermesRemoteMode.setMainBotProfile(context, profile)
                Result.success(info)
            } else {
                Result.failure(Exception("HTTP ${resp.code}: $body"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "fetchMainBot error", e)
            Result.failure(e)
        }
    }

    suspend fun switchMainBot(targetProfile: String, reason: String): Result<Boolean> = withContext(Dispatchers.IO) {
        val config = HermesRemoteMode.getConfig(context)
        val url = config.gatewayUrl.trimEnd('/') + "/api/plugins/hermes-workspace-os/main-bot"
        try {
            val payload = JSONObject().apply {
                put("main_profile", targetProfile)
                put("target_profile", targetProfile)
                put("switch_reason", reason)
            }
            val reqBuilder = Request.Builder()
                .url(url)
                .post(payload.toString().toRequestBody(JSON))
            if (config.token.isNotBlank()) {
                reqBuilder.addHeader("X-Hermes-Session-Token", config.token)
            }
            val resp = httpClient.newCall(reqBuilder.build()).execute()
            val body = resp.body?.string().orEmpty()
            if (resp.isSuccessful) {
                fetchMainBot()
                Result.success(true)
            } else {
                Result.failure(Exception("HTTP ${resp.code}: $body"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "switchMainBot error", e)
            Result.failure(e)
        }
    }
}
