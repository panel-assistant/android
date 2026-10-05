package io.panelassistant.android.camera

/**
 * Pure decision logic for the camera session watchdog. The owner's tick thread gathers one [Tick] and
 * executes the [Decision]; nothing here touches Android, so every branch is unit-testable.
 *
 * Priority is fixed and deliberate: a stop wins over everything, then the master switch, then "nobody
 * is watching", and only then a hardware fault. So a live session closes within one tick of the switch
 * turning off even while the reconfigure lane that also carries the change is still queued — the
 * privacy stop never waits behind a retry ladder. Recovery is bounded in both directions: a fault
 * reopens with exponential backoff up to [maxConsecutiveFailures], after which the session is declared
 * degraded and stays there, visibly, rather than spinning.
 */
class CameraSessionPolicy(
    /** Nominal interval between frames at the negotiated rate. */
    val frameIntervalMs: Long,
    /** No frame for this many intervals is starvation, never less than [minStarvationMs]. */
    val starvationMultiplier: Int = 4,
    val minStarvationMs: Long = 2_000L,
    /** A freshly opened session must deliver its first frame within this. */
    val openGraceMs: Long = 5_000L,
    val maxConsecutiveFailures: Int = 3,
    val initialBackoffMs: Long = 1_000L,
    val maxBackoffMs: Long = 30_000L,
    /**
     * After the encoder refuses or fails, stream leases are refused for this long without touching the
     * codec again, so a reconnecting client's patience does not set the retry rate. Snapshots are
     * unaffected: the camera is not what failed.
     */
    val encoderHoldMs: Long = 30_000L,
) {
    init {
        require(frameIntervalMs > 0) { "frameIntervalMs must be positive" }
        require(maxConsecutiveFailures >= 1) { "maxConsecutiveFailures must be at least 1" }
    }

    val starvationMs: Long get() = maxOf(frameIntervalMs * starvationMultiplier, minStarvationMs)

    data class Tick(
        val nowMs: Long,
        val openedAtMs: Long,
        /** Monotonic time of the last real captured frame, or null when none has arrived yet. */
        val lastFrameAtMs: Long?,
        val clients: Int,
        val enabled: Boolean,
        val stopping: Boolean,
        /** A fault the device reported since the last tick, already classified. */
        val deviceFault: CameraFault?,
        /** Failures since the last delivered frame; the owner resets it when a frame arrives. */
        val consecutiveFailures: Int,
    )

    enum class CloseReason { STOPPING, DISABLED, IDLE }

    sealed interface Decision {
        data object Continue : Decision
        data class Close(val reason: CloseReason) : Decision
        data class Reopen(val afterMs: Long, val fault: CameraFault, val attempt: Int) : Decision
        data class Degrade(val fault: CameraFault, val attempt: Int) : Decision
    }

    fun onTick(tick: Tick): Decision {
        if (tick.stopping) return Decision.Close(CloseReason.STOPPING)
        if (!tick.enabled) return Decision.Close(CloseReason.DISABLED)
        if (tick.clients <= 0) return Decision.Close(CloseReason.IDLE)
        val fault = tick.deviceFault ?: starvation(tick) ?: return Decision.Continue
        return onFailure(fault, tick.consecutiveFailures + 1)
    }

    /** The ladder for an open that failed or a session that faulted; [attempt] counts from 1. */
    fun onFailure(fault: CameraFault, attempt: Int): Decision =
        if (attempt >= maxConsecutiveFailures) Decision.Degrade(fault, attempt)
        else Decision.Reopen(backoffMs(attempt), fault, attempt)

    fun backoffMs(attempt: Int): Long {
        val shift = (attempt - 1).coerceIn(0, 30)
        val raw = initialBackoffMs shl shift
        return if (raw <= 0 || raw > maxBackoffMs) maxBackoffMs else raw
    }

    private fun starvation(tick: Tick): CameraFault? {
        val reference = tick.lastFrameAtMs
        val limit = if (reference == null) openGraceMs else starvationMs
        val since = tick.nowMs - (reference ?: tick.openedAtMs)
        return if (since > limit) CameraFault.STARVED else null
    }
}
