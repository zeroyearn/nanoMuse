package io.github.nanomuse.remote.bot

import android.content.Context
import android.util.Log
import io.github.nanomuse.remote.HermesRemoteMode
import io.github.nanomuse.remote.auth.HermesAuthManager
import io.github.nanomuse.remote.net.HermesHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

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

    private val httpClient = HermesHttpClient.newClient()
    private val authManager = HermesAuthManager(context)

    private val _botInfo = MutableStateFlow<MainBotInfo?>(null)
    val botInfo: StateFlow<MainBotInfo?> = _botInfo.asStateFlow()

    suspend fun fetchMainBot(): Result<MainBotInfo> = withContext(Dispatchers.IO) {
        val config = HermesRemoteMode.getConfig(context)
        val url = config.gatewayUrl.trimEnd('/') + "/api/plugins/hermes-workspace-os/main-bot"
        try {
            authManager.ensureAuthenticated()
            val reqBuilder = Request.Builder().url(url).get()
            var resp = httpClient.newCall(reqBuilder.build()).execute()
            if (resp.code == 401 || resp.code == 403) {
                authManager.login()
                resp = httpClient.newCall(reqBuilder.build()).execute()
            }
            val body = resp.body?.string().orEmpty()
            if (resp.isSuccessful) {
                val json = JSONObject(body)
                val botObj = json.optJSONObject("main_bot") ?: json
                val profile = botObj.optString("main_profile", "default")
                val museProbe = botObj.optJSONObject("muse_probe")
                val isMuseInstalled = museProbe?.optBoolean("installed", false) ?: botObj.optBoolean("muse_installed", false)
                val info = MainBotInfo(
                    mainProfile = profile,
                    agentId = botObj.optString("main_agent_id", ""),
                    switchReason = botObj.optString("switch_reason", ""),
                    generation = botObj.optLong("generation", botObj.optLong("cas_generation", 1L)),
                    switchedAt = botObj.optString("switched_at", ""),
                    museInstalled = isMuseInstalled
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
            authManager.ensureAuthenticated()
            val payload = JSONObject().apply {
                put("main_profile", targetProfile)
                put("target_profile", targetProfile)
                put("switch_reason", reason)
            }
            val reqBuilder = Request.Builder()
                .url(url)
                .post(payload.toString().toRequestBody(JSON))
            var resp = httpClient.newCall(reqBuilder.build()).execute()
            if (resp.code == 401 || resp.code == 403) {
                authManager.login()
                resp = httpClient.newCall(reqBuilder.build()).execute()
            }
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
