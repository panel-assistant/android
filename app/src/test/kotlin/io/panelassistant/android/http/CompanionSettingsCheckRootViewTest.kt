package io.panelassistant.android.http

import android.content.ContextWrapper
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.test.mock.MockPackageManager
import io.panelassistant.android.PanelStatus
import io.panelassistant.android.control.CompanionDb
import io.panelassistant.android.control.DensityController
import io.panelassistant.android.i18n.CatalogueLoader
import io.panelassistant.android.platform.RootShell
import io.panelassistant.android.testsupport.scratchDirectory
import io.panelassistant.android.util.CompanionInstaller
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.sql.DriverManager
import sun.misc.Unsafe

/**
 * The Companion settings check on Android 11 and later (Issue #24). The app's root shell inherits the
 * app's mount namespace, where app data isolation leaves only the app's own folder under `/data/data`;
 * the Companion folder is still reachable through `/data_mirror/data_ce/null/0`. A real POSIX shell
 * stands in for that root session, with every absolute data path mapped into a scratch view that holds
 * the Companion database only under the mirror. The check runs through the management observation
 * the status, dashboard banner and diagnostics read, and the banner is rendered as the panel shows it.
 */
class CompanionSettingsCheckRootViewTest {
    private val pkg = CompanionInstaller.MINIMAL_PKG
    private val scratch: File = scratchDirectory("companion-root-view").toFile()
    private val view = File(scratch, "view")
    private val bin = File(scratch, "bin").apply { mkdirs() }
    private val scope = CoroutineScope(SupervisorJob())

    @After fun cleanUp() {
        scope.cancel()
        scratch.deleteRecursively()
    }

    /** The app's root session: what it can see is [view]; `/data/data/<companion>` is not in it. */
    private val appRootSession = object : RootShell {
        override fun available() = true
        override fun run(cmd: String) = runOutput(cmd) != null
        override fun runBytes(cmd: String): ByteArray? = runOutput(cmd)?.toByteArray()
        override fun runOutput(cmd: String): String? {
            val mapped = cmd.replace("/data/", "${view.path}/data/").replace("/data_mirror/", "${view.path}/data_mirror/")
            val process = ProcessBuilder("/bin/sh", "-c", mapped).apply { environment()["PATH"] = bin.path }.start()
            val out = process.inputStream.bufferedReader().readText()
            return if (process.waitFor() == 0) out else null
        }
    }

    /** Only the minimal Companion is installed. The mock jar's constructor is a stub, so allocate it. */
    private class CompanionOnly : MockPackageManager() {
        override fun getPackageInfo(packageName: String, flags: Int): PackageInfo =
            if (packageName == CompanionInstaller.MINIMAL_PKG) PackageInfo().also { it.packageName = packageName }
            else throw PackageManager.NameNotFoundException(packageName)
    }

    private val companionInstalled = object : ContextWrapper(null) {
        private val packages = unsafe.allocateInstance(CompanionOnly::class.java) as PackageManager
        override fun getPackageName(): String = "io.panelassistant.android"
        override fun getPackageManager(): PackageManager = packages
    }

    /** sqlite3 as Android's: rows for a database file that exists, "unable to open" otherwise. */
    private fun installSqlite3(row: String) {
        File(bin, "sqlite3").apply {
            writeText(
                "#!/bin/sh\n" +
                    "db=\${1#file:}; db=\${db%%\\?*}\n" +
                    "[ -f \"\$db\" ] || { echo \"Error: unable to open database \\\"\$1\\\": unable to open database file\" >&2; exit 1; }\n" +
                    "printf '%s\\n' '$row'\n",
            )
            setExecutable(true)
        }
    }

    private fun companionDatabaseOnlyUnderTheMirror() {
        File(view, "data/data/io.panelassistant.android").mkdirs()
        File(view, "data_mirror/data_ce/null/0/$pkg/databases").mkdirs()
        File(view, "data_mirror/data_ce/null/0/$pkg/databases/HomeAssistantDB").writeText("db")
    }

    private fun observations() = ManagementObservations(
        companionInstalled, DensityController(canSu = false),
        { error("Unexpected management probe") }, { _, _ -> error("Unexpected diagnostic probe") },
        scope, { false }, root = appRootSession,
    )

    private fun banner(observation: CompanionDb.ServerObservation): String {
        val fixture = PaneldServerHttpFixture()
        return fixture.use {
            it.values["dashboard_package"] = pkg
            adHocWarnings(
                config = it.config,
                // Source-text reason: load the shipped English catalogue as runtime input, so the banner is
                // the text the panel shows rather than a key.
                catalogueLoader = CatalogueLoader { name -> File("src/main/assets", name).readText() },
                directSuReady = true,
                densityControllable = false,
                radioStatus = { null },
                dashboardRecoveryState = { PanelStatus.DashboardRecoveryState.NONE },
                companion = observation,
                inlineRepair = false,
            )
        }
    }

    @Test fun theCheckReadsTheCompanionDatabaseTheAppsRootSessionCannotSeeUnderDataData() {
        companionDatabaseOnlyUnderTheMirror()
        installSqlite3("1\u001fhttp://ha.local:8123\u001fhttps://ha.example")

        val observed = observations().companionServerCache.get()

        assertEquals(CompanionDb.Probe.SUCCEEDED, observed.probe)
        assertEquals("http://ha.local:8123", observed.preferredUrl)
        assertNull(DiagReader.companionDbLine(observed))
        val banner = banner(observed)
        assertFalse("no 'could not be inspected' banner: $banner", "could not be inspected" in banner)
    }

    /** Android 10 and earlier, or a root shell outside the app's namespace: the ordinary path is used,
     *  even where a mirror directory also exists. */
    @Test fun aVisibleDataDirectoryIsReadDirectly() {
        File(view, "data_mirror/data_ce/null/0/$pkg").mkdirs()
        File(view, "data/data/$pkg/databases").mkdirs()
        File(view, "data/data/$pkg/databases/HomeAssistantDB").writeText("db")
        installSqlite3("1\u001f\u001fhttps://ha.example")

        val observed = observations().companionServerCache.get()

        assertEquals(CompanionDb.Probe.SUCCEEDED, observed.probe)
        assertEquals(CompanionDb.UrlStatus(needsRepair = true, affected = 1), observed.status)
        assertTrue("the repair banner, not the probe one", "could not be inspected" !in banner(observed))
    }

    @Test fun aCompanionDatabaseInNeitherViewStillReportsTheOrdinaryPath() {
        File(view, "data/data/io.panelassistant.android").mkdirs()
        installSqlite3("unused")

        val observed = observations().companionServerCache.get()

        assertEquals(CompanionDb.Probe.FAILED, observed.probe)
        val line = DiagReader.companionDbLine(observed).orEmpty()
        assertTrue(line, "/data/data/$pkg/databases/HomeAssistantDB" in line)
        assertTrue(line, "Error: unable to open database" in line)
        assertTrue(banner(observed), "could not be inspected" in banner(observed))
    }

    /** Keep the writer open: both the schema and committed rows exist only in WAL. */
    private fun withWalOnlyDatabase(row: String?, check: (File) -> Unit) {
        val db = File(view, "data_mirror/data_ce/null/0/$pkg/databases/HomeAssistantDB")
        db.parentFile.mkdirs()
        File(view, "data/local/tmp").mkdirs()
        for (tool in listOf("sqlite3", "mktemp", "cp", "cmp", "rm")) {
            val process = ProcessBuilder("/bin/sh", "-c", "command -v $tool").start()
            val path = process.inputStream.bufferedReader().readText().trim()
            check(process.waitFor() == 0 && path.isNotEmpty()) { "Required fixture tool: $tool" }
            if (tool == "sqlite3") {
                // Recent host shells escape control characters by default; Android's older shells
                // emit the raw unit separator used by the production query.
                val help = ProcessBuilder(path, "-help").redirectErrorStream(true).start()
                val flags = if ("-escape" in help.inputStream.bufferedReader().readText()) "-escape off " else ""
                help.waitFor()
                File(bin, tool).apply {
                    writeText("#!/bin/sh\nexec '$path' $flags\"$@\"\n")
                    setExecutable(true)
                }
            } else {
                Files.createSymbolicLink(File(bin, tool).toPath(), File(path).toPath())
            }
        }
        DriverManager.getConnection("jdbc:sqlite:${db.path}").use { writer ->
            writer.createStatement().use { sql ->
                sql.execute("PRAGMA journal_mode=WAL")
                sql.execute("PRAGMA wal_autocheckpoint=0")
                sql.execute("CREATE TABLE servers(id INTEGER PRIMARY KEY, internal_url TEXT, external_url TEXT)")
                if (row != null) sql.execute("INSERT INTO servers VALUES($row)")
            }
            assertTrue("fixture has committed WAL data", File("${db.path}-wal").length() > 0)
            val mainOnly = ProcessBuilder(File(bin, "sqlite3").path, "file:${db.path}?immutable=1", "SELECT count(*) FROM servers;")
                .redirectErrorStream(true).start()
            val failure = mainOnly.inputStream.bufferedReader().readText()
            assertEquals("main file has no servers table: $failure", 1, mainOnly.waitFor())
            assertTrue(failure, "no such table: servers" in failure)
            val before = sourceFingerprint(db)
            check(db)
            assertEquals("source DB, WAL and SHM bytes and ownership are unchanged", before, sourceFingerprint(db))
            assertTrue("private probe copies are removed", File(view, "data/local/tmp").listFiles().orEmpty().isEmpty())
        }
    }

    private fun sourceFingerprint(db: File): List<List<Any>> = listOf("", "-wal", "-shm").map { suffix ->
        val path = File("${db.path}$suffix").toPath()
        listOf(
            MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)).toList(),
            Files.getAttribute(path, "unix:uid"), Files.getAttribute(path, "unix:gid"),
            Files.getAttribute(path, "unix:mode"),
        )
    }

    @Test fun aFreshWalOnlyCompanionHasNoServersAndNoFailedCheckBanner() {
        withWalOnlyDatabase(null) {
            val observations = observations()
            repeat(2) {
                observations.companionServerCache.invalidate()
                val observed = observations.companionServerCache.get()
                assertEquals(CompanionDb.ServerObservation.EMPTY, observed)
                assertNull(DiagReader.companionDbLine(observed))
                assertFalse(banner(observed), "could not be inspected" in banner(observed))
            }
        }
    }

    @Test fun walOnlyServersSupplyTheUrlAndRepairWarning() {
        withWalOnlyDatabase("1, '', 'https://wal.example/'") {
            val observed = observations().companionServerCache.get()
            assertEquals(CompanionDb.Probe.SUCCEEDED, observed.probe)
            assertEquals("https://wal.example", observed.preferredUrl)
            assertEquals(CompanionDb.Warning.NeedsRepair(1), CompanionDb.warning(pkg, observed, true))
            assertNull(DiagReader.companionDbLine(observed))
        }
    }

    @Test fun anUnreadableWalCopyStillReportsAFailedCheckAndCleansUp() {
        withWalOnlyDatabase(null) {
            Files.delete(File(bin, "cp").toPath())
            File(bin, "cp").apply {
                writeText("#!/bin/sh\necho 'copy denied' >&2\nexit 1\n")
                setExecutable(true)
            }
            val observed = observations().companionServerCache.get()
            assertEquals(CompanionDb.Probe.FAILED, observed.probe)
            assertEquals(CompanionDb.Warning.ProbeFailed, CompanionDb.warning(pkg, observed, true))
            assertTrue(observed.failure.orEmpty(), "copy denied" in observed.failure.orEmpty())
        }
    }

    @Test fun aChangedWalCopyIsRejectedInsteadOfReportingNoServers() {
        withWalOnlyDatabase(null) {
            Files.delete(File(bin, "cp").toPath())
            File(bin, "cp").apply {
                writeText("#!/bin/sh\n/bin/cp \"$@\" || exit ${'$'}?\ncase \"$1\" in *-wal) echo changed >> \"$2\";; esac\n")
                setExecutable(true)
            }
            val observed = observations().companionServerCache.get()
            assertEquals(CompanionDb.Probe.FAILED, observed.probe)
            assertEquals(CompanionDb.Warning.ProbeFailed, CompanionDb.warning(pkg, observed, true))
        }
    }

    private companion object {
        val unsafe = Unsafe::class.java.getDeclaredField("theUnsafe").run {
            isAccessible = true
            get(null) as Unsafe
        }
    }
}
