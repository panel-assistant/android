package io.github.maxlyth.hapaneld.dashboard

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.maxlyth.hapaneld.Config
import io.github.maxlyth.hapaneld.CoreInstrumentation
import io.github.maxlyth.hapaneld.sensors.ProximityLearningRuntime
import io.github.maxlyth.hapaneld.sensors.ProximityWakeInvalidationAuthority
import io.github.maxlyth.hapaneld.sensors.ProximityWakeInvalidationJournal
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@CoreInstrumentation
@RunWith(AndroidJUnit4::class)
class ProximityHistoryStoreTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val configPreferences = "proximity-runtime-round-trip-test"

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

    @Test fun behaviorEpochPersistsWakeEvidenceLossAcrossRuntimeRecreation() {
        val sourceIdentity = "hal:8:round-trip-proximity"
        val fingerprint = ProximityLearningRuntime.fingerprint(sourceIdentity)
        val now = System.currentTimeMillis()
        val snapshot = JSONObject().apply {
            put("schema", 1)
            put("guidedReady", true)
            put("engineSchema", 1)
            put("farRaw", 100.0)
            put("nearRaw", 0.0)
            put("noise", 1.0)
            put("mode", "GRADED")
            put("polarity", "NEAR_IS_LOWER")
            put("completedExcursions", 8)
            put("deliberateExamples", 5)
        }.toString()
        EntityCatalogStore(context).use { store ->
            store.writeProximityBatch(
                EntityCatalogStore.ProximityModelRow(
                    fingerprint = fingerprint,
                    algorithmVersion = ProximityLearningRuntime.ALGORITHM_VERSION,
                    behaviorSignature = "GRADED:NEAR_IS_LOWER",
                    snapshotJson = snapshot,
                    ready = true,
                    updatedAt = now,
                ),
                emptyList(),
                emptyList(),
                now,
            )
        }

        val config = Config(context.getSharedPreferences(configPreferences, android.content.Context.MODE_PRIVATE))
        val runtime = ProximityLearningRuntime(
            context,
            config,
            sourceIdentity,
            sparseLearningSource = false,
            legacySeedEligible = false,
        )
        runtime.observe(100f, 0)
        runtime.observe(100f, 100)
        runtime.observe(100f, 200)
        runtime.observe(100f, 300)
        assertTrue(runtime.isWaveReady())

        runtime.observe(20f, 500)
        runtime.observe(20f, 15_500)
        runtime.observe(20f, 30_501)
        assertFalse(runtime.isWaveReady())
        runtime.closeAsync().get(5, TimeUnit.SECONDS)

        val stored = EntityCatalogStore(context).use { it.readProximityModel(fingerprint)!! }
        val storedJson = JSONObject(stored.snapshotJson)
        assertFalse(storedJson.getBoolean("guidedReady"))
        assertEquals(0, storedJson.getInt("deliberateExamples"))

        val recreated = ProximityLearningRuntime(
            context,
            config,
            sourceIdentity,
            sparseLearningSource = false,
            legacySeedEligible = false,
        )
        recreated.observe(100f, 0)
        recreated.observe(100f, 100)
        recreated.observe(100f, 200)
        recreated.observe(100f, 300)
        assertFalse(recreated.isWaveReady())
        recreated.closeAsync().get(5, TimeUnit.SECONDS)
    }

    @Test fun failedFirstCheckpointAndAbruptRecreationCannotRestoreWakeReadiness() {
        val sourceIdentity = "hal:8:failed-checkpoint-proximity"
        val fingerprint = ProximityLearningRuntime.fingerprint(sourceIdentity)
        val now = System.currentTimeMillis()
        val snapshot = JSONObject().apply {
            put("schema", 1)
            put("guidedReady", true)
            put("engineSchema", 1)
            put("farRaw", 100.0)
            put("nearRaw", 0.0)
            put("noise", 1.0)
            put("mode", "GRADED")
            put("polarity", "NEAR_IS_LOWER")
            put("completedExcursions", 8)
            put("deliberateExamples", 5)
        }.toString()
        EntityCatalogStore(context).use { store ->
            store.writeProximityBatch(
                EntityCatalogStore.ProximityModelRow(
                    fingerprint = fingerprint,
                    algorithmVersion = ProximityLearningRuntime.ALGORITHM_VERSION,
                    behaviorSignature = "GRADED:NEAR_IS_LOWER",
                    snapshotJson = snapshot,
                    ready = true,
                    updatedAt = now,
                ),
                emptyList(),
                emptyList(),
                now,
            )
        }

        val failureArmed = AtomicBoolean(false)
        val failFirst = AtomicBoolean(true)
        val failed = CountDownLatch(1)
        val config = Config(context.getSharedPreferences(configPreferences, android.content.Context.MODE_PRIVATE))
        val interrupted = ProximityLearningRuntime(
            context,
            config,
            sourceIdentity,
            sparseLearningSource = false,
            legacySeedEligible = false,
            failModelPersistenceForTest = {
                failureArmed.get() && failFirst.getAndSet(false).also { if (it) failed.countDown() }
            },
        )
        interrupted.observe(100f, 0)
        interrupted.observe(100f, 100)
        interrupted.observe(100f, 200)
        interrupted.observe(100f, 300)
        assertTrue(interrupted.isWaveReady())
        failureArmed.set(true)
        interrupted.observe(20f, 500)
        interrupted.observe(20f, 15_500)
        interrupted.observe(20f, 30_501)
        assertTrue(failed.await(5, TimeUnit.SECONDS))

        // Reconstruct without orderly close: SQLite still contains the old ready row, so only the
        // synchronous invalidation authority can prevent its guided evidence being trusted.
        val stillStale = EntityCatalogStore(context).use { it.readProximityModel(fingerprint)!! }
        assertTrue(JSONObject(stillStale.snapshotJson).getBoolean("guidedReady"))
        val reconstructed = ProximityLearningRuntime(
            context,
            config,
            sourceIdentity,
            sparseLearningSource = false,
            legacySeedEligible = false,
        )
        reconstructed.observe(100f, 0)
        reconstructed.observe(100f, 100)
        reconstructed.observe(100f, 200)
        reconstructed.observe(100f, 300)
        assertFalse(reconstructed.isWaveReady())

        interrupted.closeAsync().get(5, TimeUnit.SECONDS)
        reconstructed.closeAsync().get(5, TimeUnit.SECONDS)
    }

    @Test fun markerWriteFailureSynchronouslySanitizesTheDurableFallback() {
        val sourceIdentity = "hal:8:marker-fallback-proximity"
        val fingerprint = ProximityLearningRuntime.fingerprint(sourceIdentity)
        seedReadyModel(fingerprint)
        val journal = FakeInvalidationAuthority(markResult = false)
        val config = Config(context.getSharedPreferences(configPreferences, android.content.Context.MODE_PRIVATE))
        val runtime = ProximityLearningRuntime(
            context,
            config,
            sourceIdentity,
            sparseLearningSource = false,
            legacySeedEligible = false,
            invalidationJournalForTest = journal,
        )
        runtime.observe(100f, 0)
        runtime.observe(100f, 100)
        runtime.observe(100f, 200)
        runtime.observe(100f, 300)
        assertTrue(runtime.isWaveReady())

        runtime.observe(20f, 500)
        runtime.observe(20f, 15_500)
        runtime.observe(20f, 30_501)

        val durable = EntityCatalogStore(context).use { it.readProximityModel(fingerprint)!! }
        assertFalse(JSONObject(durable.snapshotJson).getBoolean("guidedReady"))
        assertEquals(0, JSONObject(durable.snapshotJson).getInt("deliberateExamples"))
        assertFalse(runtime.isWaveReady())
        runtime.closeAsync().get(5, TimeUnit.SECONDS)
    }

    @Test fun startupDistinguishesConfirmedAbsenceFromUnknownModelRead() {
        val config = Config(context.getSharedPreferences(configPreferences, android.content.Context.MODE_PRIVATE))
        val absent = ProximityLearningRuntime(
            context,
            config,
            "hal:8:confirmed-absent",
            sparseLearningSource = false,
            legacySeedEligible = false,
        )
        assertFalse(absent.hasUnresolvedModelReadFailure())

        val unknown = ProximityLearningRuntime(
            context,
            config,
            "hal:8:unknown-read",
            sparseLearningSource = false,
            legacySeedEligible = false,
            failModelReadForTest = { true },
        )
        assertTrue(unknown.hasUnresolvedModelReadFailure())
        assertFalse(unknown.isWaveReady())
        absent.closeAsync().get(5, TimeUnit.SECONDS)
        unknown.closeAsync().get(5, TimeUnit.SECONDS)
    }

    @Test fun malformedMarkedModelIsRejectedWithoutStartupLoop() {
        val sourceIdentity = "hal:8:malformed-marked-proximity"
        val fingerprint = ProximityLearningRuntime.fingerprint(sourceIdentity)
        val now = System.currentTimeMillis()
        EntityCatalogStore(context).use { store ->
            store.writeProximityBatch(
                EntityCatalogStore.ProximityModelRow(
                    fingerprint,
                    ProximityLearningRuntime.ALGORITHM_VERSION,
                    "GRADED:NEAR_IS_LOWER",
                    "{malformed",
                    ready = true,
                    updatedAt = now,
                ),
                emptyList(),
                emptyList(),
                now,
            )
        }
        val journal = ProximityWakeInvalidationJournal(context)
        assertTrue(journal.mark(fingerprint, "pending-token"))

        val runtime = ProximityLearningRuntime(
            context,
            Config(context.getSharedPreferences(configPreferences, android.content.Context.MODE_PRIVATE)),
            sourceIdentity,
            sparseLearningSource = false,
            legacySeedEligible = false,
        )

        assertFalse(runtime.isWaveReady())
        assertTrue(runtime.hasPendingWakeInvalidation())
        assertEquals(null, EntityCatalogStore(context).use { it.readProximityModel(fingerprint) })
        runtime.closeAsync().get(5, TimeUnit.SECONDS)
    }

    @Test fun journalTokenMismatchCannotClearNewerInvalidation() {
        val fingerprint = "b".repeat(64)
        val journal = ProximityWakeInvalidationJournal(context)
        assertTrue(journal.mark(fingerprint, "old-token"))
        assertTrue(journal.mark(fingerprint, "new-token"))
        assertFalse(journal.clear(fingerprint, "old-token"))
        assertEquals("new-token", journal.read(fingerprint))
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

    private fun seedReadyModel(fingerprint: String) {
        val now = System.currentTimeMillis()
        val snapshot = JSONObject().apply {
            put("schema", 1)
            put("guidedReady", true)
            put("engineSchema", 1)
            put("farRaw", 100.0)
            put("nearRaw", 0.0)
            put("noise", 1.0)
            put("mode", "GRADED")
            put("polarity", "NEAR_IS_LOWER")
            put("completedExcursions", 8)
            put("deliberateExamples", 5)
        }.toString()
        EntityCatalogStore(context).use { store ->
            store.writeProximityBatch(
                EntityCatalogStore.ProximityModelRow(
                    fingerprint,
                    ProximityLearningRuntime.ALGORITHM_VERSION,
                    "GRADED:NEAR_IS_LOWER",
                    snapshot,
                    ready = true,
                    updatedAt = now,
                ),
                emptyList(),
                emptyList(),
                now,
            )
        }
    }

    private class FakeInvalidationAuthority(
        private val markResult: Boolean = true,
        private val clearResult: Boolean = true,
    ) : ProximityWakeInvalidationAuthority {
        private var token: String? = null

        override fun read(fingerprint: String): String? = token

        override fun mark(fingerprint: String, token: String): Boolean {
            if (markResult) this.token = token
            return markResult
        }

        override fun clear(fingerprint: String, token: String): Boolean {
            if (!clearResult || this.token != token) return false
            this.token = null
            return true
        }
    }

    private fun clean() {
        context.getSharedPreferences(configPreferences, android.content.Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences(
            ProximityWakeInvalidationJournal.PREFERENCES_NAME,
            android.content.Context.MODE_PRIVATE,
        ).edit().clear().commit()
        context.deleteDatabase(EntityCatalogStore.DATABASE_NAME)
    }
}
