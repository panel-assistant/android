package io.panelassistant.android.sensors

import io.panelassistant.android.Config
import io.panelassistant.android.DashboardAuth
import io.panelassistant.android.HaAuthOwner
import io.panelassistant.android.HaCredentialManager
import io.panelassistant.android.stableOwner
import io.panelassistant.android.util.BoundedStreams
import io.panelassistant.android.util.HaTransportEvidence
import io.panelassistant.android.util.closeBody
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant

internal data class HaApiSession(
    val baseUrl: String,
    val accessToken: String?,
    val rejected: Boolean = false,
    val owner: HaAuthOwner? = null,
    /** Set when the token is absent because minting it failed in TRANSPORT (certificate, DNS, timeout,
     *  5xx) — never when the server rejected the credential or none is configured. */
    val transientDetail: String? = null,
    /** The same failure classified from its exception type. [transientDetail] is raw platform text
     *  and may embed the configured host; this is what a pasteable diagnostic surface carries. */
    val transientEvidence: HaTransportEvidence = HaTransportEvidence.NONE,
    /** Set when no credential attempt was made at all — see [DashboardAuth.Result.notAttempted]. */
    val notAttempted: Boolean = false,
    val route: io.panelassistant.android.HaConnectionRoute? = null,
)

internal fun interface HaApiSessionProvider {
    fun resolve(force: Boolean): HaApiSession
}

/** Takes its credentials from [HaCredentialManager], the one owner every Home Assistant consumer shares. */
internal class DashboardHaApiSessionProvider(
    private val config: Config,
    private val stillCurrent: () -> Boolean = { true },
) : HaApiSessionProvider {
    override fun resolve(force: Boolean): HaApiSession {
        val expectedUrl = config.haUrl.trim().trimEnd('/')
        val result = HaCredentialManager.resolve(
            config = config,
            force = force,
            stillCurrent = { stillCurrent() && config.haUrl.trim().trimEnd('/') == expectedUrl },
        )
        val current = config.haAuthSnapshot()
        val ownsSession = result.session?.accessToken != null &&
            current.url.trim().trimEnd('/') == expectedUrl && current.accessToken == result.session.accessToken &&
            result.route?.let { io.panelassistant.android.HaConnectionRoutes.isCurrent(config, it) } == true
        return HaApiSession(
            result.route?.url ?: expectedUrl,
            result.session?.accessToken.takeIf { ownsSession },
            result.rejected,
            current.takeIf { ownsSession }?.stableOwner(),
            result.transientDetail,
            result.transientEvidence,
            result.notAttempted || (result.session != null && !ownsSession),
            result.route.takeIf { ownsSession },
        )
    }
}

internal interface HaAmbientTransport {
    suspend fun state(baseUrl: String, accessToken: String, entityId: String): JSONObject?
    suspend fun states(baseUrl: String, accessToken: String): JSONArray
    suspend fun config(baseUrl: String, accessToken: String): JSONObject
    suspend fun history(
        baseUrl: String,
        accessToken: String,
        entityId: String,
        startEpochMs: Long,
        endEpochMs: Long,
    ): JSONArray = throw UnsupportedOperationException("history is unavailable")
}

internal class HaAuthenticationException(message: String) : RuntimeException(message)

internal open class HaProtocolException(message: String) : RuntimeException(message)

/** Minimal, unfiltered history of [entityIds] (sorted, comma-joined) between two instants. */
internal fun haHistoryPath(entityIds: Collection<String>, startEpochMs: Long, endEpochMs: Long): String {
    val start = Instant.ofEpochMilli(startEpochMs)
    val end = URLEncoder.encode(Instant.ofEpochMilli(endEpochMs).toString(), Charsets.UTF_8.name())
    val entities = URLEncoder.encode(entityIds.sorted().joinToString(","), Charsets.UTF_8.name())
    return "/api/history/period/$start?end_time=$end&filter_entity_id=$entities&minimal_response&no_attributes&significant_changes_only=0"
}

/**
 * One bounded, authenticated Home Assistant REST GET. Redirects are refused so the bearer token
 * never follows one; 401/403 are [HaAuthenticationException], any other non-2xx is
 * [HaProtocolException], and a 404 is null only when [missingIsNull] says absence is an answer.
 */
internal suspend fun haRestGet(
    baseUrl: String,
    accessToken: String,
    path: String,
    maxBytes: Long,
    connectTimeoutMs: Int,
    readTimeoutMs: Int,
    missingIsNull: Boolean = false,
    openConnection: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
): String? = withContext(Dispatchers.IO) {
    val connection = openConnection(URL(baseUrl.trim().trimEnd('/') + path)).apply {
        instanceFollowRedirects = false
        requestMethod = "GET"
        connectTimeout = connectTimeoutMs
        readTimeout = readTimeoutMs
        setRequestProperty("Authorization", "Bearer $accessToken")
        setRequestProperty("Accept", "application/json")
    }
    try {
        val code = connection.responseCode
        if (code !in 200..299) connection.closeBody()
        when {
            code == HttpURLConnection.HTTP_NOT_FOUND && missingIsNull -> null
            code == HttpURLConnection.HTTP_UNAUTHORIZED || code == HttpURLConnection.HTTP_FORBIDDEN ->
                throw HaAuthenticationException("Home Assistant rejected the REST access token")
            code !in 200..299 -> throw HaProtocolException("Home Assistant REST request failed (HTTP $code)")
            else -> connection.inputStream.use { input ->
                String(BoundedStreams.readBytes(input, maxBytes), Charsets.UTF_8)
            }
        }
    } finally {
        connection.disconnect()
    }
}

internal class KtorHaAmbientTransport(
    private val openConnection: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
) : HaAmbientTransport {
    override suspend fun state(baseUrl: String, accessToken: String, entityId: String): JSONObject? =
        restGet(baseUrl, accessToken, "/api/states/$entityId", MAX_STATE_BYTES, missingIsNull = true)
            ?.let(::JSONObject)

    override suspend fun states(baseUrl: String, accessToken: String): JSONArray =
        JSONArray(checkNotNull(restGet(baseUrl, accessToken, "/api/states", MAX_STATES_BYTES)))

    override suspend fun config(baseUrl: String, accessToken: String): JSONObject =
        JSONObject(checkNotNull(restGet(baseUrl, accessToken, "/api/config", MAX_CONFIG_BYTES)))

    override suspend fun history(
        baseUrl: String,
        accessToken: String,
        entityId: String,
        startEpochMs: Long,
        endEpochMs: Long,
    ): JSONArray {
        validateEntityId(entityId)
        val path = haHistoryPath(listOf(entityId), startEpochMs, endEpochMs)
        return JSONArray(checkNotNull(restGet(baseUrl, accessToken, path, MAX_HISTORY_BYTES, readTimeoutMs = HISTORY_READ_TIMEOUT_MS)))
    }

    private suspend fun restGet(
        baseUrl: String,
        accessToken: String,
        path: String,
        maxBytes: Long,
        missingIsNull: Boolean = false,
        readTimeoutMs: Int = HTTP_READ_TIMEOUT_MS,
    ): String? = haRestGet(baseUrl, accessToken, path, maxBytes, HTTP_CONNECT_TIMEOUT_MS, readTimeoutMs, missingIsNull, openConnection)

    private companion object {
        const val HTTP_CONNECT_TIMEOUT_MS = 8_000
        const val HTTP_READ_TIMEOUT_MS = 8_000
        const val MAX_STATE_BYTES = 256L * 1024L
        const val MAX_CONFIG_BYTES = 256L * 1024L
        const val MAX_STATES_BYTES = 64L * 1024L * 1024L
        const val MAX_HISTORY_BYTES = 4L * 1024L * 1024L
        const val HISTORY_READ_TIMEOUT_MS = 15_000
    }
}
