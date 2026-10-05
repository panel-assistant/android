package io.panelassistant.android.dashboard

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.panelassistant.android.CoreInstrumentation
import io.panelassistant.android.persistence.ConfigVault
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves configuration capture during a real upgrade and vault restore into a fresh database.
 * An incompatible existing database must instead be preserved and refused.
 */
@CoreInstrumentation
@RunWith(AndroidJUnit4::class)
class ConfigVaultRecoveryTest {
    private val context = HistoricalCatalogFixture.isolatedContext(
        ApplicationProvider.getApplicationContext<Context>(), "vault-recovery",
    )
    private val vaultDir get() = File(context.filesDir, ConfigVault.VAULT_DIRECTORY)
    private val importedDir get() = File(context.filesDir, "device-profiles/imported")

    @Before fun cleanBefore() = clean()
    @After fun cleanAfter() = clean()

    @Test fun configurationAndProfilesRestoreIntoAFreshDatabase() {
        seedConfiguredPanel()
        HistoricalCatalogFixture.removeDatabase(context)
        assertTrue(File(importedDir, "custom.yaml").delete())

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
        HistoricalCatalogFixture.removeDatabase(context)
        assertTrue(File(importedDir, "custom.yaml").delete())

        EntityCatalogStore(context).use { store ->
            assertEquals(
                "a generation failing its digest must not be restored",
                "0",
                scalar(store.writableDatabase, "SELECT count(*) FROM app_state"),
            )
        }
        assertTrue("a corrupt generation must not restore profiles", !File(importedDir, "custom.yaml").exists())
    }

    @Test fun aTooNewDatabaseWithoutASnapshotIsRefusedWithoutLosingConfiguration() {
        seedConfiguredPanel()
        val target = context.getDatabasePath(EntityCatalogStore.DATABASE_NAME)
        assertTrue(preMigrationBackupFile(target, 11).delete())
        tooNewDatabase()
        val before = target.readBytes()
        val refusal = try {
            EntityCatalogStore(context).use { it.writableDatabase }
            throw AssertionError("a too-new database without a snapshot must not open")
        } catch (expected: DatabaseCompatibilityException) {
            expected
        }
        assertEquals(DatabaseCompatibilityRefusal.PRIMARY_ABOVE_MAXIMUM_WITHOUT_PREMIGRATE, refusal.refusal)
        assertTrue("refusal must preserve the database bytes", before.contentEquals(target.readBytes()))
        SQLiteDatabase.openDatabase(target.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            assertEquals(EntityCatalogSchema.CURRENT_VERSION + 5, db.version)
            assertEquals("https://ha.example.test", scalar(db, "SELECT value_text FROM app_state WHERE state_key='ha_url'"))
        }
    }

    /** Builds a panel with real configuration and an imported profile, then vaults it via a migration. */
    private fun seedConfiguredPanel() {
        importedDir.mkdirs()
        File(importedDir, "custom.yaml").writeText("id: custom")
        HistoricalCatalogFixture.create(context).use { db ->
            db.execSQL("INSERT INTO app_state_revision(committed_at,namespace,source) VALUES(1,'config','test')")
            db.execSQL(
                "INSERT INTO app_state(namespace,state_key,value_type,value_text,updated_at,revision) " +
                    "VALUES('config','ha_url','string','https://ha.example.test',1,1)",
            )
        }
        // Opening the real v11 structure captures configuration before migrating it.
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

    private fun clean() = HistoricalCatalogFixture.clean(context)
}
