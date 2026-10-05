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
 * These tests pin both halves of the fix: derived rows for purged entities are reclaimed, and rows that
 * carry explicit user intent, or that simply have no entity row yet, are not touched.
 */
@CoreInstrumentation
@RunWith(AndroidJUnit4::class)
class EntityCatalogMembershipPurgeTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before fun cleanBefore() = clean()
    @After fun cleanAfter() = clean()

    @Test fun purgingATombstonedEntityReclaimsItsDerivedMembershipButKeepsUserIntent() {
        EntityCatalogStore(context).use { store ->
            val db = store.writableDatabase
            val purgeAge = System.currentTimeMillis() - TOMBSTONE_RETENTION_GRACE_MS
            seedEntity(db, "sensor.derived", tombstoneAt = purgeAge)
            seedEntity(db, "sensor.pinned", tombstoneAt = purgeAge)
            seedEntity(db, "sensor.excluded", tombstoneAt = purgeAge)
            seedEntity(db, "sensor.live", tombstoneAt = 0)
            seedMembership(db, "sensor.derived")
            seedMembership(db, "sensor.pinned", pinned = 1)
            seedMembership(db, "sensor.excluded", excluded = 1)
            seedMembership(db, "sensor.live")
            // No entity row at all: a dashboard may reference an entity Home Assistant has not reported yet.
            seedMembership(db, "sensor.not_yet_reported")

            db.execSQL("$DELETE_SQL AND e.tombstone_at>0)")

            assertEquals(
                listOf("sensor.excluded", "sensor.live", "sensor.not_yet_reported", "sensor.pinned"),
                membershipIds(db),
            )
        }
    }

    @Test fun aPurgeLeavesNoDerivedMembershipBehind() {
        EntityCatalogStore(context).use { store ->
            val db = store.writableDatabase
            seedEntity(db, "sensor.gone", tombstoneAt = 1L)
            seedMembership(db, "sensor.gone")

            db.execSQL("$DELETE_SQL AND e.tombstone_at>0)")
            db.execSQL("DELETE FROM entity WHERE tombstone_at>0")

            assertEquals(emptyList<String>(), membershipIds(db))
            assertEquals(
                0,
                count(db, "SELECT count(*) FROM dashboard_entity WHERE NOT EXISTS(SELECT 1 FROM entity e " +
                    "WHERE e.instance=dashboard_entity.instance AND e.entity_id=dashboard_entity.entity_id) " +
                    "AND pinned=0 AND excluded=0"),
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

    private fun count(db: SQLiteDatabase, sql: String): Int =
        db.rawQuery(sql, emptyArray()).use { if (it.moveToFirst()) it.getInt(0) else -1 }

    private fun clean() {
        context.deleteDatabase(EntityCatalogStore.DATABASE_NAME)
    }

    private companion object {
        const val INSTANCE = "fixture"
        const val PATH = "home"
        const val TOMBSTONE_RETENTION_GRACE_MS = 90L * 24 * 60 * 60 * 1000
        val DELETE_SQL = EntityCatalogStore.DELETE_MEMBERSHIP_FOR_PURGED_ENTITIES
    }
}
