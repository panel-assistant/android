package io.panelassistant.android.control

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.BufferedReader
import java.io.IOException
import java.io.StringReader
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * The persistent root shell's request/reply framing, exercised through a real `/bin/sh` rather than `su`.
 * Issue #93: `prepareOutputGpio` ends in `printf ready`, whose missing newline put the sentinel mid-line,
 * so the reply was never recognised and every preparation waited out the five-second timeout.
 */
class SuPersistentShellReplyTest {
    private val sentinel = "__hapaneld_done__"

    /** Null when the reply was never recognised and the reader ran off the end of the stream. */
    private fun reply(text: String): Pair<String, Int>? = try {
        readPersistentShellReply(BufferedReader(StringReader(text)), sentinel)
    } catch (_: IOException) {
        null
    }

    /** Replies from a real shell, or null when they did not arrive within [timeoutMs]: an unrecognised
     *  reply blocks on the next read, which production turns into its five-second fallback. */
    private fun throughShell(timeoutMs: Long, vararg commands: String): List<Pair<String, Int>>? {
        val shell = ProcessBuilder("/bin/sh").start()
        val reader = Executors.newSingleThreadExecutor()
        try {
            val stdin = shell.outputStream.bufferedWriter()
            val stdout = shell.inputStream.bufferedReader()
            val replies = reader.submit(Callable {
                commands.map { cmd ->
                    stdin.write(persistentShellRequest(cmd, sentinel))
                    stdin.flush()
                    readPersistentShellReply(stdout, sentinel)
                }
            })
            return try {
                replies.get(timeoutMs, TimeUnit.MILLISECONDS)
            } catch (_: TimeoutException) {
                null
            }
        } finally {
            shell.destroy()
            shell.waitFor(5, TimeUnit.SECONDS)
            reader.shutdownNow()
        }
    }

    @Test fun anUnterminatedStdoutFragmentEndsTheReply() {
        assertEquals("ready" to 0, reply("ready${sentinel}:0\n"))
    }

    @Test fun terminatedOutputIsReturnedUnchanged() {
        assertEquals("1\n" to 0, reply("1\n${sentinel}:0\n"))
        assertEquals("a\nb\n" to 0, reply("a\nb\n${sentinel}:0\n"))
        assertEquals("" to 1, reply("${sentinel}:1\n"))
    }

    @Test fun sentinelTextInsideOutputDoesNotEndTheReply() {
        assertEquals("a ${sentinel} b\n" to 0, reply("a ${sentinel} b\n${sentinel}:0\n"))
    }

    @Test fun aMultiLineReplyKeepsOnlyItsLastFragmentUnterminated() {
        assertEquals("a\nb" to 0, reply("a\nb${sentinel}:0\n"))
    }

    @Test fun theGpioPreparationReplyIsRecognisedByARealShell() {
        // The shell stays in step after an unterminated reply, and no reply waits on a timeout.
        assertEquals(
            listOf("ready" to 0, "later\n" to 0, "" to 1),
            throughShell(10_000, "printf ready", "echo later", "false"),
        )
    }
}
