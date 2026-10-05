package io.panelassistant.android.http

import io.panelassistant.android.restoreCompanionLaunchSuppression
import io.panelassistant.android.control.CompanionDataOperationGate
import io.panelassistant.android.control.CompanionDataOperationState
import io.panelassistant.android.util.CompanionOperationStatus
import io.panelassistant.android.util.DurableRecoveryMarker
import java.nio.file.Files
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CompanionLeaseRetentionTest {
    private val companionPackage = "io.homeassistant.companion.android"

    @Test
    fun markedProcessRestartUnavailableThenBusyThenIdleRetainsAndClearsSuppression() = runTest {
        val (operationState, directory) = armedOperationState()
        var retainedLease: CompanionDataOperationGate.Lease? = null

        assertEquals(
            CompanionOperationStatus.UNAVAILABLE,
            restoreCompanionLaunchSuppression(
                packageName = companionPackage,
                operationState = operationState,
                operationStatus = { CompanionOperationStatus.UNAVAILABLE },
                retain = { retainedLease = it },
            ),
        )
        assertTrue(CompanionDataOperationGate.blocks(companionPackage))

        val statuses = ArrayDeque(
            listOf(CompanionOperationStatus.BUSY, CompanionOperationStatus.IDLE),
        )
        var releases = 0
        val retention = launch {
            retainCompanionLeaseUntilHelperIdle(
                lease = requireNotNull(retainedLease),
                operationState = operationState,
                afterRelease = { releases++ },
                operationStatus = { statuses.removeFirst() },
                pollMs = 1_000L,
            )
        }
        runCurrent()
        assertTrue(CompanionDataOperationGate.blocks(companionPackage))
        assertTrue(operationState.isPending())

        advanceTimeBy(1_000L)
        runCurrent()
        retention.join()

        assertFalse(CompanionDataOperationGate.blocks(companionPackage))
        assertFalse(operationState.isPending())
        assertEquals(1, releases)
        assertTrue(statuses.isEmpty())
        directory.deleteRecursively()
    }

    @Test
    fun unavailableThreeTimesThenBusyKeepsLaunchSuppressedUntilIdle() = runTest {
        val statuses = ArrayDeque(
            listOf(
                CompanionOperationStatus.UNAVAILABLE,
                CompanionOperationStatus.UNAVAILABLE,
                CompanionOperationStatus.UNAVAILABLE,
                CompanionOperationStatus.BUSY,
                CompanionOperationStatus.IDLE,
            ),
        )
        val lease = requireNotNull(CompanionDataOperationGate.acquire(companionPackage))
        val (operationState, directory) = armedOperationState()
        var releases = 0
        val retention = launch {
            retainCompanionLeaseUntilHelperIdle(
                lease = lease,
                operationState = operationState,
                afterRelease = { releases++ },
                operationStatus = { statuses.removeFirst() },
                pollMs = 1_000L,
            )
        }

        runCurrent()
        repeat(2) {
            assertTrue(CompanionDataOperationGate.blocks(companionPackage))
            assertEquals(0, releases)
            advanceTimeBy(1_000L)
            runCurrent()
        }

        // The former heuristic released here after the third unavailable probe.
        assertTrue(CompanionDataOperationGate.blocks(companionPackage))
        assertEquals(0, releases)
        advanceTimeBy(1_000L)
        runCurrent()

        assertTrue(CompanionDataOperationGate.blocks(companionPackage))
        assertEquals(0, releases)
        advanceTimeBy(1_000L)
        runCurrent()

        assertFalse(CompanionDataOperationGate.blocks(companionPackage))
        assertEquals(1, releases)
        assertTrue(statuses.isEmpty())
        assertFalse(operationState.isPending())
        retention.join()
        directory.deleteRecursively()
    }

    @Test
    fun legacyDaemonCannotClearMarkerLeftByANewerHelper() = runTest {
        val statuses = ArrayDeque(
            listOf(
                CompanionOperationStatus.UNAVAILABLE,
                CompanionOperationStatus.UNSUPPORTED,
                CompanionOperationStatus.IDLE,
            ),
        )
        val lease = requireNotNull(CompanionDataOperationGate.acquire(companionPackage))
        val (operationState, directory) = armedOperationState()
        var releases = 0
        val retention = launch {
            retainCompanionLeaseUntilHelperIdle(
                lease = lease,
                operationState = operationState,
                afterRelease = { releases++ },
                operationStatus = { statuses.removeFirst() },
                pollMs = 1_000L,
            )
        }

        runCurrent()
        assertTrue(CompanionDataOperationGate.blocks(companionPackage))
        assertEquals(0, releases)

        advanceTimeBy(1_000L)
        runCurrent()

        assertTrue(CompanionDataOperationGate.blocks(companionPackage))
        assertEquals(0, releases)
        assertTrue(operationState.isPending())

        advanceTimeBy(1_000L)
        runCurrent()

        assertFalse(CompanionDataOperationGate.blocks(companionPackage))
        assertEquals(1, releases)
        assertTrue(statuses.isEmpty())
        assertFalse(operationState.isPending())
        retention.join()
        directory.deleteRecursively()
    }

    private fun armedOperationState(): Pair<CompanionDataOperationState, java.io.File> {
        val directory = Files.createTempDirectory("companion-operation-test").toFile()
        val state = CompanionDataOperationState.forTest(
            DurableRecoveryMarker(directory.resolve("pending")),
        )
        assertTrue(state.arm())
        return state to directory
    }
}
