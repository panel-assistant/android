package io.panelassistant.android.control

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Process launch for a real [SuShell] on a panel whose `su -c` dialect works: counts every su process
 * started and every command sent down a persistent root shell, so a test can prove a status path
 * starts no root work at all. [mode] switches between a working su, a daemon that has died (`su`
 * starts and exits 1) and no su binary.
 */
internal class CountingSuLaunch {
    enum class Mode { WORKING, DEAD, ABSENT }

    @Volatile var mode = Mode.WORKING
    val launches = AtomicInteger()
    val shellCommands = AtomicInteger()
    private val shells = mutableListOf<Process>()

    /** su processes started plus commands piped to an open root shell. */
    fun rootWork(): Int = launches.get() + shellCommands.get()

    fun close() = synchronized(shells) { shells.forEach { it.destroy() } }

    fun launch(argv: Array<String>): Process {
        if (mode == Mode.ABSENT) throw IOException("Cannot run program \"su\": error=2, No such file or directory")
        launches.incrementAndGet()
        if (mode == Mode.DEAD) return Exited(1)
        return when {
            argv.contentEquals(arrayOf("su")) -> Shell().also { synchronized(shells) { shells += it } }
            argv.size == 3 && argv[0] == "su" && argv[1] == "-c" -> Exited(if (argv[2] == "true") 0 else 1)
            else -> Exited(1) // the other dialect is not this panel's
        }
    }

    private class Exited(private val rc: Int) : Process() {
        override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()
        override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun getErrorStream(): InputStream = InputStream.nullInputStream()
        override fun waitFor(): Int = rc
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = true
        override fun exitValue(): Int = rc
        override fun isAlive(): Boolean = false
        override fun destroy() = Unit
    }

    private inner class Shell : Process() {
        private val toShell = PipedOutputStream()
        private val shellIn = PipedInputStream(toShell)
        private val fromShell = PipedOutputStream()
        private val appIn = PipedInputStream(fromShell)
        @Volatile private var alive = true
        private val worker = Thread {
            runCatching {
                shellIn.bufferedReader().forEachLine { line ->
                    shellCommands.incrementAndGet()
                    val cmd = line.removePrefix("{ ").substringBefore(" ; } 2>/dev/null; echo ")
                    val sentinel = line.substringAfter("; echo ").substringBefore(":$?")
                    fromShell.write("$sentinel:${if (cmd == "true") 0 else 1}\n".toByteArray())
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
}
