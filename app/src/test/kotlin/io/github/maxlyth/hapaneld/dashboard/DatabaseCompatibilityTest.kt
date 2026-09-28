package io.github.maxlyth.hapaneld.dashboard

import io.github.maxlyth.hapaneld.BuildConfig
import io.github.maxlyth.hapaneld.util.DatabaseCompatibilityApkContract
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DatabaseCompatibilityTest {
    private val boundary = DatabaseCompatibilityBoundary(1, "ha-paneld.db", 11, 14)

    @Test fun sharedLanguageNeutralVectorsPinEveryVerdict() {
        val rows = vectorFile().readLines().filter { it.isNotBlank() && !it.startsWith("#") }
        assertTrue("compatibility vectors must not be empty", rows.isNotEmpty())
        rows.forEach { line ->
            val fields = line.split('\t')
            assertEquals("vector field count: $line", 7, fields.size)
            val id = fields[0]
            val rawContract = fields[1]
            val rawOwner = fields[2]
            val rawPrimary = fields[3]
            val rawRecoveries = fields[4]
            val expectedVerdict = fields[5]
            val expectedReason = fields[6]
            when (val parsed = DatabaseCompatibilityApkContract.parse(rawContract.takeUnless { it == "-" })) {
                DatabaseCompatibilityApkContract.Parsed.Missing -> {
                    assertEquals(id, "REFUSE", expectedVerdict)
                    assertEquals(id, "CANDIDATE_METADATA_MISSING", expectedReason)
                }
                is DatabaseCompatibilityApkContract.Parsed.Malformed -> {
                    assertEquals(id, "REFUSE", expectedVerdict)
                    assertEquals(id, "CANDIDATE_METADATA_MALFORMED", expectedReason)
                }
                is DatabaseCompatibilityApkContract.Parsed.Valid -> {
                    val contract = parsed.boundary
                    val decision = DatabaseCompatibility.decide(
                        DatabaseCompatibilityBoundary(
                            contract.formatVersion,
                            contract.databaseName,
                            contract.minimumSchema,
                            contract.maximumSchema,
                        ),
                        DatabaseCompatibilityObservation(
                            parsePrimary(rawPrimary),
                            parseRecoveries(rawRecoveries),
                        ),
                        DatabaseOwnerState.valueOf(rawOwner),
                    )
                    val actualVerdict = when (decision) {
                        DatabaseCompatibilityDecision.Fresh -> "FRESH"
                        is DatabaseCompatibilityDecision.Direct -> "DIRECT"
                        is DatabaseCompatibilityDecision.Recover -> "RECOVER"
                        is DatabaseCompatibilityDecision.Refuse -> "REFUSE"
                    }
                    val actualReason = when (decision) {
                        is DatabaseCompatibilityDecision.Refuse -> decision.reason.name
                        is DatabaseCompatibilityDecision.Recover -> decision.recovery.file.name
                        else -> "-"
                    }
                    assertEquals(id, expectedVerdict, actualVerdict)
                    assertEquals(id, expectedReason, actualReason)
                }
            }
        }
    }

    @Test fun currentSchemaBoundaryMatchesTheSignedContract() {
        val boundary = EntityCatalogSchema.DATABASE_COMPATIBILITY
        assertEquals(1, boundary.formatVersion)
        assertEquals(EntityCatalogStore.DATABASE_NAME, boundary.databaseName)
        assertEquals(EntityCatalogSchema.MINIMUM_SUPPORTED_VERSION, boundary.minimumSchema)
        assertEquals(EntityCatalogSchema.CURRENT_VERSION, boundary.maximumSchema)
        assertEquals(
            BuildConfig.DATABASE_COMPATIBILITY,
            DatabaseCompatibilityApkContract.encode(
                DatabaseCompatibilityApkContract.Boundary(
                    boundary.formatVersion,
                    boundary.databaseName,
                    boundary.minimumSchema,
                    boundary.maximumSchema,
                ),
            ),
        )
    }

    @Test fun fileObserverDistinguishesFreshUnreadableAndIntegrityFailure() {
        val directory = kotlin.io.path.createTempDirectory("compat-observe").toFile()
        try {
            val target = File(directory, "ha-paneld.db")
            assertEquals(
                PrimaryDatabaseObservation.Missing,
                observeDatabaseCompatibility(target, inspectDatabase = { null }).primary,
            )

            File(target.path + "-wal").writeText("orphaned")
            assertTrue(
                observeDatabaseCompatibility(target, inspectDatabase = { null }).primary
                    is PrimaryDatabaseObservation.Unreadable,
            )
            File(target.path + "-wal").delete()

            File(target.path + "-shm").writeText("orphaned")
            assertTrue(
                observeDatabaseCompatibility(target, inspectDatabase = { null }).primary
                    is PrimaryDatabaseObservation.Unreadable,
            )
            File(target.path + "-shm").delete()

            File(target.path + "-journal").writeText("orphaned")
            assertTrue(
                observeDatabaseCompatibility(target, inspectDatabase = { null }).primary
                    is PrimaryDatabaseObservation.Unreadable,
            )
            File(target.path + "-journal").delete()

            target.writeText("present")
            assertTrue(
                observeDatabaseCompatibility(target, inspectDatabase = { null }).primary
                    is PrimaryDatabaseObservation.Unreadable,
            )
            assertTrue(
                observeDatabaseCompatibility(
                    target,
                    inspectDatabase = { DatabaseFileInspection(14, integrityValid = false) },
                ).primary is PrimaryDatabaseObservation.Unreadable,
            )
            assertEquals(
                PrimaryDatabaseObservation.Readable(14),
                observeDatabaseCompatibility(
                    target,
                    inspectDatabase = { DatabaseFileInspection(14, integrityValid = true) },
                ).primary,
            )
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun directOpenDoesNotReadLargeUnusedRecovery() {
        val directory = kotlin.io.path.createTempDirectory("compat-unused-recovery").toFile()
        try {
            val target = File(directory, "ha-paneld.db").apply { writeText("current database") }
            val unused = File(directory, "ha-paneld.db.v14.premigrate")
            java.io.RandomAccessFile(unused, "rw").use { it.setLength(60L * 1024 * 1024) }
            var inspected = 0
            val observation = observeDatabaseCompatibility(
                target,
                inspectRecoveryDatabase = { inspected++; null },
                inspectDatabase = { DatabaseFileInspection(14, true) },
            )
            assertEquals(
                DatabaseCompatibilityDecision.Direct(14),
                DatabaseCompatibility.decide(boundary, observation, DatabaseOwnerState.RUNTIME_STARTUP),
            )
            assertEquals("unused recovery bytes must not be inspected", 0, inspected)
            assertEquals(60L * 1024 * 1024, unused.length())
            assertEquals(listOf(target.name, unused.name), directory.listFiles()!!.map(File::getName).sorted())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun fileObserverInspectsOnlyRecoveryThatCanAuthorizeDowngrade() {
        val directory = kotlin.io.path.createTempDirectory("compat-recovery").toFile()
        try {
            val target = File(directory, "ha-paneld.db").apply { writeText("live") }
            val older = File(directory, "ha-paneld.db.v13.premigrate").apply { writeText("older") }
            val premigrate = File(directory, "ha-paneld.db.v14.premigrate").apply { writeText("snapshot") }
            val superseded = File(directory, "ha-paneld.db.v15.superseded").apply { writeText("aside") }
            val observation = observeDatabaseCompatibility(target) { file ->
                when (file) {
                    target -> DatabaseFileInspection(15, true)
                    premigrate -> DatabaseFileInspection(14, true)
                    older -> error("older premigrate cannot authorize recovery")
                    superseded -> error("superseded database cannot authorize recovery")
                    else -> null
                }
            }
            assertEquals(PrimaryDatabaseObservation.Readable(15), observation.primary)
            assertEquals(3, observation.recoveries.size)
            val premigrateObservation = observation.recoveries.single {
                it.file == premigrate
            }
            assertEquals(
                RecoveryDatabaseObservation(
                    premigrate, RecoveryDatabaseKind.PREMIGRATE, 14, 14, true, true,
                    sourceSha256 = premigrateObservation.sourceSha256,
                    sourceBytes = premigrate.length(),
                ),
                premigrateObservation,
            )
            assertTrue(!premigrateObservation.sourceSha256.isNullOrBlank())
            assertEquals(
                RecoveryDatabaseObservation(older, RecoveryDatabaseKind.PREMIGRATE, 13, null, false, true),
                observation.recoveries.single { it.file == older },
            )
            val supersededObservation = observation.recoveries.single {
                it.kind == RecoveryDatabaseKind.SUPERSEDED
            }
            assertEquals(
                RecoveryDatabaseObservation(
                    superseded, RecoveryDatabaseKind.SUPERSEDED, 15, null, false, true,
                ),
                supersededObservation,
            )
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun malformedRecoveryNameStillPreventsFreshClassification() {
        val directory = kotlin.io.path.createTempDirectory("compat-malformed-recovery").toFile()
        try {
            val target = File(directory, "ha-paneld.db")
            File(directory, "ha-paneld.db.v999999999999999999999.premigrate").writeText("retained")
            val observation = observeDatabaseCompatibility(target, inspectDatabase = { null })
            assertEquals(PrimaryDatabaseObservation.Missing, observation.primary)
            assertEquals(1, observation.recoveries.size)
            assertEquals(null, observation.recoveries.single().namedSchema)
            assertEquals(
                DatabaseCompatibilityDecision.Refuse(
                    DatabaseCompatibilityRefusal.PRIMARY_MISSING_WITH_RECOVERY,
                ),
                DatabaseCompatibility.decide(
                    EntityCatalogSchema.DATABASE_COMPATIBILITY,
                    observation,
                    DatabaseOwnerState.RUNTIME_STARTUP,
                ),
            )
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun incompleteOrTemporaryRecoveryStateCannotMasqueradeAsFresh() {
        val directory = kotlin.io.path.createTempDirectory("compat-partial-recovery").toFile()
        try {
            val target = File(directory, "ha-paneld.db")
            listOf(
                File(target.path + ".restore.tmp"),
                File(directory, "ha-paneld.db.v14.premigrate.tmp"),
                File(directory, "ha-paneld.db.v15.superseded-wal"),
            ).forEach { retained ->
                retained.writeText("retained")
                val observation = observeDatabaseCompatibility(target, inspectDatabase = { null })
                assertEquals(PrimaryDatabaseObservation.Missing, observation.primary)
                assertTrue(observation.retainedStateFiles.contains(retained))
                assertEquals(
                    refused(DatabaseCompatibilityRefusal.PRIMARY_MISSING_WITH_RECOVERY),
                    DatabaseCompatibility.decide(
                        boundary,
                        observation,
                        DatabaseOwnerState.RUNTIME_STARTUP,
                    ),
                )
                retained.delete()
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun unreadableRecoveryInventoryFailsClosedWhenRecoveryIsRequired() {
        assertEquals(
            refused(DatabaseCompatibilityRefusal.RECOVERY_INVENTORY_UNREADABLE),
            DatabaseCompatibility.decide(
                boundary,
                DatabaseCompatibilityObservation(
                    PrimaryDatabaseObservation.Readable(15),
                    emptyList(),
                    recoveryInventoryComplete = false,
                ),
                DatabaseOwnerState.PACKAGE_PRESENT,
            ),
        )
    }

    @Test fun actualDatabaseStateRatherThanTheRunningSchemaDrivesAdmission() {
        val directory = kotlin.io.path.createTempDirectory("compat-actual-state").toFile()
        try {
            val target = File(directory, "ha-paneld.db").apply { writeText("synthetic future database") }
            val observed = observeDatabaseCompatibility(
                target,
                inspectDatabase = { DatabaseFileInspection(schema = 15, integrityValid = true) },
            )
            assertEquals(PrimaryDatabaseObservation.Readable(15), observed.primary)
            assertEquals(
                refused(DatabaseCompatibilityRefusal.PRIMARY_ABOVE_MAXIMUM_WITHOUT_PREMIGRATE),
                DatabaseCompatibility.decide(boundary, observed, DatabaseOwnerState.PACKAGE_PRESENT),
            )
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun unreadableDatabaseFailsClosed() {
        assertEquals(
            refused(DatabaseCompatibilityRefusal.PRIMARY_UNREADABLE),
            decide(PrimaryDatabaseObservation.Unreadable("synthetic read failure")),
        )
    }

    @Test fun schemaBelowCandidateMinimumIsRefused() {
        assertEquals(
            refused(DatabaseCompatibilityRefusal.PRIMARY_BELOW_MINIMUM),
            decide(PrimaryDatabaseObservation.Readable(10), recovery(14)),
        )
    }

    @Test fun schemaAboveCandidateMaximumNeedsExactRecovery() {
        assertEquals(
            refused(DatabaseCompatibilityRefusal.PRIMARY_ABOVE_MAXIMUM_WITHOUT_PREMIGRATE),
            decide(PrimaryDatabaseObservation.Readable(15)),
        )
        assertEquals(
            DatabaseCompatibilityDecision.Recover(recovery(14)),
            decide(PrimaryDatabaseObservation.Readable(15), recovery(14)),
        )
    }

    @Test fun recoverySchemaMustMatchItsFilename() {
        assertEquals(
            refused(DatabaseCompatibilityRefusal.NEWEST_PREMIGRATE_SCHEMA_MISMATCH),
            decide(PrimaryDatabaseObservation.Readable(15), recovery(named = 14, actual = 13)),
        )
    }

    @Test fun recoveryIntegrityMustBeProven() {
        assertEquals(
            refused(DatabaseCompatibilityRefusal.NEWEST_PREMIGRATE_INTEGRITY_FAILED),
            decide(PrimaryDatabaseObservation.Readable(15), recovery(14, integrity = false)),
        )
    }

    @Test fun recoveryCompanionStateCannotLicenseMainOnlyRestore() {
        val directory = kotlin.io.path.createTempDirectory("compat-recovery-sidecar").toFile()
        try {
            val target = File(directory, "ha-paneld.db").apply { writeText("schema15-live") }
            val premigrate = File(directory, "ha-paneld.db.v14.premigrate").apply { writeText("schema14-main") }
            listOf("-wal", "-shm", "-journal", ".tmp").forEach { suffix ->
                val companion = File(premigrate.path + suffix).apply { writeText("companion") }
                val observation = observeDatabaseCompatibility(target) { file ->
                    when (file) {
                        target -> DatabaseFileInspection(15, true)
                        // WAL may expose state newer than the standalone main runtime would copy.
                        premigrate -> DatabaseFileInspection(if (suffix == "-wal") 15 else 14, true)
                        else -> null
                    }
                }
                val recovery = observation.recoveries.single { it.kind == RecoveryDatabaseKind.PREMIGRATE }
                assertFalse("$suffix must make the recovery non-standalone", recovery.standalone)
                assertEquals(
                    suffix,
                    refused(DatabaseCompatibilityRefusal.NEWEST_PREMIGRATE_NOT_STANDALONE),
                    DatabaseCompatibility.decide(boundary, observation, DatabaseOwnerState.PACKAGE_PRESENT),
                )
                companion.delete()
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun walModeRecoveryInspectionIsIsolatedAndLeavesSourceInventoryUntouched() {
        val directory = kotlin.io.path.createTempDirectory("compat-isolated-source").toFile()
        val cache = kotlin.io.path.createTempDirectory("compat-isolated-cache").toFile()
        try {
            val source = File(directory, "ha-paneld.db.v14.premigrate")
                .apply { writeText("standalone-wal-mode-main") }
            val sourceBytes = source.readBytes()
            val inventoryBefore = directory.listFiles()!!.map(File::getName).sorted()

            val inspected = inspectRecoveryDatabaseIsolated(source, cache) { isolated ->
                assertFalse("SQLite must inspect a copy outside the source directory", isolated.parentFile == directory)
                File(isolated.path + "-wal").writeText("sqlite-created-wal")
                File(isolated.path + "-shm").writeText("sqlite-created-shm")
                DatabaseFileInspection(14, integrityValid = true)
            }

            requireNotNull(inspected)
            assertEquals(DatabaseFileInspection(14, true), inspected.inspection)
            assertTrue(source.readBytes().contentEquals(sourceBytes))
            assertEquals(inventoryBefore, directory.listFiles()!!.map(File::getName).sorted())
            assertTrue("isolated scratch directories must be removed", cache.listFiles().isNullOrEmpty())
            val observedRecovery = recovery(14).copy(
                file = source,
                sourceSha256 = inspected.sourceSha256,
                sourceBytes = inspected.sourceBytes,
            )
            assertEquals(
                DatabaseCompatibilityDecision.Recover(observedRecovery),
                DatabaseCompatibility.decide(
                    boundary,
                    DatabaseCompatibilityObservation(
                        PrimaryDatabaseObservation.Readable(15),
                        listOf(observedRecovery),
                    ),
                    DatabaseOwnerState.PACKAGE_PRESENT,
                ),
            )
            val staged = requireNotNull(stageValidatedRecovery(observedRecovery, cache))
            assertTrue(staged.readBytes().contentEquals(sourceBytes))
            assertTrue(source.readBytes().contentEquals(sourceBytes))
            assertEquals(inventoryBefore, directory.listFiles()!!.map(File::getName).sorted())
            staged.delete()
        } finally {
            directory.deleteRecursively()
            cache.deleteRecursively()
        }
    }

    @Test fun orphanedNewerPremigrateCompanionBlocksFallbackToOlderRecovery() {
        val directory = kotlin.io.path.createTempDirectory("compat-orphan-newest").toFile()
        try {
            val target = File(directory, "ha-paneld.db").apply { writeText("schema15-live") }
            val older = File(directory, "ha-paneld.db.v13.premigrate").apply { writeText("schema13-valid") }
            val orphan = File(directory, "ha-paneld.db.v14.premigrate-wal").apply { writeText("orphan-wal") }
            val observation = observeDatabaseCompatibility(target) { file ->
                when (file) {
                    target -> DatabaseFileInspection(15, true)
                    older -> DatabaseFileInspection(13, true)
                    else -> null
                }
            }

            assertTrue(observation.retainedStateFiles.contains(orphan))
            val incomplete = observation.recoveries.single { it.namedSchema == 14 }
            assertTrue(incomplete.incompleteClaim)
            assertFalse(incomplete.regularFile)
            assertEquals(
                refused(DatabaseCompatibilityRefusal.NEWEST_PREMIGRATE_INCOMPLETE),
                DatabaseCompatibility.decide(boundary, observation, DatabaseOwnerState.PACKAGE_PRESENT),
            )
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun poisonedNewestPremigrateCannotFallBackToOlder() {
        assertEquals(
            refused(DatabaseCompatibilityRefusal.NEWEST_PREMIGRATE_INTEGRITY_FAILED),
            decide(
                PrimaryDatabaseObservation.Readable(15),
                recovery(13),
                recovery(14, integrity = false),
            ),
        )
    }

    @Test fun incompleteNewestPremigrateCannotFallBackToOlder() {
        assertEquals(
            refused(DatabaseCompatibilityRefusal.NEWEST_PREMIGRATE_INCOMPLETE),
            decide(
                PrimaryDatabaseObservation.Readable(15),
                recovery(13),
                recovery(14, actual = null, integrity = false, standalone = false, incomplete = true),
            ),
        )
    }

    @Test fun supersededDatabaseNeverAuthorizesRecovery() {
        assertEquals(
            refused(DatabaseCompatibilityRefusal.PRIMARY_ABOVE_MAXIMUM_WITHOUT_PREMIGRATE),
            decide(
                PrimaryDatabaseObservation.Readable(15),
                recovery(14, kind = RecoveryDatabaseKind.SUPERSEDED),
            ),
        )
    }

    @Test fun recoveryActualSchemaBelowCandidateMinimumIsRefused() {
        assertEquals(
            refused(DatabaseCompatibilityRefusal.NEWEST_PREMIGRATE_OUTSIDE_BOUNDARY),
            decide(PrimaryDatabaseObservation.Readable(15), recovery(10)),
        )
    }

    private fun decide(
        primary: PrimaryDatabaseObservation,
        vararg recoveries: RecoveryDatabaseObservation,
    ): DatabaseCompatibilityDecision = DatabaseCompatibility.decide(
        boundary,
        DatabaseCompatibilityObservation(primary, recoveries.toList()),
        DatabaseOwnerState.PACKAGE_PRESENT,
    )

    private fun recovery(
        named: Int,
        actual: Int? = named,
        integrity: Boolean = true,
        kind: RecoveryDatabaseKind = RecoveryDatabaseKind.PREMIGRATE,
        standalone: Boolean = true,
        incomplete: Boolean = false,
    ): RecoveryDatabaseObservation = RecoveryDatabaseObservation(
        File("ha-paneld.db.v$named.${kind.name.lowercase()}"),
        kind,
        named,
        actual,
        integrity,
        regularFile = true,
        standalone = standalone,
        incompleteClaim = incomplete,
    )

    private fun refused(reason: DatabaseCompatibilityRefusal) =
        DatabaseCompatibilityDecision.Refuse(reason)

    private fun parsePrimary(raw: String): PrimaryDatabaseObservation = when {
        raw == "MISSING" -> PrimaryDatabaseObservation.Missing
        raw == "UNREADABLE" -> PrimaryDatabaseObservation.Unreadable()
        raw.startsWith("READABLE:") -> PrimaryDatabaseObservation.Readable(raw.substringAfter(':').toInt())
        else -> error("unknown primary observation: $raw")
    }

    private fun parseRecoveries(raw: String): List<RecoveryDatabaseObservation> =
        if (raw == "-") {
            emptyList()
        } else {
            raw.split(';').map { encoded ->
                val fields = encoded.split(':')
                require(fields.size == 6) { "invalid recovery vector: $encoded" }
                RecoveryDatabaseObservation(
                    file = File(fields[1]),
                    kind = if (fields[0] == "P") {
                        RecoveryDatabaseKind.PREMIGRATE
                    } else {
                        RecoveryDatabaseKind.SUPERSEDED
                    },
                    namedSchema = fields[2].toInt(),
                    actualSchema = fields[3].takeUnless { it == "?" }?.toInt(),
                    integrityValid = fields[4] == "ok",
                    regularFile = fields[5] != "not-file" && fields[5] != "incomplete",
                    standalone = fields[5] != "file+sidecar",
                    incompleteClaim = fields[5] == "incomplete",
                )
            }
        }

    private fun vectorFile(): File = listOf(
        File("../scripts/tests/fixtures/database-compatibility-vectors.tsv"),
        File("scripts/tests/fixtures/database-compatibility-vectors.tsv"),
    ).firstOrNull(File::isFile) ?: error("database compatibility vector fixture not found")
}
