package io.github.maxlyth.hapaneld.logship

import io.github.maxlyth.hapaneld.metrics.FeatureCostOperation
import io.github.maxlyth.hapaneld.metrics.FeatureCostRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InterruptedIOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * [LogCapture.redact] is the last line of defence before a log line reaches ANY consumer — the
 * remote [LogShipper] sink and the `:8888` live log viewer both read the same redacted stream — so
 * the secret shapes it strips are pinned here. Capture is the app's own logcat, which can carry MQTT
 * creds, HA tokens, and URLs with query secrets — none of which should escape verbatim.
 */
class LogCaptureTest {
    private class GateProcess(
        output: String,
        private val terminateOnDestroy: Boolean = true,
    ) : Process() {
        private val finished = CountDownLatch(1)
        private val waiting = CountDownLatch(1)
        private val input = ByteArrayInputStream(output.toByteArray())
        val forcedDestroys = AtomicInteger()
        @Volatile private var exitCode: Int? = null

        fun finish(code: Int = 0) {
            exitCode = code
            finished.countDown()
        }

        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        override fun getInputStream(): InputStream = input
        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun waitFor(): Int {
            finished.await()
            return exitCode ?: 0
        }
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
            waiting.countDown()
            return finished.await(timeout, unit)
        }
        fun awaitWaiting() = assertTrue("dump did not start waiting", waiting.await(2, TimeUnit.SECONDS))
        override fun exitValue(): Int = exitCode ?: throw IllegalThreadStateException("still running")
        override fun destroy() {
            if (terminateOnDestroy) finish(143)
        }
        override fun destroyForcibly(): Process {
            forcedDestroys.incrementAndGet()
            finish(137)
            return this
        }
        override fun isAlive(): Boolean = finished.count > 0
    }

    private class InterruptingInputStream : InputStream() {
        private val entered = CountDownLatch(1)
        private val interrupted = CountDownLatch(1)

        override fun read(): Int {
            entered.countDown()
            try {
                CountDownLatch(1).await()
                return -1
            } catch (_: InterruptedException) {
                interrupted.countDown()
                throw InterruptedIOException("log dump reader interrupted during cleanup")
            }
        }

        fun awaitEntered() = assertTrue("dump reader did not start", entered.await(2, TimeUnit.SECONDS))
        fun awaitInterrupted() = assertTrue("dump reader was not interrupted", interrupted.await(2, TimeUnit.SECONDS))
    }

    private fun redact(s: String) = LogCapture.redact(s)

    @Test fun stripsBearerAuthorizationHeader() {
        val out = redact("06-28 10:15:30.123  900  950 D ha-paneld/http: Authorization: Bearer abc123XYZ.token-value")
        assertFalse("token must not survive", out.contains("abc123XYZ.token-value"))
        assertTrue(out.contains("***"))
    }

    @Test fun stripsPasswordAndTokenKeyValues() {
        assertFalse(redact("mqtt password=hunter2secret").contains("hunter2secret"))
        assertFalse(redact("access_token: aaaaaaaabbbbbbbb").contains("aaaaaaaabbbbbbbb"))
        assertFalse(redact("api_key = ZZZZ9999ZZZZ").contains("ZZZZ9999ZZZZ"))
        // The key label is preserved so the line stays readable.
        assertTrue(redact("password=hunter2secret").startsWith("password"))
    }

    @Test fun stripsJwtLikeTokens() {
        val jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dozjgNryP4J3jVmNHl0w5N_XgL0n3I9PlFUP0THsR8U"
        val out = redact("06-28 10:15:30.123  900  950 I ha-paneld/mqtt: connecting with $jwt")
        assertFalse(out.contains(jwt))
        assertTrue(out.contains("***jwt***"))
    }

    @Test fun stripsUrlQuerySecrets() {
        val out = redact("GET http://ha.local:8123/api/stream?access_token=SUPERSECRETTOKEN&foo=bar")
        assertFalse(out.contains("SUPERSECRETTOKEN"))
        // Non-secret query params and the rest of the URL are untouched.
        assertTrue(out.contains("foo=bar"))
        assertTrue(out.contains("http://ha.local:8123/api/stream"))
    }

    @Test fun leavesOrdinaryLinesUnchanged() {
        val line = "06-28 10:15:30.123  900  950 I ha-paneld/svc: foreground service started"
        assertEquals(line, redact(line))
    }

    @Test fun boundedUtf8AccountingIsExactWithoutEncodedCopies() {
        assertEquals(5L, boundedUtf8Bytes("plain", 100))
        assertEquals(6L, boundedUtf8Bytes("é😀", 100))
        assertEquals(5L, boundedUtf8Bytes("é😀", 5))
        assertEquals(1L, boundedUtf8Bytes("\uD800", 100))
        assertEquals(6L, boundedUtf8Bytes(listOf("é", "😀"), 100))
        assertEquals(3L, boundedUtf8Bytes(listOf("é", "😀"), 3))
    }

    // ---- streaming behaviour (shell subprocess stands in for logcat) ----------------------------

    private fun capture(script: String, dump: String = "true") = LogCapture(
        CoroutineScope(Dispatchers.IO),
        listOf("sh", "-c", script),
        { listOf("sh", "-c", dump) },
    )

    /** Poll until [cond] or the deadline — the subprocess pipe delivery is asynchronous. */
    private fun await(timeoutMs: Long = 5_000, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!cond()) {
            assertTrue("timed out waiting for capture output", System.currentTimeMillis() < deadline)
            Thread.sleep(20)
        }
    }

    @Test fun streamsRedactsAndBuffersLines() {
        val cap = capture("echo one; echo 'password=hunter2secret'; sleep 30")
        val got = java.util.concurrent.CopyOnWriteArrayList<String>()
        val sub = cap.subscribe { got.add(it) }
        try {
            await { got.size >= 2 }
            assertEquals("one", got[0])
            // Redaction happens at capture, so the viewer path can never see the secret either.
            assertFalse(got[1].contains("hunter2secret"))
            // The ring snapshot serves the same (redacted) lines as backlog for a late viewer.
            assertTrue(cap.snapshot().containsAll(got.take(2)))
        } finally {
            sub.close()
        }
    }

    @Test fun streamingCaptureRecordsOneBoundedBufferedBatch() {
        val wall = AtomicLong()
        val costs = FeatureCostRegistry(
            wallNanos = { wall.getAndAdd(100L) },
            threadCpuNanos = { -1L },
            threadId = { 1L },
        )
        val process = GateProcess("one\ntwo\nthree\n")
        val cap = LogCapture(
            CoroutineScope(Dispatchers.IO),
            listOf("stream"),
            { listOf("dump") },
            processStarter = { process },
            featureCosts = costs,
        )
        val got = java.util.concurrent.CopyOnWriteArrayList<String>()
        val sub = cap.subscribe(got::add)
        try {
            await { got.size == 3 }
            val operation = operation(costs, FeatureCostOperation.LOG_CAPTURE_BATCH)
            assertEquals(1L, operation.getLong("calls"))
            assertEquals(1L, operation.getLong("succeeded"))
            assertEquals(3L, operation.getLong("work_units"))
            assertEquals(11L, operation.getLong("work_bytes"))
        } finally {
            sub.close()
            cap.close()
        }
    }

    @Test fun fanOutReachesEverySubscriberAndIdleStopClearsRing() {
        val started = CountDownLatch(1)
        lateinit var process: Process
        val cap = LogCapture(
            CoroutineScope(Dispatchers.IO),
            listOf("sh", "-c", "read _; echo shared; sleep 30"),
            { listOf("true") },
            processStarter = { command ->
                ProcessBuilder(command).redirectErrorStream(true).start().also {
                    process = it
                    started.countDown()
                }
            },
        )
        val a = java.util.concurrent.CopyOnWriteArrayList<String>()
        val b = java.util.concurrent.CopyOnWriteArrayList<String>()
        val subA = cap.subscribe { a.add(it) }
        val subB = cap.subscribe { b.add(it) }
        assertTrue("capture did not start", started.await(2, TimeUnit.SECONDS))
        process.outputStream.write('\n'.code)
        process.outputStream.flush()
        await { a.isNotEmpty() && b.isNotEmpty() }
        assertEquals("shared", a[0])
        assertEquals("shared", b[0])
        subA.close()
        // One subscriber left → capture (and its backlog ring) stays alive.
        assertTrue(cap.snapshot().isNotEmpty())
        subB.close()
        // Last detach = idle-stop: the subprocess is destroyed and the ring cleared.
        assertTrue(cap.snapshot().isEmpty())
        cap.close()
    }

    @Test fun dumpRunsOneShotCommandRedacted() {
        val cap = capture("sleep 30", dump = "echo ok; echo 'api_key = ZZZZ9999ZZZZ'")
        val out = cap.dump(10)
        assertEquals("ok", out[0])
        assertFalse(out[1].contains("ZZZZ9999ZZZZ"))
    }

    @Test fun concurrentFirstViewersShareOneDumpProcess() {
        val starts = AtomicInteger()
        val process = GateProcess("one\ntwo\n")
        val cap = LogCapture(
            CoroutineScope(Dispatchers.IO),
            listOf("unused"),
            { listOf("dump") },
            maxViewers = 8,
            processStarter = {
                starts.incrementAndGet()
                process
            },
        )
        val workers = 8
        val viewers = (1..workers).map {
            (cap.admitViewer() as LogCapture.ViewerAdmission.Accepted).lease
        }
        val ready = CountDownLatch(workers)
        val go = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(workers)
        try {
            val results = (1..workers).map {
                executor.submit<List<String>> {
                    ready.countDown()
                    go.await()
                    cap.initialBacklog()
                }
            }
            assertTrue(ready.await(2, TimeUnit.SECONDS))
            go.countDown()
            await { starts.get() == 1 }
            process.finish()
            results.forEach { assertEquals(listOf("one", "two"), it.get(2, TimeUnit.SECONDS)) }
            // The completed result remains shared until this admitted cohort drains, covering the
            // narrow response-before-live-subscribe scheduling gap.
            assertEquals(listOf("one", "two"), cap.initialBacklog())
            assertEquals(1, starts.get())
        } finally {
            viewers.forEach(AutoCloseable::close)
            cap.close()
            executor.shutdownNow()
        }
    }

    @Test fun viewerCapRejectsAndCleanupRestoresCapacity() {
        val cap = LogCapture(
            CoroutineScope(Dispatchers.IO),
            listOf("unused"),
            { listOf("unused") },
            maxViewers = 2,
        )
        val first = cap.admitViewer() as LogCapture.ViewerAdmission.Accepted
        val second = cap.admitViewer() as LogCapture.ViewerAdmission.Accepted
        assertEquals(LogCapture.ViewerAdmission.CapacityExceeded, cap.admitViewer())

        first.lease.close()
        first.lease.close() // cleanup is deliberately idempotent
        val replacement = cap.admitViewer() as LogCapture.ViewerAdmission.Accepted
        second.lease.close()
        replacement.lease.close()
        cap.close()
        assertEquals(LogCapture.ViewerAdmission.Unavailable, cap.admitViewer())
    }

    @Test fun dumpOutputIsBoundedBeforeDecoding() {
        val process = GateProcess("one\ntwo\nsecret-third-line\n").also { it.finish() }
        val cap = LogCapture(
            CoroutineScope(Dispatchers.IO),
            listOf("unused"),
            { listOf("dump") },
            dumpMaxBytes = 7,
            processStarter = { process },
        )
        try {
            assertEquals(listOf("one", "two"), cap.dump(10))
        } finally {
            cap.close()
        }
    }

    @Test fun timedOutDumpIsForciblyDestroyed() {
        val process = GateProcess("", terminateOnDestroy = false)
        val cap = LogCapture(
            CoroutineScope(Dispatchers.IO),
            listOf("unused"),
            { listOf("dump") },
            dumpTimeoutMs = 50,
            processStarter = { process },
        )
        try {
            assertTrue(cap.dump().isEmpty())
            assertTrue("timeout must escalate to forced destruction", process.forcedDestroys.get() > 0)
        } finally {
            cap.close()
        }
    }

    @Test fun interruptedDumpReaderCannotCrashTheProcess() {
        val input = InterruptingInputStream()
        val uncaught = AtomicReference<Throwable?>()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            if (thread.name == "ha-paneld-log-dump") uncaught.set(error)
        }
        val cap = LogCapture(
            CoroutineScope(Dispatchers.IO),
            listOf("unused"),
            { listOf("dump") },
            dumpTimeoutMs = 50,
            processStarter = { object : Process() {
                override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
                override fun getInputStream(): InputStream = input
                override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
                override fun waitFor(): Int = 137
                override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = false
                override fun exitValue(): Int = 137
                override fun destroy() = Unit
                override fun destroyForcibly(): Process = this
                override fun isAlive(): Boolean = true
            } },
        )
        try {
            val executor = Executors.newSingleThreadExecutor()
            val result = try {
                val future = executor.submit<List<String>> { cap.dump() }
                input.awaitEntered()
                future.get(2, TimeUnit.SECONDS)
            } finally {
                executor.shutdownNow()
            }
            assertTrue(result.isEmpty())
            input.awaitInterrupted()
            assertNull(uncaught.get())
        } finally {
            cap.close()
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
    }

    @Test fun terminalCloseClearsStateAndRejectsNewSubscribers() {
        val cap = capture("echo before-close; sleep 30")
        val before = java.util.concurrent.CopyOnWriteArrayList<String>()
        val first = cap.subscribe { before.add(it) }
        await { before.isNotEmpty() }
        val stoppedRun = cap.activeRun()!!

        cap.close()
        assertTrue(cap.snapshot().isEmpty())
        assertTrue(cap.dump().isEmpty())
        val after = java.util.concurrent.CopyOnWriteArrayList<String>()
        val rejected = cap.subscribe { after.add(it) }
        cap.emit(stoppedRun, "after-close")
        assertTrue(after.isEmpty())
        first.close()
        rejected.close()
    }

    @Test fun terminalCloseDestroysBlockedDumpProcess() {
        val process = GateProcess("")
        val cap = LogCapture(
            CoroutineScope(Dispatchers.IO),
            listOf("true"),
            { listOf("dump") },
            processStarter = { process },
        )
        val executor = Executors.newSingleThreadExecutor()
        try {
            val dump = executor.submit<List<String>> { cap.dump() }
            process.awaitWaiting()
            cap.close()
            assertTrue(dump.get(2, TimeUnit.SECONDS).isEmpty())
            assertTrue("close did not destroy the blocked process", process.forcedDestroys.get() > 0)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test fun lateLineFromStoppedRunCannotCrossIntoReplacementGeneration() {
        val cap = capture("sleep 30")
        val first = cap.subscribe { }
        val stoppedRun = cap.activeRun()!!
        first.close()

        val replacementLines = java.util.concurrent.CopyOnWriteArrayList<String>()
        val replacement = cap.subscribe(replacementLines::add)
        try {
            cap.emit(stoppedRun, "late-from-old-process")
            assertTrue(replacementLines.isEmpty())
            assertTrue(cap.snapshot().isEmpty())
        } finally {
            replacement.close()
            cap.close()
        }
    }

    private fun operation(
        registry: FeatureCostRegistry,
        expected: FeatureCostOperation,
    ): JSONObject {
        val operations = JSONObject(registry.json()).getJSONArray("operations")
        return (0 until operations.length()).asSequence()
            .map(operations::getJSONObject)
            .first { it.getString("id") == expected.id }
    }
}
