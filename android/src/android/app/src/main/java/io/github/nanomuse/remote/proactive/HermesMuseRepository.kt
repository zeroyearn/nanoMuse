package io.github.nanomuse.remote.proactive

import android.content.Context
import android.util.Log
import io.github.nanomuse.feed.FeedPost
import io.github.nanomuse.ideas.Idea
import io.github.nanomuse.ideas.IdeaKind
import io.github.nanomuse.remote.HermesRemoteMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

data class RemoteGoal(
    val id: String,
    val title: String,
    val status: String,
    val deadline: Long?,
    val progress: String?,
    val owner: String?
)

class HermesMuseRepository(private val context: Context) {
    companion object {
        private const val TAG = "HermesMuseRepository"
        private val ISO_DAY = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val _feedPosts = MutableStateFlow<List<FeedPost>>(emptyList())
    val feedPosts: StateFlow<List<FeedPost>> = _feedPosts.asStateFlow()

    private val _ideas = MutableStateFlow<List<Idea>>(emptyList())
    val ideas: StateFlow<List<Idea>> = _ideas.asStateFlow()

    private val _goals = MutableStateFlow<List<RemoteGoal>>(emptyList())
    val goals: StateFlow<List<RemoteGoal>> = _goals.asStateFlow()

    suspend fun fetchFeed(): Result<List<FeedPost>> = withContext(Dispatchers.IO) {
        val config = HermesRemoteMode.getConfig(context)
        val url = config.gatewayUrl.trimEnd('/') + "/api/plugins/hermes-muse/feed"
        try {
            val reqBuilder = Request.Builder().url(url).get()
            if (config.token.isNotBlank()) {
                reqBuilder.addHeader("X-Hermes-Session-Token", config.token)
            }
            val resp = httpClient.newCall(reqBuilder.build()).execute()
            val body = resp.body?.string().orEmpty()
            if (resp.isSuccessful) {
                val list = mutableListOf<FeedPost>()
                val array = runCatching { JSONArray(body) }.getOrElse {
                    runCatching { JSONObject(body).optJSONArray("items") ?: JSONArray() }.getOrDefault(JSONArray())
                }
                val dummyFile = File(context.cacheDir, "remote_feed.md")
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val created = obj.optLong("created", obj.optLong("created_at", System.currentTimeMillis()))
                    val dayStr = obj.optString("day", ISO_DAY.format(Date(created)))
                    val post = FeedPost(
                        day = dayStr,
                        index = obj.optInt("index", i + 1),
                        title = obj.optString("title", "Update"),
                        type = obj.optString("type", "note"),
                        emoji = obj.optString("emoji", "✨"),
                        body = obj.optString("body", obj.optString("content", "")),
                        source = listOf(obj.optString("source", config.mainBotProfile)),
                        createdAt = created,
                        liked = obj.optBoolean("liked", false),
                        file = dummyFile
                    )
                    list.add(post)
                }
                _feedPosts.value = list
                Result.success(list)
            } else {
                // If muse/feed is not yet populated, provide graceful fallback
                val fallback = emptyList<FeedPost>()
                _feedPosts.value = fallback
                Result.success(fallback)
            }
        } catch (e: Exception) {
            Log.e(TAG, "fetchFeed error", e)
            Result.failure(e)
        }
    }

    suspend fun fetchIdeas(): Result<List<Idea>> = withContext(Dispatchers.IO) {
        val config = HermesRemoteMode.getConfig(context)
        val url = config.gatewayUrl.trimEnd('/') + "/api/plugins/hermes-muse/ideas"
        try {
            val reqBuilder = Request.Builder().url(url).get()
            if (config.token.isNotBlank()) {
                reqBuilder.addHeader("X-Hermes-Session-Token", config.token)
            }
            val resp = httpClient.newCall(reqBuilder.build()).execute()
            val body = resp.body?.string().orEmpty()
            if (resp.isSuccessful) {
                val list = mutableListOf<Idea>()
                val array = runCatching { JSONArray(body) }.getOrElse {
                    runCatching { JSONObject(body).optJSONArray("items") ?: JSONArray() }.getOrDefault(JSONArray())
                }
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val kindStr = obj.optString("kind", "CHAT").uppercase(Locale.US)
                    val kind = when (kindStr) {
                        "ROUTINE" -> IdeaKind.ROUTINE
                        "GOAL" -> IdeaKind.GOAL
                        else -> IdeaKind.CHAT
                    }
                    val idea = Idea(
                        id = obj.optString("id", java.util.UUID.randomUUID().toString()),
                        emoji = obj.optString("emoji", "💡"),
                        title = obj.optString("title", "Idea"),
                        body = obj.optString("rationale", obj.optString("description", obj.optString("body", ""))),
                        kind = kind,
                        prompt = obj.optString("prompt", obj.optString("title", "")),
                        category = if (obj.has("category")) obj.optString("category") else null
                    )
                    list.add(idea)
                }
                _ideas.value = list
                Result.success(list)
            } else {
                val fallback = emptyList<Idea>()
                _ideas.value = fallback
                Result.success(fallback)
            }
        } catch (e: Exception) {
            Log.e(TAG, "fetchIdeas error", e)
            Result.failure(e)
        }
    }

    suspend fun fetchGoals(): Result<List<RemoteGoal>> = withContext(Dispatchers.IO) {
        val config = HermesRemoteMode.getConfig(context)
        val url = config.gatewayUrl.trimEnd('/') + "/api/plugins/hermes-muse/goals"
        try {
            val reqBuilder = Request.Builder().url(url).get()
            if (config.token.isNotBlank()) {
                reqBuilder.addHeader("X-Hermes-Session-Token", config.token)
            }
            val resp = httpClient.newCall(reqBuilder.build()).execute()
            val body = resp.body?.string().orEmpty()
            if (resp.isSuccessful) {
                val list = mutableListOf<RemoteGoal>()
                val array = runCatching { JSONArray(body) }.getOrElse {
                    runCatching { JSONObject(body).optJSONArray("items") ?: JSONArray() }.getOrDefault(JSONArray())
                }
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val goal = RemoteGoal(
                        id = obj.optString("id", "goal-$i"),
                        title = obj.optString("title", "Goal"),
                        status = obj.optString("status", "active"),
                        deadline = if (obj.has("deadline")) obj.optLong("deadline") else null,
                        progress = obj.optString("progress", null),
                        owner = obj.optString("owner", config.mainBotProfile)
                    )
                    list.add(goal)
                }
                _goals.value = list
                Result.success(list)
            } else {
                val fallback = emptyList<RemoteGoal>()
                _goals.value = fallback
                Result.success(fallback)
            }
        } catch (e: Exception) {
            Log.e(TAG, "fetchGoals error", e)
            Result.failure(e)
        }
    }
}
