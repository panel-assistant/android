package io.panelassistant.android

import io.panelassistant.android.util.BoundedStreams
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.security.MessageDigest

/** Account identity is configured; a route is only a way to reach that same authenticated instance. */
internal data class HaConnectionRoute(val owner: HaAuthOwner, val url: String, val epoch: Long)

internal data class HaConnectionAdvertisement(val instanceId: String, val userId: String, val urls: List<String>) {
    companion object {
        fun parse(value: JSONObject?): HaConnectionAdvertisement? {
            value ?: return null
            val instance = value.opt("instance_id") as? String ?: return null
            val user = value.opt("user_id") as? String ?: return null
            val urls = value.optJSONArray("urls") ?: return null
            if (!instance.matches(Regex("[0-9a-f]{32}")) || !user.matches(Regex("[0-9a-f]{32}")) ||
                urls.length() > 4) return null
            val normalized = (0 until urls.length()).map { index ->
                HaConnectionRoutes.normalize(urls.opt(index) as? String ?: return null) ?: return null
            }.distinct()
            return HaConnectionAdvertisement(instance, user, normalized)
        }
    }
}

/**
 * Uses the caller's existing IO/retry lane. The public UUID distinguishes an honest reassigned LAN
 * endpoint, not an active attacker. TLS uses normal platform trust and hostname validation.
 */
internal object HaConnectionRoutes {
    const val RECORD_KEY = "device_local_ha_connection_routes"
    const val EPOCH_KEY = "device_local_ha_route_epoch"
    private const val CHECK_INTERVAL_MS = 60_000L
    private const val RETURN_HOLD_MS = 5 * 60_000L
    private val selectionLock = Any()
    private val proofClient = okhttp3.OkHttpClient.Builder()
        .followRedirects(false).followSslRedirects(false)
        .cookieJar(okhttp3.CookieJar.NO_COOKIES)
        .authenticator(okhttp3.Authenticator.NONE).proxyAuthenticator(okhttp3.Authenticator.NONE)
        .connectTimeout(2, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(2, java.util.concurrent.TimeUnit.SECONDS)
        .callTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    private var preferredOwner: HaConnectionRoute? = null
    private var nextCheckMs = 0L
    private var successes = 0
    private var failureOwner: HaAuthOwner? = null
    private val failures = LinkedHashMap<String, Long>()

    internal fun normalize(raw: String): String? = runCatching {
        if (raw.length > 2048 || raw != raw.trim()) return null
        val uri = URI(raw)
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme !in setOf("http", "https") || uri.host.isNullOrBlank() || uri.rawUserInfo != null ||
            uri.rawQuery != null || uri.rawFragment != null || uri.rawPath !in listOf("", "/") ||
            uri.port !in -1..65535 || uri.port == 0) return null
        URI(scheme, null, uri.host.lowercase(), uri.port.takeUnless {
            it == (if (scheme == "https") 443 else 80)
        } ?: -1, null, null, null).toASCIIString()
    }.getOrNull()

    private fun fingerprint(owner: HaAuthOwner): String {
        val value = listOf(owner.url, owner.refreshToken, owner.clientId, owner.staticAccessToken)
            .joinToString("") { "${it.length}:$it" }
        return MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    private fun record(config: Config, owner: HaAuthOwner): JSONObject? = runCatching {
        JSONObject(config.haConnectionRecord()).takeIf {
            it.optString("owner") == fingerprint(owner) &&
                HaConnectionAdvertisement.parse(it.optJSONObject("connection")) != null
        }
    }.getOrNull()

    fun current(config: Config): HaConnectionRoute = config.synchronizedTransaction {
        val owner = config.haAuthSnapshot().stableOwner()
        val record = record(config, owner)
        val advertisement = HaConnectionAdvertisement.parse(record?.optJSONObject("connection"))
        val selected = record?.optString("selected")
        val url = selected?.takeIf { it == owner.url || it in advertisement?.urls.orEmpty() } ?: owner.url
        HaConnectionRoute(owner, url, config.haRouteEpoch())
    }

    fun isCurrent(config: Config, route: HaConnectionRoute): Boolean = current(config) == route

    fun hasLearned(config: Config): Boolean = record(config, config.haAuthSnapshot().stableOwner()) != null

    fun verify(config: Config, route: HaConnectionRoute): Boolean {
        if (!isCurrent(config, route)) return false
        val known = HaConnectionAdvertisement.parse(record(config, route.owner)?.optJSONObject("connection"))
            ?: return true
        return proves(route.url, known.instanceId) && isCurrent(config, route)
    }

    /** Only a successful, still-owned authenticated hello may teach routes. */
    fun learn(config: Config, route: HaConnectionRoute, advertisement: HaConnectionAdvertisement): Boolean =
        config.synchronizedTransaction {
            if (!isCurrent(config, route)) return@synchronizedTransaction false
            val previous = record(config, route.owner)
            val known = HaConnectionAdvertisement.parse(previous?.optJSONObject("connection"))
            if (known != null && (known.instanceId != advertisement.instanceId || known.userId != advertisement.userId)) {
                return@synchronizedTransaction false
            }
            val value = JSONObject().put("owner", fingerprint(route.owner))
                .put("selected", route.url)
                .put("connection", JSONObject().put("instance_id", advertisement.instanceId)
                    .put("user_id", advertisement.userId).put("urls", JSONArray((listOf(route.url).filter { it != route.owner.url } + advertisement.urls).distinct().take(4))))
            // First binding retires any pre-learning renderer whose origin policy still allowed an
            // HTTP-to-HTTPS upgrade. Thereafter only the proved effective origin inherits credentials.
            if (value.toString() == previous?.toString()) true
            else config.commitHaConnectionRecord(route, value.toString(), changedRoute = known == null)
        }

    /** A bound endpoint proves its own instance before either an access or refresh token leaves. */
    fun resolve(config: Config): HaConnectionRoute? {
        val route = current(config)
        val stored = record(config, route.owner) ?: return route
        val known = HaConnectionAdvertisement.parse(stored.optJSONObject("connection")) ?: return null
        val now = System.nanoTime() / 1_000_000L
        val candidates = synchronized(selectionLock) {
            if (failureOwner != route.owner) { failureOwner = route.owner; failures.clear() }
            failures.entries.removeAll { it.value <= now }
            (listOf(route.url, route.owner.url) + known.urls).distinct().filter { it !in failures }
        }
        // Network work never holds either the configuration lock or the route bookkeeping lock.
        for (url in candidates) {
            if (!proves(url, known.instanceId)) continue
            if (!isCurrent(config, route)) return null
            if (url == route.url) return route
            stored.put("selected", url)
            if (!config.commitHaConnectionRecord(route, stored.toString(), changedRoute = true)) return null
            return current(config)
        }
        return null
    }

    /** A proved identity alone does not make an endpoint usable. Actual transport failures try peers. */
    fun failed(config: Config, route: HaConnectionRoute) {
        if (!isCurrent(config, route)) return
        synchronized(selectionLock) {
            if (failureOwner != route.owner) { failureOwner = route.owner; failures.clear() }
            failures[route.url] = System.nanoTime() / 1_000_000L + CHECK_INTERVAL_MS
            while (failures.size > 5) failures.remove(failures.keys.first())
        }
    }

    /** Called at the native owner's existing ping cadence. Three spaced successes follow a quiet hold. */
    fun preferredCandidate(config: Config, nowMs: Long = System.nanoTime() / 1_000_000L): HaConnectionRoute? {
        val route = current(config)
        if (route.url == route.owner.url) return null
        val stored = record(config, route.owner) ?: return null
        val known = HaConnectionAdvertisement.parse(stored.optJSONObject("connection")) ?: return null
        synchronized(selectionLock) {
            if (preferredOwner != route) {
                preferredOwner = route
                successes = 0
                nextCheckMs = nowMs + RETURN_HOLD_MS
            }
            if (nowMs < nextCheckMs) return null
            nextCheckMs = nowMs + CHECK_INTERVAL_MS
        }
        val proved = proves(route.owner.url, known.instanceId)
        return synchronized(selectionLock) {
            if (preferredOwner != route || !isCurrent(config, route)) return@synchronized null
            successes = if (proved) successes + 1 else 0
            if (successes >= 3) route else null
        }
    }

    /** The existing connector must also authenticate a temporary socket before a healthy route is retired. */
    fun preferredResult(config: Config, expected: HaConnectionRoute, authenticated: Boolean,
                        nowMs: Long = System.nanoTime() / 1_000_000L): Boolean = synchronized(selectionLock) {
        successes = 0
        nextCheckMs = nowMs + RETURN_HOLD_MS
        if (!authenticated || !isCurrent(config, expected)) return@synchronized false
        val stored = record(config, expected.owner) ?: return@synchronized false
        stored.put("selected", expected.owner.url)
        config.commitHaConnectionRecord(expected, stored.toString(), changedRoute = true)
    }

    private fun proves(base: String, instance: String): Boolean = runCatching {
        // No cookies, Authorization, supplied UUID, redirects or cached replies. This endpoint returns
        // the server's own UUID; a 401 or another instance is not credential authority.
        val normalized = normalize(base) ?: return false
        val request = okhttp3.Request.Builder().url("$normalized/api/panel_assistant/instance")
            .header("Cache-Control", "no-cache").build()
        proofClient.newCall(request).execute().use { response ->
            response.code == 200 && response.body.byteStream().use {
                JSONObject(String(BoundedStreams.readBytes(it, 4096), Charsets.UTF_8)).opt("instance_id") == instance
            }
        }
    }.getOrDefault(false)
}
