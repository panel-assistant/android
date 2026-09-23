package io.github.maxlyth.hapaneld.control

import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CdpRelayStateTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun acceptedLaunchIsNotRunningWhenChildProbeFails() {
        val state = RelayProcessState(probe = { RelayExposureState.ABSENT })
        assertFalse(state.start { true })
        assertFalse(state.running())
    }

    @Test fun processDeathInvalidatesAnEarlierSuccessfulStart() {
        var alive = true
        val state = RelayProcessState(probe = {
            if (alive) RelayExposureState.PRESENT else RelayExposureState.ABSENT
        })
        assertTrue(state.start { true })
        assertTrue(state.running())
        alive = false
        assertFalse(state.running())
    }

    @Test fun failedTerminationCannotHideALiveOrphan() {
        val state = RelayProcessState({ RelayExposureState.PRESENT }, pause = {})
        assertTrue(state.start { true })
        assertFalse(state.stop { })
        assertTrue(state.running())
    }

    @Test fun successfulTerminationRequiresAVerifiedAbsentProbe() {
        var alive = true
        val state = RelayProcessState({
            if (alive) RelayExposureState.PRESENT else RelayExposureState.ABSENT
        }, pause = {})
        assertTrue(state.start { true })
        assertTrue(state.stop { alive = false })
        assertFalse(state.running())
    }

    @Test fun failedRootProbeFailsClosedForStopAndRunningState() {
        val state = RelayProcessState(probe = { RelayExposureState.UNKNOWN })
        assertFalse(state.stop { })
        assertTrue(state.running())
    }

    @Test fun truthfulNoListenerStatusCannotWeakenStrictStopVerification() {
        val state = RelayProcessState(
            probe = { RelayExposureState.ABSENT },
            stopProbe = { RelayExposureState.UNKNOWN },
        )
        assertFalse(state.running())
        assertFalse(state.stop { })
    }

    @Test fun `relay binary is atomically staged below a root-only directory`() {
        val command = CdpRelay.startCommand(
            "/data/user/0/io.github.maxlyth.hapaneld/files/cdprelay",
            "webview_devtools_remote_123",
        )
        assertTrue(command.contains("rm -rf /data/local/.hapaneld-cdp"))
        assertTrue(command.contains("mkdir -m 700 /data/local/.hapaneld-cdp"))
        assertTrue(command.contains("chown 0:0 /data/local/.hapaneld-cdp"))
        assertTrue(command.contains("chown 0:0 /data/local/.hapaneld-cdp/cdprelay.new"))
        assertTrue(command.contains("mv -f /data/local/.hapaneld-cdp/cdprelay.new /data/local/.hapaneld-cdp/cdprelay"))
        assertFalse(command.contains("cp /data/user/0/io.github.maxlyth.hapaneld/files/cdprelay /data/local/tmp/cdprelay"))
    }

    @Test fun `socket selection ignores another apps earlier WebView`() {
        val sockets = """
            000: 00000002 00000000 00010000 0001 01 1 @webview_devtools_remote_111
            000: 00000002 00000000 00010000 0001 01 2 @webview_devtools_remote_222
        """.trimIndent()
        assertEquals("webview_devtools_remote_222", CdpRelay.selectDevToolsSocket(sockets, setOf(222)))
    }

    @Test fun `socket selection fails closed when renderer owns multiple endpoints`() {
        val sockets = "@webview_devtools_remote_222\n@webview_devtools_remote_333"
        assertNull(CdpRelay.selectDevToolsSocket(sockets, setOf(222, 333)))
    }

    @Test fun `socket selection requires a renderer pid match`() {
        assertNull(CdpRelay.selectDevToolsSocket("@webview_devtools_remote_111", setOf(222)))
        assertNull(CdpRelay.selectDevToolsSocket("@webview_devtools_remote_111", emptySet()))
    }

    @Test fun `kernel process or listener evidence detects relay exposure`() {
        val empty = """
            process=0
            tcp_begin
              sl  local_address rem_address   st
               0: 0100007F:1F90 00000000:0000 0A
            tcp_end
        """.trimIndent()
        assertEquals(RelayExposureState.ABSENT, relayExposureState(empty))

        assertEquals(
            RelayExposureState.PRESENT,
            relayExposureState(empty.replace("process=0", "process=1")),
        )
        assertEquals(
            RelayExposureState.PRESENT,
            relayExposureState(empty.replace("0100007F:1F90", "00000000:2406")),
        )
        assertEquals(RelayExposureState.UNKNOWN, relayExposureState(null))
        assertEquals(RelayExposureState.UNKNOWN, relayExposureState("process=0\ntcp_begin\nbad\ntcp_end"))
    }

    @Test fun `process inventory uses shell reads and finds an orphan without a listener`() {
        val proc = procFixture()
        repeat(128) { pid -> File(proc, "${pid + 1}/comm").apply {
            parentFile.mkdirs()
            writeText("unrelated\n")
        } }
        File(proc, "999/comm").apply { parentFile.mkdirs(); writeText("cdprelay\n") }

        assertEquals(RelayExposureState.PRESENT, runProbe(proc))
    }

    @Test fun `complete process and listener inventory proves absence`() {
        val proc = procFixture()
        File(proc, "1/comm").apply { parentFile.mkdirs(); writeText("cdprelay-other\n") }
        // An entry that disappeared before its comm could be opened is not a surviving process.
        File(proc, "2").mkdir()

        assertEquals(RelayExposureState.ABSENT, runProbe(proc))
    }

    @Test fun `process inventory still checks both IPv4 and IPv6 listeners`() {
        for (table in listOf("tcp", "tcp6")) {
            val proc = procFixture()
            File(proc, "net/$table").appendText("0: 00000000:2406 00000000:0000 0A\n")

            assertEquals(table, RelayExposureState.PRESENT, runProbe(proc))
        }
    }

    @Test fun `failed live process read cannot prove absence`() {
        val proc = procFixture()
        File(proc, "1/comm").apply { parentFile.mkdirs(); writeText("") }

        assertEquals(RelayExposureState.UNKNOWN, runProbe(proc))
    }

    @Test fun `missing or unreadable listener table cannot prove absence`() {
        val missing = procFixture()
        assertTrue(File(missing, "net/tcp").delete())
        assertEquals(RelayExposureState.UNKNOWN, runProbe(missing))

        val unreadable = procFixture()
        // A directory fails cat even when this test runs as root, unlike permission-bit fixtures.
        assertTrue(File(unreadable, "net/tcp6").delete())
        assertTrue(File(unreadable, "net/tcp6").mkdir())
        assertEquals(RelayExposureState.UNKNOWN, runProbe(unreadable))
    }

    private fun procFixture(): File = temporary.newFolder().also { proc ->
        File(proc, "net").mkdir()
        for (table in listOf("tcp", "tcp6")) {
            File(proc, "net/$table").writeText("  sl  local_address rem_address   st\n")
        }
    }

    private fun runProbe(proc: File): RelayExposureState {
        // Run the production command unchanged apart from its proc mount. Reject external per-process
        // reads so a return to one cat invocation per pid fails independently of host execution speed.
        val procPath = "'${proc.absolutePath.replace("'", "'\\''")}'/"
        val command = CdpRelay.relayProbeCommand().replace("/proc/", procPath)
        val rejectCommCat = "cat() { case \"\$1\" in */comm) " +
            "echo unexpected_comm_cat >&2; return 97;; esac; command cat \"\$@\"; }; "
        val shell = ProcessBuilder("/bin/sh", "-c", rejectCommCat + command).start()
        return try {
            assertTrue("relay inventory exceeded its production deadline", shell.waitFor(2, TimeUnit.SECONDS))
            val output = shell.inputStream.bufferedReader().readText()
            val errors = shell.errorStream.bufferedReader().readText()
            assertFalse(errors, errors.contains("unexpected_comm_cat"))
            relayExposureState(output.takeIf { shell.exitValue() == 0 })
        } finally {
            shell.destroyForcibly()
        }
    }

    @Test fun `never-rooted panel without a listener can enter Hardened mode`() {
        assertTrue(
            noRootRelayAbsent(
                priorRelayArtifact = false,
                listenerProbes = listOf(LocalRelayListenerState.ABSENT, LocalRelayListenerState.ABSENT),
            ),
        )
    }

    @Test fun `no-root admission rejects prior relay evidence listener and probe failure`() {
        assertFalse(
            noRootRelayAbsent(
                priorRelayArtifact = true,
                listenerProbes = listOf(LocalRelayListenerState.ABSENT, LocalRelayListenerState.ABSENT),
            ),
        )
        assertFalse(
            noRootRelayAbsent(
                priorRelayArtifact = false,
                listenerProbes = listOf(LocalRelayListenerState.ABSENT, LocalRelayListenerState.PRESENT),
            ),
        )
        assertFalse(
            noRootRelayAbsent(
                priorRelayArtifact = false,
                listenerProbes = listOf(LocalRelayListenerState.ABSENT, LocalRelayListenerState.UNKNOWN),
            ),
        )
    }

    @Test fun `process exit ignores stale source artifact only after repeated listener absence`() {
        assertTrue(
            noRootRelayInactiveForProcessExit(
                listOf(LocalRelayListenerState.ABSENT, LocalRelayListenerState.ABSENT),
            ),
        )
        assertFalse(
            noRootRelayInactiveForProcessExit(
                listOf(LocalRelayListenerState.ABSENT, LocalRelayListenerState.PRESENT),
            ),
        )
        assertFalse(
            noRootRelayInactiveForProcessExit(
                listOf(LocalRelayListenerState.ABSENT, LocalRelayListenerState.UNKNOWN),
            ),
        )
        assertFalse(noRootRelayInactiveForProcessExit(listOf(LocalRelayListenerState.ABSENT)))
    }

    @Test fun `non-root running status uses the real fixed listener`() {
        assertEquals(
            RelayExposureState.ABSENT,
            relayStatusExposure(RelayExposureState.UNKNOWN, LocalRelayListenerState.ABSENT),
        )
        assertEquals(
            RelayExposureState.PRESENT,
            relayStatusExposure(RelayExposureState.UNKNOWN, LocalRelayListenerState.PRESENT),
        )
        assertEquals(
            RelayExposureState.UNKNOWN,
            relayStatusExposure(RelayExposureState.UNKNOWN, LocalRelayListenerState.UNKNOWN),
        )
    }
}
