package io.github.maxlyth.hapaneld.shizuku

/** User-facing lifecycle state; plain data so status rendering and retry policy stay unit-testable. */
enum class ShizukuState {
    MANAGER_MISSING,
    MANAGER_UNTRUSTED,
    DISABLED,
    STOPPED,
    PERMISSION_REQUIRED,
    MANUAL_GRANT_REQUIRED,
    BINDING,
    READY,
    INCOMPATIBLE,
    ERROR,
}

internal object ShizukuPolicy {
    enum class RejectedBindingDisposition {
        IGNORE_STALE,
        REMOVE_CURRENT,
        REMOVE_CURRENT_AND_RECONNECT,
    }

    const val MANAGER_PACKAGE = ShizukuManagerIdentity.PACKAGE
    const val SHELL_UID = 2000
    const val PROTOCOL_VERSION = 3
    val USER_SERVICE_TAG = userServiceTag(PROTOCOL_VERSION)
    const val MIN_DPI = 80
    const val MAX_DPI = 640
    const val MIN_FONT_SCALE = 0.5f
    const val MAX_FONT_SCALE = 1.5f
    const val MAX_SCREENSHOT_BYTES = 32 * 1024 * 1024
    const val MAX_APK_BYTES = 512L * 1024L * 1024L
    const val MAX_INSTALL_DEADLINE_MS = 180_000L

    fun usable(uid: Int, protocol: Int): Boolean =
        uid == SHELL_UID && protocol == PROTOCOL_VERSION

    fun idleState(
        manager: ShizukuManagerIdentity.Status,
        consentEnabled: Boolean,
    ): ShizukuState = when (manager) {
        ShizukuManagerIdentity.Status.MISSING -> ShizukuState.MANAGER_MISSING
        ShizukuManagerIdentity.Status.UNTRUSTED -> ShizukuState.MANAGER_UNTRUSTED
        ShizukuManagerIdentity.Status.TRUSTED ->
            if (consentEnabled) ShizukuState.STOPPED else ShizukuState.DISABLED
    }

    /** A disconnected ha-paneld UserService does not imply that Shizuku's core service stopped. */
    fun disconnectedState(
        manager: ShizukuManagerIdentity.Status,
        consentEnabled: Boolean,
        managerRunning: Boolean,
    ): ShizukuState = idleState(manager, consentEnabled).let { idle ->
        if (idle == ShizukuState.STOPPED && managerRunning) ShizukuState.ERROR else idle
    }

    /** The tag is part of Shizuku's retained UserService cache identity. */
    internal fun userServiceTag(protocol: Int): String = "hapaneld-shell-v$protocol"

    fun validKeyCode(keyCode: Int): Boolean = keyCode in 0..320
    fun validCoordinate(value: Int): Boolean = value in 0..100_000
    fun validDensity(dpi: Int): Boolean = dpi in MIN_DPI..MAX_DPI
    fun validFontScale(scale: Float): Boolean =
        scale.isFinite() && scale in MIN_FONT_SCALE..MAX_FONT_SCALE
    fun validApkLength(length: Long): Boolean = length in 1..MAX_APK_BYTES

    /** Keep the remote operation bounded even if a future caller supplies an excessive deadline. */
    fun installServiceDeadline(requestedMs: Long): Long? =
        requestedMs.takeIf { it > 0L }?.coerceAtMost(MAX_INSTALL_DEADLINE_MS)

    /** The client deadline outlives the UserService deadline and its bounded process/thread cleanup. */
    fun clientDeadline(innerTimeoutMs: Long): Long =
        if (innerTimeoutMs > Long.MAX_VALUE - 5_000L) Long.MAX_VALUE else innerTimeoutMs + 5_000L

    fun canAcceptBinding(
        callbackGeneration: Long,
        currentGeneration: Long,
        connectionIsCurrent: Boolean,
        consentEnabled: Boolean,
        managerTrusted: Boolean,
        identityUsable: Boolean,
    ): Boolean = callbackGeneration == currentGeneration && connectionIsCurrent && consentEnabled &&
        managerTrusted && identityUsable

    fun rejectedBindingDisposition(
        callbackGeneration: Long,
        currentGeneration: Long,
        connectionIsCurrent: Boolean,
        nextState: ShizukuState,
    ): RejectedBindingDisposition {
        if (callbackGeneration != currentGeneration || !connectionIsCurrent) {
            return RejectedBindingDisposition.IGNORE_STALE
        }
        return if (nextState == ShizukuState.ERROR) {
            RejectedBindingDisposition.REMOVE_CURRENT_AND_RECONNECT
        } else {
            RejectedBindingDisposition.REMOVE_CURRENT
        }
    }

    /**
     * Shizuku reports a rationale after the user has denied access and its prompt cannot be used to
     * recover the grant. Only a fresh explicit opt-in may open the prompt; denied access must send the
     * user to Shizuku's Authorized applications screen instead of repeatedly requesting permission.
     */
    fun shouldRequestPermission(explicitRequest: Boolean, rationaleRequired: Boolean): Boolean =
        explicitRequest && !rationaleRequired
}

/**
 * Two-stage startup admission for the bridge, so `Application.onCreate` reaches no durable state.
 *
 * Android arms the `startForegroundService` deadline at the caller, so on an out-of-process cold start
 * the whole of `Application.onCreate` is spent out of `PaneldService`'s budget to reach
 * `startForeground`. Registration therefore only installs the Binder lifecycle listeners; reading
 * consent — which opens `ha-paneld.db` — waits for [admitActivation], which the service calls once it
 * has promoted. Nothing is lost by waiting: a listener that fires before activation is discarded, and
 * the activating `refresh()` re-derives the whole state from `PackageManager` and `pingBinder()`
 * rather than from having observed the event.
 */
internal class ShizukuStartupGate {
    @Volatile private var registeredFlag = false
    @Volatile private var activatedFlag = false

    /** Listeners are installed, but durable state is still off limits. */
    val registered: Boolean get() = registeredFlag

    /** Durable state — consent, and so the database — may be read. */
    val activated: Boolean get() = activatedFlag

    /** True exactly once, for the caller that installs the listeners. */
    @Synchronized fun admitRegistration(): Boolean {
        if (registeredFlag) return false
        registeredFlag = true
        return true
    }

    /** True exactly once, for the caller that opens durable reads after the promote. */
    @Synchronized fun admitActivation(): Boolean {
        if (!registeredFlag || activatedFlag) return false
        activatedFlag = true
        return true
    }
}

internal fun interface ShizukuScheduledHandle {
    fun cancel()
}

internal fun interface ShizukuScheduler {
    fun schedule(delayMs: Long, action: () -> Unit): ShizukuScheduledHandle
}

/** Two-stage dispatch: leave Shizuku's main-loop callback first, then run Binder mutation off main. */
internal class ShizukuCallbackMutationDispatcher(
    private val postBarrier: (Runnable) -> Boolean,
    private val dispatchOffMain: (Runnable) -> Boolean,
    private val onOffMainRejected: () -> Unit = {},
) {
    fun dispatch(action: Runnable): Boolean = postBarrier(
        Runnable {
            if (!dispatchOffMain(action)) onOffMainRejected()
        },
    )
}

/** Owns publication, cancellation, generation, and capped backoff for one deferred reconnect. */
internal class ShizukuReconnectCoordinator(
    private val scheduler: ShizukuScheduler,
    private val delaysMs: LongArray = longArrayOf(500L, 1_000L, 2_000L, 5_000L),
) {
    private data class Pending(
        val token: Long,
        val generation: Long,
        var handle: ShizukuScheduledHandle? = null,
    )

    private var attempt = 0
    private var nextToken = 1L
    private var pending: Pending? = null
    private var stableResetHandle: ShizukuScheduledHandle? = null

    init {
        require(delaysMs.isNotEmpty() && delaysMs.all { it > 0L })
    }

    fun schedule(generation: Long, action: () -> Unit): Boolean {
        val reservation: Pending
        val delayMs: Long
        synchronized(this) {
            if (pending != null) return false
            reservation = Pending(nextToken++, generation)
            pending = reservation
            delayMs = delaysMs[minOf(attempt, delaysMs.lastIndex)]
            if (attempt < delaysMs.lastIndex) attempt++
        }
        val handle = try {
            scheduler.schedule(delayMs) {
                val admitted = synchronized(this) {
                    if (pending?.token != reservation.token || pending?.generation != generation) {
                        false
                    } else {
                        pending = null
                        true
                    }
                }
                if (admitted) action()
            }
        } catch (_: Throwable) {
            synchronized(this) {
                if (pending?.token == reservation.token) pending = null
            }
            return false
        }
        synchronized(this) {
            if (pending?.token == reservation.token) {
                pending?.handle = handle
            } else {
                // A synchronous/injected scheduler may already have fired the reservation.
                handle.cancel()
            }
        }
        return true
    }

    fun cancel(resetBackoff: Boolean) {
        val handle = synchronized(this) {
            val current = pending?.handle
            pending = null
            if (resetBackoff) attempt = 0
            current
        }
        handle?.cancel()
    }

    @Synchronized fun resetBackoff() {
        attempt = 0
    }

    /**
     * Own the single deferred backoff-reset handle. After a stable period the caller's [onElapsed]
     * decides whether to [resetBackoff]; keeping the handle here lets a disconnect cancel it through
     * [cancelBackoffReset] instead of a second ad-hoc handle field outside the coordinator.
     */
    fun scheduleBackoffReset(delayMs: Long, onElapsed: () -> Unit): Boolean {
        synchronized(this) {
            stableResetHandle?.cancel()
            stableResetHandle = null
        }
        val handle = try {
            scheduler.schedule(delayMs) {
                onElapsed()
                synchronized(this) { stableResetHandle = null }
            }
        } catch (_: Throwable) {
            return false
        }
        synchronized(this) { stableResetHandle = handle }
        return true
    }

    fun cancelBackoffReset() {
        val handle = synchronized(this) {
            stableResetHandle.also { stableResetHandle = null }
        }
        handle?.cancel()
    }
}
