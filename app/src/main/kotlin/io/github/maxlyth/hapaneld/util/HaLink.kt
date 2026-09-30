package io.github.maxlyth.hapaneld.util

import android.util.Log
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.io.InputStream

/**
 * Best-effort resolution of this panel's Home Assistant **device-settings URL**, so the info page can offer
 * a one-click "Open in Home Assistant". HA's device-registry id is random (not derivable) and never reported
 * back over MQTT, so it must be read from HA's API.
 *
 * The built-in renderer path authenticates with its existing HA access token. Older/external-renderer
 * installations can fall back to the panel's **MQTT username/password** when those credentials are also
 * a real HA account. The device id then comes from the WebSocket command
 * `config/entity_registry/list_for_display` — the SAME command the frontend uses to render entities for
 * **every** user, so it works for **non-admin** accounts too (unlike the admin-only `/api/template`). We find
 * this panel's own entity by its stable panel-id prefix, with its historical friendly-name prefix as a
 * compatibility fallback, and take the entry's `di` (device id).
 *
 * Anonymous brokers and any failure (bad creds, MFA, a non-HA broker login, API unreachable, no matching
 * entity) return null and the link is hidden.
 */
object HaLink {
    private const val TAG = "HaLink"
    private const val JSON = "application/json"
    private const val FORM = "application/x-www-form-urlencoded"
    internal const val MAX_WS_FRAME_BYTES = 16L * 1024L * 1024L
    internal const val WS_RESOLUTION_DEADLINE_MS = 15_000L
    internal const val MAX_HTTP_RESPONSE_BYTES = 2L * 1024L * 1024L
    internal const val MAX_TOKEN_RESPONSE_BYTES = 64L * 1024L
    internal const val MAX_OAUTH_TOKEN_CHARS = 16 * 1024
    internal const val MAX_OAUTH_EXPIRES_IN_SEC = 365L * 24L * 60L * 60L

    /** @param base HA origin from zeroconf, e.g. "https://hass.example". @return device-page URL or null. */
    fun resolve(
        base: String,
        user: String,
        pass: String,
        deviceNames: Collection<String>,
        preferIpv4: Boolean,
        ipv4Only: Boolean,
    ): String? {
        if (user.isBlank() || pass.isBlank()) return null // anonymous broker → can't auth
        return runCatching {
            val token = login(base, user, pass) ?: return null
            val slugs = deviceNames.map(::slug).filter(String::isNotBlank).distinct()
            val devId = deviceIdViaWs(base, token, slugs, preferIpv4, ipv4Only)
                ?: run { Log.i(TAG, "no HA entity matching ${slugs.joinToString()}"); return null }
            // Build the link off HA's canonical internal_url (so logging in via the broker host still yields a
            // tidy hass.example link), falling back to the URL we logged in at.
            val linkBase = internalUrl(base, token) ?: base
            "${linkBase.trimEnd('/')}/config/devices/device/$devId".also { Log.i(TAG, "HA device link resolved") }
        }.onFailure { Log.i(TAG, "resolve failed: ${it.message}") }.getOrNull()
    }

    /** Resolve through the built-in renderer's already-authenticated HA connection. The returned link is
     * deliberately based on [base], not an unrelated advertised internal/external URL: the same native
     * endpoint which rendered the panel is the authoritative server and reverse-proxy base path. */
    fun resolveWithAccessToken(
        base: String,
        token: String,
        deviceNames: Collection<String>,
        preferIpv4: Boolean,
        ipv4Only: Boolean,
    ): String? {
        if (base.isBlank() || token.isBlank()) return null
        return runCatching {
            val normalized = base.trim().trimEnd('/')
            val slugs = deviceNames.map(::slug).filter(String::isNotBlank).distinct()
            val devId = deviceIdViaWs(normalized, token, slugs, preferIpv4, ipv4Only)
                ?: run { Log.i(TAG, "no HA entity matching ${slugs.joinToString()}"); return null }
            "$normalized/config/devices/device/$devId".also { Log.i(TAG, "native HA device link resolved") }
        }.onFailure { Log.i(TAG, "native resolve failed: ${it.message}") }.getOrNull()
    }

    /** Cache owner for a resolution. It includes panel identity because the HA device can change without
     * the renderer endpoint changing (for example after reprovisioning with another panel id). */
    internal fun resolutionTarget(base: String, panelId: String): String {
        val normalizedBase = base.trim().trimEnd('/')
        // Keep this persisted value text-safe for migration/export compatibility and
        // unambiguous without relying on a delimiter which could occur in a URL.
        return "v1:${normalizedBase.length}:$normalizedBase:${slug(panelId)}"
    }

    /** An access token plus its remaining lifetime in seconds (as HA's /auth/token reports `expires_in`). */
    data class TokenSet(val accessToken: String, val expiresInSec: Long)

    /**
     * Outcome of a refresh-token exchange. The distinction matters on an unattended panel: a
     * [Rejected] means the server terminally refused the unchanged refresh request (invalid/revoked
     * token, wrong required client id, or inactive user), while a [Transient] failure (HA
     * restarting, network blip, timeout, 5xx) says nothing about the token and must never be treated
     * as a revocation — nuking a valid login over a flaky moment leaves a wall panel dead until an
     * admin re-provisions it.
     */
    sealed class Refresh {
        data class Success(val tokens: TokenSet) : Refresh()
        object Rejected : Refresh()
        /** [detail] names the transport failure (certificate, DNS, timeout, HTTP 5xx) so an admission
         *  screen can point at the real fault instead of blaming the credential. It is the raw server
         *  or platform text and stays ON the panel; [evidence] is the same failure classified from the
         *  exception type, and is the only form a pasteable diagnostic surface may carry. */
        data class Transient(
            val detail: String? = null,
            val evidence: HaTransportEvidence = HaTransportEvidence.UNKNOWN,
        ) : Refresh()
    }

    data class OAuthTokens(
        val accessToken: String,
        val refreshToken: String,
        val expiresInSec: Long,
    )

    sealed class AuthorizationCodeExchange {
        data class Success(val tokens: OAuthTokens) : AuthorizationCodeExchange()
        object Rejected : AuthorizationCodeExchange()
        object Transient : AuthorizationCodeExchange()
    }

    /** Exchange a browser-issued authorization code without ever exposing the resulting credentials to
     * the browser. Invalid/expired codes are terminal; transport, server and malformed-success failures
     * remain retryable by starting a fresh browser login. */
    fun exchangeAuthorizationCode(
        base: String,
        code: String,
        clientId: String,
    ): AuthorizationCodeExchange {
        if (code.isBlank() || code.length > 4_096 || clientId.isBlank() || clientId.length > 2_048) {
            return AuthorizationCodeExchange.Rejected
        }
        return try {
            val body = post(
                "${base.trimEnd('/')}/auth/token",
                null,
                "grant_type=authorization_code&code=${enc(code)}&client_id=${enc(clientId)}",
                FORM,
                MAX_TOKEN_RESPONSE_BYTES,
            )
            parseAuthorizationCodeTokens(body)?.let(AuthorizationCodeExchange::Success)
                ?: AuthorizationCodeExchange.Transient
        } catch (e: HttpError) {
            Log.i(TAG, "authorization-code exchange failed: HTTP ${e.code}")
            if (e.code in intArrayOf(400, 401, 403)) AuthorizationCodeExchange.Rejected
            else AuthorizationCodeExchange.Transient
        } catch (e: Exception) {
            Log.i(TAG, "authorization-code exchange failed: ${e.javaClass.simpleName}")
            AuthorizationCodeExchange.Transient
        }
    }

    internal fun parseAuthorizationCodeTokens(body: String): OAuthTokens? = runCatching {
        val json = JSONObject(body)
        val access = json.opt("access_token") as? String ?: return@runCatching null
        val refresh = json.opt("refresh_token") as? String ?: return@runCatching null
        val expiresValue = json.opt("expires_in") as? Number ?: return@runCatching null
        val expiresDouble = expiresValue.toDouble()
        if (!expiresDouble.isFinite() || expiresDouble % 1.0 != 0.0) return@runCatching null
        val expires = expiresValue.toLong()
        val tokenType = when (val value = json.opt("token_type")) {
            null -> ""
            is String -> value
            else -> return@runCatching null
        }
        if (access.isBlank() || access.length > MAX_OAUTH_TOKEN_CHARS ||
            refresh.isBlank() || refresh.length > MAX_OAUTH_TOKEN_CHARS ||
            expires !in 1..MAX_OAUTH_EXPIRES_IN_SEC ||
            (tokenType.isNotBlank() && !tokenType.equals("Bearer", ignoreCase = true))
        ) return@runCatching null
        OAuthTokens(access, refresh, expires)
    }.getOrNull()

    /**
     * Exchange a refresh token for a fresh access token (OAuth `grant_type=refresh_token`, the same call
     * the HA Companion makes). Classifies failures — see [Refresh]. Blocking HTTP — call it off the main
     * thread (the renderer's JS-bridge thread is fine).
     */
    fun refreshAccessToken(base: String, refreshToken: String, clientId: String = ""): Refresh = try {
        // client_id must match the one the refresh token was issued for. Default = HA origin (the
        // frontend's own client_id); override to reuse a token from another client (e.g. the Companion).
        val cid = clientId.ifBlank { "${base.trimEnd('/')}/" }
        val json = JSONObject(
            post(
                "${base.trimEnd('/')}/auth/token", null,
                "grant_type=refresh_token&refresh_token=${enc(refreshToken)}&client_id=${enc(cid)}", FORM,
            ),
        )
        val access = json.optString("access_token").takeIf { it.isNotBlank() }
        // A 200 without a token is a server oddity, not a revocation — treat as transient.
        if (access == null) {
            Refresh.Transient(
                "token refresh returned no access token",
                HaTransportEvidence(HaTransportFault.PROTOCOL, "MissingAccessToken"),
            )
        } else Refresh.Success(TokenSet(access, json.optLong("expires_in", 1800L)))
    } catch (e: HttpError) {
        Log.i(TAG, "refresh failed: HTTP ${e.code}")
        // Home Assistant uses 400 for invalid refresh requests, including a wrong required client id,
        // and 403 for an inactive user. These unchanged requests cannot recover by retrying; 5xx from a
        // restart or proxy and 404 from a wrong endpoint remain transient/configuration evidence.
        if (e.code in intArrayOf(400, 401, 403)) {
            Refresh.Rejected
        } else {
            Refresh.Transient(
                "token refresh failed: HTTP ${e.code}",
                HaTransportFault.evidenceOfHttpStatus(e.code),
            )
        }
    } catch (e: Exception) {
        Log.i(TAG, "refresh failed: ${e.message}")
        // Classified HERE, where the exception itself is in scope. A caller downstream sees only
        // `detail`, which is the platform's text — the classification cannot be recovered from it
        // without guessing, and guessing from a message is exactly what this avoids.
        Refresh.Transient(e.message, HaTransportFault.evidenceOf(e))
    }

    /** HA frontend login flow with username/password → short-lived access token, or null. */
    private fun login(base: String, user: String, pass: String): String? {
        val cid = "$base/" // client_id = HA's own origin (the frontend does the same), so no extra fetch
        val flow = JSONObject(
            post(
                "$base/auth/login_flow", null,
                JSONObject().put("client_id", cid)
                    .put("handler", JSONArray().put("homeassistant").put(JSONObject.NULL))
                    .put("redirect_uri", cid).toString(),
                JSON,
            ),
        ).getString("flow_id")
        val code = JSONObject(
            post(
                "$base/auth/login_flow/$flow", null,
                JSONObject().put("client_id", cid).put("username", user).put("password", pass).toString(), JSON,
            ),
        ).optString("result")
        if (code.isBlank()) { Log.i(TAG, "login: no code (MFA / invalid creds / not an HA user)"); return null }
        return JSONObject(
            post("$base/auth/token", null, "grant_type=authorization_code&code=${enc(code)}&client_id=${enc(cid)}", FORM),
        ).getString("access_token")
    }

    /**
     * Read `config/entity_registry/list_for_display` over the WebSocket API (non-admin-accessible) and return
     * the device id of the first entity whose entity_id object-part starts with a requested slug. Each entry is
     * compact: `ei` = entity_id, `di` = device id.
     */
    internal fun deviceIdViaWs(
        base: String,
        token: String,
        deviceSlugs: Collection<String>,
        preferIpv4: Boolean,
        ipv4Only: Boolean,
        resolver: ((String) -> List<java.net.InetAddress>)? = null,
    ): String? = runBlocking {
        val wsUrl = base.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://") + "/api/websocket"
        val client = HaWebSocketClients.client(preferIpv4 = preferIpv4, ipv4Only = ipv4Only, resolver = resolver)
        try {
            withTimeoutOrNull(WS_RESOLUTION_DEADLINE_MS) {
                var devId: String? = null
                val session = HaWebSocketClients.open(client, wsUrl, MAX_WS_FRAME_BYTES)
                try {
                    with(session) {
                        (incoming.receive() as? Frame.Text)?.readText() // auth_required
                        send(Frame.Text(JSONObject().put("type", "auth").put("access_token", token).toString()))
                        if ((incoming.receive() as? Frame.Text)?.readText().orEmpty().contains("auth_ok")) {
                            send(Frame.Text("""{"id":1,"type":"config/entity_registry/list_for_display"}"""))
                            // Read frames until the id:1 result (skip any interleaved events/pongs). The whole
                            // connect/auth/query exchange has one deadline, so a silent endpoint cannot strand
                            // the service's resolver thread indefinitely.
                            run frames@{
                                repeat(8) {
                                    val msg = (incoming.receive() as? Frame.Text)?.readText() ?: return@repeat
                                    if (msg.contains("\"id\":1")) { devId = matchDeviceId(msg, deviceSlugs); return@frames }
                                }
                            }
                        }
                    }
                } finally {
                    session.close()
                }
                devId
            }
        } finally {
            client.close()
        }
    }

    private data class DeviceEntityMarker(
        val domain: String,
        val suffix: String,
        /** Marker names which are specific to ha-paneld rather than plausible generic room entities. */
        val signature: Boolean = false,
    )

    private val DEVICE_ENTITY_MARKERS = listOf(
        DeviceEntityMarker("text", "home_dashboard", signature = true),
        DeviceEntityMarker("text", "navigate", signature = true),
        DeviceEntityMarker("button", "reload", signature = true),
        DeviceEntityMarker("light", "screen"),
        DeviceEntityMarker("number", "volume"),
    )

    /**
     * Find the device id using exact entity ids which ha-paneld itself publishes. A broad `<slug>_…`
     * prefix is unsafe because default panel ids are often room names: `sensor.kitchen_temperature`
     * must never make the wall panel link to a temperature-sensor device.
     *
     * HA appends `_2`, `_3`, … when a default entity id collides. Those forms are accepted alongside
     * exact ids, but a device must match at least two known markers including one ha-paneld-specific
     * marker. Multiple qualifying devices fail closed to the HA root page: an unsuffixed id belongs to
     * the collision owner and is not stronger evidence that the owner is this panel.
     * Candidate order remains meaningful: stable panel id first, historical friendly-name fallback next.
     */
    internal fun matchDeviceId(resp: String, deviceSlugs: Collection<String>): String? {
        val result = JSONObject(resp).opt("result")
        val arr: JSONArray = when (result) {
            is JSONArray -> result
            is JSONObject -> result.optJSONArray("entities") ?: return null
            else -> return null
        }
        val entities = buildList {
            for (i in 0 until arr.length()) {
                val e = arr.optJSONObject(i) ?: continue
                val di = e.optString("di").takeIf { it.isNotBlank() } ?: continue
                val entityId = e.optString("ei")
                val domain = entityId.substringBefore('.', "")
                val obj = entityId.substringAfter('.', "")
                if (domain.isNotBlank() && obj.isNotBlank()) add(Triple(domain, obj, di))
            }
        }
        for (slug in deviceSlugs) {
            // device id -> distinct marker indexes. Exact and numeric-collision forms are intentionally
            // equivalent: preferring the exact form would select the entity which forced HA to suffix
            // this panel's new entity id.
            val evidence = linkedMapOf<String, MutableSet<Int>>()
            entities.forEach { (domain, obj, deviceId) ->
                DEVICE_ENTITY_MARKERS.forEachIndexed { markerIndex, marker ->
                    if (domain != marker.domain) return@forEachIndexed
                    val base = "${slug}_${marker.suffix}"
                    val matches = when {
                        obj == base -> true
                        obj.startsWith("${base}_") &&
                            obj.removePrefix("${base}_").toIntOrNull()?.let { it >= 2 } == true -> true
                        else -> false
                    }
                    if (matches) evidence.getOrPut(deviceId) { linkedSetOf() }.add(markerIndex)
                }
            }
            val candidates = evidence.mapNotNull { (deviceId, markers) ->
                val hasSignature = markers.any { DEVICE_ENTITY_MARKERS[it].signature }
                deviceId.takeIf { markers.size >= 2 && hasSignature }
            }
            if (candidates.size > 1) return null
            candidates.singleOrNull()?.let { return it }
        }
        return null
    }

    /** Slugify a device name the way HA derives entity_ids (lowercase, non-alphanumerics → underscore). */
    private fun slug(s: String) = s.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')

    /** HA's canonical base from /api/config (internal_url, else external_url); null if unavailable. */
    private fun internalUrl(base: String, token: String): String? = runCatching {
        val c = JSONObject(get("$base/api/config", token))
        c.optString("internal_url").ifBlank { c.optString("external_url") }.takeIf { it.isNotBlank() }
    }.getOrNull()

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun post(
        url: String,
        token: String?,
        body: String,
        ctype: String,
        maxResponseBytes: Long = MAX_HTTP_RESPONSE_BYTES,
    ) = req(url, "POST", token, body, ctype, maxResponseBytes)
    private fun get(url: String, token: String?) = req(url, "GET", token, null, null)

    /** Non-2xx HTTP response — typed so callers can tell a definitive server refusal from a network fault. */
    class HttpError(val code: Int, method: String) : RuntimeException("$method -> $code")

    private fun req(
        url: String,
        method: String,
        token: String?,
        body: String?,
        ctype: String?,
        maxResponseBytes: Long = MAX_HTTP_RESPONSE_BYTES,
    ): String {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            requestMethod = method; connectTimeout = 5000; readTimeout = 5000
            token?.let { setRequestProperty("Authorization", "Bearer $it") }
            if (body != null) { doOutput = true; ctype?.let { setRequestProperty("Content-Type", it) } }
        }
        try {
            body?.let { c.outputStream.use { os -> os.write(it.toByteArray()) } }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)
                ?.use { readHttpBody(it, maxResponseBytes) }.orEmpty()
            if (code !in 200..299) throw HttpError(code, method)
            return text
        } finally {
            c.disconnect()
        }
    }

    internal fun readHttpBody(input: InputStream, maxBytes: Long = MAX_HTTP_RESPONSE_BYTES): String =
        String(BoundedStreams.readBytes(input, maxBytes), Charsets.UTF_8)
}
