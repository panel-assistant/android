package io.github.maxlyth.hapaneld.sensors

/**
 * Home Assistant's own lifecycle events, ranked in the order core fires them across one restart:
 * `stop` -> `final_write` -> `close` -> `start` -> `started`.
 *
 * The rank orders the cycle and allocates each type a stable subscription id; [shutdown] partitions
 * the five into the going-away three and the coming-back two. Only the event TYPE is modelled — no
 * payload field is read anywhere, which keeps this seam free of entity ids, user ids and credentials.
 */
internal enum class HaLifecycleEvent(val wireValue: String, val rank: Int) {
    STOP("homeassistant_stop", 1),
    FINAL_WRITE("homeassistant_final_write", 2),
    CLOSE("homeassistant_close", 3),
    START("homeassistant_start", 4),
    STARTED("homeassistant_started", 5);

    val shutdown: Boolean get() = rank <= CLOSE.rank

    companion object {
        fun fromWire(eventType: String): HaLifecycleEvent? =
            entries.firstOrNull { it.wireValue == eventType }

        /** Every type this panel subscribes to, by exact name. */
        val subscribed: List<String> = entries.map { it.wireValue }
    }
}

/**
 * Where a lifecycle observation came from, because the two sources carry different authority.
 *
 * [SOCKET] is Home Assistant saying so itself. [MQTT] is the broker publishing Home Assistant's will,
 * which fires for a deliberate shutdown AND for Home Assistant merely losing its broker link — so it
 * proves the control path is gone, not that a shutdown was intended. The wording differs accordingly;
 * claiming a shutdown we cannot prove is the mislabelling this feature exists to avoid.
 */
internal enum class HaLifecycleSource { SOCKET, MQTT }

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
    private val inferredStartingWindowMs: Long = DEFAULT_INFERRED_STARTING_WINDOW_MS,
) {
    init {
        require(backOnlineWindowMs > 0L) { "back-online window must be positive" }
        require(inferredStartingWindowMs > 0L) { "inferred-starting window must be positive" }
    }

    private val lock = Any()
    private var current = HaLifecycleState.NORMAL
    private var backOnlineSinceMs = 0L
    private var inferredStartingSinceMs: Long? = null

    /**
     * Which source OBSERVED the current state, or null when no source did: the initial NORMAL, a
     * connection loss noticed locally, and a decayed recovery notice are the panel's own inferences,
     * and labelling them with a source that never reported them would be a small lie the diagnostics
     * repeat. Null is rendered as "no source", not defaulted to one.
     */
    private var source: HaLifecycleSource? = null

    // One fact, not a tally: did Home Assistant refuse this route? A per-subscription coverage tally
    // would be accounting the panel must then prove correct, and it buys nothing — partial socket
    // coverage cannot strand an outage, because recovery is independently proven by Home Assistant's
    // MQTT birth AND by a fresh authenticated connection reaching LIVE. So the panel reports what it
    // OBSERVES and, separately, whether the socket route was refused. The refusal describes ONE
    // session's answer: it ends when that session dies or a new session starts answering for itself.
    private var refused = false

    /**
     * Does THIS session hold an accepted lifecycle subscription?
     *
     * The counterpart to [refused], and the only thing that separates "Home Assistant will tell us when
     * it has started" from "nothing ever will". It scopes to one session exactly as the refusal does:
     * set by an accepted subscribe, cleared when the session dies or a replacement starts subscribing.
     * Never persisted, never inferred from `!refused` — a stream that watches entities without watching
     * lifecycle is neither subscribed nor refused, and guessing would strand that panel's recovery.
     */
    private var subscribed = false

    /** Bumped under [lock] on every visible change, so a publisher can refuse to go backwards. */
    /**
     * Which OUTAGE this is, incremented when one opens. The machine's only other memory of "which
     * outage am I in" is the live state, and that is erased the moment a recovery notice decays to
     * NORMAL — which is what let a second, independent clearer announce the same recovery again.
     * An episode number outlives the state, so a recovery can be announced once per outage.
     */
    private var episode = 0L

    /** The episode whose recovery has already been announced, so a later clearer cannot repeat it. */
    private var recoveryAnnouncedEpisode = -1L

    /**
     * The channel the CURRENT outage depends on, as distinct from [source], the channel that claimed it.
     *
     * They differ for exactly one state and that difference was a defect: a locally-noticed
     * CONNECTION_LOST deliberately has no source, so retirement keyed on [source] could never match it
     * and the socket route going away left a stale outage rendering forever. The socket's disconnect is
     * what produced that inference, so the socket is its basis even though nothing claimed it.
     */
    private var basis: HaLifecycleSource? = null

    private var revision = 0L

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
        val refused: Boolean,
        val revision: Long,
        val backOnlineRemainingMs: Long,
    )

    fun snapshot(nowMs: Long): Snapshot = synchronized(lock) {
        Snapshot(stateLocked(nowMs), source, refused, revision, remainingLocked(nowMs))
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
        if (current == HaLifecycleState.STARTING) {
            inferredStartingSinceMs?.let { since ->
                val elapsed = nowMs - since
                if (elapsed >= inferredStartingWindowMs || elapsed < 0L) {
                    // The subscription may have been established after STARTED already fired. Retire
                    // only this inferred state silently; a server-reported START remains authoritative.
                    current = HaLifecycleState.NORMAL
                    source = null
                    inferredStartingSinceMs = null
                    revision++
                }
            }
            return current
        }
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

    /** One lifecycle observation arrived from [from]. */
    fun onEvent(event: HaLifecycleEvent, from: HaLifecycleSource, nowMs: Long) {
        synchronized(lock) {
            val observed = stateLocked(nowMs)
            // The socket is authoritative: once it has explained an outage, a broker will arriving for
            // the same event must not downgrade the wording back to a guess.
            if (!(observed == HaLifecycleState.SHUTTING_DOWN &&
                    source == HaLifecycleSource.SOCKET && from == HaLifecycleSource.MQTT)
            ) {
                if (source != from) revision++
                source = from
            }
            when {
                // Every shutdown stage means the same thing to a user, so they all land on one state and
                // a later stage cannot re-announce. This deliberately fires during BACK_ONLINE too —
                // that is how a rapid restart opens its second outage instead of being swallowed.
                event.shutdown -> {
                    inferredStartingSinceMs = null
                    if (current != HaLifecycleState.SHUTTING_DOWN) revision++
                    bumpEpisodeLocked()
                    current = HaLifecycleState.SHUTTING_DOWN
                    basis = from
                }

                event == HaLifecycleEvent.START -> {
                    // "starting" is not "back". It may never arrive, and it must not overwrite a
                    // recovery we have already proven.
                    inferredStartingSinceMs = null
                    if (observed != HaLifecycleState.STARTING && observed != HaLifecycleState.BACK_ONLINE) {
                        bumpEpisodeLocked()
                        current = HaLifecycleState.STARTING
                        basis = from
                        revision++
                    }
                }

                else ->
                    // STARTED is the authoritative "usable again". Absorb a duplicate so the recovery
                    // notice cannot restart its window.
                    if (observed != HaLifecycleState.BACK_ONLINE) enterBackOnlineLocked(nowMs)
            }
        }
    }

    /**
     * The socket dropped. Only meaningful when we have no lifecycle explanation: an outage we already
     * attribute to Home Assistant survives the disconnect that follows it, which is the whole point of
     * subscribing before the socket dies.
     */
    fun onDisconnected(nowMs: Long) {
        synchronized(lock) {
            when (stateLocked(nowMs)) {
                HaLifecycleState.SHUTTING_DOWN, HaLifecycleState.STARTING, HaLifecycleState.CONNECTION_LOST -> Unit
                else -> {
                    bumpEpisodeLocked()
                    current = HaLifecycleState.CONNECTION_LOST
                    // The loss was noticed locally; no source observed it, and claiming one would
                    // lend the guess a confidence it has not earned. It still has a BASIS: the socket
                    // whose disconnect produced the inference, which is what makes it retirable.
                    source = null
                    basis = HaLifecycleSource.SOCKET
                    revision++
                }
            }
            // A dead socket cannot still be a refused one; the next connection answers for itself.
            // The same is true of an accepted subscription: it died with the session that held it.
            clearRefusalLocked()
            subscribed = false
        }
    }

    /**
     * The basis for a recorded refusal ended without a disconnect: a replacement session has started
     * issuing its own subscriptions, or the socket route stopped being watched at all. Either way the
     * refusal describes a session that no longer answers, so keeping it would claim the CURRENT
     * user/session is refused when it was never asked. The new session re-records a refusal if it
     * receives one.
     */
    fun onRefusalBasisEnded() {
        synchronized(lock) { clearRefusalLocked() }
    }

    /**
     * A source's channel is gone for good — not merely quiet. Every claim [from] made is retired,
     * because the retraction would have arrived on the channel that just went away: hiding such a
     * claim is not enough, since re-enabling the route later would resurface an outage from the
     * previous era as though it were current. Claims from the OTHER source are untouched; they have
     * their own clearers.
     *
     * This is the general form of [onMqttChannelLost]: each source's claims die with its own channel.
     */
    fun onSourceRetired(from: HaLifecycleSource, nowMs: Long) {
        synchronized(lock) {
            // Keyed on BASIS, not on the claiming source. A locally-noticed CONNECTION_LOST has no
            // source by design, so a source-keyed test could never match it and the socket route going
            // away left the stale outage rendering for as long as the OTHER leg kept the snapshot alive.
            // Basis still scopes correctly: that inference belongs to the socket, so an MQTT channel
            // loss leaves it untouched.
            if (basis != from) return
            val observed = stateLocked(nowMs)
            if (observed == HaLifecycleState.NORMAL) {
                // Nothing claimed; only the attribution needs clearing.
                if (source != null) revision++
                source = null
                basis = null
                return
            }
            current = HaLifecycleState.NORMAL
            source = null
            // `basis` is deliberately NOT cleared here. Every outage entry assigns it, so a stale value
            // can never be read; a clear was written first, proved unkillable by mutation, and removed.
            revision++
        }
    }

    private fun clearRefusalLocked() {
        if (refused) revision++
        refused = false
        // A replacement session has not yet been answered, so it holds no subscription either. Clearing
        // both together is what stops one session's privileges describing the next one's.
        subscribed = false
    }

    /**
     * A fresh authenticated connection reached LIVE, which proves the server is running. This is the
     * second of the two things allowed to clear an outage, and it is what covers a client that missed
     * `homeassistant_started` entirely.
     */
    fun onAuthenticatedRunning(nowMs: Long) {
        synchronized(lock) {
            val observed = stateLocked(nowMs)
            when (observed) {
                // We claimed Home Assistant was down, and the socket is back. What that PROVES depends
                // entirely on whether this session will be told when the server has actually started.
                HaLifecycleState.SHUTTING_DOWN, HaLifecycleState.STARTING -> {
                    if (source != HaLifecycleSource.SOCKET) revision++
                    source = HaLifecycleSource.SOCKET
                    if (subscribed) {
                        // Measured on hardware 2026-08-14: Home Assistant accepted an authenticated
                        // connection 28 s BEFORE `homeassistant_start`, so announcing recovery here
                        // told the user controls had returned while every control was still dead.
                        // An accepted subscription promises the real event, so wait for it. Re-entry
                        // is absorbed: a flapping socket must not re-announce a startup already shown.
                        if (observed != HaLifecycleState.STARTING) {
                            current = HaLifecycleState.STARTING
                            inferredStartingSinceMs = nowMs
                            revision++
                        }
                    } else {
                        // Refused, or never subscribed at all. Nothing will ever say "started", so the
                        // authenticated socket is the best proof this panel can obtain and withholding
                        // recovery would strand the notice for good.
                        enterBackOnlineLocked(nowMs)
                    }
                }
                // We never blamed Home Assistant, so do not announce it "back" — that would retroactively
                // relabel an ordinary LAN blip as a server outage.
                HaLifecycleState.CONNECTION_LOST -> {
                    current = HaLifecycleState.NORMAL
                    source = null
                    revision++
                }
                else -> Unit
            }
        }
    }

    /**
     * Home Assistant refused one lifecycle subscription — normal for a non-admin user, since these types
     * are not in the non-admin allowlist. Non-fatal by contract: the panel simply learns nothing.
     */
    /**
     * Home Assistant ACCEPTED a lifecycle subscription for this session, so a startup will announce
     * itself. Not a rendered fact — it changes no state and bumps no revision — it only decides whether
     * a later authenticated socket may be treated as proof of readiness.
     */
    fun onSubscriptionEstablished() {
        synchronized(lock) { subscribed = true }
    }

    fun onSubscriptionRejected() {
        synchronized(lock) {
            if (!refused) revision++
            refused = true
        }
    }

    /**
     * The panel's OWN broker connection ended — the channel an MQTT-sourced claim was heard on.
     *
     * A claim is only as durable as the panel's ability to hear its retraction. The
     * outage-survives-disconnect rule exists for the SOCKET source, whose retraction arrives
     * independently over MQTT or a fresh authenticated connection; an MQTT-sourced outage that outlives
     * its own channel has no such clearer on a panel without the socket route, because Home Assistant's
     * birth is not retained by default and a birth missed during the gap is missed forever. So the claim
     * is downgraded to the generic connection-loss state, which owns "we can no longer know".
     */
    fun onMqttChannelLost(nowMs: Long) {
        synchronized(lock) {
            if (stateLocked(nowMs) == HaLifecycleState.SHUTTING_DOWN && source == HaLifecycleSource.MQTT) {
                current = HaLifecycleState.CONNECTION_LOST
                // Downgraded to a local inference: the source that made the claim can no longer
                // retract it, so the state stops carrying its name.
                source = null
                revision++
            }
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

    /**
     * Announce recovery, or clear silently when THIS episode's recovery has already been announced.
     *
     * A client whose lifecycle subscription was refused can announce recovery on its socket handshake,
     * decay to NORMAL, and then announce again 39 s later when Home Assistant's broker birth arrives —
     * one outage, two banners with a gap between them. Both clearers
     * are legitimate and neither can be removed, so the episode is what de-duplicates them.
     *
     * Deliberately NOT keyed on the live state: by the time the second clearer lands the notice has
     * decayed to NORMAL, which is exactly why a state-keyed check could not see it was the same outage.
     */
    private fun enterBackOnlineLocked(nowMs: Long) {
        if (episode == recoveryAnnouncedEpisode) {
            // Already told the user this outage ended. Land on NORMAL without re-announcing.
            inferredStartingSinceMs = null
            if (current != HaLifecycleState.NORMAL) {
                current = HaLifecycleState.NORMAL
                source = null
                basis = null
                revision++
            }
            return
        }
        recoveryAnnouncedEpisode = episode
        current = HaLifecycleState.BACK_ONLINE
        inferredStartingSinceMs = null
        backOnlineSinceMs = nowMs
        revision++
    }

    companion object {
        const val DEFAULT_BACK_ONLINE_WINDOW_MS = 8_000L
        const val DEFAULT_INFERRED_STARTING_WINDOW_MS = 120_000L
    }
}

/**
 * The user-facing copy for each state, kept beside the machine so the wording is unit-testable and
 * cannot drift between the renderer bar and the web UI.
 *
 * Pure — unit-tested in `HaLifecycleTest`.
 */
internal object HaLifecycleMessage {
    fun text(state: HaLifecycleState, source: HaLifecycleSource?): String? = when (state) {
        // Only the socket proves INTENT. Anything else — a broker will, or no attributed source at
        // all — may honestly claim only that the control path vanished, so the weaker wording is the
        // default and the stronger one requires the socket by name.
        HaLifecycleState.SHUTTING_DOWN -> if (source == HaLifecycleSource.SOCKET)
            "Home Assistant is shutting down — controls may be temporarily unavailable."
        else "Home Assistant has gone offline — controls may be temporarily unavailable."
        HaLifecycleState.STARTING ->
            "Home Assistant is starting — controls will return shortly."
        HaLifecycleState.BACK_ONLINE ->
            "Home Assistant is back online."
        HaLifecycleState.NORMAL, HaLifecycleState.CONNECTION_LOST -> null
    }
}
