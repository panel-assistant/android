package io.panelassistant.android.util

import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Durable renderer state relevant to automatic built-in preparation. */
data class RendererPreparationState(
    val dashboardPackage: String,
    val haUrl: String,
    val launchPending: Boolean = false,
)

/** Connection and presentation values read from the Companion before one atomic preference commit. */
data class BorrowedRendererSettings(
    val url: String,
    val accessToken: String,
    val refreshToken: String,
    val tokenExpiry: Long,
    val clientId: String,
    val zoom: Int?,
)

/**
 * Serializes renderer configuration with its required preparation and launch side-effects.
 *
 * A built-in renderer with a blank HA URL is the preparation retry condition. If the process dies after
 * selecting it but before borrowed settings commit, startup observes that same state and retries. The
 * connection and zoom values are persisted by [persist] in one commit, so startup cannot observe a
 * half-borrowed session. A launch-pending bit covers the other side of that commit and is cleared by
 * [completeLaunch] only after the launch side-effect returns.
 */
class RendererPreparationCoordinator(
    private val builtinPackage: String,
    private val state: () -> RendererPreparationState,
    private val borrow: () -> BorrowedRendererSettings?,
    private val persist: (BorrowedRendererSettings) -> Boolean,
    private val completeLaunch: () -> Boolean = { true },
) {
    enum class Result {
        NOT_BUILTIN,
        ALREADY_READY,
        NO_BORROWABLE_LOGIN,
        PREPARED,
        SUPERSEDED,
        PERSIST_FAILED,
        CLOSED,
    }

    private val lock = ReentrantLock()
    @Volatile private var closing = false
    @Volatile private var closed = false

    /** Run a config commit and its renderer effects without another renderer transaction interleaving. */
    fun <T> transaction(block: () -> T): T {
        check(!closing && !closed) { "renderer preparation is closed" }
        return lock.withLock {
            check(!closing && !closed) { "renderer preparation is closed" }
            block()
        }
    }

    /** Lifecycle operations decline cleanly when service teardown wins admission. */
    private fun resultTransaction(block: () -> Result): Result {
        if (closing || closed) return Result.CLOSED
        return lock.withLock {
            if (closing || closed) Result.CLOSED else block()
        }
    }

    /** Close admission and wait up to [timeoutMs] for an admitted transaction to leave its side-effects. */
    fun close(timeoutMs: Long): Boolean {
        closing = true
        val acquired = runCatching { lock.tryLock(timeoutMs, TimeUnit.MILLISECONDS) }.getOrDefault(false)
        if (acquired) {
            closed = true
            lock.unlock()
        }
        return acquired
    }

    /** Prepare the current renderer if needed without launching it. */
    fun prepareIfNeeded(): Result = resultTransaction { prepareLocked() }

    /** Prepare, then anchor and launch the latest committed renderer before returning. */
    fun launchConfigured(
        ensureHome: (dashboardPackage: String, builtinReady: Boolean) -> Unit,
        launchHome: (dashboardPackage: String) -> Unit,
    ): Result = resultTransaction {
        val result = prepareLocked()
        if (result == Result.PERSIST_FAILED || result == Result.CLOSED) return@resultTransaction result
        val current = state()
        if (closing) return@resultTransaction Result.CLOSED
        ensureHome(current.dashboardPackage, current.haUrl.isNotBlank())
        if (closing) return@resultTransaction Result.CLOSED
        launchHome(current.dashboardPackage)
        completePendingLaunch(result)
    }

    /**
     * Retry interrupted preparation at startup, then foreground a ready automatic/explicit renderer.
     * An automatic built-in renderer remains on its safe fallback until connection settings can be borrowed.
     */
    fun reconcileStartup(
        ensureHome: (dashboardPackage: String, builtinReady: Boolean) -> Unit,
        launchHome: (dashboardPackage: String) -> Unit,
    ): Result = resultTransaction {
        val result = prepareLocked()
        if (result == Result.PERSIST_FAILED || result == Result.CLOSED) return@resultTransaction result
        val current = state()
        ensureHome(current.dashboardPackage, current.haUrl.isNotBlank())
        if (closing) return@resultTransaction Result.CLOSED
        val blankBuiltinWithoutLogin = result == Result.NO_BORROWABLE_LOGIN &&
            isBuiltinRenderer(current.dashboardPackage) && current.haUrl.isBlank()
        val configuredRendererReady = !isBuiltinRenderer(current.dashboardPackage) || current.haUrl.isNotBlank()
        if (!closing && !blankBuiltinWithoutLogin && (configuredRendererReady || current.launchPending)) {
            launchHome(current.dashboardPackage)
            return@resultTransaction completePendingLaunch(result)
        }
        result
    }

    private fun prepareLocked(): Result {
        if (closing) return Result.CLOSED
        val before = state()
        if (!isBuiltinRenderer(before.dashboardPackage)) return Result.NOT_BUILTIN
        if (before.haUrl.isNotBlank()) return Result.ALREADY_READY

        val borrowed = borrow() ?: return Result.NO_BORROWABLE_LOGIN
        if (closing) return Result.CLOSED
        val current = state()
        if (!isBuiltinRenderer(current.dashboardPackage) || current.haUrl.isNotBlank()) {
            return Result.SUPERSEDED
        }
        if (!persist(borrowed)) return Result.PERSIST_FAILED
        if (closing) return Result.CLOSED

        val after = state()
        return if (isBuiltinRenderer(after.dashboardPackage) && after.haUrl.isNotBlank()) {
            Result.PREPARED
        } else {
            Result.PERSIST_FAILED
        }
    }

    private fun isBuiltinRenderer(dashboardPackage: String): Boolean =
        dashboardPackage.isBlank() || dashboardPackage == builtinPackage

    private fun completePendingLaunch(result: Result): Result =
        if (state().launchPending && !completeLaunch()) Result.PERSIST_FAILED else result
}
