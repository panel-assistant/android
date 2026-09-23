package io.github.maxlyth.hapaneld.dashboard

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.maxlyth.hapaneld.CoreInstrumentation
import io.github.maxlyth.hapaneld.persistence.ConfigVault
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end proof of the crisis path: a database this build cannot open is set aside, a fresh one is
 * created, and configuration comes back on its own instead of the owner losing their dashboard.
 */
@CoreInstrumentation
@RunWith(AndroidJUnit4::class)
class ConfigVaultRecoveryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val vaultDir get() = File(context.filesDir, ConfigVault.VAULT_DIRECTORY)
    private val importedDir get() = File(context.filesDir, "device-profiles/imported")

    @Before fun cleanBefore() = clean()
    @After fun cleanAfter() = clean()

    @Test fun configurationSurvivesADatabaseThisBuildCannotOpen() {
        seedConfiguredPanel()
        // A newer database that this build must set aside — the path that used to reset config.
        tooNewDatabase()

        EntityCatalogStore(context).use { store ->
            val db = store.writableDatabase
            assertEquals(EntityCatalogSchema.CURRENT_VERSION, db.version)
            assertEquals(
                "https://ha.example.test",
                scalar(db, "SELECT value_text FROM app_state WHERE namespace='config' AND state_key='ha_url'"),
            )
            assertEquals(
                "recovered rows must reference a real revision",
                "1",
                scalar(db, "SELECT count(*) FROM app_state a JOIN app_state_revision r ON a.revision=r.revision " +
                    "WHERE a.state_key='ha_url'"),
            )
        }
        assertEquals("imported profiles must come back too", "id: custom", File(importedDir, "custom.yaml").readText())
    }

    /** Restoring over live configuration is the dangerous direction; a populated store is left alone. */
    @Test fun anExistingConfigurationIsNeverOverwritten() {
        seedConfiguredPanel()
        EntityCatalogStore(context).use { store ->
            store.writableDatabase.execSQL(
                "UPDATE app_state SET value_text='https://changed.example.test' WHERE state_key='ha_url'",
            )
        }
        EntityCatalogStore(context).use { store ->
            assertEquals(
                "https://changed.example.test",
                scalar(store.writableDatabase, "SELECT value_text FROM app_state WHERE state_key='ha_url'"),
            )
        }
    }

    /** A first install has no vault, and clearing app data removes it, so a deliberate reset stays one. */
    @Test fun aFirstInstallRestoresNothing() {
        EntityCatalogStore(context).use { store ->
            assertEquals("0", scalar(store.writableDatabase, "SELECT count(*) FROM app_state"))
        }
    }

    @Test fun aCorruptVaultIsIgnoredRatherThanRestored() {
        seedConfiguredPanel()
        ConfigVault.generations(vaultDir).forEach { file ->
            file.writeText(file.readText().replace("ha.example.test", "evil.example.test"))
        }
        tooNewDatabase()

        EntityCatalogStore(context).use { store ->
            assertEquals(
                "a generation failing its digest must not be restored",
                "0",
                scalar(store.writableDatabase, "SELECT count(*) FROM app_state"),
            )
        }
    }

    /** Builds a panel with real configuration and an imported profile, then vaults it via a migration. */
    private fun seedConfiguredPanel() {
        importedDir.mkdirs()
        File(importedDir, "custom.yaml").writeText("id: custom")
        EntityCatalogStore(context).use { store ->
            val db = store.writableDatabase
            db.execSQL("INSERT INTO app_state_revision(committed_at,namespace,source) VALUES(1,'config','test')")
            db.execSQL(
                "INSERT INTO app_state(namespace,state_key,value_type,value_text,updated_at,revision) " +
                    "VALUES('config','ha_url','string','https://ha.example.test',1,1)",
            )
        }
        // Force the vault to be written by presenting a version difference on the next open.
        stampVersion(EntityCatalogSchema.CURRENT_VERSION - 1)
        EntityCatalogStore(context).use { it.writableDatabase.version }
        assertTrue("precondition: a generation exists", ConfigVault.generations(vaultDir).isNotEmpty())
    }

    private fun tooNewDatabase() = stampVersion(EntityCatalogSchema.CURRENT_VERSION + 5)

    private fun stampVersion(version: Int) {
        SQLiteDatabase.openDatabase(
            context.getDatabasePath(EntityCatalogStore.DATABASE_NAME).path, null, SQLiteDatabase.OPEN_READWRITE,
        ).use { it.version = version }
    }

    private fun scalar(db: SQLiteDatabase, sql: String): String? =
        db.rawQuery(sql, emptyArray()).use { if (it.moveToFirst()) it.getString(0) else null }

    private fun clean() {
        context.deleteDatabase(EntityCatalogStore.DATABASE_NAME)
        val target = context.getDatabasePath(EntityCatalogStore.DATABASE_NAME)
        target.parentFile?.listFiles()?.filter { it.name.startsWith("${target.name}.v") }?.forEach { it.delete() }
        vaultDir.deleteRecursively()
        importedDir.deleteRecursively()
    }
}
