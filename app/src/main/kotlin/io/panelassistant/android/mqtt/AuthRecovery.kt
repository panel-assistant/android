package io.panelassistant.android.mqtt

/** True when auth recovery exclusively owns reconnect scheduling for the bridge. */
fun isAuthRecoveryState(state: String): Boolean = state == "auth-retrying" || state == "auth-failed"

/** Pure policy for MQTT credential rejection. Time is monotonic; the caller owns scheduling/client I/O. */
class AuthRecovery(
    private val persistentAfterMs: Long = 5 * 60_000L,
    private val jitter: (baseMs: Long, attempt: Int) -> Long = { base, _ -> base },
) {
    data class Snapshot(
        val state: String,
        val lastSuccessMs: Long?,
        val consecutiveRejects: Int,
        val retryAttempt: Int,
        val nextRetryMs: Long?,
    )

    private var credentials = ""
    private var succeededForCredentials = false
    private var lastSuccessMs: Long? = null
    private var firstRejectMs: Long? = null
    private var rejects = 0
    private var attempt = 0
    private var nextRetryMs: Long? = null
    private var retryGeneration = 0L

    /**
     * Advances to and returns a fresh retry-currency token, superseding any prior scheduled attempt.
     * The caller schedules its retry against the returned token and checks [isCurrentRetry] before acting.
     */
    @Synchronized fun beginRetry(): Long = ++retryGeneration

    /** True only for the token of the latest [beginRetry]; a superseded (stale) scheduled attempt reads false. */
    @Synchronized fun isCurrentRetry(token: Long): Boolean = token == retryGeneration

    /** Supersedes any pending scheduled retry without starting a new one (e.g. on retirement). */
    @Synchronized fun supersedeRetry() {
        retryGeneration++
    }

    @Synchronized fun configure(identity: String) {
        if (identity == credentials) return
        credentials = identity
        succeededForCredentials = false
        lastSuccessMs = null
        firstRejectMs = null
        rejects = 0
        attempt = 0
        nextRetryMs = null
    }

    @Synchronized fun authenticated(nowMs: Long) {
        retryGeneration++
        succeededForCredentials = true
        lastSuccessMs = nowMs
        firstRejectMs = null
        rejects = 0
        attempt = 0
        nextRetryMs = null
    }

    /** Records a rejection and returns the absolute monotonic time for a fresh-client retry. */
    @Synchronized fun rejected(nowMs: Long): Long {
        if (firstRejectMs == null) firstRejectMs = nowMs
        rejects++
        attempt++
        val base = BACKOFF_MS[(attempt - 1).coerceAtMost(BACKOFF_MS.lastIndex)]
        return (nowMs + jitter(base, attempt).coerceIn(base * 3 / 4, base * 5 / 4)).also {
            nextRetryMs = it
        }
    }

    @Synchronized fun snapshot(nowMs: Long): Snapshot {
        val transient = succeededForCredentials && firstRejectMs?.let { nowMs - it < persistentAfterMs } == true
        return Snapshot(
            state = if (rejects == 0) "connected" else if (transient) "auth-retrying" else "auth-failed",
            lastSuccessMs = lastSuccessMs,
            consecutiveRejects = rejects,
            retryAttempt = attempt,
            nextRetryMs = nextRetryMs,
        )
    }

    companion object {
        val BACKOFF_MS = longArrayOf(2_000L, 5_000L, 15_000L, 30_000L, 60_000L)
    }
}
