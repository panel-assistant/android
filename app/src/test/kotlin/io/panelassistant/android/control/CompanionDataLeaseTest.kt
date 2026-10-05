package io.panelassistant.android.control

import io.panelassistant.android.util.DurableRecoveryMarker
import io.panelassistant.android.util.RecoveryMarkerPersistence
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class CompanionDataLeaseTest {
    private val companionPackage = "io.homeassistant.companion.android"

    @Test fun acquireArmedClaimsGateAndArmsMarker() {
        val (state, directory) = operationState()
        try {
            val acquisition = CompanionDataLease.acquireArmed(companionPackage, state) { _, _ -> }
            assertTrue(acquisition is CompanionDataLease.Acquisition.Acquired)
            assertTrue(CompanionDataOperationGate.blocks(companionPackage))
            assertTrue(state.isPending())
            (acquisition as CompanionDataLease.Acquisition.Acquired).lease.settle(possiblyInFlight = false) {}
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun acquireArmedReportsGateBusyWhenAlreadyHeld() {
        val (state, directory) = operationState()
        val held = requireNotNull(CompanionDataOperationGate.acquire(companionPackage))
        try {
            val acquisition = CompanionDataLease.acquireArmed(companionPackage, state) { _, _ -> }
            assertTrue(acquisition is CompanionDataLease.Acquisition.GateBusy)
            // A gate-busy acquisition must not have armed the marker.
            assertFalse(state.isPending())
        } finally {
            held.close()
            directory.deleteRecursively()
        }
    }

    @Test fun acquireArmedRollsBackGateWhenMarkerCannotBeArmed() {
        val state = CompanionDataOperationState.forTest(
            DurableRecoveryMarker(File("/pending"), FailingArmPersistence),
        )
        val acquisition = CompanionDataLease.acquireArmed(companionPackage, state) { _, _ -> }
        assertTrue(acquisition is CompanionDataLease.Acquisition.MarkerFailed)
        // The gate was released so a later operation can still acquire it.
        assertFalse(CompanionDataOperationGate.blocks(companionPackage))
        CompanionDataOperationGate.acquire(companionPackage)!!.close()
    }

    @Test fun settleAffirmativeTerminalOutcomeClearsMarkerAndClosesGate() {
        val (state, directory) = operationState()
        val gateLease = requireNotNull(CompanionDataOperationGate.acquire(companionPackage))
        assertTrue(state.arm())
        var retained = false
        var released = false
        val lease = CompanionDataLease.forTest(gateLease, state) { _, _ -> retained = true }
        try {
            lease.settle(possiblyInFlight = false) { released = true }

            assertFalse(retained)
            assertTrue(released)
            assertFalse(state.isPending())
            assertFalse(CompanionDataOperationGate.blocks(companionPackage))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun settlePossiblyInFlightTransfersOwnershipToRetention() {
        val (state, directory) = operationState()
        val gateLease = requireNotNull(CompanionDataOperationGate.acquire(companionPackage))
        assertTrue(state.arm())
        var retained = 0
        var released = false
        val lease = CompanionDataLease.forTest(gateLease, state) { held, _ -> retained++; held.close() }
        try {
            lease.settle(possiblyInFlight = true) { released = true }

            assertEquals(1, retained)
            // Retention owns the afterRelease callback; the synchronous path did not run it.
            assertFalse(released)
            // Ownership was handed to retention rather than cleared synchronously.
            assertTrue(state.isPending())
        } finally {
            state.clear()
            directory.deleteRecursively()
        }
    }

    @Test fun settleIsIdempotentSoAFinallySettleCannotDoubleRelease() {
        val (state, directory) = operationState()
        val gateLease = requireNotNull(CompanionDataOperationGate.acquire(companionPackage))
        assertTrue(state.arm())
        var retained = 0
        var synchronousReleases = 0
        val lease = CompanionDataLease.forTest(gateLease, state) { held, _ -> retained++; held.close() }
        try {
            lease.settle(possiblyInFlight = true) {}
            // Mirrors the call sites' `finally` settle after an early in-flight transfer: it must no-op.
            lease.settle(possiblyInFlight = false) { synchronousReleases++ }

            assertEquals(1, retained)
            assertEquals(0, synchronousReleases)
        } finally {
            state.clear()
            directory.deleteRecursively()
        }
    }

    private fun operationState(): Pair<CompanionDataOperationState, File> {
        val directory = Files.createTempDirectory("companion-data-lease-test").toFile()
        val state = CompanionDataOperationState.forTest(
            DurableRecoveryMarker(directory.resolve("pending")),
        )
        return state to directory
    }

    private object FailingArmPersistence : RecoveryMarkerPersistence {
        override fun isFile(file: File): Boolean = false
        override fun createDirectories(directory: File) = Unit
        override fun writeAndSync(file: File, contents: ByteArray) = throw java.io.IOException("arm failed")
        override fun replaceAtomically(source: File, target: File) = Unit
        override fun syncDirectory(directory: File) = Unit
        override fun delete(file: File): Boolean = true
    }
}
