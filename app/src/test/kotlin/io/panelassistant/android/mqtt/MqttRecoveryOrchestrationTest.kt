package io.panelassistant.android.mqtt

import io.panelassistant.android.util.ServiceRuntimeOwner
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Composition regression for the complete watchdog recovery boundary, including the real runtime owner. */
class MqttRecoveryOrchestrationTest {
    @Test fun delayedOwnerOldProgressProcessBoundaryAndRetainedRouteCompose() {
        val store = FamilyStore()
        val firstOwner = ServiceRuntimeOwner(FakeRuntime(store.preference()), "mqtt-recovery-compose-1")
        val supervisor = ConnectionSupervisor(STALE_MS, ABANDON_MS)
        val workerEntered = CountDownLatch(1)
        val releaseWorker = CountDownLatch(1)

        try {
            assertTrue(firstOwner.start { runtime ->
                runtime.family.select(BROKER)
                runtime.connected(lastOkMs = 1_000L, connectionGeneration = 41L)
            }.get(2, TimeUnit.SECONDS))
            val staleObservation = firstOwner.observe()!!
            assertEquals(1L, staleObservation.generation)

            // A real runtime replacement advances the owner generation. Its stale observation cannot
            // admit recovery work; the watchdog uses the exact replacement observation below.
            assertTrue(firstOwner.reconfigure(
                retire = {},
                build = { FakeRuntime(store.preference()) },
                start = { runtime ->
                    runtime.family.select(BROKER)
                    runtime.connected(lastOkMs = 1_000L, connectionGeneration = 41L)
                },
            ).get(2, TimeUnit.SECONDS))
            assertFalse(firstOwner.reconnect(staleObservation) {}.get(2, TimeUnit.SECONDS))
            val observed = firstOwner.observe()!!
            assertEquals(2L, observed.generation)

            val fallback = tick(supervisor, observed, now = 500_000L, sinceOkMs = STALE_MS + 1)
            assertEquals(ConnectionSupervisor.Action.Rebuild("liveness", flipFamily = true), fallback)
            observed.value.stage(fallback as ConnectionSupervisor.Action.Rebuild)
            assertTrue(observed.value.family.preferIpv4)
            assertEquals(true, store.ipv4For(BROKER))
            val submitted = firstOwner.reconnect(observed) { runtime ->
                workerEntered.countDown()
                assertTrue(releaseWorker.await(2, TimeUnit.SECONDS))
                runtime.rebuild()
            }
            assertTrue(workerEntered.await(2, TimeUnit.SECONDS))

            // Both a late PUBACK and an old-client automatic reconnect happen while the owner worker is
            // delayed. Neither can prove the not-yet-admitted fallback, despite timestamp/generation moves.
            observed.value.connected(lastOkMs = 1_100L, connectionGeneration = 41L)
            assertTrue(tick(supervisor, observed, 500_010L, 1L, rebuildInFlight = true)
                is ConnectionSupervisor.Action.SkipRebuild)
            observed.value.connected(lastOkMs = 1_200L, connectionGeneration = 42L)
            assertTrue(tick(supervisor, observed, 500_020L, 1L, rebuildInFlight = true)
                is ConnectionSupervisor.Action.SkipRebuild)

            releaseWorker.countDown()
            assertTrue(submitted.get(2, TimeUnit.SECONDS))
            supervisor.rebuildAdmitted()
            assertTrue(observed.value.family.preferIpv4)
            assertEquals(true, store.ipv4For(BROKER))
            assertTrue(tick(supervisor, observed, 500_030L, 30L)
                is ConnectionSupervisor.Action.SkipRebuild)
            assertEquals(
                ConnectionSupervisor.Action.ProcessRecovery("liveness-no-progress"),
                tick(supervisor, observed, 800_000L, STALE_MS + ABANDON_MS),
            )
        } finally {
            releaseWorker.countDown()
            firstOwner.shutdown(2_000) {}
        }

        // A clean process restores the durable IPv4 route. The two-tick state watchdog rebuilds that
        // same route and preserves it for the full progress bound instead of flipping back after 2m.
        val secondOwner = ServiceRuntimeOwner(FakeRuntime(store.preference()), "mqtt-recovery-compose-2")
        val afterBoundary = ConnectionSupervisor(STALE_MS, ABANDON_MS)
        try {
            assertTrue(secondOwner.start { it.family.select(BROKER) }.get(2, TimeUnit.SECONDS))
            val restored = secondOwner.observe()!!
            assertTrue(restored.value.family.preferIpv4)
            assertTrue(restored.value.family.awaitingProgress)
            assertEquals(ConnectionSupervisor.Action.None, tick(afterBoundary, restored, 60_000L, 0L))
            val retained = tick(afterBoundary, restored, 120_000L, 0L)
            assertEquals(ConnectionSupervisor.Action.Rebuild("state", flipFamily = false), retained)
            assertTrue(secondOwner.reconnect(restored) {
                it.stage(retained as ConnectionSupervisor.Action.Rebuild)
                it.rebuild()
            }.get(2, TimeUnit.SECONDS))
            afterBoundary.rebuildAdmitted()
            assertTrue(tick(afterBoundary, restored, 419_999L, 0L)
                is ConnectionSupervisor.Action.SkipRebuild)
            assertTrue(restored.value.family.preferIpv4)

            // A prolonged outage reaches the existing five-minute progress bound, then admits exactly
            // one alternate-family rebuild through the real owner and persists that selection.
            val alternate = tick(afterBoundary, restored, 420_000L, 0L)
            assertEquals(ConnectionSupervisor.Action.Rebuild("state", flipFamily = true), alternate)
            restored.value.stage(alternate as ConnectionSupervisor.Action.Rebuild)
            assertFalse(restored.value.family.preferIpv4)
            assertEquals(false, store.ipv4For(BROKER))
            assertTrue(secondOwner.reconnect(restored) {
                it.rebuild()
            }.get(2, TimeUnit.SECONDS))
            afterBoundary.rebuildAdmitted()
            assertFalse(restored.value.family.preferIpv4)
            assertFalse(restored.value.family.awaitingProgress)
            assertEquals(false, store.ipv4For(BROKER))

            // Baseline zero never turns this into a process restart loop. The alternate is held for a
            // full progress window, and while the family stays unproven the next window alternates
            // again rather than going silent on a dead route. Broker progress ends the epoch normally.
            assertTrue(tick(afterBoundary, restored, 480_000L, 0L)
                is ConnectionSupervisor.Action.SkipRebuild)
            val secondAlternate = tick(afterBoundary, restored, 720_000L, 0L)
            assertEquals(ConnectionSupervisor.Action.Rebuild("state", flipFamily = true), secondAlternate)
            restored.value.stage(secondAlternate as ConnectionSupervisor.Action.Rebuild)
            assertTrue(restored.value.family.preferIpv4)
            assertEquals(true, store.ipv4For(BROKER))
            afterBoundary.rebuildAdmitted()
            restored.value.connected(lastOkMs = 3_720_001L, connectionGeneration = 81L)
            assertEquals(ConnectionSupervisor.Action.None, tick(afterBoundary, restored, 3_720_002L, 1L))
        } finally {
            secondOwner.shutdown(2_000) {}
        }
    }

    @Test fun stagedRouteSurvivesWhenTheRuntimeRecoveryCallbackNeverEnters() {
        val store = FamilyStore()
        val owner = ServiceRuntimeOwner(FakeRuntime(store.preference()), "mqtt-recovery-never-entered")
        val blockersEntered = CountDownLatch(ServiceRuntimeOwner.MAX_RECOVERY_WORKERS)
        val releaseBlockers = CountDownLatch(1)
        val blockerFutures = mutableListOf<java.util.concurrent.Future<Boolean>>()

        try {
            assertTrue(owner.start { it.family.select(BROKER) }.get(2, TimeUnit.SECONDS))
            val observed = owner.observe()!!
            repeat(ServiceRuntimeOwner.MAX_RECOVERY_WORKERS) {
                blockerFutures += owner.reconnect(observed) {
                    blockersEntered.countDown()
                    releaseBlockers.await(2, TimeUnit.SECONDS)
                }
            }
            assertTrue(blockersEntered.await(2, TimeUnit.SECONDS))

            val fallback = ConnectionSupervisor.Action.Rebuild("liveness", flipFamily = true)
            observed.value.stage(fallback)
            assertEquals(true, store.ipv4For(BROKER))

            val callbackEntered = AtomicBoolean(false)
            val rejected = owner.reconnect(observed) {
                callbackEntered.set(true)
                it.rebuild()
            }
            assertFalse(rejected.get(2, TimeUnit.SECONDS))
            assertFalse(callbackEntered.get())

            // This is the controlled process boundary: a new bridge restores the already-staged route
            // even though the old owner's transport mutation never began.
            val replacementProcess = store.preference()
            assertTrue(replacementProcess.select(BROKER))
            assertTrue(replacementProcess.awaitingProgress)
        } finally {
            releaseBlockers.countDown()
            blockerFutures.forEach { runCatching { it.get(2, TimeUnit.SECONDS) } }
            owner.shutdown(2_000) {}
        }
    }

    private class FakeRuntime(val family: MqttFamilyPreference) {
        @Volatile var state = "connecting"
        @Volatile var lastOkMs = 0L
        @Volatile var connectionGeneration: Long? = null
        @Volatile var connectAttempt = 1L
        @Volatile var applicationReadyEver = false

        fun connected(lastOkMs: Long, connectionGeneration: Long) {
            family.markBrokerProgress()
            this.lastOkMs = lastOkMs
            this.connectionGeneration = connectionGeneration
            applicationReadyEver = true
            state = "connected"
        }

        fun stage(action: ConnectionSupervisor.Action.Rebuild) {
            if (action.flipFamily) family.stageAlternate(BROKER, connectAttempt)
            else family.select(BROKER)
        }

        fun rebuild() {
            connectAttempt++
            connectionGeneration = null
            state = "connecting"
        }
    }

    private class FamilyStore {
        private var broker: String? = null
        private var preferIpv4 = false

        fun preference() = MqttFamilyPreference(::load, ::persist, ::clear)

        @Synchronized fun ipv4For(identity: String): Boolean? =
            preferIpv4.takeIf { broker == identity }

        @Synchronized private fun load(identity: String): Boolean? = ipv4For(identity)

        @Synchronized private fun persist(identity: String, ipv4: Boolean): Boolean {
            broker = identity
            preferIpv4 = ipv4
            return true
        }

        @Synchronized private fun clear(): Boolean {
            broker = null
            preferIpv4 = false
            return true
        }
    }

    private fun tick(
        supervisor: ConnectionSupervisor,
        observed: ServiceRuntimeOwner.Observation<FakeRuntime>,
        now: Long,
        sinceOkMs: Long,
        rebuildInFlight: Boolean = false,
    ): ConnectionSupervisor.Action = supervisor.tick(
        state = observed.value.state,
        lastOkMs = observed.value.lastOkMs,
        sinceOkMs = sinceOkMs,
        now = now,
        rebuildInFlight = rebuildInFlight,
        runtimeGeneration = observed.generation,
        connectionGeneration = observed.value.connectionGeneration,
        holdSelectedFamily = observed.value.family.awaitingProgress,
        applicationReadyEver = observed.value.applicationReadyEver,
    )

    private companion object {
        const val BROKER = "tcp://broker:1883"
        const val STALE_MS = 150_000L
        const val ABANDON_MS = 300_000L
    }
}
