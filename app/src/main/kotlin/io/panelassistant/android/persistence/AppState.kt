package io.panelassistant.android.persistence

import android.content.Context
import android.content.SharedPreferences
import android.database.SQLException
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import io.panelassistant.android.PreDatabaseSettings
import io.panelassistant.android.dashboard.EntityCatalogStore
import io.panelassistant.android.storage.DatabaseBusyRetry
import io.panelassistant.android.storage.StorageHealthRuntime
import org.json.JSONArray
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import io.panelassistant.android.config.SettingType
import io.panelassistant.android.config.SettingValue
import io.panelassistant.android.config.SettingsRegistry
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Durable application state, partitioned into explicit namespaces inside ha-paneld.db.
 *
 * The SharedPreferences interface is retained as a compatibility boundary for existing typed callers;
 * SQLite is the only store. The one XML file left, [PreDatabaseSettings.FILE], holds only the settings
 * read before the database opens.
 */
object AppState {
    fun preferences(context: Context, namespace: String): SharedPreferences {
        val appContext = context.applicationContext
        val process = process(appContext)
        process.stores[namespace]?.let { return it }
        return process.admission.initializeWhenOpen {
            if (process.legacyStoresSwept.compareAndSet(false, true)) deleteLegacyStores(appContext)
            process.stores.getOrCreate(namespace) {
                val sqlite = SqliteNamespacePersistence(process.helper, namespace)
                SqliteStatePreferences(
                    if (namespace == PreDatabaseSettings.NAMESPACE) {
                        PreDatabaseSettingsPersistence(
                            sqlite,
                            appContext.getSharedPreferences(PreDatabaseSettings.FILE, Context.MODE_PRIVATE),
                        )
                    } else sqlite,
                    process.executor,
                    process.admission,
                )
            }
        }
    }

    /**
     * Writes restored `app_state` rows back through the ordinary per-namespace stores.
     *
     * Deliberately not a direct SQL insert: [preferences] caches one store per namespace, so a component
     * already holding that namespace would keep serving — and later re-persist — its stale in-memory copy
     * over anything written behind it. Going through the same store keeps a single authority per key.
     *
     * The caller decides *which* rows may be written ([StateBackupPolicy]); this only applies them. A row
     * whose persisted type is unreadable is skipped rather than failing the batch, because losing one
     * restored key is recoverable and abandoning the whole restore is not. Returns the rows applied.
     */
    fun applyRestoredRows(context: Context, rows: List<ConfigVault.StateRow>): Int {
        var applied = 0
        rows.groupBy { it.namespace }.forEach { (namespace, namespaceRows) ->
            val editor = preferences(context, namespace).edit()
            var staged = 0
            namespaceRows.forEach { row ->
                val value = runCatching { decodeStateValue(row.type, row.valueText) }.getOrNull()
                    ?: return@forEach
                when (value) {
                    is String -> editor.putString(row.key, value)
                    is Int -> editor.putInt(row.key, value)
                    is Long -> editor.putLong(row.key, value)
                    is Float -> editor.putFloat(row.key, value)
                    is Boolean -> editor.putBoolean(row.key, value)
                    is Set<*> -> editor.putStringSet(row.key, value.filterIsInstance<String>().toSet())
                    else -> return@forEach
                }
                staged++
            }
            if (staged > 0 && editor.commit()) applied += staged
        }
        return applied
    }

    /**
     * Wait until every state write admitted before this call is durable. This is used at orderly
     * service teardown and immediately before replacing this APK; it does not close the process-wide
     * writer, so Android may recreate the service in the same process.
     */
    fun flush(context: Context, timeoutMs: Long): Boolean =
        processes[context.applicationContext.packageName]?.let {
            it.flush(timeoutMs)
        } ?: true

    fun quiesceForSelfReplace(context: Context, timeoutMs: Long): StateQuiescence? =
        process(context.applicationContext).quiesce(timeoutMs)

    /**
     * Reject new state mutations for the final proof phase of every clean, orderly service stop.
     * Unlike the self-replace lease, cached writers arriving after this boundary fail promptly; new
     * namespace initialization waits for release. The lease remains held through checkpoint proof.
     */
    fun freezeForServiceShutdown(context: Context): StateQuiescence? =
        process(context.applicationContext).freezeForShutdown()

    /** Final flush, WAL fold and stable-file evidence shared by ordinary and upgrade-triggered stops. */
    fun proveCleanServiceShutdown(context: Context, timeoutMs: Long): CleanDatabaseProof? =
        processes[context.applicationContext.packageName]?.proveCleanShutdown(
            context.applicationContext,
            timeoutMs,
        )

    private fun process(appContext: Context): ProcessState =
        processes.getOrCreate(appContext.packageName) {
            ProcessState(
                helper = EntityCatalogStore(appContext),
                executor = Executors.newSingleThreadExecutor(StateWriterThreadFactory()),
                admission = StateMutationAdmission(),
            )
        }

    private data class ProcessState(
        val helper: EntityCatalogStore,
        val executor: ExecutorService,
        val admission: StateMutationAdmission,
        val stores: AtomicFactoryCache<String, SqliteStatePreferences> = AtomicFactoryCache(),
        val legacyStoresSwept: AtomicBoolean = AtomicBoolean(),
    ) {
        fun quiesce(timeoutMs: Long): StateQuiescence? =
            quiesceStateWrites(admission) { flush(timeoutMs) }

        fun freezeForShutdown(): StateQuiescence? = admission.freezeRejecting()

        fun flush(timeoutMs: Long): Boolean {
            if (timeoutMs < 0) return false
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
            var succeeded = true
            for (store in stores.snapshotValues()) {
                val remainingNanos = deadline - System.nanoTime()
                if (remainingNanos < 0) {
                    succeeded = false
                    continue
                }
                if (!store.flush(TimeUnit.NANOSECONDS.toMillis(remainingNanos).coerceAtLeast(1L))) {
                    succeeded = false
                }
            }
            return succeeded
        }

        fun proveCleanShutdown(appContext: Context, timeoutMs: Long): CleanDatabaseProof? {
            val budget = ShutdownProofBudget(timeoutMs)
            val flushMs = budget.remainingMs()
            if (flushMs <= 0L || !flush(flushMs) || !budget.hasTime()) return null
            val database = helper.writableDatabase
            if (!budget.hasTime()) {
                runCatching { helper.close() }
                return null
            }
            return proveStableDatabase(
                database = database,
                databaseFile = appContext.getDatabasePath(EntityCatalogStore.DATABASE_NAME),
                closeDatabase = helper::close,
                budget = budget,
            )
        }
    }

    private class StateWriterThreadFactory : ThreadFactory {
        override fun newThread(runnable: Runnable): Thread =
            Thread(runnable, "ha-paneld-state-writer").apply { isDaemon = true }
    }

    private val processes = AtomicFactoryCache<String, ProcessState>()

    /**
     * XML stores builds before 1.0 kept beside SQLite: the per-namespace import sources, the downgrade
     * journal's marker file and stores whose feature is gone. Every supported upgrade crossed a 0.9.x
     * build that imported them, so they only hold stale copies, some of a Home Assistant credential.
     */
    internal val LEGACY_XML_STORES = listOf(
        "", // applyRestoredRows opened an unnamed journal for a namespace not yet cached
        "ha-paneld-auto-brightness-runtime",
        "ha-paneld-auto-sleep-learning",
        "ha-paneld-controller-state",
        "ha-paneld-device-profiles",
        "ha-paneld-live-setting-journal",
        "ha-paneld-performance-binding",
        "ha-paneld-power-safety-acknowledgement",
        "ha-paneld-profile-calibration",
        "ha-paneld-shizuku",
        "ha-paneld-startup-recovery",
        "ha-paneld-state-bridge",
        "ha-paneld-wifi-stability",
    )

    private fun deleteLegacyStores(appContext: Context) {
        LEGACY_XML_STORES.forEach { name ->
            runCatching { appContext.deleteSharedPreferences(name) }
                .onFailure { Log.w("ha-paneld/state", "could not delete legacy preference store", it) }
        }
    }
}

internal class AtomicFactoryCache<K : Any, V : Any> {
    private val values = ConcurrentHashMap<K, V>()

    operator fun get(key: K): V? = values[key]

    fun snapshotValues(): List<V> = values.values.toList()

    fun getOrCreate(key: K, factory: () -> V): V =
        values.computeIfAbsent(key) { factory() }
}

/**
 * Narrow opt-in contract for state transitions that authorize an external side effect.
 *
 * Ordinary [SharedPreferences.Editor.apply] and [SharedPreferences.Editor.commit] retain Android's
 * publish-before-disk semantics. This operation publishes neither memory nor listeners until the
 * complete namespace candidate is durable, and leaves the prior in-process snapshot visible when
 * persistence fails. A later call can therefore retry from the same authoritative state.
 */
internal interface DurableVisibilityPreferences : SharedPreferences {
    fun commitWithDurableVisibility(
        mutation: SharedPreferences.Editor.() -> Unit,
    ): Boolean
}

internal fun SharedPreferences.commitWithDurableVisibility(
    mutation: SharedPreferences.Editor.() -> Unit,
): Boolean = (this as? DurableVisibilityPreferences)
    ?.commitWithDurableVisibility(mutation)
    ?: false

internal class SqliteStatePreferences(
    private val persistence: StateNamespacePersistence,
    executor: ExecutorService,
    private val admission: StateMutationAdmission = StateMutationAdmission(),
) : DurableVisibilityPreferences {
    constructor(
        helper: EntityCatalogStore,
        namespace: String,
        clock: () -> Long = System::currentTimeMillis,
    ) : this(
        SqliteNamespacePersistence(helper, namespace, clock),
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "ha-paneld-state-test-writer").apply { isDaemon = true }
        },
        StateMutationAdmission(),
    )

    private val writeQueue = OrderedStateWriteQueue(executor, persistence)
    private val listeners = CopyOnWriteArraySet<SharedPreferences.OnSharedPreferenceChangeListener>()
    private val stateLock = Any()
    private val values: MutableMap<String, Any> = persistence.initialize().toMutableMap()

    override fun getAll(): Map<String, *> = synchronized(stateLock) { values.toMap() }

    override fun getString(key: String, defValue: String?): String? =
        value(key)?.let { it as? String ?: typeError(key, "String", it) } ?: defValue

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? =
        value(key)?.let { it as? Set<String> ?: typeError(key, "Set<String>", it) } ?: defValues

    override fun getInt(key: String, defValue: Int): Int =
        value(key)?.let { it as? Int ?: typeError(key, "Int", it) } ?: defValue

    override fun getLong(key: String, defValue: Long): Long =
        value(key)?.let { it as? Long ?: typeError(key, "Long", it) } ?: defValue

    override fun getFloat(key: String, defValue: Float): Float =
        value(key)?.let { it as? Float ?: typeError(key, "Float", it) } ?: defValue

    override fun getBoolean(key: String, defValue: Boolean): Boolean =
        value(key)?.let { it as? Boolean ?: typeError(key, "Boolean", it) } ?: defValue

    override fun contains(key: String): Boolean = synchronized(stateLock) { key in values }

    override fun edit(): SharedPreferences.Editor = Editor(publishOnlyWhenDurable = false)

    override fun commitWithDurableVisibility(
        mutation: SharedPreferences.Editor.() -> Unit,
    ): Boolean = Editor(publishOnlyWhenDurable = true).run {
        mutation()
        commit()
    }

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener,
    ) {
        listeners += listener
    }

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener,
    ) {
        listeners -= listener
    }

    private fun value(key: String): Any? = synchronized(stateLock) { values[key] }

    internal fun flush(timeoutMs: Long): Boolean {
        val future = synchronized(stateLock) {
            writeQueue.flush(values.toMap())
        } ?: return false
        return future.awaitResult(timeoutMs)
    }

    private inner class Editor(
        private val publishOnlyWhenDurable: Boolean,
    ) : SharedPreferences.Editor {
        private val changes = linkedMapOf<String, Any?>()
        private var clear = false

        override fun putString(key: String, value: String?): SharedPreferences.Editor = stage(key, value)
        override fun putStringSet(key: String, values: Set<String>?): SharedPreferences.Editor =
            stage(key, values?.toSet())
        override fun putInt(key: String, value: Int): SharedPreferences.Editor = stage(key, value)
        override fun putLong(key: String, value: Long): SharedPreferences.Editor = stage(key, value)
        override fun putFloat(key: String, value: Float): SharedPreferences.Editor = stage(key, value)
        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = stage(key, value)
        override fun remove(key: String): SharedPreferences.Editor = stage(key, null)
        override fun clear(): SharedPreferences.Editor = apply { clear = true }

        override fun apply() {
            publish(waitForDisk = false)
        }

        override fun commit(): Boolean = publish(waitForDisk = true)

        private fun publish(waitForDisk: Boolean): Boolean {
            if (!clear && changes.isEmpty()) return true
            return admission.admit {
                publishAdmitted(waitForDisk, deferPublication = publishOnlyWhenDurable)
            } ?: false
        }

        private fun publishAdmitted(
            waitForDisk: Boolean,
            deferPublication: Boolean,
        ): Boolean {
            val pending = synchronized(stateLock) {
                val before = values.toMap()
                val candidate = before.toMutableMap()
                if (clear) candidate.clear()
                changes.forEach { (key, value) ->
                    if (value == null) candidate.remove(key) else candidate[key] = value
                }
                val changed = (before.keys + candidate.keys)
                    .filterTo(linkedSetOf()) { before[it] != candidate[it] }
                val future = writeQueue.enqueue(
                    StateMutation(clear, changes.toMap()),
                    candidate.toMap(),
                )
                if (!deferPublication) {
                    values.clear()
                    values.putAll(candidate)
                }
                PendingStateWrite(
                    changedKeys = changed,
                    future = future,
                    candidate = candidate,
                    deferredPublication = deferPublication,
                )
            }
            if (pending.deferredPublication) {
                val durable = pending.future?.awaitResult() ?: false
                if (!durable) return false
                synchronized(stateLock) {
                    values.clear()
                    values.putAll(pending.candidate)
                }
            }
            pending.changedKeys.forEach { key ->
                listeners.forEach { it.onSharedPreferenceChanged(this@SqliteStatePreferences, key) }
            }
            return if (waitForDisk && !pending.deferredPublication) {
                pending.future?.awaitResult() ?: false
            } else true
        }

        private fun stage(key: String, value: Any?): SharedPreferences.Editor = apply {
            changes[key] = value
        }
    }

    private fun typeError(key: String, expected: String, value: Any): Nothing =
        throw ClassCastException("$key is ${value.javaClass.simpleName}, expected $expected")
}

class StateQuiescence internal constructor(
    private val reopen: () -> Unit,
) : AutoCloseable {
    private val closed = AtomicBoolean()
    override fun close() {
        if (closed.compareAndSet(false, true)) reopen()
    }
}

internal class StateMutationAdmission {
    private val lock = ReentrantLock()
    private val unfrozen = lock.newCondition()
    private var deferred = false
    private var rejecting = false

    fun <T> admit(block: () -> T): T? = lock.withLock {
        while (deferred && !rejecting) unfrozen.awaitUninterruptibly()
        if (rejecting) null else block()
    }

    /** Cache-miss initialization may open/import SQLite, so it waits out every freeze under this lock. */
    fun <T> initializeWhenOpen(block: () -> T): T = lock.withLock {
        while (deferred || rejecting) unfrozen.awaitUninterruptibly()
        block()
    }

    fun freeze(): Boolean = lock.withLock {
        if (deferred || rejecting) false else {
            deferred = true
            true
        }
    }

    fun unfreeze() = lock.withLock {
        deferred = false
        if (!rejecting) unfrozen.signalAll()
    }

    /**
     * Enter the non-blocking shutdown mode. A concurrent self-replace lease retains independent
     * ownership, so either lease can close without accidentally releasing the other's admission fence.
     */
    fun freezeRejecting(): StateQuiescence? = lock.withLock {
        if (rejecting) return null
        rejecting = true
        unfrozen.signalAll()
        StateQuiescence {
            lock.withLock {
                if (rejecting) {
                    rejecting = false
                    if (!deferred) unfrozen.signalAll()
                }
            }
        }
    }
}

internal fun quiesceStateWrites(
    admission: StateMutationAdmission,
    flush: () -> Boolean,
): StateQuiescence? {
    if (!admission.freeze()) return null
    val flushed = runCatching(flush).getOrDefault(false)
    if (!flushed) {
        admission.unfreeze()
        return null
    }
    return StateQuiescence(admission::unfreeze)
}

data class CleanDatabaseProof(
    val databaseBytes: Long,
    val sha256: String,
    val userVersion: Int,
    val appStateRows: Long,
    val orderedAppStateSha256: String = "",
    val settingsSemanticSha256: String = "",
)

internal data class AppStateDigestRow(
    val namespace: String,
    val key: String,
    val type: String,
    val valueText: String?,
    val updatedAt: Long,
)

internal data class AppStateSemanticProof(
    val count: Long,
    val orderedSha256: String,
    val settingsSha256: String,
)

internal data class GuardDbSettingDefault(val key: String, val type: String, val value: String)

internal fun authoritativeGuardDbSettingDefaults(): List<GuardDbSettingDefault> = buildList {
    SettingsRegistry.settable().forEach { spec ->
        val type = when (spec.type) {
            SettingType.BOOL -> "boolean"
            SettingType.INT -> "int"
            SettingType.LONG -> "long"
            SettingType.FLOAT -> "float"
            SettingType.ENUM, SettingType.STRING, SettingType.PASSWORD -> "string"
        }
        val canonicalDefault = if (spec.type == SettingType.BOOL) {
            if (SettingValue.parseBool(spec.default) == true) "1" else "0"
        } else spec.default
        add(GuardDbSettingDefault(spec.key, type, canonicalDefault))
    }
    SettingsRegistry.persistedExposureDefaults().forEach { (key, default) ->
        add(
            GuardDbSettingDefault(
                key,
                "boolean",
                if (default) "1" else "0",
            ),
        )
    }
}.sortedBy(GuardDbSettingDefault::key)

/** Sealed SETTINGS authority consumed by the native immutable-copy verifier. */
internal fun canonicalGuardDbSettingsAuthority(
    settingDefaults: List<GuardDbSettingDefault> = authoritativeGuardDbSettingDefaults(),
): ByteArray {
    require(settingDefaults.isNotEmpty())
    val sorted = settingDefaults.sortedBy(GuardDbSettingDefault::key)
    require(sorted.map(GuardDbSettingDefault::key).distinct().size == sorted.size)
    fun hex(value: String): String = value.toByteArray(Charsets.UTF_8)
        .joinToString("") { byte -> "%02x".format(byte) }
    return buildString {
        append("S2\n")
        sorted.forEach { setting ->
            require(setting.key.isNotEmpty() && setting.type in setOf("boolean", "int", "long", "float", "string"))
            append(hex(setting.key))
            append('|')
            append(hex(setting.type))
            append('|')
            append(hex(setting.value))
            append('\n')
        }
    }.toByteArray(Charsets.US_ASCII)
}

/** Canonical semantic proof shared by clean ARM capture and replacement-process health acknowledgement. */
internal fun canonicalAppStateSemanticProof(
    rows: List<AppStateDigestRow>,
    settingDefaults: List<GuardDbSettingDefault> = authoritativeGuardDbSettingDefaults(),
): AppStateSemanticProof {
    val ordered = rows.sortedWith(compareBy(AppStateDigestRow::namespace, AppStateDigestRow::key))
    fun digest(domain: String, selected: List<AppStateDigestRow>, includeTimestamp: Boolean): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fun frame(value: String?) {
            if (value == null) {
                digest.update(0.toByte())
                return
            }
            digest.update(1.toByte())
            val bytes = value.toByteArray(Charsets.UTF_8)
            for (shift in 56 downTo 0 step 8) digest.update(((bytes.size.toLong() ushr shift) and 0xff).toByte())
            digest.update(bytes)
        }
        frame(domain)
        selected.forEach { row ->
            frame(row.namespace)
            frame(row.key)
            frame(row.type)
            frame(row.valueText)
            if (includeTimestamp) frame(row.updatedAt.toString())
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }
    val configByKey = ordered.filter { it.namespace == "config" }.associateBy(AppStateDigestRow::key)
    require(settingDefaults.map(GuardDbSettingDefault::key).distinct().size == settingDefaults.size)
    val knownKeys = settingDefaults.mapTo(linkedSetOf(), GuardDbSettingDefault::key)
    val effectiveSettings = buildList {
        settingDefaults.sortedBy(GuardDbSettingDefault::key).forEach { default ->
            val present = configByKey[default.key]
            add(
                AppStateDigestRow(
                    namespace = "config-effective-v2",
                    key = default.key,
                    type = present?.type ?: default.type,
                    valueText = present?.valueText ?: default.value,
                    updatedAt = 0L,
                ),
            )
        }
        // Internal/dynamic config rows are not silently discarded merely because they are absent from
        // the ordinary settings registry. They remain exact present-value semantics.
        ordered.filter { it.namespace == "config" && it.key !in knownKeys }.forEach { row ->
            add(row.copy(namespace = "config-extra-v2", updatedAt = 0L))
        }
    }
    return AppStateSemanticProof(
        count = ordered.size.toLong(),
        orderedSha256 = digest("guard-db/app-state/v1", ordered, includeTimestamp = true),
        settingsSha256 = digest(
            "guard-db/settings/v2",
            effectiveSettings,
            includeTimestamp = false,
        ),
    )
}

internal fun readAppStateSemanticProof(
    database: SQLiteDatabase,
    budget: ShutdownProofBudget? = null,
): AppStateSemanticProof? = runCatching {
    val rows = database.rawQuery(
        "SELECT namespace,state_key,value_type,value_text,updated_at FROM app_state " +
            "ORDER BY namespace COLLATE BINARY,state_key COLLATE BINARY",
        null,
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                if (budget?.hasTime() == false) error("shutdown proof budget exhausted during app-state digest")
                add(
                    AppStateDigestRow(
                        namespace = cursor.getString(0),
                        key = cursor.getString(1),
                        type = cursor.getString(2),
                        valueText = if (cursor.isNull(3)) null else cursor.getString(3),
                        updatedAt = cursor.getLong(4),
                    ),
                )
            }
        }
    }
    canonicalAppStateSemanticProof(rows)
}.getOrNull()

internal fun cleanCheckpointAccepted(
    busy: Int,
    walBytesAfterCheckpoint: Long,
    databaseBytesBeforeDigest: Long,
    databaseBytesAfterDigest: Long,
    walBytesAfterDigest: Long,
): Boolean = busy == 0 && walBytesAfterCheckpoint == 0L &&
    databaseBytesBeforeDigest > 0L && databaseBytesBeforeDigest == databaseBytesAfterDigest &&
    walBytesAfterDigest == 0L

internal class ShutdownProofBudget(
    timeoutMs: Long,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private val deadlineNanos = nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs.coerceAtLeast(0L))

    fun hasTime(): Boolean = deadlineNanos - nanoTime() > 0L

    fun remainingMs(): Long {
        val remainingNanos = deadlineNanos - nanoTime()
        if (remainingNanos <= 0L) return 0L
        return ((remainingNanos - 1L) / 1_000_000L) + 1L
    }
}

private fun proveStableDatabase(
    database: SQLiteDatabase,
    databaseFile: File,
    closeDatabase: () -> Unit,
    budget: ShutdownProofBudget,
): CleanDatabaseProof? {
    var closed = false
    return try {
        check(budget.hasTime()) { "shutdown proof budget exhausted before WAL checkpoint" }
        val checkpoint = database.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null)
        val busy = try {
            check(checkpoint.moveToFirst()) { "checkpoint returned no result" }
            checkpoint.getInt(0)
        } finally {
            checkpoint.close()
        }
        check(budget.hasTime()) { "shutdown proof budget exhausted during WAL checkpoint" }
        check(busy == 0) { "WAL checkpoint remained busy" }
        val userVersion = scalarLong(database, "PRAGMA user_version", budget).toInt()
        val appState = readAppStateSemanticProof(database, budget)
            ?: error("application-state semantic proof failed")
        // Explicit close is API-27-safe; SQLiteOpenHelper does not implement AutoCloseable until API 29.
        closeDatabase()
        closed = true
        check(budget.hasTime()) { "shutdown proof budget exhausted while closing database" }
        val wal = File(databaseFile.path + "-wal")
        val walBytesAfterCheckpoint = if (wal.exists()) wal.length() else 0L
        val databaseBytesBeforeDigest = databaseFile.length()
        val sha256 = sha256WithinBudget(databaseFile, budget)
            ?: error("shutdown proof budget exhausted while hashing database")
        val databaseBytesAfterDigest = databaseFile.length()
        val walBytesAfterDigest = if (wal.exists()) wal.length() else 0L
        check(budget.hasTime()) { "shutdown proof budget exhausted after hashing database" }
        check(
            cleanCheckpointAccepted(
                busy = busy,
                walBytesAfterCheckpoint = walBytesAfterCheckpoint,
                databaseBytesBeforeDigest = databaseBytesBeforeDigest,
                databaseBytesAfterDigest = databaseBytesAfterDigest,
                walBytesAfterDigest = walBytesAfterDigest,
            ),
        ) { "database did not remain stable after WAL checkpoint" }
        CleanDatabaseProof(
            databaseBytes = databaseBytesAfterDigest,
            sha256 = sha256,
            userVersion = userVersion,
            appStateRows = appState.count,
            orderedAppStateSha256 = appState.orderedSha256,
            settingsSemanticSha256 = appState.settingsSha256,
        )
    } catch (failure: Throwable) {
        Log.e("AppState", "clean database shutdown proof failed", failure)
        null
    } finally {
        if (!closed) runCatching(closeDatabase)
            .onFailure { Log.e("AppState", "database close after failed shutdown proof failed", it) }
    }
}

internal fun sha256WithinBudget(databaseFile: File, budget: ShutdownProofBudget): String? {
    if (!budget.hasTime()) return null
    val digest = MessageDigest.getInstance("SHA-256")
    FileInputStream(databaseFile).use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            if (!budget.hasTime()) return null
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    if (!budget.hasTime()) return null
    return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
}

private fun scalarLong(database: SQLiteDatabase, sql: String, budget: ShutdownProofBudget): Long {
    check(budget.hasTime()) { "shutdown proof budget exhausted before query" }
    val cursor = database.rawQuery(sql, null)
    return try {
        check(cursor.moveToFirst()) { "$sql returned no result" }
        cursor.getLong(0).also {
            check(budget.hasTime()) { "shutdown proof budget exhausted during query" }
        }
    } finally {
        cursor.close()
    }
}

internal data class StateMutation(
    val clear: Boolean,
    val changes: Map<String, Any?>,
)

private data class PendingStateWrite(
    val changedKeys: Set<String>,
    val future: Future<Boolean>?,
    val candidate: Map<String, Any>,
    val deferredPublication: Boolean,
)

internal interface StateNamespacePersistence {
    fun initialize(): Map<String, Any>
    fun persist(mutation: StateMutation): Boolean
    fun replace(snapshot: Map<String, Any>): Boolean
}

/**
 * Keeps the settings `HaPaneldApp.onCreate` reads before the database opens ([PreDatabaseSettings.KEYS])
 * in [PreDatabaseSettings.FILE], and nothing else in it.
 *
 * SQLite stays the authority. Each durable write, and the namespace's first load, rewrites the file
 * when its copy differs, so a stale or missing copy repairs itself on the next start or change. A failed
 * write costs only a cold start in the previous language or theme until the service re-asserts the
 * database value; it never fails the save.
 */
internal class PreDatabaseSettingsPersistence(
    private val primary: StateNamespacePersistence,
    private val file: SharedPreferences,
) : StateNamespacePersistence {
    private var snapshot: Map<String, Any> = emptyMap()

    override fun initialize(): Map<String, Any> = primary.initialize().also(::publish)

    override fun persist(mutation: StateMutation): Boolean =
        primary.persist(mutation).also { if (it) publish(snapshot.toMutableMap().applyMutation(mutation)) }

    override fun replace(snapshot: Map<String, Any>): Boolean =
        primary.replace(snapshot).also { if (it) publish(snapshot.toMap()) }

    private fun publish(state: Map<String, Any>) {
        snapshot = state
        val wanted = state.filterKeys(PreDatabaseSettings.KEYS::contains)
        if (file.all == wanted) return
        runCatching {
            file.edit().clear().also { editor ->
                wanted.forEach { (key, value) ->
                    when (value) {
                        is String -> editor.putString(key, value)
                        is Boolean -> editor.putBoolean(key, value)
                        else -> Log.w(TAG, "pre-database setting $key has unexpected type")
                    }
                }
            }.apply()
        }.onFailure { Log.w(TAG, "could not write pre-database settings", it) }
    }

    private companion object {
        const val TAG = "ha-paneld/state"
    }
}

private fun MutableMap<String, Any>.applyMutation(mutation: StateMutation): Map<String, Any> = apply {
    if (mutation.clear) clear()
    mutation.changes.forEach { (key, value) ->
        if (value == null) remove(key) else put(key, value)
    }
}.toMap()

internal class OrderedStateWriteQueue(
    private val executor: ExecutorService,
    private val persistence: StateNamespacePersistence,
) {
    // Accessed only by the single ordered executor. A failed asynchronous write leaves memory ahead
    // of disk; the next queued write repairs the namespace from its complete post-mutation snapshot.
    private var reconcileRequired = false

    fun enqueue(mutation: StateMutation, snapshot: Map<String, Any>): Future<Boolean>? = runCatching {
        executor.submit<Boolean> {
            val succeeded = runCatching {
                if (reconcileRequired) persistence.replace(snapshot) else persistence.persist(mutation)
            }.getOrDefault(false)
            reconcileRequired = !succeeded
            succeeded
        }
    }.getOrNull()

    /**
     * Queue a complete snapshot behind all admitted mutations. Replacing even after successful writes
     * makes shutdown a durability operation rather than only an executor-idle observation.
     */
    fun flush(snapshot: Map<String, Any>): Future<Boolean>? = runCatching {
        executor.submit<Boolean> {
            val succeeded = runCatching { persistence.replace(snapshot) }.getOrDefault(false)
            reconcileRequired = !succeeded
            succeeded
        }
    }.getOrNull()
}

private fun Future<Boolean>.awaitResult(): Boolean =
    runCatching { get() }.getOrDefault(false)

private fun Future<Boolean>.awaitResult(timeoutMs: Long): Boolean =
    runCatching { get(timeoutMs, TimeUnit.MILLISECONDS) }.getOrDefault(false)

private class SqliteNamespacePersistence(
    private val helper: EntityCatalogStore,
    private val namespace: String,
    private val clock: () -> Long = System::currentTimeMillis,
) : StateNamespacePersistence {
    private val busyRetry = DatabaseBusyRetry()

    override fun initialize(): Map<String, Any> {
        require(namespace.isNotBlank()) { "state namespace must not be blank" }
        val values = linkedMapOf<String, Any>()
        helper.readableDatabase.rawQuery(
            "SELECT state_key,value_type,value_text FROM app_state WHERE namespace=?",
            arrayOf(namespace),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                values[cursor.getString(0)] = decodeStateValue(cursor.getString(1), cursor.getString(2))
            }
        }
        return values
    }

    override fun persist(mutation: StateMutation): Boolean = writeTransaction {
        val revision = insertRevision(this, "app")
        if (mutation.clear) delete("app_state", "namespace=?", arrayOf(namespace))
        mutation.changes.forEach { (key, value) ->
            if (value == null) {
                delete("app_state", "namespace=? AND state_key=?", arrayOf(namespace, key))
            } else {
                putValue(this, key, value, revision)
            }
        }
        pruneRevisions(this)
    }

    override fun replace(snapshot: Map<String, Any>): Boolean = writeTransaction {
        val revision = insertRevision(this, "app-reconcile")
        delete("app_state", "namespace=?", arrayOf(namespace))
        snapshot.forEach { (key, value) -> putValue(this, key, value, revision) }
        pruneRevisions(this)
    }

    private fun writeTransaction(block: SQLiteDatabase.() -> Unit): Boolean {
        // Config writes ride their own connection pool over the shared database file, so a BUSY here
        // is the app's own maintenance briefly holding the write lock — expected concurrency, retried
        // within one bounded budget before the unchanged latch path decides (Issue #91). Each attempt
        // rolls back with its own throw, so a re-run cannot double-apply.
        val retry = busyRetry.begin()
        while (true) {
            try {
                val db = helper.writableDatabase
                db.beginTransaction()
                try {
                    db.block()
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
                StorageHealthRuntime.recordDatabaseWriteSuccess()
                return true
            } catch (failure: Throwable) {
                if (failure is SQLException && retry.admitRetry(failure, helper::isBusyRetryAbandoned)) continue
                StorageHealthRuntime.recordDatabaseFailure("app_state:$namespace", failure)
                return false
            }
        }
    }

    private fun insertRevision(db: SQLiteDatabase, source: String): Long {
        db.execSQL(
            "INSERT INTO app_state_revision(committed_at,namespace,source) VALUES(?,?,?)",
            arrayOf<Any?>(clock(), namespace, source),
        )
        return db.rawQuery("SELECT last_insert_rowid()", null).use {
            check(it.moveToFirst())
            it.getLong(0)
        }
    }

    private fun putValue(db: SQLiteDatabase, key: String, value: Any, revision: Long) {
        val (type, text) = encode(value)
        db.execSQL(
            """INSERT OR REPLACE INTO app_state(
                namespace,state_key,value_type,value_text,updated_at,revision
            ) VALUES(?,?,?,?,?,?)""",
            arrayOf<Any?>(namespace, key, type, text, clock(), revision),
        )
    }

    private fun pruneRevisions(db: SQLiteDatabase) {
        db.execSQL(
            """DELETE FROM app_state_revision
               WHERE namespace=? AND revision NOT IN (
                   SELECT revision FROM app_state WHERE namespace=?
               ) AND revision < (
                   SELECT coalesce(max(revision),0)-? FROM app_state_revision WHERE namespace=?
               )""",
            arrayOf<Any?>(namespace, namespace, REVISION_RETENTION, namespace),
        )
    }

    private fun encode(value: Any): Pair<String, String> = when (value) {
        is String -> "string" to value
        is Int -> "int" to value.toString()
        is Long -> "long" to value.toString()
        is Float -> "float" to value.toString()
        is Boolean -> "boolean" to if (value) "1" else "0"
        is Set<*> -> {
            require(value.all { it is String }) { "only string sets are supported" }
            "string_set" to JSONArray(value.toList()).toString()
        }
        else -> error("unsupported state type ${value.javaClass.name}")
    }

    private companion object {
        const val REVISION_RETENTION = 256
    }
}

private fun decodeStateValue(type: String, text: String?): Any = when (type) {
    "string" -> text.orEmpty()
    "int" -> text.orEmpty().toInt()
    "long" -> text.orEmpty().toLong()
    "float" -> text.orEmpty().toFloat()
    "boolean" -> text == "1"
    "string_set" -> JSONArray(text.orEmpty()).let { json ->
        buildSet { repeat(json.length()) { add(json.getString(it)) } }
    }
    else -> error("unsupported persisted state type $type")
}
