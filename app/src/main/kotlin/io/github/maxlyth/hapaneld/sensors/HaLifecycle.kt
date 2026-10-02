package io.github.maxlyth.hapaneld.sensors

/** Panel Assistant is the only reporter of Home Assistant lifecycle intent. */
internal enum class HaLifecycleSource { NATIVE }

internal enum class HaLifecyclePhase { SHUTTING_DOWN, STARTING, READY }
internal enum class HaLifecycleReason(val wireValue: String) {
    RESTART("restart"), HOST_REBOOT("host_reboot"), CORE_UPDATE("core_update"), UNKNOWN("unknown")
}
internal data class HaLifecycleNotice(
    val phase: HaLifecyclePhase,
    val reason: HaLifecycleReason,
    val elapsedMs: Long?,
    val expectedMs: Long?,
)

/** What the panel is currently entitled to claim about its Home Assistant server. */
internal enum class HaLifecycleState(val wireValue: String) {
    /** Nothing to report. */
    NORMAL("normal"),

    /** Home Assistant told us it is going away. Deliberate, so say so. */
    SHUTTING_DOWN("shutting_down"),

    /** Home Assistant is coming up but is NOT usable yet. `homeassistant_start` means this, not "back". */
    STARTING("starting"),

    /** Recovery proven; announce briefly, then decay to [NORMAL]. */
    BACK_ONLINE("back_online"),

    /** The socket dropped with no lifecycle event. Cause unknown — never call this a shutdown. */
    CONNECTION_LOST("connection_lost"),
}

/**
 * Bounded, deduplicating, process-local tracker for Home Assistant's lifecycle.
 *
 * Pure and time-injected — every observation carries its own `now`, so this owns no timer, thread or
 * connection and is unit-tested without Android in `HaLifecycleTest`. It holds a handful of scalar
 * fields and no collection: there is no history to grow and no cursor to persist, so it re-derives
 * from live state exactly as `SetupJourney` does.
 *
 * The distinction it exists to protect: a deliberate Home Assistant shutdown and an ordinary LAN drop
 * look identical at the socket, and calling the second one a shutdown would be a lie the user acts on.
 */
internal class HaLifecycle(
    private val backOnlineWindowMs: Long = DEFAULT_BACK_ONLINE_WINDOW_MS,
) {
    init {
        require(backOnlineWindowMs > 0L) { "back-online window must be positive" }
    }

    private val lock = Any()
    private var current = HaLifecycleState.NORMAL
    private var backOnlineSinceMs = 0L

    /**
     * Which source OBSERVED the current state, or null when no source did: the initial NORMAL, a
     * connection loss noticed locally, and a decayed recovery notice are the panel's own inferences,
     * and labelling them with a source that never reported them would be a small lie the diagnostics
     * repeat. Null is rendered as "no source", not defaulted to one.
     */
    private var source: HaLifecycleSource? = null

    /**
     * Which OUTAGE this is, incremented when one opens. The machine's only other memory of "which
     * outage am I in" is the live state, and that is erased the moment a recovery notice decays to
     * NORMAL — which is what let a second, independent clearer announce the same recovery again.
     * An episode number outlives the state, so a recovery can be announced once per outage.
     */
    private var episode = 0L

    /** The episode whose recovery has already been announced, so a later clearer cannot repeat it. */
    private var recoveryAnnouncedEpisode = -1L

    /** Bumped under [lock] on visible changes so a publisher cannot go backwards. */
    private var revision = 0L
    private var lostSinceMs = 0L
    private var notice: HaLifecycleNotice? = null
    private var noticeReceivedMs = 0L

    /**
     * Everything a consumer's rendering can depend on, captured under ONE lock acquisition, with the
     * revision that produced it. Reading the parts separately allows a torn tuple — a state from one
     * moment paired with a source from another — so this is the ONLY read the machine's owners expose;
     * the revision additionally lets a publisher reject an older snapshot instead of letting it
     * overwrite a newer one.
     *
     * [backOnlineRemainingMs] rides along because a view timing out a recovery notice otherwise pairs
     * a state from one read with a lifetime from another. It is NOT part of the publish-dedup identity:
     * it varies within one [HaLifecycleState.BACK_ONLINE] episode without being a new fact.
     */
    data class Snapshot(
        val state: HaLifecycleState,
        val source: HaLifecycleSource?,
        /** Legacy diagnostic field; direct Core subscriptions are no longer requested. */
        val refused: Boolean,
        val revision: Long,
        val backOnlineRemainingMs: Long,
        val offlineGraceRemainingMs: Long = 0L,
        val reason: HaLifecycleReason = HaLifecycleReason.UNKNOWN,
        val elapsedMs: Long? = null,
        val expectedMs: Long? = null,
    )

    fun snapshot(nowMs: Long): Snapshot = synchronized(lock) {
        Snapshot(stateLocked(nowMs), source, false, revision, remainingLocked(nowMs),
            if (current == HaLifecycleState.CONNECTION_LOST) (10_000L - (nowMs - lostSinceMs).coerceAtLeast(0L)).coerceAtLeast(0L) else 0L,
            notice?.reason ?: HaLifecycleReason.UNKNOWN,
            notice?.elapsedMs?.let { elapsed ->
                val advance = (nowMs - noticeReceivedMs).coerceAtLeast(0L)
                if (advance > Long.MAX_VALUE - elapsed) Long.MAX_VALUE else elapsed + advance
            },
            notice?.expectedMs)
    }

    private fun remainingLocked(nowMs: Long): Long {
        // No clamp: reaching the arithmetic means the state IS BACK_ONLINE, which stateLocked only
        // reports while elapsed is inside the window — so the result is already within (0, window].
        if (stateLocked(nowMs) != HaLifecycleState.BACK_ONLINE) return 0L
        return backOnlineSinceMs + backOnlineWindowMs - nowMs
    }

    /**
     * The state to render now. Reading is what retires [HaLifecycleState.BACK_ONLINE] — a decay
     * checked on read rather than fired by a timer, matching `ProximityReportGate`'s no-timer idiom.
     */
    fun state(nowMs: Long): HaLifecycleState = synchronized(lock) { stateLocked(nowMs) }

    private fun stateLocked(nowMs: Long): HaLifecycleState {
        if (current != HaLifecycleState.BACK_ONLINE) return current
        // An injected or monotonic clock can move backwards; expire rather than extend the window.
        val elapsed = nowMs - backOnlineSinceMs
        if (elapsed >= backOnlineWindowMs || elapsed < 0L) {
            current = HaLifecycleState.NORMAL
            // The notice has lapsed; nothing is being reported, so no source is reporting it.
            source = null
            revision++
        }
        return current
    }

    /**
     * The socket dropped. Only meaningful when we have no lifecycle explanation: an outage we already
     * attribute to Home Assistant survives the disconnect that follows it, which is why the PA notice
     * survives the socket that carried it.
     */
    fun onDisconnected(nowMs: Long) {
        synchronized(lock) {
            when (stateLocked(nowMs)) {
                HaLifecycleState.SHUTTING_DOWN, HaLifecycleState.STARTING, HaLifecycleState.CONNECTION_LOST -> Unit
                else -> {
                    bumpEpisodeLocked()
                    current = HaLifecycleState.CONNECTION_LOST
                    lostSinceMs = nowMs
                    notice = null
                    // The connection ended without a lifecycle explanation.
                    source = null
                    revision++
                }
            }
        }
    }

    /** Panel Assistant reports Core readiness independently of socket authentication. */
    fun onNativeNotice(next: HaLifecycleNotice, nowMs: Long) = synchronized(lock) {
        val observed = stateLocked(nowMs)
        if (next.phase == HaLifecyclePhase.READY) {
            if (observed == HaLifecycleState.SHUTTING_DOWN || observed == HaLifecycleState.STARTING ||
                (observed == HaLifecycleState.CONNECTION_LOST && nowMs - lostSinceMs >= 10_000L)) {
                source = HaLifecycleSource.NATIVE
                enterBackOnlineLocked(nowMs)
            } else if (observed == HaLifecycleState.CONNECTION_LOST) {
                current = HaLifecycleState.NORMAL
                source = null
                recoveryAnnouncedEpisode = episode
                revision++
            }
            return@synchronized
        }
        notice = next
        noticeReceivedMs = nowMs
        source = HaLifecycleSource.NATIVE
        if (observed == HaLifecycleState.NORMAL || observed == HaLifecycleState.BACK_ONLINE) bumpEpisodeLocked()
        current = if (next.phase == HaLifecyclePhase.SHUTTING_DOWN) HaLifecycleState.SHUTTING_DOWN else HaLifecycleState.STARTING
        revision++
    }

    /** A replaced native endpoint retires its predecessor's outage. */
    fun onNativeRetired(nowMs: Long) = synchronized(lock) {
        if (stateLocked(nowMs) != HaLifecycleState.NORMAL || source != null) revision++
        current = HaLifecycleState.NORMAL
        source = null
        notice = null
    }

    /** Authentication can clear an unexplained connection loss, never a PA-reported startup. */
    fun onAuthenticatedRunning(nowMs: Long) = synchronized(lock) {
        if (stateLocked(nowMs) == HaLifecycleState.CONNECTION_LOST) {
            current = HaLifecycleState.NORMAL
            recoveryAnnouncedEpisode = episode
            source = null
            revision++
        }
    }

    /**
     * Mark this as a distinct outage, so a recovery announced for an earlier one cannot suppress it.
     *
     * Unconditional by proof, not by accident. A guard skipping re-entry into an outage already showing
     * was written first and then DELETED: mutating it away killed nothing, because the number is only
     * ever compared against itself, so bumping it inside one outage is unobservable. An unprovable
     * guard is worse than no guard, since it reads as protection nobody has tested.
     */
    private fun bumpEpisodeLocked() {
        episode++
    }

    /** Announce one recovery per outage without restarting an existing notice's window. */
    private fun enterBackOnlineLocked(nowMs: Long) {
        if (episode == recoveryAnnouncedEpisode) {
            // Already told the user this outage ended. Land on NORMAL without re-announcing.
            if (current != HaLifecycleState.NORMAL) {
                current = HaLifecycleState.NORMAL
                source = null
                revision++
            }
            return
        }
        recoveryAnnouncedEpisode = episode
        current = HaLifecycleState.BACK_ONLINE
        backOnlineSinceMs = nowMs
        revision++
    }

    companion object {
        const val DEFAULT_BACK_ONLINE_WINDOW_MS = 8_000L
    }
}

/**
 * The user-facing copy for each state, kept beside the machine so the wording is unit-testable and
 * cannot drift between the renderer bar and the web UI.
 *
 * Pure — unit-tested in `HaLifecycleTest`.
 */
internal object HaLifecycleMessage {
    fun text(snap: HaLifecycle.Snapshot): String? {
        if (snap.state == HaLifecycleState.NORMAL ||
            (snap.state == HaLifecycleState.CONNECTION_LOST && snap.offlineGraceRemainingMs > 0L)) return null
        if (snap.state == HaLifecycleState.BACK_ONLINE) return "Home Assistant is back online."
        val expected = snap.expectedMs
        val elapsed = snap.elapsedMs
        val overdue = expected != null && elapsed != null && elapsed > expected
        val headline = if (overdue) "Taking longer than usual"
        else text(snap.state, snap.source) ?: "Home Assistant has gone offline — controls may be temporarily unavailable."
        val reason = when (snap.reason) {
            HaLifecycleReason.RESTART -> "Home Assistant restart"
            HaLifecycleReason.HOST_REBOOT -> "Host reboot"
            HaLifecycleReason.CORE_UPDATE -> "Home Assistant Core update"
            HaLifecycleReason.UNKNOWN -> "Reason unknown"
        }
        val forecast = if (expected == null || elapsed == null) "Time back has not been measured yet"
        else {
            val duration = haLifecycleDuration(kotlin.math.abs(expected - elapsed))
            if (overdue) "${duration.value} ${duration.abbreviation} past the estimate"
            else "Expected back in about ${duration.value} ${duration.abbreviation}"
        }
        return "$headline · $reason · $forecast"
    }

    fun text(state: HaLifecycleState, source: HaLifecycleSource?): String? = when (state) {
        // Only a Panel Assistant notice proves lifecycle intent.
        HaLifecycleState.SHUTTING_DOWN -> if (source == HaLifecycleSource.NATIVE)
            "Home Assistant is shutting down — controls may be temporarily unavailable."
        else "Home Assistant has gone offline — controls may be temporarily unavailable."
        HaLifecycleState.STARTING ->
            "Home Assistant is starting — controls will return shortly."
        HaLifecycleState.BACK_ONLINE ->
            "Home Assistant is back online."
        HaLifecycleState.NORMAL, HaLifecycleState.CONNECTION_LOST -> null
    }
}

/** A duration retains its unit, so an hour can never look like a minute. */
internal data class HaLifecycleDuration(val value: Long, val unit: String) {
    val abbreviation: String get() = when (unit) { "hours" -> "h"; "minutes" -> "min"; else -> "sec" }
}
internal fun haLifecycleDuration(durationMs: Long): HaLifecycleDuration {
    val milliseconds = durationMs.coerceAtLeast(0L)
    val seconds = milliseconds / 1_000L + if (milliseconds % 1_000L > 0L) 1L else 0L
    return when {
        seconds >= 3_600L -> HaLifecycleDuration((seconds + 3_599L) / 3_600L, "hours")
        seconds >= 60L -> HaLifecycleDuration((seconds + 59L) / 60L, "minutes")
        else -> HaLifecycleDuration(seconds, "seconds")
    }
}
