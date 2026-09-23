package io.github.maxlyth.hapaneld.http

import io.github.maxlyth.hapaneld.control.TameDesiredStateReconciler
import io.github.maxlyth.hapaneld.control.TamePackageObservation
import io.github.maxlyth.hapaneld.control.TamePackagePresence
import io.github.maxlyth.hapaneld.control.TamePackageSafety
import io.github.maxlyth.hapaneld.control.TameStatePolicy
import io.github.maxlyth.hapaneld.persistence.SqliteStatePreferences
import io.github.maxlyth.hapaneld.persistence.StateMutation
import io.github.maxlyth.hapaneld.persistence.StateNamespacePersistence
import io.github.maxlyth.hapaneld.persistence.commitWithDurableVisibility
import io.github.maxlyth.hapaneld.util.LatestDispatcher
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TameDurableOwnershipCompositionTest {
    @Test fun `shutdown finishes active package without interruption and new owner resumes durable work`() {
        for (restoring in listOf(false, true)) for (firstSucceeds in listOf(false, true)) {
            val first = "com.vendor.one"
            val second = "com.vendor.two"
            val firstKey = TameStatePolicy.markerKey(first)
            val secondKey = TameStatePolicy.markerKey(second)
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val interrupted = AtomicBoolean()
            val active = AtomicBoolean(true)
            val actions = AtomicInteger()
            val converged = CountDownLatch(1)
            val initial = if (restoring) mapOf(firstKey to "foreground", secondKey to "deny") else emptyMap()
            val persistence = FailOncePersistence(initial, CountDownLatch(0), failuresRemaining = 0)
            val writer = Executors.newSingleThreadExecutor()
            val state = SqliteStatePreferences(persistence, writer)
            val desired = if (restoring) emptySet() else setOf(first, second)
            fun mutate(pkg: String): Boolean {
                actions.incrementAndGet()
                if (active.get() && pkg == first) {
                    // The real policy commits this restoration mode before entering the actuator.
                    assertEquals("foreground", persistence.snapshot()[firstKey])
                    entered.countDown()
                    try {
                        check(release.await(10, TimeUnit.SECONDS))
                    } catch (error: InterruptedException) {
                        interrupted.set(true)
                        throw error
                    }
                    return firstSucceeds
                }
                return true
            }
            val reconciler = TameDesiredStateReconciler(
                readOwned = { TameStatePolicy.parseOwnedMarkers(state.all) },
                observePackages = ::presentAndSafe,
                reassert = { pkg ->
                    val key = TameStatePolicy.markerKey(pkg)
                    TameStatePolicy.reassertOwnership(
                        markerExists = state.contains(key),
                        markerMode = state.getString(key, null),
                        captureMode = { "foreground" },
                        persistMarker = { mode -> state.commitWithDurableVisibility { putString(key, mode) } },
                        mutate = { mutate(pkg) },
                    )
                },
                restore = { marker ->
                    TameStatePolicy.restoreOwnership(
                        markerMode = marker.overlayMode,
                        enable = { mutate(marker.pkg) },
                        restoreOverlay = { it == marker.overlayMode },
                        removeMarker = { state.commitWithDurableVisibility { remove(TameStatePolicy.markerKey(marker.pkg)) } },
                    )
                },
                clearAbsent = { error("fixture packages are present") },
            )
            val owner = TameReconcileAuthority(
                readDesired = { desired },
                reconcile = reconciler::reconcile,
                stopping = { false },
            )
            var restarted: TameReconcileAuthority? = null
            try {
                owner.request()
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                owner.request() // This pending wake must never run on the closing owner.
                assertFalse("an active package cannot prove quiescence", owner.closeAndJoin(10))
                assertFalse("close must not interrupt a package transaction", interrupted.get())
                assertEquals(1, actions.get())
                assertEquals("foreground", persistence.snapshot()[firstKey])

                release.countDown()
                assertTrue(owner.closeAndJoin(2_000))
                assertFalse(interrupted.get())
                assertEquals("neither the rest of the pass nor the queued wake ran", 1, actions.get())
                assertEquals(LatestDispatcher.Admission.CLOSED, owner.request())
                if (restoring) {
                    assertEquals(if (firstSucceeds) null else "foreground", persistence.snapshot()[firstKey])
                    assertEquals("deny", persistence.snapshot()[secondKey])
                } else {
                    assertEquals("foreground", persistence.snapshot()[firstKey])
                    assertFalse(secondKey in persistence.snapshot())
                }

                active.set(false)
                restarted = TameReconcileAuthority(
                    readDesired = { desired },
                    reconcile = { value, stopping ->
                        reconciler.reconcile(value, stopping).also { converged.countDown() }
                    },
                    stopping = { false },
                )
                restarted.request()
                assertTrue(converged.await(5, TimeUnit.SECONDS))
                assertTrue(restarted.closeAndJoin(2_000))
                if (restoring) {
                    assertTrue(persistence.snapshot().isEmpty())
                    assertEquals(if (firstSucceeds) 2 else 3, actions.get())
                } else {
                    assertEquals(mapOf(firstKey to "foreground", secondKey to "foreground"), persistence.snapshot())
                    assertEquals(3, actions.get())
                }
            } finally {
                release.countDown()
                owner.closeAndJoin(2_000)
                restarted?.closeAndJoin(2_000)
                writer.shutdownNow()
            }
        }
    }

    @Test fun `failed backend marker creation stays invisible and automatic retry precedes mutation`() {
        val failedWrite = CountDownLatch(1)
        val retryPaused = CountDownLatch(1)
        val releaseRetry = CountDownLatch(1)
        val mutated = CountDownLatch(1)
        val persistence = FailOncePersistence(emptyMap(), failedWrite)
        val writer = Executors.newSingleThreadExecutor()
        val state = SqliteStatePreferences(persistence, writer)
        val pkg = "com.vendor.one"
        val key = TameStatePolicy.markerKey(pkg)
        val externalMutations = AtomicInteger()
        val reconciler = TameDesiredStateReconciler(
            readOwned = { TameStatePolicy.parseOwnedMarkers(state.all) },
            observePackages = ::presentAndSafe,
            reassert = {
                val markerExists = state.contains(key)
                TameStatePolicy.reassertOwnership(
                    markerExists = markerExists,
                    markerMode = state.getString(key, null),
                    captureMode = { "allow" },
                    persistMarker = { mode ->
                        state.commitWithDurableVisibility { putString(key, mode) }
                    },
                    mutate = {
                        assertEquals("allow", persistence.snapshot()[key])
                        externalMutations.incrementAndGet()
                        mutated.countDown()
                        true
                    },
                )
            },
            restore = { false },
            clearAbsent = { false },
        )
        val owner = TameReconcileAuthority(
            readDesired = { setOf(pkg) },
            reconcile = { desired, stopping ->
                reconciler.reconcile(desired, stopping).also { result ->
                    if (result.retryableFailure) {
                        retryPaused.countDown()
                        releaseRetry.await(10, TimeUnit.SECONDS)
                    }
                }
            },
            stopping = { false },
            retryDelayMs = 1,
        )
        try {
            owner.request()
            assertTrue(failedWrite.await(5, TimeUnit.SECONDS))
            assertTrue(retryPaused.await(5, TimeUnit.SECONDS))
            assertFalse(state.contains(key))
            assertFalse(key in persistence.snapshot())
            assertEquals(0, externalMutations.get())

            releaseRetry.countDown()
            assertTrue(mutated.await(5, TimeUnit.SECONDS))
            assertTrue(owner.closeAndJoin(2_000))
            assertEquals("allow", state.getString(key, null))
            assertEquals("allow", persistence.snapshot()[key])
            assertEquals(1, externalMutations.get())
        } finally {
            releaseRetry.countDown()
            owner.closeAndJoin(2_000)
            writer.shutdownNow()
        }
    }

    @Test fun `failed backend marker removal stays visible and automatic retry restores again`() {
        val pkg = "com.vendor.one"
        val key = TameStatePolicy.markerKey(pkg)
        val failedWrite = CountDownLatch(1)
        val retryPaused = CountDownLatch(1)
        val releaseRetry = CountDownLatch(1)
        val restored = CountDownLatch(1)
        val persistence = FailOncePersistence(mapOf(key to "foreground"), failedWrite)
        val writer = Executors.newSingleThreadExecutor()
        val state = SqliteStatePreferences(persistence, writer)
        val restoreAttempts = AtomicInteger()
        val reconciler = TameDesiredStateReconciler(
            readOwned = { TameStatePolicy.parseOwnedMarkers(state.all) },
            observePackages = ::presentAndSafe,
            reassert = { false },
            restore = { marker ->
                restoreAttempts.incrementAndGet()
                TameStatePolicy.restoreOwnership(
                    markerMode = marker.overlayMode,
                    enable = { true },
                    restoreOverlay = { it == "foreground" },
                    removeMarker = {
                        state.commitWithDurableVisibility { remove(key) }.also { removed ->
                            if (removed) restored.countDown()
                        }
                    },
                )
            },
            clearAbsent = { false },
        )
        val owner = TameReconcileAuthority(
            readDesired = { emptySet() },
            reconcile = { desired, stopping ->
                reconciler.reconcile(desired, stopping).also { result ->
                    if (result.retryableFailure) {
                        retryPaused.countDown()
                        releaseRetry.await(10, TimeUnit.SECONDS)
                    }
                }
            },
            stopping = { false },
            retryDelayMs = 1,
        )
        try {
            owner.request()
            assertTrue(failedWrite.await(5, TimeUnit.SECONDS))
            assertTrue(retryPaused.await(5, TimeUnit.SECONDS))
            assertEquals("foreground", state.getString(key, null))
            assertEquals("foreground", persistence.snapshot()[key])
            assertEquals(1, restoreAttempts.get())

            releaseRetry.countDown()
            assertTrue(restored.await(5, TimeUnit.SECONDS))
            assertTrue(owner.closeAndJoin(2_000))
            assertFalse(state.contains(key))
            assertFalse(key in persistence.snapshot())
            assertEquals(2, restoreAttempts.get())
        } finally {
            releaseRetry.countDown()
            owner.closeAndJoin(2_000)
            writer.shutdownNow()
        }
    }

    private fun presentAndSafe(packages: Set<String>): Map<String, TamePackageObservation> =
        packages.associateWith {
            TamePackageObservation(TamePackagePresence.PRESENT, TamePackageSafety.SAFE)
        }

    private class FailOncePersistence(
        initial: Map<String, Any>,
        private val failedWrite: CountDownLatch,
        private var failuresRemaining: Int = 1,
    ) : StateNamespacePersistence {
        private val values = initial.toMutableMap()

        @Synchronized
        override fun initialize(): Map<String, Any> = values.toMap()

        @Synchronized
        override fun persist(mutation: StateMutation): Boolean = write {
            if (mutation.clear) values.clear()
            mutation.changes.forEach { (key, value) ->
                if (value == null) values.remove(key) else values[key] = value
            }
        }

        @Synchronized
        override fun replace(snapshot: Map<String, Any>): Boolean = write {
            values.clear()
            values.putAll(snapshot)
        }

        @Synchronized
        fun snapshot(): Map<String, Any> = values.toMap()

        private fun write(mutation: () -> Unit): Boolean {
            if (failuresRemaining > 0) {
                failuresRemaining--
                failedWrite.countDown()
                return false
            }
            mutation()
            return true
        }
    }
}
