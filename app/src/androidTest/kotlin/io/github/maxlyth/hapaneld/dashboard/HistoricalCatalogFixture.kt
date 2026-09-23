package io.github.maxlyth.hapaneld.dashboard

import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import io.github.maxlyth.hapaneld.persistence.ConfigVault
import java.io.File

/** Literal schema shipped by v0.9.5; never derive this baseline from current production DDL. */
internal object HistoricalCatalogFixture {
    /** Boot receivers can start the real service during instrumentation; fixtures must own their files. */
    fun isolatedContext(base: Context, suite: String): Context = object : ContextWrapper(base) {
        private val root = File(base.filesDir, "catalog-instrumentation/$suite")
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
        override fun getCacheDir(): File = File(root, "cache").apply { mkdirs() }
        override fun getDatabasePath(name: String): File = File(root, "databases/$name").also {
            it.parentFile!!.mkdirs()
        }
        override fun deleteDatabase(name: String): Boolean = SQLiteDatabase.deleteDatabase(getDatabasePath(name))
        override fun openOrCreateDatabase(
            name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?,
        ): SQLiteDatabase = openOrCreateDatabase(name, mode, factory, null)

        override fun openOrCreateDatabase(
            name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, errorHandler: DatabaseErrorHandler?,
        ): SQLiteDatabase {
            val wal = if (mode and Context.MODE_ENABLE_WRITE_AHEAD_LOGGING != 0) {
                SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING
            } else 0
            return SQLiteDatabase.openDatabase(
                getDatabasePath(name).path, factory, SQLiteDatabase.CREATE_IF_NECESSARY or wal, errorHandler,
            )
        }
    }

    fun create(context: Context): SQLiteDatabase {
        context.deleteDatabase(EntityCatalogStore.DATABASE_NAME)
        return context.openOrCreateDatabase(EntityCatalogStore.DATABASE_NAME, Context.MODE_PRIVATE, null).also { db ->
            V095_SCHEMA.forEach(db::execSQL)
            db.version = 11
        }
    }

    fun removeDatabase(context: Context) {
        context.deleteDatabase(EntityCatalogStore.DATABASE_NAME)
        val target = context.getDatabasePath(EntityCatalogStore.DATABASE_NAME)
        target.parentFile?.listFiles()?.filter { it.name.startsWith("${target.name}.v") }
            ?.forEach { it.delete() }
    }

    fun clean(context: Context) {
        removeDatabase(context)
        File(context.filesDir, ConfigVault.VAULT_DIRECTORY).deleteRecursively()
        File(context.filesDir, "device-profiles/imported").deleteRecursively()
    }

    val V095_SCHEMA = listOf(
        """CREATE TABLE entity(
            instance TEXT NOT NULL, entity_id TEXT NOT NULL, state TEXT NOT NULL DEFAULT '',
            attributes_json TEXT NOT NULL DEFAULT '{}', metadata_json TEXT NOT NULL DEFAULT '{}',
            first_seen INTEGER NOT NULL, last_seen INTEGER NOT NULL, missing_streak INTEGER NOT NULL DEFAULT 0,
            tombstone_at INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(instance,entity_id))""",
        """CREATE TABLE dashboard(
            instance TEXT NOT NULL, path TEXT NOT NULL, config_hash TEXT NOT NULL DEFAULT '',
            config_json TEXT NOT NULL DEFAULT '{}', status TEXT NOT NULL DEFAULT 'disabled',
            last_sync INTEGER NOT NULL DEFAULT 0, error TEXT NOT NULL DEFAULT '',
            unresolved_json TEXT NOT NULL DEFAULT '[]', sync_generation INTEGER NOT NULL DEFAULT 0,
            issues_json TEXT NOT NULL DEFAULT '[]',
            PRIMARY KEY(instance,path))""",
        """CREATE TABLE membership(
            instance TEXT NOT NULL, path TEXT NOT NULL, entity_id TEXT NOT NULL,
            static_ref INTEGER NOT NULL DEFAULT 0, runtime_ref INTEGER NOT NULL DEFAULT 0,
            pinned INTEGER NOT NULL DEFAULT 0, excluded INTEGER NOT NULL DEFAULT 0,
            reasons TEXT NOT NULL DEFAULT '', first_access INTEGER NOT NULL DEFAULT 0,
            last_access INTEGER NOT NULL DEFAULT 0, access_count INTEGER NOT NULL DEFAULT 0,
            update_count INTEGER NOT NULL DEFAULT 0, update_bytes INTEGER NOT NULL DEFAULT 0,
            rate_window_start INTEGER NOT NULL DEFAULT 0, rate_update_bytes INTEGER NOT NULL DEFAULT 0,
            last_update_at INTEGER NOT NULL DEFAULT 0,
            PRIMARY KEY(instance,path,entity_id))""",
        """CREATE TABLE minute_rollup(
            instance TEXT NOT NULL, path TEXT NOT NULL, entity_id TEXT NOT NULL, minute INTEGER NOT NULL,
            access_count INTEGER NOT NULL DEFAULT 0, update_count INTEGER NOT NULL DEFAULT 0,
            update_bytes INTEGER NOT NULL DEFAULT 0, span_start INTEGER NOT NULL DEFAULT 0,
            PRIMARY KEY(instance,path,entity_id,minute))""",
        "CREATE INDEX entity_missing ON entity(instance,missing_streak)",
        "CREATE INDEX membership_load ON membership(instance,path,update_bytes DESC)",
        "CREATE INDEX minute_rollup_age ON minute_rollup(instance,path,minute)",
        """CREATE TABLE dashboard_issue_ignore(
            instance TEXT NOT NULL, path TEXT NOT NULL, fingerprint TEXT NOT NULL, ignored_at INTEGER NOT NULL,
            PRIMARY KEY(instance,path,fingerprint),
            FOREIGN KEY(instance,path) REFERENCES dashboard(instance,path) ON DELETE CASCADE)""",
        """CREATE TABLE dashboard_performance(
            instance TEXT NOT NULL,path TEXT NOT NULL,minute INTEGER NOT NULL,
            filter_active INTEGER NOT NULL DEFAULT 0,entity_count INTEGER NOT NULL DEFAULT 0,
            sample_ms INTEGER NOT NULL DEFAULT 0,frames INTEGER NOT NULL DEFAULT 0,
            payload_bytes INTEGER NOT NULL DEFAULT 0,updates INTEGER NOT NULL DEFAULT 0,
            hydration_updates INTEGER NOT NULL DEFAULT 0,observer_micros INTEGER NOT NULL DEFAULT 0,
            dropped_frames INTEGER NOT NULL DEFAULT 0,state_task_micros INTEGER NOT NULL DEFAULT 0,
            state_task_max_micros INTEGER NOT NULL DEFAULT 0,interaction_count INTEGER NOT NULL DEFAULT 0,
            interaction_max_micros INTEGER NOT NULL DEFAULT 0,input_delay_micros INTEGER NOT NULL DEFAULT 0,
            interaction_processing_micros INTEGER NOT NULL DEFAULT 0,presentation_micros INTEGER NOT NULL DEFAULT 0,
            loaf_count INTEGER NOT NULL DEFAULT 0,blocking_micros INTEGER NOT NULL DEFAULT 0,
            loaf_max_micros INTEGER NOT NULL DEFAULT 0,script_micros INTEGER NOT NULL DEFAULT 0,
            render_micros INTEGER NOT NULL DEFAULT 0,long_task_count INTEGER NOT NULL DEFAULT 0,
            PRIMARY KEY(instance,path,minute))""",
        "CREATE INDEX dashboard_performance_age ON dashboard_performance(minute)",
        """CREATE TABLE app_state_revision(
            revision INTEGER PRIMARY KEY AUTOINCREMENT,
            committed_at INTEGER NOT NULL,
            namespace TEXT NOT NULL,
            source TEXT NOT NULL DEFAULT 'app')""",
        """CREATE TABLE app_state_namespace(
            namespace TEXT PRIMARY KEY,
            imported_at INTEGER NOT NULL,
            legacy_name TEXT NOT NULL DEFAULT '')""",
        """CREATE TABLE app_state(
            namespace TEXT NOT NULL,
            state_key TEXT NOT NULL,
            value_type TEXT NOT NULL,
            value_text TEXT,
            updated_at INTEGER NOT NULL,
            revision INTEGER NOT NULL,
            PRIMARY KEY(namespace,state_key),
            FOREIGN KEY(revision) REFERENCES app_state_revision(revision))""",
        "CREATE INDEX app_state_updated ON app_state(namespace,updated_at)",
        """CREATE TABLE proximity_model(
            fingerprint TEXT PRIMARY KEY,
            algorithm_version INTEGER NOT NULL,
            behavior_signature TEXT NOT NULL DEFAULT '',
            snapshot_json TEXT NOT NULL,
            ready INTEGER NOT NULL DEFAULT 0,
            updated_at INTEGER NOT NULL)""",
        """CREATE TABLE proximity_rollup(
            fingerprint TEXT NOT NULL,
            bucket INTEGER NOT NULL,
            sample_count INTEGER NOT NULL,
            raw_min REAL NOT NULL,
            raw_max REAL NOT NULL,
            raw_sum REAL NOT NULL,
            raw_square_sum REAL NOT NULL,
            excursion_count INTEGER NOT NULL DEFAULT 0,
            gesture_count INTEGER NOT NULL DEFAULT 0,
            PRIMARY KEY(fingerprint,bucket),
            FOREIGN KEY(fingerprint) REFERENCES proximity_model(fingerprint) ON DELETE CASCADE)""",
        "CREATE INDEX proximity_rollup_age ON proximity_rollup(bucket)",
        """CREATE TABLE proximity_episode(
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            fingerprint TEXT NOT NULL,
            started_at INTEGER NOT NULL,
            duration_ms INTEGER NOT NULL,
            peak_level INTEGER NOT NULL,
            completed INTEGER NOT NULL,
            guided INTEGER NOT NULL,
            FOREIGN KEY(fingerprint) REFERENCES proximity_model(fingerprint) ON DELETE CASCADE)""",
        "CREATE INDEX proximity_episode_age ON proximity_episode(started_at)",
        """CREATE TABLE ambient_lux_minute(
            context_id TEXT NOT NULL,source_id TEXT NOT NULL,minute INTEGER NOT NULL,
            lux_integral REAL NOT NULL DEFAULT 0,coverage_ms INTEGER NOT NULL DEFAULT 0,
            min_lux REAL NOT NULL DEFAULT 0,max_lux REAL NOT NULL DEFAULT 0,last_lux REAL NOT NULL DEFAULT 0,
            sample_count INTEGER NOT NULL DEFAULT 0,baseline_log_integral REAL NOT NULL DEFAULT 0,
            baseline_coverage_ms INTEGER NOT NULL DEFAULT 0,
             PRIMARY KEY(context_id,source_id,minute))""",
        "CREATE INDEX ambient_lux_minute_age ON ambient_lux_minute(minute)",
    )
}
