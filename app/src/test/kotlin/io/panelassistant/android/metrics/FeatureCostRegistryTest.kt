package io.panelassistant.android.metrics

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class FeatureCostRegistryTest {
    @Test fun entitySyncCostsHaveStableFixedKeys() {
        assertEquals("entity_learning.sync", FeatureCostOperation.ENTITY_SYNC.id)
        assertEquals("entity_learning.states_fetch_parse", FeatureCostOperation.ENTITY_STATES_FETCH_PARSE.id)
        assertEquals("entity_learning.dashboard_fetch_parse", FeatureCostOperation.ENTITY_DASHBOARD_FETCH_PARSE.id)
        assertEquals("entity_learning.static_scan", FeatureCostOperation.ENTITY_STATIC_SCAN.id)
    }
    @Test fun proximityCostsKeepRetiredAndReplacementKeysStable() {
        assertEquals("sensors.gpio_proximity_poll", FeatureCostOperation.GPIO_PROXIMITY_POLL.id)
        assertEquals("sensors.proximity_sample", FeatureCostOperation.PROXIMITY_SAMPLE.id)
        assertEquals("sensors.proximity_persist_batch", FeatureCostOperation.PROXIMITY_PERSIST_BATCH.id)
    }
    @Test fun disabledRegistryHasNoPerOperationStateOrPerCallSpanAndReturnsConstantProjection() {
        var clockReads = 0
        val registry = FeatureCostRegistry(
            wallNanos = { clockReads++; 1L },
            threadCpuNanos = { error("disabled registry must not read thread CPU") },
            threadId = { error("disabled registry must not read thread identity") },
            enabled = false,
        )

        val firstSpan = registry.span(FeatureCostOperation.ENTITY_ACCESS_PARSE)
        val secondSpan = registry.span(FeatureCostOperation.LOG_CAPTURE_BATCH)
        firstSpan
            .work(units = 3, bytes = 4)
            .outcome(FeatureCostOutcome.FAILURE)
            .close()
        val synchronous = registry.beginSynchronous(FeatureCostOperation.ENTITY_METRIC_PARSE)
        registry.finishSynchronous(
            FeatureCostOperation.ENTITY_METRIC_PARSE,
            synchronous,
            outcome = FeatureCostOutcome.REJECTED,
            workUnits = 9,
        )
        registry.recordDropped(FeatureCostOperation.ENTITY_ACCESS_PARSE)
        registry.recordExternal(FeatureCostOperation.ENTITY_BROWSER_OBSERVER, externalExecutionNanos = 100L)

        val firstJson = registry.json()
        val secondJson = registry.json()
        val root = JSONObject(firstJson)
        assertEquals(false, root.getBoolean("enabled"))
        assertEquals(setOf("schema", "enabled"), root.keys().asSequence().toSet())
        assertEquals("""{"schema":2,"enabled":false}""", firstJson)
        assertSame(firstJson, secondJson)
        assertSame(firstSpan, secondSpan)
        assertNull(FeatureCostRegistry::class.java.getDeclaredField("records").apply {
            isAccessible = true
        }.get(registry))
        assertEquals(0, clockReads)
    }

    @Test fun synchronousTimingUsesPrimitiveTokenWithoutThreadCpuOrSpanAllocation() {
        var wall = 1_000L
        var cpuReads = 0
        val registry = FeatureCostRegistry(
            wallNanos = { wall },
            threadCpuNanos = { cpuReads++; 50L },
            threadId = { error("synchronous timing must not read thread identity") },
        )

        val started = registry.beginSynchronous(FeatureCostOperation.ENTITY_ACCESS_PARSE)
        wall += 2_000L
        registry.finishSynchronous(
            FeatureCostOperation.ENTITY_ACCESS_PARSE,
            started,
            workUnits = 2,
            workBytes = 12,
        )

        val result = operation(JSONObject(registry.json()), FeatureCostOperation.ENTITY_ACCESS_PARSE)
        assertEquals(1L, result.getLong("calls"))
        assertEquals(1L, result.getLong("succeeded"))
        assertEquals(0, result.getInt("in_flight"))
        assertEquals(2_000L, result.getLong("wall_ns_total"))
        assertEquals(0L, result.getLong("thread_cpu_samples"))
        assertEquals(0, cpuReads)
        assertEquals(
            java.lang.Long.TYPE,
            FeatureCostRegistry::class.java
                .getDeclaredMethod("beginSynchronous", FeatureCostOperation::class.java)
                .returnType,
        )
        assertEquals(
            java.lang.Long.TYPE,
            FeatureCostLongSource::class.java.getDeclaredMethod("read").returnType,
        )
        assertEquals(
            java.lang.Long.TYPE,
            FeatureCostRegistry::class.java.declaredMethods
                .single { it.name == "finishSynchronous" }
                .parameterTypes[1],
        )
    }

    @Test fun recordsFixedOperationCountersWorkAndHistogramWithoutDynamicLabels() {
        var wall = 1_000L
        var cpu = 100L
        var thread = 7L
        val registry = FeatureCostRegistry({ wall }, { cpu }, { thread })

        val span = registry.span(FeatureCostOperation.ENTITY_ACCESS_PARSE).work(units = 3, bytes = 48)
        wall += 1_500_000L
        cpu += 400_000L
        span.close()
        span.close()

        val root = JSONObject(registry.json())
        val operation = operation(root, FeatureCostOperation.ENTITY_ACCESS_PARSE)
        assertEquals(1L, operation.getLong("calls"))
        assertEquals(1L, operation.getLong("succeeded"))
        assertEquals(0, operation.getInt("in_flight"))
        assertEquals(1, operation.getInt("peak_in_flight"))
        assertEquals(1_500_000L, operation.getLong("wall_ns_total"))
        assertEquals(400_000L, operation.getLong("thread_cpu_ns_total"))
        assertEquals(1L, operation.getLong("thread_cpu_samples"))
        assertEquals(3L, operation.getLong("work_units"))
        assertEquals(48L, operation.getLong("work_bytes"))
        assertEquals(1L, (0 until operation.getJSONArray("wall_histogram").length())
            .sumOf { operation.getJSONArray("wall_histogram").getLong(it) })
        assertEquals(FeatureCostOperation.entries.size, root.getJSONArray("operations").length())
        assertTrue(root.getJSONArray("operations").let { operations ->
            (0 until operations.length()).all { index ->
                operations.getJSONObject(index).getString("id") in FeatureCostOperation.entries.map { it.id }
            }
        })
    }

    @Test fun recordsOutcomesAdmissionPressureAndOmitsCrossThreadCpuDelta() {
        var wall = 0L
        var cpu = 10L
        var thread = 1L
        val registry = FeatureCostRegistry({ wall }, { cpu }, { thread })

        val failed = registry.span(FeatureCostOperation.ENTITY_TELEMETRY_FLUSH)
        wall += 50L
        cpu += 25L
        thread = 2L
        failed.outcome(FeatureCostOutcome.FAILURE).close()
        registry.recordDropped(FeatureCostOperation.ENTITY_TELEMETRY_FLUSH, Long.MAX_VALUE)
        registry.recordDropped(FeatureCostOperation.ENTITY_TELEMETRY_FLUSH, 1)
        registry.recordCoalesced(FeatureCostOperation.ENTITY_TELEMETRY_FLUSH, 4)
        registry.setBacklog(FeatureCostOperation.ENTITY_TELEMETRY_FLUSH, 8)
        registry.setBacklog(FeatureCostOperation.ENTITY_TELEMETRY_FLUSH, 2)

        val operation = operation(JSONObject(registry.json()), FeatureCostOperation.ENTITY_TELEMETRY_FLUSH)
        assertEquals(1L, operation.getLong("failed"))
        assertEquals(0L, operation.getLong("thread_cpu_samples"))
        assertEquals(0L, operation.getLong("thread_cpu_ns_total"))
        assertEquals(Long.MAX_VALUE, operation.getLong("dropped"))
        assertEquals(4L, operation.getLong("coalesced"))
        assertEquals(2, operation.getInt("backlog"))
        assertEquals(8, operation.getInt("peak_backlog"))
    }

    @Test fun recordsExternalRendererAggregatesWithoutInventingThreadCpu() {
        val registry = FeatureCostRegistry({ 0L }, { 0L }, { 1L })
        registry.recordExternal(
            FeatureCostOperation.ENTITY_BROWSER_OBSERVER,
            externalExecutionNanos = 4_600_000L,
            events = 4L,
            inputChars = 8192L,
            workUnits = 7L,
        )

        val operation = operation(JSONObject(registry.json()), FeatureCostOperation.ENTITY_BROWSER_OBSERVER)
        assertEquals(1L, operation.getLong("calls"))
        assertEquals(0L, operation.getLong("wall_ns_total"))
        assertEquals(0L, operation.getLong("thread_cpu_samples"))
        assertEquals(4_600_000L, operation.getLong("external_execution_ns_total"))
        assertEquals(1L, operation.getLong("external_execution_samples"))
        assertEquals(4L, operation.getLong("external_events"))
        assertEquals(8192L, operation.getLong("external_input_chars"))
        assertEquals(7L, operation.getLong("work_units"))
    }

    @Test fun epochKeepsLifetimeCountersWhileExposingBoundedPostBoundaryDeltas() {
        var wall = 1_000L
        val registry = FeatureCostRegistry({ wall }, { -1L }, { 1L })
        registry.beginSynchronous(FeatureCostOperation.PROFILE_VALIDATE).also {
            wall += 100L
            registry.finishSynchronous(FeatureCostOperation.PROFILE_VALIDATE, it, workUnits = 2)
        }

        registry.beginEpoch()
        registry.beginSynchronous(FeatureCostOperation.PROFILE_VALIDATE).also {
            wall += 250L
            registry.finishSynchronous(FeatureCostOperation.PROFILE_VALIDATE, it, workUnits = 3)
        }

        val root = JSONObject(registry.json())
        val lifetime = operation(root, FeatureCostOperation.PROFILE_VALIDATE)
        val epoch = lifetime.getJSONObject("epoch")
        assertEquals(2L, lifetime.getLong("calls"))
        assertEquals(350L, lifetime.getLong("wall_ns_total"))
        assertEquals(5L, lifetime.getLong("work_units"))
        assertEquals(1L, epoch.getLong("calls"))
        assertEquals(250L, epoch.getLong("wall_ns_total"))
        assertEquals(3L, epoch.getLong("work_units"))
        assertEquals(2L, root.getLong("epoch_generation"))
        assertEquals(250L, root.getLong("epoch_elapsed_ns"))
    }

    @Test fun projectionDefinesInclusiveLatencyAndKnownParentHierarchy() {
        val root = JSONObject(FeatureCostRegistry({ 1L }, { -1L }, { 1L }).json())
        assertEquals(2, root.getInt("schema"))
        assertEquals(
            "inclusive_elapsed_latency_not_additive",
            root.getJSONObject("metric_semantics").getString("wall_ns"),
        )
        assertEquals(
            FeatureCostOperation.ENTITY_SYNC.id,
            operation(root, FeatureCostOperation.ENTITY_STATIC_SCAN).getString("parent_id"),
        )
        assertTrue(operation(root, FeatureCostOperation.ENTITY_SYNC).isNull("parent_id"))
        assertEquals("log", operation(root, FeatureCostOperation.LOG_CAPTURE_BATCH).getString("family"))
    }

    private fun operation(root: JSONObject, operation: FeatureCostOperation): JSONObject {
        val operations = root.getJSONArray("operations")
        return (0 until operations.length()).asSequence()
            .map(operations::getJSONObject)
            .first { it.getString("id") == operation.id }
    }
}
