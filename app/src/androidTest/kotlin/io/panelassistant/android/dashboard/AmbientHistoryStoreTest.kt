package io.panelassistant.android.dashboard

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.panelassistant.android.CoreInstrumentation
import io.panelassistant.android.control.AmbientHistoryKey
import io.panelassistant.android.control.AmbientMinuteAggregate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@CoreInstrumentation
@RunWith(AndroidJUnit4::class)
class AmbientHistoryStoreTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before fun cleanBefore() {
        context.deleteDatabase(EntityCatalogStore.DATABASE_NAME)
    }

    @After fun cleanAfter() {
        context.deleteDatabase(EntityCatalogStore.DATABASE_NAME)
    }

    @Test fun rowsAggregateAndRemainPartitionedBySourceAndContext() {
        val now = System.currentTimeMillis()
        val minute = now / 60_000L
        fun aggregate(contextId: String, sourceId: String, lux: Double) = AmbientMinuteAggregate(
            AmbientHistoryKey(contextId, sourceId, minute),
        ).also { it.add(lux, 1_000, baselineEligible = true) }

        EntityCatalogStore(context).use { store ->
            store.recordAmbientHistory(listOf(
                aggregate("location-a", "panel", 10.0),
                aggregate("location-a", "panel", 30.0),
                aggregate("location-a", "sensor.room", 100.0),
                aggregate("location-b", "panel", 200.0),
            ), now)
            val local = store.ambientHistory("location-a", "panel", minute - 1)
            assertEquals(1, local.size)
            assertEquals(20.0, local.single().meanLux, 0.001)
            assertEquals(10.0, local.single().minLux, 0.001)
            assertEquals(30.0, local.single().maxLux, 0.001)
            assertEquals(2L, local.single().sampleCount)
            assertEquals(1, store.ambientHistory("location-a", "sensor.room", minute - 1).size)
            assertEquals(1, store.ambientHistory("location-b", "panel", minute - 1).size)
        }
    }

    @Test fun clockInvalidAndExpiredRowsAreRejectedAndResetIsScoped() {
        val now = System.currentTimeMillis()
        val minute = now / 60_000L
        fun aggregate(contextId: String, sourceId: String, targetMinute: Long) = AmbientMinuteAggregate(
            AmbientHistoryKey(contextId, sourceId, targetMinute),
        ).also { it.add(10.0, 1_000, baselineEligible = false) }

        EntityCatalogStore(context).use { store ->
            store.recordAmbientHistory(listOf(
                aggregate("a", "panel", minute),
                aggregate("a", "future", minute + 20),
                aggregate("a", "expired", minute - 8L * 24L * 60L),
                aggregate("b", "panel", minute),
            ), now)
            assertTrue(store.ambientHistory("a", "future", 0).isEmpty())
            assertTrue(store.ambientHistory("a", "expired", 0).isEmpty())
            assertEquals(1, store.resetAmbientHistory("a"))
            assertTrue(store.ambientHistory("a", "panel", 0).isEmpty())
            assertEquals(1, store.ambientHistory("b", "panel", 0).size)
        }
    }

    @Test fun seededRowsAreIdempotentAndNeverReplaceLiveEvidence() {
        val now = System.currentTimeMillis()
        val minute = now / 60_000L
        fun aggregate(lux: Double) = AmbientMinuteAggregate(
            AmbientHistoryKey("location-a", "ha:source", minute - 1),
        ).also { it.add(lux, 60_000, baselineEligible = true) }

        EntityCatalogStore(context).use { store ->
            store.seedAmbientHistory(listOf(aggregate(10.0)), now)
            store.seedAmbientHistory(listOf(aggregate(30.0)), now)
            assertEquals(10.0, store.ambientHistory("location-a", "ha:source", minute - 2).single().meanLux, 0.001)

            store.recordAmbientHistory(listOf(aggregate(20.0)), now)
            store.seedAmbientHistory(listOf(aggregate(40.0)), now)
            val row = store.ambientHistory("location-a", "ha:source", minute - 2).single()
            assertEquals(15.0, row.meanLux, 0.001)
            assertEquals(2L, row.sampleCount)
        }
    }

    @Test fun activeHaAndIrreplaceablePanelHistoryBothKeepFullSevenDays() {
        val now = System.currentTimeMillis()
        val nowMinute = now / 60_000L
        val oldestMinute = nowMinute - 7L * 24L * 60L
        fun aggregate(contextId: String, sourceId: String, minute: Long) = AmbientMinuteAggregate(
            key = AmbientHistoryKey(contextId, sourceId, minute),
            luxIntegral = 10.0 * 60_000L,
            coverageMs = 60_000L,
            minLux = 10.0,
            maxLux = 10.0,
            lastLux = 10.0,
            sampleCount = 1,
            baselineLogIntegral = kotlin.math.ln1p(10.0) * 60_000L,
            baselineCoverageMs = 60_000L,
        )
        val active = (oldestMinute..nowMinute).map { aggregate("active-location", "ha:selected", it) }
        val panel = (oldestMinute..nowMinute).map { minute ->
            aggregate(if (minute < nowMinute - 5_000L) "old-location" else "active-location", "panel", minute)
        }
        val inactive = ((nowMinute - 5_999L)..nowMinute).map { aggregate("old-location", "ha:explored", it) }

        EntityCatalogStore(context).use { store ->
            store.recordAmbientHistory(
                active + panel + inactive,
                now,
                activeContextId = "active-location",
                activeSourceId = "ha:selected",
            )

            assertEquals(active.size, store.ambientHistory("active-location", "ha:selected", oldestMinute).size)
            val retainedPanel = store.readableDatabase.rawQuery(
                "SELECT COUNT(*) FROM ambient_lux_minute WHERE source_id='panel'",
                emptyArray(),
            ).use { cursor -> cursor.moveToFirst(); cursor.getInt(0) }
            assertEquals(panel.size, retainedPanel)
            val total = store.readableDatabase.rawQuery(
                "SELECT COUNT(*) FROM ambient_lux_minute",
                emptyArray(),
            ).use { cursor -> cursor.moveToFirst(); cursor.getInt(0) }
            assertEquals(24_000, total)
            assertEquals(24_000 - active.size - panel.size, store.ambientHistory("old-location", "ha:explored", oldestMinute).size)
        }
    }
}
