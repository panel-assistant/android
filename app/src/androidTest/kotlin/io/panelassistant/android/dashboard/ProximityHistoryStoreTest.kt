package io.panelassistant.android.dashboard

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.panelassistant.android.CoreInstrumentation
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@CoreInstrumentation
@RunWith(AndroidJUnit4::class)
class ProximityHistoryStoreTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before fun cleanBefore() = clean()

    @After fun cleanAfter() = clean()

    @Test fun modelCheckpointPreservesExistingRollupsAndEpisodes() {
        val fingerprint = "a".repeat(64)
        val now = System.currentTimeMillis()
        val bucket = now / (5L * 60_000L)
        EntityCatalogStore(context).use { store ->
            store.writeProximityBatch(
                model(fingerprint, "first", now),
                listOf(
                    EntityCatalogStore.ProximityRollupRow(
                        fingerprint, bucket, 4, 0.0, 1.0, 2.0, 2.0, 1, 1,
                    ),
                ),
                listOf(
                    EntityCatalogStore.ProximityEpisodeRow(
                        fingerprint, now, 500, 100, completed = true, guided = false,
                    ),
                ),
                now,
            )

            store.writeProximityBatch(
                model(fingerprint, "second", now + 1),
                listOf(
                    EntityCatalogStore.ProximityRollupRow(
                        fingerprint, bucket, 2, -1.0, 2.0, 1.0, 5.0, 2, 1,
                    ),
                ),
                emptyList(),
                now + 1,
            )

            store.readableDatabase.rawQuery(
                "SELECT (SELECT COUNT(*) FROM proximity_sample)," +
                    "(SELECT COUNT(*) FROM proximity_episode)," +
                    "(SELECT behavior_signature FROM proximity_model WHERE fingerprint=?)," +
                    "sample_count,raw_min,raw_max,raw_sum,raw_sum_squares,excursion_count,gesture_count " +
                    "FROM proximity_sample WHERE fingerprint=? AND bucket=?",
                arrayOf(fingerprint, fingerprint, bucket.toString()),
            ).use { cursor ->
                cursor.moveToFirst()
                assertEquals(1, cursor.getInt(0))
                assertEquals(1, cursor.getInt(1))
                assertEquals("second", cursor.getString(2))
                assertEquals(6, cursor.getInt(3))
                assertEquals(-1.0, cursor.getDouble(4), 0.0)
                assertEquals(2.0, cursor.getDouble(5), 0.0)
                assertEquals(3.0, cursor.getDouble(6), 0.0)
                assertEquals(7.0, cursor.getDouble(7), 0.0)
                assertEquals(3, cursor.getInt(8))
                assertEquals(2, cursor.getInt(9))
            }
        }
    }

    private fun model(fingerprint: String, signature: String, now: Long) =
        EntityCatalogStore.ProximityModelRow(
            fingerprint = fingerprint,
            algorithmVersion = 1,
            behaviorSignature = signature,
            snapshotJson = "{}",
            ready = true,
            updatedAt = now,
        )

    private fun clean() {
        context.deleteDatabase(EntityCatalogStore.DATABASE_NAME)
    }
}
