package io.panelassistant.android.persistence

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceShutdownPersistenceTest {
    @Test fun shutdownFreezeRejectsNewWritesAndOrdinaryReleaseReopensAdmission() {
        val admission = StateMutationAdmission()
        val freeze = admission.freezeRejecting()

        assertTrue(freeze != null)
        assertNull(admission.admit { "mutated" })

        freeze!!.close()
        assertEquals("accepted", admission.admit { "accepted" })
    }

    @Test fun shutdownFreezeRestoresAnExistingSelfReplaceHold() {
        val admission = StateMutationAdmission()
        val entered = CountDownLatch(1)
        val caller = Executors.newSingleThreadExecutor()
        try {
            assertTrue(admission.freeze())
            val shutdown = admission.freezeRejecting()
            assertNull(admission.admit { "rejected" })
            shutdown!!.close()

            val deferred = caller.submit { admission.admit { entered.countDown() } }
            assertFalse(entered.await(100, TimeUnit.MILLISECONDS))
            admission.unfreeze()
            deferred.get(1, TimeUnit.SECONDS)
            assertTrue(entered.await(1, TimeUnit.SECONDS))
        } finally {
            admission.unfreeze()
            caller.shutdownNow()
        }
    }

    @Test fun selfReplaceReleaseCannotOpenAdmissionWhileShutdownStillOwnsIt() {
        val admission = StateMutationAdmission()
        assertTrue(admission.freeze())
        val shutdown = admission.freezeRejecting()

        admission.unfreeze()
        assertNull(admission.admit { "must remain rejected" })

        shutdown!!.close()
        assertEquals("accepted", admission.admit { "accepted" })
    }

    @Test fun checkpointMustBeCompleteAndDatabaseStable() {
        assertTrue(cleanCheckpointAccepted(0, 0, 8192, 8192, 0))
        assertFalse(cleanCheckpointAccepted(1, 0, 8192, 8192, 0))
        assertFalse(cleanCheckpointAccepted(0, 4096, 8192, 8192, 0))
        assertFalse(cleanCheckpointAccepted(0, 0, 8192, 12288, 0))
        assertFalse(cleanCheckpointAccepted(0, 0, 8192, 8192, 4096))
    }

    @Test fun shutdownRejectsCachedWritesButDefersOneUncachedInitializationUntilRelease() {
        val admission = StateMutationAdmission()
        val cache = AtomicFactoryCache<String, String>()
        val callersStarted = CountDownLatch(2)
        val initializerRuns = AtomicInteger()
        val callers = Executors.newFixedThreadPool(2)
        val freeze = admission.freezeRejecting()!!
        try {
            assertNull(admission.admit { "cached write" })
            val results = List(2) {
                callers.submit<String> {
                    callersStarted.countDown()
                    admission.initializeWhenOpen {
                        cache.getOrCreate("uncached") {
                            initializerRuns.incrementAndGet()
                            "initialized"
                        }
                    }
                }
            }
            assertTrue(callersStarted.await(1, TimeUnit.SECONDS))
            Thread.sleep(100)
            assertEquals(0, initializerRuns.get())

            freeze.close()
            assertEquals(listOf("initialized", "initialized"), results.map { it.get(1, TimeUnit.SECONDS) })
            assertEquals(1, initializerRuns.get())
        } finally {
            freeze.close()
            callers.shutdownNow()
        }
    }

    @Test fun shutdownProofBudgetRoundsUpRemainingTimeAndExpires() {
        var now = 1_000_000L
        val budget = ShutdownProofBudget(timeoutMs = 10) { now }

        assertEquals(10L, budget.remainingMs())
        now += 9_500_000L
        assertEquals(1L, budget.remainingMs())
        assertTrue(budget.hasTime())
        now += 500_000L
        assertEquals(0L, budget.remainingMs())
        assertFalse(budget.hasTime())
    }

    @Test fun databaseDigestFailsWhenSharedShutdownBudgetExpires() {
        val database = File.createTempFile("ha-paneld-proof-", ".db")
        try {
            database.writeBytes(ByteArray(16 * 1024) { it.toByte() })
            var clockReads = 0
            val budget = ShutdownProofBudget(timeoutMs = 1) {
                if (clockReads++ < 2) 0L else 2_000_000L
            }

            assertNull(sha256WithinBudget(database, budget))
        } finally {
            database.delete()
        }
    }
}
