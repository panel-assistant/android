package io.github.maxlyth.hapaneld.sensors

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The clock is owned entirely by the test: every observation is given an explicit millisecond, so no
 * assertion here can be satisfied by real elapsed time.
 */
class HaLifecycleTest {
    private fun lifecycle() = HaLifecycle(backOnlineWindowMs = 8_000L)

    private fun HaLifecycle.event(event: HaLifecycleEvent, nowMs: Long) =
        onEvent(event, HaLifecycleSource.SOCKET, nowMs)

    // ---- the normal five-event restart ------------------------------------------------------

    @Test fun normalRestartWalksShuttingDownThenStartingThenBackOnline() {
        val ha = lifecycle()
        assertEquals(HaLifecycleState.NORMAL, ha.state(0))

        ha.event(HaLifecycleEvent.STOP, 1_000)
        assertEquals(HaLifecycleState.SHUTTING_DOWN, ha.state(1_000))

        ha.event(HaLifecycleEvent.FINAL_WRITE, 1_100)
        assertEquals(HaLifecycleState.SHUTTING_DOWN, ha.state(1_100))

        ha.event(HaLifecycleEvent.CLOSE, 1_200)
        assertEquals(HaLifecycleState.SHUTTING_DOWN, ha.state(1_200))

        ha.event(HaLifecycleEvent.START, 20_000)
        assertEquals(HaLifecycleState.STARTING, ha.state(20_000))

        ha.event(HaLifecycleEvent.STARTED, 30_000)
        assertEquals(HaLifecycleState.BACK_ONLINE, ha.state(30_000))
    }

    @Test fun backOnlineDecaysToNormalOnceItsWindowElapses() {
        val ha = lifecycle()
        ha.event(HaLifecycleEvent.STARTED, 5_000)
        assertEquals(HaLifecycleState.BACK_ONLINE, ha.state(12_999))
        assertEquals(HaLifecycleState.NORMAL, ha.state(13_000))
    }

    /**
     * A renderer recreated mid-window must FINISH the original notice, not restart it. Seeding a fresh
     * timer on every rebuild let an unlucky sequence of rebuilds extend a notice indefinitely.
     */
    @Test fun theRecoveryNoticeReportsItsRemainingLifetimeNotAFreshWindow() {
        val ha = lifecycle()
        ha.event(HaLifecycleEvent.STARTED, 1_000)
        assertEquals(8_000L, ha.remainingBackOnlineMs(1_000))
        assertEquals("half spent", 4_000L, ha.remainingBackOnlineMs(5_000))
        assertEquals("nearly done", 1L, ha.remainingBackOnlineMs(8_999))
    }

    @Test fun thereIsNoRemainingLifetimeWhenNoNoticeIsShowing() {
        val ha = lifecycle()
        assertEquals("nothing showing", 0L, ha.remainingBackOnlineMs(1_000))

        ha.event(HaLifecycleEvent.STARTED, 1_000)
        assertEquals("expired", 0L, ha.remainingBackOnlineMs(9_000))

        ha.event(HaLifecycleEvent.STOP, 20_000)
        assertEquals("an outage is not a recovery notice", 0L, ha.remainingBackOnlineMs(20_000))
    }

    @Test fun aBackwardsClockReportsNoRemainingLifetimeBecauseTheNoticeHasExpired() {
        val ha = lifecycle()
        ha.event(HaLifecycleEvent.STARTED, 50_000)
        // The state expires first, so there is no notice left to measure — which is also why no clamp
        // is needed on the arithmetic.
        assertEquals(0L, ha.remainingBackOnlineMs(10))
    }

    @Test fun backOnlineExpiresRatherThanExtendsWhenTheClockMovesBackwards() {
        val ha = lifecycle()
        ha.event(HaLifecycleEvent.STARTED, 50_000)
        assertEquals(HaLifecycleState.NORMAL, ha.state(10))
    }

    // ---- startup semantics --------------------------------------------------------------------

    @Test fun startIsNotBackOnline() {
        val ha = lifecycle()
        ha.event(HaLifecycleEvent.STOP, 1_000)
        ha.event(HaLifecycleEvent.START, 2_000)
        assertEquals(HaLifecycleState.STARTING, ha.state(2_000))
        // Still an outage: the frontend cannot render yet, so the notice must not clear.
        assertNotNull(HaLifecycleMessage.text(ha.state(2_000), HaLifecycleSource.SOCKET))
    }

    @Test fun startingNeverDecaysOnItsOwn() {
        val ha = lifecycle()
        ha.event(HaLifecycleEvent.START, 1_000)
        assertEquals(HaLifecycleState.STARTING, ha.state(9_999_000))
    }

    // ---- duplicates ---------------------------------------------------------------------------

    @Test fun duplicateShutdownEventsDoNotReopenOrReannounce() {
        val ha = lifecycle()
        ha.event(HaLifecycleEvent.STOP, 1_000)
        ha.event(HaLifecycleEvent.STOP, 1_050)
        ha.event(HaLifecycleEvent.STOP, 1_060)
        assertEquals(HaLifecycleState.SHUTTING_DOWN, ha.state(1_060))
    }

    @Test fun duplicateStartedDoesNotRestartTheBackOnlineWindow() {
        val ha = lifecycle()
        ha.event(HaLifecycleEvent.STARTED, 1_000)
        ha.event(HaLifecycleEvent.STARTED, 8_000)
        // Had the duplicate re-armed the window it would still be BACK_ONLINE at 9_000.
        assertEquals(HaLifecycleState.NORMAL, ha.state(9_000))
    }

    @Test fun duplicateStartDoesNotDisturbStarting() {
        val ha = lifecycle()
        ha.event(HaLifecycleEvent.START, 1_000)
        ha.event(HaLifecycleEvent.START, 2_000)
        assertEquals(HaLifecycleState.STARTING, ha.state(2_000))
    }

    // ---- out of order -------------------------------------------------------------------------

    @Test fun aLowerRankedShutdownStragglerIsAbsorbedNotReplayed() {
        val ha = lifecycle()
        ha.event(HaLifecycleEvent.CLOSE, 1_000)
        ha.event(HaLifecycleEvent.STOP, 1_100)
        ha.event(HaLifecycleEvent.FINAL_WRITE, 1_200)
        assertEquals(HaLifecycleState.SHUTTING_DOWN, ha.state(1_200))
    }

    @Test fun aLateStartDoesNotOverwriteAProvenRecovery() {
        val ha = lifecycle()
        ha.event(HaLifecycleEvent.STOP, 1_000)
        ha.event(HaLifecycleEvent.STARTED, 2_000)
        ha.event(HaLifecycleEvent.START, 2_100)
        assertEquals(HaLifecycleState.BACK_ONLINE, ha.state(2_100))
    }

    // ---- every suffix and prefix a remote client can miss --------------------------------------

    @Test fun everyShutdownEventAloneIsEnoughToReportShuttingDown() {
        listOf(HaLifecycleEvent.STOP, HaLifecycleEvent.FINAL_WRITE, HaLifecycleEvent.CLOSE)
            .forEach { only ->
                val ha = lifecycle()
                ha.event(only, 1_000)
                assertEquals("$only alone must report a shutdown", HaLifecycleState.SHUTTING_DOWN, ha.state(1_000))
            }
    }

    @Test fun everyProperPrefixOfTheRestartLeavesAnHonestState() {
        val order = HaLifecycleEvent.entries.sortedBy { it.rank }
        val expected = listOf(
            HaLifecycleState.SHUTTING_DOWN,
            HaLifecycleState.SHUTTING_DOWN,
            HaLifecycleState.SHUTTING_DOWN,
            HaLifecycleState.STARTING,
            HaLifecycleState.BACK_ONLINE,
        )
        for (length in 1..order.size) {
            val ha = lifecycle()
            order.take(length).forEachIndexed { index, event -> ha.event(event, 1_000L + index) }
            assertEquals(
                "prefix of length $length",
                expected[length - 1],
                ha.state(1_000L + length),
            )
        }
    }

    @Test fun noSuffixOfTheRestartCanStrandAShutdownBanner() {
        val order = HaLifecycleEvent.entries.sortedBy { it.rank }
        // A client that joins part-way through a restart still ends recovered, whichever leading
        // events it missed. This is the "reconnected mid-restart" family.
        for (drop in order.indices) {
            val ha = lifecycle()
            order.drop(drop).forEachIndexed { index, event -> ha.event(event, 1_000L + index) }
            assertEquals(
                "suffix dropping the first $drop event(s)",
                HaLifecycleState.BACK_ONLINE,
                ha.state(1_000L + order.size),
            )
        }
    }

    @Test fun aClientThatSeesOnlyStartedStillReportsRecovery() {
        val ha = lifecycle()
        ha.event(HaLifecycleEvent.STARTED, 1_000)
        assertEquals(HaLifecycleState.BACK_ONLINE, ha.state(1_000))
    }

    // ---- disconnects --------------------------------------------------------------------------

    @Test fun disconnectImmediatelyAfterStopKeepsTheShutdownExplanation() {
        val ha = lifecycle()
        ha.event(HaLifecycleEvent.STOP, 1_000)
        ha.onDisconnected(1_010)
        assertEquals(HaLifecycleState.SHUTTING_DOWN, ha.state(1_010))
    }

    @Test fun disconnectWhileStartingKeepsTheStartingExplanation() {
        val ha = lifecycle()
        ha.event(HaLifecycleEvent.START, 1_000)
        ha.onDisconnected(1_010)
        assertEquals(HaLifecycleState.STARTING, ha.state(1_010))
    }

    @Test fun ordinaryLanLossIsGenericAndNeverCalledAShutdown() {
        val ha = lifecycle()
        ha.onDisconnected(1_000)
        assertEquals(HaLifecycleState.CONNECTION_LOST, ha.state(1_000))
        assertNull(
            "a generic loss must not borrow Home Assistant shutdown wording",
            HaLifecycleMessage.text(ha.state(1_000), null),
        )
    }

    @Test fun repeatedDisconnectsStayGeneric() {
        val ha = lifecycle()
        ha.onDisconnected(1_000)
        ha.onDisconnected(2_000)
        assertEquals(HaLifecycleState.CONNECTION_LOST, ha.state(2_000))
    }

    // ---- the MQTT channel dying under its own claim ---------------------------------------------

    /**
     * A claim is only as durable as the panel's ability to hear its retraction. Home Assistant's birth
     * is not retained by default, so one missed while the panel's own broker link was down is missed
     * forever — an MQTT-sourced outage surviving that gap would be a stale banner with no clearer.
     */
    @Test fun anMqttSourcedOutageDoesNotSurviveTheLossOfItsOwnChannel() {
        val ha = lifecycle()
        ha.onEvent(HaLifecycleEvent.STOP, HaLifecycleSource.MQTT, 1_000)
        assertEquals(HaLifecycleState.SHUTTING_DOWN, ha.state(1_000))
        ha.onMqttChannelLost(2_000)
        assertEquals(
            "we can no longer know, and generic loss owns that",
            HaLifecycleState.CONNECTION_LOST,
            ha.state(2_000),
        )
        assertNull(HaLifecycleMessage.text(ha.state(2_000), null))
    }

    @Test fun aSocketSourcedOutageSurvivesAnMqttChannelLossBecauseItsClearersAreIndependent() {
        val ha = lifecycle()
        ha.onEvent(HaLifecycleEvent.STOP, HaLifecycleSource.SOCKET, 1_000)
        ha.onMqttChannelLost(2_000)
        assertEquals(
            "the socket claim is retracted by LIVE or by a later birth, not by our broker link",
            HaLifecycleState.SHUTTING_DOWN,
            ha.state(2_000),
        )
    }

    @Test fun mqttChannelLossInCalmOrGenericStatesChangesNothing() {
        val ha = lifecycle()
        ha.onMqttChannelLost(1_000)
        assertEquals(HaLifecycleState.NORMAL, ha.state(1_000))
        ha.onDisconnected(2_000)
        ha.onMqttChannelLost(3_000)
        assertEquals(HaLifecycleState.CONNECTION_LOST, ha.state(3_000))
    }

    @Test fun theChannelRestoredWithABirthStillReportsRecovery() {
        // The downgrade is not a dead end: a birth heard on the NEW channel proves recovery normally.
        val ha = lifecycle()
        ha.onEvent(HaLifecycleEvent.STOP, HaLifecycleSource.MQTT, 1_000)
        ha.onMqttChannelLost(2_000)
        ha.onEvent(HaLifecycleEvent.STARTED, HaLifecycleSource.MQTT, 10_000)
        // A birth is affirmative news of a restart, so it announces recovery from ANY prior state —
        // consistent with a client that saw only the birth — and then decays as recovery always does.
        assertEquals(HaLifecycleState.BACK_ONLINE, ha.state(10_000))
        assertEquals(HaLifecycleState.NORMAL, ha.state(19_000))
    }

    // ---- authenticated recovery ---------------------------------------------------------------

    @Test fun reconnectAfterBothStartupEventsWereMissedClearsTheOutage() {
        val ha = lifecycle()
        ha.event(HaLifecycleEvent.STOP, 1_000)
        ha.onDisconnected(1_010)
        // start and started both fired while the panel had no socket.
        ha.onAuthenticatedRunning(30_000)
        assertEquals(HaLifecycleState.BACK_ONLINE, ha.state(30_000))
        assertEquals(HaLifecycleState.NORMAL, ha.state(38_000))
    }

    // ---- readiness is proven by the server, not by the handshake -------------------------------

    @Test fun aSubscribedSessionDoesNotCallAReconnectedSocketRecovery() {
        // The defect this lane exists to fix, reproduced from the hardware sequence of 2026-08-14:
        // Home Assistant accepted an authenticated connection 28 s before it said it had started.
        val ha = lifecycle()
        ha.onSubscriptionEstablished()
        ha.event(HaLifecycleEvent.STOP, 1_000)
        ha.onDisconnected(1_010)
        ha.onSubscriptionEstablished()
        ha.onAuthenticatedRunning(60_000)
        assertEquals(
            "an authenticated socket proves Home Assistant is coming up, never that it can serve",
            HaLifecycleState.STARTING,
            ha.state(60_000),
        )
        // If STARTED happened before this new subscription existed, no event can arrive. Retire the
        // inferred notice silently rather than claiming recovery or stranding the panel forever.
        assertEquals(HaLifecycleState.STARTING, ha.state(179_999))
        assertEquals(HaLifecycleState.NORMAL, ha.state(180_000))
    }

    @Test fun onlyTheServersOwnStartedEventAnnouncesRecoveryToASubscribedSession() {
        val ha = lifecycle()
        ha.event(HaLifecycleEvent.STOP, 1_000)
        ha.onDisconnected(1_010)
        ha.onSubscriptionEstablished()
        ha.onAuthenticatedRunning(60_000)
        ha.event(HaLifecycleEvent.STARTED, 88_000)
        assertEquals(HaLifecycleState.BACK_ONLINE, ha.state(88_000))
        assertEquals(HaLifecycleState.NORMAL, ha.state(96_000))
    }

    @Test fun aRefusedSessionStillTreatsAnAuthenticatedSocketAsProof() {
        // A non-admin panel is never told the server started, so withholding recovery would strand the
        // notice for good. Its handshake remains the best proof it can obtain.
        val ha = lifecycle()
        ha.event(HaLifecycleEvent.STOP, 1_000)
        ha.onDisconnected(1_010)
        ha.onSubscriptionRejected()
        ha.onAuthenticatedRunning(60_000)
        assertEquals(HaLifecycleState.BACK_ONLINE, ha.state(60_000))
    }

    @Test fun aSubscriptionDiesWithItsOwnSessionRatherThanCoveringTheNext() {
        // The privileges of a session that has ended must not make the NEXT session wait for an event
        // nobody promised it — the same session-scoping the refusal already has.
        val ha = lifecycle()
        ha.onSubscriptionEstablished()
        ha.event(HaLifecycleEvent.STOP, 1_000)
        ha.onDisconnected(1_010)
        ha.onAuthenticatedRunning(60_000)
        assertEquals(
            "the accepted subscription died with the socket that held it",
            HaLifecycleState.BACK_ONLINE,
            ha.state(60_000),
        )
    }

    @Test fun aReplacementSessionStartingToSubscribeDropsThePredecessorsSubscription() {
        val ha = lifecycle()
        ha.onSubscriptionEstablished()
        ha.event(HaLifecycleEvent.STOP, 1_000)
        ha.onRefusalBasisEnded()
        ha.onAuthenticatedRunning(60_000)
        assertEquals(HaLifecycleState.BACK_ONLINE, ha.state(60_000))
    }

    @Test fun aFlappingSocketDoesNotReannounceAStartupAlreadyShowing() {
        val ha = lifecycle()
        ha.event(HaLifecycleEvent.STOP, 1_000)
        ha.onDisconnected(1_010)
        ha.onSubscriptionEstablished()
        ha.onAuthenticatedRunning(60_000)
        val first = ha.snapshot(60_000).revision
        ha.onAuthenticatedRunning(61_000)
        assertEquals(HaLifecycleState.STARTING, ha.state(61_000))
        assertEquals("re-entry is absorbed", first, ha.snapshot(61_000).revision)
    }

    @Test fun aSubscribedSessionStillNamesTheSocketWhenABrokerWordedTheOutage() {
        val ha = lifecycle()
        ha.onEvent(HaLifecycleEvent.STOP, HaLifecycleSource.MQTT, 1_000)
        ha.onSubscriptionEstablished()
        ha.onAuthenticatedRunning(60_000)
        val snap = ha.snapshot(60_000)
        assertEquals(HaLifecycleState.STARTING, snap.state)
        assertEquals(HaLifecycleSource.SOCKET, snap.source)
    }

    // ---- an episode outlives its state ---------------------------------------------------------

    @Test fun aLocallyNoticedLossIsRetiredWhenTheSocketRouteGoesAway() {
        // The measured defect: `onDisconnected` gives CONNECTION_LOST no source by design, so retirement
        // keyed on the claiming source could never match it. With MQTT keeping the snapshot alive the
        // stale outage rendered indefinitely.
        val ha = lifecycle()
        ha.onDisconnected(1_000)
        assertEquals(HaLifecycleState.CONNECTION_LOST, ha.state(1_000))
        ha.onSourceRetired(HaLifecycleSource.SOCKET, 2_000)
        assertEquals(
            "the socket produced the inference, so the socket route retires it",
            HaLifecycleState.NORMAL,
            ha.state(2_000),
        )
    }

    @Test fun anMqttChannelLossStillLeavesALocallyNoticedLossAlone() {
        // Basis must SCOPE retirement, not merely widen it: the inference belongs to the socket, so the
        // broker going away must not clear it.
        val ha = lifecycle()
        ha.onDisconnected(1_000)
        ha.onSourceRetired(HaLifecycleSource.MQTT, 2_000)
        assertEquals(HaLifecycleState.CONNECTION_LOST, ha.state(2_000))
    }

    @Test fun aRefusedPanelAnnouncesOneRecoveryAcrossHandshakeThenBirth() {
        // A refused subscription can produce shutting_down -> back_online(socket) -> normal ->
        // back_online(mqtt) 39 s later. Two banners for one outage, with a gap between.
        val ha = lifecycle()
        ha.onEvent(HaLifecycleEvent.STOP, HaLifecycleSource.MQTT, 1_000)
        ha.onDisconnected(1_100)
        ha.onSubscriptionRejected()
        ha.onAuthenticatedRunning(22_000)
        assertEquals("its handshake is the only proof it will ever get", HaLifecycleState.BACK_ONLINE, ha.state(22_000))
        assertEquals(HaLifecycleState.NORMAL, ha.state(30_100))
        // The broker birth for the SAME outage lands after the notice has decayed.
        ha.onEvent(HaLifecycleEvent.STARTED, HaLifecycleSource.MQTT, 61_000)
        assertEquals(
            "one outage announces one recovery, even after the notice decayed",
            HaLifecycleState.NORMAL,
            ha.state(61_000),
        )
    }

    @Test fun aGenuinelyNewOutageStillAnnouncesItsOwnRecovery() {
        // The suppression must be per-episode, not once per process, or the second real restart of the
        // day would go unannounced.
        val ha = lifecycle()
        ha.onEvent(HaLifecycleEvent.STOP, HaLifecycleSource.MQTT, 1_000)
        ha.onEvent(HaLifecycleEvent.STARTED, HaLifecycleSource.MQTT, 5_000)
        assertEquals(HaLifecycleState.BACK_ONLINE, ha.state(5_000))
        assertEquals(HaLifecycleState.NORMAL, ha.state(13_000))
        ha.onEvent(HaLifecycleEvent.STOP, HaLifecycleSource.MQTT, 100_000)
        ha.onEvent(HaLifecycleEvent.STARTED, HaLifecycleSource.MQTT, 140_000)
        assertEquals(
            "a second outage is a second episode and earns its own notice",
            HaLifecycleState.BACK_ONLINE,
            ha.state(140_000),
        )
    }

    @Test fun aRepeatedShutdownStageDoesNotEarnASecondRecoveryNotice() {
        // Every subscribed panel receives more than one shutdown stage, so the multi-stage path must
        // still announce exactly one recovery.
        val ha = lifecycle()
        ha.event(HaLifecycleEvent.STOP, 1_000)
        ha.event(HaLifecycleEvent.FINAL_WRITE, 1_100)
        ha.event(HaLifecycleEvent.CLOSE, 1_200)
        ha.event(HaLifecycleEvent.STARTED, 20_000)
        assertEquals(HaLifecycleState.BACK_ONLINE, ha.state(20_000))
        assertEquals(HaLifecycleState.NORMAL, ha.state(28_000))
        ha.event(HaLifecycleEvent.STARTED, 40_000)
        assertEquals("the same outage cannot announce twice", HaLifecycleState.NORMAL, ha.state(40_000))
    }

    @Test fun anOutageIsOnlyRetiredByTheChannelItDependsOn() {
        // Retirement must be scoped by basis, not merely enabled by it: a channel that does not own the
        // current outage must leave it alone.
        val ha = lifecycle()
        ha.onDisconnected(1_000)
        ha.onSourceRetired(HaLifecycleSource.SOCKET, 2_000)
        ha.onEvent(HaLifecycleEvent.STOP, HaLifecycleSource.MQTT, 10_000)
        ha.onSourceRetired(HaLifecycleSource.SOCKET, 11_000)
        assertEquals(
            "an MQTT-basis outage is not the socket's to retire",
            HaLifecycleState.SHUTTING_DOWN,
            ha.state(11_000),
        )
    }

    @Test fun aStaleShutdownNeverSurvivesAuthenticatedRecovery() {
        val ha = lifecycle()
        ha.event(HaLifecycleEvent.CLOSE, 1_000)
        ha.onDisconnected(1_010)
        ha.onAuthenticatedRunning(9_000)
        assertNull(
            "no shutdown banner may outlive proof that Home Assistant is running",
            HaLifecycleMessage.text(ha.state(20_000), HaLifecycleSource.SOCKET),
        )
    }

    @Test fun recoveryFromGenericLossDoesNotAnnounceHomeAssistantBack() {
        val ha = lifecycle()
        ha.onDisconnected(1_000)
        ha.onAuthenticatedRunning(2_000)
        // We never blamed Home Assistant, so we must not now credit its return.
        assertEquals(HaLifecycleState.NORMAL, ha.state(2_000))
    }

    @Test fun authenticatedRunningDuringNormalChangesNothing() {
        val ha = lifecycle()
        ha.onAuthenticatedRunning(1_000)
        assertEquals(HaLifecycleState.NORMAL, ha.state(1_000))
    }

    // ---- rapid restart ------------------------------------------------------------------------

    @Test fun aSecondShutdownDuringTheBackOnlineWindowOpensAFreshEpisode() {
        val ha = lifecycle()
        ha.event(HaLifecycleEvent.STOP, 1_000)
        ha.event(HaLifecycleEvent.STARTED, 2_000)
        assertEquals(HaLifecycleState.BACK_ONLINE, ha.state(2_000))
        ha.event(HaLifecycleEvent.STOP, 2_500)
        assertEquals(HaLifecycleState.SHUTTING_DOWN, ha.state(2_500))
    }

    @Test fun twoCompleteRestartsBackToBackBothReportFully() {
        val ha = lifecycle()
        listOf(HaLifecycleEvent.STOP, HaLifecycleEvent.CLOSE, HaLifecycleEvent.STARTED)
            .forEachIndexed { index, event -> ha.event(event, 1_000L + index) }
        assertEquals(HaLifecycleState.BACK_ONLINE, ha.state(1_002))

        ha.event(HaLifecycleEvent.STOP, 40_000)
        assertEquals(HaLifecycleState.SHUTTING_DOWN, ha.state(40_000))
        ha.event(HaLifecycleEvent.STARTED, 41_000)
        assertEquals(HaLifecycleState.BACK_ONLINE, ha.state(41_000))
    }

    // ---- subscription permission --------------------------------------------------------------

    @Test fun nonAdminRejectionIsRecordedAndLeavesTheStateHonest() {
        val ha = lifecycle()
        ha.onSubscriptionRejected()
        assertTrue(ha.snapshot(1_000).refused)
        // Learning nothing is not the same as claiming an outage.
        assertEquals(HaLifecycleState.NORMAL, ha.state(1_000))
        assertNull(HaLifecycleMessage.text(ha.state(1_000), null))
    }

    @Test fun aRefusalIsRecordedAndClearedByTheNextConnection() {
        val ha = lifecycle()
        ha.onSubscriptionRejected()
        assertTrue(ha.snapshot(500).refused)
        // A dead socket cannot still be a refused one; the next connection answers for itself.
        ha.onDisconnected(1_000)
        assertFalse(ha.snapshot(1_000).refused)
    }

    @Test fun aPartialRefusalCannotStrandAnOutageBecauseRecoveryIsProvenElsewhere() {
        // The socket route may be refused mid-outage; recovery is still proven independently, by Home
        // Assistant's MQTT birth and by a fresh authenticated connection. This is why per-subscription
        // coverage accounting was removed rather than made identity-aware.
        val ha = lifecycle()
        ha.onEvent(HaLifecycleEvent.STOP, HaLifecycleSource.SOCKET, 1_000)
        ha.onSubscriptionRejected()
        assertEquals(HaLifecycleState.SHUTTING_DOWN, ha.state(1_100))

        ha.onEvent(HaLifecycleEvent.STARTED, HaLifecycleSource.MQTT, 2_000)
        assertEquals("MQTT closes it", HaLifecycleState.BACK_ONLINE, ha.state(2_000))

        val other = lifecycle()
        other.onEvent(HaLifecycleEvent.STOP, HaLifecycleSource.SOCKET, 1_000)
        other.onSubscriptionRejected()
        other.onAuthenticatedRunning(2_000)
        assertEquals("a fresh authenticated connection closes it too", HaLifecycleState.BACK_ONLINE, other.state(2_000))
    }

    // ---- wire contract ------------------------------------------------------------------------

    @Test fun everyFunnelNamedEventTypeIsSubscribedByExactName() {
        assertEquals(
            listOf(
                "homeassistant_stop",
                "homeassistant_final_write",
                "homeassistant_close",
                "homeassistant_start",
                "homeassistant_started",
            ),
            HaLifecycleEvent.subscribed,
        )
    }

    @Test fun unknownEventTypesAreNotParsedIntoLifecycleEvents() {
        assertNull(HaLifecycleEvent.fromWire("state_changed"))
        assertNull(HaLifecycleEvent.fromWire(""))
        assertNull(HaLifecycleEvent.fromWire("homeassistant_started_extra"))
        assertEquals(HaLifecycleEvent.STARTED, HaLifecycleEvent.fromWire("homeassistant_started"))
    }

    @Test fun stateWireValuesAreStableAndDistinct() {
        val wire = HaLifecycleState.entries.map { it.wireValue }
        assertEquals(wire.size, wire.toSet().size)
        assertEquals(
            listOf("normal", "shutting_down", "starting", "back_online", "connection_lost"),
            wire,
        )
    }

    @Test fun messagesNameHomeAssistantAsTheActorAndCarryNoPanelDetail() {
        val shutdown = HaLifecycleMessage.text(HaLifecycleState.SHUTTING_DOWN, HaLifecycleSource.SOCKET).orEmpty()
        assertTrue(shutdown.startsWith("Home Assistant"))
        listOf(
            HaLifecycleState.SHUTTING_DOWN,
            HaLifecycleState.STARTING,
            HaLifecycleState.BACK_ONLINE,
        ).forEach { state ->
            val text = HaLifecycleMessage.text(state, HaLifecycleSource.SOCKET).orEmpty()
            assertTrue("$state must name Home Assistant", text.contains("Home Assistant"))
            assertFalse("$state must not blame ha-paneld", text.contains("ha-paneld"))
        }
    }

    @Test fun theBackOnlineWindowMustBePositive() {
        listOf(0L, -1L).forEach { invalid ->
            val failed = runCatching { HaLifecycle(backOnlineWindowMs = invalid) }.isFailure
            assertTrue("window $invalid must be rejected", failed)
        }
    }

    // ---- the source names an observer, never a guess --------------------------------------------

    @Test fun theSourceNamesAnObserverNeverAGuess() {
        val ha = lifecycle()
        assertNull("nothing observed yet", ha.snapshot(0).source)

        ha.onDisconnected(1_000)
        assertNull("a locally noticed loss has no observer to name", ha.snapshot(1_000).source)

        ha.onEvent(HaLifecycleEvent.STOP, HaLifecycleSource.MQTT, 2_000)
        assertEquals(HaLifecycleSource.MQTT, ha.snapshot(2_000).source)

        ha.onEvent(HaLifecycleEvent.STARTED, HaLifecycleSource.MQTT, 3_000)
        assertEquals(HaLifecycleSource.MQTT, ha.snapshot(3_000).source)
        assertNull(
            "a decayed notice reports nothing, so no source is reporting it",
            ha.snapshot(60_000).source,
        )
    }

    /**
     * Unreachable through the machine — a SHUTTING_DOWN always carries the source of the event that
     * opened it — but the message layer is public wording policy and must default to the claim it can
     * defend: only the socket proves intent.
     */
    @Test fun aNullSourceRendersTheWeakerOfflineClaimNeverTheDeliberateOne() {
        assertEquals(
            "Home Assistant has gone offline — controls may be temporarily unavailable.",
            HaLifecycleMessage.text(HaLifecycleState.SHUTTING_DOWN, null),
        )
        assertEquals(
            "Home Assistant is offline",
            HaLifecycleMessage.panelText(HaLifecycleState.SHUTTING_DOWN, null),
        )
    }

    /**
     * The reset that mutation testing proved unobserved: a connection loss reached from a state that
     * DID have an observer must drop that observer's name. Starting from the initial NORMAL proves
     * nothing, because the source is already null there.
     */
    @Test fun aConnectionLossDuringARecoveryNoticeDropsTheNoticesSource() {
        val ha = lifecycle()
        ha.onEvent(HaLifecycleEvent.STARTED, HaLifecycleSource.MQTT, 1_000)
        assertEquals(HaLifecycleSource.MQTT, ha.snapshot(1_000).source)

        ha.onDisconnected(2_000)
        val snap = ha.snapshot(2_000)
        assertEquals(HaLifecycleState.CONNECTION_LOST, snap.state)
        assertNull(
            "nobody observed the loss, so the recovery notice's source must not be inherited",
            snap.source,
        )
    }

    @Test fun refusalsBasisEndingClearsItWithoutInventingAnyOtherChange() {
        val ha = lifecycle()
        ha.onSubscriptionRejected()
        ha.onEvent(HaLifecycleEvent.STOP, HaLifecycleSource.SOCKET, 1_000)
        ha.onRefusalBasisEnded()
        val snap = ha.snapshot(1_100)
        assertFalse("the refusal is retired", snap.refused)
        assertEquals("the outage it accompanied is untouched", HaLifecycleState.SHUTTING_DOWN, snap.state)
    }
}
