package io.github.maxlyth.hapaneld.persistence

import android.content.Context
import android.content.SharedPreferences
import android.database.SQLException
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import io.github.maxlyth.hapaneld.dashboard.EntityCatalogStore
import io.github.maxlyth.hapaneld.storage.DatabaseBusyRetry
import io.github.maxlyth.hapaneld.storage.StorageHealthRuntime
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
import io.github.maxlyth.hapaneld.config.SettingType
import io.github.maxlyth.hapaneld.config.SettingValue
import io.github.maxlyth.hapaneld.config.SettingsRegistry
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Durable application state, partitioned into explicit namespaces inside ha-paneld.db.
 *
 * The SharedPreferences interface is retained as a compatibility boundary for existing typed callers;
 * XML preferences are retained as a small compatibility journal for supported 0.9.x downgrades.
 * Runtime reads use SQLite-backed state; startup imports the journal so edits made while temporarily
 * running an older build also survive the return upgrade.
 */
object AppState {
    // TODO(v1.0): Remove legacyName, getSharedPreferences(), the bridge metadata, importLegacyOnce(),
    // app_state_namespace and the legacy shared-preference backup exclusions. Every supported upgrade
    // should have crossed a 0.9.x release which performs this bridge before SQLite-only startup is strict.
    fun preferences(context: Context, namespace: String, legacyName: String): SharedPreferences {
        val appContext = context.applicationContext
        val process = process(appContext)
        process.stores[namespace]?.let { return it }
        return process.admission.initializeWhenOpen {
            process.stores.getOrCreate(namespace) {
                val legacy = appContext.getSharedPreferences(legacyName, Context.MODE_PRIVATE)
                SqliteStatePreferences(
                    DowngradeCompatibleStatePersistence(
                        SqliteNamespacePersistence(process.helper, namespace, legacyName, legacy),
                        SharedPreferencesLegacyStateMirror(legacy),
                        SharedPreferencesBridgeMetadata(
                            appContext.getSharedPreferences(BRIDGE_PREFS, Context.MODE_PRIVATE),
                            namespace,
                        ),
                        legacyMirrorExclusions(namespace),
                    ),
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
            val editor = preferences(context, namespace, "").edit()
            var staged = 0
            namespaceRows.forEach { row ->
                val value = runCatching { decodeRestoredValue(row.type, row.valueText) }.getOrNull()
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

    private fun decodeRestoredValue(type: String, text: String?): Any = when (type) {
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
    private const val BRIDGE_PREFS = "ha-paneld-state-bridge"
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
        legacyName: String,
        legacy: SharedPreferences,
        bridge: SharedPreferences,
        clock: () -> Long = System::currentTimeMillis,
        mirrorExcludedKeys: Set<String> = legacyMirrorExclusions(namespace),
    ) : this(
        DowngradeCompatibleStatePersistence(
            SqliteNamespacePersistence(helper, namespace, legacyName, legacy, clock),
            SharedPreferencesLegacyStateMirror(legacy),
            SharedPreferencesBridgeMetadata(
                bridge,
                namespace,
            ),
            mirrorExcludedKeys,
        ),
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

/**
 * Keys that SQLite keeps but the plaintext downgrade journal never holds. Home Assistant credentials
 * stay out of the XML: an older build that reads only that journal starts signed out instead.
 */
internal fun legacyMirrorExclusions(namespace: String): Set<String> =
    if (namespace == "config") setOf("ha_token", "ha_refresh_token") else emptySet()

internal interface StateNamespacePersistence {
    fun initialize(): Map<String, Any>
    fun persist(mutation: StateMutation): Boolean
    fun replace(snapshot: Map<String, Any>): Boolean
}

/**
 * Keeps the pre-SQLite XML namespace current for the supported 0.9.x downgrade window.
 *
 * A separate hash marker distinguishes supported downgrade edits after the bridge is established.
 * A durable write-intent marker brackets runtime composite writes so an interrupted bridge update is
 * never mistaken for a later old-build edit; while an intent exists, SQLite is authoritative.
 * Markerless divergence is possible only after unpublished SQLite-only builds: neither side has
 * reliable ordering evidence, so SQLite stays active, XML is left untouched, and metadata records
 * the conflict for diagnostics rather than destroying either state.
 *
 * [mirrorExcludedKeys] are never written to, read from or hashed over the XML journal; SQLite keeps
 * them through every reconciliation, and a journal that still holds one is scrubbed at startup.
 */
internal class DowngradeCompatibleStatePersistence(
    private val primary: StateNamespacePersistence,
    private val legacy: LegacyStateMirror,
    private val metadata: BridgeMetadata,
    private val mirrorExcludedKeys: Set<String> = emptySet(),
) : StateNamespacePersistence {
    private var snapshot: Map<String, Any> = emptyMap()
    private var markerlessConflict = false

    override fun initialize(): Map<String, Any> {
        val current = primary.initialize()
        val rawJournal = legacy.snapshot()
        if (rawJournal.keys.any(mirrorExcludedKeys::contains)) scrubExcludedKeys()
        val journal = project(rawJournal)
        val mirrored = project(current)
        val journalHash = mirrorHash(journal)
        val marker = metadata.readHash()
        if (metadata.readWriteIntent() != null) {
            // A composite write was admitted but did not durably reach its completion marker. The
            // SQLite transaction is the authority in every interruption position: it is either the
            // complete new candidate or the complete previous snapshot. Never interpret the XML
            // mismatch as a deliberate downgrade edit while this marker is present.
            snapshot = current
            markerlessConflict = false
            if (!runCatching { legacy.replace(mirrored) }.getOrDefault(false)) {
                Log.w(TAG, "could not repair interrupted legacy compatibility journal")
            } else if (
                !runCatching { metadata.writeHash(mirrorHash(current)) }.getOrDefault(false)
            ) {
                // Both stores now agree. Keeping the intent marker merely causes the same safe repair
                // on the next start, so metadata pressure must not make the durable state unavailable.
                Log.w(TAG, "could not complete interrupted compatibility marker")
            }
            return current
        }
        snapshot = when {
            marker == null -> {
                if (journal == mirrored) {
                    check(legacy.replace(mirrored)) { "could not seed legacy compatibility journal" }
                    check(metadata.writeHash(mirrorHash(current))) { "could not mark legacy compatibility journal" }
                } else {
                    check(metadata.writeConflict(mirrorHash(current), journalHash)) {
                        "could not record markerless compatibility conflict"
                    }
                    Log.w(TAG, "markerless SQLite/XML state conflict preserved; SQLite remains active")
                    markerlessConflict = true
                }
                current
            }
            marker == journalHash -> {
                if (journal != mirrored) {
                    val currentPayload = mirrored.filterKeys(::isPayloadKey)
                    val journalPayload = journal.filterKeys(::isPayloadKey)
                    val journalContainsCurrent = currentPayload.all { (key, value) -> journalPayload[key] == value }
                    if (journalContainsCurrent && journalPayload.size > currentPayload.size) {
                        val recovered = journal.toMutableMap().apply {
                            current.filterKeys { !isPayloadKey(it) || it in mirrorExcludedKeys }
                                .forEach { (key, value) -> put(key, value) }
                        }
                        check(primary.replace(recovered)) { "could not recover truncated SQLite state" }
                        check(legacy.replace(project(recovered))) { "could not refresh recovered compatibility journal" }
                        check(metadata.writeHash(mirrorHash(recovered))) {
                            "could not mark recovered compatibility journal"
                        }
                        recovered
                    } else {
                        check(legacy.replace(mirrored)) { "could not refresh legacy compatibility journal" }
                        check(metadata.writeHash(mirrorHash(current))) { "could not refresh compatibility marker" }
                        current
                    }
                }
                else current
            }
            else -> {
                // Reconcile by merging the mirror onto the SQLite primary, never replacing it outright:
                // an old build's downgrade edit still wins for shared keys, but a drifted or degraded
                // mirror can never drop live keys and wipe a panel's configuration. Excluded keys are
                // absent from the projected journal, so SQLite's values always survive the merge.
                val reconciled = current + journal
                check(primary.replace(reconciled)) { "could not import legacy compatibility journal" }
                check(metadata.writeHash(mirrorHash(reconciled))) {
                    "could not mark imported compatibility journal"
                }
                reconciled
            }
        }
        return snapshot
    }

    private fun isPayloadKey(key: String): Boolean = key != "panel_id" && key != "config_schema"

    private fun project(state: Map<String, Any>): Map<String, Any> =
        state.filterKeys { it !in mirrorExcludedKeys }

    private fun mirrorHash(state: Map<String, Any>): String = stateSnapshotHash(project(state))

    private fun scrubExcludedKeys() {
        // A journal written before the exclusion, or by an older build, can still hold these keys;
        // persisting never deletes a key it does not mention. A failed scrub retries next startup.
        val removal = StateMutation(clear = false, changes = mirrorExcludedKeys.associateWith { null })
        if (!runCatching { legacy.persist(removal) }.getOrDefault(false)) {
            Log.w(TAG, "could not remove excluded keys from legacy compatibility journal")
        }
    }

    override fun persist(mutation: StateMutation): Boolean {
        val candidate = snapshot.toMutableMap().applyMutation(mutation)
        val candidateHash = mirrorHash(candidate)
        val fullReconcile = markerlessConflict || metadata.readWriteIntent() != null
        if (!runCatching { metadata.writeIntent(candidateHash) }.getOrDefault(false)) return false

        val primarySucceeded = runCatching {
            if (fullReconcile) primary.replace(candidate) else primary.persist(mutation)
        }.getOrDefault(false)
        if (!primarySucceeded) return false

        snapshot = candidate
        markerlessConflict = false
        val legacySucceeded = runCatching {
            if (fullReconcile) {
                legacy.replace(project(candidate))
            } else {
                legacy.persist(mutation.copy(changes = mutation.changes.filterKeys { it !in mirrorExcludedKeys }))
            }
        }.getOrDefault(false)
        if (!legacySucceeded) {
            // SQLite is already durable, so this save is committed. The intent must remain: a restart
            // repairs XML from SQLite, while another live-process write notices the intent and uses a
            // complete replacement instead of extending a stale compatibility journal.
            Log.w(TAG, "SQLite state durable but legacy compatibility journal update failed")
            return true
        }

        if (!runCatching { metadata.writeHash(candidateHash) }.getOrDefault(false)) {
            // The primary and compatibility journal are already durable. Returning false here would
            // publish a false failure to config callers and invite a retry of an already-saved change.
            // The surviving intent marker makes the next startup repair/finalize this safely.
            Log.w(TAG, "state stores durable but compatibility marker completion failed")
        }
        return true
    }

    override fun replace(snapshot: Map<String, Any>): Boolean {
        val durableSnapshot = snapshot.toMap()
        val snapshotHash = mirrorHash(durableSnapshot)
        if (!runCatching { metadata.writeIntent(snapshotHash) }.getOrDefault(false)) return false
        if (!runCatching { primary.replace(durableSnapshot) }.getOrDefault(false)) return false

        this.snapshot = durableSnapshot
        markerlessConflict = false
        if (!runCatching { legacy.replace(project(durableSnapshot)) }.getOrDefault(false)) {
            Log.w(TAG, "SQLite state durable but legacy compatibility journal replacement failed")
            return true
        }
        if (!runCatching { metadata.writeHash(snapshotHash) }.getOrDefault(false)) {
            Log.w(TAG, "state stores durable but compatibility marker completion failed")
        }
        return true
    }

    private companion object {
        const val TAG = "ha-paneld/state"
    }
}

internal interface LegacyStateMirror {
    fun snapshot(): Map<String, Any>
    fun persist(mutation: StateMutation): Boolean
    fun replace(snapshot: Map<String, Any>): Boolean
}

internal interface BridgeMetadata {
    fun readHash(): String?
    fun readWriteIntent(): String?
    fun writeIntent(candidateHash: String): Boolean
    fun writeHash(hash: String): Boolean
    fun writeConflict(sqliteHash: String, legacyHash: String): Boolean
}

private class SharedPreferencesBridgeMetadata(
    private val preferences: SharedPreferences,
    private val key: String,
) : BridgeMetadata {
    override fun readHash(): String? = preferences.getString(key, null)
    override fun readWriteIntent(): String? = preferences.getString("$key.write_in_progress", null)
    override fun writeIntent(candidateHash: String): Boolean = preferences.edit()
        .putString("$key.write_in_progress", candidateHash)
        .commit()

    override fun writeHash(hash: String): Boolean = preferences.edit()
        .putString(key, hash)
        .remove("$key.conflict")
        .remove("$key.write_in_progress")
        .commit()

    override fun writeConflict(sqliteHash: String, legacyHash: String): Boolean =
        preferences.edit()
            .putString("$key.conflict", "$sqliteHash:$legacyHash")
            .commit()
}

private fun MutableMap<String, Any>.applyMutation(mutation: StateMutation): Map<String, Any> = apply {
    if (mutation.clear) clear()
    mutation.changes.forEach { (key, value) ->
        if (value == null) remove(key) else put(key, value)
    }
}.toMap()

internal fun stateSnapshotHash(snapshot: Map<String, Any>): String {
    val digest = MessageDigest.getInstance("SHA-256")
    fun add(text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        digest.update(bytes.size.toString().toByteArray(Charsets.US_ASCII))
        digest.update(':'.code.toByte())
        digest.update(bytes)
    }
    snapshot.toSortedMap().forEach { (key, value) ->
        add(key)
        when (value) {
            is String -> { add("string"); add(value) }
            is Int -> { add("int"); add(value.toString()) }
            is Long -> { add("long"); add(value.toString()) }
            is Float -> { add("float"); add(value.toRawBits().toString()) }
            is Boolean -> { add("boolean"); add(if (value) "1" else "0") }
            is Set<*> -> {
                require(value.all { it is String }) { "only string sets are supported" }
                add("string_set")
                value.map { it as String }.sorted().forEach(::add)
            }
            else -> error("unsupported state type ${value.javaClass.name}")
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

private class SharedPreferencesLegacyStateMirror(
    private val preferences: SharedPreferences,
) : LegacyStateMirror {
    override fun snapshot(): Map<String, Any> = buildMap {
        preferences.all.forEach { (key, value) ->
            if (value != null) {
                @Suppress("UNCHECKED_CAST")
                put(key, if (value is Set<*>) (value as Set<String>).toSet() else value)
            }
        }
    }

    override fun persist(mutation: StateMutation): Boolean =
        preferences.edit().also { editor ->
            if (mutation.clear) editor.clear()
            mutation.changes.forEach { (key, value) -> editor.putStateValue(key, value) }
        }.commit()

    override fun replace(snapshot: Map<String, Any>): Boolean =
        preferences.edit().clear().also { editor ->
            snapshot.forEach { (key, value) -> editor.putStateValue(key, value) }
        }.commit()

    private fun SharedPreferences.Editor.putStateValue(
        key: String,
        value: Any?,
    ): SharedPreferences.Editor = when (value) {
        null -> remove(key)
        is String -> putString(key, value)
        is Int -> putInt(key, value)
        is Long -> putLong(key, value)
        is Float -> putFloat(key, value)
        is Boolean -> putBoolean(key, value)
        is Set<*> -> {
            require(value.all { it is String }) { "only string sets are supported" }
            @Suppress("UNCHECKED_CAST")
            putStringSet(key, value as Set<String>)
        }
        else -> error("unsupported state type ${value.javaClass.name}")
    }
}

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
    private val legacyName: String,
    private val legacy: SharedPreferences,
    private val clock: () -> Long = System::currentTimeMillis,
) : StateNamespacePersistence {
    private val busyRetry = DatabaseBusyRetry()

    override fun initialize(): Map<String, Any> {
        require(namespace.isNotBlank()) { "state namespace must not be blank" }
        importLegacyOnce()
        return loadAll()
    }

    private fun loadAll(): Map<String, Any> {
        val values = linkedMapOf<String, Any>()
        helper.readableDatabase.rawQuery(
            "SELECT state_key,value_type,value_text FROM app_state WHERE namespace=?",
            arrayOf(namespace),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                values[cursor.getString(0)] = decode(cursor.getString(1), cursor.getString(2))
            }
        }
        return values
    }

    private fun importLegacyOnce() {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            val imported = db.rawQuery(
                "SELECT 1 FROM app_state_namespace WHERE namespace=?",
                arrayOf(namespace),
            ).use { it.moveToFirst() }
            if (!imported) {
                val revision = insertRevision(db, "legacy:$legacyName")
                legacy.all.forEach { (key, value) -> value?.let { putValue(db, key, it, revision) } }
                db.execSQL(
                    "INSERT INTO app_state_namespace(namespace,imported_at,legacy_name) VALUES(?,?,?)",
                    arrayOf<Any?>(namespace, clock(), legacyName),
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
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

    private fun decode(type: String, text: String?): Any = when (type) {
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

    private companion object {
        const val REVISION_RETENTION = 256
    }
}
