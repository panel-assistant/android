package io.panelassistant.android.http

/**
 * Cross-origin (CSRF) guard for the unauthenticated `:8888` control surface.
 *
 * The surface is LAN-source-gated but has no token (the turnkey-UX / LAN-trust model — see
 * `docs/architecture/security.md`). The realistic residual threat is *browser-mediated*: a person on
 * the LAN visits a malicious web page, whose JavaScript silently POSTs to the panel's IP to drive a
 * state-changing endpoint — e.g. `POST /config` to repoint MQTT (entity takeover), `/action` to
 * reboot, or `/play` to inject audio. The cross-origin *read* is already blocked by the browser's
 * same-origin policy (no permissive CORS headers are sent), but the *side effect* of a CSRF write
 * still lands.
 *
 * So we refuse a **state-changing** request whose `Origin` (or, as a fallback, `Referer`) is present
 * and does **not** match the request's own `Host`:
 *  - The panel's own web UI issues same-origin `fetch`es (`Origin` == this panel) → allowed.
 *  - Non-browser API clients (curl, HA `rest_command`, monitors) send no `Origin`/`Referer` → allowed;
 *    that's the LAN API contract, and browsers always attach `Origin` on cross-origin writes.
 *  - A malicious cross-origin page carries its own `Origin` → refused.
 *
 * Scope note: [allowed] stops classic CSRF (a page POSTing to the panel's address) but not active
 * DNS-rebinding (which makes the request genuinely same-origin); [hostAllowed] covers rebinding via a
 * `Host` allowlist. Ordinary GETs aren't origin-guarded (idempotent + cross-origin reads are CORS-blocked),
 * but active GETs that start privileged capture, subprocesses, sampling, or network refreshes additionally
 * use [activeReadAllowed]. The higher-assurance path for an untrusted network remains HA-token auth
 * (security decision 3), deferred under the LAN-trust model.
 */
object OriginGuard {
    private val MUTATING = setOf("POST", "PUT", "PATCH", "DELETE")

    fun isStateChanging(method: String): Boolean = method.uppercase() in MUTATING

    /**
     * True = allow the request. [origin] / [referer] / [host] are the raw header values (any may be
     * null). A state-changing request is refused only when a browser origin is present and its
     * `host[:port]` differs from [host].
     */
    fun allowed(method: String, origin: String?, referer: String?, host: String?): Boolean {
        if (!isStateChanging(method)) return true
        val src = origin?.takeIf { it.isNotBlank() } ?: referer?.takeIf { it.isNotBlank() } ?: return true
        val srcAuthority = authorityOf(src) ?: return false // present-but-unparseable origin → refuse
        val hostAuthority = host?.trim()?.ifEmpty { null } ?: return false // mutation with no Host → refuse
        return srcAuthority.equals(hostAuthority, ignoreCase = true)
    }

    /**
     * Admission for GET routes whose response generation itself has a material side effect or cost.
     * Browser Fetch Metadata fails closed for cross-site/same-site embedding, while same-origin UI
     * requests and header-less LAN automation remain compatible. If a browser supplies Origin or
     * Referer, it must name this request's Host.
     */
    fun activeReadAllowed(
        origin: String?,
        referer: String?,
        host: String?,
        fetchSite: String?,
        accept: String?,
        userAgent: String? = null,
        allowLegacyNavigation: Boolean = false,
    ): Boolean {
        when (fetchSite?.trim()?.lowercase()) {
            "cross-site", "same-site" -> return false
            "same-origin", "none", null -> Unit
            else -> return false
        }
        val src = origin?.takeIf { it.isNotBlank() } ?: referer?.takeIf { it.isNotBlank() }
        if (src == null) {
            // Header-less automation, and any browser that did supply Fetch Metadata, are settled above.
            if (fetchSite != null || !looksLikeBrowser(userAgent)) return true
            // What is left is a browser that sent no Origin, no Referer and no Fetch Metadata at all —
            // Safari before 16.4, and anything older or privacy-configured. Refusing the lot made these
            // routes simply unopenable there, which is how this was found: typing the snapshot URL
            // returned "cross-origin active read refused" on a request that was not cross-origin.
            //
            // The one honest signal such a browser still gives is what it asked for. A top-level
            // navigation requests a document; an <img>, <script> or fetch subresource never does. So a
            // navigation-shaped Accept is admitted and everything else stays refused, which keeps the
            // case this guard exists for — an opaque cross-origin subresource starting camera work —
            // closed on exactly the browsers that cannot prove themselves any other way.
            return allowLegacyNavigation && navigationShaped(accept)
        }
        val srcAuthority = authorityOf(src) ?: return false
        val hostAuthority = host?.trim()?.ifEmpty { null } ?: return false
        return srcAuthority.equals(hostAuthority, ignoreCase = true)
    }

    /**
     * Whether the request asked for a document, i.e. whether it is a top-level navigation rather than a
     * subresource load. Deliberately strict: the header must actually name `text/html`, so the bare
     * wildcard an image or fetch load sends does not qualify.
     *
     * **Residual, stated rather than hidden:** on a browser with no Fetch Metadata this also admits a
     * cross-origin *navigation* and an `<iframe>`, both of which send a document Accept. Neither leaks a
     * frame — the response carries `X-Frame-Options: DENY` and `frame-ancestors 'none'`, and the bytes
     * are unreadable cross-origin — but an iframe on a hostile page could still make the camera open
     * once, with the indicator lighting as it must. That is a smaller and more visible harm than the
     * silent unopenability it replaces, and it disappears entirely on any browser that sends Fetch
     * Metadata, which is every current one.
     */
    private fun navigationShaped(accept: String?): Boolean = accept
        ?.split(',')
        ?.any { range ->
            val parts = range.split(';').map(String::trim)
            if (!parts.firstOrNull().orEmpty().equals("text/html", ignoreCase = true)) return@any false
            val quality = parts.drop(1).firstOrNull {
                it.substringBefore('=').trim().equals("q", ignoreCase = true)
            } ?: return@any true
            quality.substringAfter('=', "").trim().toDoubleOrNull()?.let { it > 0.0 && it <= 1.0 } == true
        } == true

    private fun looksLikeBrowser(userAgent: String?): Boolean {
        val ua = userAgent?.lowercase().orEmpty()
        return ua.contains("mozilla/") || ua.contains("chrome/") || ua.contains("chromium/") ||
            ua.contains("safari/") || ua.contains("firefox/")
    }

    /** Extract `host[:port]` from an Origin (`scheme://host[:port]`) or a Referer (full URL). */
    private fun authorityOf(url: String): String? {
        val afterScheme = url.substringAfter("://", "").ifEmpty { return null }
        return afterScheme.substringBefore('/').substringBefore('?').ifEmpty { null }
    }

    /**
     * Anti-DNS-rebinding guard: the `Origin` check above is defeated by active DNS-rebinding (the
     * attacker's page rebinds its own name to the panel's IP, so the request becomes genuinely
     * same-origin). The defence is to pin the `Host` header to values that can't be rebound onto the
     * panel: **IP literals** (v4/v6 — reached directly, no DNS name to rebind), `localhost`, and
     * `*.local` (mDNS, LAN-only resolution), plus any operator-configured [allowedNames]. Any other
     * DNS hostname in `Host` is refused. Applies to every method (rebinding also enables reads —
     * `GET /config/export`, `/screenshot`). A missing `Host` is not a rebinding vector, so it passes.
     *
     * [allowedNames] must be lowercased by the caller ([io.panelassistant.android.Config.httpAllowedHosts]).
     */
    fun hostAllowed(host: String?, allowedNames: Set<String>): Boolean {
        val raw = host?.trim()?.ifEmpty { null } ?: return true // no Host header — not a rebinding vector
        val name = hostnameOf(raw).lowercase()
        if (name.isEmpty()) return true
        return isIpLiteral(name) || name == "localhost" || name.endsWith(".local") || name in allowedNames
    }

    /** The hostname part of a `Host` header — strips the port and IPv6 brackets. */
    private fun hostnameOf(host: String): String {
        if (host.startsWith("[")) { // [ipv6]:port
            val end = host.indexOf(']')
            return if (end > 1) host.substring(1, end) else host.removePrefix("[")
        }
        // 2+ colons ⇒ a bare (unbracketed) IPv6 literal, which has no port suffix; keep it whole.
        return if (host.count { it == ':' } >= 2) host else host.substringBefore(':')
    }

    /** True if [hostname] is an IPv4 dotted-quad or an IPv6 literal (both are unrebindable). */
    private fun isIpLiteral(hostname: String): Boolean {
        if (hostname.contains(':')) return true // IPv6
        val octets = hostname.split('.')
        return octets.size == 4 && octets.all { o -> o.toIntOrNull()?.let { it in 0..255 } == true }
    }
}
