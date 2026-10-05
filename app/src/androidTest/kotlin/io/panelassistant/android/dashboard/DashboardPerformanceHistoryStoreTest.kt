package io.panelassistant.android.dashboard

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.panelassistant.android.CoreInstrumentation
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@CoreInstrumentation
@RunWith(AndroidJUnit4::class)
class DashboardPerformanceHistoryStoreTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before fun cleanBefore() {
        context.deleteDatabase(EntityCatalogStore.DATABASE_NAME)
    }

    @After fun cleanAfter() {
        context.deleteDatabase(EntityCatalogStore.DATABASE_NAME)
    }

    @Test fun minuteRollupsSurviveStoreReopenAndAggregateBatches() {
        val minute = System.currentTimeMillis() / 60_000L
        fun sample(updates: Long, bytes: Long, worstMicros: Long) = DashboardPerformanceSample(
            instance = "https://ha.example",
            path = "wall/default",
            minute = minute,
            filterActive = true,
            entityCount = 42,
            batch = EntityFilterProtocol.TrafficBatch(
                sampleMs = 5_000,
                frames = 2,
                frameChars = bytes,
                entityUpdates = updates,
                processingMicros = 100,
                droppedFrames = 0,
                stateTaskMicros = 50_000,
                stateTaskMaxMicros = 30_000,
                interactionBins = LongArray(10).also { it[3] = 1 },
                interactionMaxMicros = worstMicros,
                inputDelayMicros = worstMicros / 4,
                interactionProcessingMicros = worstMicros / 2,
                presentationMicros = worstMicros / 4,
            ),
        )

        EntityCatalogStore(context).use { store ->
            val pending = DashboardPerformanceAccumulator(4).apply {
                add(sample(10, 20_000, 200_000))
                add(sample(15, 30_000, 500_000))
            }
            store.recordDashboardPerformance(pending.drain())
        }

        EntityCatalogStore(context).use { reopened ->
            val rows = reopened.dashboardPerformanceHistory(
                "https://ha.example",
                "wall/default",
                minute - 1,
            )
            assertEquals(1, rows.size)
            assertEquals(10_000, rows.single().totals.sampleMs)
            assertEquals(25, rows.single().totals.updates)
            assertEquals(50_000, rows.single().totals.payloadBytes)
            assertEquals(500_000, rows.single().totals.interactionMaxMicros)
            assertTrue(rows.single().filterActive)
        }
    }

    @Test fun performanceSummaryUsesNonZeroRollingHourTotals() {
        val now = System.currentTimeMillis()
        EntityCatalogStore(context).use { store ->
            store.recordMetrics(
                "https://ha.example",
                "wall/default",
                mapOf(
                    "sensor.active" to (3L to 1_536L),
                    "sensor.quiet" to (1L to 128L),
                ),
                now - 30L * 60_000L,
            )
            store.recordAccess(
                "https://ha.example",
                "wall/default",
                mapOf("sensor.access_only" to 4L),
                now,
            )

            val rows = org.json.JSONArray(store.performanceSummaryJson("https://ha.example", "wall/default", now))
            assertEquals(2, rows.length())
            assertEquals("sensor.active", rows.getJSONObject(0).getString("entityId"))
            assertEquals(3L, rows.getJSONObject(0).getLong("updates1h"))
            assertEquals(1_536L, rows.getJSONObject(0).getLong("payloadBytes1h"))
            assertFalse(rows.toString().contains("sensor.access_only"))
        }
    }
}
