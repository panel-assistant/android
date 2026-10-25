package io.panelassistant.android.http

import android.content.ContentResolver
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.res.Resources
import io.panelassistant.android.Config
import io.panelassistant.android.ServiceComponentOperations
import io.panelassistant.android.backup.CompanionRestore
import io.panelassistant.android.control.CompanionDataOperationGate
import io.panelassistant.android.control.CompanionDataOperationState
import io.panelassistant.android.control.FakeDaemon
import io.panelassistant.android.control.FakeRootShell
import io.panelassistant.android.control.FakeSystemEnv
import io.panelassistant.android.control.SystemController
import io.panelassistant.android.testsupport.scratchDirectory
import io.panelassistant.android.util.CompanionHelperProtocol
import io.panelassistant.android.util.CompanionInstaller
import io.panelassistant.android.util.CompanionOperationStatus
import io.panelassistant.android.util.DurableRecoveryMarker
import io.panelassistant.android.util.InstallProgress
import java.io.File
import java.lang.reflect.Proxy
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The Install tab's Companion URL repair, driven through the service lane that runs it, against a
 * Companion whose live database still holds commits only in its WAL. Each case injects one failure the
 * repair must survive (a stop that fails, a launch queued before the stop, a checkpoint that cannot
 * complete, a helper call that never answers) and checks what the panel keeps: a live DB/WAL set that
 * still opens with every committed row, no Companion launch while the repair holds the data, and a
 * result that says what happened.
 */
class CompanionUrlRepairTest {
    private val pkg = CompanionInstaller.MINIMAL_PKG
    private val launcher = "$pkg/.Launch"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val root = scratchDirectory("companion-url-repair").toFile()
    private val cache = root.resolve("cache").apply { mkdirs() }
    private val operationState = CompanionDataOperationState.forTest(
        DurableRecoveryMarker(root.resolve("state").apply { mkdirs() }.resolve("companion-data-operation.pending")),
    )
    private val env = FakeSystemEnv(installed = setOf(pkg), launchers = mapOf(pkg to launcher))
    private val daemon = FakeDaemon(
        replies = mapOf("START $launcher" to "OK"),
        onSend = { command ->
            if (command == "START $launcher") {
                assertFalse("recovery must release the launch gate first", CompanionDataOperationGate.blocks(pkg))
                assertFalse("recovery must clear the durable marker first", operationState.isPending())
            }
        },
    )
    private val system = SystemController(env, FakeRootShell(runResult = false), daemon, sdkInt = 27)
    private val config = companionDashboardConfig()
    private val device = CompanionDevice(root.resolve("data"), pkg)
    private val database = JdbcDatabaseWork()

    @Before fun aCompanionWithCommitsOnlyInItsWal() {
        device.seed()
    }

    @After fun releaseProcessState() {
        scope.cancel()
        // The gate is process-wide; a lease a failed case left behind must not leak into the next case.
        @Suppress("UNCHECKED_CAST")
        (CompanionDataOperationGate::class.java.getDeclaredField("active").apply { isAccessible = true }
            .get(CompanionDataOperationGate) as AtomicReference<String?>).set(null)
        InstallProgress.start("test cleanup")?.let { InstallProgress.finish(it, "idle") }
    }

    @Test fun aSuccessfulRepairChangesOnlyTheBlankInternalUrlAndKeepsEveryWalCommit() {
        val before = device.liveRows()

        val message = repairThroughService()

        assertEquals("1 blank internal URL(s) repaired", message)
        val after = device.liveRows()
        assertEquals(before.servers.filterKeys { it != 1L }, after.servers.filterKeys { it != 1L })
        assertEquals(before.servers.getValue(1).copy(internalUrl = "https://ha.example"), after.servers.getValue(1))
        assertEquals(before.sensors, after.sensors)
        assertEquals("token-1-refreshed", after.servers.getValue(1).refreshToken)
        assertEquals(listOf(CompanionRestore.DATABASE_FILE), device.restoredPaths)
        assertEquals("the helper keeps the captured Companion stopped until the restore", listOf(true), device.holds)
        awaitGateReleased()
        assertFalse(operationState.isPending())
        assertEquals("the completed restore already relaunched Companion", 0, launches())
        assertTrue("the repair leaves no capture or preparation files", cache.listFiles().orEmpty().isEmpty())
    }

    @Test fun aCommitMadeAfterTheCaptureIsKeptAndTheRepairChangesNothing() {
        // A launch from outside Panel Assistant starts the held Companion, and it saves before the restore.
        device.launchedFromOutside = { device.commit("INSERT INTO sensors VALUES ('light', 'on')") }

        val message = repairThroughService()

        assertEquals("the Companion's database changed during the repair; nothing was replaced", message)
        val after = device.liveRows()
        assertEquals("on", after.sensors["light"])
        assertEquals("80", after.sensors["battery"])
        assertEquals("", after.servers.getValue(1).internalUrl)
        assertEquals("token-1-refreshed", after.servers.getValue(1).refreshToken)
        awaitGateReleased()
        assertFalse(operationState.isPending())
        assertEquals("the helper relaunches a Companion whose restore it refused", 0, launches())
    }

    @Test fun aCompanionWithNoBlankInternalUrlIsLeftAsItWas() {
        device.seed(firstInternalUrl = "http://ha.lan")
        val live = device.fingerprint()

        val message = repairThroughService()

        assertEquals("nothing to repair — every server already has an internal URL", message)
        assertEquals(live, device.fingerprint())
        assertEquals(emptyList<String>(), device.restoredPaths)
        awaitGateReleased()
        assertFalse(operationState.isPending())
        assertEquals("the Companion held for the repair is relaunched once, after release", 1, launches())
    }

    @Test fun aRejectedRestoreRelaunchesTheHeldCompanionWithoutChangingItsData() {
        assertSafeRejection(CompanionHelperProtocol.RestoreResult.FAILED, "restore rejected before commit")
    }

    @Test fun anUnsubmittedRestoreRelaunchesTheHeldCompanionWithoutChangingItsData() {
        assertSafeRejection(CompanionHelperProtocol.RestoreResult.NOT_SUBMITTED, "Companion helper is unavailable")
    }

    @Test fun aRejectedRepairReturnsToAnotherConfiguredDashboardAfterRecoveringCompanion() {
        val dashboard = "org.example.dashboard"
        val dashboardLauncher = "$dashboard/.Launch"
        env.installed = env.installed + dashboard
        env.launchers = env.launchers + (dashboard to dashboardLauncher)

        assertSafeRejection(
            CompanionHelperProtocol.RestoreResult.FAILED,
            "restore rejected before commit",
            companionDashboardConfig(dashboard),
        )

        assertEquals("the configured surface is returned after Companion recovery", listOf(dashboardLauncher), env.directStarts)
        assertEquals(listOf("START $launcher", "START $dashboardLauncher"), daemon.sent.filter { it.startsWith("START ") })
    }

    @Test fun aRejectedRestoreDoesNotDuplicateAHelperLaunch() {
        // The capture protocol can report that the helper already relaunched Companion.
        device.captureRelaunched = true
        device.restoreReply = CompanionHelperProtocol.RestoreResult.FAILED
        val live = device.fingerprint()

        assertEquals("restore rejected before commit", repairThroughService())

        assertEquals(listOf(true), device.holds)
        assertEquals(listOf(CompanionRestore.DATABASE_FILE), device.restoredPaths)
        assertEquals(live, device.fingerprint())
        awaitGateReleased()
        assertFalse(operationState.isPending())
        assertEquals("the helper has already relaunched Companion", 0, launches())
    }

    @Test fun unsafeRestoreOutcomesKeepCustodyAndNeverRequestCompanionRecovery() {
        val dashboard = "org.example.dashboard"
        val dashboardLauncher = "$dashboard/.Launch"
        env.installed = env.installed + dashboard
        env.launchers = env.launchers + (dashboard to dashboardLauncher)
        val live = device.fingerprint()
        for (outcome in listOf(
            CompanionHelperProtocol.RestoreResult.INDETERMINATE,
            CompanionHelperProtocol.RestoreResult.BUSY,
            CompanionHelperProtocol.RestoreResult.ROLLBACK_FAILED,
            CompanionHelperProtocol.RestoreResult.ROLLBACK_FAILED_RELAUNCH_FAILED,
            CompanionHelperProtocol.RestoreResult.ROLLBACK_FAILED_RELAUNCH_SUPPRESSED,
        )) {
            device.restoreReply = outcome
            device.helperIdle = false
            val attempts = device.restoredPaths.size

            repairThroughService(companionDashboardConfig(dashboard))

            assertEquals("the restore path must be exercised for $outcome", attempts + 1, device.restoredPaths.size)
            assertEquals(live, device.fingerprint())
            assertTrue("$outcome retains the marker", operationState.isPending())
            assertTrue("$outcome retains the launch gate", CompanionDataOperationGate.blocks(pkg))
            system.launchHome(pkg)
            assertEquals("$outcome must suppress launch", 0, launches())
            device.helperIdle = true
            awaitGateReleased()
            // This return follows the recovery decision, so observe the whole asynchronous callback.
            await("the configured dashboard must return for $outcome") { env.directStarts.size == attempts + 1 }
            assertFalse(operationState.isPending())
            assertEquals("IDLE alone must not request recovery for $outcome", 0, launches())
        }
    }

    private fun assertSafeRejection(
        outcome: CompanionHelperProtocol.RestoreResult,
        detail: String,
        dashboardConfig: Config = config,
    ) {
        device.restoreReply = outcome
        device.competingLaunch = { system.launchHome(pkg) }
        val live = device.fingerprint()
        val rows = device.liveRows()

        assertEquals(detail, repairThroughService(dashboardConfig))

        assertEquals("DB, WAL and SHM remain byte-identical", live, device.fingerprint())
        assertEquals("committed WAL rows and the original URLs remain readable", rows, device.liveRows())
        assertEquals(listOf(true), device.holds)
        assertEquals(listOf(CompanionRestore.DATABASE_FILE), device.restoredPaths)
        assertEquals(
            listOf(
                Sample(gateHeld = true, markerArmed = true, launched = false),
                Sample(gateHeld = true, markerArmed = true, launched = false),
            ),
            device.samples,
        )
        awaitGateReleased()
        assertFalse(operationState.isPending())
        assertEquals("the held Companion is recovered exactly once", 1, launches())
        assertTrue("capture and preparation files are released", cache.listFiles().orEmpty().isEmpty())
    }

    @Test fun aStopThatFailsChangesNothingAndHoldsLaunchUntilTheHelperIsIdle() {
        device.stopFails = true
        device.helperIdle = false
        val live = device.fingerprint()

        val message = repairThroughService()

        assertEquals(live, device.fingerprint())
        assertTrue("a failed stop may leave the helper holding the data", CompanionDataOperationGate.blocks(pkg))
        system.launchHome(pkg)
        assertEquals(0, launches())
        device.helperIdle = true
        assertEquals(
            "Companion could not be captured and its relaunch was not confirmed; nothing was changed",
            message,
        )
        assertEquals(emptyList<String>(), device.restoredPaths)
        assertEquals(listOf(Sample(gateHeld = true, markerArmed = true, launched = false)), device.samples)
        awaitGateReleased()
        assertFalse(operationState.isPending())
        assertEquals("the Companion the helper left stopped is relaunched once, after release", 1, launches())
    }

    @Test fun aLaunchQueuedBeforeTheStopWaitsUntilTheRepairIsTerminal() {
        device.competingLaunch = { system.launchHome(pkg) }

        repairThroughService()

        assertEquals(
            listOf(
                Sample(gateHeld = true, markerArmed = true, launched = false),
                Sample(gateHeld = true, markerArmed = true, launched = false),
            ),
            device.samples,
        )
        awaitGateReleased()
        assertEquals(0, launches())
        system.launchHome(pkg)
        assertEquals("the same launch is admitted once the repair has settled", 1, launches())
    }

    @Test fun aCheckpointThatCannotCompleteChangesNothing() {
        database.pinReaderDuringCheckpoint = true
        val live = device.fingerprint()

        val message = repairThroughService()

        assertEquals("Companion database could not be checkpointed safely; nothing was changed", message)
        assertEquals(live, device.fingerprint())
        assertEquals(emptyList<String>(), device.restoredPaths)
        assertEquals(1, database.checkpoints)
        awaitGateReleased()
        assertFalse(operationState.isPending())
        assertTrue(cache.listFiles().orEmpty().isEmpty())
    }

    @Test fun aRestoreWhoseReplyNeverArrivesKeepsLaunchSuppressedUntilTheHelperIsIdle() {
        device.restoreReply = CompanionHelperProtocol.RestoreResult.INDETERMINATE
        device.helperIdle = false

        val message = repairThroughService()

        assertEquals("restore terminal status was indeterminate", message)
        assertTrue(operationState.isPending())
        assertTrue(CompanionDataOperationGate.blocks(pkg))
        system.launchHome(pkg)
        assertEquals(0, launches())
        device.helperIdle = true
        awaitGateReleased()
        assertFalse(operationState.isPending())
        system.launchHome(pkg)
        assertEquals(1, launches())
    }

    @Test fun aRestoreInterruptedAfterSubmissionLeavesTheMarkerArmed() {
        device.restoreThrows = true
        device.helperIdle = false

        val message = repairThroughService()

        assertEquals("error: helper socket thread died after submission", message)
        assertTrue(operationState.isPending())
        assertTrue(CompanionDataOperationGate.blocks(pkg))
        system.launchHome(pkg)
        assertEquals(0, launches())
    }

    /** The service caller, with this test's Companion behind the data custody the repair uses. */
    private fun repairThroughService(dashboardConfig: Config = config): String {
        val operations = CompanionBackupOperations(
            installedCompanionPackage = { pkg },
            cacheDir = cache,
            ensureCompanionHelper = { true },
            companionDataOperationState = operationState,
            scope = scope,
            config = dashboardConfig,
            system = system,
            helper = device,
            database = database,
            statusPollMs = 1L,
        )
        val service = ServiceComponentOperations(
            ContextWrapper(null),
            scope,
            { dashboardConfig },
            { error("no core") },
            { error("no profile") },
        )
        device.observe = { gate() }
        assertTrue(service.repairCompanionUrl(operations::repairInternalUrls))
        awaitLaneReleased()
        return InstallProgress.message
    }

    private fun gate() = Sample(
        gateHeld = CompanionDataOperationGate.blocks(pkg),
        markerArmed = operationState.isPending(),
        launched = run {
            val before = launches()
            device.competingLaunch()
            launches() > before
        },
    )

    private fun launches() = daemon.sent.count { it == "START $launcher" } + env.directStarts.count { it == launcher }

    private fun awaitLaneReleased() = await("the repair must release the lane") { !InstallProgress.running }

    private fun awaitGateReleased() = await("the repair must release launch suppression") {
        !CompanionDataOperationGate.blocks(pkg)
    }

    private fun await(reason: String, done: () -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (!done() && System.nanoTime() < deadline) Thread.sleep(5)
        assertTrue(reason, done())
    }

    private fun companionDashboardConfig(dashboardPackage: String = pkg): Config {
        val values = ConcurrentHashMap<String, Any>(mapOf("dashboard_package" to dashboardPackage))
        val prefs = Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getAll" -> values.toMap()
                "getString", "getInt", "getLong", "getFloat", "getBoolean", "getStringSet" ->
                    values[args!![0] as String] ?: args[1]
                "contains" -> values.containsKey(args!![0] as String)
                "registerOnSharedPreferenceChangeListener", "unregisterOnSharedPreferenceChangeListener" -> null
                "hashCode" -> System.identityHashCode(values)
                "equals" -> false
                "toString" -> "MemoryPreferences"
                else -> error("unexpected SharedPreferences call: ${method.name}")
            }
        } as SharedPreferences
        val constructor = Config::class.java.declaredConstructors.single {
            it.parameterTypes.contentEquals(
                arrayOf(
                    SharedPreferences::class.java,
                    ContentResolver::class.java,
                    SharedPreferences::class.java,
                    SharedPreferences::class.java,
                    Resources::class.java,
                ),
            )
        }
        constructor.isAccessible = true
        return constructor.newInstance(prefs, object : ContentResolver(null) {}, prefs, prefs, null) as Config
    }
}

/** What a launch attempt saw while the repair held the Companion's data. */
private data class Sample(val gateHeld: Boolean, val markerArmed: Boolean, val launched: Boolean)

private data class Server(val internalUrl: String?, val externalUrl: String?, val refreshToken: String?)

private data class Rows(val servers: Map<Long, Server>, val sensors: Map<String, String>)

/**
 * The Companion's app-private database as the root helper meets it: `HomeAssistantDB` with a WAL that
 * holds commits the main file does not, left behind by a Companion that was stopped mid-run. It answers
 * the helper's capture and restore verbs the way `helper/src/companion.c` does: a failed stop captures
 * nothing and launches nothing; a restore moves the live DB, WAL and SHM aside and installs the one
 * staged database.
 */
private class CompanionDevice(dataDir: File, private val pkg: String) : CompanionDataHelper {
    private val databases = dataDir.resolve("databases").apply { mkdirs() }
    private val db = databases.resolve("HomeAssistantDB")
    private val trio get() = listOf(db, File("${db.path}-wal"), File("${db.path}-shm"))
    var stopFails = false
    var captureRelaunched = false
    var restoreReply: CompanionHelperProtocol.RestoreResult? = null
    var restoreThrows = false
    /** What a launch from outside Panel Assistant does between the capture and the restore. */
    var launchedFromOutside: () -> Unit = {}
    val holds = mutableListOf<Boolean>()
    /** What the helper's STATUS verb answers: BUSY while it may still hold the data. */
    @Volatile var helperIdle = true
    var observe: () -> Sample = { error("no observer") }
    var competingLaunch: () -> Unit = {}
    val samples = mutableListOf<Sample>()
    val restoredPaths = mutableListOf<String>()
    private var captures = 0

    fun seed(firstInternalUrl: String = "") {
        trio.forEach(File::delete)
        val holding = db.parentFile!!.resolve("holding").apply { mkdirs() }
        DriverManager.getConnection("jdbc:sqlite:${db.path}").use { writer ->
            writer.exec("PRAGMA journal_mode=WAL")
            writer.exec("PRAGMA wal_autocheckpoint=0")
            writer.exec("CREATE TABLE servers(id INTEGER PRIMARY KEY, internal_url TEXT, external_url TEXT, refresh_token TEXT)")
            writer.exec("CREATE TABLE sensors(id TEXT PRIMARY KEY, state TEXT)")
            writer.exec("INSERT INTO servers VALUES (1, '$firstInternalUrl', 'https://ha.example', 'token-1')")
            writer.exec("PRAGMA wal_checkpoint(TRUNCATE)")
            // Committed by the Companion but never checkpointed: these live only in the WAL.
            writer.exec("UPDATE servers SET refresh_token='token-1-refreshed' WHERE id=1")
            writer.exec("INSERT INTO servers VALUES (2, 'http://ha.lan', 'https://other.example', 'token-2')")
            writer.exec("INSERT INTO sensors VALUES ('battery', '80')")
            // The Companion stops here: keep the files as they are on disk, before a clean close merges them.
            trio.filter(File::exists).forEach { it.copyTo(holding.resolve(it.name), overwrite = true) }
        }
        trio.forEach(File::delete)
        holding.listFiles()!!.forEach { it.copyTo(databases.resolve(it.name), overwrite = true) }
        holding.deleteRecursively()
        check(File("${db.path}-wal").length() > 0) { "the fixture must leave commits in the WAL" }
    }

    /** The Companion, running again, saves something of its own. */
    fun commit(sql: String) {
        DriverManager.getConnection("jdbc:sqlite:${db.path}").use { it.exec(sql) }
    }

    fun fingerprint(): Map<String, String> = trio.filter(File::exists).associate { it.name to sha256(it) }

    /** Read the live set from a copy, so reading never checkpoints the files under test. */
    fun liveRows(): Rows {
        val copy = db.parentFile!!.parentFile!!.resolve("read-${System.nanoTime()}").apply { mkdirs() }
        trio.filter(File::exists).forEach { it.copyTo(copy.resolve(it.name)) }
        return try {
            DriverManager.getConnection("jdbc:sqlite:${copy.resolve(db.name).path}").use { c ->
                assertEquals("ok", c.scalar("PRAGMA integrity_check"))
                Rows(
                    c.query("SELECT id, internal_url, external_url, refresh_token FROM servers") {
                        it.getLong(1) to Server(it.getString(2), it.getString(3), it.getString(4))
                    }.toMap(),
                    c.query("SELECT id, state FROM sensors") { it.getString(1) to it.getString(2) }.toMap(),
                )
            }
        } finally {
            copy.deleteRecursively()
        }
    }

    override fun backup(packageName: String, cacheDir: File, hold: Boolean): CompanionHelperProtocol.BackupResult {
        assertEquals(pkg, packageName)
        holds += hold
        samples += observe()
        if (stopFails) return CompanionHelperProtocol.BackupResult.Failed(relaunchFailed = true)
        val directory = cacheDir.resolve("${CompanionHelperProtocol.CAPTURE_DIRECTORY_PREFIX}${captures++}")
        val captured = directory.resolve("databases").apply { mkdirs() }
        val files = mapOf(
            CompanionRestore.DATABASE_FILE to db,
            CompanionHelperProtocol.DATABASE_WAL_FILE to File("${db.path}-wal"),
            CompanionHelperProtocol.DATABASE_SHM_FILE to File("${db.path}-shm"),
        ).filterValues { it.length() > 0 }.mapValues { (_, live) -> live.copyTo(captured.resolve(live.name)) }
        return CompanionHelperProtocol.BackupResult.Success(
            CompanionHelperProtocol.Capture(files, relaunched = !hold || captureRelaunched, directory),
        )
    }

    override fun restore(
        packageName: String,
        files: Map<String, File>,
        expected: CompanionHelperProtocol.LiveDigest?,
    ): CompanionHelperProtocol.RestoreResult {
        assertEquals(pkg, packageName)
        launchedFromOutside()
        samples += observe()
        restoredPaths += files.keys
        if (restoreThrows) throw IllegalStateException("helper socket thread died after submission")
        restoreReply?.let { return it }
        if (stopFails) return CompanionHelperProtocol.RestoreResult.ROLLED_BACK_RELAUNCH_FAILED
        // As helper/src/companion.c: commit only over the live bytes the capture saw.
        val wal = File("${db.path}-wal").takeIf { it.length() > 0 }
        if (expected != null && expected != CompanionHelperProtocol.LiveDigest(sha256(db), wal?.let(::sha256))) {
            return CompanionHelperProtocol.RestoreResult.CHANGED
        }
        trio.forEach(File::delete)
        files.getValue(CompanionRestore.DATABASE_FILE).copyTo(db)
        return CompanionHelperProtocol.RestoreResult.COMMITTED
    }

    override fun status(): CompanionOperationStatus =
        if (helperIdle) CompanionOperationStatus.IDLE else CompanionOperationStatus.BUSY
}

/**
 * [CompanionDatabaseWork] on sqlite-jdbc, because the JVM suite has no Android SQLite: the same checks
 * `CompanionDatabasePreparation` makes (a complete, non-busy checkpoint into DELETE-journal mode; one
 * transaction that leaves no blank internal URL) over the same SQL.
 */
private class JdbcDatabaseWork : CompanionDatabaseWork {
    var pinReaderDuringCheckpoint = false
    var checkpoints = 0

    override fun checkpoint(database: File, wal: File?, shm: File?): Boolean {
        checkpoints++
        val reader = if (pinReaderDuringCheckpoint) {
            DriverManager.getConnection("jdbc:sqlite:${database.path}").apply {
                autoCommit = false
                scalar("SELECT count(*) FROM servers")
            }
        } else {
            null
        }
        try {
            val complete = DriverManager.getConnection("jdbc:sqlite:${database.path}").use { c ->
                if (c.scalar("PRAGMA quick_check(1)") != "ok") return false
                val (busy, log, done) = c.query("PRAGMA wal_checkpoint(TRUNCATE)") {
                    Triple(it.getLong(1), it.getLong(2), it.getLong(3))
                }.single()
                busy == 0L && log >= 0L && log == done &&
                    c.scalar("PRAGMA journal_mode=DELETE").equals("delete", ignoreCase = true)
            }
            if (!complete) return false
        } finally {
            reader?.close()
        }
        listOfNotNull(wal, shm).forEach { it.delete() }
        return listOf("-wal", "-shm", "-journal").none { File("${database.path}$it").exists() }
    }

    override fun prepare(plan: CompanionRestore.Plan, cacheDir: File): CompanionRestore.Preparation? {
        val source = plan.files.single { it.relativePath == CompanionRestore.DATABASE_FILE }
        val temporary = File.createTempFile("companion-prepare-", ".db", cacheDir)
        source.file.copyTo(temporary, overwrite = true)
        val repaired = DriverManager.getConnection("jdbc:sqlite:${temporary.path}").use { c ->
            if (!c.scalar("PRAGMA journal_mode=DELETE").equals("delete", ignoreCase = true)) return null
            c.autoCommit = false
            val count = c.createStatement().use {
                it.executeUpdate(io.panelassistant.android.control.CompanionDb.INTERNAL_URL_REPAIR_SQL)
            }
            if (c.scalar(io.panelassistant.android.control.CompanionDb.INTERNAL_URL_REPAIR_REMAINING_SQL) != "0") {
                c.rollback()
                return null
            }
            c.commit()
            count
        }
        return CompanionRestore.Preparation.withPreparedFiles(
            listOf(CompanionRestore.FilePayload(source.relativePath, temporary, deleteOnClose = false)),
            repaired,
            listOf(temporary),
        )
    }
}

private fun Connection.exec(sql: String) {
    createStatement().use { it.execute(sql) }
}

private fun Connection.scalar(sql: String): String? =
    createStatement().use { s -> s.executeQuery(sql).use { if (it.next()) it.getString(1) else null } }

private fun <T> Connection.query(sql: String, row: (java.sql.ResultSet) -> T): List<T> =
    createStatement().use { s ->
        s.executeQuery(sql).use { rs -> buildList { while (rs.next()) add(row(rs)) } }
    }

private fun sha256(file: File): String =
    MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
