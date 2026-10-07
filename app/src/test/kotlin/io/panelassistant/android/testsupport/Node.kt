package io.panelassistant.android.testsupport

import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assume.assumeTrue

/**
 * The one way JVM tests run Node.js: an availability probe and a run that returns the exit code and the
 * combined stdout/stderr. Callers that need Node call [assumeAvailable] so a runner without it skips.
 */
object Node {
    data class Result(val exitCode: Int, val output: String)

    val available: Boolean by lazy { runCatching { run("--version").exitCode == 0 }.getOrDefault(false) }

    fun assumeAvailable() = assumeTrue("node not available (skipping)", available)

    /** Runs `node args…`, feeding [stdin] when given; a run past [timeoutSeconds] is killed and reported as exit -1. */
    fun run(vararg args: String, stdin: String? = null, timeoutSeconds: Long = 120): Result {
        val out = File.createTempFile("node-run", ".log")
        try {
            val process = ProcessBuilder(listOf("node") + args).redirectErrorStream(true)
                .redirectOutput(out).start()
            process.outputStream.bufferedWriter().use { if (stdin != null) it.write(stdin) }
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return Result(-1, out.readText() + "\nnode timed out after ${timeoutSeconds}s")
            }
            return Result(process.exitValue(), out.readText())
        } finally {
            out.delete()
        }
    }
}
