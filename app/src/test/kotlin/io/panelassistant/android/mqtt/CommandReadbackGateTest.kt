package io.panelassistant.android.mqtt

import io.panelassistant.android.MqttCommandDispatcher
import io.panelassistant.android.mqttStatePayload
import io.panelassistant.android.metrics.FeatureCostOperation
import io.panelassistant.android.metrics.FeatureCostRegistry
import io.panelassistant.android.util.MonotonicDeadline
import java.util.Collections
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CommandReadbackGate — per-channel latest-generation coalescing of hardware read-backs (Issue #93).
 * The pump is manual so tests control exactly when a scheduled read-back executes relative to newer
 * commands, including while a newer command's write is still in progress.
 */
class CommandReadbackGateTest {
    // Source-text reason: pins MqttBridge.kt, deleted with the MQTT removal.

    private class ManualPump {
        private val tasks = ConcurrentLinkedQueue<() -> Unit>()
        fun schedule(task: () -> Unit) { tasks.add(task) }
        fun drain() {
            while (true) { (tasks.poll() ?: return).invoke() }
        }
    }

    private fun registry() = FeatureCostRegistry(
        wallNanos = { 0L },
        threadCpuNanos = { -1L },
        threadId = { 1L },
    )

    private fun readbackOperation(costs: FeatureCostRegistry): JSONObject {
        val operations = JSONObject(costs.json()).getJSONArray("operations")
        return (0 until operations.length()).asSequence()
            .map(operations::getJSONObject)
            .first { it.getString("id") == FeatureCostOperation.RELAY_READBACK.id }
    }

    @Test fun aBurstRunsExactlyOneReadbackForTheNewestGeneration() {
        val pump = ManualPump()
        val readbacks = mutableListOf<String>()
        val costs = registry()
        val gate = CommandReadbackGate(pump::schedule, { key, _ -> readbacks += key }, costs)

        assertTrue(gate.command("relay1") { true })   // ON
        assertTrue(gate.command("relay1") { true })   // OFF
        assertTrue(gate.command("relay1") { true })   // ON
        pump.drain()

        assertEquals(listOf("relay1"), readbacks)
        assertEquals(2L, readbackOperation(costs).getLong("coalesced"))
        assertEquals(1L, readbackOperation(costs).getLong("succeeded"))
        assertEquals(2L, readbackOperation(costs).getLong("cancelled"))
        // Every span closed — superseded skips included. A leaked span would pin in_flight forever.
        assertEquals(0, readbackOperation(costs).getInt("in_flight"))
    }

    @Test fun aThrowingReadbackStillClosesItsCostSpan() {
        val pump = ManualPump()
        val costs = registry()
        val gate = CommandReadbackGate(pump::schedule, { _, _ -> error("observer blew up") }, costs)

        gate.command("relay1") { true }
        val thrown = runCatching { pump.drain() }
        assertTrue(thrown.isFailure)

        assertEquals(0, readbackOperation(costs).getInt("in_flight"))
    }

    /**
     * The interleaving the first submission missed: the old read-back passes the gate's pre-check and
     * is INSIDE its observation when a newer command bumps the generation and changes the hardware.
     * The stale observation must be withdrawn at the converger's admission lock — never published —
     * and the newer read-back, ordered behind it, publishes the fresh state.
     */
    @Test fun anObservationOvertakenByANewerCommandIsWithdrawnAtAdmission() {
        val pump = ManualPump()
        val published = Collections.synchronizedList(mutableListOf<String>())
        val converger = StateConverger(
            sender = { _, observation, done -> published += mqttStatePayload(observation); done(true) },
            schedule = { it() },
        )
        val hardware = java.util.concurrent.atomic.AtomicReference("ON")
        val observeEntered = CountDownLatch(1)
        val observeRelease = CountDownLatch(1)
        converger.register(
            StateConverger.Channel("relay1", observe = {
                val snapshot = hardware.get()   // captured BEFORE blocking — stale once released
                observeEntered.countDown()
                assertTrue(observeRelease.await(5, TimeUnit.SECONDS))
                StateConverger.Observation.Known(snapshot)
            }),
        )
        val gate = CommandReadbackGate(
            pump::schedule,
            { key, stillCurrent -> converger.reconcile(key, force = true, admit = stillCurrent) },
            registry(),
        )

        gate.command("relay1") { true }                     // generation 1 wrote ON
        val oldReadback = Thread { pump.drain() }.apply { start() }
        try {
            assertTrue(observeEntered.await(5, TimeUnit.SECONDS))
            // Newer command lands mid-observation: generation 2, hardware now OFF.
            gate.command("relay1") { hardware.set("OFF"); true }
        } finally {
            observeRelease.countDown()
            oldReadback.join(5_000L)
        }
        pump.drain()   // generation 2's read-back (observer no longer blocks a second entrant)

        // The stale ON was withdrawn at admission; only the fresh OFF was ever published.
        assertEquals(listOf("OFF"), published)
    }

    /**
     * WIRING CONTRACT: the gate's scheduler must execute every task. dispatchStateWork drops tasks
     * after bridge retirement, which would strand the feature-cost span opened at scheduling time and
     * pin the process-global in-flight count. Cancellation belongs to reconcile's own lifecycle gate.
     */
    @Test fun theBridgeWiresTheGateToAnUndroppableScheduler() {
        val source = sequenceOf(
            java.io.File("src/main/kotlin/io/panelassistant/android/MqttBridge.kt"),
            java.io.File("app/src/main/kotlin/io/panelassistant/android/MqttBridge.kt"),
        ).first(java.io.File::isFile).readText()
        val wiring = source.substringAfter("relayReadbackGate = ").substringBefore("\n    )")
        assertTrue("gate must schedule on the raw convergence pump", wiring.contains("StateConverger.dispatch"))
        assertFalse("gate must not schedule through the task-dropping bridge wrapper", wiring.contains("dispatchStateWork"))
    }

    @Test fun sequentialCommandsEachVerifyPhysically() {
        val pump = ManualPump()
        val readbacks = mutableListOf<String>()
        val gate = CommandReadbackGate(pump::schedule, { key, _ -> readbacks += key }, registry())

        gate.command("relay1") { true }
        pump.drain()
        gate.command("relay1") { true }
        pump.drain()

        assertEquals(listOf("relay1", "relay1"), readbacks)
    }

    @Test fun independentChannelsKeepTheirOwnGenerations() {
        val pump = ManualPump()
        val readbacks = mutableListOf<String>()
        val gate = CommandReadbackGate(pump::schedule, { key, _ -> readbacks += key }, registry())

        gate.command("relay1") { true }
        gate.command("button_led2") { true }
        pump.drain()

        assertEquals(listOf("relay1", "button_led2"), readbacks)
    }

    @Test fun theGenerationAdvancesBeforeTheWriteSoAMidWriteReadbackYields() {
        val pump = ManualPump()
        val readbacks = mutableListOf<String>()
        val gate = CommandReadbackGate(pump::schedule, { key, _ -> readbacks += key }, registry())
        val writeStarted = CountDownLatch(1)
        val release = CountDownLatch(1)

        gate.command("relay1") { true }   // generation 1; its read-back is now queued
        val newer = Thread {
            gate.command("relay1") {
                writeStarted.countDown()
                assertTrue(release.await(5, TimeUnit.SECONDS))
                true
            }
        }.apply { start() }
        try {
            assertTrue(writeStarted.await(5, TimeUnit.SECONDS))
            // Generation 2's write is mid-flight: the older read-back must already see itself
            // superseded, or it would publish a state the newer command is about to change.
            pump.drain()
            assertTrue(readbacks.isEmpty())
        } finally {
            release.countDown()
            newer.join(5_000L)
        }
        pump.drain()
        assertEquals(listOf("relay1"), readbacks)
    }

    @Test fun aFailedWriteStillSchedulesItsReadback() {
        val pump = ManualPump()
        val readbacks = mutableListOf<String>()
        val gate = CommandReadbackGate(pump::schedule, { key, _ -> readbacks += key }, registry())

        assertFalse(gate.command("relay1") { false })
        pump.drain()

        // Publishing the physical state after a failed command is what keeps HA honest.
        assertEquals(listOf("relay1"), readbacks)
    }

    @Test fun aThrowingWriteStillSchedulesItsReadback() {
        val pump = ManualPump()
        val readbacks = mutableListOf<String>()
        val gate = CommandReadbackGate(pump::schedule, { key, _ -> readbacks += key }, registry())

        val thrown = runCatching { gate.command("relay1") { error("wedged root lane") } }
        assertTrue(thrown.isFailure)
        pump.drain()

        assertEquals(listOf("relay1"), readbacks)
    }

    /**
     * The composed Issue #93 property over the REAL command dispatcher: a burst against a blocked
     * root lane conflates queued writes latest-wins, an independent channel is not displaced, and
     * the pump ends at exactly one read-back per channel — newest generation only.
     */
    @Test fun aBlockedRootLaneBurstEndsAtTheLatestStateWithOneReadbackPerChannel() {
        val pump = ManualPump()
        val readbacks = Collections.synchronizedList(mutableListOf<String>())
        val gate = CommandReadbackGate(pump::schedule, { key, _ -> readbacks += key }, registry())
        val dispatcher = MqttCommandDispatcher(threadName = "issue93-blocked-lane-test")
        val writes = Collections.synchronizedList(mutableListOf<Pair<String, Boolean>>())
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val done = CountDownLatch(1)

        // First command wedges like a slow su transact while holding the ordered worker.
        dispatcher.submitLatest("relay1/set") {
            gate.command("relay1") {
                entered.countDown()
                assertTrue(release.await(5, TimeUnit.SECONDS))
                writes += "relay1" to true
                true
            }
        }
        assertTrue(entered.await(5, TimeUnit.SECONDS))

        // Rapid burst while blocked: OFF must be conflated away, the final ON survives.
        dispatcher.submitLatest("relay1/set") { gate.command("relay1") { writes += "relay1" to false; true } }
        assertEquals(
            MqttCommandDispatcher.Admission.COALESCED,
            dispatcher.submitLatest("relay1/set") { gate.command("relay1") { writes += "relay1" to true; true } },
        )
        // An independent channel commanded during the burst is not displaced by it.
        dispatcher.submitLatest("button_led2/set") { gate.command("button_led2") { writes += "button_led2" to true; true } }
        dispatcher.submitAction { done.countDown() }

        release.countDown()
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertTrue(dispatcher.closeAndDrain(MonotonicDeadline(5_000L)).drained)
        pump.drain()

        // The wedged ON ran, the burst's OFF never executed, its final ON did, and the LED ran.
        assertEquals(
            listOf("relay1" to true, "relay1" to true, "button_led2" to true),
            writes,
        )
        // Exactly one read-back per channel, for the newest generation only.
        assertEquals(listOf("relay1", "button_led2"), readbacks)
    }
}
