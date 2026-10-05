package io.panelassistant.android.storage

import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Ordered from no evidence through pressure to a latched database failure. */
enum class StorageHealthSeverity {
    UNCHECKED,
    HEALTHY,
    WARNING,
    CRITICAL,
    DATABASE_FAILURE,
}

/** Sanitized result of SQLite's bounded quick check. */
enum class StorageQuickCheck {
    NOT_RUN,
    OK,
    FAILED,
}

/**
 * Whether bounded freelist reclamation is even possible for this database.
 *
 * `NONE` is not a fault and is never converted implicitly — enabling auto-vacuum on such a database
 * needs a full `VACUUM`, whose temporary-space demand can worsen a low-space incident. Until then
 * freelist pages cannot return to the filesystem, which an operator reading a large `freelist_count`
 * otherwise has no way to tell from reclamation that is merely lagging. The one conversion is inside a
 * full `VACUUM` that [fullVacuumAdmission] admits during storage remediation.
 */
enum class StorageAutoVacuumMode {
    UNKNOWN,
    NONE,
    FULL,
    INCREMENTAL,
}

/** A deliberately small failure vocabulary; exception messages never enter the health snapshot. */
enum class StorageDatabaseFailureKind {
    STORAGE_FULL,
    IO,
    CORRUPTION,
    BUSY,
    UNKNOWN,
}

/**
 * Raw bounded measurements from the filesystem containing the open database and from SQLite itself.
 * A zero [totalBytes] means that filesystem capacity was unavailable, so [usedPercent] is unknown.
 */
data class StorageHealthObservation(
    val checkedAtMillis: Long,
    val usableBytes: Long,
    val totalBytes: Long,
    val mainDatabaseBytes: Long,
    val walBytes: Long,
    val sidecarBytes: Long,
    val pageSizeBytes: Long,
    val pageCount: Long,
    val freelistCount: Long,
    val schemaVersion: Int,
    val quickCheck: StorageQuickCheck,
    val autoVacuumMode: StorageAutoVacuumMode = StorageAutoVacuumMode.UNKNOWN,
)

/** One immutable, path-free projection shared by every health surface. */
data class StorageHealthSnapshot(
    val severity: StorageHealthSeverity,
    val pressureSeverity: StorageHealthSeverity,
    val checkedAtMillis: Long,
    val usableBytes: Long,
    val totalBytes: Long,
    val usedPercent: Double?,
    val mainDatabaseBytes: Long,
    val walBytes: Long,
    val sidecarBytes: Long,
    val pageSizeBytes: Long,
    val pageCount: Long,
    val freelistCount: Long,
    val schemaVersion: Int,
    val quickCheck: StorageQuickCheck,
    val autoVacuumMode: StorageAutoVacuumMode = StorageAutoVacuumMode.UNKNOWN,
    val databaseFailureKind: StorageDatabaseFailureKind? = null,
    val databaseFailureOperation: String? = null,
    /** The most recent remediation run in this process, or null when none has run. */
    val remediation: StorageRemediationSummary? = null,
) {
    /**
     * The failing operation as it may be shown, reduced to the closed internal vocabulary.
     *
     * Capture already sanitizes, but capture is not the only way a snapshot comes into existence —
     * `copy` reaches this field directly. Reducing again at the read boundary is what makes it safe
     * for every surface to render: anything outside the known set becomes the generic `database`, so
     * a path, a query fragment or an entity id can never reach a user-visible string by construction
     * rather than by the discipline of each call site.
     */
    val databaseFailureOperationLabel: String?
        get() = databaseFailureOperation?.takeIf { it.isNotBlank() }?.let(::sanitizeDatabaseOperation)

    companion object {
        val UNCHECKED = StorageHealthSnapshot(
            severity = StorageHealthSeverity.UNCHECKED,
            pressureSeverity = StorageHealthSeverity.UNCHECKED,
            checkedAtMillis = 0L,
            usableBytes = 0L,
            totalBytes = 0L,
            usedPercent = null,
            mainDatabaseBytes = 0L,
            walBytes = 0L,
            sidecarBytes = 0L,
            pageSizeBytes = 0L,
            pageCount = 0L,
            freelistCount = 0L,
            schemaVersion = 0,
            quickCheck = StorageQuickCheck.NOT_RUN,
            autoVacuumMode = StorageAutoVacuumMode.UNKNOWN,
        )
    }
}

/**
 * Reviewable entry and clear bands. Free-space signals rise as values fall; percent and WAL signals
 * rise as values increase. Constructor validation prevents an injected policy with inverted hysteresis.
 */
data class StorageHealthThresholds(
    val warningFreeBytesEntry: Long = 512L * MIB,
    val warningFreeBytesClear: Long = 768L * MIB,
    val criticalFreeBytesEntry: Long = 128L * MIB,
    val criticalFreeBytesClear: Long = 256L * MIB,
    val warningUsedPercentEntry: Double = 90.0,
    val warningUsedPercentClear: Double = 85.0,
    val criticalUsedPercentEntry: Double = 97.0,
    val criticalUsedPercentClear: Double = 94.0,
    val warningWalBytesEntry: Long = 64L * MIB,
    val warningWalBytesClear: Long = 32L * MIB,
    val criticalWalBytesEntry: Long = 128L * MIB,
    val criticalWalBytesClear: Long = 96L * MIB,
) {
    init {
        require(criticalFreeBytesEntry in 0..criticalFreeBytesClear)
        require(criticalFreeBytesClear <= warningFreeBytesEntry)
        require(warningFreeBytesEntry <= warningFreeBytesClear)
        require(warningUsedPercentClear in 0.0..warningUsedPercentEntry)
        require(warningUsedPercentEntry <= criticalUsedPercentClear)
        require(criticalUsedPercentClear <= criticalUsedPercentEntry)
        require(criticalUsedPercentEntry <= 100.0)
        require(warningWalBytesClear in 0..warningWalBytesEntry)
        require(warningWalBytesEntry <= criticalWalBytesClear)
        require(criticalWalBytesClear <= criticalWalBytesEntry)
    }

    companion object {
        private const val MIB = 1024L * 1024L
        val DEFAULT = StorageHealthThresholds()
    }
}

/** Pure pressure policy. Database failure latching is owned separately by [StorageHealthState]. */
class StorageHealthPolicy(
    val thresholds: StorageHealthThresholds = StorageHealthThresholds.DEFAULT,
) {
    fun evaluate(
        previousSeverity: StorageHealthSeverity,
        observation: StorageHealthObservation,
    ): StorageHealthSeverity {
        val normalized = observation.normalized()
        val used = normalized.usedPercent()
        val capacityKnown = normalized.totalBytes > 0L
        val criticalEntry = (capacityKnown && normalized.usableBytes <= thresholds.criticalFreeBytesEntry) ||
            used?.let { it >= thresholds.criticalUsedPercentEntry } == true ||
            normalized.walBytes >= thresholds.criticalWalBytesEntry
        if (criticalEntry) return StorageHealthSeverity.CRITICAL

        val warningEntry = (capacityKnown && normalized.usableBytes <= thresholds.warningFreeBytesEntry) ||
            used?.let { it >= thresholds.warningUsedPercentEntry } == true ||
            normalized.walBytes >= thresholds.warningWalBytesEntry
        if (warningEntry && !capacityKnown) return StorageHealthSeverity.WARNING

        // Losing the capacity probe is not evidence of healthy headroom or that prior pressure cleared.
        if (!capacityKnown) return when (previousSeverity) {
            StorageHealthSeverity.WARNING,
            StorageHealthSeverity.CRITICAL -> previousSeverity
            else -> StorageHealthSeverity.UNCHECKED
        }

        val warningClear = (!capacityKnown || normalized.usableBytes >= thresholds.warningFreeBytesClear) &&
            (used == null || used <= thresholds.warningUsedPercentClear) &&
            normalized.walBytes <= thresholds.warningWalBytesClear
        val criticalClear = (!capacityKnown || normalized.usableBytes >= thresholds.criticalFreeBytesClear) &&
            (used == null || used <= thresholds.criticalUsedPercentClear) &&
            normalized.walBytes <= thresholds.criticalWalBytesClear

        return when (previousSeverity) {
            StorageHealthSeverity.CRITICAL -> when {
                !criticalClear -> StorageHealthSeverity.CRITICAL
                warningEntry || !warningClear -> StorageHealthSeverity.WARNING
                else -> StorageHealthSeverity.HEALTHY
            }
            StorageHealthSeverity.WARNING -> when {
                !warningClear -> StorageHealthSeverity.WARNING
                else -> StorageHealthSeverity.HEALTHY
            }
            else -> if (warningEntry) StorageHealthSeverity.WARNING else StorageHealthSeverity.HEALTHY
        }
    }
}

/** Mutable synchronization core, injectable for deterministic tests and wrapped process-wide below. */
internal class StorageHealthState(
    private val policy: StorageHealthPolicy,
) {
    private var pressureSeverity = StorageHealthSeverity.UNCHECKED
    private var databaseFailureKind: StorageDatabaseFailureKind? = null
    private var databaseFailureOperation: String? = null
    private var eventEpoch = 0L
    private var databaseFailureEpoch: Long? = null
    private var cleanObservationFailureEpoch: Long? = null
    private var current = StorageHealthSnapshot.UNCHECKED

    @Synchronized
    fun snapshot(): StorageHealthSnapshot = current

    @Synchronized
    fun beginObservation(): Long = advanceEventEpoch()

    @Synchronized
    fun refresh(observation: StorageHealthObservation, observationToken: Long): StorageHealthSnapshot {
        val normalized = observation.normalized()
        pressureSeverity = policy.evaluate(pressureSeverity, normalized)
        if (normalized.quickCheck == StorageQuickCheck.FAILED) {
            // A check that began before a newer write failure must not replace that newer evidence.
            if (databaseFailureEpoch == null || observationToken > databaseFailureEpoch!!) {
                databaseFailureKind = StorageDatabaseFailureKind.CORRUPTION
                databaseFailureOperation = QUICK_CHECK_OPERATION
                databaseFailureEpoch = advanceEventEpoch()
                cleanObservationFailureEpoch = null
            }
        } else if (databaseFailureEpoch != null) {
            // Remember exactly which failure the *current* clean observation began after. A later
            // failure, an incomplete check, or a stale probe publishing after a fresh one invalidates
            // this evidence so stale capacity cannot participate in a full-storage recovery decision.
            cleanObservationFailureEpoch = databaseFailureEpoch?.takeIf {
                normalized.quickCheck == StorageQuickCheck.OK && observationToken > it
            }
        }
        current = normalized.toSnapshot(
            severity = if (databaseFailureKind == null) pressureSeverity else StorageHealthSeverity.DATABASE_FAILURE,
            pressureSeverity = pressureSeverity,
            databaseFailureKind = databaseFailureKind,
            databaseFailureOperation = databaseFailureOperation,
            remediation = current.remediation,
        )
        return current
    }

    /** Remediation never changes severity: only a later observation can say whether pressure cleared. */
    @Synchronized
    fun recordRemediation(summary: StorageRemediationSummary): StorageHealthSnapshot {
        current = current.copy(remediation = summary)
        return current
    }

    @Synchronized
    fun recordDatabaseFailure(operation: String, throwable: Throwable): StorageHealthSnapshot {
        databaseFailureKind = classifyDatabaseFailure(throwable)
        databaseFailureOperation = sanitizeDatabaseOperation(operation)
        databaseFailureEpoch = advanceEventEpoch()
        cleanObservationFailureEpoch = null
        current = current.copy(
            severity = StorageHealthSeverity.DATABASE_FAILURE,
            databaseFailureKind = databaseFailureKind,
            databaseFailureOperation = databaseFailureOperation,
        )
        return current
    }

    @Synchronized
    fun recordDatabaseWriteSuccess(): StorageHealthSnapshot {
        // A write against a stale pre-failure observation proves too little. Recovery needs a later
        // clean quick_check and then a real write; a full-storage failure also needs healthy capacity.
        val observedAfterFailure = databaseFailureEpoch != null &&
            cleanObservationFailureEpoch == databaseFailureEpoch
        val recoveryVerified = observedAfterFailure && current.quickCheck == StorageQuickCheck.OK &&
            (databaseFailureKind != StorageDatabaseFailureKind.STORAGE_FULL ||
                (current.totalBytes > 0L && current.pressureSeverity == StorageHealthSeverity.HEALTHY))
        if (recoveryVerified) {
            databaseFailureKind = null
            databaseFailureOperation = null
            databaseFailureEpoch = null
            cleanObservationFailureEpoch = null
        }
        current = current.copy(
            severity = if (databaseFailureKind == null) pressureSeverity else StorageHealthSeverity.DATABASE_FAILURE,
            databaseFailureKind = databaseFailureKind,
            databaseFailureOperation = databaseFailureOperation,
        )
        return current
    }

    private fun advanceEventEpoch(): Long {
        if (eventEpoch < Long.MAX_VALUE) eventEpoch++
        return eventEpoch
    }

    private companion object {
        const val QUICK_CHECK_OPERATION = "quick-check"
    }
}

/** Serialization and listener core, injectable so concurrency and threshold behavior stay testable. */
internal class StorageHealthAuthority(policy: StorageHealthPolicy) {
    private val state = StorageHealthState(policy)
    /** Serializes state changes with delivery so two writers cannot publish snapshots out of order. */
    private val publicationLock = Any()
    private val listeners = LinkedHashSet<(StorageHealthSnapshot) -> Unit>()
    private val pendingPublications = java.util.ArrayDeque<StorageHealthSnapshot>()
    private var publishing = false

    fun snapshot(): StorageHealthSnapshot = state.snapshot()

    fun beginObservation(): Long = state.beginObservation()

    fun refresh(observation: StorageHealthObservation): StorageHealthSnapshot =
        refresh(observation, beginObservation())

    fun refresh(observation: StorageHealthObservation, observationToken: Long): StorageHealthSnapshot =
        publishIfChanged { state.refresh(observation, observationToken) }

    fun recordDatabaseFailure(operation: String, throwable: Throwable): StorageHealthSnapshot =
        publishIfChanged { state.recordDatabaseFailure(operation, throwable) }

    fun recordDatabaseWriteSuccess(): StorageHealthSnapshot =
        publishIfChanged { state.recordDatabaseWriteSuccess() }

    fun recordRemediation(summary: StorageRemediationSummary): StorageHealthSnapshot =
        publishIfChanged { state.recordRemediation(summary) }

    fun subscribe(listener: (StorageHealthSnapshot) -> Unit): AutoCloseable {
        synchronized(publicationLock) {
            listeners += listener
            runCatching { listener(state.snapshot()) }
        }
        return AutoCloseable { synchronized(publicationLock) { listeners -= listener } }
    }

    private inline fun publishIfChanged(change: () -> StorageHealthSnapshot): StorageHealthSnapshot {
        return synchronized(publicationLock) {
            val before = state.snapshot()
            val after = change()
            if (after != before) pendingPublications.addLast(after)
            if (!publishing) {
                publishing = true
                try {
                    while (pendingPublications.isNotEmpty()) {
                        val publication = pendingPublications.removeFirst()
                        listeners.toList().forEach { runCatching { it(publication) } }
                    }
                } finally {
                    publishing = false
                }
            }
            after
        }
    }
}

/**
 * Moves external listeners off the database writer and authority lock while preserving publication
 * order. A close suppresses queued callbacks; listeners still retain their own lifecycle fence for a
 * callback that was already executing.
 */
internal class StorageHealthListenerDispatch(
    private val executor: Executor,
) {
    fun subscribe(
        authority: StorageHealthAuthority,
        listener: (StorageHealthSnapshot) -> Unit,
    ): AutoCloseable {
        val active = AtomicBoolean(true)
        val upstream = authority.subscribe { snapshot ->
            runCatching {
                executor.execute {
                    if (active.get()) runCatching { listener(snapshot) }
                }
            }
        }
        return AutoCloseable {
            if (active.compareAndSet(true, false)) upstream.close()
        }
    }
}

/** Process-wide storage-health authority. Listeners receive only the immutable sanitized snapshot. */
object StorageHealthRuntime {
    private val authority = StorageHealthAuthority(StorageHealthPolicy())
    private val listenerDispatch = StorageHealthListenerDispatch(
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "ha-paneld-storage-health-listener").apply { isDaemon = true }
        },
    )
    private val failureHub = StorageHealthFailureHub(authority)

    fun snapshot(): StorageHealthSnapshot = authority.snapshot()

    fun refresh(observation: StorageHealthObservation): StorageHealthSnapshot = authority.refresh(observation)

    internal fun beginObservation(): Long = authority.beginObservation()

    internal fun refresh(
        observation: StorageHealthObservation,
        observationToken: Long,
    ): StorageHealthSnapshot = authority.refresh(observation, observationToken)

    fun recordDatabaseFailure(operation: String, throwable: Throwable): StorageHealthSnapshot {
        val snapshot = failureHub.recordDatabaseFailure(operation, throwable)
        // Every latch raised by a failing *operation* funnels through here. A failed `quick_check`
        // is the one exception: `StorageHealthState.refresh` sets CORRUPTION and `quick-check`
        // directly, and reports it through the snapshot rather than this line. Only the classified
        // kind and the sanitized operation are printed: the exception message can carry SQL, and
        // never enters any surface.
        android.util.Log.w(
            "StorageHealth",
            "database failure latched: operation=${snapshot.databaseFailureOperationLabel ?: "unknown"} " +
                "kind=${snapshot.databaseFailureKind?.name?.lowercase(java.util.Locale.ROOT) ?: "unknown"}",
        )
        return snapshot
    }

    fun recordDatabaseWriteSuccess(): StorageHealthSnapshot = authority.recordDatabaseWriteSuccess()

    fun recordRemediation(summary: StorageRemediationSummary): StorageHealthSnapshot =
        authority.recordRemediation(summary)

    fun subscribe(listener: (StorageHealthSnapshot) -> Unit): AutoCloseable =
        listenerDispatch.subscribe(authority, listener)

    internal fun subscribeDatabaseFailures(listener: () -> Unit): AutoCloseable =
        failureHub.subscribe(listener)
}

/**
 * Whether a bounded freelist-reclamation pass may run, given what is currently known about storage.
 *
 * Reclamation is not free before it pays: relocating pages writes WAL frames, and the space only
 * comes back at the checkpoint afterwards. A pass started with no margin can therefore deepen the
 * very low-space incident it exists to relieve, so this refuses one outright under critical pressure
 * or a latched failure, and otherwise requires room for a whole pass plus [marginBytes].
 *
 * A capacity reading that is genuinely **unavailable** — [usableBytes] of `null`, meaning the probe
 * itself could not be taken — admits the pass: losing the probe is not evidence of a full disk, and
 * the pass is bounded regardless. That is a deliberate fail-open, and it is the only one here.
 *
 * A measured **zero** is the opposite and must refuse. A full filesystem reports zero available
 * blocks, so collapsing the two into one number admitted a relocation pass on a disk with no room
 * for it — the exact outcome this gate exists to prevent.
 */
internal fun reclamationAdmitted(
    severity: StorageHealthSeverity,
    usableBytes: Long?,
    pageSizeBytes: Long,
    maxPagesPerPass: Long,
    marginBytes: Long,
): Boolean {
    if (severity == StorageHealthSeverity.CRITICAL || severity == StorageHealthSeverity.DATABASE_FAILURE) {
        return false
    }
    if (pageSizeBytes <= 0L) return true
    if (usableBytes == null) return true
    val transientBytes = storageSaturatingMultiply(maxPagesPerPass, pageSizeBytes)
    return usableBytes >= storageSaturatingAdd(transientBytes, marginBytes)
}

private fun storageSaturatingAdd(left: Long, right: Long): Long =
    if (left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right

private fun storageSaturatingMultiply(left: Long, right: Long): Long = when {
    left <= 0L || right <= 0L -> 0L
    left > Long.MAX_VALUE / right -> Long.MAX_VALUE
    else -> left * right
}

/**
 * Whether a reclamation pass should run at all, given the freelist and whether an earlier pass's
 * bytes are still stranded in the WAL.
 *
 * The second term is what makes deferred bytes recoverable. A reader whose snapshot predates a drain
 * pins the backfill, so that pass frees pages and returns nothing; by then the freelist is back at
 * its floor, and a rule that looked only at the freelist would never bring a pass back here to try
 * the checkpoint again. The bytes would sit in the WAL until the freelist happened to rebuild.
 */
internal fun reclamationPassWanted(
    freelistCount: Long,
    retainedPages: Long,
    walReclamationPending: Boolean,
): Boolean = freelistCount > retainedPages || walReclamationPending

/**
 * Whether reclaimed bytes are still stranded in the WAL after a checkpoint.
 *
 * Pending means exactly one thing: a checkpoint could not hand back bytes that a drain had already
 * made reclaimable. Any checkpoint that returns bytes clears it, and a pass that freed nothing and
 * had nothing pending cannot set it — otherwise an idle panel would checkpoint on every pass for
 * ever.
 */
internal fun walReclamationStillPending(
    freedPages: Long,
    bytesReturned: Long,
    wasPending: Boolean,
): Boolean = bytesReturned == 0L && (freedPages > 0L || wasPending)

internal fun classifyDatabaseFailure(throwable: Throwable): StorageDatabaseFailureKind {
    var candidate: Throwable? = throwable
    repeat(MAX_FAILURE_CAUSE_DEPTH) {
        val current = candidate ?: return StorageDatabaseFailureKind.UNKNOWN
        val type = current.javaClass.simpleName.lowercase()
        val message = current.message.orEmpty().take(MAX_FAILURE_MESSAGE_CHARS).lowercase()
        when {
            "sqlitefullexception" in type || "database or disk is full" in message ||
                "disk full" in message -> return StorageDatabaseFailureKind.STORAGE_FULL
            "sqlitecorrupt" in type || "malformed" in message || "not a database" in message ->
                return StorageDatabaseFailureKind.CORRUPTION
            "sqlitebusy" in type || "sqlitelocked" in type || "database is locked" in message ||
                "database is busy" in message -> return StorageDatabaseFailureKind.BUSY
            "sqlitediskio" in type || "sqlitecantopen" in type || "disk i/o" in message ||
                "i/o error" in message -> return StorageDatabaseFailureKind.IO
        }
        candidate = current.cause.takeUnless { it === current }
    }
    return StorageDatabaseFailureKind.UNKNOWN
}

internal fun sanitizeDatabaseOperation(operation: String): String {
    val bounded = operation.take(MAX_OPERATION_CHARS).lowercase()
    val sanitized = buildString(bounded.length) {
        var lastWasSeparator = false
        for (character in bounded) {
            val safe = character in 'a'..'z' || character in '0'..'9' || character == '_' || character == '-'
            if (safe) {
                append(character)
                lastWasSeparator = false
            } else if (!lastWasSeparator && isNotEmpty()) {
                append('-')
                lastWasSeparator = true
            }
        }
    }.trim('-')
    if (sanitized.startsWith("app_state-") || sanitized == "app_state") return "app-state-write"
    return sanitized.takeIf(KNOWN_DATABASE_OPERATIONS::contains) ?: "database"
}

private fun StorageHealthObservation.normalized(): StorageHealthObservation {
    val safeTotal = totalBytes.coerceAtLeast(0L)
    return copy(
        checkedAtMillis = checkedAtMillis.coerceAtLeast(0L),
        usableBytes = if (safeTotal > 0L) usableBytes.coerceIn(0L, safeTotal) else 0L,
        totalBytes = safeTotal,
        mainDatabaseBytes = mainDatabaseBytes.coerceAtLeast(0L),
        walBytes = walBytes.coerceAtLeast(0L),
        sidecarBytes = sidecarBytes.coerceAtLeast(0L),
        pageSizeBytes = pageSizeBytes.coerceAtLeast(0L),
        pageCount = pageCount.coerceAtLeast(0L),
        freelistCount = freelistCount.coerceIn(0L, pageCount.coerceAtLeast(0L)),
        schemaVersion = schemaVersion.coerceAtLeast(0),
    )
}

private fun StorageHealthObservation.usedPercent(): Double? {
    if (totalBytes <= 0L) return null
    return ((totalBytes - usableBytes).toDouble() / totalBytes.toDouble() * 100.0).coerceIn(0.0, 100.0)
}

private fun StorageHealthObservation.toSnapshot(
    severity: StorageHealthSeverity,
    pressureSeverity: StorageHealthSeverity,
    databaseFailureKind: StorageDatabaseFailureKind?,
    databaseFailureOperation: String?,
    remediation: StorageRemediationSummary?,
) = StorageHealthSnapshot(
    severity = severity,
    pressureSeverity = pressureSeverity,
    checkedAtMillis = checkedAtMillis,
    usableBytes = usableBytes,
    totalBytes = totalBytes,
    usedPercent = usedPercent(),
    mainDatabaseBytes = mainDatabaseBytes,
    walBytes = walBytes,
    sidecarBytes = sidecarBytes,
    pageSizeBytes = pageSizeBytes,
    pageCount = pageCount,
    freelistCount = freelistCount,
    schemaVersion = schemaVersion,
    quickCheck = quickCheck,
    autoVacuumMode = autoVacuumMode,
    databaseFailureKind = databaseFailureKind,
    databaseFailureOperation = databaseFailureOperation,
    remediation = remediation,
)

private const val MAX_FAILURE_CAUSE_DEPTH = 8
private const val MAX_FAILURE_MESSAGE_CHARS = 256
private const val MAX_OPERATION_CHARS = 48
internal val KNOWN_DATABASE_OPERATIONS = setOf(
    // The canonical form every `app_state:<namespace>` write collapses to. It must be a member in
    // its own right, or reducing an already-reduced label would degrade it to the generic fallback
    // — which is exactly what happens when a snapshot is rendered rather than captured.
    "app-state-write",
    "ambient-history",
    "ambient-history-reset",
    "ambient-history-seed",
    "catalog-access-history",
    "catalog-issue-override",
    "catalog-maintenance",
    "catalog-metric-history",
    "catalog-overrides",
    "catalog-reset",
    "catalog-retention",
    "catalog-scope-migration",
    "catalog-status",
    "catalog-sync",
    "dashboard-performance-history",
    "database-checkpoint",
    "database-create",
    "database-vacuum",
    "database-downgrade-tripwire",
    "database-preopen-reconcile",
    "database-upgrade",
    "database-vault-read",
    "database-vault-restore",
    "database-version-read",
    "database-wal-truncate",
    "proximity-history",
    "proximity-history-reset",
    "quick-check",
    "storage-health-read",
)
