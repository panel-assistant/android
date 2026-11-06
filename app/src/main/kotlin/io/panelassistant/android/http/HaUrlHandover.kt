package io.panelassistant.android.http

/**
 * Deciding whether a Home Assistant address handed to this panel actually answers from the panel's
 * own network.
 *
 * The Panel Assistant integration knows an address for Home Assistant, but it knows it from Home
 * Assistant's side of the network. An internal URL, a reverse proxy, a remote-access URL or — most
 * often — the bridge address of the container Home Assistant runs in can all be perfectly correct
 * there and unreachable here. The panel is the only party that can settle that, so it asks before it
 * accepts, and the wizard shows a correction rather than a blank question when the answer is no.
 *
 * **Why the probe is `GET {base}/auth/providers` and never `/api/`.** Home Assistant answers an
 * unauthenticated `/api/` with 401, and its HTTP ban middleware turns every 401 into a "Login attempt
 * failed" notification naming the sender and a strike towards an IP ban. A check that relied on that
 * 401 raised a false security alarm on every install. `/auth/providers` is the unauthenticated endpoint
 * Home Assistant's own login page reads first: measured against a live instance, it answers 200
 * `application/json` with an object holding a `providers` array, and nothing on that path reports a
 * failed login.
 *
 * **Why a 200 alone is still a failure.** A captive portal, a permissive reverse proxy or an
 * unrelated server on a reused address answers 200 to any path. So the answer must be 200, JSON, and
 * shaped like Home Assistant's provider list; anything else means something that is *not* Home
 * Assistant holds the address. Accepting a bare 200 would rebuild exactly the false confidence that
 * [PaneldServer.probeLogSinkJson] was changed to remove, where a bare TCP connect succeeded against
 * any listening socket and "Connected" asserted a working endpoint on evidence that could not
 * distinguish one.
 *
 * Pure and Android-free so it runs on the plain JVM unit-test classpath; the socket work itself lives
 * at the Android edge and hands its status or its exception back here to be classified.
 */
object HaUrlHandover {

    /**
     * What the probe decided. [reason] is the stable wire code stored in `ha_url_handover_reason` and
     * shown to the operator through an i18n key of the same name; it is blank for [VERIFIED] because
     * nothing failed.
     */
    enum class Outcome(val reason: String) {
        /** Home Assistant answered from the panel's network. */
        VERIFIED(""),

        /** Not a usable http/https origin. */
        INVALID("invalid"),

        /** The host name does not resolve from this panel. */
        UNRESOLVABLE("unresolvable"),

        /** Resolved, but nothing accepted a connection. */
        UNREACHABLE("unreachable"),

        /** Nothing completed in time. Covers a connect that never lands as well as a reply that never
         *  arrives, because the transport reports both the same way — so the operator-facing text says
         *  only that the address did not answer, never that anything accepted a connection. Observed
         *  against a null-routed address, where nothing accepted anything. */
        TIMEOUT("timeout"),

        /** HTTPS, but the certificate could not be validated from this panel. */
        TLS("tls"),

        /** Something answered, but not the way Home Assistant does. */
        NOT_HOME_ASSISTANT("not_home_assistant"),
        ;

        val verified: Boolean get() = this == VERIFIED
    }

    /** Path probed on the handed-over origin: unauthenticated, and never answered 401 by Home Assistant. */
    const val PROBE_PATH: String = "auth/providers"

    /** Home Assistant's provider list is about a hundred bytes; anything far larger is not it. */
    const val MAX_BODY_BYTES: Int = 16 * 1024

    /** Bounded like every other panel-side probe: this runs inside a config POST, not in the background. */
    const val CONNECT_TIMEOUT_MS: Int = 4_000
    const val READ_TIMEOUT_MS: Int = 4_000

    /**
     * The exact URL to probe for a handed-over origin, or null when it is not a usable origin.
     *
     * Takes the already-normalized origin and appends [PROBE_PATH]; the trailing-slash handling is
     * explicit because `{base}api/` and `{base}//api/` are both wrong against Home Assistant.
     */
    fun probeUrl(origin: String): String? {
        val trimmed = origin.trim()
        if (trimmed.isBlank()) return null
        val lower = trimmed.lowercase()
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return null
        return trimmed.trimEnd('/') + "/" + PROBE_PATH
    }

    /** Whether a response is worth reading: only a 200 JSON reply can be Home Assistant's. */
    fun mayBeHomeAssistant(status: Int, contentType: String?): Boolean =
        status == 200 && contentType.orEmpty().trim().lowercase().startsWith("application/json")

    /**
     * Classify what the handed-over address answered. [body] is read only when [mayBeHomeAssistant]
     * held, and is null otherwise or when it could not be read within [MAX_BODY_BYTES].
     */
    fun classifyResponse(status: Int, contentType: String?, body: String?): Outcome {
        if (!mayBeHomeAssistant(status, contentType) || body == null) return Outcome.NOT_HOME_ASSISTANT
        val providers = runCatching { org.json.JSONObject(body).optJSONArray("providers") }.getOrNull()
        return if (providers != null) Outcome.VERIFIED else Outcome.NOT_HOME_ASSISTANT
    }

    /**
     * Classify a failure to get any status at all.
     *
     * Matched on exception type rather than message so it stays stable across JVM and Android, and
     * ordered most specific first: a [javax.net.ssl.SSLException] is also an IOException, and a
     * [java.net.SocketTimeoutException] is also a ConnectException on some stacks.
     */
    fun classifyFailure(error: Throwable): Outcome = when (error) {
        is java.net.UnknownHostException -> Outcome.UNRESOLVABLE
        is javax.net.ssl.SSLException -> Outcome.TLS
        is java.net.SocketTimeoutException -> Outcome.TIMEOUT
        is java.net.ConnectException, is java.net.NoRouteToHostException -> Outcome.UNREACHABLE
        is java.net.MalformedURLException, is java.net.URISyntaxException -> Outcome.INVALID
        else -> Outcome.UNREACHABLE
    }
}
