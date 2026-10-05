package io.panelassistant.android.logship

import android.util.Log
import io.panelassistant.android.device.SuForm
import io.panelassistant.android.metrics.FeatureCostOperation
import io.panelassistant.android.metrics.FeatureCostOutcome
import io.panelassistant.android.metrics.FeatureCostRegistry
import io.panelassistant.android.metrics.FeatureCosts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.min

/**
 * Shared, demand-driven logcat capture. ONE subprocess and ONE redaction pass feed every consumer —
 * the remote [LogShipper] and any live viewers on the `:8888` Logs tab — plus a bounded ring of
 * recent lines so a viewer that attaches while capture is already running gets instant backlog.
 *
 * Runs only while it has subscribers (idle-stop): with log shipping off and no viewer connected
 * there is no logcat subprocess at all. Two sources exist as separate instances:
 *  - [app]: ha-paneld's own-process logcat (its `Log.*` output + the Ktor/HiveMQ SLF4J lines) —
 *    own-uid, so readable with no `READ_LOGS` permission and no root.
 *  - [system]: full system logcat via the profile's su form, or the authenticated helper when the app
 *    cannot use root itself.
 *  - [webView]: the dashboard WebView's JavaScript console, read over the CDP relay by an in-process
 *    [lineStream] instead of a subprocess (see [WebViewConsoleStream]).
 *
 * Every line is [redact]ed for tokens / passwords / URL secrets as it is captured, so both the
 * remote sink AND the local browser view only ever see scrubbed lines.
 */
class LogCapture(
    private val scope: CoroutineScope,
    /** Streaming subprocess argv (long-lived `logcat` follow). */
    private val streamCmd: List<String>,
    /** One-shot dump argv for [dump] — backlog prefill when the stream isn't already running. */
    private val dumpCmd: (Int) -> List<String>,
    private val maxViewers: Int = MAX_VIEWERS,
    private val dumpTimeoutMs: Long = DUMP_TIMEOUT_MS,
    private val dumpMaxBytes: Int = DUMP_MAX_BYTES,
    private val processStarter: (List<String>) -> Process = { command ->
        ProcessBuilder(command).redirectErrorStream(true).start()
    },
    private val featureCosts: FeatureCostRegistry = FeatureCosts.registry,
    /**
     * In-process producer used instead of [streamCmd] when set. It runs while the capture has
     * subscribers and hands each raw line to its callback; the line is then redacted and fanned out
     * exactly like a logcat line. There is no dump for such a source: backlog is the ring alone.
     */
    private val lineStream: (suspend (emit: (String) -> Unit) -> Unit)? = null,
    /** `-v long -v printable` escapes message newlines; only the blank footer separates entries. */
    private val longLogcatRecords: Boolean = false,
    /** Only system capture uses this to choose the root process or the helper at each restart. */
    private val processAvailable: () -> Boolean = { true },
) {
    init {
        require(maxViewers > 0) { "maxViewers must be positive" }
        require(dumpTimeoutMs > 0) { "dumpTimeoutMs must be positive" }
        require(dumpMaxBytes > 0) { "dumpMaxBytes must be positive" }
    }

    private val listeners = CopyOnWriteArrayList<(String) -> Unit>()
    private val ring = ArrayDeque<String>(RING_CAP)
    private var dumpFlight: DumpFlight? = null
    private var dumpProcess: Process? = null
    private var activeViewers = 0

    // Each start() gets its own Run so a stop→start race can never orphan the new subprocess.
    internal class Run {
        @Volatile var job: Job? = null
        @Volatile var proc: Process? = null
        private var cancelled = false

        fun attach(candidate: Process): Boolean = synchronized(this) {
            if (cancelled) {
                candidate.destroy()
                false
            } else {
                proc = candidate
                true
            }
        }

        fun detach(candidate: Process) = synchronized(this) {
            if (proc === candidate) proc = null
        }

        fun cancel() = synchronized(this) {
            cancelled = true
            job?.cancel()
            runCatching { proc?.destroy() }   // unblocks the reader parked in readLine()
            proc = null
        }
    }
    private var run: Run? = null
    @Volatile private var closed = false

    /** Admission is separate from [subscribe]: remote shipping is not a browser viewer and must not
     * consume the small interactive-stream budget. */
    sealed class ViewerAdmission {
        class Accepted internal constructor(val lease: AutoCloseable) : ViewerAdmission()
        data object CapacityExceeded : ViewerAdmission()
        data object Unavailable : ViewerAdmission()
    }

    private class DumpFlight {
        private val done = CountDownLatch(1)
        private val completed = AtomicBoolean(false)
        @Volatile private var value: List<String> = emptyList()

        fun complete(result: List<String>) {
            if (completed.compareAndSet(false, true)) {
                value = result
                done.countDown()
            }
        }

        fun await(timeoutMs: Long): List<String> =
            if (done.await(timeoutMs, TimeUnit.MILLISECONDS)) value else emptyList()

        fun isDone(): Boolean = done.count == 0L
    }

    internal fun activeRun(): Run? = synchronized(this) { run }

    /** Reserve one live-viewer slot. The returned lease is idempotent so every route exit path can
     * release it defensively. */
    fun admitViewer(): ViewerAdmission = synchronized(this) {
        if (closed) return ViewerAdmission.Unavailable
        if (activeViewers >= maxViewers) return ViewerAdmission.CapacityExceeded
        activeViewers++
        val released = AtomicBoolean(false)
        ViewerAdmission.Accepted(AutoCloseable {
            if (released.compareAndSet(false, true)) {
                synchronized(this) {
                    check(activeViewers > 0) { "viewer admission underflow" }
                    activeViewers--
                    if (activeViewers == 0 && dumpFlight?.isDone() == true) dumpFlight = null
                }
            }
        })
    }

    /** Register [listener] for every future (redacted) line; starts the capture if it's the first
     *  consumer. Close the returned handle to detach — the last detach stops the subprocess. */
    fun subscribe(listener: (String) -> Unit): AutoCloseable {
        synchronized(this) {
            if (closed) return AutoCloseable {}
            listeners.add(listener)
            if (run == null) start()
        }
        return AutoCloseable {
            synchronized(this) {
                listeners.remove(listener)
                if (listeners.isEmpty()) stop()
            }
        }
    }

    /** The buffered recent lines (already redacted) — instant backlog while capture is running. */
    fun snapshot(): List<String> = synchronized(ring) { ring.toList() }

    /** Backlog for a new viewer. Concurrent viewers that all observe an empty ring share one bounded
     * process rather than launching a dump apiece. */
    fun initialBacklog(lines: Int = DUMP_LINES): List<String> = snapshot().ifEmpty { dump(lines) }

    /** One-shot dump of the last [lines] log lines (redacted). A single flight is shared by all
     * callers, output is capped before decoding, and a wedged command is forcibly terminated. */
    fun dump(lines: Int = DUMP_LINES): List<String> {
        if (lines <= 0 || (lineStream != null && (streamCmd.isEmpty() || !processAvailable()))) return emptyList()
        val (flight, owner) = synchronized(this) {
            if (closed) return emptyList()
            val current = dumpFlight
            if (current != null) current to false
            else DumpFlight().also { dumpFlight = it } to true
        }
        if (!owner) return flight.await(dumpTimeoutMs + DUMP_CLEANUP_GRACE_MS)

        val cost = featureCosts.beginSynchronous(FeatureCostOperation.LOG_CAPTURE_BATCH)
        var outcome = FeatureCostOutcome.SUCCESS
        var result: List<String> = emptyList()
        try {
            result = runDump(lines)
        } catch (_: Exception) {
            outcome = FeatureCostOutcome.FAILURE
        } finally {
            featureCosts.finishSynchronous(
                FeatureCostOperation.LOG_CAPTURE_BATCH,
                cost,
                outcome = outcome,
                workUnits = result.size.toLong(),
                workBytes = if (featureCosts.recordingEnabled) {
                    boundedUtf8Bytes(result, CAPTURE_WORK_BYTES_MAX)
                } else {
                    0L
                },
            )
        }
        flight.complete(result)
        synchronized(this) {
            // Retain the completed flight while this cohort still has admitted viewers and the live
            // ring is empty. A viewer scheduled just after process exit still receives the same dump
            // instead of launching a second command in the response-before-subscribe gap.
            if (dumpFlight === flight && (activeViewers == 0 || synchronized(ring) { ring.isNotEmpty() })) {
                dumpFlight = null
            }
        }
        return result
    }

    private fun runDump(lines: Int): List<String> {
        val p = processStarter(dumpCmd(lines))
        val admitted = synchronized(this) {
            if (closed) false else {
                dumpProcess = p
                true
            }
        }
        if (!admitted) {
            runCatching { p.destroyForcibly() }
            return emptyList()
        }

        val output = ByteArrayOutputStream(min(dumpMaxBytes, 8 * 1024))
        val readerDone = CountDownLatch(1)
        val reader = thread(name = "ha-paneld-log-dump", isDaemon = true) {
            try {
                p.inputStream.use { input ->
                    val buffer = ByteArray(4 * 1024)
                    var remaining = dumpMaxBytes
                    while (remaining > 0) {
                        val read = input.read(buffer, 0, min(buffer.size, remaining))
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        remaining -= read
                    }
                    // Stop a producer as soon as the output budget is exhausted; otherwise it can
                    // block forever on a full pipe while the caller waits for process exit.
                    if (remaining == 0) runCatching { p.destroyForcibly() }
                }
            } catch (_: IOException) {
                // Closing and interrupting a blocked pipe is the normal bounded-cleanup path. In
                // particular, Android's logcat wrapper may surface that interruption as an
                // InterruptedIOException; never let a teardown race crash the application process.
            } finally {
                readerDone.countDown()
            }
        }

        try {
            if (!p.waitFor(dumpTimeoutMs, TimeUnit.MILLISECONDS)) {
                runCatching { p.destroy() }
                if (!p.waitFor(DUMP_DESTROY_GRACE_MS, TimeUnit.MILLISECONDS)) {
                    runCatching { p.destroyForcibly() }
                }
            }
            if (!readerDone.await(DUMP_READER_GRACE_MS, TimeUnit.MILLISECONDS)) {
                runCatching { p.inputStream.close() }
                reader.interrupt()
                readerDone.await(DUMP_READER_GRACE_MS, TimeUnit.MILLISECONDS)
            }
            val text = output.toByteArray().toString(Charsets.UTF_8)
            if (longLogcatRecords) {
                val records = ArrayList<String>()
                val reader = LongLogcatRecords { records += redact(it) }
                // A byte cap or timeout can end after any byte. Ignore the last unterminated
                // physical line and require the long-format blank footer before emitting a record.
                text.substringBeforeLast('\n', "").lineSequence().forEach(reader::accept)
                return records.takeLast(lines)
            }
            return text.lineSequence().map(::redact).toList()
                .dropLastWhile(String::isEmpty).takeLast(lines)
        } finally {
            synchronized(this) {
                if (dumpProcess === p) dumpProcess = null
            }
            runCatching { p.destroyForcibly() }
        }
    }

    /** Permanently close this service-owned capture and destroy a blocked streaming subprocess. */
    fun close() = synchronized(this) {
        if (closed) return@synchronized
        closed = true
        listeners.clear()
        dumpFlight?.complete(emptyList())
        runCatching { dumpProcess?.destroyForcibly() }
        dumpProcess = null
        stop()
    }

    private fun start() {
        val r = Run()
        run = r
        val producer = lineStream
        r.job = scope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    val records = if (longLogcatRecords) LongLogcatRecords { emit(r, redact(it)) } else null
                    if (producer != null && (streamCmd.isEmpty() || !processAvailable())) {
                        producer { line ->
                            if (records != null) records.accept(line) else emit(r, redact(line))
                        }
                    } else {
                        val p = processStarter(streamCmd)
                        if (!r.attach(p)) break
                        try {
                            p.inputStream.bufferedReader().use { reader ->
                                while (isActive) {
                                    val line = reader.readLine() ?: break
                                    if (records != null) records.accept(line)
                                    else if (featureCosts.recordingEnabled) captureAvailableBatch(r, reader, line)
                                    else emit(r, redact(line))
                                }
                            }
                        } finally {
                            r.detach(p)
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "capture restart: ${e.message}")
                }
                if (isActive) delay(BACKOFF_MS)
            }
        }
    }

    private fun stop() {
        run?.cancel()
        run = null
        synchronized(ring) { ring.clear() }
    }

    /**
     * Redact and fan out one bounded batch. The first blocking read happens before this method, then
     * only already-buffered lines are drained so the measurement never counts idle logcat wait time.
     */
    private fun captureAvailableBatch(source: Run, reader: java.io.BufferedReader, firstLine: String) {
        val cost = featureCosts.beginSynchronous(FeatureCostOperation.LOG_CAPTURE_BATCH)
        var outcome = FeatureCostOutcome.SUCCESS
        var workUnits = 0L
        var workBytes = 0L
        try {
            var line = firstLine
            while (true) {
                val redacted = redact(line)
                emit(source, redacted)
                workUnits++
                if (featureCosts.recordingEnabled) {
                    workBytes = boundedAdd(
                        workBytes,
                        boundedUtf8Bytes(redacted, CAPTURE_WORK_BYTES_MAX - workBytes),
                        CAPTURE_WORK_BYTES_MAX,
                    )
                }
                if (workUnits >= CAPTURE_BATCH_MAX || !reader.ready()) break
                line = reader.readLine() ?: break
            }
        } catch (failure: Exception) {
            outcome = FeatureCostOutcome.FAILURE
            throw failure
        } finally {
            featureCosts.finishSynchronous(
                FeatureCostOperation.LOG_CAPTURE_BATCH,
                cost,
                outcome = outcome,
                workUnits = workUnits,
                workBytes = workBytes,
            )
        }
    }

    internal fun emit(source: Run, line: String) {
        val targets = synchronized(this) {
            // A process can produce one final buffered line after destroy(). Never let that line cross a
            // stop→start boundary into the replacement generation's ring or listeners.
            if (closed || run !== source) return
            synchronized(ring) {
                if (ring.size >= RING_CAP) ring.removeFirst()
                ring.addLast(line)
            }
            if (dumpFlight?.isDone() == true) dumpFlight = null
            listeners.toList()
        }
        for (listener in targets) runCatching { listener(line) }
    }

    companion object {
        private const val TAG = "ha-paneld/logcap"
        private const val RING_CAP = 400
        private const val DUMP_LINES = 300
        private const val BACKOFF_MS = 2_000L
        private const val MAX_VIEWERS = 4
        private const val DUMP_TIMEOUT_MS = 3_000L
        private const val DUMP_MAX_BYTES = 256 * 1024
        private const val DUMP_DESTROY_GRACE_MS = 100L
        private const val DUMP_READER_GRACE_MS = 250L
        private const val DUMP_CLEANUP_GRACE_MS = 1_000L
        private const val CAPTURE_BATCH_MAX = 64L
        private const val CAPTURE_WORK_BYTES_MAX = 4L * 1024 * 1024

        /** Own-process logcat — no `READ_LOGS` / root. `-T 1` starts at "now" so a restart doesn't
         *  replay the whole ring buffer into the shipper. */
        fun app(scope: CoroutineScope) = LogCapture(
            scope,
            listOf("logcat", "-v", "threadtime", "-T", "1", "*:V"),
            { n -> listOf("logcat", "-v", "threadtime", "-d", "-t", "$n", "*:V") },
        )

        /** Full system logcat. A helper producer is used only when root is unavailable to this app.
         *  `printable` escapes message newlines so only the `long` footer has a blank physical line;
         *  embedded blank lines in exceptions cannot be mistaken for record boundaries. */
        fun system(
            scope: CoroutineScope,
            suForm: SuForm,
            helperLines: (suspend (emit: (String) -> Unit) -> Unit)? = null,
            rootAvailable: () -> Boolean = { true },
            processStarter: (List<String>) -> Process = { command ->
                ProcessBuilder(command).redirectErrorStream(true).start()
            },
        ): LogCapture {
            val su = when (suForm) {
                SuForm.TOOLBOX -> listOf("su", "-c")
                SuForm.ANDROID -> listOf("su", "0", "sh", "-c")
                SuForm.NONE -> emptyList()
            }
            return LogCapture(
                scope,
                su + "logcat -b all -v long -v epoch -v printable -T 1 '*:V'",
                { n -> su + "logcat -b all -v long -v epoch -v printable -d -t $n '*:V'" },
                processStarter = processStarter,
                lineStream = helperLines,
                longLogcatRecords = true,
                processAvailable = { su.isNotEmpty() && rootAvailable() },
            )
        }

        /** Dashboard WebView console over the CDP relay. [enabled] keeps it idle unless log shipping is
         *  configured; it never starts the relay itself. */
        fun webView(scope: CoroutineScope, enabled: () -> Boolean) = LogCapture(
            scope,
            streamCmd = emptyList(),
            dumpCmd = { emptyList() },
            lineStream = WebViewConsoleStream(enabled)::run,
        )

        // Conservative redaction — strip the obvious secret shapes before a line reaches ANY consumer
        // (remote sink or browser view).
        private val REDACTIONS: List<Pair<Regex, String>> = listOf(
            Regex("""(?i)(authorization:\s*bearer\s+)\S+""") to "$1***",
            // Value bounded by [^\s&]+ (not \S+) so it stops at the next URL query param / whitespace,
            // redacting only the secret rather than eating the rest of the line.
            Regex("""(?i)\b(password|passwd|pwd|secret|api[_-]?key|access[_-]?token|token)(["'=:\s]+)[^\s&]+""")
                to "$1$2***",
            // Home Assistant long-lived tokens / JWTs.
            Regex("""\beyJ[A-Za-z0-9_\-]{10,}\.[A-Za-z0-9_\-]{10,}\.[A-Za-z0-9_\-]{6,}""") to "***jwt***",
            // Secrets carried in URL query strings.
            Regex("""(?i)([?&](?:token|auth|access_token|api_key|key|password)=)[^&\s"]+""") to "$1***",
            // Credentials embedded in a URL's authority (`scheme://user:pass@host`).
            Regex("""(?i)\b([a-z][a-z0-9+.\-]*://)[^/\s:@]+:[^/\s@]+@""") to "$1***@",
        )

        /** Apply [REDACTIONS] in sequence. Public for unit testing. */
        fun redact(line: String): String {
            var s = line
            for ((re, repl) in REDACTIONS) s = re.replace(s, repl)
            return s
        }
    }
}

/** `logcat -v long -v printable` separates complete native entries with an empty line. */
private class LongLogcatRecords(private val emit: (String) -> Unit) {
    private val entry = StringBuilder()

    fun accept(line: String) {
        if (line.startsWith("--------- beginning of ")) return // logcat buffer banner, not an entry
        if (line.isEmpty()) {
            finish()
        } else {
            if (entry.isNotEmpty()) entry.append('\n')
            entry.append(line)
        }
    }

    fun finish() {
        if (entry.isNotEmpty()) {
            emit(decodePrintableLogcatRecord(entry.toString()))
            entry.setLength(0)
        }
    }
}

/** Decode AOSP printable's C escapes in the message, before whitespace-sensitive redaction. */
private fun decodePrintableLogcatRecord(record: String): String {
    val start = record.indexOf('\n') + 1
    if (start == 0) return record
    val decoded = StringBuilder(record.length).append(record, 0, start)
    var index = start
    while (index < record.length) {
        // Some Android logcat builds print embedded newlines as hex, not C-style \n.
        if (record.regionMatches(index, "\\x0A", 0, 4, ignoreCase = true)) {
            decoded.append('\n')
            index += 4
            continue
        }
        val escaped = if (record[index] == '\\') record.getOrNull(index + 1) else null
        val value = when (escaped) {
            '\\' -> '\\'
            'a' -> '\u0007'
            'b' -> '\b'
            't' -> '\t'
            'n' -> '\n'
            'v' -> '\u000B'
            'f' -> '\u000C'
            'r' -> '\r'
            else -> null // Retain other escapes as their lossless printable text.
        }
        if (value != null) {
            decoded.append(value)
            index += 2
        } else {
            decoded.append(record[index++])
        }
    }
    return decoded.toString()
}

/** Exact UTF-8 length without allocating an encoded copy; stops once [limit] bytes are reached. */
internal fun boundedUtf8Bytes(value: String, limit: Long): Long {
    if (limit <= 0L) return 0L
    var bytes = 0L
    var index = 0
    while (index < value.length && bytes < limit) {
        val ch = value[index]
        val width = when {
            ch.code < 0x80 -> 1
            ch.code < 0x800 -> 2
            ch.isHighSurrogate() && index + 1 < value.length && value[index + 1].isLowSurrogate() -> {
                index++
                4
            }
            ch.isSurrogate() -> 1
            else -> 3
        }
        bytes = boundedAdd(bytes, width.toLong(), limit)
        index++
    }
    return bytes
}

internal fun boundedUtf8Bytes(values: Iterable<String>, limit: Long): Long {
    var bytes = 0L
    for (value in values) {
        bytes = boundedAdd(bytes, boundedUtf8Bytes(value, limit - bytes), limit)
        if (bytes >= limit) break
    }
    return bytes
}

internal fun boundedAdd(current: Long, delta: Long, limit: Long): Long =
    if (current >= limit || delta >= limit - current) limit else current + delta
