package io.github.maxlyth.hapaneld

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.maxlyth.hapaneld.util.ServiceRuntimeOwner
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Android sweeps a destroyed service's receivers as soon as `onDestroy` returns and reports each one
 * still registered as `IntentReceiverLeaked`. When runtime teardown overruns the service deadline the
 * queued runtime cleanup has not run by then, so the receivers must be gone before it is handed off.
 *
 * `registerReceiver` and `unregisterReceiver` are no-ops in the mockable android.jar, so the service's
 * own receiver fields are what these tests observe.
 */
class ServiceTeardownReceiverTest {
    private val receiverFields = listOf("screenOnReceiver", "webViewRebindReceiver")

    @Test fun everyReceiverIsReleasedBeforeOnDestroyReturnsWhileRuntimeShutdownIsBlocked() {
        val service = PaneldService()
        fenceProcessExit(service)
        for (name in receiverFields) setField(service, name, dummyReceiver())
        val lane = blockRuntimeLane(service)
        try {
            service.onDestroy()

            assertFalse(
                "the runtime lane must still be blocked, so its queued cleanup cannot be what released them",
                lane.finished.get(),
            )
            for (name in receiverFields) {
                assertNull("$name must be unregistered before onDestroy returns", field(service, name))
            }
        } finally {
            lane.release.countDown()
        }
    }

    @Test fun aStartupThatReachesRegistrationAfterTeardownBeganRegistersNothing() {
        val service = PaneldService()
        teardownBoundary(service).markStopping()

        startReceivers(service)

        for (name in receiverFields) {
            assertNull("$name must not be registered once teardown has begun", field(service, name))
        }
    }

    @Test fun anOrdinaryStartupRegistersEveryReceiver() {
        val service = PaneldService()

        startReceivers(service)

        for (name in receiverFields) assertNotNull("$name must be registered on startup", field(service, name))
    }

    private class BlockedLane(val release: CountDownLatch, val finished: AtomicBoolean)

    /** Occupy the runtime lane the way an in-flight startup does, so the queued teardown cannot run. */
    private fun blockRuntimeLane(service: PaneldService): BlockedLane {
        val networkRuntime = Class.forName("${PaneldService::class.java.name}\$NetworkRuntime")
        val owner = ServiceRuntimeOwner<Any>(allocate(networkRuntime), "test-runtime-lane")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = AtomicBoolean(false)
        owner.start {
            entered.countDown()
            release.await()
            finished.set(true)
        }
        assertTrue("the runtime lane must be occupied before teardown", entered.await(5, TimeUnit.SECONDS))
        setField(service, "runtime", owner)
        return BlockedLane(release, finished)
    }

    /**
     * The asynchronous finalizer ends in `exitProcess` on an incomplete teardown. Claiming the one
     * recovery slot first makes every later boundary run return before it, so the test JVM survives.
     */
    private fun fenceProcessExit(service: PaneldService) {
        assertTrue(teardownBoundary(service).recordCompletionAndClaimRecovery(false))
    }

    private fun startReceivers(service: PaneldService) {
        for (name in listOf("startScreenOnReconciliation", "startWebViewRebindWatch")) {
            PaneldService::class.java.getDeclaredMethod(name).apply { isAccessible = true }.invoke(service)
        }
    }

    private fun teardownBoundary(service: PaneldService): ServiceTeardownBoundary =
        field(service, "teardownBoundary") as ServiceTeardownBoundary

    private fun dummyReceiver(): BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = Unit
    }

    private fun field(service: PaneldService, name: String): Any? =
        PaneldService::class.java.getDeclaredField(name).apply { isAccessible = true }.get(service)

    private fun setField(service: PaneldService, name: String, value: Any?) {
        PaneldService::class.java.getDeclaredField(name).apply { isAccessible = true }.set(service, value)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> allocate(type: Class<T>): T {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        return unsafeClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, type) as T
    }
}
