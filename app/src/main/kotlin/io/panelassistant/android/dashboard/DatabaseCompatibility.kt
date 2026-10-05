package io.panelassistant.android.dashboard

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest

/** A candidate build's complete, finite contract with the EntityCatalog database. */
internal data class DatabaseCompatibilityBoundary(
    val formatVersion: Int,
    val databaseName: String,
    val minimumSchema: Int,
    val maximumSchema: Int,
) {
    init {
        require(formatVersion == FORMAT_VERSION) { "unsupported database compatibility format" }
        require(databaseName == DATABASE_NAME) { "unexpected database name" }
        require(minimumSchema > 0) { "minimum schema must be positive" }
        require(maximumSchema >= minimumSchema) { "maximum schema precedes minimum schema" }
    }

    fun contains(schema: Int): Boolean = schema in minimumSchema..maximumSchema

    companion object {
        const val FORMAT_VERSION = 1
        const val DATABASE_NAME = "ha-paneld.db"
    }
}

/** What is actually present at the canonical live database path. */
internal sealed interface PrimaryDatabaseObservation {
    data object Missing : PrimaryDatabaseObservation
    data class Readable(val schema: Int) : PrimaryDatabaseObservation
    data class Unreadable(val detail: String? = null) : PrimaryDatabaseObservation
}

internal enum class RecoveryDatabaseKind { PREMIGRATE, SUPERSEDED }

/** A recovery file's name claims and independently inspected SQLite state. */
internal data class RecoveryDatabaseObservation(
    val file: File,
    val kind: RecoveryDatabaseKind,
    val namedSchema: Int?,
    val actualSchema: Int?,
    val integrityValid: Boolean,
    val regularFile: Boolean,
    val standalone: Boolean = true,
    val incompleteClaim: Boolean = false,
    val sourceSha256: String? = null,
    val sourceBytes: Long? = null,
)

internal data class DatabaseCompatibilityObservation(
    val primary: PrimaryDatabaseObservation,
    val recoveries: List<RecoveryDatabaseObservation>,
    val retainedStateFiles: List<File> = emptyList(),
    val recoveryInventoryComplete: Boolean = true,
)

/** Whether a missing canonical database is sufficient proof of a legitimate fresh install. */
internal enum class DatabaseOwnerState {
    RUNTIME_STARTUP,
    PACKAGE_ABSENT_PROVEN,
    PACKAGE_PRESENT,
    PACKAGE_UNKNOWN,
}

internal enum class DatabaseCompatibilityRefusal {
    DATABASE_CHANGED_AFTER_OBSERVATION,
    PRIMARY_UNREADABLE,
    PRIMARY_MISSING_WITH_RECOVERY,
    PRIMARY_MISSING_NOT_PROVEN_FRESH,
    RECOVERY_INVENTORY_UNREADABLE,
    PRIMARY_BELOW_MINIMUM,
    PRIMARY_ABOVE_MAXIMUM_WITHOUT_PREMIGRATE,
    NEWEST_PREMIGRATE_INCOMPLETE,
    NEWEST_PREMIGRATE_NOT_REGULAR,
    NEWEST_PREMIGRATE_NOT_STANDALONE,
    NEWEST_PREMIGRATE_UNREADABLE,
    NEWEST_PREMIGRATE_SCHEMA_MISMATCH,
    NEWEST_PREMIGRATE_INTEGRITY_FAILED,
    NEWEST_PREMIGRATE_OUTSIDE_BOUNDARY,
}

/** The sole compatibility verdict consumed by startup and package-replacement entry points. */
internal sealed interface DatabaseCompatibilityDecision {
    data object Fresh : DatabaseCompatibilityDecision
    data class Direct(val schema: Int) : DatabaseCompatibilityDecision
    data class Recover(val recovery: RecoveryDatabaseObservation) : DatabaseCompatibilityDecision
    data class Refuse(val reason: DatabaseCompatibilityRefusal) : DatabaseCompatibilityDecision
}

internal object DatabaseCompatibility {
    /**
     * Decide without I/O. A candidate may open only the finite range it declares. Recovery is only a
     * downgrade operation, and only the exact newest premigrate snapshot may authorize it.
     */
    fun decide(
        boundary: DatabaseCompatibilityBoundary,
        observation: DatabaseCompatibilityObservation,
        ownerState: DatabaseOwnerState,
    ): DatabaseCompatibilityDecision {
        val primary = observation.primary
        if (primary is PrimaryDatabaseObservation.Missing) {
            return if (!observation.recoveryInventoryComplete) {
                DatabaseCompatibilityDecision.Refuse(
                    DatabaseCompatibilityRefusal.RECOVERY_INVENTORY_UNREADABLE,
                )
            } else if (observation.recoveries.isNotEmpty() || observation.retainedStateFiles.isNotEmpty()) {
                DatabaseCompatibilityDecision.Refuse(
                    DatabaseCompatibilityRefusal.PRIMARY_MISSING_WITH_RECOVERY,
                )
            } else if (
                ownerState == DatabaseOwnerState.RUNTIME_STARTUP ||
                ownerState == DatabaseOwnerState.PACKAGE_ABSENT_PROVEN
            ) {
                DatabaseCompatibilityDecision.Fresh
            } else {
                DatabaseCompatibilityDecision.Refuse(
                    DatabaseCompatibilityRefusal.PRIMARY_MISSING_NOT_PROVEN_FRESH,
                )
            }
        }
        if (primary is PrimaryDatabaseObservation.Unreadable) {
            // DB_COMPAT_MUTATION_ANCHOR: UNREADABLE_DATABASE
            return DatabaseCompatibilityDecision.Refuse(DatabaseCompatibilityRefusal.PRIMARY_UNREADABLE)
        }
        primary as PrimaryDatabaseObservation.Readable
        // DB_COMPAT_MUTATION_ANCHOR: MAXIMUM_SCHEMA
        if (boundary.contains(primary.schema)) {
            return DatabaseCompatibilityDecision.Direct(primary.schema)
        }
        // DB_COMPAT_MUTATION_ANCHOR: MINIMUM_SCHEMA
        if (primary.schema < boundary.minimumSchema) {
            return DatabaseCompatibilityDecision.Refuse(DatabaseCompatibilityRefusal.PRIMARY_BELOW_MINIMUM)
        }
        if (!observation.recoveryInventoryComplete) {
            return DatabaseCompatibilityDecision.Refuse(
                DatabaseCompatibilityRefusal.RECOVERY_INVENTORY_UNREADABLE,
            )
        }

        val newest = newestEligibleRecovery(observation.recoveries, boundary)
            ?: return DatabaseCompatibilityDecision.Refuse(
                DatabaseCompatibilityRefusal.PRIMARY_ABOVE_MAXIMUM_WITHOUT_PREMIGRATE,
            )
        val refusal = when {
            // DB_COMPAT_MUTATION_ANCHOR: RECOVERY_INCOMPLETE
            newest.incompleteClaim -> DatabaseCompatibilityRefusal.NEWEST_PREMIGRATE_INCOMPLETE
            // DB_COMPAT_MUTATION_ANCHOR: RECOVERY_FILENAME
            !newest.regularFile -> DatabaseCompatibilityRefusal.NEWEST_PREMIGRATE_NOT_REGULAR
            // DB_COMPAT_MUTATION_ANCHOR: RECOVERY_STANDALONE
            !newest.standalone -> DatabaseCompatibilityRefusal.NEWEST_PREMIGRATE_NOT_STANDALONE
            newest.actualSchema == null -> DatabaseCompatibilityRefusal.NEWEST_PREMIGRATE_UNREADABLE
            newest.actualSchema != newest.namedSchema -> {
                DatabaseCompatibilityRefusal.NEWEST_PREMIGRATE_SCHEMA_MISMATCH
            }
            // DB_COMPAT_MUTATION_ANCHOR: RECOVERY_INTEGRITY
            !newest.integrityValid -> DatabaseCompatibilityRefusal.NEWEST_PREMIGRATE_INTEGRITY_FAILED
            !boundary.contains(newest.actualSchema) -> {
                DatabaseCompatibilityRefusal.NEWEST_PREMIGRATE_OUTSIDE_BOUNDARY
            }
            else -> null
        }
        return refusal?.let(DatabaseCompatibilityDecision::Refuse)
            ?: DatabaseCompatibilityDecision.Recover(newest)
    }

    /** Android observation seam shared by pre-open startup and staged-APK admission. */
    fun observe(
        context: Context,
        boundary: DatabaseCompatibilityBoundary,
    ): DatabaseCompatibilityObservation = observeDatabaseCompatibility(context, boundary)

    fun observeAndDecide(
        context: Context,
        boundary: DatabaseCompatibilityBoundary,
        ownerState: DatabaseOwnerState,
    ): DatabaseCompatibilityDecision = decide(boundary, observe(context, boundary), ownerState)
}

private fun File.isRegularFileWithoutFollowingLinks(): Boolean =
    Files.isRegularFile(toPath(), LinkOption.NOFOLLOW_LINKS)

/** Observe the canonical Android database without mutating configuration or recovery files. */
internal fun observeDatabaseCompatibility(
    context: Context,
    boundary: DatabaseCompatibilityBoundary,
): DatabaseCompatibilityObservation = observeDatabaseCompatibility(
    target = context.getDatabasePath(boundary.databaseName),
    inspectRecoveryDatabase = { recovery -> inspectRecoveryDatabaseIsolated(recovery, context.cacheDir) },
    boundary = boundary,
)

internal data class DatabaseFileInspection(val schema: Int, val integrityValid: Boolean)

/** Injectable file observer used by JVM tests to prove absent/unreadable/recovery classification. */
internal fun observeDatabaseCompatibility(
    target: File,
    isRegularFile: (File) -> Boolean = File::isRegularFileWithoutFollowingLinks,
    inspectRecoveryDatabase: ((File) -> IsolatedRecoveryInspection?)? = null,
    boundary: DatabaseCompatibilityBoundary = EntityCatalogSchema.DATABASE_COMPATIBILITY,
    inspectDatabase: (File) -> DatabaseFileInspection? = ::inspectSqlite,
): DatabaseCompatibilityObservation {
    val orphanedPrimarySidecar = listOf("-wal", "-shm", "-journal")
        .map { File(target.path + it) }
        .firstOrNull { !Files.notExists(it.toPath(), LinkOption.NOFOLLOW_LINKS) }
    // DB_COMPAT_MUTATION_ANCHOR: ACTUAL_DATABASE_STATE
    val primary = when {
        Files.notExists(target.toPath(), LinkOption.NOFOLLOW_LINKS) && orphanedPrimarySidecar == null -> {
            PrimaryDatabaseObservation.Missing
        }
        Files.notExists(target.toPath(), LinkOption.NOFOLLOW_LINKS) -> {
            PrimaryDatabaseObservation.Unreadable("canonical database is missing beside a SQLite sidecar")
        }
        !isRegularFile(target) -> {
            PrimaryDatabaseObservation.Unreadable("canonical path is not a regular file")
        }
        else -> inspectDatabase(target)?.let {
            if (it.integrityValid) {
                PrimaryDatabaseObservation.Readable(it.schema)
            } else {
                PrimaryDatabaseObservation.Unreadable("database quick_check failed")
            }
        } ?: PrimaryDatabaseObservation.Unreadable("database could not be inspected read-only")
    }
    val recoveryInventory = observeRecoveryDatabases(
        target,
        isRegularFile,
        inspectRecoveryDatabase ?: { recovery -> inspectRecoveryDirectForTests(recovery, inspectDatabase) },
        primary,
        boundary,
    )
    return DatabaseCompatibilityObservation(
        primary = primary,
        recoveries = recoveryInventory.recoveries,
        retainedStateFiles = recoveryInventory.retainedStateFiles,
        recoveryInventoryComplete = recoveryInventory.complete,
    )
}

private fun inspectSqlite(file: File): DatabaseFileInspection? = runCatching {
    SQLiteDatabase.openDatabase(
        file.path,
        null,
        SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
    ).use { database ->
        val integrityValid = database.rawQuery("PRAGMA quick_check", emptyArray()).use { cursor ->
            cursor.moveToFirst() && cursor.getString(0).equals("ok", ignoreCase = true) &&
                !cursor.moveToNext()
        }
        DatabaseFileInspection(database.version, integrityValid)
    }
}.getOrNull()

/** The isolated inspection's scratch directory, `<prefix><digits>` directly inside the cache directory. */
internal const val RECOVERY_INSPECTION_DIRECTORY_PREFIX = "database-compatibility-"

/** The one copy inside it; SQLite may add only its own `-wal`, `-shm` or `-journal` beside it. */
internal const val RECOVERY_INSPECTION_COPY = "recovery.db"

internal data class IsolatedRecoveryInspection(
    val inspection: DatabaseFileInspection,
    val sourceSha256: String,
    val sourceBytes: Long,
)

/**
 * Inspect a recovery copy outside the database directory. Android's read-only SQLite open may still
 * create WAL bookkeeping beside a WAL-mode database; those files must be disposable, never companions
 * of the authoritative `.premigrate` source that runtime later installs as one main file.
 */
internal fun inspectRecoveryDatabaseIsolated(
    source: File,
    scratchParent: File,
    inspectCopy: (File) -> DatabaseFileInspection? = ::inspectIsolatedSqlite,
): IsolatedRecoveryInspection? = runCatching {
    if (!source.isRegularFileWithoutFollowingLinks() || !recoveryCompanionsAbsent(source)) return null
    val sourceBytesBefore = source.length()
    val sourceDigestBefore = sha256(source) ?: return null
    if (!scratchParent.isDirectory && !scratchParent.mkdirs()) return null
    val scratch = Files.createTempDirectory(scratchParent.toPath(), RECOVERY_INSPECTION_DIRECTORY_PREFIX).toFile()
    try {
        val isolated = File(scratch, RECOVERY_INSPECTION_COPY)
        Files.copy(source.toPath(), isolated.toPath(), LinkOption.NOFOLLOW_LINKS)
        if (sha256(isolated) != sourceDigestBefore) return null
        val inspected = inspectCopy(isolated) ?: return null
        // Re-read after SQLite inspection: a concurrent source rewrite or companion appearing makes the
        // observation unprovable and therefore unusable for recovery admission.
        if (source.length() != sourceBytesBefore || sha256(source) != sourceDigestBefore ||
            !recoveryCompanionsAbsent(source)
        ) return null
        IsolatedRecoveryInspection(inspected, sourceDigestBefore, sourceBytesBefore)
    } finally {
        scratch.deleteRecursively()
    }
}.getOrNull()

/** The isolated copy is disposable and writable so Android 8-era WAL bookkeeping cannot block inspection. */
private fun inspectIsolatedSqlite(file: File): DatabaseFileInspection? = runCatching {
    SQLiteDatabase.openDatabase(
        file.path,
        null,
        SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
    ).use { database ->
        val integrityValid = database.rawQuery("PRAGMA quick_check", emptyArray()).use { cursor ->
            cursor.moveToFirst() && cursor.getString(0).equals("ok", ignoreCase = true) &&
                !cursor.moveToNext()
        }
        DatabaseFileInspection(database.version, integrityValid)
    }
}.getOrNull()

/** Test-seam adapter; Android production always uses [inspectRecoveryDatabaseIsolated]. */
private fun inspectRecoveryDirectForTests(
    source: File,
    inspect: (File) -> DatabaseFileInspection?,
): IsolatedRecoveryInspection? {
    val digestBefore = sha256(source) ?: return null
    val bytesBefore = source.length()
    val inspected = inspect(source) ?: return null
    if (sha256(source) != digestBefore || source.length() != bytesBefore) return null
    return IsolatedRecoveryInspection(inspected, digestBefore, bytesBefore)
}

private fun recoveryCompanionsAbsent(source: File): Boolean =
    listOf("-wal", "-shm", "-journal", ".tmp").all { suffix ->
        Files.notExists(File(source.path + suffix).toPath(), LinkOption.NOFOLLOW_LINKS)
    }

private fun sha256(file: File): String? = runCatching {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    digest.digest().joinToString("") { byte -> "%02x".format(byte) }
}.getOrNull()

/** Bind the source identity to one immutable cache copy used for the eventual main-file install. */
internal fun stageValidatedRecovery(
    recovery: RecoveryDatabaseObservation,
    scratchParent: File,
): File? {
    var staged: File? = null
    return runCatching {
        val expectedDigest = recovery.sourceSha256 ?: return null
        val expectedBytes = recovery.sourceBytes ?: return null
        val source = recovery.file
        if (!source.isRegularFileWithoutFollowingLinks() || !recoveryCompanionsAbsent(source) ||
            source.length() != expectedBytes || sha256(source) != expectedDigest
        ) return null
        if (!scratchParent.isDirectory && !scratchParent.mkdirs()) return null
        staged = Files.createTempFile(scratchParent.toPath(), "database-recovery-", ".db").toFile()
        val exactStage = checkNotNull(staged)
        Files.copy(source.toPath(), exactStage.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        if (exactStage.length() != expectedBytes || sha256(exactStage) != expectedDigest ||
            source.length() != expectedBytes || sha256(source) != expectedDigest ||
            !recoveryCompanionsAbsent(source)
        ) {
            exactStage.delete()
            return null
        }
        exactStage
    }.getOrElse {
        staged?.delete()
        null
    }
}

private data class RecoveryInventory(
    val recoveries: List<RecoveryDatabaseObservation>,
    val retainedStateFiles: List<File>,
    val complete: Boolean,
)

private fun newestEligibleRecovery(
    recoveries: List<RecoveryDatabaseObservation>,
    boundary: DatabaseCompatibilityBoundary,
): RecoveryDatabaseObservation? = recoveries.asSequence()
    // DB_COMPAT_MUTATION_ANCHOR: SUPERSEDED_RECOVERY
    .filter { it.kind == RecoveryDatabaseKind.PREMIGRATE }
    // DB_COMPAT_MUTATION_ANCHOR: RECOVERY_SELECTION
    .filter { it.namedSchema?.let { schema -> schema <= boundary.maximumSchema } == true }
    .maxWithOrNull(
        compareBy<RecoveryDatabaseObservation> { it.namedSchema ?: Int.MIN_VALUE }
            .thenBy { it.file.name },
    )

private fun observeRecoveryDatabases(
    target: File,
    isRegularFile: (File) -> Boolean,
    inspectDatabase: (File) -> IsolatedRecoveryInspection?,
    primary: PrimaryDatabaseObservation,
    boundary: DatabaseCompatibilityBoundary,
): RecoveryInventory {
    val directory = target.parentFile ?: return RecoveryInventory(emptyList(), emptyList(), false)
    if (!directory.exists()) return RecoveryInventory(emptyList(), emptyList(), true)
    val files = directory.listFiles() ?: return RecoveryInventory(emptyList(), emptyList(), false)
    val pattern = Regex(
        "^" + Regex.escape(target.name) + "\\.v([^.]+)\\.(premigrate|superseded)$",
    )
    val retainedPattern = Regex(
        "^" + Regex.escape(target.name) +
            "(?:\\.restore\\.tmp|\\.v[^.]+\\.(?:premigrate|superseded)(?:\\.tmp|-wal|-shm|-journal))$",
    )
    val recoveriesWithMain = files
        .mapNotNull { file ->
            val match = pattern.matchEntire(file.name) ?: return@mapNotNull null
            val namedSchema = match.groupValues[1].toIntOrNull()
            val kind = when (match.groupValues[2]) {
                "premigrate" -> RecoveryDatabaseKind.PREMIGRATE
                else -> RecoveryDatabaseKind.SUPERSEDED
            }
            val regularFile = isRegularFile(file)
            RecoveryDatabaseObservation(
                file = file,
                kind = kind,
                namedSchema = namedSchema,
                actualSchema = null,
                integrityValid = false,
                regularFile = regularFile,
                standalone = recoveryCompanionsAbsent(file),
            )
        }
    val companionPattern = Regex(
        "^(" + Regex.escape(target.name) +
            "\\.v([^.]+)\\.(premigrate|superseded))(?:\\.tmp|-wal|-shm|-journal)$",
    )
    val mainNames = recoveriesWithMain.mapTo(mutableSetOf()) { it.file.name }
    val incompleteClaims = files
        .mapNotNull { companion ->
            val match = companionPattern.matchEntire(companion.name) ?: return@mapNotNull null
            val claimedMainName = match.groupValues[1]
            if (claimedMainName in mainNames) return@mapNotNull null
            RecoveryDatabaseObservation(
                file = File(directory, claimedMainName),
                kind = if (match.groupValues[3] == "premigrate") {
                    RecoveryDatabaseKind.PREMIGRATE
                } else {
                    RecoveryDatabaseKind.SUPERSEDED
                },
                namedSchema = match.groupValues[2].toIntOrNull(),
                actualSchema = null,
                integrityValid = false,
                regularFile = false,
                standalone = false,
                incompleteClaim = true,
            )
        }
        .distinctBy { Triple(it.file.name, it.kind, it.namedSchema) }
    val inventory = recoveriesWithMain + incompleteClaims
    // A missing primary needs the complete name inventory to refuse false-fresh startup. A readable
    // primary needs recovery bytes only when it is too new; then the exact newest eligible snapshot
    // alone can authorize replacement. Hashing older or superseded files cannot change that verdict.
    val selected = (primary as? PrimaryDatabaseObservation.Readable)
        ?.takeIf { it.schema > boundary.maximumSchema }
        ?.let { newestEligibleRecovery(inventory, boundary) }
    val recoveries = inventory.map { recovery ->
        if (recovery != selected || recovery.incompleteClaim || !recovery.regularFile || !recovery.standalone) {
            recovery
        } else {
            val inspected = inspectDatabase(recovery.file)
            recovery.copy(
                actualSchema = inspected?.inspection?.schema,
                integrityValid = inspected?.inspection?.integrityValid == true,
                standalone = recoveryCompanionsAbsent(recovery.file),
                sourceSha256 = inspected?.sourceSha256,
                sourceBytes = inspected?.sourceBytes,
            )
        }
    }
        .sortedWith(compareBy<RecoveryDatabaseObservation> { it.kind }.thenBy { it.namedSchema })
    return RecoveryInventory(
        recoveries = recoveries,
        retainedStateFiles = files.filter { retainedPattern.matches(it.name) }.sortedBy(File::getName),
        complete = true,
    )
}
