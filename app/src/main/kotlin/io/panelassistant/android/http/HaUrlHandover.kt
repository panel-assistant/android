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
 * **Why the expected status is 401 and a 200 is a failure.** The probe is an unauthenticated
 * `GET {base}/api/`. Home Assistant answers that with `401: Unauthorized`, measured against a live
 * instance rather than assumed. A 200 from that path without a token is therefore not a weaker
 * success: it means something that is *not* Home Assistant is answering — a captive portal, a
 * permissive reverse proxy, or an unrelated server on a reused address. Accepting it would rebuild
 * exactly the false confidence that [PaneldServer.probeLogSinkJson] was changed to remove, where a
 * bare TCP connect succeeded against any listening socket and "Connected" asserted a working
 * endpoint on evidence that could not distinguish one.
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

    /** Path probed on the handed-over origin. */
    const val PROBE_PATH: String = "api/"

    /** The status an unauthenticated Home Assistant returns for [PROBE_PATH]. */
    const val HOME_ASSISTANT_STATUS: Int = 401

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

    /** Classify a status the handed-over address actually returned. */
    fun classifyStatus(status: Int): Outcome =
        if (status == HOME_ASSISTANT_STATUS) Outcome.VERIFIED else Outcome.NOT_HOME_ASSISTANT

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
