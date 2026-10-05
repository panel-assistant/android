package io.panelassistant.android.mqtt

import io.panelassistant.android.MqttBrokerProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression net over the MQTT watchdog decision — the exact behaviour behind the reconnect incidents,
 * now unit-testable via the ConnectionSupervisor extraction. Times are in the same units the service uses.
 */
class ConnectionSupervisorTest {
    @Test fun `broker progress exposes provenance only while its connection is current`() {
        val progress = MqttBrokerProgress(lastOkMs = 12_345L, connectionGeneration = 8L)

        assertEquals(progress, progress.forCurrentConnection(8L))
        assertEquals(MqttBrokerProgress(12_345L, null), progress.forCurrentConnection(null))
        assertEquals(MqttBrokerProgress(12_345L, null), progress.forCurrentConnection(9L))
    }

    @Test fun `live heartbeat for current generation suppresses another`() {
        assertEquals(
            HeartbeatAdmission.Decision.CurrentHeartbeatAlive,
            HeartbeatAdmission.decide(currentGeneration = 7L, liveTrackedGenerations = listOf(7L)),
        )
    }

    @Test fun `first stranded generation admits replacement`() {
        assertEquals(
            HeartbeatAdmission.Decision.Admit(generation = 8L, replacingStranded = true),
            HeartbeatAdmission.decide(currentGeneration = 8L, liveTrackedGenerations = listOf(7L)),
        )
    }

    @Test fun `second stranded generation still admits replacement`() {
        assertEquals(
            HeartbeatAdmission.Decision.Admit(generation = 9L, replacingStranded = true),
            HeartbeatAdmission.decide(currentGeneration = 9L, liveTrackedGenerations = listOf(7L, 8L)),
        )
    }

    @Test fun `third stranded generation escalates without admitting another thread`() {
        assertEquals(
            HeartbeatAdmission.Decision.EscalateRecovery(strandedGenerations = 3),
            HeartbeatAdmission.decide(currentGeneration = 10L, liveTrackedGenerations = listOf(7L, 8L, 9L)),
        )
    }

    @Test fun `missing current connection suppresses heartbeat admission`() {
        assertEquals(
            HeartbeatAdmission.Decision.NoCurrentConnection,
            HeartbeatAdmission.decide(currentGeneration = null, liveTrackedGenerations = emptyList()),
        )
    }

    @Test fun `completed heartbeat admits another probe in the same generation`() {
        assertEquals(
            HeartbeatAdmission.Decision.Admit(generation = 7L, replacingStranded = false),
            HeartbeatAdmission.decide(currentGeneration = 7L, liveTrackedGenerations = emptyList()),
        )
    }

    @Test fun `successful reconnect admits a heartbeat while the retired connection probe is stuck`() {
        val connection = MqttConnectionGeneration()
        val retired = connection.advance()
        connection.clear()
        assertEquals(null, connection.currentOrNull())
        // Models either an explicit replacement connect or HiveMQ's automatic reconnect callback.
        val replacement = connection.advance()

        assertTrue(replacement > retired)
        assertEquals(false, connection.isCurrent(retired))
        assertTrue(connection.isCurrent(replacement))
        assertEquals(
            HeartbeatAdmission.Decision.Admit(generation = replacement, replacingStranded = true),
            HeartbeatAdmission.decide(
                currentGeneration = connection.currentOrNull(),
                liveTrackedGenerations = listOf(retired),
            ),
        )
    }

    @Test fun `authentication recovery is not rebuilt or family-flipped by generic watchdog`() {
        val s = ConnectionSupervisor(staleMs = 100, rebuildAbandonMs = 1_000)
        assertEquals(ConnectionSupervisor.Action.None, s.tick("auth-retrying", 1, 10_000, 10_001, false))
        assertEquals(ConnectionSupervisor.Action.None, s.tick("auth-failed", 1, 20_000, 20_001, false))
    }

    private val stale = 150_000L
    private val abandon = 300_000L
    private fun supervisor() = ConnectionSupervisor(stale, abandon)

    @Test fun connectedIsHealthy() {
        assertEquals(ConnectionSupervisor.Action.None, supervisor().tick("connected", 1_000L, 5_000L, 10_000L, false))
    }

    @Test fun disabledIsNoneEvenWhenApparentlyStale() {
        assertEquals(ConnectionSupervisor.Action.None, supervisor().tick("disabled", 1_000L, stale + 1, 10_000L, false))
    }

    @Test fun invalidBrokerConfigurationIsTerminalUntilConfigurationChanges() {
        val s = supervisor()
        assertEquals(ConnectionSupervisor.Action.None, s.tick("config-error", 0L, 0L, 1_000L, false))
        assertEquals(ConnectionSupervisor.Action.None, s.tick("config-error", 0L, 0L, 2_000L, false))
    }

    @Test fun `completed reconnect submission cannot detach and alternate the fallback every minute`() {
        val s = supervisor()
        val actions = mutableListOf<ConnectionSupervisor.Action>()

        val fallback = s.tick("connected", 1_000L, stale + 1, 500_000L, false).also(actions::add)
        assertTrue("$fallback", fallback is ConnectionSupervisor.Action.Rebuild)
        assertEquals(true, (fallback as ConnectionSupervisor.Action.Rebuild).flipFamily)

        // Models the fleet failure exactly: reconnect() submitted successfully and returned, but its
        // asynchronous client never reached onConnected/PUBACK. Seven milliseconds later the owner
        // Future is already done (rebuildInFlight=false); that is submission, not recovery.
        s.rebuildAdmitted()
        val submissionDone = s.tick("connecting", 1_000L, stale + 8L, 500_007L, false).also(actions::add)
        assertTrue("$submissionDone", submissionDone is ConnectionSupervisor.Action.SkipRebuild)
        // The old implementation detached the IPv4 fallback after each 60-second tick and flipped back
        // to IPv6 forever. The one admitted client must instead retain the full progress grace.
        val oneMinuteLater = s.tick("connecting", 1_000L, stale + 60_001L, 560_000L, false).also(actions::add)
        assertTrue("$oneMinuteLater", oneMinuteLater is ConnectionSupervisor.Action.SkipRebuild)
        val twoMinutesLater = s.tick("connecting", 1_000L, stale + 120_001L, 620_000L, false).also(actions::add)
        assertTrue("$twoMinutesLater", twoMinutesLater is ConnectionSupervisor.Action.SkipRebuild)
        val beforeBound = s.tick("connecting", 1_000L, stale + 299_999L, 799_999L, false).also(actions::add)
        assertTrue("$beforeBound", beforeBound is ConnectionSupervisor.Action.SkipRebuild)
        val atBound = s.tick("connecting", 1_000L, stale + 300_000L, 800_000L, false).also(actions::add)
        assertEquals(ConnectionSupervisor.Action.ProcessRecovery("liveness-no-progress"), atBound)
        assertEquals(1, actions.count { it is ConnectionSupervisor.Action.Rebuild })
    }

    @Test fun `old acknowledgement cannot classify connecting state as liveness stale`() {
        val s = supervisor()
        assertEquals(ConnectionSupervisor.Action.None, s.tick("connecting", 1_000L, stale + 1, 500_000L, false))
        val action = s.tick("connecting", 1_000L, stale + 60_001L, 560_000L, false)
        assertTrue("$action", action is ConnectionSupervisor.Action.Rebuild && action.reason == "state")
    }

    @Test fun `broker progress clears recovery epoch and permits a later independent fallback`() {
        val s = supervisor()
        assertTrue(s.tick(
            "connected", 1_000L, stale + 1, 500_000L, false,
            connectionGeneration = 7L,
        ) is ConnectionSupervisor.Action.Rebuild)
        s.rebuildAdmitted()
        // A generation-scoped CONNACK or PUBACK moves lastOk. The completed fallback is now proven.
        assertEquals(ConnectionSupervisor.Action.None, s.tick(
            "connected", 2_000L, 10L, 560_000L, false,
            connectionGeneration = 8L,
        ))
        val later = s.tick(
            "connected", 2_000L, stale + 1, 800_000L, false,
            connectionGeneration = 8L,
        )
        assertTrue("$later", later is ConnectionSupervisor.Action.Rebuild && later.flipFamily)
    }

    @Test fun `late old acknowledgement cannot prove the admitted fallback`() {
        val s = supervisor()
        assertTrue(s.tick(
            "connected", 1_000L, stale + 1, 500_000L, false,
            runtimeGeneration = 3L,
            connectionGeneration = 41L,
        ) is ConnectionSupervisor.Action.Rebuild)

        // The runtime worker has not detached the old client yet. Its late PUBACK advances the shared
        // timestamp, but belongs to the baseline transport generation and must not clear the epoch.
        assertTrue(s.tick(
            "connected", 1_100L, 1L, 500_010L, true,
            runtimeGeneration = 3L,
            connectionGeneration = 41L,
        ) is ConnectionSupervisor.Action.SkipRebuild)

        // Even after the owner Future proves submission, another old-client ACK is not replacement
        // progress. Only the new client's CONNACK/PUBACK generation can prove the fallback.
        s.rebuildAdmitted()
        assertTrue(s.tick(
            "connected", 1_200L, 1L, 500_020L, false,
            runtimeGeneration = 3L,
            connectionGeneration = 41L,
        ) is ConnectionSupervisor.Action.SkipRebuild)
        assertEquals(ConnectionSupervisor.Action.None, s.tick(
            "connected", 1_300L, 1L, 500_030L, false,
            runtimeGeneration = 3L,
            connectionGeneration = 42L,
        ))
    }

    @Test fun `old auto reconnect detached by queued fallback cannot prove that fallback`() {
        val s = supervisor()
        assertTrue(s.tick(
            "connected", 1_000L, stale + 1, 500_000L, false,
            runtimeGeneration = 3L,
            connectionGeneration = 41L,
        ) is ConnectionSupervisor.Action.Rebuild)

        // The old Hive client auto-reconnects while owner work is queued. It has genuine progress and a
        // new connection generation, but admission has not completed, so it cannot prove the fallback.
        assertTrue(s.tick(
            "connected", 1_100L, 1L, 500_010L, true,
            runtimeGeneration = 3L,
            connectionGeneration = 42L,
        ) is ConnectionSupervisor.Action.SkipRebuild)

        // The queued fallback then detaches that client. The watchdog observation keeps the timestamp but strips
        // its no-longer-current provenance; successful submission still is not a CONNACK.
        s.rebuildAdmitted()
        assertTrue(s.tick(
            "connecting", 1_100L, 10L, 500_020L, false,
            runtimeGeneration = 3L,
            connectionGeneration = null,
        ) is ConnectionSupervisor.Action.SkipRebuild)
        assertEquals(ConnectionSupervisor.Action.None, s.tick(
            "connected", 1_200L, 1L, 500_030L, false,
            runtimeGeneration = 3L,
            connectionGeneration = 43L,
        ))
    }

    @Test fun `rejected runtime admission does not masquerade as a recovery attempt`() {
        val s = supervisor()
        assertTrue(s.tick("connected", 1_000L, stale + 1, 500_000L, false) is ConnectionSupervisor.Action.Rebuild)
        s.rebuildNotAdmitted()

        val retried = s.tick("connected", 1_000L, stale + 60_001L, 560_000L, false)
        assertTrue("$retried", retried is ConnectionSupervisor.Action.Rebuild)
    }

    @Test fun `replacement runtime starts a new fallback epoch even when both have no progress`() {
        val s = supervisor()
        assertEquals(ConnectionSupervisor.Action.None, s.tick("connecting", 0L, 0L, 60_000L, false, runtimeGeneration = 7L))
        assertTrue(s.tick("connecting", 0L, 0L, 120_000L, false, runtimeGeneration = 7L) is ConnectionSupervisor.Action.Rebuild)
        assertTrue(s.tick("connecting", 0L, 0L, 180_000L, false, runtimeGeneration = 7L) is ConnectionSupervisor.Action.SkipRebuild)

        assertEquals(ConnectionSupervisor.Action.None, s.tick("connecting", 0L, 0L, 240_000L, false, runtimeGeneration = 8L))
        val replacementFallback = s.tick("connecting", 0L, 0L, 300_000L, false, runtimeGeneration = 8L)
        assertTrue("$replacementFallback", replacementFallback is ConnectionSupervisor.Action.Rebuild)
    }

    @Test fun livenessNotArmedUntilFirstAck() {
        // lastOkMs == 0 => liveness disabled; a connected client with no ACK yet is not "stale"
        assertEquals(ConnectionSupervisor.Action.None, supervisor().tick("connected", 0L, stale + 1, 500_000L, false))
    }

    @Test fun stateStuckForcesRebuildAfterTwoTicks() {
        val s = supervisor()
        assertEquals(ConnectionSupervisor.Action.None, s.tick("reconnecting", 0L, 0L, 1_000L, false))
        val a = s.tick("reconnecting", 0L, 0L, 2_000L, false)
        assertTrue("$a", a is ConnectionSupervisor.Action.Rebuild && a.reason == "state")
    }

    @Test fun `never-connected broker outage does not become a periodic process restart loop`() {
        val s = supervisor()
        assertEquals(ConnectionSupervisor.Action.None, s.tick("connecting", 0L, 0L, 60_000L, false))
        assertTrue(s.tick("connecting", 0L, 0L, 120_000L, false) is ConnectionSupervisor.Action.Rebuild)
        assertTrue(s.tick("connecting", 0L, 0L, 419_999L, false) is ConnectionSupervisor.Action.SkipRebuild)
        // A never-live runtime never crosses the process boundary. It DOES keep replacing its client at
        // the abandon cadence: this test previously asserted permanent silence here, which is exactly
        // how a panel stayed on a black-holed address family for hours with no mechanism able to try
        // the live sibling. Every action below must therefore be a Rebuild, never a ProcessRecovery.
        var now = 420_000L
        repeat(20) {
            val action = s.tick("unreachable", 0L, 0L, now, false)
            assertEquals(ConnectionSupervisor.Action.Rebuild("state", flipFamily = true), action)
            s.rebuildAdmitted()
            assertTrue(
                "$now",
                s.tick("unreachable", 0L, 0L, now + 60_000L, false) is
                    ConnectionSupervisor.Action.SkipRebuild,
            )
            now += 300_000L
        }
    }

    @Test fun `never-connected runtime keeps alternating even while a rebuild is still in flight`() {
        val s = supervisor()
        assertEquals(ConnectionSupervisor.Action.None, s.tick("unreachable", 0L, 0L, 60_000L, false))
        assertTrue(s.tick("unreachable", 0L, 0L, 120_000L, false) is ConnectionSupervisor.Action.Rebuild)
        // A wedged owner worker must not consume the alternation; the epoch stays open until it clears.
        assertTrue(
            s.tick("unreachable", 0L, 0L, 420_000L, true) is ConnectionSupervisor.Action.SkipRebuild,
        )
        assertEquals(
            ConnectionSupervisor.Action.Rebuild("state", flipFamily = true),
            s.tick("unreachable", 0L, 0L, 480_000L, false),
        )
    }

    @Test fun `a proved address family is never flipped by the never-live alternation`() {
        val s = supervisor()
        assertEquals(ConnectionSupervisor.Action.None, s.tick("announcing", 0L, 0L, 60_000L, false))
        assertTrue(s.tick("announcing", 0L, 0L, 120_000L, false) is ConnectionSupervisor.Action.Rebuild)
        s.rebuildAdmitted()
        // CONNACK already proved this family. With no announcement boundary available the wedge waits;
        // it must never be turned into an address-family flip, which would abandon a working route.
        repeat(5) { tick ->
            assertTrue(
                s.tick("announcing", 0L, 0L, 420_000L + tick * 300_000L, false) is
                    ConnectionSupervisor.Action.SkipRebuild,
            )
        }
    }

    @Test fun `restored family gets full grace before one bounded alternate attempt`() {
        val s = supervisor()
        assertEquals(ConnectionSupervisor.Action.None, s.tick(
            "connecting", 0L, 0L, 60_000L, false,
            holdSelectedFamily = true,
        ))
        assertEquals(ConnectionSupervisor.Action.Rebuild("state", flipFamily = false), s.tick(
            "connecting", 0L, 0L, 120_000L, false,
            holdSelectedFamily = true,
        ))
        s.rebuildAdmitted()
        assertTrue(s.tick(
            "unreachable", 0L, 0L, 419_999L, false,
            holdSelectedFamily = true,
        ) is ConnectionSupervisor.Action.SkipRebuild)
        assertEquals(ConnectionSupervisor.Action.Rebuild("state", flipFamily = true), s.tick(
            "unreachable", 0L, 0L, 420_000L, false,
            holdSelectedFamily = true,
        ))
        s.rebuildAdmitted()
        // The restored route has now had its grace window and one alternate. A still-unreachable
        // never-live runtime keeps alternating at the abandon cadence rather than going silent.
        assertTrue(s.tick(
            "unreachable", 0L, 0L, 480_000L, false,
            holdSelectedFamily = false,
        ) is ConnectionSupervisor.Action.SkipRebuild)
        assertEquals(ConnectionSupervisor.Action.Rebuild("state", flipFamily = true), s.tick(
            "unreachable", 0L, 0L, 720_000L, false,
            holdSelectedFamily = false,
        ))
    }

    @Test fun autoDiscoveryWaitIsRetriedByTheWatchdog() {
        val s = supervisor()
        assertEquals(ConnectionSupervisor.Action.None, s.tick("discovering", 0L, 0L, 1_000L, false))
        val action = s.tick("discovering", 0L, 0L, 2_000L, false)
        assertEquals(ConnectionSupervisor.Action.Rebuild("discovery", flipFamily = false), action)

        // A missed mDNS result creates no Hive client/automatic retry. The same runtime must remain able
        // to discover HA when it appears later, without a process restart or address-family mutation.
        assertEquals(ConnectionSupervisor.Action.None, s.tick("discovering", 0L, 0L, 3_000L, false))
        assertEquals(
            ConnectionSupervisor.Action.Rebuild("discovery", flipFamily = false),
            s.tick("discovering", 0L, 0L, 4_000L, false),
        )
    }

    @Test fun `slow discovery mutation is never stacked and does not consume later retry`() {
        val s = supervisor()
        assertEquals(ConnectionSupervisor.Action.None, s.tick("discovering", 0L, 0L, 1_000L, true))
        assertEquals(
            ConnectionSupervisor.Action.SkipRebuild("discovery", 0L),
            s.tick("discovering", 0L, 0L, 2_000L, true),
        )
        assertEquals(ConnectionSupervisor.Action.None, s.tick("discovering", 0L, 0L, 3_000L, false))
        assertEquals(
            ConnectionSupervisor.Action.Rebuild("discovery", flipFamily = false),
            s.tick("discovering", 0L, 0L, 4_000L, false),
        )
    }

    @Test fun connectedResetsTheStuckCounter() {
        val s = supervisor()
        s.tick("reconnecting", 0L, 0L, 1_000L, false) // stuck tick 1
        s.tick("connected", 0L, 0L, 2_000L, false)    // reset
        // only one stuck tick again -> not yet a rebuild
        assertEquals(ConnectionSupervisor.Action.None, s.tick("reconnecting", 0L, 0L, 3_000L, false))
    }

    @Test fun `post-connected outage crosses one process boundary but never becomes a restart loop`() {
        val beforeRestart = supervisor()
        assertTrue(beforeRestart.tick(
            "connected", 1_000L, stale + 1, 500_000L, false,
            connectionGeneration = 9L,
        ) is ConnectionSupervisor.Action.Rebuild)
        beforeRestart.rebuildAdmitted()
        assertTrue(beforeRestart.tick(
            "connecting", 1_000L, stale + 299_999L, 799_999L, false,
            connectionGeneration = 9L,
        ) is ConnectionSupervisor.Action.SkipRebuild)
        assertEquals(ConnectionSupervisor.Action.ProcessRecovery("liveness-no-progress"), beforeRestart.tick(
            "connecting", 1_000L, stale + 300_000L, 800_000L, false,
            connectionGeneration = 9L,
        ))

        // A clean process has no historical ACK baseline. Its restored route gets one full grace before
        // one alternate-family attempt, but it cannot schedule periodic process restarts.
        val afterRestart = supervisor()
        assertEquals(ConnectionSupervisor.Action.None, afterRestart.tick(
            "connecting", 0L, 0L, 60_000L, false,
            holdSelectedFamily = true,
        ))
        assertEquals(ConnectionSupervisor.Action.Rebuild("state", flipFamily = false), afterRestart.tick(
            "connecting", 0L, 0L, 120_000L, false,
            holdSelectedFamily = true,
        ))
        afterRestart.rebuildAdmitted()
        assertEquals(ConnectionSupervisor.Action.Rebuild("state", flipFamily = true), afterRestart.tick(
            "unreachable", 0L, 0L, 420_000L, false,
            holdSelectedFamily = true,
        ))
        afterRestart.rebuildAdmitted()
        assertTrue(afterRestart.tick(
            "unreachable", 0L, 0L, 480_000L, false,
        ) is ConnectionSupervisor.Action.SkipRebuild)
        // The recreated process has still never been application-ready, so it never crosses another
        // process boundary. It does keep alternating its unproven address family at the abandon
        // cadence: permanent silence here is what left a panel dialling a black-holed family for hours.
        var now = 720_000L
        repeat(10) {
            assertEquals(
                ConnectionSupervisor.Action.Rebuild("state", flipFamily = true),
                afterRestart.tick("unreachable", 0L, 0L, now, false),
            )
            afterRestart.rebuildAdmitted()
            now += 300_000L
        }
    }

    @Test fun `fresh announcing wedge spends one durable process boundary despite transport ACKs`() {
        val firstProcess = supervisor()
        assertEquals(ConnectionSupervisor.Action.None, firstProcess.tick(
            "announcing", 1_000L, 1L, 60_000L, false,
            connectionGeneration = 41L,
            applicationReadyEver = false,
            announcementBoundaryAvailable = true,
        ))
        assertEquals(ConnectionSupervisor.Action.Rebuild("state", flipFamily = true), firstProcess.tick(
            "announcing", 2_000L, 1L, 120_000L, false,
            connectionGeneration = 42L,
            applicationReadyEver = false,
            announcementBoundaryAvailable = true,
        ))
        firstProcess.rebuildAdmitted()
        assertEquals(
            ConnectionSupervisor.Action.ProcessRecovery(
                "state-announcement-no-progress",
                consumeAnnouncementBudget = true,
            ),
            firstProcess.tick(
                "announcing", 3_000L, 1L, 420_000L, false,
                connectionGeneration = 43L,
                applicationReadyEver = false,
                announcementBoundaryAvailable = true,
            ),
        )

        // Recreated process sees the durable token consumed. CONNACK/heartbeat do not reopen it.
        val replacementProcess = supervisor()
        assertEquals(ConnectionSupervisor.Action.None, replacementProcess.tick(
            "announcing", 4_000L, 1L, 60_000L, false,
            connectionGeneration = 51L,
            holdSelectedFamily = true,
            applicationReadyEver = false,
            announcementBoundaryAvailable = false,
        ))
        assertEquals(ConnectionSupervisor.Action.Rebuild("state", flipFamily = false), replacementProcess.tick(
            "announcing", 5_000L, 1L, 120_000L, false,
            connectionGeneration = 52L,
            holdSelectedFamily = true,
            applicationReadyEver = false,
            announcementBoundaryAvailable = false,
        ))
        replacementProcess.rebuildAdmitted()
        assertEquals(ConnectionSupervisor.Action.Rebuild("state", flipFamily = true), replacementProcess.tick(
            "announcing", 6_000L, 1L, 420_000L, false,
            connectionGeneration = 53L,
            holdSelectedFamily = true,
            applicationReadyEver = false,
            announcementBoundaryAvailable = false,
        ))
        replacementProcess.rebuildAdmitted()
        assertTrue(replacementProcess.tick(
            "announcing", 7_000L, 1L, 3_720_000L, false,
            connectionGeneration = 54L,
            applicationReadyEver = false,
            announcementBoundaryAvailable = false,
        ) is ConnectionSupervisor.Action.SkipRebuild)
    }

    @Test fun `formerly ready runtime also spends announcement boundary before process recovery`() {
        val s = supervisor()
        // Establish sticky application readiness, then model a later reconnect stuck announcing.
        assertEquals(ConnectionSupervisor.Action.None, s.tick(
            "connected", 1_000L, 1L, 10_000L, false,
            connectionGeneration = 7L,
            applicationReadyEver = true,
            announcementBoundaryAvailable = true,
        ))
        assertEquals(ConnectionSupervisor.Action.None, s.tick(
            "announcing", 2_000L, 1L, 60_000L, false,
            connectionGeneration = 8L,
            applicationReadyEver = true,
            announcementBoundaryAvailable = true,
        ))
        assertTrue(s.tick(
            "announcing", 3_000L, 1L, 120_000L, false,
            connectionGeneration = 9L,
            applicationReadyEver = true,
            announcementBoundaryAvailable = true,
        ) is ConnectionSupervisor.Action.Rebuild)
        s.rebuildAdmitted()
        assertEquals(
            ConnectionSupervisor.Action.ProcessRecovery(
                "state-announcement-no-progress",
                consumeAnnouncementBudget = true,
            ),
            s.tick(
                "announcing", 4_000L, 1L, 420_000L, false,
                connectionGeneration = 10L,
                applicationReadyEver = true,
                announcementBoundaryAvailable = true,
            ),
        )
    }

    @Test fun `new CONNACK cannot prove replacement until announcement readiness`() {
        val s = supervisor()
        assertTrue(s.tick(
            "connected", 1_000L, stale + 1, 500_000L, false,
            connectionGeneration = 41L,
            applicationReadyEver = true,
        ) is ConnectionSupervisor.Action.Rebuild)
        s.rebuildAdmitted()

        assertTrue(s.tick(
            "announcing", 2_000L, 1L, 500_010L, false,
            connectionGeneration = 42L,
            applicationReadyEver = true,
        ) is ConnectionSupervisor.Action.SkipRebuild)
        assertEquals(ConnectionSupervisor.Action.ProcessRecovery("liveness-no-progress"), s.tick(
            "connecting", 2_000L, stale + abandon, 800_000L, false,
            connectionGeneration = 42L,
            applicationReadyEver = true,
        ))
    }
}
