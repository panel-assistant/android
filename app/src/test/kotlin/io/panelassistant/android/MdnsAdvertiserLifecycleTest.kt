package io.panelassistant.android

import io.panelassistant.android.util.RetirableMutationGate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MdnsAdvertiserLifecycleTest {
    @Test fun ordinaryStopDuringProductionRecoveryTransactionCannotResurrectResponder() {
        val topology = MdnsTopology()
        val ticket = topology.request("192.0.2.109")
        val gate = RetirableMutationGate()
        val teardownEntered = CountDownLatch(1)
        val releaseTeardown = CountDownLatch(1)
        val activeIp = AtomicReference<String?>(ticket.lanIp)
        val executor = Executors.newFixedThreadPool(2)
        val recovery = executor.submit<MdnsRecoveryOutcome> {
            runMdnsRecoveryTransaction(
                gate = gate,
                admitted = { topology.matches(ticket.epoch, ticket.lanIp) },
                teardown = {
                    activeIp.set(null)
                    teardownEntered.countDown()
                    releaseTeardown.await(2, TimeUnit.SECONDS)
                },
                restart = {
                    gate.runIfOpen(false) {
                        if (!topology.matches(ticket.epoch, ticket.lanIp)) false
                        else { activeIp.set(ticket.lanIp); true }
                    }
                },
                stillCurrent = { topology.matches(ticket.epoch, ticket.lanIp) },
            )
        }
        assertTrue(teardownEntered.await(1, TimeUnit.SECONDS))
        topology.stop()
        val stop = executor.submit { gate.runExclusive { activeIp.set(null) } }
        releaseTeardown.countDown()

        assertEquals(MdnsRecoveryOutcome.SUPERSEDED, recovery.get(2, TimeUnit.SECONDS))
        stop.get(2, TimeUnit.SECONDS)
        assertEquals(null, activeIp.get())
        executor.shutdownNow()
    }

    @Test fun newIpRebindWinsAgainstProductionOldIpRecoveryTransaction() {
        val topology = MdnsTopology()
        val old = topology.request("192.0.2.109")
        val gate = RetirableMutationGate()
        val teardownEntered = CountDownLatch(1)
        val releaseTeardown = CountDownLatch(1)
        val activeIp = AtomicReference<String?>(old.lanIp)
        val executor = Executors.newFixedThreadPool(2)
        val recovery = executor.submit<MdnsRecoveryOutcome> {
            runMdnsRecoveryTransaction(
                gate = gate,
                admitted = { topology.matches(old.epoch, old.lanIp) },
                teardown = {
                    activeIp.set(null)
                    teardownEntered.countDown()
                    releaseTeardown.await(2, TimeUnit.SECONDS)
                },
                restart = {
                    gate.runIfOpen(false) {
                        if (!topology.matches(old.epoch, old.lanIp)) false
                        else { activeIp.set(old.lanIp); true }
                    }
                },
                stillCurrent = { topology.matches(old.epoch, old.lanIp) },
            )
        }
        assertTrue(teardownEntered.await(1, TimeUnit.SECONDS))
        val replacement = topology.request("192.0.2.110")
        val rebind = executor.submit {
            gate.runExclusive { activeIp.set(replacement.lanIp) }
        }
        releaseTeardown.countDown()

        assertEquals(MdnsRecoveryOutcome.SUPERSEDED, recovery.get(2, TimeUnit.SECONDS))
        rebind.get(2, TimeUnit.SECONDS)
        assertEquals("192.0.2.110", activeIp.get())
        executor.shutdownNow()
    }

    @Test fun duplicateTopologyCallbackPreservesEpochButStopAndRebindInvalidateIt() {
        val topology = MdnsTopology()
        val first = topology.request("192.0.2.109")
        val duplicate = topology.request("192.0.2.109")

        assertTrue(first.changed)
        assertFalse(duplicate.changed)
        assertEquals(first.epoch, duplicate.epoch)
        assertTrue(topology.matches(first.epoch, "192.0.2.109"))

        val rebound = topology.request("192.0.2.110")
        assertTrue(rebound.changed)
        assertFalse(topology.matches(first.epoch, "192.0.2.109"))
        topology.stop()
        assertFalse(topology.matches(rebound.epoch, "192.0.2.110"))
    }

    @Test fun stoppedOrReboundAdvertiserRejectsItsQueuedRecovery() {
        assertFalse(mdnsRecoveryStillCurrent(4, 5, "192.0.2.109", "192.0.2.109", 7, 7, true))
        assertFalse(mdnsRecoveryStillCurrent(4, 4, "192.0.2.109", "192.0.2.110", 7, 7, true))
        assertFalse(mdnsRecoveryStillCurrent(4, 4, "192.0.2.109", "192.0.2.109", 7, 8, true))
        assertFalse(mdnsRecoveryStillCurrent(4, 4, "192.0.2.109", "192.0.2.109", 7, 7, false))
        assertTrue(mdnsRecoveryStillCurrent(4, 4, "192.0.2.109", "192.0.2.109", 7, 7, true))
    }

    @Test fun topologyChangeInvalidatesOldInterfaceWithoutSpendingNewBudget() {
        val policy = MdnsLivenessPolicy(deadSweeps = 1)
        val old = checkNotNull(policy.observeSelf(false, 0))
        assertEquals(1, policy.snapshot(0).recoveryAttempts)

        policy.onStarted(resetBudget = true)

        assertFalse(policy.isCurrent(old.token))
        assertEquals(0, policy.snapshot(0).recoveryAttempts)
        assertEquals(0L, checkNotNull(policy.observeSelf(false, 1)).delayMs)
        assertEquals(1, policy.snapshot(1).recoveryAttempts)
    }

    @Test fun cancelledBackoffDoesNotBlockFreshRecovery() {
        val scheduler = LatestScheduledTask("mdns-recovery-test")
        val staleRan = CountDownLatch(1)
        val freshRan = CountDownLatch(1)
        scheduler.schedule({ staleRan.countDown() }, 5_000)
        scheduler.cancel()
        scheduler.schedule({ freshRan.countDown() }, 0)

        assertTrue(freshRan.await(1, TimeUnit.SECONDS))
        assertEquals(1L, staleRan.count)
        assertTrue(scheduler.closeAndJoin(1_000))
    }

    @Test fun rejectedStaleSubmissionCannotDisplaceCurrentScheduledRecovery() {
        val scheduler = LatestScheduledTask("mdns-schedule-admission-test")
        val currentRan = CountDownLatch(1)
        val staleRan = CountDownLatch(1)
        scheduler.schedule({ currentRan.countDown() }, 50)

        assertFalse(scheduler.scheduleIf({ false }, { staleRan.countDown() }, 0))

        assertTrue(currentRan.await(1, TimeUnit.SECONDS))
        assertEquals(1L, staleRan.count)
        assertTrue(scheduler.closeAndJoin(1_000))
    }

    @Test fun cancelledRunningTaskDoesNotBlockFreshRecovery() {
        val scheduler = LatestScheduledTask("mdns-running-recovery-test")
        val staleStarted = CountDownLatch(1)
        val freshRan = CountDownLatch(1)
        scheduler.schedule({
            staleStarted.countDown()
            try { CountDownLatch(1).await() } catch (_: InterruptedException) { }
        }, 0)
        assertTrue(staleStarted.await(1, TimeUnit.SECONDS))

        scheduler.cancel()
        scheduler.schedule({ freshRan.countDown() }, 0)

        assertTrue(freshRan.await(1, TimeUnit.SECONDS))
        assertTrue(scheduler.closeAndJoin(1_000))
    }

    @Test fun retirementCleansAnInFlightStartAndRejectsLateRecovery() {
        val gate = RetirableMutationGate()
        val startEntered = CountDownLatch(1)
        val releaseStart = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        var activeAdvertisements = 0

        val start = executor.submit<Boolean> {
            gate.runIfOpen(false) {
                startEntered.countDown()
                assertTrue(releaseStart.await(2, TimeUnit.SECONDS))
                activeAdvertisements++
                true
            }
        }
        assertTrue(startEntered.await(2, TimeUnit.SECONDS))
        gate.closeAdmission()
        val retire = executor.submit {
            gate.runExclusive { activeAdvertisements = 0 }
        }

        assertFalse(gate.runIfOpen(false) { activeAdvertisements++; true })

        releaseStart.countDown()
        assertTrue(start.get(2, TimeUnit.SECONDS))
        retire.get(2, TimeUnit.SECONDS)
        assertEquals(0, activeAdvertisements)

        assertFalse(gate.runIfOpen(false) { activeAdvertisements++; true })
        assertEquals(0, activeAdvertisements)
        executor.shutdownNow()
    }

    @Test fun ordinaryStopRemainsRestartableForCurrentRuntimeRecovery() {
        val gate = RetirableMutationGate()
        var activeAdvertisements = 0

        assertTrue(gate.runIfOpen(false) { activeAdvertisements++; true })
        gate.runExclusive { activeAdvertisements = 0 }
        assertTrue(gate.runIfOpen(false) { activeAdvertisements++; true })

        assertEquals(1, activeAdvertisements)
    }
}
