package io.panelassistant.android.logship

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class LogCaptureNoiseTest {
    private fun await(check: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!check()) {
            assertTrue("capture did not deliver expected output", System.nanoTime() < deadline)
            Thread.sleep(5)
        }
    }

    // Uses only the pre-existing caller API: the base delivers all three warnings and fails.
    @Test fun repeatedWarningsReachOrdinaryConsumersOnce() {
        val release = CompletableDeferred<Unit>()
        val cap = LogCapture(CoroutineScope(Dispatchers.IO), emptyList(), { emptyList() },
            lineStream = { emit ->
                release.await()
                repeat(3) { emit("11-07 00:00:00.000 123 123 W Example: password=private-value failed") }
                emit("11-07 00:00:00.001 123 123 I Example: complete")
                awaitCancellation()
            })
        val got = CopyOnWriteArrayList<String>()
        val sub = cap.subscribe(got::add)
        try {
            release.complete(Unit)
            await { got.any { it.endsWith("complete") } }
            assertEquals(2, got.size)
            assertFalse(got.joinToString().contains("private-value"))
        } finally { sub.close(); cap.close() }
    }

    @Test fun profileNoiseAndRepeatedWarningsShareCaptureButKeepRedactedRawAccess() {
        val release = CompletableDeferred<Unit>()
        val emitted = CompletableDeferred<Unit>()
        val starts = AtomicInteger()
        val audit = "type=1400 audit(0.0:9): avc: denied { read } for comm=\"ps\" permissive=1"
        val lines = listOf(
            "11-07 00:00:00.000 123 123 I auditd: $audit",
            "11-07 00:00:00.001 123 123 I DefaultDispatch: $audit",
            "11-07 00:00:00.002 123 123 W Example: password=private-value failed",
            "11-07 00:00:00.003 123 123 W Example: password=private-value failed",
            "11-07 00:00:00.004 123 123 W Example: password=private-value failed",
            "11-07 00:00:00.005 123 123 E auditd: $audit",
            "11-07 00:00:00.006 123 123 I auditd: ${audit.replace("permissive=1", "permissive=0")}",
            "unparsed permissive=1 password=private-value",
        )
        val cap = LogCapture(CoroutineScope(Dispatchers.IO), emptyList(), { emptyList() },
            lineStream = { emit ->
                starts.incrementAndGet(); release.await(); lines.forEach(emit)
                emitted.complete(Unit); awaitCancellation()
            }, knownNoise = { setOf("selinux-permissive") }, noiseSummaryIntervalMs = 100)
        val normal = CopyOnWriteArrayList<String>()
        val raw = CopyOnWriteArrayList<String>()
        val records = CopyOnWriteArrayList<LogCaptureRecord>()
        val subs = listOf(cap.subscribe(normal::add), cap.subscribe(true, raw::add), cap.subscribeRecords(records::add))
        try {
            release.complete(Unit)
            await { emitted.isCompleted && normal.count { it.contains("collapsed") } == 2 }
            assertEquals(1, starts.get())
            assertEquals(lines.map(LogCapture::redact), raw.toList())
            assertEquals(raw.toList(), cap.initialBacklog(raw = true))
            assertEquals(normal.toList(), cap.initialBacklog())
            assertEquals(1, normal.count { it.contains("W Example:") })
            assertTrue(normal.any { it.contains("selinux-permissive: collapsed 2 records") })
            assertTrue(normal.any { it.contains("repeated warning from Example: collapsed 2 records") })
            assertTrue(normal.any { it.contains("E auditd:") })
            assertTrue(normal.any { it.contains("permissive=0") })
            assertTrue(normal.any { it.startsWith("unparsed") })
            assertEquals(2, records.count { it.tag == "ha-paneld/log-noise" })
            assertFalse((raw + normal).joinToString().contains("private-value"))
        } finally { subs.forEach { it.close() }; cap.close() }
    }

    @Test fun unselectedProfileKeepsAuditsAndRestartWarningsUseFirstPlusCount() {
        val warnings = CopyOnWriteArrayList<String>()
        val attempts = AtomicInteger()
        val cap = LogCapture(CoroutineScope(Dispatchers.IO), emptyList(), { emptyList() },
            lineStream = { emit ->
                if (attempts.incrementAndGet() == 1) {
                    emit("11-07 00:00:00.000 123 123 I auditd: type=1400 audit(0.0:9): avc: denied { read } permissive=1")
                }
                throw IOException("helper offline")
            }, restartBaseMs = 10, restartMaxMs = 10, noiseSummaryIntervalMs = 150,
            warningLogger = warnings::add)
        val got = CopyOnWriteArrayList<String>()
        val sub = cap.subscribe(got::add)
        try {
            await { warnings.any { it.contains("repeated") } }
            assertTrue(attempts.get() > warnings.size)
            assertEquals(1, warnings.count { !it.contains("repeated") })
            assertEquals(1, got.count { it.contains("permissive=1") })
        } finally { sub.close(); cap.close() }
    }

    @Test fun aSourceThatNeverStartsReportsItsRetriesAsTheyDoubleAndReportsItsFinalCount() = runTest {
        val warnings = mutableListOf<String>()
        var attempts = 0
        val cap = LogCapture(backgroundScope, emptyList(), { emptyList() },
            lineStream = { attempts++; throw IOException("helper LOGCAT unavailable") },
            restartBaseMs = 10, restartMaxMs = 10, noiseSummaryIntervalMs = 10,
            warningLogger = warnings::add, captureContext = StandardTestDispatcher(testScheduler))
        val sub = cap.subscribe { }
        try {
            advanceTimeBy(640)
            runCurrent()
            assertEquals(65, attempts)
            assertEquals(
                listOf("capture restart: helper LOGCAT unavailable") +
                    listOf(1, 2, 4, 8, 16, 32, 64).map { "capture restart: helper LOGCAT unavailable (repeated $it times)" },
                warnings,
            )
            advanceTimeBy(30)
            runCurrent()
            assertEquals(68, attempts)
        } finally { sub.close(); cap.close() }
        assertEquals("capture restart: helper LOGCAT unavailable (repeated 67 times)", warnings.last())
        assertEquals(9, warnings.size)
    }

    @Test fun aFailureAfterRecoveryIsLoggedAtOnce() = runTest {
        val warnings = mutableListOf<String>()
        var attempts = 0
        val cap = LogCapture(backgroundScope, emptyList(), { emptyList() },
            lineStream = { emit ->
                if (++attempts == 5) emit("11-07 00:00:00.000 123 123 I Example: streaming")
                throw IOException("helper offline")
            }, restartBaseMs = 10, restartMaxMs = 10, noiseSummaryIntervalMs = 10,
            warningLogger = warnings::add, captureContext = StandardTestDispatcher(testScheduler))
        val got = mutableListOf<String>()
        val sub = cap.subscribe(got::add)
        try {
            advanceTimeBy(40)
            runCurrent()
            assertEquals(5, attempts)
            assertEquals(listOf("11-07 00:00:00.000 123 123 I Example: streaming"), got)
            assertEquals(
                listOf("capture restart: helper offline",
                    "capture restart: helper offline (repeated 1 times)",
                    "capture restart: helper offline (repeated 2 times)",
                    "capture restart: helper offline (repeated 3 times)",
                    "capture restart: helper offline"),
                warnings,
            )
        } finally { sub.close(); cap.close() }
    }

    @Test fun helperBacklogUsesSameRuleAndRawDoesNotStartAnotherDump() {
        val dumps = AtomicInteger()
        val native = listOf("[ 1794011128.123 123: 123 I/auditd ]",
            "type=1400 audit(0.0:9): avc: denied { read } permissive=1", "")
        val cap = LogCapture(CoroutineScope(Dispatchers.IO), emptyList(), { emptyList() },
            helperDump = { dumps.incrementAndGet(); native }, longLogcatRecords = true,
            knownNoise = { setOf("selinux-permissive") })
        val viewer = cap.admitViewer() as LogCapture.ViewerAdmission.Accepted
        try {
            assertTrue(cap.dump().single().contains("selinux-permissive: collapsed 1 records"))
            val raw = cap.dump(raw = true).single()
            assertTrue(raw.endsWith("permissive=1"))
            assertFalse(raw.contains("collapsed"))
            assertEquals(1, dumps.get())
        } finally { viewer.lease.close(); cap.close() }
    }

    @Test fun helperBacklogCollapsesWarningsThatDifferOnlyInTheLeadingNumericAuditStamp() {
        val messages = listOf(
            "type=1400 audit(0.0:9): avc: denied { read } path=\"/tmp/audit(0.0:21)\"",
            "type=1400 audit(169.812:10): avc: denied { read } path=\"/tmp/audit(0.0:21)\"",
            "type=1400 audit(170.003:11): avc: denied { read } path=\"/tmp/audit(0.0:21)\"",
            "type=1400 audit(0.0:12): avc: denied { read } path=\"/tmp/audit(0.0:22)\"",
            "type=1400 audit(): avc: denied { read } path=\"/tmp/audit(0.0:21)\"",
            "type=1400 audit(invalid:13): avc: denied { read }",
            "type=1400 audit(invalid:14): avc: denied { read }",
            "retry failed for audit(0.0:15)",
            "retry failed for audit(0.0:16)",
        )
        val native = messages.mapIndexed { index, message ->
            val tag = if (message.startsWith("type=")) "auditd" else "Example"
            listOf("[ 1794011128.12$index 123: 123 W/$tag ]", message)
        }
        val raw = native.map { it.joinToString("\n") }
        val cap = LogCapture(CoroutineScope(Dispatchers.IO), emptyList(), { emptyList() },
            helperDump = { native.flatMap { it + "" } }, longLogcatRecords = true, wallMillis = { 0L })
        try {
            val bytes = raw.subList(1, 3).sumOf { it.toByteArray(Charsets.UTF_8).size }
            val summary = "[ 0.000000000  123: 0 W/ha-paneld/log-noise ]\n" +
                "repeated warning from auditd: collapsed 2 records ($bytes UTF-8 bytes); raw logs available on demand"
            assertEquals(listOf(raw.first()) + raw.drop(3) + summary, cap.dump())
            assertEquals(raw, cap.dump(raw = true))
        } finally { cap.close() }
    }
}
