package io.github.maxlyth.hapaneld

import io.github.maxlyth.hapaneld.util.HaLink
import io.github.maxlyth.hapaneld.util.HaTransportEvidence

/**
 * Decides which access token the built-in renderer hands the HA frontend, and when to refresh — the
 * panel-side of the external-auth contract, matching how the HA Companion's `ensureValidSession` works.
 *
 * Two models, chosen by whether a refresh token is present:
 *  - **static** (no refresh token): [access] is a long-lived token; hand it back with a long advertised
 *    life. Simplest; the token is a standing credential on the panel.
 *  - **refresh** (refresh token present): [access] is a short-lived token. Reuse it while it has
 *    comfortable life left, otherwise mint a new one from the refresh token — so no 10-year token lives
 *    on the panel and the credential is revocable by removing the refresh token in HA.
 *
 * [resolve] is pure and fully unit-testable: it takes the current clock and a `refresher` and returns
 * the session to reply with plus an optional (access, expiry) pair to persist. Reading [Config],
 * persisting and the one shared refresh request belong to [HaCredentialManager], which every consumer
 * goes through.
 */
internal object DashboardAuth {

    /** The reply material for one external-auth handshake. */
    data class Session(val accessToken: String, val expiresInSec: Long)

    /** Outcome of [resolve]: a session to reply with (or null = fail closed), when a refresh happened
     *  the (access, epoch-expiry) to persist so the next handshake reuses it, and [rejected] when the
     *  server terminally refused the unchanged refresh request. Home Assistant can reject either the
     *  token or its required OAuth client id; a transient refresh failure never sets [rejected] — it
     *  fails closed with [transientDetail] naming the transport fault, so a caller can distinguish
     *  "could not reach the server to mint a token" from "the server refused" or "never signed in".
     *  [transientEvidence] is that same fault classified from the exception type — the only form a
     *  pasteable diagnostic may carry, because [transientDetail] is raw platform text that can embed
     *  the configured host or address. */
    data class Result(
        val session: Session?,
        val persist: Pair<String, Long>? = null,
        val rejected: Boolean = false,
        val transientDetail: String? = null,
        val transientEvidence: HaTransportEvidence = HaTransportEvidence.NONE,
        /** Set when no authentication verdict may be reported: the panel is not configured yet, the
         *  request lost ownership, or a refreshed credential could not be committed. Without this
         *  distinction, a cold-boot race or abandoned refresh is indistinguishable from a server
         *  refusal, and the panel parks on a credential screen for a credential HA never rejected. */
        val notAttempted: Boolean = false,
        val route: HaConnectionRoute? = null,
    )

    /** Comfortable life a cached access token must have left to be reused rather than refreshed. */
    const val REFRESH_SKEW_SEC = 60L

    /** Life advertised for a static long-lived token (~10 years) — the frontend only uses it to know
     *  when to ask again, so a large value means it never re-asks within a session. */
    const val STATIC_TTL_SEC = 315_360_000L

    /**
     * @param url          HA base URL (blank => no renderer configured => null session).
     * @param access       current access token (static LLAT, or the last short-lived token).
     * @param refreshToken OAuth refresh token, or blank for the static model.
     * @param expiryEpochSec epoch-seconds expiry of [access] (refresh model); 0 => unknown.
     * @param nowSec       current epoch seconds.
     * @param force        the frontend's force flag: bypass the cached token (it was just rejected) and
     *                     refresh; on a refresh failure, fail closed rather than re-hand the dead token.
     * @param refresher    (url, refreshToken) -> classified refresh outcome (see [HaLink.Refresh]).
     */
    fun resolve(
        url: String,
        access: String,
        refreshToken: String,
        expiryEpochSec: Long,
        nowSec: Long,
        force: Boolean,
        refresher: (String, String) -> HaLink.Refresh,
    ): Result {
        // No URL means the renderer is not configured — or, on a cold boot, that the configuration
        // has not loaded yet. Either way no credential was judged, so this is not an auth verdict.
        if (url.isBlank()) return Result(null, notAttempted = true)
        // Static model: no refresh token → the access token is long-lived, hand it back as-is. (Nothing
        // to refresh, so `force` can't produce a different token — the LLAT is all there is.)
        if (refreshToken.isBlank()) {
            return if (access.isBlank()) Result(null)
            else Result(Session(access, STATIC_TTL_SEC))
        }
        // Refresh model: reuse the cached access token while it has comfortable life left — unless the
        // frontend forced a refresh (the cached token was just rejected server-side).
        val ttl = expiryEpochSec - nowSec
        if (!force && access.isNotBlank() && ttl > REFRESH_SKEW_SEC) return Result(Session(access, ttl))
        // Expired / near-expiry / unknown / forced → mint a new one.
        return when (val fresh = refresher(url, refreshToken)) {
            is HaLink.Refresh.Success ->
                Result(
                    Session(fresh.tokens.accessToken, fresh.tokens.expiresInSec),
                    fresh.tokens.accessToken to (nowSec + fresh.tokens.expiresInSec),
                )
            // Terminal server refusal: the refresh token or its OAuth client identity is invalid. Fail
            // closed and surface setup repair instead of retrying the unchanged request forever.
            is HaLink.Refresh.Rejected -> Result(null, rejected = true)
            // Transient (HA down / network blip): says nothing about the token. On a forced refresh the
            // cached token was rejected — re-handing it just loops, so fail closed (but NOT terminally rejected — the
            // frontend will re-ask and a recovered network can still succeed). On a non-forced
            // near-expiry refresh, reuse the cached token while it has any life left.
            is HaLink.Refresh.Transient ->
                if (!force && access.isNotBlank() && ttl > 0) Result(Session(access, ttl))
                else Result(
                    null,
                    transientDetail = fresh.detail ?: "Home Assistant token refresh failed",
                    transientEvidence = fresh.evidence.orUnclassified(),
                )
        }
    }
}
