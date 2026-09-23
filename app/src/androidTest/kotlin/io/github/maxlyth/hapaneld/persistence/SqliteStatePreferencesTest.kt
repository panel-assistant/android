package io.github.maxlyth.hapaneld.persistence

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.maxlyth.hapaneld.CoreInstrumentation
import io.github.maxlyth.hapaneld.dashboard.EntityCatalogStore
import io.github.maxlyth.hapaneld.dashboard.use
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@CoreInstrumentation
@RunWith(AndroidJUnit4::class)
class SqliteStatePreferencesTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val legacyName = "sqlite-state-test-legacy"
    private val bridgeName = "sqlite-state-test-bridge"
    private val namespace = "sqlite-state-test"

    @Before fun cleanBefore() = clean()
    @After fun cleanAfter() = clean()

    @Test fun legacyJournalPreservesTypedStateAcrossDowngradeAndReturnUpgrade() {
        val legacy = context.getSharedPreferences(legacyName, Context.MODE_PRIVATE)
        assertTrue(
            legacy.edit()
                .putString("name", "legacy")
                .putInt("count", 7)
                .putLong("time", 123L)
                .putFloat("ratio", 1.5f)
                .putBoolean("enabled", true)
                .putStringSet("labels", setOf("one", "two"))
                .commit(),
        )
        val bridge = context.getSharedPreferences(bridgeName, Context.MODE_PRIVATE)

        EntityCatalogStore(context).use { helper ->
            val state = SqliteStatePreferences(helper, namespace, legacyName, legacy, bridge)
            assertEquals("legacy", state.getString("name", null))
            assertEquals(7, state.getInt("count", 0))
            assertEquals(123L, state.getLong("time", 0))
            assertEquals(1.5f, state.getFloat("ratio", 0f))
            assertTrue(state.getBoolean("enabled", false))
            assertEquals(setOf("one", "two"), state.getStringSet("labels", emptySet()))

            assertTrue(state.edit().putString("name", "sqlite").remove("enabled").commit())
            assertFalse(state.contains("enabled"))
            assertEquals("sqlite", legacy.getString("name", null))
            assertFalse(legacy.contains("enabled"))
        }

        // Simulate a supported 0.9.x downgrade changing the XML journal.
        assertTrue(legacy.edit().putString("name", "edited-during-downgrade").commit())
        EntityCatalogStore(context).use { helper ->
            val reopened = SqliteStatePreferences(helper, namespace, legacyName, legacy, bridge)
            assertEquals("edited-during-downgrade", reopened.getString("name", null))
            assertFalse(reopened.contains("enabled"))
            val revisions = helper.readableDatabase.rawQuery(
                "SELECT COUNT(*) FROM app_state_revision WHERE namespace=?",
                arrayOf(namespace),
            ).use {
                assertTrue(it.moveToFirst())
                it.getInt(0)
            }
            assertEquals(3, revisions)
        }
    }

    @Test fun firstImportKeepsCredentialsInSqliteButRemovesThemFromTheJournal() {
        val legacy = context.getSharedPreferences(legacyName, Context.MODE_PRIVATE)
        assertTrue(
            legacy.edit()
                .putString("ha_url", "https://ha.example")
                .putString("ha_token", "imported-access")
                .putString("ha_refresh_token", "imported-refresh")
                .commit(),
        )
        val bridge = context.getSharedPreferences(bridgeName, Context.MODE_PRIVATE)
        val excluded = legacyMirrorExclusions("config")

        EntityCatalogStore(context).use { helper ->
            val state = SqliteStatePreferences(
                helper, namespace, legacyName, legacy, bridge, mirrorExcludedKeys = excluded,
            )
            assertEquals("imported-access", state.getString("ha_token", null))
            assertEquals("imported-refresh", state.getString("ha_refresh_token", null))
            assertTrue(sqliteContains(helper.readableDatabase, "ha_token"))
            assertFalse(legacy.contains("ha_token"))
            assertFalse(legacy.contains("ha_refresh_token"))
            assertEquals("https://ha.example", legacy.getString("ha_url", null))
        }
        EntityCatalogStore(context).use { helper ->
            val reopened = SqliteStatePreferences(
                helper, namespace, legacyName, legacy, bridge, mirrorExcludedKeys = excluded,
            )
            assertEquals("imported-access", reopened.getString("ha_token", null))
            assertEquals("imported-refresh", reopened.getString("ha_refresh_token", null))
        }
    }

    @Test fun failedSqliteMarkerCreateAndRemoveRetainStrictInProcessVisibilityUntilRetry() {
        val legacy = context.getSharedPreferences(legacyName, Context.MODE_PRIVATE)
        val bridge = context.getSharedPreferences(bridgeName, Context.MODE_PRIVATE)
        val key = "tame_overlay.com.vendor.one"

        EntityCatalogStore(context).use { helper ->
            val state = SqliteStatePreferences(helper, namespace, legacyName, legacy, bridge)
            val db = helper.writableDatabase
            db.execSQL(
                """CREATE TRIGGER fail_tame_insert BEFORE INSERT ON app_state
                   WHEN NEW.namespace='$namespace' AND NEW.state_key='$key'
                   BEGIN SELECT RAISE(ABORT, 'injected insert failure'); END""",
            )

            assertFalse(state.commitWithDurableVisibility { putString(key, "allow") })
            assertFalse(state.contains(key))
            assertFalse(sqliteContains(db, key))

            db.execSQL("DROP TRIGGER fail_tame_insert")
            assertTrue(state.commitWithDurableVisibility { putString(key, "allow") })
            assertEquals("allow", state.getString(key, null))
            assertTrue(sqliteContains(db, key))

            db.execSQL(
                """CREATE TRIGGER fail_tame_delete BEFORE DELETE ON app_state
                   WHEN OLD.namespace='$namespace' AND OLD.state_key='$key'
                   BEGIN SELECT RAISE(ABORT, 'injected delete failure'); END""",
            )
            assertFalse(state.commitWithDurableVisibility { remove(key) })
            assertEquals("allow", state.getString(key, null))
            assertTrue(sqliteContains(db, key))

            db.execSQL("DROP TRIGGER fail_tame_delete")
            assertTrue(state.commitWithDurableVisibility { remove(key) })
            assertFalse(state.contains(key))
            assertFalse(sqliteContains(db, key))
        }
    }

    private fun sqliteContains(db: android.database.sqlite.SQLiteDatabase, key: String): Boolean =
        db.rawQuery(
            "SELECT 1 FROM app_state WHERE namespace=? AND state_key=?",
            arrayOf(namespace, key),
        ).use { it.moveToFirst() }

    private fun clean() {
        context.getSharedPreferences(legacyName, Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences(bridgeName, Context.MODE_PRIVATE).edit().clear().commit()
        context.deleteDatabase(EntityCatalogStore.DATABASE_NAME)
    }
}
