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
class EntityTrafficSummaryStoreTest {
    private val context = HistoricalCatalogFixture.isolatedContext(
        ApplicationProvider.getApplicationContext<android.content.Context>(), "traffic-summary",
    )

    @Before fun cleanBefore() {
        context.deleteDatabase(EntityCatalogStore.DATABASE_NAME)
    }

    @After fun cleanAfter() {
        context.deleteDatabase(EntityCatalogStore.DATABASE_NAME)
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
