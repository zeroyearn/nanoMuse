package io.github.nanomuse.remote.auth

import android.content.Context
import android.util.Log
import io.github.nanomuse.remote.HermesConfig
import io.github.nanomuse.remote.HermesRemoteMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class HermesAuthManager(private val context: Context) {
    companion object {
        private const val TAG = "HermesAuthManager"
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private var cachedCookie: String? = null

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

    suspend fun login(username: String, password: String): Result<String> = withContext(Dispatchers.IO) {
        val config = HermesRemoteMode.getConfig(context)
        val url = config.gatewayUrl.trimEnd('/') + "/api/auth/login"
        try {
            val payload = JSONObject().apply {
                put("username", username)
                put("password", password)
            }
            val req = Request.Builder()
                .url(url)
                .post(payload.toString().toRequestBody(JSON))
                .build()
            val resp = httpClient.newCall(req).execute()
            val cookie = resp.header("Set-Cookie")
            val body = resp.body?.string().orEmpty()
            if (resp.isSuccessful) {
                cachedCookie = cookie
                val token = runCatching { JSONObject(body).optString("token") }.getOrNull().orEmpty()
                HermesRemoteMode.saveConfig(context, config.copy(token = token))
                Result.success(cookie ?: token)
            } else {
                Result.failure(Exception("HTTP ${resp.code}: $body"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "login failed", e)
            Result.failure(e)
        }
    }

    suspend fun mintWsTicket(): Result<String> = withContext(Dispatchers.IO) {
        val config = HermesRemoteMode.getConfig(context)
        val url = config.gatewayUrl.trimEnd('/') + "/api/auth/ws-ticket"
        try {
            val builder = Request.Builder().url(url).post("{}".toRequestBody(JSON))
            cachedCookie?.let { builder.addHeader("Cookie", it) }
            if (config.token.isNotBlank()) {
                builder.addHeader("X-Hermes-Session-Token", config.token)
            }
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
            } else {
                Result.failure(Exception("HTTP ${resp.code}: $body"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "mintWsTicket failed", e)
            Result.failure(e)
        }
    }
}
