package io.panelassistant.android

import io.panelassistant.android.mqtt.MqttConnectionLease
import io.panelassistant.android.mqtt.MqttConnectionGeneration
import io.panelassistant.android.mqtt.MqttFamilyPreference
import io.panelassistant.android.util.LatestDispatcher
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MqttRecoveryAuthorityTest {
    @Test fun `transport connected event records CONNACK before announcing is observable`() {
        val authority = MqttRecoveryAuthority(
            initialState = "connecting",
            initialProgress = MqttBrokerProgress(0L, null),
        )
        val generations = MqttConnectionGeneration()
        val completed = CountDownLatch(1)
        val observed = java.util.concurrent.atomic.AtomicReference<MqttRecoverySnapshot>()
        lateinit var dispatcher: MqttConnectionEventDispatcher
        dispatcher = MqttConnectionEventDispatcher { sequenced ->
            if (!dispatcher.isCurrent(sequenced)) return@MqttConnectionEventDispatcher
            val event = sequenced.event as MqttConnectionEvent.Connected
            observed.set(
                recordMqttConnack(
                    authority = authority,
                    connectionGeneration = generations.advance(),
                    applicationReadyEver = false,
                    addressFamily = event.addressFamily,
                    nowMs = 42L,
                ),
            )
            completed.countDown()
        }
        try {
            dispatcher.submit(
                MqttConnectionEvent.Connected(MqttConnectionLease(), MqttAddressFamily.IPV4),
            )
            assertTrue(completed.await(2, TimeUnit.SECONDS))
            val snapshot = observed.get()
            assertEquals("announcing", snapshot.state)
            assertEquals(42L, snapshot.brokerProgress.lastOkMs)
            assertEquals(snapshot.connectionGeneration, snapshot.brokerProgress.connectionGeneration)
            assertEquals(MqttAddressFamily.IPV4, snapshot.addressFamily)
        } finally {
            dispatcher.closeAndJoin(2_000)
        }
    }

    @Test fun `installed build change opens a new announcement recovery epoch`() {
        fun identity(versionCode: Int) = mqttAnnouncementRecoveryIdentity(
            brokerIdentity = "tcp://broker:1883",
            panelId = "test-panel",
            profileIdentity = "panel.example@revision",
            user = "panel",
            password = "secret",
            buildVersionCode = versionCode,
        )

        assertNotEquals(identity(288), identity(289))
        assertEquals(identity(289), identity(289))
    }

    @Test fun `online and state acknowledgements must belong to one exact announcement`() {
        val readiness = MqttAnnouncementReadiness()
        val current = MqttConnectAnnouncement(7L, MqttConnectionLease())
        val stale = MqttConnectAnnouncement(6L, MqttConnectionLease())

        readiness.begin(current)
        assertNull(readiness.acknowledgeOnline(current))
        assertNull(readiness.acknowledgeState(stale.generation))
        assertEquals(current, readiness.acknowledgeState(current.generation))
        assertNull(readiness.acknowledgeOnline(current))

        readiness.begin(stale)
        assertNull(readiness.acknowledgeState(stale.generation))
        assertEquals(stale, readiness.acknowledgeOnline(stale))
    }

    @Test fun `watchdog ticket is invalidated by readiness before stage or owner entry`() {
        val authority = authority("announcing", 41L, readyEver = false)
        val staleSnapshot = authority.snapshot()
        assertTrue(authority.isCurrent(authority.ticket(staleSnapshot)))

        authority.updateProgress(MqttBrokerProgress(10_000L, 42L))
        authority.updateLifecycle("connected", 42L, applicationReadyEver = true)
        // Ticket construction happens after readiness but deliberately uses the exact stale watchdog
        // snapshot. It must not silently resample and pair stale state with healthy authority.
        val observed = authority.ticket(staleSnapshot)

        assertFalse(authority.isCurrent(observed))
        assertEquals(
            MqttRecoveryAuthority.Claim.STALE_SAME_ATTEMPT,
            authority.claim(observed),
        )
    }

    @Test fun `dead-family reconnect churn cannot cancel the staged alternate-family recovery`() {
        var storedIpv4: Boolean? = null
        val family = MqttFamilyPreference(
            load = { null },
            persist = { _, ipv4 -> storedIpv4 = ipv4; true },
            clear = { true },
        )
        val authority = MqttRecoveryAuthority(
            initialState = "connecting",
            initialProgress = MqttBrokerProgress(0L, null),
        )
        val attempt = authority.beginConnectAttempt("connecting", null)
        assertTrue(authority.updateAddressFamily(attempt, MqttAddressFamily.IPV6))
        assertFalse(family.selectForConnect("tcp://broker:1883", attempt))

        // The watchdog observes, then stages the alternate family for this exact attempt.
        val observed = authority.ticket(authority.snapshot(), "tcp://broker:1883")
        assertTrue(family.stageAlternate("tcp://broker:1883", attempt).durable)
        assertTrue(family.preferIpv4)

        // A black-holed family disconnects roughly every socket-connect bound, and each disconnect
        // republishes lifecycle state while the owner's recovery worker is still queued. That churn is
        // NOT recovery: it must not consume the ticket, or the staged IPv4 route is rolled back and the
        // panel dials the same dead AAAA forever.
        repeat(30) {
            authority.updateLifecycle("unreachable", null, applicationReadyEver = false)
            authority.updateLifecycle("connecting", null, applicationReadyEver = false)
        }

        assertTrue(authority.isCurrent(observed))
        assertEquals(MqttRecoveryAuthority.Claim.CLAIMED, authority.claim(observed))
        assertTrue(family.preferIpv4)
        assertEquals(true, storedIpv4)
    }

    @Test fun `late same-session PubAck wins against queued detach`() {
        val authority = authority("connected", 41L, readyEver = true)
        val observed = authority.ticket(authority.snapshot())

        authority.updateProgress(MqttBrokerProgress(10_001L, 41L))

        assertEquals(
            MqttRecoveryAuthority.Claim.STALE_SAME_ATTEMPT,
            authority.claim(observed),
        )
    }

    @Test fun `newer fresh start consumes stage and forbids old-ticket rollback`() {
        val authority = authority("announcing", 41L, readyEver = false)
        val observed = authority.ticket(
            authority.snapshot(),
            stagedBrokerIdentity = "tcp://broker:1883",
        )

        assertEquals(8L, authority.beginConnectAttempt("connecting", null))

        assertEquals(
            MqttRecoveryAuthority.Claim.CONSUMED_BY_NEW_ATTEMPT,
            authority.claim(observed),
        )
    }

    @Test fun `selected address family follows the fresh client lifecycle atomically`() {
        val authority = MqttRecoveryAuthority(
            initialState = "disabled",
            initialProgress = MqttBrokerProgress(0L, null),
        )
        val firstAttempt = authority.beginConnectAttempt("connecting", null)

        assertTrue(authority.updateAddressFamily(firstAttempt, MqttAddressFamily.IPV4))
        authority.updateLifecycle("unreachable", null, applicationReadyEver = false)
        assertEquals("unreachable", authority.snapshot().state)
        assertEquals(MqttAddressFamily.IPV4, authority.snapshot().addressFamily)

        val replacementAttempt = authority.beginConnectAttempt("connecting", null)
        val replacement = authority.snapshot()
        assertEquals("connecting", replacement.state)
        assertNull(replacement.addressFamily)
        assertFalse(authority.updateAddressFamily(firstAttempt, MqttAddressFamily.IPV6))

        authority.updateLifecycleWithAddressFamily(
            "announcing",
            connectionGeneration = 42L,
            applicationReadyEver = false,
            addressFamily = MqttAddressFamily.IPV6,
        )
        val connected = authority.snapshot()
        assertEquals(replacementAttempt, connected.familyConnectAttempt)
        assertEquals("announcing", connected.state)
        assertEquals(MqttAddressFamily.IPV6, connected.addressFamily)
    }

    @Test fun `claim is single use and linearizes recovery before detach`() {
        val authority = authority("announcing", 41L, readyEver = false)
        val observed = authority.ticket(authority.snapshot())

        assertEquals(MqttRecoveryAuthority.Claim.CLAIMED, authority.claim(observed))
        assertEquals(MqttRecoveryAuthority.Claim.STALE_SAME_ATTEMPT, authority.claim(observed))
    }

    @Test fun `owner rejection after late same-session PubAck rolls back unused stage`() {
        var storedIpv4 = false
        val family = MqttFamilyPreference(
            load = { null },
            persist = { _, ipv4 -> storedIpv4 = ipv4; true },
            clear = { true },
        )
        assertFalse(family.selectForConnect("tcp://broker:1883", 7L))
        assertTrue(family.stageAlternate("tcp://broker:1883", 7L).durable)

        val authority = authority("connected", 41L, readyEver = true)
        val staged = authority.ticket(
            authority.snapshot(),
            stagedBrokerIdentity = "tcp://broker:1883",
        )
        // The recovery pool rejects before callback entry, but a real PUBACK advances exact authority.
        authority.updateProgress(MqttBrokerProgress(10_001L, 41L))

        assertTrue(reconcileRejectedMqttRecovery(authority, staged) {
            family.cancelStaged("tcp://broker:1883", 7L)
        })
        assertFalse(family.preferIpv4)
        assertFalse(storedIpv4)
    }

    @Test fun `owner rejection cancels current stage before a racing PubAck`() {
        var cancelled = false
        val authority = authority("connected", 41L, readyEver = true)
        val staged = authority.ticket(authority.snapshot(), "tcp://broker:1883")

        assertTrue(reconcileRejectedMqttRecovery(authority, staged) {
            cancelled = true
            true
        })
        // A PUBACK immediately after reconciliation cannot resurrect the already-cancelled stage.
        authority.updateProgress(MqttBrokerProgress(10_002L, 41L))
        assertTrue(cancelled)
    }

    @Test fun `closed lifecycle dispatcher suppresses automatic reconnect`() {
        val dispatcher = MqttConnectionEventDispatcher { }
        dispatcher.close()
        val admission = dispatcher.submit(MqttConnectionEvent.Connected(MqttConnectionLease()))

        assertEquals(LatestDispatcher.Admission.CLOSED, admission)
        assertFalse(mqttAutomaticReconnectAllowed("unreachable", admission))
        assertFalse(mqttAutomaticReconnectAllowed("auth-failed", LatestDispatcher.Admission.ACCEPTED))
        assertTrue(mqttAutomaticReconnectAllowed("unreachable", LatestDispatcher.Admission.ACCEPTED))
    }

    @Test fun `same-attempt disconnect owns state when its connected event was conflated`() {
        val first = MqttConnectionLease()
        val replacement = MqttConnectionLease()

        assertTrue(mqttDisconnectOwnsBridgeState(activeConnection = null, disconnectedConnection = first))
        assertTrue(mqttDisconnectOwnsBridgeState(activeConnection = first, disconnectedConnection = first))
        assertFalse(mqttDisconnectOwnsBridgeState(activeConnection = first, disconnectedConnection = null))
        assertFalse(mqttDisconnectOwnsBridgeState(activeConnection = replacement, disconnectedConnection = first))
    }

    @Test fun `explicit fresh start supersedes a queued prior-client disconnect`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val evaluated = CountDownLatch(1)
        val remainedCurrent = AtomicBoolean(true)
        lateinit var dispatcher: MqttConnectionEventDispatcher
        dispatcher = MqttConnectionEventDispatcher { event ->
            entered.countDown()
            release.await(2, TimeUnit.SECONDS)
            remainedCurrent.set(dispatcher.isCurrent(event))
            evaluated.countDown()
        }

        try {
            dispatcher.submit(
                MqttConnectionEvent.Disconnected(MqttConnectionLease(), "unreachable", "old client"),
            )
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            dispatcher.supersede()
            release.countDown()
            assertTrue(evaluated.await(2, TimeUnit.SECONDS))
            assertFalse(remainedCurrent.get())
        } finally {
            release.countDown()
            dispatcher.closeAndJoin(2_000L)
        }
    }

    private fun authority(
        state: String,
        connectionGeneration: Long?,
        readyEver: Boolean,
    ) = MqttRecoveryAuthority(
        initialState = "connecting",
        initialProgress = MqttBrokerProgress(0L, null),
    ).also { authority ->
        repeat(7) { authority.beginConnectAttempt("connecting", null) }
        authority.updateLifecycle(state, connectionGeneration, readyEver)
    }
}
