package io.panelassistant.android.backup

import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import io.panelassistant.android.control.CompanionDb
import io.panelassistant.android.util.BoundedStreams
import java.io.File

/**
 * Validates and repairs a restored Companion database with Android's platform SQLite engine.
 *
 * The uploaded database is handled only in the app's private cache. The live Companion database is
 * still replaced by [CompanionRestore]'s root-side staged transaction, but that transaction no longer
 * depends on a target-device `sqlite3` executable being present.
 */
object CompanionDatabasePreparation {
    const val MAX_DATABASE_BYTES = CompanionRestore.MAX_DATABASE_BYTES
    private const val MAX_WAL_BYTES = 64L * 1024L * 1024L
    private const val MAX_SHM_BYTES = 8L * 1024L * 1024L

    /**
     * Merge a descriptor-captured WAL into its adjacent private-cache database copy. The helper sends
     * raw DB/WAL/SHM bytes and never opens live SQLite paths as root; Android's SQLite engine performs
     * the checkpoint only after those bytes are inside ha-paneld's own data directory.
     */
    fun checkpointCapturedDatabase(database: File, wal: File?, shm: File?): Boolean {
        if (!database.isFile || database.length() !in 1..MAX_DATABASE_BYTES) return false
        if (wal != null && (!wal.isFile || wal.length() !in 1..MAX_WAL_BYTES || wal.path != "${database.path}-wal")) {
            return false
        }
        if (shm != null && (!shm.isFile || shm.length() !in 1..MAX_SHM_BYTES || shm.path != "${database.path}-shm")) {
            return false
        }
        val journal = File("${database.path}-journal")
        val opened = runCatching {
            SQLiteDatabase.openDatabase(
                database.path,
                null,
                SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
            )
        }.getOrNull() ?: return false
        val complete = try {
            if (scalar(opened, "PRAGMA quick_check(1)") != "ok") return false
            val checkpoint = opened.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { cursor ->
                if (!cursor.moveToFirst() || cursor.columnCount < 3) null else Triple(
                    cursor.getLong(0),
                    cursor.getLong(1),
                    cursor.getLong(2),
                )
            } ?: return false
            if (checkpoint.first != 0L || checkpoint.second < 0L || checkpoint.second != checkpoint.third) return false
            if (!scalar(opened, "PRAGMA journal_mode=DELETE").equals("delete", ignoreCase = true)) return false
            scalar(opened, "PRAGMA quick_check(1)") == "ok"
        } catch (_: Exception) {
            false
        } finally {
            opened.close()
        }
        if (!complete || database.length() !in 1..MAX_DATABASE_BYTES) return false
        // Checkpoint success proves the main file is complete. Sidecars are capture artifacts and must
        // not enter the portable archive or later be restored beside a newer database.
        fun remove(file: File?): Boolean = file == null || !file.exists() || file.delete()
        if (!remove(wal) || !remove(shm) || journal.exists()) return false
        if (!remove(File("${database.path}-wal")) || !remove(File("${database.path}-shm"))) return false
        return true
    }

    fun prepare(plan: CompanionRestore.Plan, cacheDir: File): CompanionRestore.Preparation? {
        val databaseIndex = plan.files.indexOfFirst { it.relativePath == CompanionRestore.DATABASE_FILE }
        if (databaseIndex < 0) return CompanionRestore.Preparation.unchanged(plan)
        val source = plan.files[databaseIndex]
        if (source.size !in 1..MAX_DATABASE_BYTES) return null

        val temporary = runCatching { File.createTempFile("companion-prepare-", ".db", cacheDir) }.getOrNull()
            ?: return null
        val wal = File("${temporary.path}-wal")
        val shm = File("${temporary.path}-shm")
        val journal = File("${temporary.path}-journal")
        var keepTemporary = false
        return try {
            source.file.inputStream().use { input ->
                temporary.outputStream().use { output -> BoundedStreams.copy(input, output, MAX_DATABASE_BYTES) }
            }
            val repaired = prepareDatabase(temporary) ?: return null
            // A successful DELETE-journal transaction must leave the complete result in the main file.
            // Never silently discard a WAL that could contain the repair.
            if (wal.exists() || shm.exists() || journal.exists()) return null
            val finalSize = temporary.length()
            if (finalSize <= 0L || finalSize > MAX_DATABASE_BYTES) return null
            val preparedDatabase = CompanionRestore.FilePayload(source.relativePath, temporary, deleteOnClose = false)
            val files = plan.files.toMutableList().apply { this[databaseIndex] = preparedDatabase }
            keepTemporary = true
            CompanionRestore.Preparation.withPreparedFiles(files, repaired, listOf(temporary))
        } catch (_: Exception) {
            null
        } finally {
            if (!keepTemporary) temporary.delete()
            wal.delete()
            shm.delete()
            journal.delete()
        }
    }

    private fun prepareDatabase(file: File): Int? {
        val database = runCatching {
            SQLiteDatabase.openDatabase(
                file.path,
                null,
                SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
            )
        }.getOrNull() ?: return null
        return try {
            // The backup deliberately excludes WAL/SHM. Force rollback-journal mode before mutation so
            // the repaired bytes are committed to the one file that will be staged on the target.
            if (!scalar(database, "PRAGMA journal_mode=DELETE").equals("delete", ignoreCase = true)) return null
            if (scalar(database, "PRAGMA quick_check(1)") != "ok") return null

            var repaired = 0
            database.beginTransaction()
            try {
                repaired = database.compileStatement(CompanionDb.INTERNAL_URL_REPAIR_SQL).executeUpdateDelete()
                val remaining = DatabaseUtils.longForQuery(
                    database,
                    CompanionDb.INTERNAL_URL_REPAIR_REMAINING_SQL,
                    null,
                )
                if (remaining != 0L) return null
                database.setTransactionSuccessful()
            } finally {
                database.endTransaction()
            }
            if (scalar(database, "PRAGMA quick_check(1)") != "ok") return null
            repaired
        } catch (_: Exception) {
            null
        } finally {
            database.close()
        }
    }

    private fun scalar(database: SQLiteDatabase, sql: String): String? =
        database.rawQuery(sql, null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
}
