package io.panelassistant.android.dashboard

import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardTelemetryTest {
    @After fun reset() {
        DashboardTelemetry.setHistorySink(null)
        DashboardTelemetry.reset()
    }

    @Test fun acceptedBatchesAreForwardedToDurableHistoryWithoutPayloadContent() {
        val recorded = mutableListOf<EntityFilterProtocol.TrafficBatch>()
        DashboardTelemetry.setHistorySink(DashboardPerformanceHistorySink(recorded::add))
        DashboardTelemetry.installed()
        val batch = batch(updates = 12, payloadBytes = 34_567)

        DashboardTelemetry.record(batch)

        assertEquals(listOf(batch), recorded)
        val minute = DashboardPerformanceMinute(
            minute = 123,
            filterActive = true,
            entityCount = 42,
            totals = TrafficTotals(
                sampleMs = batch.sampleMs,
                frames = batch.frames,
                payloadBytes = batch.payloadBytes,
                updates = batch.entityUpdates,
                hydrationUpdates = batch.hydrationUpdates,
                observerMicros = batch.observerMicros,
                droppedFrames = batch.droppedFrames,
                stateTaskMicros = batch.stateTaskMicros,
                stateTaskMaxMicros = batch.stateTaskMaxMicros,
                interactionCount = batch.interactionBins.sum(),
                interactionMaxMicros = batch.interactionMaxMicros,
                inputDelayMicros = batch.inputDelayMicros,
                interactionProcessingMicros = batch.interactionProcessingMicros,
                presentationMicros = batch.presentationMicros,
                loafCount = batch.loafCount,
                blockingMicros = batch.blockingMicros,
                loafMaxMicros = batch.loafMaxMicros,
                scriptMicros = batch.scriptMicros,
                renderMicros = batch.renderMicros,
                longTaskCount = batch.longTaskCount,
            ),
        )
        val json = dashboardPerformanceHistoryJson(listOf(minute), 7)
        assertTrue(json.contains("\"updatesPerSec\":2.4"))
        assertTrue(json.contains("\"payloadBytesPerSec\":6913.4"))
        assertFalse(json.contains("entity_id"))
        assertFalse(json.contains("payload\""))
    }

    @Test fun projectsBoundedRatesInteractionBreakdownAndStateCause() {
        DashboardTelemetry.reset()
        DashboardTelemetry.installed()
        val bins = LongArray(10).also { it[5] = 3 }
        DashboardTelemetry.record(batch(
            updates = 500,
            payloadBytes = 512_000,
            stateTaskMicros = 1_500_000,
            bins = bins,
            interactionMaxMicros = 700_000,
            inputMicros = 200_000,
            processingMicros = 300_000,
            presentationMicros = 200_000,
            blockingMicros = 750_000,
        ))

        val json = JSONObject(DashboardTelemetry.json(
            builtinActive = true,
            filterActive = true,
            entityCount = 42,
            reloads24h = 0,
            topEntities = JSONArray().put(JSONObject()
                .put("entityId", "sensor.noisy")
                .put("updates1h", 60)
                .put("payloadBytes1h", 1024)),
        ))

        assertEquals("builtin_direct", json.getString("mode"))
        assertEquals("state_stream", json.getString("likelyCause"))
        assertEquals(100.0, json.getJSONObject("stateStream").getDouble("updatesPerSec"), 0.01)
        assertEquals(300.0, json.getJSONObject("stateStream").getDouble("mainThreadMsPerSec"), 0.01)
        assertEquals(500.0, json.getJSONObject("interaction").getDouble("p95Ms"), 0.01)
        assertEquals(300.0, json.getJSONObject("interaction").getDouble("processingMs"), 0.01)
        assertEquals("sensor.noisy", json.getJSONArray("topEntities").getJSONObject(0).getString("entityId"))
    }

    @Test fun retainsOnlyFourMinutesAndResetsGeneration() {
        DashboardTelemetry.reset()
        DashboardTelemetry.installed()
        repeat(60) { DashboardTelemetry.record(batch(updates = it.toLong())) }
        val before = JSONObject(DashboardTelemetry.json(true, false, 0, 0))
        assertEquals(48, before.getInt("sampleCount"))
        assertEquals(240_000, before.getLong("windowMs"))
        val generation = before.getLong("generation")

        DashboardTelemetry.reset()
        val after = JSONObject(DashboardTelemetry.json(true, false, 0, 0))
        assertTrue(after.getLong("generation") > generation)
        assertEquals(0, after.getInt("sampleCount"))
        assertFalse(after.toString().contains("sensor."))
    }

    @Test fun exposesBrowserSampleAgeSoExternalCollectorsCanDetectAStalledWebView() {
        DashboardTelemetry.reset()
        DashboardTelemetry.installed()
        DashboardTelemetry.record(batch(), observedAtElapsedMs = 10_000L)

        val fresh = JSONObject(DashboardTelemetry.json(
            builtinActive = true,
            filterActive = false,
            entityCount = 0,
            reloads24h = 0,
            nowElapsedMs = 12_500L,
        ))
        assertEquals(2_500L, fresh.getLong("latestSampleAgeMs"))

        DashboardTelemetry.reset()
        val absent = JSONObject(DashboardTelemetry.json(
            builtinActive = true,
            filterActive = false,
            entityCount = 0,
            reloads24h = 0,
            nowElapsedMs = 20_000L,
        ))
        assertEquals(-1L, absent.getLong("latestSampleAgeMs"))
    }

    @Test fun companionModeNeverClaimsDirectInteractionTiming() {
        DashboardTelemetry.reset()
        val json = JSONObject(DashboardTelemetry.json(
            builtinActive = false,
            filterActive = false,
            entityCount = 0,
            reloads24h = 0,
            rendererMainPct = 92.0,
        ))
        assertEquals("foreign_proxy", json.getString("mode"))
        assertEquals("dashboard_or_state_proxy", json.getString("likelyCause"))
        assertEquals("low", json.getString("confidence"))
    }

    @Test fun aMeasuredDegradedPathToHomeAssistantIsNamedBeforeAnyInPanelBurden() {
        DashboardTelemetry.reset()
        DashboardTelemetry.installed()
        // The same state-stream pressure that names state_stream above...
        val bins = LongArray(10).also { it[5] = 3 }
        DashboardTelemetry.record(batch(
            updates = 500,
            payloadBytes = 512_000,
            stateTaskMicros = 1_500_000,
            bins = bins,
            interactionMaxMicros = 700_000,
            inputMicros = 200_000,
            processingMicros = 300_000,
            presentationMicros = 200_000,
            blockingMicros = 750_000,
        ))
        // ...yields to the measured path when it is severe, with high confidence...
        val severe = JSONObject(DashboardTelemetry.json(
            builtinActive = true, filterActive = true, entityCount = 42, reloads24h = 3, networkPath = "severe",
        ))
        assertEquals("ha_network_path", severe.getString("likelyCause"))
        assertEquals("high", severe.getString("confidence"))
        // ...and to a warning with medium confidence...
        val warning = JSONObject(DashboardTelemetry.json(
            builtinActive = true, filterActive = true, entityCount = 42, reloads24h = 0, networkPath = "warning",
        ))
        assertEquals("ha_network_path", warning.getString("likelyCause"))
        assertEquals("medium", warning.getString("confidence"))
        // ...while a healthy or unmeasured path (null) leaves the existing verdict untouched.
        val untouched = JSONObject(DashboardTelemetry.json(
            builtinActive = true, filterActive = true, entityCount = 42, reloads24h = 0, networkPath = null,
        ))
        assertEquals("state_stream", untouched.getString("likelyCause"))
    }

    @Test fun builtinWithoutObserverIsNotMisreportedAsCompanionProxy() {
        DashboardTelemetry.reset()
        val json = JSONObject(DashboardTelemetry.json(
            builtinActive = true,
            filterActive = false,
            entityCount = 0,
            reloads24h = 0,
            rendererMainPct = 92.0,
        ))
        assertEquals("builtin_unavailable", json.getString("mode"))
        assertEquals("no_clear_dominant_cause", json.getString("likelyCause"))
        assertEquals("low", json.getString("confidence"))
    }

    @Test fun currentStatePressureOutranksOneHistoricalReload() {
        DashboardTelemetry.reset()
        DashboardTelemetry.installed()
        val bins = LongArray(10).also { it[5] = 1 }
        DashboardTelemetry.record(batch(
            updates = 500,
            payloadBytes = 512_000,
            stateTaskMicros = 1_500_000,
            bins = bins,
            interactionMaxMicros = 700_000,
        ))

        val json = JSONObject(DashboardTelemetry.json(
            builtinActive = true,
            filterActive = false,
            entityCount = 0,
            reloads24h = 1,
        ))
        assertEquals("state_stream", json.getString("likelyCause"))
        assertEquals("high", json.getString("confidence"))
    }

    @Test fun historicalReloadRemainsFallbackWithoutDecisiveLiveEvidence() {
        DashboardTelemetry.reset()
        DashboardTelemetry.installed()

        val json = JSONObject(DashboardTelemetry.json(
            builtinActive = true,
            filterActive = false,
            entityCount = 0,
            reloads24h = 1,
        ))
        assertEquals("memory_or_renderer_instability", json.getString("likelyCause"))
        assertEquals("medium", json.getString("confidence"))
    }

    @Test fun currentExternalRendererPressureOutranksHistoricalReload() {
        DashboardTelemetry.reset()
        val json = JSONObject(DashboardTelemetry.json(
            builtinActive = false,
            filterActive = false,
            entityCount = 0,
            reloads24h = 1,
            rendererMainPct = 92.0,
        ))
        assertEquals("dashboard_or_state_proxy", json.getString("likelyCause"))
        assertEquals("low", json.getString("confidence"))
    }

    private fun batch(
        updates: Long = 1,
        payloadBytes: Long = 100,
        stateTaskMicros: Long = 0,
        bins: LongArray = LongArray(10),
        interactionMaxMicros: Long = 0,
        inputMicros: Long = 0,
        processingMicros: Long = 0,
        presentationMicros: Long = 0,
        blockingMicros: Long = 0,
    ) = EntityFilterProtocol.TrafficBatch(
        sampleMs = 5_000,
        frames = 1,
        frameChars = payloadBytes,
        entityUpdates = updates,
        processingMicros = 10,
        droppedFrames = 0,
        hydrationUpdates = 0,
        stateTaskMicros = stateTaskMicros,
        stateTaskMaxMicros = stateTaskMicros,
        interactionBins = bins,
        interactionMaxMicros = interactionMaxMicros,
        inputDelayMicros = inputMicros,
        interactionProcessingMicros = processingMicros,
        presentationMicros = presentationMicros,
        loafCount = if (blockingMicros > 0) 1 else 0,
        blockingMicros = blockingMicros,
        loafMaxMicros = blockingMicros,
        scriptMicros = blockingMicros / 2,
        renderMicros = blockingMicros / 2,
    )
}
