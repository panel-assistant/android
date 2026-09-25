package io.github.maxlyth.hapaneld.dashboard

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic coverage for the Tier-2 DB-schema downgrade net ([reconcilePreOpen]). Exercises the
 * upgrade-snapshot (with integrity and disk-space handling), downgrade-restore, sidecar preservation,
 * and no-compatible-backup fresh-start branches with real temp files and a stubbed WAL checkpoint.
 * Database snapshots carry a real SQLite header so integrity validation is exercised. The invariant:
 * config-bearing data is never lost outright, an upgrade never fails for lack of backup room, and a
 * downgrade either restores a valid compatible snapshot or preserves the newer database aside.
 */
class EntityCatalogDowngradeReconcileTest {
    private val dir: File = Files.createTempDirectory("dbnet").toFile()
    private val target = File(dir, "ha-paneld.db")

    @After fun cleanup() {
        dir.deleteRecursively()
    }

    // A minimal valid SQLite file: the 16-byte header magic followed by a marker payload.
    private val magic = byteArrayOf(
        0x53, 0x51, 0x4c, 0x69, 0x74, 0x65, 0x20, 0x66, 0x6f, 0x72, 0x6d, 0x61, 0x74, 0x20, 0x33, 0x00,
    )

    private fun writeDb(file: File, marker: String) = file.writeBytes(magic + marker.toByteArray())
    private fun markerOf(file: File): String {
        val bytes = file.readBytes()
        return String(bytes, magic.size, bytes.size - magic.size)
    }
    private fun write(file: File, content: String) = file.writeText(content) // plain sidecars
    private fun premig(version: Int) = preMigrationBackupFile(target, version)
    private fun superseded(version: Int) = supersededFile(target, version)
    private fun premigFiles() = dir.listFiles()!!.filter { it.name.contains(".$PREMIGRATE_TAG") }

    /** Legacy-shaped test adapter; every call still crosses the new finite compatibility authority. */
    @Suppress("UNUSED_PARAMETER")
    private fun reconcilePreOpen(
        target: File,
        currentVersion: Int,
        onDiskVersion: Int?,
        keepBackups: Int = 2,
        maxBackupBytes: Long = 64L * 1024 * 1024,
        freeSpace: (File) -> Long = { it.usableSpace },
        checkpoint: (File) -> Boolean = { true },
        minimumSupportedVersion: Int = 11,
        minimumCompatibleVersion: Int = 14,
        vaultConfig: (File) -> Unit = {},
        revalidateObservation: ((DatabaseCompatibilityObservation) -> DatabaseCompatibilityObservation)? = null,
    ): SchemaReconcile {
        val files = target.parentFile?.listFiles() ?: emptyArray()
        val recoveriesWithMain = files.mapNotNull { file ->
            val match = Regex("^${Regex.escape(target.name)}\\.v(\\d+)\\.(premigrate|superseded)$")
                .matchEntire(file.name) ?: return@mapNotNull null
            val named = match.groupValues[1].toInt()
            val valid = file.isFile && runCatching {
                file.inputStream().use { input ->
                    ByteArray(magic.size).also { input.read(it) }.contentEquals(magic)
                }
            }.getOrDefault(false)
            RecoveryDatabaseObservation(
                file,
                if (match.groupValues[2] == "premigrate") {
                    RecoveryDatabaseKind.PREMIGRATE
                } else {
                    RecoveryDatabaseKind.SUPERSEDED
                },
                named,
                named.takeIf { valid },
                valid,
                file.isFile,
                standalone = listOf("-wal", "-shm", "-journal", ".tmp")
                    .none { suffix -> File(file.path + suffix).exists() },
                sourceSha256 = file.takeIf { it.isFile }?.readBytes()?.contentHashCode()?.toString(),
                sourceBytes = file.takeIf { it.isFile }?.length(),
            )
        }
        val mainNames = recoveriesWithMain.mapTo(mutableSetOf()) { it.file.name }
        val companionPattern = Regex(
            "^(${Regex.escape(target.name)}\\.v([^.]+)\\.(premigrate|superseded))" +
                "(?:\\.tmp|-wal|-shm|-journal)$",
        )
        val incompleteClaims = files.mapNotNull { companion ->
            val match = companionPattern.matchEntire(companion.name) ?: return@mapNotNull null
            if (match.groupValues[1] in mainNames) return@mapNotNull null
            RecoveryDatabaseObservation(
                file = File(target.parentFile, match.groupValues[1]),
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
        }.distinctBy { Triple(it.file.name, it.kind, it.namedSchema) }
        val recoveries = recoveriesWithMain + incompleteClaims
        val observation = DatabaseCompatibilityObservation(
            onDiskVersion?.let(PrimaryDatabaseObservation::Readable)
                ?: PrimaryDatabaseObservation.Missing,
            recoveries,
        )
        return io.github.maxlyth.hapaneld.dashboard.reconcilePreOpen(
            target = target,
            boundary = DatabaseCompatibilityBoundary(1, target.name, minimumSupportedVersion, currentVersion),
            observation = observation,
            keepBackups = keepBackups,
            maxBackupBytes = maxBackupBytes,
            freeSpace = freeSpace,
            checkpoint = checkpoint,
            vaultConfig = vaultConfig,
            revalidateObservation = { revalidateObservation?.invoke(observation) ?: observation },
        )
    }

    private inline fun expectCompatibilityRefusal(block: () -> Unit): DatabaseCompatibilityException =
        try {
            block()
            throw AssertionError("expected database compatibility refusal")
        } catch (expected: DatabaseCompatibilityException) {
            expected
        }

    private fun assertFreshRevalidationRefusesBeforeMutation(
        label: String,
        revealChangedState: () -> DatabaseCompatibilityObservation,
        assertUnchanged: () -> Unit,
    ) {
        val mutationCalls = mutableListOf<String>()
        var revalidations = 0
        val refusal = expectCompatibilityRefusal {
            reconcilePreOpen(
                target,
                currentVersion = 14,
                onDiskVersion = null,
                checkpoint = { mutationCalls += "checkpoint"; true },
                vaultConfig = { mutationCalls += "vault" },
                revalidateObservation = {
                    revalidations++
                    revealChangedState()
                },
            )
        }

        assertEquals(label, DatabaseCompatibilityRefusal.DATABASE_CHANGED_AFTER_OBSERVATION, refusal.refusal)
        assertEquals("$label must be observed once at the last pre-open gate", 1, revalidations)
        assertTrue("$label must refuse before compatibility helpers run", mutationCalls.isEmpty())
        assertUnchanged()
    }

    @Test fun freshInstallNoFileIsNoOp() {
        var revalidations = 0
        val result = reconcilePreOpen(
            target,
            currentVersion = 11,
            onDiskVersion = null,
            revalidateObservation = { observed ->
                revalidations++
                observed
            },
        )

        assertEquals(SchemaReconcileAction.NONE, result.action)
        assertEquals("fresh startup must cross the last pre-open observation gate exactly once", 1, revalidations)
        assertFalse(target.exists())
        assertTrue(dir.listFiles()!!.isEmpty())
    }

    @Test fun freshStartupRefusesUnreadablePrimaryBeforeOpenOrCreate() {
        assertFreshRevalidationRefusesBeforeMutation(
            label = "unreadable primary",
            revealChangedState = {
                target.writeText("unreadable")
                DatabaseCompatibilityObservation(
                    PrimaryDatabaseObservation.Unreadable("synthetic inspection failure"),
                    emptyList(),
                )
            },
            assertUnchanged = { assertEquals("unreadable", target.readText()) },
        )
    }

    @Test fun freshStartupRefusesTooNewPrimaryBeforeOpenOrCreate() {
        assertFreshRevalidationRefusesBeforeMutation(
            label = "too-new primary",
            revealChangedState = {
                writeDb(target, "schema15-live")
                DatabaseCompatibilityObservation(PrimaryDatabaseObservation.Readable(15), emptyList())
            },
            assertUnchanged = { assertEquals("schema15-live", markerOf(target)) },
        )
    }

    @Test fun freshStartupRefusesRecoveryDatabaseBeforeOpenOrCreate() {
        assertFreshRevalidationRefusesBeforeMutation(
            label = "recovery database",
            revealChangedState = {
                writeDb(premig(14), "schema14-recovery")
                DatabaseCompatibilityObservation(
                    PrimaryDatabaseObservation.Missing,
                    listOf(
                        RecoveryDatabaseObservation(
                            file = premig(14),
                            kind = RecoveryDatabaseKind.PREMIGRATE,
                            namedSchema = 14,
                            actualSchema = 14,
                            integrityValid = true,
                            regularFile = true,
                        ),
                    ),
                )
            },
            assertUnchanged = {
                assertFalse(target.exists())
                assertEquals("schema14-recovery", markerOf(premig(14)))
            },
        )
    }

    @Test fun freshStartupRefusesRetainedRecoveryStateBeforeOpenOrCreate() {
        val retained = File(dir, "ha-paneld.db.restore.tmp")
        assertFreshRevalidationRefusesBeforeMutation(
            label = "retained recovery state",
            revealChangedState = {
                write(retained, "retained")
                DatabaseCompatibilityObservation(
                    PrimaryDatabaseObservation.Missing,
                    emptyList(),
                    retainedStateFiles = listOf(retained),
                )
            },
            assertUnchanged = {
                assertFalse(target.exists())
                assertEquals("retained", retained.readText())
            },
        )
    }

    @Test fun freshStartupRefusesOrphanedSqliteSidecarBeforeOpenOrCreate() {
        val sidecar = File(target.path + "-wal")
        assertFreshRevalidationRefusesBeforeMutation(
            label = "orphaned SQLite sidecar",
            revealChangedState = {
                write(sidecar, "orphaned-wal")
                DatabaseCompatibilityObservation(
                    PrimaryDatabaseObservation.Unreadable(
                        "canonical database is missing beside a SQLite sidecar",
                    ),
                    emptyList(),
                )
            },
            assertUnchanged = {
                assertFalse(target.exists())
                assertEquals("orphaned-wal", sidecar.readText())
            },
        )
    }

    @Test fun sameVersionIsNoOpAndTakesNoBackup() {
        writeDb(target, "v11")
        val result = reconcilePreOpen(target, currentVersion = 11, onDiskVersion = 11)
        assertEquals(SchemaReconcileAction.NONE, result.action)
        assertEquals("v11", markerOf(target))
        assertTrue(premigFiles().isEmpty())
    }

    @Test fun laterNoOpReconcileDoesNotEraseFirstMeaningfulOutcome() {
        val preserved = SchemaReconcile(SchemaReconcileAction.PRESERVED_FRESH, 13, 11)
        val first = retainFirstSchemaReconcile(null, preserved)
        val later = retainFirstSchemaReconcile(
            first,
            SchemaReconcile(SchemaReconcileAction.NONE, 11, 11),
        )

        assertEquals(preserved, later)
    }

    @Test fun upgradeWritesPreMigrationBackupAndLeavesLiveDataForOnUpgrade() {
        writeDb(target, "v12-data")
        val result = reconcilePreOpen(target, currentVersion = 13, onDiskVersion = 12)
        assertEquals(SchemaReconcileAction.BACKED_UP, result.action)
        assertEquals(12, result.fromVersion)
        assertEquals(13, result.toVersion)
        assertEquals("v12-data", markerOf(target)) // live DB untouched; onUpgrade migrates it afterwards
        assertTrue(premig(12).isFile)
        assertEquals("v12-data", markerOf(premig(12)))
    }

    @Test fun upgradeSkipsBackupWhenCheckpointFails() {
        writeDb(target, "v12")
        val result = reconcilePreOpen(target, currentVersion = 13, onDiskVersion = 12, checkpoint = { false })
        assertEquals(SchemaReconcileAction.NONE, result.action) // the upgrade still proceeds, but no backup is claimed
        assertFalse(premig(12).exists())
    }

    @Test fun upgradeDoesNotClaimBackupWhenCheckpointThrows() {
        writeDb(target, "v12")
        val result = reconcilePreOpen(
            target,
            currentVersion = 13,
            onDiskVersion = 12,
            checkpoint = { throw IllegalStateException("checkpoint failed") },
        )

        assertEquals(SchemaReconcileAction.NONE, result.action)
        assertEquals("v12", markerOf(target))
        assertFalse(premig(12).exists())
    }

    @Test fun upgradeSkipsBackupWhenDatabaseTooLarge() {
        writeDb(target, "0123456789")
        val result = reconcilePreOpen(target, currentVersion = 13, onDiskVersion = 12, maxBackupBytes = 4)
        assertEquals(SchemaReconcileAction.NONE, result.action)
        assertFalse(premig(12).exists())
    }

    @Test fun upgradeDoesNotClaimBackupWhenCompletedCopyCannotBeInstalled() {
        writeDb(target, "v12")
        assertTrue(premig(12).mkdirs()) // a directory at the destination forces renameTo to fail

        val result = reconcilePreOpen(target, currentVersion = 13, onDiskVersion = 12)

        assertEquals(SchemaReconcileAction.NONE, result.action)
        assertEquals("v12", markerOf(target))
        assertTrue(premig(12).isDirectory)
        assertFalse(File(premig(12).path + ".tmp").exists())
    }

    @Test fun upgradePrunesToNewestBackups() {
        writeDb(premig(10), "old10")
        writeDb(premig(11), "old11")
        writeDb(target, "v12")
        reconcilePreOpen(target, currentVersion = 13, onDiskVersion = 12, keepBackups = 2)
        assertTrue(premig(12).isFile)
        assertTrue(premig(11).isFile)
        assertFalse(premig(10).exists())
    }

    @Test fun upgradeReducesRetainedBackupsAndNeverFailsWhenDiskIsTight() {
        writeDb(premig(10), "old10")
        writeDb(premig(11), "old11")
        writeDb(target, "v12")
        // Almost no space free: retention must shrink and the copy is skipped — never a failed upgrade.
        val result = reconcilePreOpen(
            target, currentVersion = 13, onDiskVersion = 12, keepBackups = 2, freeSpace = { 1L },
        )
        assertEquals(SchemaReconcileAction.NONE, result.action) // upgrade proceeds, but no backup is claimed
        assertFalse(premig(12).exists()) // no room even after pruning -> skipped, not failed
        assertFalse(premig(10).exists()) // old snapshots dropped to try to free room
        assertFalse(premig(11).exists())
    }

    @Test fun upgradeFreesRoomForNewBackupByDroppingOldest() {
        writeDb(premig(10), "old10")
        writeDb(premig(11), "old11")
        writeDb(target, "v12")
        reconcilePreOpen(
            target, currentVersion = 13, onDiskVersion = 12, keepBackups = 2, freeSpace = { 100_000_000L },
        )
        assertTrue(premig(12).isFile) // new copy written
        assertTrue(premig(11).isFile) // newest existing kept (total capped at keepBackups)
        assertFalse(premig(10).exists()) // oldest dropped
    }

    /**
     * A downgrade across additive versions must be a non-event. Setting the database aside would reset
     * the owner's configuration, so this is what stops every future version bump from being a latent
     * config-reset event. Sound only because SchemaAdditivePolicy enforces additivity.
     */
    @Test fun aNewerDatabaseIsNeverOpenedThroughAnOpenEndedAdditiveShortcut() {
        writeDb(target, "written-by-a-newer-build")
        val refusal = expectCompatibilityRefusal {
            reconcilePreOpen(target, currentVersion = 12, onDiskVersion = 14)
        }
        assertEquals(DatabaseCompatibilityRefusal.PRIMARY_ABOVE_MAXIMUM_WITHOUT_PREMIGRATE, refusal.refusal)
        assertEquals("the live database must be left in place", "written-by-a-newer-build", markerOf(target))
        assertFalse("nothing may be set aside", superseded(14).exists())
    }

    @Test fun aNewerDatabaseWithoutRecoveryRefusesWithoutMutation() {
        writeDb(target, "incompatible")
        expectCompatibilityRefusal { reconcilePreOpen(target, currentVersion = 12, onDiskVersion = 14) }
        assertEquals("incompatible", markerOf(target))
        assertFalse(superseded(14).exists())
    }

    @Test fun runtimeRefusalStopsBeforeVaultCheckpointOrFileMutation() {
        writeDb(target, "schema15-live")
        val calls = mutableListOf<String>()
        val refusal = expectCompatibilityRefusal {
            reconcilePreOpen(
                target,
                currentVersion = 14,
                onDiskVersion = 15,
                checkpoint = { calls += "checkpoint"; true },
                vaultConfig = { calls += "vault" },
            )
        }
        assertEquals(DatabaseCompatibilityRefusal.PRIMARY_ABOVE_MAXIMUM_WITHOUT_PREMIGRATE, refusal.refusal)
        assertTrue(calls.isEmpty())
        assertEquals("schema15-live", markerOf(target))
        assertFalse(superseded(15).exists())
    }

    @Test fun aRefusedDowngradeDoesNotMutateTheConfigurationVault() {
        writeDb(target, "newer")
        val seen = mutableListOf<File>()
        expectCompatibilityRefusal {
            reconcilePreOpen(target, currentVersion = 12, onDiskVersion = 14, vaultConfig = { seen += it })
        }
        assertTrue(seen.isEmpty())
    }

    /**
     * Configuration is a fraction of a percent of the bytes and effectively all of the value, so it must
     * be copied out whenever the structure is about to change — including in the two cases where the
     * whole-database snapshot deliberately gives up (file too large, or too little free space), which
     * are precisely the conditions under which loss is most likely.
     */
    @Test fun configurationIsVaultedEvenWhenTheWholeDatabaseSnapshotIsSkipped() {
        fun vaultedDuring(configure: (MutableList<File>) -> SchemaReconcile): List<File> {
            val seen = mutableListOf<File>()
            configure(seen)
            return seen
        }

        writeDb(target, "v12")
        val tooLarge = vaultedDuring { seen ->
            reconcilePreOpen(
                target, currentVersion = 13, onDiskVersion = 12, maxBackupBytes = 1, vaultConfig = { seen += it },
            )
        }
        assertFalse("precondition: the whole-database snapshot was skipped", premig(12).exists())
        assertEquals("configuration must still be vaulted", listOf(target), tooLarge)

        cleanup(); dir.mkdirs(); writeDb(target, "v12")
        val noRoom = vaultedDuring { seen ->
            reconcilePreOpen(
                target, currentVersion = 13, onDiskVersion = 12, freeSpace = { 1L }, vaultConfig = { seen += it },
            )
        }
        assertFalse("precondition: the whole-database snapshot was skipped", premig(12).exists())
        assertEquals("configuration must still be vaulted", listOf(target), noRoom)
    }

    @Test fun outOfContractDatabaseRefusesBeforeVaultMutation() {
        writeDb(target, "ancient")
        val seen = mutableListOf<File>()
        expectCompatibilityRefusal {
            reconcilePreOpen(
                target, currentVersion = 13, onDiskVersion = 8, minimumSupportedVersion = 11,
                vaultConfig = { seen += it },
            )
        }
        assertTrue(seen.isEmpty())
    }

    @Test fun anUnchangedSchemaDoesNotRewriteTheVault() {
        writeDb(target, "v13")
        val seen = mutableListOf<File>()
        reconcilePreOpen(target, currentVersion = 13, onDiskVersion = 13, vaultConfig = { seen += it })
        assertTrue("no structural change means nothing to protect against", seen.isEmpty())
    }

    /**
     * A structure older than the supported floor must be preserved and replaced by a fresh store, never
     * handed to onUpgrade: its migration steps no longer exist, and a throw inside onUpgrade aborts the
     * open and takes configuration down with it.
     */
    @Test fun belowTheSupportedFloorRefusesWithoutPreservingFresh() {
        writeDb(target, "ancient")
        write(File(target.path + "-wal"), "wal")
        expectCompatibilityRefusal {
            reconcilePreOpen(target, currentVersion = 13, onDiskVersion = 8, minimumSupportedVersion = 11)
        }
        assertEquals("ancient", markerOf(target))
        assertEquals("wal", File(target.path + "-wal").readText())
        assertFalse(superseded(8).exists())
    }

    @Test fun atTheSupportedFloorTheOrdinaryUpgradePathStillRuns() {
        writeDb(target, "floor")
        val result = reconcilePreOpen(target, currentVersion = 13, onDiskVersion = 11, minimumSupportedVersion = 11)
        assertEquals(SchemaReconcileAction.BACKED_UP, result.action)
        assertEquals("floor", markerOf(target)) // left live for onUpgrade
        assertTrue(premig(11).isFile)
    }

    @Test fun downgradeRestoresNewestCompatibleBackupAndPreservesNewer() {
        writeDb(premig(10), "snap10")
        writeDb(premig(11), "snap11")
        writeDb(premig(12), "snap12") // newer than this build; must not be chosen
        writeDb(target, "v13-live")
        val result = reconcilePreOpen(target, currentVersion = 11, onDiskVersion = 13, minimumCompatibleVersion = 20)
        assertEquals(SchemaReconcileAction.RESTORED, result.action)
        assertEquals(11, result.restoredVersion)
        assertEquals(13, result.fromVersion)
        assertEquals("snap11", markerOf(target)) // opens the v11-structured snapshot this build understands
        assertTrue(superseded(13).isFile)
        assertEquals("v13-live", markerOf(superseded(13))) // newer database preserved for recovery
    }

    @Test fun schema15ToSchema14CanaryUsesRecoveryRatherThanOpenEndedTolerance() {
        writeDb(premig(14), "schema14-canary-snapshot")
        writeDb(target, "schema15-canary-live")

        val result = reconcilePreOpen(target, currentVersion = 14, onDiskVersion = 15)

        assertEquals(SchemaReconcileAction.RESTORED, result.action)
        assertEquals(15, result.fromVersion)
        assertEquals(14, result.toVersion)
        assertEquals(14, result.restoredVersion)
        assertEquals("schema14-canary-snapshot", markerOf(target))
        assertEquals("schema15-canary-live", markerOf(superseded(15)))
    }

    @Test fun aSecondSupportedRollbackAtomicallyRetainsOnlyTheLatestNewerDatabase() {
        writeDb(premig(14), "schema14-original-snapshot")
        writeDb(target, "schema15-first-cycle")

        val first = reconcilePreOpen(target, currentVersion = 14, onDiskVersion = 15)
        assertEquals(SchemaReconcileAction.RESTORED, first.action)
        assertEquals("schema14-original-snapshot", markerOf(target))
        assertEquals("schema15-first-cycle", markerOf(superseded(15)))
        assertTrue(DatabaseRestoreTransaction(target).settleRestoredAfterOpen() !is DatabaseRestoreOpenedReceipt.Hold)

        // Pre-open prepares the rollback snapshot; SQLiteOpenHelper performs the actual upgrade afterward.
        val upgrade = reconcilePreOpen(target, currentVersion = 15, onDiskVersion = 14)
        assertTrue(upgrade.action == SchemaReconcileAction.BACKED_UP || upgrade.action == SchemaReconcileAction.NONE)
        writeDb(target, "schema15-second-cycle")

        val second = reconcilePreOpen(target, currentVersion = 14, onDiskVersion = 15)

        assertEquals(SchemaReconcileAction.RESTORED, second.action)
        assertEquals(15, second.fromVersion)
        assertEquals(14, second.restoredVersion)
        assertEquals("schema14-original-snapshot", markerOf(target))
        assertEquals("schema15-second-cycle", markerOf(superseded(15)))
        assertEquals(
            listOf(superseded(15).name),
            dir.listFiles()!!.filter { it.name.contains(".superseded") }.map { it.name },
        )
        assertTrue(DatabaseRestoreTransaction(target).settleRestoredAfterOpen() !is DatabaseRestoreOpenedReceipt.Hold)
    }

    @Test fun recoveryWithWalVisibleSchemaRefusesBeforeAnyRuntimeMutation() {
        writeDb(premig(14), "schema14-main")
        write(File(premig(14).path + "-wal"), "schema15-wal")
        writeDb(target, "schema15-live")
        val calls = mutableListOf<String>()

        val refusal = expectCompatibilityRefusal {
            reconcilePreOpen(
                target,
                currentVersion = 14,
                onDiskVersion = 15,
                checkpoint = { calls += "checkpoint"; true },
                vaultConfig = { calls += "vault" },
            )
        }

        assertEquals(DatabaseCompatibilityRefusal.NEWEST_PREMIGRATE_NOT_STANDALONE, refusal.refusal)
        assertTrue(calls.isEmpty())
        assertEquals("schema15-live", markerOf(target))
        assertFalse(superseded(15).exists())
        assertEquals("schema14-main", markerOf(premig(14)))
        assertEquals("schema15-wal", File(premig(14).path + "-wal").readText())
    }

    @Test fun orphanedNewerPremigrateCompanionBlocksRuntimeFallbackWithoutMutation() {
        writeDb(premig(13), "schema13-valid")
        val orphan = File(premig(14).path + "-wal")
        write(orphan, "orphan-schema14-wal")
        writeDb(target, "schema15-live")
        val calls = mutableListOf<String>()

        val refusal = expectCompatibilityRefusal {
            reconcilePreOpen(
                target,
                currentVersion = 14,
                onDiskVersion = 15,
                checkpoint = { calls += "checkpoint"; true },
                vaultConfig = { calls += "vault" },
            )
        }

        assertEquals(DatabaseCompatibilityRefusal.NEWEST_PREMIGRATE_INCOMPLETE, refusal.refusal)
        assertTrue(calls.isEmpty())
        assertEquals("schema15-live", markerOf(target))
        assertEquals("schema13-valid", markerOf(premig(13)))
        assertEquals("orphan-schema14-wal", orphan.readText())
        assertFalse(premig(14).exists())
        assertFalse(superseded(15).exists())
    }

    @Test fun recoverySwapAfterObservationRefusesBeforeRuntimeMutation() {
        writeDb(premig(14), "schema14-observed")
        writeDb(target, "schema15-live")
        val calls = mutableListOf<String>()

        val refusal = expectCompatibilityRefusal {
            reconcilePreOpen(
                target,
                currentVersion = 14,
                onDiskVersion = 15,
                checkpoint = { calls += "checkpoint"; true },
                vaultConfig = { calls += "vault" },
                revalidateObservation = { observed ->
                    writeDb(premig(14), "schema14-swapped")
                    observed.copy(
                        recoveries = observed.recoveries.map { recovery ->
                            recovery.copy(
                                sourceSha256 = premig(14).readBytes().contentHashCode().toString(),
                                sourceBytes = premig(14).length(),
                            )
                        },
                    )
                },
            )
        }

        assertEquals(DatabaseCompatibilityRefusal.DATABASE_CHANGED_AFTER_OBSERVATION, refusal.refusal)
        assertTrue(calls.isEmpty())
        assertEquals("schema15-live", markerOf(target))
        assertEquals("schema14-swapped", markerOf(premig(14)))
        assertFalse(superseded(15).exists())
    }

    @Test fun primarySchemaChangeAfterObservationRefusesBeforeRuntimeMutation() {
        writeDb(premig(14), "schema14-recovery")
        writeDb(target, "schema15-live")
        val calls = mutableListOf<String>()

        val refusal = expectCompatibilityRefusal {
            reconcilePreOpen(
                target,
                currentVersion = 14,
                onDiskVersion = 15,
                checkpoint = { calls += "checkpoint"; true },
                vaultConfig = { calls += "vault" },
                revalidateObservation = { observed ->
                    observed.copy(primary = PrimaryDatabaseObservation.Readable(16))
                },
            )
        }

        assertEquals(DatabaseCompatibilityRefusal.DATABASE_CHANGED_AFTER_OBSERVATION, refusal.refusal)
        assertTrue(calls.isEmpty())
        assertEquals("schema15-live", markerOf(target))
        assertEquals("schema14-recovery", markerOf(premig(14)))
        assertFalse(superseded(15).exists())
    }

    @Test fun downgradeWithNoCompatibleBackupRefusesWithoutMutation() {
        writeDb(premig(12), "snap12") // only a newer-than-current snapshot exists
        writeDb(target, "v13-live")
        expectCompatibilityRefusal {
            reconcilePreOpen(target, currentVersion = 11, onDiskVersion = 13)
        }
        assertEquals("v13-live", markerOf(target))
        assertFalse(superseded(13).exists())
        assertTrue(premig(12).isFile)
    }

    @Test fun downgradeRefusesCorruptNewestSnapshotWithoutMutation() {
        premig(11).writeText("not-a-sqlite-database") // no SQLite header
        writeDb(target, "v13-live")
        val refusal = expectCompatibilityRefusal {
            reconcilePreOpen(target, currentVersion = 11, onDiskVersion = 13)
        }
        assertEquals(DatabaseCompatibilityRefusal.NEWEST_PREMIGRATE_UNREADABLE, refusal.refusal)
        assertEquals("v13-live", markerOf(target))
        assertFalse(superseded(13).exists())
    }

    @Test fun downgradeRefusesNonCanonicalSidecarsBeforeMovingTheMainDatabase() {
        writeDb(premig(11), "snap11")
        writeDb(target, "v13")
        write(File(target.path + "-wal"), "wal")
        write(File(target.path + "-shm"), "shm")
        try {
            reconcilePreOpen(target, currentVersion = 11, onDiskVersion = 13, minimumCompatibleVersion = 20)
            throw AssertionError("expected canonical-sidecar hold")
        } catch (_: DatabaseRestoreHoldException) {
            // Exact Guard recovery never carries live SQLite sidecars into the superseded artifact.
        }
        assertEquals("v13", markerOf(target))
        assertEquals("wal", File(target.path + "-wal").readText())
        assertEquals("shm", File(target.path + "-shm").readText())
        assertFalse(superseded(13).exists())
        assertFalse(File(superseded(13).path + "-wal").exists())
        assertFalse(File(superseded(13).path + "-shm").exists())
    }

    @Test fun failedCheckpointRefusesBeforeAnyCanonicalMutation() {
        writeDb(premig(11), "snap11")
        writeDb(target, "v13")
        write(File(target.path + "-wal"), "wal")
        try {
            reconcilePreOpen(
                target, currentVersion = 11, onDiskVersion = 13,
                minimumCompatibleVersion = 20, checkpoint = { false },
            )
            throw AssertionError("expected checkpoint hold")
        } catch (_: DatabaseRestoreHoldException) {
            // A failed checkpoint cannot authorize a main-only move.
        }
        assertEquals("v13", markerOf(target))
        assertEquals("wal", File(target.path + "-wal").readText())
        assertFalse(superseded(13).exists())
    }

    @Test fun preexistingSupersededArtifactHoldsWithoutPruning() {
        writeDb(superseded(12), "old-superseded")
        writeDb(premig(11), "snap11")
        writeDb(target, "v13")
        try {
            reconcilePreOpen(target, currentVersion = 11, onDiskVersion = 13, minimumCompatibleVersion = 20)
            throw AssertionError("expected retained-state hold")
        } catch (_: DatabaseRestoreHoldException) {
            // Unknown retained data is never silently pruned by the final-A transaction.
        }
        assertEquals("v13", markerOf(target))
        assertEquals("old-superseded", markerOf(superseded(12)))
        assertFalse(superseded(13).exists())
    }

    @Test fun staleHigherSupersededArtifactAlsoHoldsWithoutMutation() {
        writeDb(superseded(14), "stale-v14") // stale HIGHER-version recovery copy from an earlier downgrade
        writeDb(premig(11), "snap11")
        writeDb(target, "v13-live") // current live DB, lower version than the stale aside
        try {
            reconcilePreOpen(target, currentVersion = 11, onDiskVersion = 13, minimumCompatibleVersion = 20)
            throw AssertionError("expected retained-state hold")
        } catch (_: DatabaseRestoreHoldException) {
            // The app cannot establish which superseded artifact owns rollback authority.
        }
        assertEquals("v13-live", markerOf(target))
        assertEquals("stale-v14", markerOf(superseded(14)))
        assertFalse(superseded(13).exists())
    }

    @Test fun downgradeCheckpointsTooNewDbBeforePreserving() {
        writeDb(premig(11), "snap11")
        writeDb(target, "v13")
        val checkpointed = mutableListOf<String>()
        reconcilePreOpen(
            target, currentVersion = 11, onDiskVersion = 13, minimumCompatibleVersion = 20,
            checkpoint = { f -> checkpointed.add(f.name); true },
        )
        assertTrue(checkpointed.contains(target.name))
    }

    private companion object {
        const val PREMIGRATE_TAG = "premigrate"
    }
}
