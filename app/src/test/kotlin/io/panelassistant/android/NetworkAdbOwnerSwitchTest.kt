package io.panelassistant.android

import io.panelassistant.android.control.FakeRootShell
import io.panelassistant.android.control.ManagedNetworkAdb
import io.panelassistant.android.control.directNetworkAdbOnRefusalJson
import io.panelassistant.android.control.takeNetworkAdbBackForOwner
import io.panelassistant.android.panelassistant.LocalAdbEndpoint
import io.panelassistant.android.panelassistant.LocalAdbRuntime
import io.panelassistant.android.panelassistant.LocalAdbState
import io.panelassistant.android.testsupport.PanelRuntimeRig
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * A panel that loses its Home Assistant session keeps network ADB closed: nothing reopens by itself because
 * something cut the panel's path to Home Assistant. Its owner is not locked out for good, though. Once no
 * session has been held for the bound, the Network ADB switch on the panel's own settings page takes ADB
 * back, and the lease stays away until the next accepted session arms a new one.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class NetworkAdbOwnerSwitchTest : PanelRuntimeRig() {
    private val bound = ManagedNetworkAdb.OWNER_SWITCH_AFTER_MS

    @Test fun theSwitchStaysRefusedWhileTheSessionIsUpAndUntilTheBoundAfterItIsLost() = lease { rig, endpoint, clock ->
        assertNull("a live session leaves no way round the lease", ManagedNetworkAdb.ownerSwitchWaitMs())
        assertNotEquals(LiveSettingRequestOutcome.APPLIED, turnOn(rig))
        assertEquals(LocalAdbState(true, false), endpoint.state())

        runtime.sessionEnded()
        assertEquals(bound, ManagedNetworkAdb.ownerSwitchWaitMs())
        clock.now += bound - 1
        assertEquals(1L, ManagedNetworkAdb.ownerSwitchWaitMs())
        assertNotEquals(LiveSettingRequestOutcome.APPLIED, turnOn(rig))
        // The lease owner refuses by itself too, whatever a caller believes about the bound.
        assertFalse(ManagedNetworkAdb.releaseToOwner())
        assertEquals("the lease still holds one millisecond short of the bound", LocalAdbState(true, false), endpoint.state())
        assertFalse(endpoint.listening)
        assertFalse(rig.config.networkAdbEnabled)

        // Losing the session alone reopens nothing: the renewals go on.
        val renewals = endpoint.beats
        advanceTimeBy(95_000)
        assertEquals(renewals + 3, endpoint.beats)
        assertFalse(endpoint.listening)
    }

    @Test fun afterTheBoundTheSwitchTakesAdbBackAndTheLeaseStaysAwayUntilTheNextSession() = lease { rig, endpoint, clock ->
        runtime.sessionEnded()
        clock.now += bound
        assertEquals(0L, ManagedNetworkAdb.ownerSwitchWaitMs())

        assertEquals(LiveSettingRequestOutcome.APPLIED, turnOn(rig))
        assertEquals("the helper reopened ADB and holds off re-arming", LocalAdbState(false, true), endpoint.state())
        assertTrue(endpoint.listening)
        assertTrue("the owner's intent is saved", rig.config.networkAdbEnabled)
        assertFalse("the hold is the owner's, not Panel Assistant's", ManagedNetworkAdb.ownsRuntime())

        // Nothing closes it again half a minute later: no renewal, no new arm, for as long as no session comes.
        val (arms, beats) = endpoint.arms to endpoint.beats
        advanceTimeBy(10 * 60_000L)
        assertEquals(arms to beats, endpoint.arms to endpoint.beats)
        assertTrue(endpoint.listening)

        // The next accepted session arms a new lease; the saved intent waits for management to end.
        runtime.session("again", true)
        runCurrent()
        assertEquals(LocalAdbState(true, false), endpoint.state())
        assertFalse(endpoint.listening)
        assertTrue(ManagedNetworkAdb.managed)
        assertNull(ManagedNetworkAdb.ownerSwitchWaitMs())
        assertTrue(rig.config.networkAdbEnabled)
    }

    @Test fun anOnThatIsNotTheOwnersPressNeverGivesTheLeaseBack() = lease { rig, endpoint, clock ->
        runtime.sessionEnded()
        clock.now += 10 * bound
        assertEquals("the owner's switch would work now", 0L, ManagedNetworkAdb.ownerSwitchWaitMs())

        // An ON an older version queued, or one a restore carries, reaches the apply path with nobody at
        // the door. It stays refused, however often it is tried, and Panel Assistant's own command too.
        val authority = LiveSettingAuthority(setOf("network_adb"), bootIdentity = { "test-boot" })
        repeat(2) { assertNotEquals(LiveSettingRequestOutcome.APPLIED, applyOn(rig, authority)) }
        assertNotEquals(io.panelassistant.android.panelassistant.PanelAssistantCommandResult.Applied, submitNative(rig, "network_adb", "on"))
        assertEquals(LocalAdbState(true, false), endpoint.state())
        assertFalse(endpoint.listening)
        assertFalse(rig.config.networkAdbEnabled)
        val renewals = endpoint.beats
        advanceTimeBy(65_000)
        assertEquals("the lease is still renewed", renewals + 2, endpoint.beats)
    }

    @Test fun aHelperThatKeepsTheLeaseLeavesEverythingAsItWasAndStillRenewed() = lease { rig, endpoint, clock ->
        runtime.sessionEnded()
        clock.now += bound
        endpoint.recovery = Recovery.REFUSED
        assertEquals(LiveSettingRequestOutcome.REJECTED, turnOn(rig))
        assertEquals(LocalAdbState(true, false), endpoint.state())
        assertFalse(endpoint.listening)
        assertTrue(ManagedNetworkAdb.managed)
        assertFalse(rig.config.networkAdbEnabled)
        // The press changed nothing, so the lease is renewed as before and ADB does not reopen by itself.
        val renewals = endpoint.beats
        advanceTimeBy(65_000)
        assertEquals(renewals + 2, endpoint.beats)
        // The owner can press again.
        endpoint.recovery = Recovery.REOPENS
        assertEquals(LiveSettingRequestOutcome.APPLIED, turnOn(rig))
        assertTrue(endpoint.listening)
    }

    @Test fun aReopeningWhoseReplyIsLostIsStillARelease() = lease { rig, endpoint, clock ->
        runtime.sessionEnded()
        clock.now += bound
        endpoint.recovery = Recovery.REOPENS_THEN_FAILS
        assertEquals("ADB is open, so the press is not answered with a refusal", LiveSettingRequestOutcome.APPLIED, turnOn(rig))
        assertEquals(LocalAdbState(false, true), endpoint.state())
        assertFalse(ManagedNetworkAdb.managed)
        val (arms, beats) = endpoint.arms to endpoint.beats
        advanceTimeBy(95_000)
        assertEquals(arms to beats, endpoint.arms to endpoint.beats)
        assertTrue(endpoint.listening)
    }

    @Test fun aSessionAcceptedWhileTheHelperReopensStillArmsAndIsRenewed() = lease { rig, endpoint, clock ->
        runtime.sessionEnded()
        clock.now += bound
        // Home Assistant comes back at the worst moment: after the helper was asked, before it answered.
        endpoint.whileRecovering = { runtime.session("returned", true) }
        assertEquals("the press is not a release: the panel is managed again", LiveSettingRequestOutcome.REJECTED, turnOn(rig))
        runCurrent()
        assertEquals("the returned session armed its own lease", LocalAdbState(true, false), endpoint.state())
        assertEquals(2, endpoint.arms)
        assertFalse(endpoint.listening)
        assertTrue(ManagedNetworkAdb.managed)
        assertFalse(ManagedNetworkAdb.releasedToOwner)
        assertNull(ManagedNetworkAdb.ownerSwitchWaitMs())
        assertFalse(rig.config.networkAdbEnabled)
        val renewals = endpoint.beats
        advanceTimeBy(65_000)
        assertEquals("and it is renewed", renewals + 2, endpoint.beats)
        assertFalse(endpoint.listening)
    }

    @Test fun aSessionThatReturnsBeforeTheBoundStartsTheCountAgain() = lease { rig, endpoint, clock ->
        runtime.sessionEnded()
        clock.now += bound - 1_000
        runtime.session("back", true)
        runCurrent()
        assertNull(ManagedNetworkAdb.ownerSwitchWaitMs())
        runtime.sessionEnded()
        clock.now += 2_000
        assertEquals(bound - 2_000, ManagedNetworkAdb.ownerSwitchWaitMs())
        assertNotEquals(LiveSettingRequestOutcome.APPLIED, turnOn(rig))
        assertEquals(LocalAdbState(true, false), endpoint.state())
    }

    @Test fun hardenedModeKeepsTheLeaseHoweverLongTheSessionHasBeenLost() = lease(hardened = true) { _, endpoint, clock ->
        runtime.sessionEnded()
        clock.now += 10 * bound
        assertNull(ManagedNetworkAdb.ownerSwitchWaitMs())
        assertFalse(ManagedNetworkAdb.releaseToOwner())
        assertEquals(LocalAdbState(true, false), endpoint.state())
        assertFalse(endpoint.listening)
    }

    private class Clock { var now = 1_000_000L }
    private lateinit var runtime: LocalAdbRuntime

    private fun lease(hardened: Boolean = false, body: suspend TestScope.(Rig, LeaseEndpoint, Clock) -> Unit) = runTest {
        val endpoint = LeaseEndpoint()
        val root = FakeRootShell(available = true, runResult = true, onRun = { command ->
            if (command.contains("setprop")) endpoint.listening = true
        })
        val rig = rig(adbRoot = root, configure = { assertTrue(it.setSecurityMode(Config.SecurityMode.RELAXED)) })
        val clock = Clock()
        runtime = LocalAdbRuntime(
            backgroundScope, endpoint, { hardened }, { JSONObject() }, { false },
            StandardTestDispatcher(testScheduler), monotonicMs = { clock.now },
        )
        // Production installs the runtime as the switch's lease owner where it is created.
        ManagedNetworkAdb.ownerSwitch = runtime
        try {
            runtime.session("panel-assistant", true)
            runCurrent()
            assertEquals(LocalAdbState(true, false), endpoint.state())
            assertTrue(ManagedNetworkAdb.managed)
            body(rig, endpoint, clock)
        } finally {
            rig.close()
            ManagedNetworkAdb.managed = false
            ManagedNetworkAdb.recoveryHold = false
            ManagedNetworkAdb.releasedToOwner = false
            ManagedNetworkAdb.ownerSwitch = null
        }
    }

    /** The owner's press as the settings door runs it: the first look, then the lease, then the apply. */
    private fun turnOn(rig: Rig): LiveSettingRequestOutcome = when {
        directNetworkAdbOnRefusalJson(turnOn = true) != null -> LiveSettingRequestOutcome.REJECTED
        !takeNetworkAdbBackForOwner() -> LiveSettingRequestOutcome.REJECTED
        else -> applyOn(rig)
    }

    /** The apply path alone, as a queued or restored ON reaches it, with no owner at the door. */
    private fun applyOn(rig: Rig, authority: LiveSettingAuthority = LiveSettingAuthority(setOf("network_adb"), bootIdentity = { "test-boot" })) =
        requestLiveSetting(authority, rig.config, { rig.commands }, "network_adb", "true", unlockKiosk = { true }, refreshAutoSleep = {})

    private enum class Recovery { REOPENS, REFUSED, REOPENS_THEN_FAILS }

    /** Native daemon boundary only; the session runtime, dispatcher and controller are production. */
    private class LeaseEndpoint : LocalAdbEndpoint {
        private var status = LocalAdbState(false, false)
        var recovery = Recovery.REOPENS
        var whileRecovering: () -> Unit = {}
        var listening = false
        var arms = 0
            private set
        var beats = 0
            private set
        override fun state() = status
        override fun arm(nonce: String): Boolean {
            arms++
            status = LocalAdbState(true, false)
            listening = false
            return true
        }
        override fun beat(nonce: String): Boolean {
            beats++
            listening = false
            return true
        }
        override fun recover(nonce: String, submit: (() -> Unit) -> Boolean): Boolean? {
            if (!submit {
                if (recovery != Recovery.REFUSED) {
                    status = LocalAdbState(false, true)
                    listening = true
                }
            }) return null
            whileRecovering()
            // The helper reopened ADB and its answer never arrived.
            if (recovery == Recovery.REOPENS_THEN_FAILS) throw java.io.IOException("reply lost")
            return recovery == Recovery.REOPENS
        }
        override fun shell(command: ByteArray, timeoutMs: Int, submit: (() -> Unit) -> Boolean): JSONObject? =
            error("this test does not execute maintenance shell commands")
    }
}
