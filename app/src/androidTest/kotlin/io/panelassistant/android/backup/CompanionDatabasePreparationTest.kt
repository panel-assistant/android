package io.panelassistant.android.backup

import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.panelassistant.android.CoreInstrumentation
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@CoreInstrumentation
@RunWith(AndroidJUnit4::class)
class CompanionDatabasePreparationTest {
    private val cacheDir: File
        get() = ApplicationProvider.getApplicationContext<android.content.Context>().cacheDir

    @Test fun repairsAndValidatesDatabaseWithoutExternalSqliteExecutable() {
        val source = createDatabase()
        val plan = CompanionRestore.Plan(
            "io.homeassistant.companion.android.minimal",
            listOf(
                CompanionRestore.FilePayload(CompanionRestore.DATABASE_FILE, source, deleteOnClose = false),
                payload("shared_prefs/session_0.xml", "session".toByteArray()),
            ),
        )

        val preparation = CompanionDatabasePreparation.prepare(plan, cacheDir)!!

        try {
            assertEquals(1, preparation.repairedInternalUrls)
            assertEquals(plan.files.map { it.relativePath }, preparation.files.map { it.relativePath })
            assertTrue(preparation.files[1].file.readBytes().contentEquals("session".toByteArray()))
            SQLiteDatabase.openDatabase(
                preparation.files.first().file.path,
                null,
                SQLiteDatabase.OPEN_READONLY,
            ).use { db ->
                assertEquals(
                    "https://ha.example",
                    DatabaseUtils.stringForQuery(db, "SELECT internal_url FROM servers WHERE id=1", null),
                )
                assertEquals("ok", scalar(db, "PRAGMA quick_check(1)"))
            }
        } finally {
            preparation.close()
            plan.close()
            source.delete()
        }
    }

    @Test fun rejectsBytesThatAreNotAValidCompanionDatabase() {
        val plan = CompanionRestore.Plan(
            "io.homeassistant.companion.android.minimal",
            listOf(payload(CompanionRestore.DATABASE_FILE, "not sqlite".toByteArray())),
        )

        try {
            assertNull(CompanionDatabasePreparation.prepare(plan, cacheDir))
        } finally {
            plan.close()
        }
    }

    private fun payload(relativePath: String, bytes: ByteArray): CompanionRestore.FilePayload =
        CompanionRestore.FilePayload(
            relativePath,
            File.createTempFile("companion-payload-", ".bin", cacheDir).apply { writeBytes(bytes) },
        )

    private fun createDatabase(): File {
        val file = File.createTempFile("companion-source-", ".db", cacheDir)
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL(
                "CREATE TABLE servers (id INTEGER PRIMARY KEY, internal_url TEXT, external_url TEXT)",
            )
            db.execSQL(
                "INSERT INTO servers(id, internal_url, external_url) VALUES(1, '', 'https://ha.example')",
            )
            db.execSQL(
                "INSERT INTO servers(id, internal_url, external_url) VALUES(2, 'https://local.example', 'https://remote.example')",
            )
        }
        return file
    }

    private fun scalar(database: SQLiteDatabase, sql: String): String? =
        database.rawQuery(sql, null).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
}
