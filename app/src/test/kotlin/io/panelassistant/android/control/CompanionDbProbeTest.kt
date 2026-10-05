package io.panelassistant.android.control

import io.panelassistant.android.platform.RootShell
import io.panelassistant.android.util.CompanionInstaller
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The Companion servers probe run through a real POSIX shell, with `sqlite3` present, failing, or
 * absent from PATH (Issue #24: a rooted panel whose firmware ships no `sqlite3`). The shell stands in
 * for su the way [Su.runOutput] reports it: stdout on exit 0, null otherwise.
 */
class CompanionDbProbeTest {
    private val pkg = CompanionInstaller.MINIMAL_PKG
    private val bin: File = Files.createTempDirectory("companion-probe").toFile()

    @After fun cleanUp() {
        bin.deleteRecursively()
    }

    private val shell = object : RootShell {
        override fun available() = true
        override fun run(cmd: String) = runOutput(cmd) != null
        override fun runBytes(cmd: String): ByteArray? = runOutput(cmd)?.toByteArray()
        override fun fireAndForget(cmd: String) = false
        override fun runOutput(cmd: String): String? {
            val p = ProcessBuilder("/bin/sh", "-c", cmd).apply { environment()["PATH"] = bin.path }.start()
            val out = p.inputStream.bufferedReader().readText()
            return if (p.waitFor() == 0) out else null
        }
    }

    private fun sqlite3(body: String) {
        File(bin, "sqlite3").apply { writeText("#!/bin/sh\n$body\n"); setExecutable(true) }
    }

    @Test fun missingSqlite3RaisesNoBanner() {
        val observed = CompanionDb.observeServers(pkg, shell)

        assertFalse(observed.probeSucceeded)
        assertNull(observed.preferredUrl)
        assertNull(CompanionDb.warning(pkg, observed, directSuReady = true))
    }

    @Test fun missingSqlite3IsItsOwnUnknownState() {
        val observed = CompanionDb.observeServers(pkg, shell)

        assertEquals(CompanionDb.ServerObservation.TOOL_ABSENT, observed)
        assertEquals(CompanionDb.Probe.TOOL_ABSENT, observed.probe)
        assertFalse(observed.status.needsRepair)
    }

    @Test fun failedReadStillRaisesTheProbeBanner() {
        sqlite3("exit 1")

        val observed = CompanionDb.observeServers(pkg, shell)

        assertEquals(CompanionDb.ServerObservation.UNKNOWN, observed)
        assertEquals(CompanionDb.Warning.ProbeFailed, CompanionDb.warning(pkg, observed, directSuReady = true))
    }

    @Test fun successfulReadReportsRowsAndNoProbeBanner() {
        sqlite3("echo '1\u001f\u001fhttps://ha.example.com/'\necho '2\u001fhttp://ha.local:8123\u001fhttps://ha.example.com'")

        val observed = CompanionDb.observeServers(pkg, shell)

        assertEquals(CompanionDb.Probe.SUCCEEDED, observed.probe)
        assertEquals("https://ha.example.com", observed.preferredUrl)
        assertEquals(CompanionDb.Warning.NeedsRepair(1), CompanionDb.warning(pkg, observed, directSuReady = true))
    }

    @Test fun successfulHealthyReadRaisesNoBanner() {
        sqlite3("echo '1\u001fhttp://ha.local:8123\u001fhttps://ha.example.com'")

        val observed = CompanionDb.observeServers(pkg, shell)

        assertTrue(observed.probeSucceeded)
        assertNull(CompanionDb.warning(pkg, observed, directSuReady = true))
    }

    @Test fun retainedPayloadKeepsTheToolAbsentState() {
        val healthy = CompanionDb.observeServers(listOf(
            CompanionDb.ServerRow("1", "http://ha.local:8123", "https://ha.example.com"),
        ))

        val absent = CompanionDb.retainLastKnownServerObservation(healthy, CompanionDb.ServerObservation.TOOL_ABSENT)
        val failed = CompanionDb.retainLastKnownServerObservation(healthy, CompanionDb.ServerObservation.UNKNOWN)

        assertEquals(CompanionDb.Probe.TOOL_ABSENT, absent.probe)
        assertEquals("http://ha.local:8123", absent.preferredUrl)
        assertNull(CompanionDb.warning(pkg, absent, directSuReady = true))
        assertEquals(CompanionDb.Warning.ProbeFailed, CompanionDb.warning(pkg, failed, directSuReady = true))
    }
}
