package io.panelassistant.android.control

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * A panel whose SuperSU daemon dies while the app runs: `su` still starts,
 * then exits 1 with no output. Driven through a repeated sysfs read over the real [SuShell] with only
 * process launch and the clock replaced. (The screen controller's backlight read that used to drive this
 * now goes to the root helper only; the shell's loss and recovery behaviour still serves rare callers.)
 */
class SuLostRootTest {

    private var now = 0L
    private val launches = AtomicInteger()
    @Volatile private var daemonAlive = true
    private val shells = mutableListOf<FakeSuShellProcess>()

    private val su = SuShell(launch = ::launch, nowMs = { now })

    /** A root read of the backlight power node: true when powered, null when root did not answer. */
    private val screen = object {
        fun observedLit(): Boolean? = su.runOutput("cat /sys/class/backlight/bl/bl_power")?.trim()?.toIntOrNull()?.let { it == 0 }
    }

    @After
    fun closeShells() = shells.forEach { it.destroy() }

    @Test
    fun `a dead su daemon costs one probe a minute, and root returns without a restart`() {
        assertEquals(true, screen.observedLit())
        assertTrue(su.availableCachedIsolated())  // diagnostics read the shared probe while root works

        daemonAlive = false
        shells.forEach { it.destroy() }
        val beforeLoss = launches.get()
        repeat(20) { screen.observedLit() }
        val duringLoss = launches.get() - beforeLoss
        // The first read finds the shell dead and falls back once; the second probes; the rest wait.
        assertEquals("20 backlight reads with su dead started $duringLoss su processes", 3, duringLoss)

        now += 61_000L
        val beforeMinute = launches.get()
        repeat(20) { assertNull(screen.observedLit()) }
        assertEquals("one probe in the next minute, nothing else", 1, launches.get() - beforeMinute)

        daemonAlive = true
        now += 61_000L
        assertEquals(true, screen.observedLit())
        val beforeReuse = launches.get()
        repeat(5) { assertEquals(true, screen.observedLit()) }
        assertEquals("the reopened shell serves later reads", 0, launches.get() - beforeReuse)
    }

    @Test
    fun `root that is not up yet when the app starts is used as soon as it answers`() {
        daemonAlive = false
        assertNull(screen.observedLit())

        daemonAlive = true
        assertEquals(true, screen.observedLit())
    }

    @Test fun `negative startup prewarm cannot block reads after root is proven`() {
        daemonAlive = false
        assertEquals(false, su.availableCachedIsolated())
        daemonAlive = true
        assertEquals(true, screen.observedLit())
        assertEquals(true, screen.observedLit())
        assertTrue(su.run("true"))
        assertEquals("persistent root shell opened", 1, shells.size)
        val beforeReuse = launches.get()
        repeat(5) { assertEquals(true, screen.observedLit()) }
        assertEquals(beforeReuse, launches.get())
    }

    @Test fun `isolated proof clears the negative startup availability observation`() {
        daemonAlive = false
        assertEquals(false, su.availableCachedIsolated())
        daemonAlive = true
        assertEquals("", su.runOutputIsolatedBounded("true", 1L, 5_000L))
        assertEquals(true, screen.observedLit())
        assertEquals(true, screen.observedLit())
        assertTrue(su.run("true"))
        assertEquals(1, shells.size)
    }

    private fun launch(argv: Array<String>): Process {
        launches.incrementAndGet()
        if (!daemonAlive) return ExitedProcess(1, "")
        return when {
            argv.contentEquals(arrayOf("su")) -> FakeSuShellProcess().also { shells += it }
            argv.size == 3 && argv[0] == "su" && argv[1] == "-c" -> answer(argv[2]).let { (out, rc) -> ExitedProcess(rc, out) }
            else -> ExitedProcess(1, "")  // the other su dialect is not this panel's
        }
    }

    private class ExitedProcess(private val rc: Int, stdout: String) : Process() {
        private val out = ByteArrayInputStream(stdout.toByteArray())
        override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()
        override fun getInputStream(): InputStream = out
        override fun getErrorStream(): InputStream = InputStream.nullInputStream()
        override fun waitFor(): Int = rc
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = true
        override fun exitValue(): Int = rc
        override fun isAlive(): Boolean = false
        override fun destroy() = Unit
    }

    /** An interactive root shell that answers each [persistentShellRequest] until destroyed. */
    private class FakeSuShellProcess : Process() {
        private val toShell = PipedOutputStream()
        private val shellIn = PipedInputStream(toShell)
        private val fromShell = PipedOutputStream()
        private val appIn = PipedInputStream(fromShell)
        @Volatile private var alive = true
        private val worker = Thread {
            runCatching {
                shellIn.bufferedReader().forEachLine { line ->
                    val cmd = line.removePrefix("{ ").substringBefore(" ; } 2>/dev/null; echo ")
                    val sentinel = line.substringAfter("; echo ").substringBefore(":$?")
                    val (out, rc) = answer(cmd)
                    fromShell.write("$out$sentinel:$rc\n".toByteArray())
                    fromShell.flush()
                }
            }
        }.apply { isDaemon = true; start() }

        override fun getOutputStream(): OutputStream = toShell
        override fun getInputStream(): InputStream = appIn
        override fun getErrorStream(): InputStream = InputStream.nullInputStream()
        override fun waitFor(): Int { worker.join(); return exitValue() }
        override fun exitValue(): Int = if (alive) throw IllegalThreadStateException() else 1
        override fun isAlive(): Boolean = alive
        override fun destroy() {
            alive = false
            runCatching { fromShell.close() }
            runCatching { shellIn.close() }
        }
    }

    private companion object {
        fun answer(cmd: String): Pair<String, Int> = when {
            cmd == "true" -> "" to 0
            "bl_power" in cmd -> "0\n" to 0
            else -> "" to 1
        }
    }
}
