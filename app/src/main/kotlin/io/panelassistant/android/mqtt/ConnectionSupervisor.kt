package io.panelassistant.android.mqtt

/**
 * Pure decision logic for the MQTT reconnect watchdog — the state machine behind the incident history:
 * an auto-reconnect that stalled after a broker/HA restart, a half-open "connected but dead" socket that
 * published into the void, and a rebuild that wedged inside the client and disabled healing. Extracted
 * from the watchdog thread so it can be unit-tested without HiveMQ or Android; the thread only executes
 * the [Action] returned and owns the timing + off-thread execution.
 *
 * Each [tick] decides whether to force a full client rebuild or a clean process boundary:
 *  - LIVENESS-stale: an established client stopped receiving broker ACKs → try one fresh client.
 *  - STATE-stuck: not connected for [STUCK_TICKS] consecutive ticks → rebuild.
 *  - DISCOVERY-wait: no client exists, so retry mDNS at the same bounded cadence without a family flip.
 * A fresh-client submission completes before its asynchronous connection does, so completion alone is
 * not recovery. After one rebuild, keep its selected address family and wait up to [rebuildAbandonMs]
 * for exact application readiness on a different concrete connection. A previously live process then crosses the
 * bounded process boundary. A new process first gives its durably restored route the same full grace;
 * only after that may it alternate the family. A runtime that has never been application-ready never
 * crosses a process boundary — that would be a restart loop — but it does keep alternating its address
 * family at the [rebuildAbandonMs] cadence, because otherwise nothing can reach a live sibling family.
 * Broker progress resets the epoch and allows one later fallback if a different connection subsequently
 * goes stale.
 *
 * Not thread-safe; the single watchdog thread calls [tick] serially.
 */
class ConnectionSupervisor(
    private val staleMs: Long,
    private val rebuildAbandonMs: Long,
) {
    private var staleTicks = 0
    private var recoveryAttempt: RecoveryAttempt? = null
    private var observedRuntimeGeneration: Long? = null

    private data class RecoveryAttempt(
        val wasPreviouslyLive: Boolean,
        val baselineConnectionGeneration: Long?,
        val startedAt: Long,
        val reason: String,
        val heldSelectedFamily: Boolean,
        val announcementBoundaryAvailable: Boolean,
        var admitted: Boolean = false,
    )

    sealed interface Action {
        /** Link healthy or still within tolerance — do nothing this tick. */
        object None : Action

        /** Force the epoch's one fresh client, optionally selecting the other address family. */
        data class Rebuild(val reason: String, val flipFamily: Boolean = true) : Action

        /** The one fresh client made no broker progress within its bound; cross the process boundary. */
        data class ProcessRecovery(
            val reason: String,
            val consumeAnnouncementBudget: Boolean = false,
        ) : Action

        /** Preserve the admitted fresh client while waiting for exact application readiness. */
        data class SkipRebuild(val reason: String, val elapsedMs: Long) : Action
    }

    /**
     * @param state           the client's self-reported state ("connected" / "disabled" / anything else)
     * @param lastOkMs        timestamp of the last broker ACK, or 0 if none yet (liveness not armed)
     * @param sinceOkMs       ms since the last broker ACK
     * @param now             monotonic now (same clock across calls), for the wedged-rebuild guard
     * @param rebuildInFlight whether this supervisor's prior [Action.Rebuild] is still running
     * @param runtimeGeneration exact service-runtime generation owning [state] and [lastOkMs]
     * @param connectionGeneration broker-ACK epoch that produced [lastOkMs], or null before first ACK
     * @param holdSelectedFamily this process restored a durable route that has not yet reached the broker
     * @param applicationReadyEver this runtime has ACKed both online and state at least once
     * @param announcementBoundaryAvailable durable one-shot escape for a pre-readiness announcement wedge
     */
    fun tick(
        state: String,
        lastOkMs: Long,
        sinceOkMs: Long,
        now: Long,
        rebuildInFlight: Boolean,
        runtimeGeneration: Long = 0L,
        connectionGeneration: Long? = null,
        holdSelectedFamily: Boolean = false,
        applicationReadyEver: Boolean = state == CONNECTED,
        announcementBoundaryAvailable: Boolean = false,
    ): Action {
        if (observedRuntimeGeneration != runtimeGeneration) {
            // A config replacement starts its own connection epoch. lastOk commonly resets to zero, so
            // timestamp comparison alone cannot distinguish it from an old never-connected attempt.
            observedRuntimeGeneration = runtimeGeneration
            recoveryAttempt = null
            staleTicks = 0
        }
        // Auth recovery and terminal configuration states have their own authority. In particular, an
        // ordinary credentials failure must not age a generic attempt into a process restart.
        if (isAuthRecoveryState(state) || state == DISABLED || state == CONFIG_ERROR) {
            staleTicks = 0
            recoveryAttempt = null
            return Action.None
        }

        recoveryAttempt?.let { attempt ->
            val replacementProgress = attempt.admitted && state == CONNECTED &&
                connectionGeneration != null &&
                connectionGeneration != attempt.baselineConnectionGeneration
            if (replacementProgress) {
                // A late PUBACK from the old, still-current client can advance lastOk while the runtime
                // worker is queued. CONNACK and independent heartbeat PUBACKs can likewise arrive while
                // the replacement's discovery/state announcement is wedged. Only a successful owner
                // admission plus application-ready state on a different connection proves the fallback.
                recoveryAttempt = null
                staleTicks = 0
                return Action.None
            } else {
                val elapsedMs = (now - attempt.startedAt).coerceAtLeast(0L)
                if (elapsedMs >= rebuildAbandonMs) {
                    if (state == ANNOUNCING) {
                        // Every boundary from an announcement wedge spends the durable one-shot,
                        // including a runtime that was ready earlier. A recreated process with the
                        // consumed token must never inherit an unbounded restart loop.
                        if (attempt.announcementBoundaryAvailable) {
                            return Action.ProcessRecovery(
                                "${attempt.reason}-announcement-no-progress",
                                consumeAnnouncementBudget = true,
                            )
                        }
                    } else if (attempt.wasPreviouslyLive) {
                        return Action.ProcessRecovery("${attempt.reason}-no-progress")
                    }
                    if (attempt.heldSelectedFamily && !rebuildInFlight) {
                        // A route restored after the process boundary gets one uninterrupted grace
                        // window. If it still cannot reach the broker, try the alternate family.
                        return alternateFamily(attempt, connectionGeneration, now)
                    }
                    // A runtime that has NEVER been application-ready keeps alternating at this bounded
                    // cadence while its address family remains unproven. Going silent here is what
                    // stranded a panel on a black-holed address family for hours: the process boundary
                    // is correctly withheld from a never-live runtime — that would be a restart loop —
                    // but withholding the cheap in-place rebuild along with it left nothing able to
                    // reach the live sibling family at all. An announcing or connected wedge already
                    // proved its family through CONNACK and is never flipped by this path.
                    val addressFamilyUnproven = state != ANNOUNCING && state != CONNECTED
                    if (!attempt.wasPreviouslyLive && addressFamilyUnproven && !rebuildInFlight) {
                        return alternateFamily(attempt, connectionGeneration, now)
                    }
                }
                return Action.SkipRebuild(attempt.reason, elapsedMs)
            }
        }

        val reason = when {
            // Old liveness must never dominate a replacement's connecting/unreachable state. That was
            // the fleet loop: every 60 seconds it detached the still-connecting fallback and flipped the
            // address family again. Non-connected states use the separate two-tick startup grace below.
            state == CONNECTED && lastOkMs != 0L && sinceOkMs > staleMs -> {
                staleTicks = 0
                "liveness"
            }
            state == CONNECTED -> { staleTicks = 0; return Action.None }
            else -> {
                staleTicks++
                if (staleTicks < STUCK_TICKS) return Action.None
                staleTicks = 0
                if (state == DISCOVERING) {
                    // Unlike connecting/unreachable, discovery has no Hive client or automatic retry.
                    // Do not create a progress epoch that could suppress mDNS forever after one miss,
                    // but also never stack another discovery while its runtime mutation is still live.
                    return if (rebuildInFlight) Action.SkipRebuild("discovery", 0L)
                    else Action.Rebuild("discovery", flipFamily = false)
                }
                "state"
            }
        }
        // Non-discovery actions are emitted only when no local owner Future exists. Retain the route
        // restored across a process boundary for one full progress window before alternating it.
        recoveryAttempt = RecoveryAttempt(
            // CONNACK and heartbeat ACKs can exist while the application announcement is wedged.
            // Only this runtime having reached exact application-ready state earns process recovery.
            wasPreviouslyLive = applicationReadyEver,
            baselineConnectionGeneration = connectionGeneration,
            startedAt = now,
            reason = reason,
            heldSelectedFamily = holdSelectedFamily,
            announcementBoundaryAvailable = announcementBoundaryAvailable,
        )
        return Action.Rebuild(reason, flipFamily = !holdSelectedFamily)
    }

    /** Open a fresh recovery epoch that selects the other address family. */
    private fun alternateFamily(
        attempt: RecoveryAttempt,
        connectionGeneration: Long?,
        now: Long,
    ): Action {
        recoveryAttempt = RecoveryAttempt(
            wasPreviouslyLive = false,
            baselineConnectionGeneration = connectionGeneration,
            startedAt = now,
            reason = attempt.reason,
            heldSelectedFamily = false,
            announcementBoundaryAvailable = false,
        )
        return Action.Rebuild(attempt.reason, flipFamily = true)
    }

    /** The runtime owner accepted and completed the fallback mutation. Completion proves submission,
     * not broker recovery; [tick] still requires progress from the replacement connection generation. */
    fun rebuildAdmitted() {
        recoveryAttempt?.admitted = true
    }

    /** The runtime owner rejected or could not publish the requested mutation. It was not a recovery
     * attempt, so it must not consume the epoch or age into a process boundary. Watchdog-thread only. */
    fun rebuildNotAdmitted() {
        recoveryAttempt = null
        staleTicks = 0
    }

    /** The original connection recovered while owner work was queued; consume the stale epoch cleanly. */
    fun recoveryNoLongerNeeded() {
        recoveryAttempt = null
        staleTicks = 0
    }

    /** No runtime is currently published. Forget observations from the retired generation and wait for
     * the owner to publish one coherent replacement before making another recovery decision. */
    fun runtimeUnavailable() {
        observedRuntimeGeneration = null
        recoveryAttempt = null
        staleTicks = 0
    }

    companion object {
        const val STUCK_TICKS = 2
        private const val CONNECTED = "connected"
        private const val ANNOUNCING = "announcing"
        private const val DISCOVERING = "discovering"
        private const val DISABLED = "disabled"
        private const val CONFIG_ERROR = "config-error"
    }
}

/**
 * Connection-generation-aware admission for sacrificial MQTT heartbeat threads.
 *
 * A live heartbeat suppresses another probe only while it belongs to the current transport connection.
 * Once an in-place reconnect or runtime replacement advances the connection generation, the old client
 * may remain wedged forever; the replacement still needs exactly one heartbeat thread of its own.
 */
internal object HeartbeatAdmission {
    sealed interface Decision {
        data object NoCurrentConnection : Decision
        data object CurrentHeartbeatAlive : Decision
        data class Admit(val generation: Long, val replacingStranded: Boolean) : Decision
        data class EscalateRecovery(val strandedGenerations: Int) : Decision
    }

    fun decide(
        currentGeneration: Long?,
        liveTrackedGenerations: Collection<Long>,
        maxStrandedGenerations: Int = MAX_STRANDED_GENERATIONS,
    ): Decision {
        currentGeneration ?: return Decision.NoCurrentConnection
        if (currentGeneration in liveTrackedGenerations) {
            return Decision.CurrentHeartbeatAlive
        }
        val stranded = liveTrackedGenerations.count { it != currentGeneration }
        if (stranded > maxStrandedGenerations) {
            return Decision.EscalateRecovery(stranded)
        }
        return Decision.Admit(
            generation = currentGeneration,
            replacingStranded = stranded > 0,
        )
    }

    const val MAX_STRANDED_GENERATIONS = 2
}

/**
 * Process-unique identity for one concrete MQTT transport connection attempt.
 *
 * A service runtime can rebuild its HiveMQ client in place, so the service runtime generation is too
 * coarse for sacrificial heartbeat ownership: a heartbeat wedged on the retired client must not suppress
 * a probe on its replacement. Every successful broker connection advances this identity, including
 * HiveMQ-managed automatic reconnects that reuse the same transport client.
 */
internal class MqttConnectionGeneration {
    @Volatile private var current = 0L

    fun advance(): Long = NEXT.incrementAndGet().also { current = it }
    fun currentOrNull(): Long? = current.takeIf { it > 0L }
    fun isCurrent(generation: Long): Boolean = generation > 0L && current == generation
    fun clear() {
        current = 0L
    }

    private companion object {
        val NEXT = java.util.concurrent.atomic.AtomicLong()
    }
}
