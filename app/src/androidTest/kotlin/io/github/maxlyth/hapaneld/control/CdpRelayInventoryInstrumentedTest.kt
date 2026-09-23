package io.github.maxlyth.hapaneld.control

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.maxlyth.hapaneld.CoreInstrumentation
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Executes the production inventory command in Android's shell with a controlled proc tree. */
@CoreInstrumentation
@RunWith(AndroidJUnit4::class)
class CdpRelayInventoryInstrumentedTest {
    @Test fun largeProcessInventoryNeedsNoPerProcessExecutableAndStillProvesAbsence() = withProc { proc ->
        assertEquals(RelayExposureState.ABSENT, probe(proc))
    }

    @Test fun processAndListenerEachPreventAnAbsentVerdict() = withProc { proc ->
        File(proc, "399/comm").writeText("cdprelay\n")
        assertEquals(RelayExposureState.PRESENT, probe(proc))
        File(proc, "399/comm").writeText("worker\n")
        File(proc, "net/tcp").appendText(" 0: 00000000:2406 00000000:0000 0A\n")
        assertEquals(RelayExposureState.PRESENT, probe(proc))
    }

    @Test fun unreadableProcessOrMissingListenerTableCannotProveAbsence() = withProc { proc ->
        // A directory is an existing comm entry that read cannot consume, even on a rooted emulator.
        val comm = File(proc, "1/comm")
        assertTrue(comm.delete())
        assertTrue(comm.mkdir())
        assertEquals(RelayExposureState.UNKNOWN, probe(proc))
        assertTrue(comm.delete())
        comm.writeText("worker\n")
        assertTrue(File(proc, "net/tcp").delete())
        assertEquals(RelayExposureState.UNKNOWN, probe(proc))
    }

    private fun probe(proc: File): RelayExposureState {
        val script = """
            cat() {
                case "${'$'}1" in
                    */[0-9]*/comm) echo forbidden_per_process_cat >&2; return 99 ;;
                    *) /system/bin/cat "${'$'}@" ;;
                esac
            }
        """.trimIndent() + "\n" + CdpRelay.relayProbeCommand().replace("/proc/", "${proc.absolutePath}/")
        val process = ProcessBuilder("/system/bin/sh", "-c", script).redirectErrorStream(true).start()
        try {
            assertTrue("inventory exceeded the production two-second probe budget", process.waitFor(2, TimeUnit.SECONDS))
            val output = process.inputStream.bufferedReader().use { it.readText() }
            assertTrue("inventory used an executable per process: $output", "forbidden_per_process_cat" !in output)
            return relayExposureState(output.takeIf { process.exitValue() == 0 })
        } finally {
            process.destroy()
        }
    }

    private fun withProc(test: (File) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val proc = File(context.cacheDir, "relay-proc-${System.nanoTime()}").apply { mkdirs() }
        try {
            repeat(400) { index ->
                File(proc, "${index + 1}/comm").apply { parentFile!!.mkdirs(); writeText("worker\n") }
            }
            File(proc, "net").mkdirs()
            File(proc, "net/tcp").writeText("sl local_address rem_address st\n")
            File(proc, "net/tcp6").writeText("sl local_address remote_address st\n")
            test(proc)
        } finally {
            proc.deleteRecursively()
        }
    }
}
