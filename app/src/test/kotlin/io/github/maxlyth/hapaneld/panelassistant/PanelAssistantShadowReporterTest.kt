package io.github.maxlyth.hapaneld.panelassistant

import io.github.maxlyth.hapaneld.mqtt.StateConverger.Observation
import io.github.maxlyth.hapaneld.mqtt.StateSink
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PanelAssistantShadowReporterTest {

    @Test fun actionDescriptorsAreOfferedWithoutStateObservations() {
        val reporter = PanelAssistantShadowReporter()
        reporter.bindShape {
            PanelAssistantChannelShape(served = listOf("reload"), unsupported = listOf("reboot"))
        }
        val offer = reporter.offer()
        assertEquals(listOf("reload"), offer.descriptors.map { it.channel })
        assertEquals("button", offer.descriptors.single().platform)
        assertEquals(listOf("reboot"), offer.unsupported)
        reporter.open(offer.descriptors)
        val begin = JSONObject(requireNotNull(reporter.next(1, "session", 0)))
        assertEquals(0, begin.getJSONArray("observations").length())
    }

    @Test fun fullSyncSendsEveryCachedObservationThenAnEmptyFullEndThenDeltas() {
        val h = Harness()
        h.sink("screen", """{"state":"ON","brightness":40}""")
        h.sink("relay1", "OFF")
        h.sink("storage_health", "healthy")
        h.sink("storage_health_attributes", """{"quick_check":"ok","nested":{"x":1}}""")
        h.open()

        val begin = h.next(2)
        assertEquals("full_begin", begin.getString("sync"))
        assertEquals("session-token", begin.getString("session"))
        assertEquals(listOf("relay1", "screen", "storage_health"), channels(begin))
        val storage = observation(begin, "storage_health")
        assertEquals("healthy", storage.getString("value"))
        assertTrue("attributes were not folded into $storage", storage.has("attributes"))
        assertEquals("ok", storage.getJSONObject("attributes").getString("quick_check"))
        assertFalse(storage.getJSONObject("attributes").has("nested"))
        assertNull("nothing is sent until full_begin is acknowledged", h.reporter.next(3, TOKEN, 0))

        h.ack(2)
        val end = h.next(3)
        assertEquals("full_end", end.getString("sync"))
        assertEquals(0, end.getJSONArray("observations").length())
        h.sink("relay1", "ON")
        assertNull("deltas wait for full_end to be acknowledged", h.reporter.next(4, TOKEN, 0))

        h.ack(3)
        val delta = h.next(4)
        assertEquals("delta", delta.getString("sync"))
        assertEquals(listOf("relay1"), channels(delta))
        assertEquals(true, observation(delta, "relay1").getBoolean("value"))
        assertNull(h.reporter.next(5, TOKEN, 0))
    }

    @Test fun anAttributeChangeReReportsItsParentUnderTheParentChannel() {
        val h = Harness().synced()
        h.sink("diag_wifi_outages_24h", "3")
        h.ack(h.next(10).getLong("id"))
        h.sink("diag_wifi_outages_attributes", """{"is_lower_bound":true}""")
        val delta = h.next(11)
        assertEquals(listOf("diag_wifi_outages_24h"), channels(delta))
        assertTrue(observation(delta, "diag_wifi_outages_24h").optJSONObject("attributes")?.optBoolean("is_lower_bound") == true)
    }

    @Test fun updateChannelsReportUnderTheirWireIds() {
        val h = Harness().synced()
        h.sink("software_update_paneld", """{"installed_version":"1","in_progress":false}""")
        assertEquals(listOf("update_paneld"), channels(h.next(10)))
    }

    @Test fun onlyTheResultWithTheRequestsParsedIdAcknowledgesIt() {
        val h = Harness().synced()
        h.sink("relay1", "ON")
        assertEquals(10L, h.next(10).getLong("id"))
        h.reporter.onResult(PanelAssistantReportResult.Acknowledged(1, emptyMap()), 0)
        h.reporter.onResult(PanelAssistantReportResult.Acknowledged(100, emptyMap()), 0)
        h.sink("relay1", "OFF")
        assertNull("relay1 is still outstanding", h.reporter.next(11, TOKEN, 0))
        h.ack(10)
        assertEquals(false, observation(h.next(11), "relay1").getBoolean("value"))
    }

    @Test fun aRejectedObservationIsNotRetriedUntilItsValueChanges() {
        val h = Harness().synced()
        h.sink("diag_ip", "192.0.2.4")
        h.reporter.onResult(PanelAssistantReportResult.Acknowledged(h.next(10).getLong("id"), mapOf("diag_ip" to "invalid_value")), 0)
        h.sink("diag_ip", "192.0.2.4")
        assertNull(h.reporter.next(11, TOKEN, 60_000))
        assertEquals(1L, h.reporter.rejections)
        h.sink("diag_ip", "192.0.2.5")
        assertEquals("192.0.2.5", observation(h.next(11), "diag_ip").getString("value"))
    }

    @Test fun aRepeatOfTheAcknowledgedValueIsSentAsARefresh() {
        val h = Harness().synced()
        h.sink("diag_cpu", "12")
        val first = h.next(10)
        assertFalse(observation(first, "diag_cpu").has("refresh"))
        h.ack(10)
        h.sink("diag_cpu", "12")
        assertTrue(observation(h.next(11), "diag_cpu").optBoolean("refresh"))
    }

    @Test fun anUnansweredRequestTimesOutAfterFifteenSecondsAndRetriesTheSameChannels() {
        val h = Harness().synced()
        h.sink("relay1", "ON")
        h.next(10, now = 1_000)
        h.reporter.expire(15_999)
        assertNull("still outstanding before the deadline", h.reporter.next(11, TOKEN, 15_999))
        assertEquals(16_000L, h.reporter.nextDeadline(15_999))
        h.reporter.expire(16_000)
        assertNull("waits out the retry delay", h.reporter.next(11, TOKEN, 16_000))
        assertEquals(21_000L, h.reporter.nextDeadline(16_000))
        assertEquals(listOf("relay1"), channels(h.next(11, now = 21_000)))
        h.reporter.onResult(PanelAssistantReportResult.Acknowledged(10, emptyMap()), 21_000)
        assertNull("a late result for the expired request acknowledges nothing", h.reporter.next(12, TOKEN, 21_000))
    }

    @Test fun anErrorResultLeavesTheChannelsDirtyForARetry() {
        val h = Harness().synced()
        h.sink("relay1", "ON")
        h.next(10)
        h.reporter.onResult(PanelAssistantReportResult.Failed(10, "invalid_format"), 0)
        assertNull(h.reporter.next(11, TOKEN, 4_999))
        assertEquals(listOf("relay1"), channels(h.next(11, now = 5_000)))
    }

    @Test fun aFailedFullBeginIsSentAgainBeforeFullEnd() {
        val h = Harness()
        h.sink("relay1", "ON")
        h.open()
        h.next(2)
        h.reporter.onResult(PanelAssistantReportResult.Failed(2, "invalid_format"), 0)
        assertNull(h.reporter.next(3, TOKEN, 1_000))
        val again = h.next(3, now = 5_000)
        assertEquals("full_begin", again.getString("sync"))
        assertEquals(listOf("relay1"), channels(again))
    }

    @Test fun atMostFourRequestsAreOutstanding() {
        val h = Harness().synced()
        for ((index, channel) in listOf("relay1", "relay2", "relay3", "relay4").withIndex()) {
            h.sink(channel, "ON")
            h.next(10L + index)
        }
        h.sink("relay5", "ON")
        assertNull(h.reporter.next(14, TOKEN, 0))
        h.ack(12)
        assertEquals(listOf("relay5"), channels(h.next(14)))
    }

    @Test fun anUntranslatablePayloadIsSkippedAndCountedOnceWithoutBlockingOthers() {
        val h = Harness().synced()
        h.sink("volume", "loud")
        h.sink("relay1", "ON")
        assertEquals(listOf("relay1"), channels(h.next(10)))
        assertEquals(1L, h.reporter.untranslatable)
        h.sink("volume", "loud")
        assertNull(h.reporter.next(11, TOKEN, 0))
        assertEquals(1L, h.reporter.untranslatable)
    }

    @Test fun aChannelTheSessionDidNotDescribeIsNotSentAndChangesTheDescriptors() {
        val h = Harness(listOf("relay1")).synced()
        assertFalse(h.reporter.descriptorsChanged())
        h.keys += "relay2"
        h.sink("relay2", "ON")
        assertTrue(h.reporter.descriptorsChanged())
        assertNull(h.reporter.next(10, TOKEN, 0))
    }

    @Test fun aRetiredBridgeGenerationCannotOverwriteTheLiveObservation() {
        val h = Harness().synced()
        val retired = h.bound
        h.bound = h.reporter.bind { h.keys }
        h.sink("relay1", "ON")
        val thrown = runCatching { retired("relay1", Observation.Known("OFF")) {} }.exceptionOrNull()
        assertNull("a late observation must never throw into the convergence pump", thrown)
        assertEquals(true, observation(h.next(10), "relay1").getBoolean("value"))
    }

    @Test fun theSinkDoesNoTranslationAndIgnoresItsAcknowledgement() {
        var described = 0
        val h = Harness(describe = { wire -> described++; PanelAssistantChannelCatalog.describe(wire) })
        described = 0
        var acknowledged = false
        repeat(100) { h.bound("volume", Observation.Known("not a number")) { acknowledged = true } }
        assertEquals(0, described)
        assertEquals(0L, h.reporter.untranslatable)
        assertFalse(acknowledged)
        assertTrue(h.reporter.wake.tryReceive().isSuccess)
    }

    @Test fun reportingStopsWhenTheSessionCloses() {
        val h = Harness().synced()
        h.sink("relay1", "ON")
        h.reporter.close()
        assertNull(h.reporter.next(10, TOKEN, 0))
        assertFalse(h.reporter.descriptorsChanged())
    }

    private class Harness(
        initial: List<String> = listOf(
            "screen", "relay1", "relay2", "relay3", "relay4", "relay5", "storage_health", "storage_health_attributes",
            "diag_wifi_outages_24h", "diag_wifi_outages_attributes", "software_update_paneld", "diag_ip", "diag_cpu", "volume",
        ),
        describe: (String) -> PanelAssistantChannelDescriptor? = PanelAssistantChannelCatalog::describe,
    ) {
        val keys = initial.toMutableList()
        val reporter = PanelAssistantShadowReporter(describe = describe, log = {})
        var bound: StateSink = reporter.bind { keys.toList() }

        fun sink(channel: String, payload: String) = bound(channel, Observation.Known(payload)) {}

        fun open() = reporter.open(reporter.descriptors())

        fun next(id: Long, now: Long = 0): JSONObject {
            val text = reporter.next(id, TOKEN, now)
            assertNotNull("no request was due", text)
            return JSONObject(text!!).also {
                assertEquals("panel_assistant/report_state", it.getString("type"))
                assertEquals(id, it.getLong("id"))
            }
        }

        fun ack(id: Long) = reporter.onResult(PanelAssistantReportResult.Acknowledged(id, emptyMap()), 0)

        /** Open a session and complete its empty full sync. */
        fun synced(): Harness = apply {
            open()
            ack(next(2).getLong("id"))
            ack(next(3).getLong("id"))
        }
    }

    private companion object {
        const val TOKEN = "session-token"

        fun channels(request: JSONObject): List<String> {
            val observations = request.getJSONArray("observations")
            return (0 until observations.length()).map { observations.getJSONObject(it).getString("channel") }
        }

        fun observation(request: JSONObject, channel: String): JSONObject {
            val observations = request.getJSONArray("observations")
            val found = (0 until observations.length()).map(observations::getJSONObject).firstOrNull { it.getString("channel") == channel }
            assertNotNull("$channel not in $request", found)
            return found!!
        }
    }
}
