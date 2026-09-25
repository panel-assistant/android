package io.github.maxlyth.hapaneld.storage

import io.github.maxlyth.hapaneld.dashboard.RECOVERY_INSPECTION_COPY
import io.github.maxlyth.hapaneld.dashboard.RECOVERY_INSPECTION_DIRECTORY_PREFIX
import io.github.maxlyth.hapaneld.util.CompanionHelperProtocol
import io.github.maxlyth.hapaneld.util.GuardDbMaintenanceProtocol
import io.github.maxlyth.hapaneld.util.guardDbCandidateName
import io.github.maxlyth.hapaneld.util.guardDbCandidatePendingName
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

/**
 * The classes of disposable data remediation may remove. Rotated logs are deliberately absent: the
 * app writes no log files (log capture is an in-memory ring and log shipping is UDP), so there is
 * nothing on disk for that class to own. History rows are pruned inside the database by the existing
 * retention rules, never by deleting files.
 */
enum class DisposableDataClass {
    DOWNLOADS,
    SCREENSHOTS,
    CACHES,
    /** Scratch directories a companion capture or an isolated recovery inspection left behind. */
    TEMPORARY_DIRECTORIES,
    /** A Guard DB candidate claim's temporary copy, and a staged candidate pair no ARM can use. */
    GUARD_DB_CANDIDATES,
}

/**
 * One class of app-owned disposable files: a directory only this app writes and a name rule that
 * proves a file there was created by a known writer. Only direct children that are regular files are
 * ever considered; directories and symbolic links are never followed, entered or deleted.
 *
 * [retain] names files that must survive even when they match, such as the screenshot the panel is
 * currently serving. It sees only the matching candidates and returns the names to keep.
 *
 * [ownedFile] is any further proof the writer itself applies to its file, such as the Guard DB
 * staging's exact owner, mode and link count; a file it refuses, or cannot judge, is kept.
 */
internal class DisposableFileRule(
    val dataClass: DisposableDataClass,
    val directory: File,
    val ownedName: Regex,
    val retain: (List<File>) -> Set<String> = { emptySet() },
    val ownedFile: (File) -> Boolean = { true },
)

/**
 * One class of app-owned temporary directories: each a direct child of [parent] named exactly as its
 * writer names it, holding only the relative paths ([ownedEntry], `/`-separated, directories
 * included) that writer creates. The directory and every entry must pass the same orphan proof as a
 * file, so a tree any live operation could still be writing is kept whole. A link, a special file,
 * an entry no writer creates or a tree deeper or larger than any writer makes keeps the tree whole;
 * nothing is ever followed out of it.
 */
internal class DisposableDirectoryRule(
    val dataClass: DisposableDataClass,
    val parent: File,
    val ownedName: Regex,
    val ownedEntry: Regex,
)

/** What one rule actually did, measured from the filesystem rather than inferred. */
data class DisposableSweepResult(
    val dataClass: DisposableDataClass,
    val filesDeleted: Int,
    val bytesFreed: Long,
    val filesRetained: Int,
    val deleteFailures: Int,
)

/**
 * Whether a matching file is provably orphaned: modified before this process started, so no live
 * in-process owner (a download, an upload, a prepared install, a backup stream) can still hold it,
 * and at least [minimumAgeMs] old, which leaves a margin for a process that has only just died.
 * [processStartMillis] is [ProcessStartWallClock.orphanBoundary], which already stands in the oldest
 * wall-clock epoch this process has run in, so a backwards clock step cannot make a file this process
 * wrote look older than the process. A zero or future modification time is unknown and keeps the file.
 */
internal fun disposableFileOrphaned(
    lastModifiedMillis: Long,
    processStartMillis: Long,
    nowMillis: Long,
    minimumAgeMs: Long,
): Boolean = lastModifiedMillis > 0L &&
    lastModifiedMillis < processStartMillis &&
    nowMillis - lastModifiedMillis >= minimumAgeMs

/**
 * Deletes only files that both [DisposableFileRule.ownedName] proves are this app's and
 * [disposableFileOrphaned] proves no live operation holds. Bytes are counted only for files that are
 * gone afterwards, so a failed delete can never be reported as reclaimed space.
 */
internal class DisposableFileSweeper(
    private val processStartMillis: Long,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val minimumAgeMs: Long = DISPOSABLE_ORPHAN_MINIMUM_AGE_MS,
) {
    fun sweep(rule: DisposableFileRule): DisposableSweepResult {
        val empty = DisposableSweepResult(rule.dataClass, 0, 0L, 0, 0)
        val directory = rule.directory.toPath()
        // A directory that is itself a link could point anywhere, including another app's data.
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) return empty
        val candidates = (rule.directory.listFiles() ?: return empty).filter { file ->
            rule.ownedName.matches(file.name) &&
                Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) &&
                runCatching { rule.ownedFile(file) }.getOrDefault(false)
        }
        val retained = runCatching { rule.retain(candidates) }.getOrNull()
            // A retention rule that cannot decide keeps everything rather than guessing.
            ?: return empty.copy(filesRetained = candidates.size)
        val now = nowMillis()
        var deleted = 0
        var freed = 0L
        var kept = 0
        var failures = 0
        candidates.forEach { file ->
            if (file.name in retained ||
                !disposableFileOrphaned(file.lastModified(), processStartMillis, now, minimumAgeMs)
            ) {
                kept++
                return@forEach
            }
            val bytes = file.length().coerceAtLeast(0L)
            val removed = runCatching { Files.deleteIfExists(file.toPath()) }.getOrDefault(false)
            if (removed && !Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                deleted++
                freed = storageRemediationSaturatingAdd(freed, bytes)
            } else {
                failures++
            }
        }
        return DisposableSweepResult(rule.dataClass, deleted, freed, kept, failures)
    }

    /**
     * Removes whole directory trees that [DisposableDirectoryRule] proves are this app's and every
     * entry of which [disposableFileOrphaned] proves no live operation holds. Counts are regular
     * files; bytes are counted only for files that are gone afterwards.
     */
    fun sweep(rule: DisposableDirectoryRule): DisposableSweepResult {
        val empty = DisposableSweepResult(rule.dataClass, 0, 0L, 0, 0)
        if (!Files.isDirectory(rule.parent.toPath(), LinkOption.NOFOLLOW_LINKS)) return empty
        val tops = (rule.parent.listFiles() ?: return empty).filter { top ->
            rule.ownedName.matches(top.name) && Files.isDirectory(top.toPath(), LinkOption.NOFOLLOW_LINKS)
        }
        val now = nowMillis()
        var deleted = 0
        var freed = 0L
        var kept = 0
        var failures = 0
        tops.forEach { top ->
            val tree = disposableTree(top)
            val provable = tree.complete &&
                tree.entries.all { rule.ownedEntry.matches(it.relative) } &&
                (tree.entries.map { it.file } + top).all { entry ->
                    disposableFileOrphaned(entry.lastModified(), processStartMillis, now, minimumAgeMs)
                }
            if (!provable) {
                kept += tree.entries.count { !it.directory }
                return@forEach
            }
            tree.entries.filterNot { it.directory }.forEach { entry ->
                val bytes = entry.file.length().coerceAtLeast(0L)
                val removed = runCatching { Files.deleteIfExists(entry.file.toPath()) }.getOrDefault(false)
                if (removed && !Files.exists(entry.file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                    deleted++
                    freed = storageRemediationSaturatingAdd(freed, bytes)
                } else {
                    failures++
                }
            }
            // Deepest first, then the tree itself; a directory still holding anything stays.
            (tree.entries.filter { it.directory }.sortedByDescending { it.relative.count { c -> c == '/' } }
                .map { it.file } + top).forEach { directory ->
                if (!runCatching { Files.deleteIfExists(directory.toPath()) }.getOrDefault(false)) failures++
            }
        }
        return DisposableSweepResult(rule.dataClass, deleted, freed, kept, failures)
    }
}

private class DisposableTreeEntry(val file: File, val relative: String, val directory: Boolean)

private class DisposableTree(val entries: List<DisposableTreeEntry>, val complete: Boolean)

/**
 * Every entry under [top], listed without following links. The listing is incomplete, and the tree
 * therefore unprovable, when an entry is neither a plain file nor a directory, a directory cannot be
 * listed, or the tree is deeper or larger than any writer makes one.
 */
private fun disposableTree(top: File): DisposableTree {
    val entries = mutableListOf<DisposableTreeEntry>()
    fun visit(directory: File, prefix: String, depth: Int): Boolean {
        val children = directory.listFiles() ?: return false
        children.forEach { child ->
            if (entries.size >= DISPOSABLE_TREE_MAXIMUM_ENTRIES) return false
            val relative = prefix + child.name
            val path = child.toPath()
            when {
                Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) -> {
                    entries += DisposableTreeEntry(child, relative, directory = true)
                    if (depth >= DISPOSABLE_TREE_MAXIMUM_DEPTH || !visit(child, "$relative/", depth + 1)) return false
                }
                Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) ->
                    entries += DisposableTreeEntry(child, relative, directory = false)
                else -> return false
            }
        }
        return true
    }
    val complete = runCatching { visit(top, "", 1) }.getOrDefault(false)
    return DisposableTree(entries, complete)
}

/**
 * Every disposable file class the app writes, each confined to one app-private directory and to the
 * exact names its writer produces (`File.createTempFile` adds only decimal digits). Configuration,
 * the config vault, revisions, imported profiles, database files and their restore or superseded
 * copies and the platform-owned WebView store are absent by construction: no rule names their
 * directory or their names.
 *
 * Every Guard DB file is admitted only through [guardDbFileOwned], the staging's own file proof. The
 * staged `guard-db-candidate-<role>.apk` pair is process-independent by design, so a process-start
 * proof alone cannot show it abandoned; it is admitted only once [guardDbPairUnarmable]
 * ([GuardDbAppStaging.unarmable]) proves no ARM can use it, evaluated at most once per sweep. The
 * default admits no pair.
 */
internal fun appOwnedDisposableFileRules(
    cacheDir: File,
    filesDir: File,
    guardDbPairUnarmable: () -> Boolean = { false },
    guardDbFileOwned: (File) -> Boolean,
): List<DisposableFileRule> = listOf(
    DisposableFileRule(
        DisposableDataClass.DOWNLOADS,
        cacheDir,
        Regex("""(hapaneld-dl-|hapaneld-prepared-|apk-upload-)[0-9]+\.apk|hapaneld-helper-[0-9]+\.bin"""),
    ),
    DisposableFileRule(
        DisposableDataClass.CACHES,
        cacheDir,
        Regex(
            """panel-backup-[0-9]+\.(zip|hpb)""" +
                """|panel-restore-[0-9]+\.(upload|plain)""" +
                """|(entity-filter|entity-overrides|profile|app-state)-(backup|restore)-[0-9]+\.payload""" +
                """|companion-restore-[0-9]+\.payload""" +
                """|companion-prepare-[0-9]+\.db(-wal|-shm|-journal)?""" +
                """|database-recovery-[0-9]+\.db""" +
                """|ha-paneld-audio-[0-9]+\.media""",
        ),
    ),
    DisposableFileRule(
        DisposableDataClass.SCREENSHOTS,
        File(filesDir, SCREENSHOT_DIRECTORY),
        Regex("""[0-9a-f]{64}\.png(\.new)?|current\.new"""),
        retain = { candidates -> retainedScreenshots(File(filesDir, SCREENSHOT_DIRECTORY), candidates) },
    ),
    DisposableFileRule(
        DisposableDataClass.GUARD_DB_CANDIDATES,
        filesDir,
        exactNames(GuardDbMaintenanceProtocol.Role.values().map(::guardDbCandidatePendingName)),
        ownedFile = guardDbFileOwned,
    ),
    DisposableFileRule(
        DisposableDataClass.GUARD_DB_CANDIDATES,
        filesDir,
        exactNames(GuardDbMaintenanceProtocol.Role.values().map(::guardDbCandidateName)),
        ownedFile = lazy(guardDbPairUnarmable).let { unarmable -> { file -> guardDbFileOwned(file) && unarmable.value } },
    ),
)

/**
 * The temporary directories app writers create and remove themselves, left behind only when the
 * process dies mid-operation: a companion data capture (`companion-capture-<nanoTime>-<attempt>`,
 * holding exactly the capture's paths) and an isolated recovery inspection
 * (`database-compatibility-<digits>`, holding one copy and SQLite's own companions of it).
 *
 * `profile-restore-plan-<nanoTime>` is deliberately absent: the bundled-only registry that names it
 * plans without writing, so no writer ever creates it
 * (`ProfileCatalogMigrationRestoreTest` "the bundled-only planner writes nothing").
 */
internal fun appOwnedDisposableDirectoryRules(cacheDir: File): List<DisposableDirectoryRule> = listOf(
    DisposableDirectoryRule(
        DisposableDataClass.TEMPORARY_DIRECTORIES,
        cacheDir,
        Regex(Regex.escape(CompanionHelperProtocol.CAPTURE_DIRECTORY_PREFIX) +
            "[0-9]+-[0-${CompanionHelperProtocol.CAPTURE_DIRECTORY_ATTEMPTS - 1}]"),
        exactNames(
            CompanionHelperProtocol.capturePaths.flatMap { path ->
                path.split('/').runningReduce { parent, child -> "$parent/$child" }
            }.distinct(),
        ),
    ),
    DisposableDirectoryRule(
        DisposableDataClass.TEMPORARY_DIRECTORIES,
        cacheDir,
        Regex(Regex.escape(RECOVERY_INSPECTION_DIRECTORY_PREFIX) + "[0-9]+"),
        exactNames(listOf("", "-wal", "-shm", "-journal").map { RECOVERY_INSPECTION_COPY + it }),
    ),
)

private fun exactNames(names: List<String>): Regex = Regex(names.joinToString("|") { Regex.escape(it) })

/**
 * The screenshot the panel serves (named by the `current` pointer) and the newest other image, which
 * the cache itself keeps as the previous one. An unreadable or malformed pointer throws, so the
 * sweeper keeps every screenshot rather than guessing which one is live.
 */
private fun retainedScreenshots(directory: File, candidates: List<File>): Set<String> {
    val pointer = File(directory, "current")
    val current = if (pointer.exists()) {
        pointer.readText().trim().also { require(it.matches(Regex("[0-9a-f]{64}"))) }
    } else {
        null
    }
    val images = candidates.filter { it.name.endsWith(".png") }
    val previous = images.filter { it.name != "$current.png" }.maxByOrNull { it.lastModified() }?.name
    return setOfNotNull(current?.let { "$it.png" }, previous)
}

/**
 * The wall-clock time this process started, in the oldest clock epoch the process has run in.
 *
 * Every observation recomputes the start from monotonic uptime ([nowWallMillis] minus the elapsed
 * time since start) and keeps the lowest value seen. A forward step (a panel with no real-time clock
 * booting before NTP) raises the recomputed start, so the captured one stays: files this process
 * wrote before the step still postdate it. A backward step of any size lowers it by exactly the
 * step, so files this process writes afterwards still postdate it too. A step that is undone again
 * before any observation sees it is the one case left, which is why the app also observes on every
 * `ACTION_TIME_CHANGED`.
 */
internal class ProcessStartBoundary(startWallMillis: Long, private val startElapsedMillis: Long) {
    private var lowest = startWallMillis

    @Synchronized
    fun observe(nowWallMillis: Long, nowElapsedMillis: Long): Long {
        lowest = minOf(lowest, nowWallMillis - (nowElapsedMillis - startElapsedMillis))
        return lowest
    }
}

/** The process's [ProcessStartBoundary], captured once as early as the process runs. */
object ProcessStartWallClock {
    @Volatile
    private var boundary: ProcessStartBoundary? = null

    @Synchronized
    fun capture(startWallMillis: Long, startElapsedMillis: Long) {
        if (boundary == null) boundary = ProcessStartBoundary(startWallMillis, startElapsedMillis)
    }

    /** Records the clock as it is now, so a backward step is remembered even after it is undone. */
    fun observe(nowWallMillis: Long, nowElapsedMillis: Long) {
        boundary?.observe(nowWallMillis, nowElapsedMillis)
    }

    /** Null until captured; with no known start, nothing is provably orphaned. */
    fun orphanBoundary(nowWallMillis: Long, nowElapsedMillis: Long): Long? =
        boundary?.observe(nowWallMillis, nowElapsedMillis)
}

internal const val SCREENSHOT_DIRECTORY = "panel-screenshots"
internal const val DISPOSABLE_ORPHAN_MINIMUM_AGE_MS = 60L * 60L * 1000L
private const val DISPOSABLE_TREE_MAXIMUM_DEPTH = 4
private const val DISPOSABLE_TREE_MAXIMUM_ENTRIES = 64

/** A WAL checkpoint's outcome, as it happened. */
enum class WalCheckpointResult {
    /** Remediation did not reach this step. */
    NOT_RUN,
    /** The WAL was already empty. */
    NOT_NEEDED,
    /** A latched database failure: no further writes are attempted. */
    SKIPPED_DATABASE_FAILURE,
    /** The filesystem could not absorb the backfill before the WAL is truncated. */
    REFUSED_HEADROOM,
    /** Shutdown or another owner of the data directory began before the checkpoint could run. */
    SKIPPED_LIFECYCLE,
    /** No completed observation after retention, so its admission facts were unknown. */
    SKIPPED_UNOBSERVED,
    /** Every frame was backfilled and the WAL file measurably shrank. */
    COMPLETED,
    /** A reader or writer held the WAL; nothing is wrong, and a later pass retries. */
    DEFERRED_BUSY,
    /** The checkpoint threw something other than contention, or returned no readable result. */
    FAILED,
}

data class WalCheckpointOutcome(
    val result: WalCheckpointResult,
    val walBytesBefore: Long,
    val walBytesAfter: Long,
)

/**
 * Whether a truncating checkpoint may run. Backfilling copies WAL frames into the main file before
 * the WAL is truncated, so the main file can grow by up to the WAL's size first; the filesystem must
 * hold that plus [marginBytes]. Unknown capacity refuses: the cost here is proportional to the WAL,
 * not bounded like a reclamation slice.
 */
internal fun walCheckpointAdmitted(usableBytes: Long?, walBytes: Long, marginBytes: Long): Boolean {
    if (usableBytes == null) return false
    return usableBytes >= storageRemediationSaturatingAdd(walBytes.coerceAtLeast(0L), marginBytes)
}

/**
 * Reads a `wal_checkpoint(TRUNCATE)` result row together with the measured WAL length.
 *
 * Measured against real SQLite (`FullVacuumSpaceReproTest`): a reader whose snapshot predates recent
 * writes yields `(1, N, 0)`, a reader with a current snapshot still yields `(1, N, N)` with the WAL
 * unchanged, and an uncontended truncation yields `(0, 0, 0)` with a zero-length WAL. Contention comes
 * back as a row, not a throw. So `busy=1` is deferred, never success and never a fault, and success is
 * judged by the file: only a WAL measured at zero bytes is [WalCheckpointResult.COMPLETED]. A clear
 * row over a WAL that is not empty means another connection wrote in between, which is contention too.
 * A missing or malformed row is a failure.
 */
internal fun interpretWalCheckpoint(
    busy: Long?,
    logFrames: Long?,
    checkpointedFrames: Long?,
    walBytesBefore: Long,
    walBytesAfter: Long,
): WalCheckpointOutcome {
    val result = when {
        busy == null || logFrames == null || checkpointedFrames == null -> WalCheckpointResult.FAILED
        busy != 0L -> WalCheckpointResult.DEFERRED_BUSY
        walBytesAfter == 0L -> WalCheckpointResult.COMPLETED
        else -> WalCheckpointResult.DEFERRED_BUSY
    }
    return WalCheckpointOutcome(result, walBytesBefore, walBytesAfter)
}

/** Why a full `VACUUM` did not run. Every refusal is reported as itself, never as success. */
enum class VacuumRefusal {
    /** Pressure is not elevated; a rebuild would buy nothing the panel needs. */
    NOT_NEEDED,
    /** Never at critical pressure: the rebuild needs space before it returns any. */
    CRITICAL_PRESSURE,
    DATABASE_FAILURE,
    /** Service shutdown, or another owner of the database file is active. */
    LIFECYCLE,
    /** Freelist pages come back through the bounded incremental path instead. */
    INCREMENTAL_AVAILABLE,
    AUTO_VACUUM_UNKNOWN,
    /** Too little of the file is free pages for a whole-database rewrite to be worth it. */
    NOT_WORTHWHILE,
    /** Rewriting this much would hold the write lock past sibling connections' busy budget. */
    TOO_LARGE,
    CAPACITY_UNKNOWN,
    INSUFFICIENT_HEADROOM,
    NO_VERIFIED_BACKUP,
}

/** The facts a full `VACUUM` decision is made from, all taken from one completed observation. */
internal data class VacuumCandidate(
    val severity: StorageHealthSeverity,
    val pressureSeverity: StorageHealthSeverity,
    /** Null when the filesystem capacity could not be measured. */
    val usableBytes: Long?,
    val mainDatabaseBytes: Long,
    val walBytes: Long,
    val pageSizeBytes: Long,
    val pageCount: Long,
    val freelistCount: Long,
    val autoVacuumMode: StorageAutoVacuumMode,
)

internal fun StorageHealthSnapshot.vacuumCandidate(): VacuumCandidate = VacuumCandidate(
    severity = severity,
    pressureSeverity = pressureSeverity,
    usableBytes = usableBytes.takeIf { totalBytes > 0L },
    mainDatabaseBytes = mainDatabaseBytes,
    walBytes = walBytes,
    pageSizeBytes = pageSizeBytes,
    pageCount = pageCount,
    freelistCount = freelistCount,
    autoVacuumMode = autoVacuumMode,
)

/**
 * Bounds for a full `VACUUM`.
 *
 * Measured against real SQLite in WAL mode with in-memory temp storage, which is how Android builds
 * its platform library (`FullVacuumSpaceReproTest`): the main file does not change during the
 * rebuild; the whole new image is appended to the WAL at `page_size + 24` bytes per page plus a
 * 32-byte header, and stays there until an uncontended truncating checkpoint. For a dense database
 * that growth slightly exceeds main + WAL, so the bar is [headroomMultiplier] × (main + WAL) plus
 * [headroomMarginBytes], which covers the worst case with about a whole database to spare. The
 * compacted copy is built in memory, bounded by the same live-byte ceiling.
 *
 * [maximumLiveBytes] also keeps the rewrite, which blocks other writers for its whole duration, well
 * inside the 12 s bounded-busy budget sibling connections retry within (Issue #91).
 */
internal data class FullVacuumPolicy(
    val minimumFreelistFraction: Double = 0.25,
    val minimumReclaimableBytes: Long = 8L * MIB,
    val maximumLiveBytes: Long = 24L * MIB,
    val headroomMultiplier: Long = 2L,
    val headroomMarginBytes: Long = 64L * MIB,
) {
    companion object {
        private const val MIB = 1024L * 1024L
        val DEFAULT = FullVacuumPolicy()
    }
}

/**
 * Every precondition for a full `VACUUM` except the backup, which is expensive enough to take only
 * once everything else admits. Null means none of these refuses.
 *
 * Unlike [reclamationAdmitted], unknown capacity refuses: a reclamation slice is bounded, a rebuild
 * of the whole database is not.
 */
internal fun fullVacuumPrecondition(
    candidate: VacuumCandidate,
    lifecycleOwned: Boolean,
    policy: FullVacuumPolicy = FullVacuumPolicy.DEFAULT,
): VacuumRefusal? {
    if (candidate.severity == StorageHealthSeverity.DATABASE_FAILURE) return VacuumRefusal.DATABASE_FAILURE
    when (candidate.pressureSeverity) {
        StorageHealthSeverity.CRITICAL -> return VacuumRefusal.CRITICAL_PRESSURE
        StorageHealthSeverity.WARNING -> Unit
        else -> return VacuumRefusal.NOT_NEEDED
    }
    if (!lifecycleOwned) return VacuumRefusal.LIFECYCLE
    when (candidate.autoVacuumMode) {
        StorageAutoVacuumMode.NONE -> Unit
        StorageAutoVacuumMode.INCREMENTAL,
        StorageAutoVacuumMode.FULL -> return VacuumRefusal.INCREMENTAL_AVAILABLE
        StorageAutoVacuumMode.UNKNOWN -> return VacuumRefusal.AUTO_VACUUM_UNKNOWN
    }
    val pageCount = candidate.pageCount.coerceAtLeast(0L)
    val freelist = candidate.freelistCount.coerceIn(0L, pageCount)
    val pageSize = candidate.pageSizeBytes.coerceAtLeast(0L)
    if (pageCount == 0L || pageSize == 0L) return VacuumRefusal.NOT_WORTHWHILE
    val reclaimableBytes = storageRemediationSaturatingMultiply(freelist, pageSize)
    if (freelist.toDouble() / pageCount.toDouble() < policy.minimumFreelistFraction ||
        reclaimableBytes < policy.minimumReclaimableBytes
    ) {
        return VacuumRefusal.NOT_WORTHWHILE
    }
    if (storageRemediationSaturatingMultiply(pageCount - freelist, pageSize) > policy.maximumLiveBytes) {
        return VacuumRefusal.TOO_LARGE
    }
    val usable = candidate.usableBytes ?: return VacuumRefusal.CAPACITY_UNKNOWN
    val footprint = storageRemediationSaturatingAdd(
        candidate.mainDatabaseBytes.coerceAtLeast(0L),
        candidate.walBytes.coerceAtLeast(0L),
    )
    val required = storageRemediationSaturatingAdd(
        storageRemediationSaturatingMultiply(footprint, policy.headroomMultiplier),
        policy.headroomMarginBytes,
    )
    if (usable < required) return VacuumRefusal.INSUFFICIENT_HEADROOM
    return null
}

/** The single gate a full `VACUUM` passes: every precondition, then a verified backup. */
internal fun fullVacuumAdmission(
    candidate: VacuumCandidate,
    lifecycleOwned: Boolean,
    backupVerified: Boolean,
    policy: FullVacuumPolicy = FullVacuumPolicy.DEFAULT,
): VacuumRefusal? = fullVacuumPrecondition(candidate, lifecycleOwned, policy)
    ?: VacuumRefusal.NO_VERIFIED_BACKUP.takeUnless { backupVerified }

/** What the full-`VACUUM` step did. */
enum class VacuumResult {
    NOT_RUN,
    REFUSED,
    COMPLETED,
    DEFERRED_BUSY,
    FAILED,
}

data class VacuumOutcome(
    val result: VacuumResult,
    val refusal: VacuumRefusal? = null,
    /**
     * The truncating checkpoint that follows a completed rebuild. Until it completes, the new image
     * is still in the WAL and the rebuild has returned nothing, so a deferred one defers the run.
     */
    val checkpoint: WalCheckpointResult = WalCheckpointResult.NOT_RUN,
)

/** What the retention step did. */
enum class RetentionResult {
    NOT_RUN,
    /** Retention ran and found nothing past its limits. */
    CONVERGED,
    /** Retention ran and removed rows past their limits. */
    PRUNED,
    SKIPPED_CRITICAL_PRESSURE,
    SKIPPED_DATABASE_FAILURE,
    SKIPPED_LIFECYCLE,
    FAILED,
}

/**
 * How a remediation run ended, judged only from a fresh observation taken after it.
 *
 * [EXHAUSTED] is the escalation: every safe step ran or was refused for a stated reason and pressure
 * remains. Nothing further is deleted to make the condition go away; the operator must act.
 */
enum class StorageRemediationVerdict {
    RELIEVED,
    DEFERRED,
    EXHAUSTED,
    UNVERIFIED,
}

/** One remediation run, as it happened. Process-local and path-free. */
data class StorageRemediationSummary(
    val ranAtMillis: Long,
    val verdict: StorageRemediationVerdict,
    val filesDeleted: Int,
    val fileBytesFreed: Long,
    val databaseBytesFreed: Long,
    val retention: RetentionResult,
    val walCheckpoint: WalCheckpointResult,
    val vacuum: VacuumOutcome,
    val finalPressure: StorageHealthSeverity,
) {
    val bytesFreed: Long get() = storageRemediationSaturatingAdd(fileBytesFreed, databaseBytesFreed)

    /** The operator must act: remediation ran everything safe and pressure remains. */
    val escalated: Boolean get() = verdict == StorageRemediationVerdict.EXHAUSTED
}

/** What the daily storage maintenance should do for one completed observation. */
internal enum class StorageMaintenancePlan {
    NONE,
    /** Converge existing retention limits even when no write path triggers them. */
    RETENTION,
    /** Pressure is elevated: run the remediation ladder. */
    REMEDIATE,
}

internal fun storageMaintenancePlan(snapshot: StorageHealthSnapshot): StorageMaintenancePlan =
    when (snapshot.pressureSeverity) {
        StorageHealthSeverity.WARNING,
        StorageHealthSeverity.CRITICAL -> StorageMaintenancePlan.REMEDIATE
        StorageHealthSeverity.HEALTHY ->
            if (snapshot.severity == StorageHealthSeverity.DATABASE_FAILURE) StorageMaintenancePlan.NONE
            else StorageMaintenancePlan.RETENTION
        else -> StorageMaintenancePlan.NONE
    }

/** The side effects the remediation ladder may use. Each reports what it did. */
internal interface StorageRemediationOperations {
    fun sweepDisposableFiles(): List<DisposableSweepResult>
    fun enforceRetention(): RetentionResult
    fun checkpointWal(): WalCheckpointOutcome
    /** Writes a fresh configuration backup and proves it reads back; false when it cannot. */
    fun writeVerifiedBackup(): Boolean
    fun vacuum(): VacuumOutcome
    fun lifecycleOwned(): Boolean
    /** A fresh completed observation, or null when none could be taken. */
    suspend fun observe(): StorageHealthSnapshot?
}

/**
 * The fail-safe remediation ladder, ordered by what each step costs before it returns anything:
 *
 * 1. Orphaned app-owned files, unless another owner holds the data directory. Deleting a file returns its bytes at once, so this is the only step
 *    that helps at critical pressure.
 * 2. Existing retention limits, at warning only. Deleting rows writes WAL frames before the freed
 *    pages come back through bounded incremental reclamation, so it is refused at critical.
 * 3. A truncating WAL checkpoint, admitted on a fresh observation after retention and a lifecycle
 *    read immediately before it, when the filesystem can absorb the backfill.
 * 4. A full `VACUUM`, only through [fullVacuumAdmission]: never at critical pressure, never without
 *    measured headroom, a worthwhile freelist that no bounded path can reach, a verified backup and
 *    lifecycle ownership.
 * 5. Escalation. When a fresh observation still shows pressure and nothing was merely deferred, the
 *    run reports [StorageRemediationVerdict.EXHAUSTED] and warning continues.
 *
 * Nothing here deletes configuration, the live database or evidence, and nothing is inferred: bytes
 * freed are measured, and the verdict comes only from an observation taken after the steps ran.
 */
internal class StorageRemediationLadder(
    private val operations: StorageRemediationOperations,
    private val walCheckpointMarginBytes: Long = WAL_CHECKPOINT_MARGIN_BYTES,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    suspend fun run(initial: StorageHealthSnapshot): StorageRemediationSummary? {
        if (storageMaintenancePlan(initial) != StorageMaintenancePlan.REMEDIATE) return null
        val databaseFailed = initial.severity == StorageHealthSeverity.DATABASE_FAILURE
        val critical = initial.pressureSeverity == StorageHealthSeverity.CRITICAL

        // A companion data backup or restore owns the data directory while it runs; so does shutdown.
        val ownedAtStart = operations.lifecycleOwned()
        val swept = if (ownedAtStart) operations.sweepDisposableFiles() else emptyList()

        val retention = when {
            databaseFailed -> RetentionResult.SKIPPED_DATABASE_FAILURE
            critical -> RetentionResult.SKIPPED_CRITICAL_PRESSURE
            !ownedAtStart || !operations.lifecycleOwned() -> RetentionResult.SKIPPED_LIFECYCLE
            else -> operations.enforceRetention()
        }

        // The checkpoint is admitted on current facts. Retention writes WAL frames, can shrink
        // headroom and can latch a database failure, so a run in which it ran re-observes first, and
        // an unobservable state refuses rather than reusing the facts retention may have changed.
        val retentionRan = retention == RetentionResult.PRUNED ||
            retention == RetentionResult.CONVERGED ||
            retention == RetentionResult.FAILED
        val facts = if (retentionRan) operations.observe() else initial
        val walBytes = (facts ?: initial).walBytes.coerceAtLeast(0L)
        val checkpoint = when {
            facts == null -> WalCheckpointOutcome(WalCheckpointResult.SKIPPED_UNOBSERVED, walBytes, walBytes)
            facts.severity == StorageHealthSeverity.DATABASE_FAILURE ->
                WalCheckpointOutcome(WalCheckpointResult.SKIPPED_DATABASE_FAILURE, walBytes, walBytes)
            walBytes == 0L -> WalCheckpointOutcome(WalCheckpointResult.NOT_NEEDED, 0L, 0L)
            !walCheckpointAdmitted(
                facts.usableBytes.takeIf { facts.totalBytes > 0L },
                walBytes,
                walCheckpointMarginBytes,
            ) -> WalCheckpointOutcome(WalCheckpointResult.REFUSED_HEADROOM, walBytes, walBytes)
            // Read again immediately before the write: shutdown or a companion data operation may
            // have begun while retention ran.
            !operations.lifecycleOwned() ->
                WalCheckpointOutcome(WalCheckpointResult.SKIPPED_LIFECYCLE, walBytes, walBytes)
            else -> operations.checkpointWal()
        }

        var latest = operations.observe()
        val vacuum = latest?.let { observed ->
            val candidate = observed.vacuumCandidate()
            fullVacuumPrecondition(candidate, operations.lifecycleOwned())?.let { refusal ->
                VacuumOutcome(VacuumResult.REFUSED, refusal)
            } ?: run {
                val backedUp = operations.writeVerifiedBackup()
                // The one gate: re-evaluated with the backup's real result and a fresh lifecycle read.
                fullVacuumAdmission(candidate, operations.lifecycleOwned(), backedUp)?.let { refusal ->
                    VacuumOutcome(VacuumResult.REFUSED, refusal)
                } ?: operations.vacuum().also { outcome ->
                    if (outcome.result != VacuumResult.REFUSED) latest = operations.observe()
                }
            }
        } ?: VacuumOutcome(VacuumResult.NOT_RUN)

        val final = latest
        val filesDeleted = swept.sumOf { it.filesDeleted }
        val fileBytes = swept.fold(0L) { total, result -> storageRemediationSaturatingAdd(total, result.bytesFreed) }
        val databaseBytes = final?.let {
            val before = storageRemediationSaturatingAdd(initial.mainDatabaseBytes, initial.walBytes)
            val after = storageRemediationSaturatingAdd(it.mainDatabaseBytes, it.walBytes)
            (before - after).coerceAtLeast(0L)
        } ?: 0L
        // Contention, a busy owner and freelist pages bounded reclamation has yet to return are not
        // exhaustion: a later run or maintenance pass can still do more. Deleted rows reach the
        // filesystem only through that reclamation, which is gated and capped per pass.
        val reclamationPending = final != null &&
            final.pressureSeverity == StorageHealthSeverity.WARNING &&
            final.autoVacuumMode == StorageAutoVacuumMode.INCREMENTAL &&
            final.freelistCount > FREELIST_RETAINED_PAGES
        val deferred = !ownedAtStart || reclamationPending ||
            retention == RetentionResult.SKIPPED_LIFECYCLE ||
            checkpoint.result == WalCheckpointResult.DEFERRED_BUSY ||
            checkpoint.result == WalCheckpointResult.SKIPPED_LIFECYCLE ||
            checkpoint.result == WalCheckpointResult.SKIPPED_UNOBSERVED ||
            vacuum.result == VacuumResult.DEFERRED_BUSY ||
            vacuum.refusal == VacuumRefusal.LIFECYCLE ||
            (vacuum.result == VacuumResult.COMPLETED && vacuum.checkpoint == WalCheckpointResult.DEFERRED_BUSY)
        val verdict = when {
            final == null -> StorageRemediationVerdict.UNVERIFIED
            final.pressureSeverity == StorageHealthSeverity.HEALTHY -> StorageRemediationVerdict.RELIEVED
            final.pressureSeverity != StorageHealthSeverity.WARNING &&
                final.pressureSeverity != StorageHealthSeverity.CRITICAL -> StorageRemediationVerdict.UNVERIFIED
            deferred -> StorageRemediationVerdict.DEFERRED
            else -> StorageRemediationVerdict.EXHAUSTED
        }
        return StorageRemediationSummary(
            ranAtMillis = nowMillis(),
            verdict = verdict,
            filesDeleted = filesDeleted,
            fileBytesFreed = fileBytes,
            databaseBytesFreed = databaseBytes,
            retention = retention,
            walCheckpoint = checkpoint.result,
            vacuum = vacuum,
            finalPressure = final?.pressureSeverity ?: initial.pressureSeverity,
        )
    }
}

/**
 * Checks that a configuration backup written just now reads back as exactly the rows it was written
 * from. A backup that cannot be decoded, is empty, or differs in any row is not a backup.
 */
internal fun <T> configurationBackupVerified(written: List<T>, readBack: List<T>?): Boolean =
    written.isNotEmpty() && readBack != null && readBack.size == written.size &&
        readBack.toSet() == written.toSet()

internal const val WAL_CHECKPOINT_MARGIN_BYTES = 16L * 1024L * 1024L

/**
 * Small freelist bounded reclamation leaves for ordinary page reuse; below it, reclamation is not
 * worth a lock. Pages above it on an INCREMENTAL database are returned by a later maintenance pass.
 */
internal const val FREELIST_RETAINED_PAGES = 512L

private fun storageRemediationSaturatingAdd(left: Long, right: Long): Long =
    if (right > 0L && left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right

private fun storageRemediationSaturatingMultiply(left: Long, right: Long): Long = when {
    left <= 0L || right <= 0L -> 0L
    left > Long.MAX_VALUE / right -> Long.MAX_VALUE
    else -> left * right
}
