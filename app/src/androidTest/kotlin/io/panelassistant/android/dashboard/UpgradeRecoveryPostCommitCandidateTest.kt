package io.panelassistant.android.dashboard

import android.content.Context
import android.database.sqlite.SQLiteDatabase
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

/**
 * The upgrade recovery must be decided on the set the apply will actually install, not on a preview
 * taken before the commit.
 *
 * `commitSync` opens by advancing `missing_streak` for every entity of the instance and only resets it
 * for entities this scan reported; `activeIds` then excludes any row whose entity has reached three.
 * So a row sitting at two, whose entity has dropped out of Home Assistant, is present before the commit
 * and gone after it. A recovery that measured the candidate beforehand would see a sufficient set,
 * overwrite the retained subscription with a smaller one and clear the one-shot migration latch, and
 * nothing afterwards would have any record that the missing ids were ever in the filter.
 *
 * These tests run against the real store because that arithmetic lives in SQL; a JVM double would only
 * restate the assumption under test.
 */
@CoreInstrumentation
@RunWith(AndroidJUnit4::class)
class UpgradeRecoveryPostCommitCandidateTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before fun cleanBefore() = clean()
    @After fun cleanAfter() = clean()

    @Test fun aRowAtTwoMissesIsPresentBeforeTheCommitAndGoneAfterIt() {
        EntityCatalogStore(context).use { store ->
            val db = store.writableDatabase
            // Both are runtime-referenced members of the retained filter. `stale` is the one Home
            // Assistant has stopped reporting, and it is already at two consecutive misses.
            seedEntity(db, "sensor.kept", missingStreak = 0)
            seedEntity(db, "sensor.stale", missingStreak = 2)
            seedMembership(db, "sensor.kept", lastAccessAt = System.currentTimeMillis())
            seedMembership(db, "sensor.stale", lastAccessAt = System.currentTimeMillis())

            val retained = listOf("sensor.kept", "sensor.stale")
            val before = store.activeIds(INSTANCE, PATH, System.currentTimeMillis())
            assertEquals("both rows are visible before the commit", retained, before)
            assertTrue(
                "a pre-commit measurement would admit the recovery",
                upgradeRecoveryPreservesFilter(retained, before),
            )

            // The scan reports only sensor.kept, exactly as Home Assistant would after the other left.
            store.commitSync(
                instance = INSTANCE,
                path = PATH,
                states = listOf(EntityCatalogStore.StateRow("sensor.kept", "on", "")),
                metadata = emptyMap(),
                configJson = "{}",
                configHash = "hash",
                derived = emptySet(),
                unresolved = emptyList(),
                status = "blocked",
                now = System.currentTimeMillis(),
            )

            val after = store.activeIds(INSTANCE, PATH, System.currentTimeMillis())
            assertEquals("the third miss drops the row", listOf("sensor.kept"), after)
            assertFalse(
                "the post-commit candidate must refuse the recovery and leave the decision hold",
                upgradeRecoveryPreservesFilter(retained, after),
            )
        }
    }

    @Test fun aPinnedRowAtTwoMissesIsDroppedByTheSameArithmetic() {
        EntityCatalogStore(context).use { store ->
            val db = store.writableDatabase
            seedEntity(db, "sensor.kept", missingStreak = 0)
            seedEntity(db, "sensor.pinned_stale", missingStreak = 2)
            seedMembership(db, "sensor.kept", lastAccessAt = System.currentTimeMillis())
            // A pin is user intent and survives a purge, but it is not exempt from the missing-streak
            // rule, so the recovery must not assume a pinned id will still be there.
            seedMembership(db, "sensor.pinned_stale", pinned = 1)

            val retained = listOf("sensor.kept", "sensor.pinned_stale")
            assertTrue(upgradeRecoveryPreservesFilter(retained, store.activeIds(INSTANCE, PATH, System.currentTimeMillis())))

            store.commitSync(
                instance = INSTANCE,
                path = PATH,
                states = listOf(EntityCatalogStore.StateRow("sensor.kept", "on", "")),
                metadata = emptyMap(),
                configJson = "{}",
                configHash = "hash",
                derived = emptySet(),
                unresolved = emptyList(),
                status = "blocked",
                now = System.currentTimeMillis(),
            )

            val after = store.activeIds(INSTANCE, PATH, System.currentTimeMillis())
            assertFalse("the pinned row is gone", after.contains("sensor.pinned_stale"))
            assertFalse(upgradeRecoveryPreservesFilter(retained, after))
        }
    }

    @Test fun aCompleteSurvivingSetStillAdmitsTheRecovery() {
        EntityCatalogStore(context).use { store ->
            val db = store.writableDatabase
            seedEntity(db, "sensor.one", missingStreak = 2)
            seedEntity(db, "sensor.two", missingStreak = 0)
            seedMembership(db, "sensor.one", lastAccessAt = System.currentTimeMillis())
            seedMembership(db, "sensor.two", lastAccessAt = System.currentTimeMillis())

            val retained = listOf("sensor.one", "sensor.two")
            // This scan reports both, so the streak resets and nothing is lost.
            store.commitSync(
                instance = INSTANCE,
                path = PATH,
                states = listOf(
                    EntityCatalogStore.StateRow("sensor.one", "on", ""),
                    EntityCatalogStore.StateRow("sensor.two", "on", ""),
                ),
                metadata = emptyMap(),
                configJson = "{}",
                configHash = "hash",
                derived = emptySet(),
                unresolved = emptyList(),
                status = "blocked",
                now = System.currentTimeMillis(),
            )

            val after = store.activeIds(INSTANCE, PATH, System.currentTimeMillis())
            assertEquals(retained, after)
            assertTrue(
                "a recovery that genuinely preserves the whole filter is still allowed",
                upgradeRecoveryPreservesFilter(retained, after),
            )
        }
    }

    private fun seedEntity(db: SQLiteDatabase, entityId: String, missingStreak: Int) {
        db.execSQL(
            "INSERT OR REPLACE INTO entity(instance,entity_id,state,attributes_json,metadata_json," +
                "first_seen_at,last_seen_at,missing_streak,tombstone_at) VALUES(?,?,'on','{}','{}',?,?,?,0)",
            arrayOf<Any?>(INSTANCE, entityId, 1L, 1L, missingStreak),
        )
    }

    private fun seedMembership(
        db: SQLiteDatabase,
        entityId: String,
        pinned: Int = 0,
        lastAccessAt: Long = 0,
    ) {
        db.execSQL(
            "INSERT OR REPLACE INTO dashboard_entity(instance,path,entity_id,pinned,excluded,last_access_at) " +
                "VALUES(?,?,?,?,0,?)",
            arrayOf<Any?>(INSTANCE, PATH, entityId, pinned, lastAccessAt),
        )
    }

    private fun clean() {
        context.deleteDatabase(EntityCatalogStore.DATABASE_NAME)
    }

    private companion object {
        const val INSTANCE = "fixture"
        const val PATH = "/dashboard"
    }
}
