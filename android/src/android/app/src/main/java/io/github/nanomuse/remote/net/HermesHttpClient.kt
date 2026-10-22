package io.github.nanomuse.remote.net

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Shared OkHttpClient configured for Hermes VPS connections.
 * Accepts self-signed TLS certificates (used by Caddy on port 39443)
 * across both Tailscale (100.100.160.32) and Public IP (129.225.181.45).
 * Maintains a shared CookieJar for session authentication across calls.
 */
object HermesHttpClient {
    private val trustAllCerts = arrayOf<TrustManager>(
        object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
    )

    private val sslSocketFactory by lazy {
        val sslContext = SSLContext.getInstance("SSL")
        sslContext.init(null, trustAllCerts, SecureRandom())
        sslContext.socketFactory
    }

    private val inMemoryCookieStore = ConcurrentHashMap<String, MutableList<Cookie>>()

    val sharedCookieJar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            if (cookies.isEmpty()) return
            val host = url.host
            val current = inMemoryCookieStore.getOrPut(host) { mutableListOf() }
            synchronized(current) {
                cookies.forEach { newCookie ->
                    current.removeAll { it.name == newCookie.name }
                    current.add(newCookie)
                }
            }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val list = inMemoryCookieStore[url.host] ?: return emptyList()
            val now = System.currentTimeMillis()
            return synchronized(list) {
                list.filter { it.expiresAt > now }
            }
        }
    }

    fun hasValidSession(host: String): Boolean {
        val list = inMemoryCookieStore[host] ?: return false
        val now = System.currentTimeMillis()
        return synchronized(list) {
            list.any { it.name == "__Host-hermes_session_at" && it.expiresAt > now }
        }
    }

    fun newClient(isWebSocket: Boolean = false): OkHttpClient {
        val trustManager = trustAllCerts[0] as X509TrustManager
        val builder = OkHttpClient.Builder()
            .sslSocketFactory(sslSocketFactory, trustManager)
            .hostnameVerifier { _, _ -> true }
            .cookieJar(sharedCookieJar)

        if (isWebSocket) {
            builder.readTimeout(0, TimeUnit.MILLISECONDS)
                .pingInterval(20, TimeUnit.SECONDS)
        } else {
            builder.connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
        }
        return builder.build()
    }
}
