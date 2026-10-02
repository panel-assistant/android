package io.github.maxlyth.hapaneld.sensors

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * What the panel's own status surfaces are allowed to claim, and WHO is allowed to change it.
 *
 * Two rules under test were each broken in an earlier build. The row may report the socket route only
 * once Home Assistant has ACKNOWLEDGED it, never merely because the panel demanded it — on hardware that
 * difference showed as a panel announcing socket coverage on an account that had refused every one of
 * those subscriptions. And every mutation is identity-checked against the owning coordinator, because
 * Android overlaps service lifetimes: a predecessor's deadline-late teardown must not erase the
 * successor's live installation.
 */
class HaLifecycleRuntimeTest {
    private var now = 0L
    private var mqttLive = false
    private var installed: HaLifecycleCoordinator? = null

    private fun coordinator(): HaLifecycleCoordinator = HaLifecycleCoordinator(
        lifecycle = HaLifecycle(backOnlineWindowMs = 8_000L),
        nowMs = { now },
    )

    private var lease = HaLifecycleRuntime.MqttLease()

    private fun install(): HaLifecycleCoordinator {
        val c = coordinator()
        HaLifecycleRuntime.install(c)
        lease = HaLifecycleRuntime.MqttLease()
        HaLifecycleRuntime.installMqttLease(c, lease) { mqttLive }
        installed = c
        return c
    }

    private fun uninstallAny() {
        installed?.let { HaLifecycleRuntime.uninstall(it) }
        installed = null
    }

    @Before fun reset() {
        now = 0
        mqttLive = false
        uninstallAny()
    }

    @After fun teardown() {
        uninstallAny()
    }

    // ---- nothing to say --------------------------------------------------------------------------

    @Test fun anUninstalledRuntimeClaimsNothingAndNeverThrows() {
        assertNull(HaLifecycleRuntime.statusText())
        assertNull("no owner means no snapshot, not a default one", HaLifecycleRuntime.snapshot())
        assertFalse(HaLifecycleRuntime.watching)
    }

    @Test fun aPanelWatchingWithNeitherSourceSaysNothingAtAll() {
        val c = install()
        HaLifecycleRuntime.setWatching(c, false)
        mqttLive = false
        assertNull("no source means the row is omitted entirely", HaLifecycleRuntime.statusText())
    }

    /**
     * The adversarial two-session ordering that breaks copy-based designs: session A's late disconnect
     * callback lands after session B is live. With the MQTT half DERIVED from the bridge's canonical
     * connection state, a superseded session has nothing to write — the read simply reports whichever
     * session is current, in every interleaving.
     */
    @Test fun aStaleSessionsDisconnectCannotClearALiveSessionsTruth() {
        install()
        mqttLive = true   // session B is live; A's stale disconnect mutates nothing
        assertTrue("the read reports the CURRENT session", HaLifecycleRuntime.watching)
        mqttLive = false  // the current session genuinely drops
        assertFalse("and only the current session's drop clears it", HaLifecycleRuntime.watching)
        mqttLive = true   // reconnecting claims again with no ceremony to get wrong
        assertTrue(HaLifecycleRuntime.watching)
    }

    @Test fun theDerivedReadIsAlwaysCurrentTruthNeverACachedClaim() {
        install()
        repeat(3) {
            mqttLive = true
            assertTrue(HaLifecycleRuntime.watching)
            mqttLive = false
            assertFalse(HaLifecycleRuntime.watching)
        }
    }

    @Test fun eitherSourceAloneCountsAsWatching() {
        val c = install()
        HaLifecycleRuntime.setWatching(c, true)
        mqttLive = false
        assertTrue(HaLifecycleRuntime.watching)

        HaLifecycleRuntime.setWatching(c, false)
        mqttLive = true
        assertTrue("MQTT alone must keep the feature reporting", HaLifecycleRuntime.watching)
    }

    // ---- overlapping service lifetimes: ownership is identity-checked -----------------------------

    @Test fun aPredecessorsLateTeardownCannotEraseTheSuccessorsInstallation() {
        val predecessor = install()
        val successor = coordinator()
        HaLifecycleRuntime.install(successor)
        HaLifecycleRuntime.installMqttLease(successor, HaLifecycleRuntime.MqttLease()) { mqttLive }
        installed = successor
        HaLifecycleRuntime.setWatching(successor, true)
        successor.onSignal(HaLifecycleSignal.Event(HaLifecycleEvent.STOP))

        assertFalse("a superseded owner clears nothing", HaLifecycleRuntime.uninstall(predecessor))
        assertEquals(
            "the successor's live state survives the predecessor's timed-out teardown",
            HaLifecycleState.SHUTTING_DOWN,
            HaLifecycleRuntime.snapshot()?.state,
        )
    }

    @Test fun uninstallingTheCurrentOwnerReportsItSoConsumersCanBeToldToRedraw() {
        val c = install()
        assertTrue("clearing the live owner is a change consumers must hear about", HaLifecycleRuntime.uninstall(c))
        assertNull(HaLifecycleRuntime.snapshot())
        installed = null
    }

    @Test fun aSupersededOwnersWatchFlagWriteIsIgnored() {
        val predecessor = install()
        val successor = coordinator()
        HaLifecycleRuntime.install(successor)
        HaLifecycleRuntime.installMqttLease(successor, HaLifecycleRuntime.MqttLease()) { mqttLive }
        installed = successor

        HaLifecycleRuntime.setWatching(predecessor, true)
        assertFalse("a dead owner cannot claim the socket route is watched", HaLifecycleRuntime.watching)
        HaLifecycleRuntime.setWatching(successor, true)
        assertTrue(HaLifecycleRuntime.watching)
    }

    @Test fun installingReplacesTheWholeOwnershipAtomically() {
        val predecessor = install()
        HaLifecycleRuntime.setWatching(predecessor, true)
        val successor = coordinator()
        HaLifecycleRuntime.install(successor)
        HaLifecycleRuntime.installMqttLease(successor, HaLifecycleRuntime.MqttLease()) { false }
        installed = successor
        assertFalse(
            "the predecessor's watch flag does not leak into the successor's installation",
            HaLifecycleRuntime.watching,
        )
    }

    // ---- switching the watch off retires what consumers render -----------------------------------

    /**
     * Disabling the last route does not merely stop new observations: nothing is reportable any more,
     * so an outage claimed a moment ago must stop being renderable in the same step. Otherwise the
     * native card keeps describing an outage for a feature that is no longer watching, and a resume
     * redraws it from that state.
     */
    @Test fun disablingTheLastWatchRetiresEverythingConsumersCanRender() {
        val c = install()
        HaLifecycleRuntime.setWatching(c, true)
        mqttLive = false
        c.onSignal(HaLifecycleSignal.Event(HaLifecycleEvent.STOP))
        assertEquals(HaLifecycleState.SHUTTING_DOWN, HaLifecycleRuntime.snapshot()?.state)

        assertTrue("the caller must learn it has to poke consumers", HaLifecycleRuntime.setWatching(c, false))
        assertNull("an unreportable holder renders nothing", HaLifecycleRuntime.snapshot())
        assertNull(HaLifecycleRuntime.statusText())
    }

    @Test fun anUnchangedWatchFlagReportsNoChangeSoConsumersAreNotWokenForNothing() {
        val c = install()
        assertTrue(HaLifecycleRuntime.setWatching(c, true))
        assertFalse("setting the same value twice is not a change", HaLifecycleRuntime.setWatching(c, true))
    }

    @Test fun theOtherSourceAloneKeepsTheStateRenderable() {
        val c = install()
        HaLifecycleRuntime.setWatching(c, true)
        mqttLive = true
        c.onSignal(HaLifecycleSignal.Event(HaLifecycleEvent.STOP))
        HaLifecycleRuntime.setWatching(c, false)
        assertEquals(
            "MQTT is still watching, so the outage is still reportable",
            HaLifecycleState.SHUTTING_DOWN,
            HaLifecycleRuntime.snapshot()?.state,
        )
    }

    // ---- a bridge outliving its service cannot feed the successor --------------------------------

    @Test fun aPredecessorBridgeCallbackCannotMutateTheSuccessorsCoordinator() {
        val predecessor = install()
        val predecessorLease = lease
        val successor = coordinator()
        HaLifecycleRuntime.install(successor)
        lease = HaLifecycleRuntime.MqttLease()
        HaLifecycleRuntime.installMqttLease(successor, lease) { mqttLive }
        installed = successor
        HaLifecycleRuntime.setWatching(successor, true)

        HaLifecycleRuntime.observeMqtt(predecessorLease, HaLifecycleEvent.STOP)
        assertEquals(
            "a superseded owner's broker callback observes nothing",
            HaLifecycleState.NORMAL,
            HaLifecycleRuntime.snapshot()?.state,
        )

        HaLifecycleRuntime.observeMqtt(lease, HaLifecycleEvent.STOP)
        assertEquals(HaLifecycleState.SHUTTING_DOWN, HaLifecycleRuntime.snapshot()?.state)
    }

    @Test fun aPredecessorBridgeChannelLossCannotDowngradeTheSuccessorsClaim() {
        val predecessor = install()
        val predecessorLease = lease
        val successor = coordinator()
        HaLifecycleRuntime.install(successor)
        lease = HaLifecycleRuntime.MqttLease()
        HaLifecycleRuntime.installMqttLease(successor, lease) { mqttLive }
        installed = successor
        HaLifecycleRuntime.setWatching(successor, true)
        HaLifecycleRuntime.observeMqtt(lease, HaLifecycleEvent.STOP)

        HaLifecycleRuntime.observeMqttChannelLost(predecessorLease)
        assertEquals(
            "the successor's MQTT-sourced claim survives a DEAD session's channel loss",
            HaLifecycleState.SHUTTING_DOWN,
            HaLifecycleRuntime.snapshot()?.state,
        )

        HaLifecycleRuntime.observeMqttChannelLost(lease)
        assertEquals(HaLifecycleState.CONNECTION_LOST, HaLifecycleRuntime.snapshot()?.state)
    }

    // ---- a REPLACED bridge is a replaced channel -------------------------------------------------

    /**
     * Reconfigure replaces the bridge while the service and its coordinator stay the same, so owner
     * identity alone cannot separate the old channel from the new one. The lease can — and the
     * replacement also retires what the old channel claimed, because the birth that would retract it
     * is not retained and the new channel gets no replay.
     */
    @Test fun replacingTheBridgeRetiresWhatTheOldChannelClaimed() {
        val c = install()
        mqttLive = true
        HaLifecycleRuntime.observeMqtt(lease, HaLifecycleEvent.STOP)
        assertEquals(HaLifecycleState.SHUTTING_DOWN, HaLifecycleRuntime.snapshot()?.state)

        val replacement = HaLifecycleRuntime.MqttLease()
        assertTrue(HaLifecycleRuntime.installMqttLease(c, replacement) { mqttLive })
        assertEquals(
            "a claim heard on a channel that no longer exists is retired, not carried over",
            HaLifecycleState.NORMAL,
            HaLifecycleRuntime.snapshot()?.state,
        )
    }

    @Test fun aSupersededBridgeGenerationCannotReportAtAll() {
        val c = install()
        val stale = lease
        mqttLive = true
        val replacement = HaLifecycleRuntime.MqttLease()
        HaLifecycleRuntime.installMqttLease(c, replacement) { mqttLive }

        HaLifecycleRuntime.observeMqtt(stale, HaLifecycleEvent.STOP)
        assertEquals(
            "the replaced bridge's queued callback observes nothing",
            HaLifecycleState.NORMAL,
            HaLifecycleRuntime.snapshot()?.state,
        )
        HaLifecycleRuntime.observeMqtt(replacement, HaLifecycleEvent.STOP)
        assertEquals(HaLifecycleState.SHUTTING_DOWN, HaLifecycleRuntime.snapshot()?.state)
    }

    @Test fun aSupersededOwnerCannotRegisterABridgeLease() {
        val predecessor = install()
        val successor = coordinator()
        HaLifecycleRuntime.install(successor)
        installed = successor
        assertFalse(
            "a dead service's bridge cannot claim the live channel",
            HaLifecycleRuntime.installMqttLease(predecessor, HaLifecycleRuntime.MqttLease()) { true },
        )
    }

    // ---- a superseded owner cannot answer a read either -------------------------------------------

    /**
     * The window the after-read validation exists for, observed deterministically. Ownership must change
     * BETWEEN the capture and the coordinator read — checking before and after an uninstall proves
     * nothing, because the capture already sees no owner and returns early (mutation testing caught
     * exactly that: the revalidation survived its own removal).
     *
     * The bridge supplier IS that window: it is deliberately invoked outside the lock, so a supplier
     * that supersedes the holder as it runs reproduces the race with no threads.
     */
    @Test fun aHolderSupersededBetweenTheCaptureAndTheReadAnswersNothing() {
        val c = coordinator()
        HaLifecycleRuntime.install(c)
        var supersedeDuringRead = false
        HaLifecycleRuntime.installMqttLease(c, HaLifecycleRuntime.MqttLease()) {
            if (supersedeDuringRead) HaLifecycleRuntime.uninstall(c)
            true
        }
        installed = c
        c.onSignal(HaLifecycleSignal.Event(HaLifecycleEvent.STOP))
        assertEquals(
            "the ordinary path still reports",
            HaLifecycleState.SHUTTING_DOWN,
            HaLifecycleRuntime.snapshot()?.state,
        )

        supersedeDuringRead = true
        assertNull(
            "a superseded owner's state must not be returned, even when it was current at capture",
            HaLifecycleRuntime.snapshot(),
        )
        installed = null
    }

    @Test fun aSnapshotIsDiscardedIfOwnershipChangedWhileItWasRead() {
        val c = install()
        HaLifecycleRuntime.setWatching(c, true)
        c.onSignal(HaLifecycleSignal.Event(HaLifecycleEvent.STOP))
        assertEquals(HaLifecycleState.SHUTTING_DOWN, HaLifecycleRuntime.snapshot()?.state)

        HaLifecycleRuntime.uninstall(c)
        installed = null
        assertNull("a superseded owner's state is never returned", HaLifecycleRuntime.snapshot())
    }

    // ---- the claim the panel is allowed to make --------------------------------------------------

    @Test fun aRefusalIsReportedPlainlyAlongsideTheRouteStillWorking() {
        val c = install()
        HaLifecycleRuntime.setWatching(c, true)
        mqttLive = true
        c.onSignal(HaLifecycleSignal.Rejected)
        val text = HaLifecycleRuntime.statusText().orEmpty()
        assertTrue("a refusal is reported rather than hidden", text.contains("does not permit"))
    }

    @Test fun aRefusalWithoutMqttSaysOnlyThatItIsNotPermitted() {
        val c = install()
        HaLifecycleRuntime.setWatching(c, true)
        mqttLive = false
        c.onSignal(HaLifecycleSignal.Rejected)
        assertTrue(HaLifecycleRuntime.statusText().orEmpty().contains("does not permit"))
    }

    @Test fun mqttOnlyIsDescribedWithoutMentioningTheSocket() {
        val c = install()
        HaLifecycleRuntime.setWatching(c, false)
        mqttLive = true
        assertEquals("watching", HaLifecycleRuntime.statusText())
    }

    // ---- an actual outage outranks the idle description -------------------------------------------

    @Test fun anOutageIsReportedInsteadOfTheWatchingDescription() {
        val c = install()
        mqttLive = true
        HaLifecycleRuntime.observeMqtt(lease, HaLifecycleEvent.STOP)
        val text = HaLifecycleRuntime.statusText().orEmpty()
        assertTrue("a broker will must not claim intent", text.contains("gone offline"))
        assertFalse(text.contains("watching"))
    }

    @Test fun aSocketOutageKeepsTheStrongerWording() {
        val c = install()
        HaLifecycleRuntime.setWatching(c, true)
        c.onSignal(HaLifecycleSignal.Event(HaLifecycleEvent.STOP))
        assertTrue(HaLifecycleRuntime.statusText().orEmpty().contains("shutting down"))
    }

    @Test fun aGenericConnectionLossIsNotDressedAsAHomeAssistantOutage() {
        val c = install()
        HaLifecycleRuntime.setWatching(c, true)
        c.onSignal(HaLifecycleSignal.Transport(HaExactEntityStreamPhase.RECONNECTING))
        assertEquals("watching", HaLifecycleRuntime.statusText())
    }

    @Test fun theStatusNeverLeaksAnEventPayloadOrCredential() {
        val c = install()
        HaLifecycleRuntime.setWatching(c, true)
        mqttLive = true
        listOf(
            HaLifecycleSignal.Event(HaLifecycleEvent.STOP),
            HaLifecycleSignal.Rejected,
            HaLifecycleSignal.Transport(HaExactEntityStreamPhase.LIVE),
        ).forEach { signal ->
            c.onSignal(signal)
            val text = HaLifecycleRuntime.statusText().orEmpty()
            listOf("token", "password", "entity_id", "http", "Bearer").forEach {
                assertFalse("status must not contain $it", text.contains(it, ignoreCase = true))
            }
        }
    }
}
