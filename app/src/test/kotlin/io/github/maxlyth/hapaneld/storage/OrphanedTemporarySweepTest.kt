package io.github.maxlyth.hapaneld.storage

import io.github.maxlyth.hapaneld.util.GuardDbAppStaging
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The temporary directories and Guard DB claim temporaries a dead process leaves behind are removed
 * only when their writer's exact names prove them this app's and every entry predates this process in
 * the oldest clock epoch it has run in; anything a live operation or the Guard DB staging still owns
 * is kept, and what was removed reaches the remediation summary as measured bytes.
 */
class OrphanedTemporarySweepTest {
    private lateinit var root: File
    private lateinit var cacheDir: File
    private lateinit var filesDir: File

    private val hour = DISPOSABLE_ORPHAN_MINIMUM_AGE_MS
    private val now = 1_800_000_000_000L
    private val processStart = now - 10L * 60L * 1000L
    private val old = processStart - 2L * hour

    @Before fun createDirectories() {
        root = Files.createTempDirectory("orphaned-temporary-sweep").toFile()
        cacheDir = File(root, "cache").apply { mkdirs() }
        filesDir = File(root, "files").apply { mkdirs() }
    }

    @After fun removeDirectories() {
        root.deleteRecursively()
    }

    private fun sweeper(start: Long = processStart, at: Long = now) = DisposableFileSweeper(start, nowMillis = { at })

    private fun sweepDirectories(sweeper: DisposableFileSweeper = sweeper()) =
        appOwnedDisposableDirectoryRules(cacheDir).map(sweeper::sweep)

    private fun guardRule(owned: (File) -> Boolean = { true }) =
        appOwnedDisposableFileRules(cacheDir, filesDir, guardDbFileOwned = owned)
            .single { it.ownedName.matches(".guard-db-candidate-a.pending") }

    private fun file(directory: File, name: String, modified: Long = old, bytes: Int = 100): File =
        File(directory, name).apply {
            parentFile.mkdirs()
            writeBytes(ByteArray(bytes))
            setLastModified(modified)
        }

    /** Writing children moves a directory's own time, so directories are dated last, deepest first. */
    private fun tree(name: String, files: Map<String, Int>, modified: Long = old): File {
        val top = File(cacheDir, name)
        files.forEach { (path, bytes) -> file(top, path, modified, bytes) }
        top.walkBottomUp().filter { it.isDirectory }.forEach { it.setLastModified(modified) }
        return top
    }

    private fun companionCapture(name: String = "companion-capture-123456789-0", modified: Long = old) = tree(
        name,
        mapOf(
            "databases/HomeAssistantDB" to 1_000,
            "databases/HomeAssistantDB-wal" to 200,
            "databases/HomeAssistantDB-shm" to 30,
            "shared_prefs/session_0.xml" to 40,
            "shared_prefs/integration_0.xml" to 50,
        ),
        modified,
    )

    // ---- temporary directories ----

    @Test fun anOrphanedCompanionCaptureIsRemovedWholeAndItsBytesMeasured() {
        val capture = companionCapture()

        val result = sweepDirectories().single { it.filesDeleted > 0 }

        assertFalse("a capture a dead process left behind is disposable", capture.exists())
        assertEquals(DisposableDataClass.TEMPORARY_DIRECTORIES, result.dataClass)
        assertEquals(5, result.filesDeleted)
        assertEquals(1_320L, result.bytesFreed)
        assertEquals(0, result.deleteFailures)
    }

    @Test fun anOrphanedRecoveryInspectionIsRemovedWhole() {
        val inspection = tree(
            "database-compatibility-8123456789012345678",
            mapOf("recovery.db" to 4_096, "recovery.db-wal" to 512, "recovery.db-shm" to 64),
        )

        val removed = sweepDirectories().sumOf { it.bytesFreed }

        assertFalse(inspection.exists())
        assertEquals(4_672L, removed)
    }

    @Test fun aCaptureThisProcessMayStillBeWritingIsKeptWhole() {
        // A live capture: one frame written after this process started, however old the rest looks.
        val live = companionCapture()
        val frame = File(live, "shared_prefs/integration_0.xml").apply { setLastModified(processStart + 1_000L) }

        val results = sweepDirectories()

        assertTrue(frame.exists())
        assertTrue("no entry of a tree a live operation may hold is removed", File(live, "databases/HomeAssistantDB").exists())
        assertEquals(0, results.sumOf { it.filesDeleted })
        assertEquals(5, results.sumOf { it.filesRetained })
    }

    @Test fun aDirectoryCreatedByThisProcessIsKeptEvenWhenEmpty() {
        val fresh = File(cacheDir, "database-compatibility-1").apply { mkdirs(); setLastModified(processStart + 1L) }

        sweepDirectories()

        assertTrue(fresh.exists())
    }

    @Test fun aTreeHoldingAnythingItsWriterNeverCreatesIsKeptWhole() {
        val capture = companionCapture()
        val stranger = file(capture, "databases/other.db")
        File(capture, "databases").setLastModified(old)
        capture.setLastModified(old)

        sweepDirectories()

        assertTrue(stranger.exists())
        assertTrue("an unprovable tree loses nothing", File(capture, "databases/HomeAssistantDB").exists())
    }

    @Test fun aLinkInsideATemporaryDirectoryIsNeverFollowedAndKeepsTheTree() {
        val elsewhere = File(root, "other-app").apply { mkdirs() }
        val victim = file(elsewhere, "HomeAssistantDB")
        val inspection = tree("database-compatibility-5", mapOf("recovery.db" to 100))
        Files.createSymbolicLink(File(inspection, "recovery.db-wal").toPath(), victim.toPath())
        inspection.setLastModified(old)

        sweepDirectories()

        assertTrue("the link's target is outside the app's storage", victim.exists())
        assertTrue(Files.isSymbolicLink(File(inspection, "recovery.db-wal").toPath()))
        assertTrue(File(inspection, "recovery.db").exists())
    }

    @Test fun aTemporaryDirectoryThatIsItselfALinkIsNeverEntered() {
        val elsewhere = File(root, "elsewhere").apply { mkdirs() }
        val victim = file(elsewhere, "recovery.db")
        elsewhere.setLastModified(old)
        val link = File(cacheDir, "database-compatibility-6").toPath()
        Files.createSymbolicLink(link, elsewhere.toPath())

        sweepDirectories()

        assertTrue(victim.exists())
        assertTrue(Files.isSymbolicLink(link))
    }

    @Test fun directoryNamesNoWriterProducesAreNeverTouched() {
        // Each stranger holds exactly what the writer it imitates would put inside, so only its name
        // can refuse it.
        val captureShaped = listOf(
            "companion-capture-abc-0",
            "companion-capture-1-8",
            "companion-capture-1",
            "xcompanion-capture-1-0",
            "profile-restore-plan-1",
        ).map { companionCapture(it) }
        val inspectionShaped = listOf(
            "database-compatibility-",
            "database-compatibility-1a",
            "WebView",
        ).map { tree(it, mapOf("recovery.db" to 10)) }
        val nested = tree("nested/database-compatibility-1", mapOf("recovery.db" to 10))
        val strangers = captureShaped + inspectionShaped

        sweepDirectories()

        (strangers + nested).forEach { assertTrue("${it.name} is not provably this app's scratch", it.exists()) }
    }

    @Test fun aScratchNameThatIsAFileIsLeftToTheFileRules() {
        val plain = file(cacheDir, "companion-capture-1-0")

        sweepDirectories()

        assertTrue(plain.exists())
    }

    // ---- Guard DB claim temporaries and staged candidates ----

    @Test fun anOrphanedGuardDbClaimTemporaryIsRemovedAndMeasured() {
        val deadClaims = listOf(
            file(filesDir, ".guard-db-candidate-a.pending", bytes = 7_000),
            file(filesDir, ".guard-db-candidate-b.pending", bytes = 9_000),
        )

        val result = sweeper().sweep(guardRule())

        deadClaims.forEach { assertFalse("${it.name} is a claim a dead process abandoned", it.exists()) }
        assertEquals(DisposableDataClass.GUARD_DB_CANDIDATES, result.dataClass)
        assertEquals(2, result.filesDeleted)
        assertEquals(16_000L, result.bytesFreed)
    }

    @Test fun aGuardDbClaimInFlightIsKept() {
        val claiming = file(filesDir, ".guard-db-candidate-a.pending", modified = processStart + 1_000L)

        val result = sweeper(at = processStart + 3L * hour).sweep(guardRule())

        assertTrue(claiming.exists())
        assertEquals(1, result.filesRetained)
    }

    @Test fun aStagedPairIsNeverAdmittedWithoutItsUnarmableProofHoweverOld() {
        // Staging is process-independent by design: age alone never proves a pair abandoned, and a rule
        // set given no proof admits no pair.
        val staged = listOf(
            file(filesDir, "guard-db-candidate-a.apk", modified = old - 72L * hour),
            file(filesDir, "guard-db-candidate-b.apk", modified = old - 72L * hour),
            file(filesDir, "guard-db-candidate-c.pending", modified = old - 72L * hour),
            file(filesDir, ".guard-db-candidate-a.pending.bak", modified = old - 72L * hour),
        )

        appOwnedDisposableFileRules(cacheDir, filesDir) { true }.forEach { sweeper().sweep(it) }
        sweepDirectories()

        staged.forEach { assertTrue("${it.name} is not a dead claim", it.exists()) }
    }

    @Test fun aClaimTemporaryTheStagingsOwnFileProofRefusesIsKept() {
        val foreign = file(filesDir, ".guard-db-candidate-a.pending")
        val unjudged = file(filesDir, ".guard-db-candidate-b.pending")

        sweeper().sweep(guardRule { candidate ->
            check(candidate.name != unjudged.name) { "stat failed" }
            false
        })

        assertTrue("wrong owner, mode or link count", foreign.exists())
        assertTrue("a proof that cannot decide keeps the file", unjudged.exists())
    }

    // ---- Guard DB staged pair: removed only once no ARM can use it ----

    private val stagedA = ByteArray(8_000) { 1 }
    private val stagedB = ByteArray(9_000) { 2 }

    private fun stage(name: String, bytes: ByteArray, modified: Long = old) =
        File(filesDir, name).apply { writeBytes(bytes); setLastModified(modified) }

    private fun installed(bytes: ByteArray) = File(root, "base.apk").apply { writeBytes(bytes) }

    /** The production rule set, admitting the pair through the staging's own [GuardDbAppStaging.unarmable]. */
    private fun rulesWithPairProof(
        installedApk: File,
        sessionRecord: Boolean = false,
        owned: (File) -> Boolean = { it.isFile },
    ): List<DisposableFileRule> {
        val staging = GuardDbAppStaging(
            filesDir,
            inspect = { null },
            validateFile = { it.isFile },
            sessionRecordPresent = { sessionRecord },
        )
        return appOwnedDisposableFileRules(cacheDir, filesDir, { staging.unarmable(installedApk) }, owned)
    }

    @Test fun anUnarmablePairIsRemovedAndReachesTheRemediationSummary() {
        val pair = listOf(stage("guard-db-candidate-a.apk", stagedA), stage("guard-db-candidate-b.apk", stagedB))
        val rules = rulesWithPairProof(installed(ByteArray(3_000) { 3 }))
        val sweeper = sweeper()

        val summary = runBlocking { ladder { rules.map(sweeper::sweep) }.run(pressured) }!!

        pair.forEach { assertFalse("${it.name} can never be armed", it.exists()) }
        assertEquals(2, summary.filesDeleted)
        assertEquals(17_000L, summary.fileBytesFreed)
    }

    @Test fun aPairWhoseAIsStillTheInstalledApkIsKept() {
        val pair = listOf(stage("guard-db-candidate-a.apk", stagedA), stage("guard-db-candidate-b.apk", stagedB))

        rulesWithPairProof(installed(stagedA.copyOf())).forEach { sweeper().sweep(it) }

        pair.forEach { assertTrue("${it.name} may still be armed", it.exists()) }
    }

    @Test fun aPairASessionRecordReferencesIsKeptWhileBIsInstalled() {
        // Mid-canary B is the installed APK, so A differs from it; only the session record protects the pair.
        val pair = listOf(stage("guard-db-candidate-a.apk", stagedA), stage("guard-db-candidate-b.apk", stagedB))

        rulesWithPairProof(installed(stagedB.copyOf()), sessionRecord = true).forEach { sweeper().sweep(it) }

        pair.forEach { assertTrue("a live canary lost ${it.name}", it.exists()) }
    }

    @Test fun anUnarmablePairTheStagingsOwnFileProofRefusesIsKept() {
        val foreign = stage("guard-db-candidate-a.apk", stagedA)
        val b = stage("guard-db-candidate-b.apk", stagedB)

        rulesWithPairProof(installed(ByteArray(3_000) { 3 }), owned = { it != foreign })
            .forEach { sweeper().sweep(it) }

        assertTrue("wrong owner, mode or link count", foreign.exists())
        assertFalse(b.exists())
    }

    @Test fun aLoneBIsKeptBecauseItMayStillAwaitItsA() {
        val lone = stage("guard-db-candidate-b.apk", stagedB)

        rulesWithPairProof(installed(ByteArray(3_000) { 3 })).forEach { sweeper().sweep(it) }

        assertTrue(lone.exists())
    }

    @Test fun aCandidateStagedByThisProcessIsKeptEvenBesideAnUnarmableA() {
        val restaged = stage("guard-db-candidate-a.apk", stagedA, modified = processStart + 1_000L)
        stage("guard-db-candidate-b.apk", stagedB)

        rulesWithPairProof(installed(ByteArray(3_000) { 3 })).forEach { sweeper(at = processStart + 3L * hour).sweep(it) }

        assertTrue("a claim this process made may be mid-restaging", restaged.exists())
    }

    // ---- the clock ----

    @Test fun aFileWrittenAfterABackwardClockStepStillPostdatesThisProcess() {
        val elapsedAtStart = 50_000L
        val boundary = ProcessStartBoundary(processStart, elapsedAtStart)
        // Ten minutes in, the clock steps back two hours; the process then writes a prepared install
        // and holds it for three hours.
        val stepBack = 2L * hour
        val writtenAt = processStart + 10L * 60L * 1000L - stepBack
        val held = file(cacheDir, "hapaneld-prepared-1.apk", modified = writtenAt)
        val later = writtenAt + 3L * hour
        val laterElapsed = elapsedAtStart + 10L * 60L * 1000L + 3L * hour

        val start = boundary.observe(later, laterElapsed)
        val rule = appOwnedDisposableFileRules(cacheDir, filesDir) { true }.single { it.dataClass == DisposableDataClass.DOWNLOADS }
        sweeper(start = start, at = later).sweep(rule)

        assertEquals("the start moves into the clock's older epoch", processStart - stepBack, start)
        assertTrue("a file this process holds is never its own orphan", held.exists())
    }

    @Test fun aBackwardStepStaysRememberedAfterTheClockIsCorrectedAgain() {
        val boundary = ProcessStartBoundary(processStart, 0L)
        boundary.observe(processStart + 60_000L - 2L * hour, 60_000L)

        val start = boundary.observe(processStart + 5L * hour, 5L * hour)

        assertEquals(processStart - 2L * hour, start)
    }

    @Test fun aForwardStepKeepsTheCapturedStart() {
        val boundary = ProcessStartBoundary(1_000_000L, 0L)

        assertEquals(
            "files written before an NTP jump forward still postdate the process",
            1_000_000L,
            boundary.observe(now, 60_000L),
        )
    }

    // ---- reporting ----

    private val pressured = StorageHealthSnapshot.UNCHECKED.copy(
        severity = StorageHealthSeverity.CRITICAL,
        pressureSeverity = StorageHealthSeverity.CRITICAL,
        checkedAtMillis = 1L,
        usableBytes = 1L,
        totalBytes = 1L shl 33,
    )

    private fun ladder(sweep: () -> List<DisposableSweepResult>) = StorageRemediationLadder(
        object : StorageRemediationOperations {
            override fun sweepDisposableFiles() = sweep()
            override fun enforceRetention() = RetentionResult.NOT_RUN
            override fun checkpointWal() = WalCheckpointOutcome(WalCheckpointResult.NOT_NEEDED, 0L, 0L)
            override fun writeVerifiedBackup() = false
            override fun vacuum() = VacuumOutcome(VacuumResult.NOT_RUN)
            override fun lifecycleOwned() = true
            override suspend fun observe(): StorageHealthSnapshot? = pressured
        },
        nowMillis = { now },
    )

    @Test fun theRemediationSummaryReportsExactlyWhatTheSweepRemoved() {
        companionCapture()
        file(filesDir, ".guard-db-candidate-a.pending", bytes = 5_000)
        val live = companionCapture("companion-capture-99-1", modified = processStart + 1_000L)
        val staged = file(filesDir, "guard-db-candidate-a.apk", bytes = 8_000)
        val sweeper = sweeper()

        val summary = runBlocking {
            ladder {
                appOwnedDisposableFileRules(cacheDir, filesDir) { true }.map(sweeper::sweep) +
                    appOwnedDisposableDirectoryRules(cacheDir).map(sweeper::sweep)
            }.run(pressured)
        }!!

        assertEquals("five capture files and one dead claim", 6, summary.filesDeleted)
        assertEquals(1_320L + 5_000L, summary.fileBytesFreed)
        assertEquals(summary.fileBytesFreed, summary.bytesFreed)
        assertTrue(live.exists())
        assertTrue(staged.exists())
    }
}
