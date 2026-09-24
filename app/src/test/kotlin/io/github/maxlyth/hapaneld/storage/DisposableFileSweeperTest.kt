package io.github.maxlyth.hapaneld.storage

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Each disposable file class removes only files it can prove are this app's (the exact name its
 * writer produces, directly inside the app-private directory it writes to) and that no live operation
 * can still hold (written before this process started, and at least an hour old).
 */
class DisposableFileSweeperTest {
    private lateinit var root: File
    private lateinit var cacheDir: File
    private lateinit var filesDir: File

    private val now = 1_800_000_000_000L
    private val processStart = now - 10L * 60L * 1000L
    private val old = processStart - 2L * DISPOSABLE_ORPHAN_MINIMUM_AGE_MS

    @Before fun createDirectories() {
        root = Files.createTempDirectory("disposable-sweep").toFile()
        cacheDir = File(root, "cache").apply { mkdirs() }
        filesDir = File(root, "files").apply { mkdirs() }
    }

    @After fun removeDirectories() {
        root.deleteRecursively()
    }

    private fun sweeper() = DisposableFileSweeper(processStart, nowMillis = { now })

    private fun rule(dataClass: DisposableDataClass) =
        appOwnedDisposableFileRules(cacheDir, filesDir).single { it.dataClass == dataClass }

    private fun file(directory: File, name: String, modified: Long = old, bytes: Int = 100): File =
        File(directory, name).apply {
            parentFile.mkdirs()
            writeBytes(ByteArray(bytes))
            setLastModified(modified)
        }

    @Test fun downloadsRemovesOrphanedAppDownloadsAndMeasuresTheBytes() {
        val orphans = listOf(
            file(cacheDir, "hapaneld-dl-123.apk", bytes = 1_000),
            file(cacheDir, "hapaneld-prepared-456.apk", bytes = 2_000),
            file(cacheDir, "apk-upload-789.apk", bytes = 3_000),
            file(cacheDir, "hapaneld-helper-42.bin", bytes = 4_000),
        )

        val result = sweeper().sweep(rule(DisposableDataClass.DOWNLOADS))

        orphans.forEach { assertFalse("${it.name} is an orphaned app download", it.exists()) }
        assertEquals(4, result.filesDeleted)
        assertEquals(10_000L, result.bytesFreed)
        assertEquals(0, result.deleteFailures)
    }

    @Test fun downloadsKeepsAFileThisProcessMayStillHold() {
        // Written after this process started: a live download, upload or prepared install may own it.
        val live = file(cacheDir, "hapaneld-prepared-1.apk", modified = processStart + 1_000L)

        val result = sweeper().sweep(rule(DisposableDataClass.DOWNLOADS))

        assertTrue(live.exists())
        assertEquals(0, result.filesDeleted)
        assertEquals(1, result.filesRetained)
    }

    @Test fun anOrphanYoungerThanTheMinimumAgeIsKept() {
        val young = file(cacheDir, "hapaneld-dl-1.apk", modified = processStart - 1_000L)

        sweeper().sweep(rule(DisposableDataClass.DOWNLOADS))

        assertTrue("a backwards clock step must not make a fresh file look orphaned", young.exists())
    }

    @Test fun anUnknownModificationTimeIsKept() {
        val unknown = file(cacheDir, "hapaneld-dl-1.apk", modified = 0L)

        sweeper().sweep(rule(DisposableDataClass.DOWNLOADS))

        assertTrue(unknown.exists())
    }

    @Test fun namesNoWriterProducesAreNeverTouched() {
        val strangers = listOf(
            "hapaneld-dl-abc.apk",
            "hapaneld-dl-1.apk.bak",
            "xhapaneld-dl-1.apk",
            "hapaneld-dl-.apk",
            "other-app-1.apk",
            "ha-brand-icon.png",
            "ha-paneld-touch-click-v1.wav",
            "ha-paneld.db",
            "config-1.vault",
        ).map { file(cacheDir, it) }

        appOwnedDisposableFileRules(cacheDir, filesDir).forEach { sweeper().sweep(it) }

        strangers.forEach { assertTrue("${it.name} is not provably this app's disposable file", it.exists()) }
    }

    @Test fun cachesRemovesOrphanedTemporaryFilesOfEveryKnownWriter() {
        val orphans = listOf(
            "panel-backup-1.zip",
            "panel-backup-2.hpb",
            "panel-restore-3.upload",
            "panel-restore-4.plain",
            "entity-filter-backup-5.payload",
            "entity-overrides-restore-6.payload",
            "profile-backup-7.payload",
            "app-state-restore-8.payload",
            "companion-restore-9.payload",
            "companion-prepare-10.db",
            "companion-prepare-10.db-wal",
            "companion-prepare-10.db-shm",
            "database-recovery-11.db",
            "ha-paneld-audio-12.media",
        ).map { file(cacheDir, it) }

        val result = sweeper().sweep(rule(DisposableDataClass.CACHES))

        orphans.forEach { assertFalse("${it.name} is an orphaned app temporary", it.exists()) }
        assertEquals(orphans.size, result.filesDeleted)
    }

    @Test fun protectedDataIsOutsideEveryRuleEvenWhenOldAndPlausiblyNamed() {
        val protected = listOf(
            file(filesDir, "config-vault/config-1.vault"),
            file(filesDir, "config-revisions/1700000000000.json"),
            file(filesDir, "device-profiles/imported/panel.yaml"),
            file(filesDir, "guard-db-candidate-a.apk"),
            file(filesDir, ".guard-db-candidate-a.pending"),
            file(filesDir, "hapaneld-dl-1.apk"),
            file(root, "databases/ha-paneld.db"),
            file(root, "databases/ha-paneld.db.v11.superseded"),
            file(root, "databases/ha-paneld.db.v10.premigrate"),
            file(cacheDir, "WebView/Default/data_1"),
            file(cacheDir, "nested/hapaneld-dl-1.apk"),
        )

        appOwnedDisposableFileRules(cacheDir, filesDir).forEach { sweeper().sweep(it) }

        protected.forEach { assertTrue("${it.path} must never be swept", it.exists()) }
    }

    @Test fun aSymbolicLinkIsNeverFollowedOrDeleted() {
        val outside = file(root, "other-app/data.bin")
        val link = File(cacheDir, "hapaneld-dl-1.apk").toPath()
        Files.createSymbolicLink(link, outside.toPath())

        val result = sweeper().sweep(rule(DisposableDataClass.DOWNLOADS))

        assertTrue("the target another app owns survives", outside.exists())
        assertTrue("the link itself is not a regular file and is left alone", Files.isSymbolicLink(link))
        assertEquals(0, result.filesDeleted)
    }

    @Test fun aSweepDirectoryThatIsItselfALinkIsRefusedWhole() {
        val elsewhere = File(root, "elsewhere").apply { mkdirs() }
        val victim = file(elsewhere, "hapaneld-dl-1.apk")
        val linkedCache = File(root, "linked-cache")
        Files.createSymbolicLink(linkedCache.toPath(), elsewhere.toPath())

        val linkedRule = appOwnedDisposableFileRules(linkedCache, filesDir)
            .single { it.dataClass == DisposableDataClass.DOWNLOADS }
        sweeper().sweep(linkedRule)

        assertTrue(victim.exists())
    }

    @Test fun screenshotsKeepTheServedImageAndThePreviousOneAndRemoveOlderAndStagedLeftovers() {
        val directory = File(filesDir, SCREENSHOT_DIRECTORY)
        val current = "a".repeat(64)
        val previous = "b".repeat(64)
        val stale = "c".repeat(64)
        val currentImage = file(directory, "$current.png", modified = old - 3_000L)
        val previousImage = file(directory, "$previous.png", modified = old - 1_000L)
        val staleImage = file(directory, "$stale.png", modified = old - 2_000L)
        val stagedImage = file(directory, "$stale.png.new")
        val stagedPointer = file(directory, "current.new")
        File(directory, "current").writeText("$current\n")

        val result = sweeper().sweep(rule(DisposableDataClass.SCREENSHOTS))

        assertTrue("the image the pointer names is being served", currentImage.exists())
        assertTrue("the newest other image is the cache's previous one", previousImage.exists())
        assertTrue("the pointer itself is not disposable", File(directory, "current").exists())
        assertFalse(staleImage.exists())
        assertFalse(stagedImage.exists())
        assertFalse(stagedPointer.exists())
        assertEquals(3, result.filesDeleted)
    }

    @Test fun anUnreadableScreenshotPointerKeepsEveryScreenshot() {
        val directory = File(filesDir, SCREENSHOT_DIRECTORY)
        val images = listOf("a", "b", "c").map { file(directory, "${it.repeat(64)}.png") }
        File(directory, "current").writeText("not-an-id")

        val result = sweeper().sweep(rule(DisposableDataClass.SCREENSHOTS))

        images.forEach { assertTrue("with no trustworthy pointer nothing is known to be disposable", it.exists()) }
        assertEquals(0, result.filesDeleted)
    }

    @Test fun orphanProofIsStrict() {
        assertTrue(disposableFileOrphaned(old, processStart, now, DISPOSABLE_ORPHAN_MINIMUM_AGE_MS))
        assertFalse("written at process start", disposableFileOrphaned(processStart, processStart, now, 0L))
        assertFalse("unknown time", disposableFileOrphaned(0L, processStart, now, 0L))
        assertFalse("future time", disposableFileOrphaned(now + 1L, now + 2L, now, 0L))
        assertTrue(
            "exactly the minimum age qualifies",
            disposableFileOrphaned(now - 1_000L, processStartMillis = now, nowMillis = now, minimumAgeMs = 1_000L),
        )
        assertFalse(
            "one millisecond short",
            disposableFileOrphaned(now - 999L, processStartMillis = now, nowMillis = now, minimumAgeMs = 1_000L),
        )
    }
}
