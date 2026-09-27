package io.github.maxlyth.hapaneld.util

import io.github.maxlyth.hapaneld.util.LatestOperationPolicy
import io.github.maxlyth.hapaneld.util.LatestOperationTimeoutPolicy
import io.github.maxlyth.hapaneld.util.ServiceRuntimeOwner
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The backlog recorded that `PaneldService.reconfigure()` was starved on `Dispatchers.IO` when blocking
 * `su` calls exhausted the pool. This saturates the pool the way `su` does (threads parked in a blocking
 * wait, not suspended) and proves a network reconfigure is still admitted and run promptly, because it
 * executes on the runtime owner's dedicated lane rather than on the shared pool.
 */
class ReconfigureIoStarvationTest {
    @Test fun reconfigureRunsWhileBlockingWorkSaturatesDispatchersIo() {
        val release = CountDownLatch(1)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val watchdog = Executors.newSingleThreadScheduledExecutor()
        val scheduled = mutableMapOf<Runnable, ScheduledFuture<*>>()
        val reconfigured = CountDownLatch(1)
        val owner = ServiceRuntimeOwner(
            initial = Any(),
            threadName = "ha-paneld-runtime",
            latestOperation = LatestOperationPolicy(
                name = "network-reconfigure",
                timeout = LatestOperationTimeoutPolicy(
                    budgetMs = 30_000,
                    schedule = { task, delayMs ->
                        synchronized(scheduled) {
                            scheduled[task] = watchdog.schedule(task, delayMs, TimeUnit.MILLISECONDS)
                        }
                        true
                    },
                    cancel = { task -> synchronized(scheduled) { scheduled.remove(task)?.cancel(false) } },
                    onTimeout = {},
                ),
                operation = { reconfigured.countDown() },
            ),
        )
        try {
            // Park more blocking callers than Dispatchers.IO will ever run at once (its limit is
            // max(64, cores)), exactly as synchronous `su` waits do.
            repeat(SATURATING_BLOCKERS) { scope.launch { release.await() } }
            val probeRan = CountDownLatch(1)
            scope.launch { probeRan.countDown() }
            assertFalse(
                "the pool was not saturated, so this test would prove nothing",
                probeRan.await(500, TimeUnit.MILLISECONDS),
            )

            assertTrue(owner.start {}.get(2, TimeUnit.SECONDS))
            assertEquals(ServiceRuntimeOwner.LatestAdmission.ACCEPTED, owner.requestLatest())
            assertTrue(
                "reconfigure was starved by blocking work on Dispatchers.IO",
                reconfigured.await(2, TimeUnit.SECONDS),
            )
        } finally {
            release.countDown()
            scope.cancel()
            owner.shutdown(2_000) {}
            watchdog.shutdownNow()
        }
    }

    private companion object {
        const val SATURATING_BLOCKERS = 512
    }
}
