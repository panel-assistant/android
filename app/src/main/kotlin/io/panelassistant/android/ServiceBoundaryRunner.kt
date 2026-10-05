package io.panelassistant.android

import java.util.concurrent.atomic.AtomicBoolean

internal enum class ServiceTeardownDisposition { RELEASE, EXIT }

/** Whether a newly created Android service generation may take ownership inside this process. */
internal enum class ServiceGenerationAdmission { START, STAND_DOWN }

/**
 * Process-wide record that this OS process has already committed to exiting.
 *
 * An accepted explicit boundary is unconditionally terminal: [serviceTeardownDisposition] returns EXIT
 * for it whatever the teardown proves. The service still has to re-arm its start request before exiting,
 * because stopSelf() clears START_STICKY, and Android delivers that request into the *still-live*
 * process. The generation it creates there cannot outlive the exit, so it can never prove a startup
 * healthy, and anything it takes ownership of is owned twice for the length of the teardown.
 *
 * A generation created after the commitment must therefore stand down instead of resolving the staged
 * profile, opening the database or attaching hardware owners. Consuming the staged PENDING activation in
 * a process that is about to die is what leaves the fresh process an orphaned APPLYING to roll back.
 *
 * Only an accepted explicit boundary may commit. An ordinary clean stop deliberately leaves this open:
 * that teardown releases a same-process successor and never exits, so fencing it would park the
 * successor forever in a process with nothing left to restart it.
 */
internal class ProcessBoundaryCommitment {
    private val committed = AtomicBoolean(false)

    /** True for the one caller that commits this process; false for every caller after it. */
    fun commit(): Boolean = committed.compareAndSet(false, true)

    fun admitServiceGeneration(): ServiceGenerationAdmission =
        if (committed.get()) ServiceGenerationAdmission.STAND_DOWN else ServiceGenerationAdmission.START
}

/** Only an ordinary, completely proved teardown may open a same-process successor. */
internal fun serviceTeardownDisposition(
    completed: Boolean,
    explicitProcessBoundary: Boolean,
): ServiceTeardownDisposition =
    if (completed && !explicitProcessBoundary) ServiceTeardownDisposition.RELEASE
    else ServiceTeardownDisposition.EXIT

/**
 * One terminal decision for an Android service generation.
 *
 * Completion is sticky-false, explicit process-boundary requests are sticky, and exactly one caller
 * owns external-state recovery. Keeping those decisions under this one lock avoids composing a second
 * atomic owner in [PaneldService].
 */
internal class ServiceTeardownBoundary {
    private var explicitProcessBoundary = false
    private var recoveryClaimed = false
    private var finalized = false
    private var completed = true

    // Sticky "am I stopping?" signal, read from many threads on hot paths as a fast self-check. It is
    // deliberately a lock-free volatile independent of the synchronized decision above (as it was when it
    // lived on the service): the reads must not take this monitor, and the flag becomes true at exactly
    // the writer's program point, never as a side effect of a boundary decision.
    @Volatile private var stopping = false

    /** True once teardown or an explicit process boundary has begun. Set once, never cleared. */
    val isStopping: Boolean get() = stopping

    /** Latch the sticky stop signal. Idempotent; safe to call from any thread. */
    fun markStopping() {
        stopping = true
    }

    @Synchronized
    fun requestExplicitBoundary(): Boolean {
        if (finalized || explicitProcessBoundary) return false
        explicitProcessBoundary = true
        // Acceptance is itself the beginning of the explicit process boundary. Latch the
        // lock-free self-check before returning so callers cannot observe a split state.
        stopping = true
        return true
    }

    /** Record this caller's proof and atomically select the sole external-state recovery runner. */
    @Synchronized
    fun recordCompletionAndClaimRecovery(completed: Boolean): Boolean {
        if (finalized) return false
        this.completed = this.completed && completed
        if (recoveryClaimed) return false
        recoveryClaimed = true
        return true
    }

    @Synchronized
    fun claim(): ServiceTeardownDisposition? {
        if (finalized) return null
        check(recoveryClaimed)
        finalized = true
        return serviceTeardownDisposition(completed, explicitProcessBoundary)
    }
}

/** One external-state observation. Forced exit is permitted only by the service's durable safety rule. */
internal data class ServiceBoundaryProof(
    val externalStateSafe: Boolean,
    val forceFreshProcess: Boolean,
)

/**
 * Stateless composition of teardown proof, external-state recovery and the terminal release/exit action.
 * The boundary above owns the only mutable decision; this runner adds no thread, queue, timer or policy
 * state and is synchronously testable with hostile caller interleavings.
 */
internal fun runServiceBoundary(
    boundary: ServiceTeardownBoundary,
    completed: Boolean,
    prepare: () -> Unit,
    prove: (attempt: Int) -> ServiceBoundaryProof,
    pauseBeforeRetry: () -> Unit,
    finish: (ServiceTeardownDisposition) -> Unit,
) {
    if (!boundary.recordCompletionAndClaimRecovery(completed)) return
    prepare()
    var attempt = 0
    while (true) {
        attempt += 1
        val proof = prove(attempt)
        if (proof.externalStateSafe || proof.forceFreshProcess) {
            val claimed = boundary.claim() ?: return
            finish(if (proof.forceFreshProcess) ServiceTeardownDisposition.EXIT else claimed)
            return
        }
        pauseBeforeRetry()
    }
}
