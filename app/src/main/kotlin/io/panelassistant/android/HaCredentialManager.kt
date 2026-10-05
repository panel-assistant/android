package io.panelassistant.android

import io.panelassistant.android.util.HaLink
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * The process's one authority for Home Assistant credentials. The renderer's external-auth replies and
 * every native Home Assistant connection take their access token from [resolve]; none refreshes on its
 * own. [DashboardAuth] decides whether a token must be minted; this object owns what happens when it is.
 *
 * - **One refresh per credential generation.** The generation is [HaAuthOwner]: URL, refresh token,
 *   OAuth client id (and a static token). Concurrent callers needing a new access token join one HTTPS
 *   `/auth/token` request and all receive its outcome. The lock guards registration only, never the
 *   network call.
 * - **One write.** Only the caller that made the request persists the result, and only while the
 *   complete stored generation it started from is still current.
 * - **One refusal.** When Home Assistant terminally refuses the refresh token (revoked, wrong client
 *   id, inactive user), every later caller for that generation gets the same refusal without another
 *   request, so N connections converge on one `reauthorization required` state instead of N retry
 *   loops replaying a refused credential. A new generation (sign-in, changed client id or URL) clears
 *   it, and so does [forgetRefusal], the operator's explicit retry.
 *
 * Nothing here opens, closes or notifies a socket: each connection asks when it opens or reconnects,
 * so a refresh made for one consumer never recycles another's healthy connection. A static long-lived
 * token never reaches the refresh path.
 */
internal object HaCredentialManager {

    /** Bound on a joiner's wait: the request itself is bounded far below this by [HaLink]'s timeouts. */
    private const val JOIN_TIMEOUT_SEC = 30L

    private class Outcome(val refresh: HaLink.Refresh, val committed: Boolean)

    private val lock = Any()
    private val inFlight = HashMap<HaAuthOwner, CompletableFuture<Outcome>>()
    private var refused: HaAuthOwner? = null

    fun resolve(
        config: Config,
        nowSec: Long = System.currentTimeMillis() / 1000,
        force: Boolean = false,
        stillCurrent: () -> Boolean = { true },
    ): DashboardAuth.Result {
        if (!stillCurrent()) return NOT_ATTEMPTED
        val snapshot = config.haAuthSnapshot()
        val owner = snapshot.stableOwner()
        val route = HaConnectionRoutes.resolve(config) ?: return DashboardAuth.Result(
            null, transientDetail = "Home Assistant connection unavailable",
        )
        if (route.owner != owner || !stillCurrent()) return NOT_ATTEMPTED
        if (snapshot.url.isNotBlank() && snapshot.refreshToken.isNotBlank() && isRefused(owner)) {
            return DashboardAuth.Result(null, rejected = true)
        }
        var committed = true
        val result = DashboardAuth.resolve(
            snapshot.url, snapshot.accessToken, snapshot.refreshToken, snapshot.tokenExpiry, nowSec, force,
        ) { _, _ ->
            refresh(config, snapshot, route, nowSec).also { committed = it.committed }.refresh
        }
        // A caller whose own authority lapsed, or whose credential generation was replaced while the
        // request was in flight, is told nothing was judged; the old generation's answer is not its.
        if (!stillCurrent() || !HaConnectionRoutes.isCurrent(config, route)) return NOT_ATTEMPTED
        if (!committed) return NOT_ATTEMPTED
        return result.copy(route = route)
    }

    /** The operator asked to try again: let the next caller put the refused credential to Home Assistant. */
    fun forgetRefusal() {
        synchronized(lock) { refused = null }
    }

    private fun isRefused(owner: HaAuthOwner): Boolean = synchronized(lock) { refused == owner }

    private fun refresh(config: Config, snapshot: HaAuthSnapshot, route: HaConnectionRoute, nowSec: Long): Outcome {
        val owner = snapshot.stableOwner()
        val future = CompletableFuture<Outcome>()
        val running = synchronized(lock) {
            if (refused == owner) return Outcome(HaLink.Refresh.Rejected, committed = true)
            inFlight.putIfAbsent(owner, future)
        }
        // Joined outside the lock: the request's owner needs it to finish.
        if (running != null) return join(running)
        var outcome = Outcome(HaLink.Refresh.Transient("Home Assistant token refresh failed"), committed = false)
        try {
            val fresh = HaLink.refreshAccessToken(route.url, snapshot.refreshToken, snapshot.clientId.ifBlank { "${snapshot.url.trimEnd('/')}/" })
            if (!HaConnectionRoutes.isCurrent(config, route)) return Outcome(fresh, committed = false)
            outcome = when (fresh) {
                is HaLink.Refresh.Success -> Outcome(
                    fresh,
                    config.setHaRefreshedTokenIfOwned(
                        snapshot,
                        fresh.tokens.accessToken,
                        nowSec + fresh.tokens.expiresInSec,
                    ),
                )
                HaLink.Refresh.Rejected -> {
                    synchronized(lock) { refused = owner }
                    Outcome(fresh, committed = true)
                }
                is HaLink.Refresh.Transient -> {
                    HaConnectionRoutes.failed(config, route)
                    Outcome(fresh, committed = true)
                }
            }
        } finally {
            synchronized(lock) { inFlight.remove(owner, future) }
            future.complete(outcome)
        }
        return outcome
    }

    private fun join(future: CompletableFuture<Outcome>): Outcome = try {
        future.get(JOIN_TIMEOUT_SEC, TimeUnit.SECONDS)
    } catch (_: TimeoutException) {
        Outcome(HaLink.Refresh.Transient("Home Assistant token refresh timed out"), committed = false)
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        Outcome(HaLink.Refresh.Transient("Home Assistant token refresh was interrupted"), committed = false)
    }

    private val NOT_ATTEMPTED = DashboardAuth.Result(null, notAttempted = true)
}
