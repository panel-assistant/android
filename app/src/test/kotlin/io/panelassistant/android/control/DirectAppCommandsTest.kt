package io.panelassistant.android.control

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectAppCommandsTest {
    @Test(timeout = 15_000)
    fun blockedPropertyLaunchReturnsAndRetainsCustodyUntilLateProcessIsDestroyed() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val lateReads = AtomicInteger()
        val late = ExitedProcess(input = object : InputStream() {
            override fun read(): Int {
                lateReads.incrementAndGet()
                return -1
            }
        })
        val starts = AtomicInteger()
        val commands = DirectAppCommands(processStarter = {
            if (starts.incrementAndGet() == 1) {
                entered.countDown()
                awaitIgnoringInterrupt(release)
                late
            } else ExitedProcess("5555\n")
        }, readTimeoutMs = 1_000L)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val first = executor.submit<String?> { commands.getprop(SERVICE_ADB_TCP_PORT_PROPERTY) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertNull(first.get(3, TimeUnit.SECONDS))
            assertNull(commands.getprop(SERVICE_ADB_TCP_PORT_PROPERTY))
            assertEquals(1, starts.get())
            release.countDown()
            assertTrue(late.destroyed.await(5, TimeUnit.SECONDS))
            assertEquals(0, lateReads.get())
            assertEquals("5555", commands.getprop(SERVICE_ADB_TCP_PORT_PROPERTY))
            assertEquals(0, lateReads.get())
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test(timeout = 15_000)
    fun exitedCatWithHeldPipeReturnsAndPreventsOverlapUntilDrainFinishes() {
        val reading = CountDownLatch(1)
        val release = CountDownLatch(1)
        val held = ExitedProcess(input = object : InputStream() {
            override fun read(): Int {
                reading.countDown()
                awaitIgnoringInterrupt(release)
                return -1
            }
        })
        val starts = AtomicInteger()
        val commands = DirectAppCommands(processStarter = {
            if (starts.incrementAndGet() == 1) held else ExitedProcess("healthy")
        }, readTimeoutMs = 1_000L)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val first = executor.submit<String?> { commands.tcpInventory("/proc/net/tcp") }
            assertTrue(reading.await(5, TimeUnit.SECONDS))
            assertNull(first.get(3, TimeUnit.SECONDS))
            assertTrue(held.destroyed.await(1, TimeUnit.SECONDS))
            assertNull(commands.tcpInventory("/proc/net/tcp6"))
            assertEquals(1, starts.get())
            release.countDown()
            assertEquals("healthy", commands.tcpInventory("/proc/net/tcp6"))
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test fun fixedCommandsPreserveArgvByteLimitsAndExitPolicies() {
        val argv = mutableListOf<List<String>>()
        var output = "x".repeat(128)
        var exit = 0
        val commands = DirectAppCommands(processStarter = {
            argv += it
            ExitedProcess(output, exit)
        })
        assertEquals(output, commands.getprop("-Z", SERVICE_ADB_TCP_PORT_PROPERTY))
        assertEquals(listOf("/system/bin/getprop", "-Z", SERVICE_ADB_TCP_PORT_PROPERTY), argv.last())
        output += "x"
        assertNull(commands.getprop(SERVICE_ADB_TCP_PORT_PROPERTY))
        output = "x".repeat(64 * 1024)
        assertEquals(output, commands.tcpInventory("/proc/net/tcp"))
        assertEquals(listOf("/system/bin/cat", "/proc/net/tcp"), argv.last())
        output += "x"
        assertNull(commands.tcpInventory("/proc/net/tcp"))
        output = " Physical density: 160\n"
        exit = 1
        assertNull(commands.getprop(SERVICE_ADB_TCP_PORT_PROPERTY))
        assertNull(commands.tcpInventory("/proc/net/tcp6"))
        assertEquals(output.trim(), commands.windowDensity(null))
        assertEquals(listOf("cmd", "window", "density"), argv.last())
        assertEquals(output.trim(), commands.windowDensity("reset"))
        assertEquals(listOf("cmd", "window", "density", "reset"), argv.last())
        assertEquals(output.trim(), commands.windowDensity("240"))
        assertEquals(listOf("cmd", "window", "density", "240"), argv.last())
    }

    @Test(timeout = 10_000)
    fun densityDrainsBeforeWaitingForExitAndCleansUpSuccessfulCommands() {
        val drained = CountDownLatch(1)
        val process = ExitedProcess(input = object : ByteArrayInputStream("Physical density: 160".toByteArray()) {
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int =
                super.read(bytes, offset, length).also { if (it == -1) drained.countDown() }
        }, readyToExit = drained)
        val commands = DirectAppCommands(processStarter = { process })
        assertEquals("Physical density: 160", commands.windowDensity(null))
        assertTrue(process.destroyed.await(1, TimeUnit.SECONDS))
    }

    private class ExitedProcess(
        output: String = "",
        private val code: Int = 0,
        private val input: InputStream = ByteArrayInputStream(output.toByteArray()),
        private val readyToExit: CountDownLatch = CountDownLatch(0),
    ) : Process() {
        val destroyed = CountDownLatch(1)
        override fun getInputStream(): InputStream = input
        override fun getErrorStream(): InputStream = ByteArrayInputStream(byteArrayOf())
        override fun getOutputStream() = ByteArrayOutputStream()
        override fun waitFor(): Int { readyToExit.await(); return code }
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
            return readyToExit.await(timeout, unit)
        }
        override fun exitValue(): Int = code
        override fun destroy() { destroyed.countDown() }
        override fun destroyForcibly(): Process { destroy(); return this }
    }

    private companion object {
        fun awaitIgnoringInterrupt(latch: CountDownLatch) {
            while (true) {
                try { latch.await(); return } catch (_: InterruptedException) { }
            }
        }
    }
}
