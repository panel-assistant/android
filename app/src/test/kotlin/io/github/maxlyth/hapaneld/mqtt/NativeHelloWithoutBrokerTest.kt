package io.github.maxlyth.hapaneld.mqtt

import io.github.maxlyth.hapaneld.panelassistant.PanelAssistantShadowReporter
import io.github.maxlyth.hapaneld.requestWatchdogLocalObservation
import io.github.maxlyth.hapaneld.util.ServiceRuntimeOwner
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

internal class NativeHelloWithoutBrokerTest : MqttWireRig() {
    @Test(timeout = 90_000)
    fun acceptedHelloReportsEveryKnowableDescribedChannelWithoutBroker() = runBlocking {
        val rig = rig(runtimeBroker = "")
        val owner = ServiceRuntimeOwner(rig.bridge, "native-hello-without-broker")
        try {
            assertTrue(owner.start { it.start() }.get(5, TimeUnit.SECONDS))
            assertEquals("discovering", rig.bridge.state)
            assertNull(rig.bridge.heartbeatConnectionGeneration())
            assertTrue("no MQTT connection was attempted", rig.transport.snapshot().none { it.startsWith("connect\t") })
            var checks = 0
            assertTrue("a replaced runtime cannot complete a native snapshot", !rig.bridge.observeForNativeHello {
                ++checks == 1
            })
            assertEquals("ownership was checked after observation", 2, checks)

            // An ordinary local pass establishes which channels this hardware can actually read.
            // Bind a fresh reporter afterwards: its accepted hello must produce those observations
            // even though no MQTT connect or watchdog tick supplies its initial cache.
            val reference = PanelAssistantShadowReporter(log = {})
            rig.bridge.addStateSink(reference.bindShape(rig.bridge::nativeChannelShape))
            requestWatchdogLocalObservation(owner) { it }
            drainStatePump()
            val referenceOffer = reference.offer()
            reference.open(referenceOffer.descriptors)
            val knowable = channels(JSONObject(requireNotNull(reference.next(1, "reference", 0L))))
            assertTrue("fixture must expose a meaningful state surface: $knowable", knowable.size >= 20)
            assertTrue(knowable.containsAll(listOf("screen", "navigate", "home_dashboard", "storage_health", "relay1")))

            val native = PanelAssistantShadowReporter(log = {})
            rig.bridge.addStateSink(native.bindShape(rig.bridge::nativeChannelShape))
            val hello = native.offer()
            val described = hello.descriptors.map { it.channel }.toSet()
            assertTrue("expected channels must be described", described.containsAll(knowable))

            // The production accepted-hello path samples the current bridge before opening reporting.
            assertTrue(rig.bridge.observeForNativeHello { owner.observe()?.let(owner::isCurrent) == true })
            assertTrue(
                "a disconnected MQTT client must not be used by the native full pass",
                rig.transport.snapshot().none {
                    it.startsWith("# dropped\t") || it.startsWith("true\t") || it.startsWith("false\t")
                },
            )
            native.open(hello.descriptors)
            val begin = JSONObject(requireNotNull(native.next(2, "native-session", 0L)))
            assertEquals("full_begin", begin.getString("sync"))
            val reported = channels(begin)
            assertTrue("full sync missed ${knowable - reported.toSet()}", reported.containsAll(knowable))
            native.onResult(io.github.maxlyth.hapaneld.panelassistant.PanelAssistantReportResult.Acknowledged(2, emptyMap()), 0L)
            val end = JSONObject(requireNotNull(native.next(3, "native-session", 0L)))
            assertEquals("full_end", end.getString("sync"))
            native.onResult(io.github.maxlyth.hapaneld.panelassistant.PanelAssistantReportResult.Acknowledged(3, emptyMap()), 0L)
            assertTrue(native.fullSyncComplete())
        } finally {
            owner.shutdown(1_000) {}
            rig.close()
        }
    }

    @Test(timeout = 45_000)
    fun acceptedHelloReportsCurrentStateWhileConnectedBrokerSendStalls() = runBlocking {
        val rig = rig()
        val owner = ServiceRuntimeOwner(rig.bridge, "native-hello-stalled-broker")
        try {
            assertTrue(owner.start { it.start() }.get(5, TimeUnit.SECONDS))
            rig.transport.awaitPublication(0, "connected broker", timeoutSeconds = 10) {
                it.topic() == "ha-paneld/golden/availability"
            }
            rig.transport.drain()
            val native = PanelAssistantShadowReporter(log = {})
            rig.bridge.addStateSink(native.bindShape(rig.bridge::nativeChannelShape))
            val hello = native.offer()

            rig.transport.blockNextPublish("ha-paneld/golden/auto_sleep_activity/state")
            rig.storage.set(storageSnapshot(io.github.maxlyth.hapaneld.storage.StorageHealthSeverity.WARNING, 65_536))
            rig.bridge.publishAutoSleepActivity()
            assertTrue("connected MQTT send did not stall", rig.transport.publishEntered.await(10, TimeUnit.SECONDS))

            // The fake accepted the connection and holds every PUBACK. Its send also stops returning,
            // as a broker-facing client under backpressure can. Native full sync must still finish.
            assertTrue(withTimeout(2_000) {
                rig.bridge.observeForNativeHello { owner.observe()?.let(owner::isCurrent) == true }
            })
            native.open(hello.descriptors)
            val begin = JSONObject(requireNotNull(native.next(2, "native-session", 0L)))
            assertEquals("full_begin", begin.getString("sync"))
            val observations = begin.getJSONArray("observations")
            val storage = (0 until observations.length()).map(observations::getJSONObject)
                .first { it.getString("channel") == "storage_health" }
            assertEquals("warning", storage.getString("value"))
            native.onResult(io.github.maxlyth.hapaneld.panelassistant.PanelAssistantReportResult.Acknowledged(2, emptyMap()), 0L)
            val end = JSONObject(requireNotNull(native.next(3, "native-session", 0L)))
            assertEquals("full_end", end.getString("sync"))
            native.onResult(io.github.maxlyth.hapaneld.panelassistant.PanelAssistantReportResult.Acknowledged(3, emptyMap()), 0L)
            assertTrue(native.fullSyncComplete())
            val retirement = rig.bridge.stop(io.github.maxlyth.hapaneld.util.MonotonicDeadline(1_000), publishOffline = false)
            assertTrue("stalled MQTT send must not hold runtime retirement", retirement.ownersDrained.get(2, TimeUnit.SECONDS))
            retirement.finalization.get(2, TimeUnit.SECONDS)
            Unit
        } finally {
            rig.transport.releasePublish.countDown()
            owner.shutdown(1_000) {}
            rig.close()
        }
    }

    private fun channels(report: JSONObject): Set<String> = report.getJSONArray("observations").let { observations ->
        (0 until observations.length()).mapTo(linkedSetOf()) { observations.getJSONObject(it).getString("channel") }
    }
}
