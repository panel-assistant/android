package io.panelassistant.android.util

import kotlinx.coroutines.delay
import java.net.HttpURLConnection
import java.net.URL

/** Builds the loopback and externally advertised URLs for the on-device administration server. */
object LocalAdminEndpoint {
    private const val LOOPBACK = "127.0.0.1"

    /** Bracket IPv6 literals as required by RFC 3986 while leaving DNS names and IPv4 untouched. */
    internal fun authorityHost(host: String): String {
        val clean = host.trim().removePrefix("[").removeSuffix("]")
        return if (clean.contains(':')) "[$clean]" else clean
    }

    fun url(host: String, port: Int, pathAndQuery: String = "/"): String {
        require(port in 1..65535) { "invalid HTTP port" }
        val path = if (pathAndQuery.startsWith('/')) pathAndQuery else "/$pathAndQuery"
        return "http://${authorityHost(host)}:$port$path"
    }

    fun loopbackUrl(port: Int, pathAndQuery: String = "/"): String = url(LOOPBACK, port, pathAndQuery)

    /** Prefer the LAN IPv4 address, then a globally routable IPv6 literal, then loopback. */
    fun externalUrl(ipv4: String?, ipv6: String?, port: Int, pathAndQuery: String = "/"): String =
        url(ipv4?.takeIf { it.isNotBlank() } ?: ipv6?.takeIf { it.isNotBlank() } ?: LOOPBACK, port, pathAndQuery)
}

/**
 * Bounded readiness gate for the in-app administration WebView.
 *
 * The probe is intentionally separate from retry policy so the policy is deterministic in JVM tests.
 * Production callers run [healthProbe] off the main thread and may cancel the surrounding coroutine.
 */
class LocalAdminReadiness(
    private val attempts: Int = 12,
    private val retryDelayMs: Long = 250L,
) {
    init {
        require(attempts > 0) { "attempts must be positive" }
        require(retryDelayMs >= 0L) { "retry delay must not be negative" }
    }

    suspend fun await(
        probe: suspend () -> Boolean,
        pause: suspend (Long) -> Unit = { delay(it) },
    ): Boolean {
        repeat(attempts) { attempt ->
            if (runCatching { probe() }.getOrDefault(false)) return true
            if (attempt + 1 < attempts) pause(retryDelayMs)
        }
        return false
    }

    companion object {
        internal const val MAX_HEALTH_RESPONSE_BYTES = 512L

        internal fun isExpectedHealthResponse(status: Int, body: String): Boolean =
            status == HttpURLConnection.HTTP_OK && body.startsWith("ha-paneld ")

        /** One short, side-effect-free liveness request. */
        fun healthProbe(url: String, timeoutMs: Int = 500): Boolean {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                instanceFollowRedirects = false
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                useCaches = false
            }
            return try {
                val status = connection.responseCode
                if (status != HttpURLConnection.HTTP_OK) {
                    connection.closeBody()
                    false
                } else {
                    val body = connection.inputStream.use { input ->
                        String(BoundedStreams.readBytes(input, MAX_HEALTH_RESPONSE_BYTES), Charsets.UTF_8)
                    }
                    isExpectedHealthResponse(status, body)
                }
            } catch (_: Exception) {
                false
            } finally {
                connection.disconnect()
            }
        }
    }
}
