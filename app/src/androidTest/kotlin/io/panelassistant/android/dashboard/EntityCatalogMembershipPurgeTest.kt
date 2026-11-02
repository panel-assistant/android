package io.panelassistant.android.dashboard

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.panelassistant.android.CoreInstrumentation
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `dashboard_entity` is logically a child of `entity` with no declared foreign key, so an entity purge used to
 * strand its dashboard_entity rows permanently — dashboard_entity has no age-based prune, and the stranding was
 * produced by the same routines that reclaim space.
 *
 * The real sync pins both halves of the fix: derived rows for purged entities are reclaimed, and rows that
 * carry explicit user intent, or that simply have no entity row yet, are not touched.
 */
@CoreInstrumentation
@RunWith(AndroidJUnit4::class)
class EntityCatalogMembershipPurgeTest {
    private val context = HistoricalCatalogFixture.isolatedContext(
        ApplicationProvider.getApplicationContext<Context>(), "membership-purge",
    )

    @Before fun cleanBefore() = clean()
    @After fun cleanAfter() = clean()

    @Test fun syncingPurgesExpiredDerivedMembershipButKeepsUserIntentAndRecentEntities() {
        val now = 1_800_000_000_000L
        val day = 24L * 60 * 60 * 1000
        EntityCatalogStore(context).use { store ->
            val db = store.writableDatabase
            // Use the documented thirty-day lifetime independently of the production cutoff helper.
            val expired = now - 31L * day
            seedEntity(db, "sensor.derived", tombstoneAt = expired)
            seedEntity(db, "sensor.pinned", tombstoneAt = expired)
            seedEntity(db, "sensor.excluded", tombstoneAt = expired)
            seedEntity(db, "sensor.recent", tombstoneAt = now - day)
            seedEntity(db, "sensor.live", tombstoneAt = 0)
            seedMembership(db, "sensor.derived")
            seedMembership(db, "sensor.pinned", pinned = 1)
            seedMembership(db, "sensor.excluded", excluded = 1)
            seedMembership(db, "sensor.recent")
            seedMembership(db, "sensor.live")
            // No entity row at all: a dashboard may reference an entity Home Assistant has not reported yet.
            seedMembership(db, "sensor.not_yet_reported")

            store.commitSync(
                instance = INSTANCE,
                path = PATH,
                states = listOf(EntityCatalogStore.StateRow("sensor.live", "on", "{}")),
                metadata = emptyMap(),
                configJson = "{}",
                configHash = "fixture",
                derived = emptySet(),
                unresolved = emptyList(),
                status = "active",
                now = now,
            )

            assertEquals(
                listOf("sensor.excluded", "sensor.live", "sensor.not_yet_reported", "sensor.pinned", "sensor.recent"),
                membershipIds(db),
            )
            assertEquals(
                "the sync must actually purge expired entities, while retaining live and recent ones",
                listOf("sensor.live", "sensor.recent"),
                db.rawQuery("SELECT entity_id FROM entity ORDER BY entity_id", emptyArray()).use { cursor ->
                    buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
                },
            )
        }
    }

    private fun seedEntity(db: SQLiteDatabase, entityId: String, tombstoneAt: Long) {
        db.execSQL(
            "INSERT OR REPLACE INTO entity(instance,entity_id,first_seen_at,last_seen_at,tombstone_at) VALUES(?,?,?,?,?)",
            arrayOf<Any?>(INSTANCE, entityId, 1L, 1L, tombstoneAt),
        )
    }

    private fun seedMembership(db: SQLiteDatabase, entityId: String, pinned: Int = 0, excluded: Int = 0) {
        db.execSQL(
            "INSERT OR REPLACE INTO dashboard_entity(instance,path,entity_id,pinned,excluded) VALUES(?,?,?,?,?)",
            arrayOf<Any?>(INSTANCE, PATH, entityId, pinned, excluded),
        )
    }

    private fun membershipIds(db: SQLiteDatabase): List<String> =
        db.rawQuery("SELECT entity_id FROM dashboard_entity ORDER BY entity_id", emptyArray()).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }

    private fun clean() = HistoricalCatalogFixture.clean(context)

    private companion object {
        const val INSTANCE = "fixture"
        const val PATH = "home"
    }
}
