package io.panelassistant.android.camera

import io.panelassistant.android.camera.CameraSessionPolicy.CloseReason
import io.panelassistant.android.camera.CameraSessionPolicy.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The watchdog's decisions, driven without Android. The ordering assertions are the privacy contract:
 * a stop or the master switch closes a session before any retry ladder is consulted.
 */
class CameraSessionPolicyTest {

    private val policy = CameraSessionPolicy(frameIntervalMs = 66L)

    private fun tick(
        nowMs: Long = 10_000L,
        openedAtMs: Long = 0L,
        lastFrameAtMs: Long? = 9_950L,
        clients: Int = 1,
        enabled: Boolean = true,
        stopping: Boolean = false,
        deviceFault: CameraFault? = null,
        consecutiveFailures: Int = 0,
    ) = CameraSessionPolicy.Tick(nowMs, openedAtMs, lastFrameAtMs, clients, enabled, stopping, deviceFault, consecutiveFailures)

    @Test fun aHealthyWatchedSessionContinues() {
        assertEquals(Decision.Continue, policy.onTick(tick()))
    }

    @Test fun theMasterSwitchClosesEvenWhileTheDeviceIsFaulting() {
        val decision = policy.onTick(tick(enabled = false, deviceFault = CameraFault.DEVICE_ERROR))
        assertEquals(Decision.Close(CloseReason.DISABLED), decision)
    }

    @Test fun stoppingOutranksTheMasterSwitch() {
        assertEquals(Decision.Close(CloseReason.STOPPING), policy.onTick(tick(stopping = true, enabled = false)))
    }

    @Test fun nobodyWatchingClosesBeforeAFaultIsConsidered() {
        assertEquals(Decision.Close(CloseReason.IDLE), policy.onTick(tick(clients = 0, deviceFault = CameraFault.DISCONNECTED)))
    }

    @Test fun aFreshSessionIsGivenTheOpenGraceBeforeStarvationCounts() {
        val within = tick(nowMs = 4_000L, openedAtMs = 0L, lastFrameAtMs = null)
        assertEquals(Decision.Continue, policy.onTick(within))
        val beyond = tick(nowMs = 5_001L, openedAtMs = 0L, lastFrameAtMs = null)
        val decision = policy.onTick(beyond)
        assertTrue("$decision", decision is Decision.Reopen && decision.fault == CameraFault.STARVED)
    }

    @Test fun starvationIsAMultipleOfTheFrameIntervalWithAFloor() {
        assertEquals(2_000L, policy.starvationMs)
        val slow = CameraSessionPolicy(frameIntervalMs = 1_000L)
        assertEquals(4_000L, slow.starvationMs)
        val stalled = slow.onTick(tick(nowMs = 10_000L, lastFrameAtMs = 5_999L))
        assertTrue("$stalled", stalled is Decision.Reopen)
        assertEquals(Decision.Continue, slow.onTick(tick(nowMs = 10_000L, lastFrameAtMs = 6_001L)))
    }

    @Test fun theLadderReopensWithBoundedBackoffThenDegrades() {
        val first = policy.onFailure(CameraFault.OPEN, attempt = 1)
        assertEquals(Decision.Reopen(1_000L, CameraFault.OPEN, 1), first)
        val second = policy.onFailure(CameraFault.OPEN, attempt = 2)
        assertEquals(Decision.Reopen(2_000L, CameraFault.OPEN, 2), second)
        val ceiling = policy.onFailure(CameraFault.OPEN, attempt = 3)
        assertEquals(Decision.Degrade(CameraFault.OPEN, 3), ceiling)
    }

    @Test fun backoffNeverExceedsTheCapEvenForAbsurdAttempts() {
        assertEquals(30_000L, policy.backoffMs(6))
        assertEquals(30_000L, policy.backoffMs(200))
        assertEquals(1_000L, policy.backoffMs(0))
    }

    @Test fun aDeviceFaultOnTickCountsFromTheRecordedFailures() {
        val decision = policy.onTick(tick(deviceFault = CameraFault.DISCONNECTED, consecutiveFailures = 2))
        assertEquals(Decision.Degrade(CameraFault.DISCONNECTED, 3), decision)
    }
}
