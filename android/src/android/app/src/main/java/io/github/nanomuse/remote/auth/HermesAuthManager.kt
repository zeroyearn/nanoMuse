package io.github.nanomuse.remote.auth

import android.content.Context
import android.util.Log
import io.github.nanomuse.remote.HermesConfig
import io.github.nanomuse.remote.HermesRemoteMode
import io.github.nanomuse.remote.net.HermesHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class HermesAuthManager(private val context: Context) {
    companion object {
        private const val TAG = "HermesAuthManager"
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }

    private val httpClient = HermesHttpClient.newClient()

    suspend fun checkStatus(): Result<JSONObject> = withContext(Dispatchers.IO) {
        val config = HermesRemoteMode.getConfig(context)
        val url = config.gatewayUrl.trimEnd('/') + "/api/status"
        try {
            val req = Request.Builder().url(url).get().build()
            val resp = httpClient.newCall(req).execute()
            val body = resp.body?.string().orEmpty()
            if (resp.isSuccessful) {
                Result.success(JSONObject(body))
            } else {
                Result.failure(Exception("HTTP ${resp.code}: $body"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "checkStatus failed", e)
            Result.failure(e)
        }
    }

    suspend fun login(username: String? = null, password: String? = null): Result<Boolean> = withContext(Dispatchers.IO) {
        val config = HermesRemoteMode.getConfig(context)
        val user = username ?: config.username
        val pass = password ?: config.password
        val url = config.gatewayUrl.trimEnd('/') + "/auth/password-login"
        try {
            val payload = JSONObject().apply {
                put("provider", "basic")
                put("username", user)
                put("password", pass)
            }
            val req = Request.Builder()
                .url(url)
                .post(payload.toString().toRequestBody(JSON))
                .build()
            val resp = httpClient.newCall(req).execute()
            val body = resp.body?.string().orEmpty()
            if (resp.isSuccessful) {
                Log.i(TAG, "Successfully authenticated as $user with basic auth")
                Result.success(true)
            } else {
                Log.w(TAG, "Login failed: HTTP ${resp.code}: $body")
                Result.failure(Exception("HTTP ${resp.code}: $body"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "login exception", e)
            Result.failure(e)
        }
    }

    suspend fun ensureAuthenticated(): Result<Boolean> = withContext(Dispatchers.IO) {
        val config = HermesRemoteMode.getConfig(context)
        val host = config.gatewayUrl.toHttpUrlOrNull()?.host ?: ""
        if (HermesHttpClient.hasValidSession(host)) {
            return@withContext Result.success(true)
        }
        login(config.username, config.password)
    }

    suspend fun mintWsTicket(): Result<String> = withContext(Dispatchers.IO) {
        val config = HermesRemoteMode.getConfig(context)
        ensureAuthenticated()

        val url = config.gatewayUrl.trimEnd('/') + "/api/auth/ws-ticket"
        try {
            val builder = Request.Builder().url(url).post("{}".toRequestBody(JSON))
            val resp = httpClient.newCall(builder.build()).execute()
            val body = resp.body?.string().orEmpty()
            if (resp.isSuccessful) {
                val json = JSONObject(body)
                val ticket = json.optString("ticket")
                if (ticket.isNotBlank()) {
                    Result.success(ticket)
                } else {
                    Result.failure(Exception("Missing ticket in response: $body"))
                }
            } else if (resp.code == 401 || resp.code == 403) {
                // Session expired or missing, retry login once
                val loginRes = login(config.username, config.password)
                if (loginRes.isSuccess) {
                    val retryResp = httpClient.newCall(builder.build()).execute()
                    val retryBody = retryResp.body?.string().orEmpty()
                    if (retryResp.isSuccessful) {
                        val ticket = JSONObject(retryBody).optString("ticket")
                        return@withContext Result.success(ticket)
                    }
                }
                Result.failure(Exception("HTTP ${resp.code}: $body"))
            } else {
                Result.failure(Exception("HTTP ${resp.code}: $body"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "mintWsTicket failed", e)
            Result.failure(e)
        }
    }
}
