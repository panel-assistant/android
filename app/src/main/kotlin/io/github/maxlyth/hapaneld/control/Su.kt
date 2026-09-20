package io.github.maxlyth.hapaneld.control

import android.util.Log
import io.github.maxlyth.hapaneld.platform.RootRunOutcome
import io.github.maxlyth.hapaneld.platform.RootShell
import io.github.maxlyth.hapaneld.util.BoundedStreams
import io.github.maxlyth.hapaneld.util.Cached
import io.github.maxlyth.hapaneld.util.BoundedLaunchGate
import io.github.maxlyth.hapaneld.util.MonotonicDeadline
import io.github.maxlyth.hapaneld.util.runBoundedLaunch
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.Writer
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal enum class SuExecFailure {
    FIRST_MISSING,
    ALREADY_MISSING,
    FIRST_DENIED,
    ALREADY_DENIED,
    OTHER;

    /** True when this launch proved root structurally unusable, so further execs are pointless. */
    val unusable: Boolean
        get() = this == FIRST_MISSING || this == ALREADY_MISSING ||
            this == FIRST_DENIED || this == ALREADY_DENIED
}

/**
 * Decide what one root attempt proved, separated from the exec machinery so it can be tested without a
 * device — this is the judgement that decides whether a setting may be presented as unappliable.
 *
 * [launchCreatedNoProcess] covers every launch-level refusal, not only a missing file — see
 * [SuExecFailureCache] for which of those are decidable enough to latch. [rootKnownUnusable] is that
 * latch's verdict from an earlier call. A command that ran and exited non-zero is a root manager saying
 * no, which can change on the next attempt and must stay retryable.
 */
internal fun classifyRootRun(
    ran: Boolean,
    launchCreatedNoProcess: Boolean,
    rootKnownUnusable: Boolean,
): RootRunOutcome = when {
    ran -> RootRunOutcome.RAN_OK
    launchCreatedNoProcess || rootKnownUnusable -> RootRunOutcome.NO_LAUNCH
    else -> RootRunOutcome.RAN_FAILED
}

/** One persistent-shell request: [cmd] (its stderr suppressed), then the sentinel plus the exit code so
 *  both stdout and status are recoverable over the single pipe. The `{ …; }` group tolerates
 *  multi-statement (and even multi-line) commands. */
internal fun persistentShellRequest(cmd: String, sentinel: String): String =
    "{ $cmd ; } 2>/dev/null; echo $sentinel:$?\n"

/**
 * Read one reply to [persistentShellRequest]: (stdout, exit code). A command whose stdout lacks a trailing
 * newline (`printf ready`) puts its last fragment on the sentinel's own line, so a line that ENDS with
 * `<sentinel>:<exit code>` terminates the reply and the fragment before it is kept unterminated, as a
 * one-shot exec would return it. Matching only at line start missed it, and the shell waited out its
 * whole timeout before falling back to a one-shot (Issue #93: 5.15 s on every GPIO preparation).
 */
internal fun readPersistentShellReply(stdout: BufferedReader, sentinel: String): Pair<String, Int> {
    val out = StringBuilder()
    while (true) {
        val line = stdout.readLine() ?: throw IOException("persistent su shell closed")
        val at = line.lastIndexOf(sentinel)
        val rc = if (at < 0) null else line.substring(at + sentinel.length).removePrefix(":").toIntOrNull()
        if (rc != null) return out.append(line, 0, at).toString() to rc
        out.append(line).append('\n')
    }
}

/**
 * The standard places an Android `su` lives, used when the process has no `PATH` of its own.
 * `Runtime.exec` resolves the bare name `su` the way `execvp` does — it walks every `PATH` entry and an
 * EACCES in one directory does not end the search — so a decidable answer has to walk the same list.
 */
internal val DEFAULT_SU_SEARCH_PATH = listOf("/sbin", "/system/sbin", "/system/bin", "/system/xbin", "/vendor/bin")

internal fun suSearchPath(pathVariable: String? = System.getenv("PATH")): List<String> =
    pathVariable?.split(':')?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() } ?: DEFAULT_SU_SEARCH_PATH

/**
 * True when a `su` exists on the search path and **none** that exists may be executed by this uid.
 *
 * This is the decidable half of a launch refusal: a supported panel ships `su` mode 4750 `root:shell`, so an app
 * uid outside that group is refused EACCES before any retry can matter. [File.canExecute] is `access(X_OK)`,
 * the same permission check the kernel makes at exec — DAC mode and owning group, and SELinux `execute` on
 * the file — so it answers for this uid exactly as the launch will. What it cannot see is a refusal raised
 * after that check passes, such as a denied domain transition at execve; that stays an ordinary refusal and
 * stays retryable, which is the conservative way round.
 */
internal fun suDeniedToCaller(
    searchPath: List<String> = suSearchPath(),
    exists: (String) -> Boolean = { File(it).exists() },
    canExecute: (String) -> Boolean = { File(it).canExecute() },
): Boolean {
    var found = false
    for (directory in searchPath) {
        val candidate = "$directory/su"
        if (!exists(candidate)) continue
        if (canExecute(candidate)) return false
        found = true
    }
    return found
}

/**
 * Process-lifetime cache for launch failures that prove root structurally unusable on this device.
 *
 * Two classes latch, and they latch differently:
 *
 * - **Missing binary** (ENOENT). Unchanged since it was introduced: once seen it suppresses every later
 *   exec for the process lifetime, because a `su` that is not there cannot be launched.
 * - **Denied to this caller** (EACCES *and* [suDeniedToCaller]). The permission on an existing `su` refuses
 *   exec to the app uid, so every retry is provably futile — one supported panel logged such a refusal
 *   roughly every 25 seconds, each with a stack. This latch is re-tested rather than permanent:
 *   [shouldSkipExec] re-runs the same `access(X_OK)` sweep before skipping, which costs no fork and no log,
 *   and clears the latch the moment an executable `su` appears on the path. So a panel that gains root
 *   during its uptime still finds it.
 *
 * Everything else is [SuExecFailure.OTHER] and is never cached: a refusal raised after the permission check
 * passes, such as a denied domain transition at execve, can change without the file changing. A command that
 * ran and exited non-zero never reaches here at all — a root manager saying no, or a prompt nobody has
 * answered, is RAN_FAILED and always retryable.
 */
internal class SuExecFailureCache(
    private val deniedToCaller: () -> Boolean = { suDeniedToCaller() },
) {
    private val missing = AtomicBoolean(false)
    private val denied = AtomicBoolean(false)

    fun shouldSkipExec(): Boolean {
        if (missing.get()) return true
        if (!denied.get()) return false
        if (deniedToCaller()) return true
        denied.set(false)               // an executable su appeared during this boot; stop skipping
        return false
    }

    fun record(error: Exception): SuExecFailure {
        if (isMissingBinary(error)) {
            return if (missing.compareAndSet(false, true)) {
                SuExecFailure.FIRST_MISSING
            } else {
                SuExecFailure.ALREADY_MISSING
            }
        }
        if (isPermissionDenied(error) && deniedToCaller()) {
            return if (denied.compareAndSet(false, true)) {
                SuExecFailure.FIRST_DENIED
            } else {
                SuExecFailure.ALREADY_DENIED
            }
        }
        return SuExecFailure.OTHER
    }

    private fun isPermissionDenied(error: Exception): Boolean =
        messages(error).any { message ->
            message.contains("Permission denied", ignoreCase = true) ||
                message.contains("EACCES", ignoreCase = true) ||
                EACCES_ERROR_NUMBER.containsMatchIn(message)
        }

    private fun isMissingBinary(error: Exception): Boolean =
        messages(error).any { message ->
            message.contains("No such file or directory", ignoreCase = true) ||
                message.contains("ENOENT", ignoreCase = true) ||
                ENOENT_ERROR_NUMBER.containsMatchIn(message)
        }

    private fun messages(error: Exception): Sequence<String> =
        generateSequence(error as Throwable?) { it.cause }
            .filterIsInstance<IOException>()
            .mapNotNull { it.message }

    private companion object {
        val ENOENT_ERROR_NUMBER = Regex("(?:^|\\D)error=2(?:\\D|$)", RegexOption.IGNORE_CASE)
        val EACCES_ERROR_NUMBER = Regex("(?:^|\\D)error=13(?:\\D|$)", RegexOption.IGNORE_CASE)
    }
}

/**
 * One detailed line per failure class per boot, then counts.
 *
 * The volume this exists to remove was not one loud event: it was the same launch refusal re-logged with a
 * full stack roughly every 25 seconds. A class is logged in full the first time it is seen, which keeps the
 * diagnostic, and after that only a periodic count, which keeps the evidence that it is still happening. A
 * genuinely new class has its own signature, so it is never suppressed by a class already seen.
 */
internal class SuFailureLogThrottle(
    private val summaryIntervalMs: Long = SUMMARY_INTERVAL_MS,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    sealed interface Decision {
        /** First of its class this boot: log the message with its stack. */
        object Detailed : Decision

        /** Already reported and not yet due a count: log nothing. */
        object Silent : Decision

        /** Due a count: log [suppressed] occurrences since the last line, without a stack. */
        data class Summary(val suppressed: Long) : Decision
    }

    private class Seen(var suppressed: Long = 0L, var lastLineMs: Long)

    private val seen = HashMap<String, Seen>()

    @Synchronized
    fun onFailure(signature: String): Decision {
        val now = nowMs()
        val state = seen[signature]
        if (state == null) {
            seen[signature] = Seen(lastLineMs = now)
            return Decision.Detailed
        }
        state.suppressed++
        if (now - state.lastLineMs < summaryIntervalMs) return Decision.Silent
        state.lastLineMs = now
        return Decision.Summary(state.suppressed).also { state.suppressed = 0L }
    }

    private companion object {
        const val SUMMARY_INTERVAL_MS = 3_600_000L
    }
}

/**
 * What distinguishes one failure class from another for [SuFailureLogThrottle].
 *
 * A latched class is one signature whatever operation hit it — the point is that the device cannot run
 * `su` at all, and repeating that per call site is the noise. An uncached failure keeps its exception type
 * and errno, so a different failure appearing later is a new class and still gets its own full line.
 */
internal fun suFailureSignature(outcome: SuExecFailure, error: Exception): String = when {
    outcome == SuExecFailure.FIRST_MISSING || outcome == SuExecFailure.ALREADY_MISSING -> "missing"
    outcome == SuExecFailure.FIRST_DENIED || outcome == SuExecFailure.ALREADY_DENIED -> "denied"
    else -> "other:${error.javaClass.name}:${errorNumber(error) ?: "none"}"
}

private val ERROR_NUMBER = Regex("error=(\\d+)")

private fun errorNumber(error: Exception): String? =
    generateSequence(error as Throwable?) { it.cause }
        .mapNotNull { it.message }
        .mapNotNull { ERROR_NUMBER.find(it)?.groupValues?.get(1) }
        .firstOrNull()

/**
 * Root command execution.
 *
 * Two su syntaxes across the fleet: toolbox `su -c '<cmd>'` (Sonoff PX30) and Android `su 0 sh -c
 * '<cmd>'` (Tuya TPA10 userdebug). A working form is cached; a negative probe is retried by later
 * one-shot operations because the root manager may become ready after boot. Only a launch failure that
 * proves root structurally unusable is cached ([SuExecFailureCache]). Graceful: returns false/null if no su works (a panel
 * without root just loses the root-gated capabilities).
 *
 * **Persistent shell (0.8.3).** [run]/[runOutput] are piped into a single long-lived root shell rather
 * than forking `su` per call. A fresh `su` fork+auth costs ~200–300 ms, which made the navbar's
 * Back/Recents (root `input keyevent`) feel unresponsive. The shell is opened lazily, reused, and each
 * command is wrapped with a sentinel so its stdout and exit code are recovered over the one pipe. If the
 * shell is absent, has died, or a command wedges past [CMD_TIMEOUT_MS], it transparently falls back to a
 * per-call `su` exec — **never worse than the pre-0.8.3 behaviour**. All entry points are `@Synchronized`,
 * so the single pipe is strictly one command at a time.
 *
 * [fireAndForget] (e.g. `reboot`) and the form probe always use one-shot execs — a `reboot` must not be
 * fed into the shared shell, and the probe is what discovers/caches the form the shell is opened with.
 */
object Su : RootShell {
    private const val TAG = "ha-paneld/su"
    private const val SENTINEL = "__hapaneld_done__"
    private const val CMD_TIMEOUT_MS = 5000L
    private const val AVAILABILITY_TTL_MS = 60_000L
    private const val SKIPPED_EXEC_SIGNATURE = "skipped-exec"

    // A successful dialect is sticky. NONE_LAST_PROBE records diagnostics only; it must not suppress
    // later one-shot probes because root-manager readiness can change during the process lifetime.
    private val formState = SuFormState()

    private var shell: ShellHandle? = null
    private val execFailureCache = SuExecFailureCache()
    private val failureLog = SuFailureLogThrottle()
    /** Set by any launch in the current [runClassified] call that started no child process. Reset per
     *  call and never latched, so a refusal this boot cannot disable root for the process lifetime. */
    @Volatile private var launchCreatedNoProcess = false
    private val oneShotLaunchGate = BoundedLaunchGate()

    private class ShellHandle(
        val process: Process,
        val stdin: Writer,
        val stdout: BufferedReader,
        val io: ExecutorService,
    )

    private fun argvOneShot(f: Int, cmd: String): Array<String> = when (f) {
        0 -> arrayOf("su", "-c", cmd)
        else -> arrayOf("su", "0", "sh", "-c", cmd)
    }

    private fun argvShell(f: Int): Array<String> = when (f) {
        0 -> arrayOf("su")
        else -> arrayOf("su", "0", "sh")
    }

    /**
     * Try each candidate su dialect until [attempt] returns an accepted non-null result; the caller
     * defines acceptance, and streamed stdin intentionally accepts a completed non-zero process as
     * dialect proof. The winning dialect becomes sticky. If every candidate fails and none was ever
     * proven, record the negative probe so the persistent shell stops trying (a later one-shot still
     * re-probes). Null when no dialect works. This is the one home for the sticky-form and exhaustion
     * invariant that the one-shot entry points previously re-implemented inline.
     */
    private fun <T : Any> overForms(attempt: (Int) -> T?): T? {
        SuFormPolicy.firstAccepted(formState.current(), attempt)
            ?.let { formState.recordSuccess(it.form); return it.value }
        formState.recordExhaustion()
        return null
    }

    /** Run [cmd] as root, waiting for completion. Returns true on exit 0. */
    @Synchronized
    override fun run(cmd: String): Boolean {
        piped(cmd)?.let { return it.second == 0 }
        return oneShotRun(cmd)
    }

    /** Resolve the harmless su dialect first, then execute the side-effecting command exactly once. */
    @Synchronized
    override fun runSingleAttempt(cmd: String, timeoutMs: Long): Boolean {
        if (!formState.working()) {
            oneShotRun("true")
            if (!formState.working()) return false
        }
        val form = formState.current()
        return runBounded("run-single", argvOneShot(form, cmd), timeoutMs) { it.waitFor() } == 0
    }

    /** Run [cmd] as root and return its stdout, or null if no su form works / it exits non-zero. */
    @Synchronized
    override fun runOutput(cmd: String): String? {
        piped(cmd)?.let { return if (it.second == 0) it.first else null }
        return oneShotOutput(cmd)
    }

    /**
     * Run [cmd] and report whether a root process was ever created.
     *
     * Deliberately NOT derived from [SuExecFailureCache]: that cache answers whether root is unusable on
     * this device at all, which is a durable property, while this flag answers what this one call's own
     * launches did and never latches. Both feed [classifyRootRun], which still reports the true outcome
     * of this command whatever the logging did with it.
     */
    @Synchronized
    override fun runClassified(cmd: String): RootRunOutcome {
        launchCreatedNoProcess = false
        val ran = run(cmd)
        return classifyRootRun(
            ran = ran,
            launchCreatedNoProcess = launchCreatedNoProcess,
            rootKnownUnusable = execFailureCache.shouldSkipExec(),
        )
    }

    /** Fire [cmd] as root without waiting (for commands like `reboot` that kill the process). Always a
     *  one-shot — never sent into the shared persistent shell (it would take the shell down with it). */
    override fun fireAndForget(cmd: String): Boolean {
        if (execFailureCache.shouldSkipExec()) {
            noteSkippedExec()
            return false
        }
        val forms = formState.candidates()
        for (f in forms) {
            try {
                Runtime.getRuntime().exec(argvOneShot(f, cmd))
                return true
            } catch (e: Exception) {
                if (logExecFailure("fire form $f", e)) return false
            }
        }
        return false
    }

    /** Run [cmd] as root and return its raw stdout **bytes** (one-shot — the persistent shell's sentinel
     *  protocol is text-only, so binary output like `screencap -p` needs a dedicated exec). Not
     *  synchronized: it doesn't touch the shared shell, so a screenshot won't stall navbar root actions.
     *  Null on failure / no su. */
    override fun runBytes(cmd: String): ByteArray? = runBytesBounded(cmd, Long.MAX_VALUE - 1L)

    override fun runBytesBounded(cmd: String, maxBytes: Long): ByteArray? = overForms { f ->
        runBounded("bytes", argvOneShot(f, cmd)) { p ->
            // binary-safe; drain before waitFor to avoid a full pipe deadlock. The bounded reader
            // probes one byte beyond the ceiling, then runBounded kills the producer on overflow.
            val b = BoundedStreams.readBytes(p.inputStream, maxBytes)
            if (p.waitFor() == 0) b else null
        }
    }

    /** Stream binary stdout directly to [target] under a hard byte ceiling. This is for larger root
     * artifacts such as Companion databases where a ByteArray would double peak heap before staging.
     * A failed/non-zero/oversized command leaves no partial file. */
    fun runToFileBounded(cmd: String, target: File, maxBytes: Long, timeoutMs: Long = 30_000L): Long? {
        val written = overForms { f ->
            runBounded("file", argvOneShot(f, cmd), timeoutMs) { p ->
                val count = target.outputStream().use { output ->
                    BoundedStreams.copy(p.inputStream, output, maxBytes)
                }
                if (p.waitFor() == 0) count else null
            }.also { if (it == null) target.delete() }   // a failed dialect leaves no partial file
        }
        if (written == null) target.delete()
        return written
    }

    /** One-shot, bounded text command for diagnostics. Unlike [runOutput], this never waits behind the
     * synchronized persistent control shell, so a slow perf probe cannot add seconds of latency to a
     * navbar/screen/hardware command. Both possible `su` dialects share one [timeoutMs] deadline. */
    override fun runOutputIsolatedBounded(cmd: String, maxBytes: Long, timeoutMs: Long): String? {
        val deadline = MonotonicDeadline(timeoutMs)
        val selected = SuFormPolicy.firstSuccessfulWithin(formState.current(), deadline) { candidate, sharedDeadline ->
            runBounded("isolated-out", argvOneShot(candidate, cmd), sharedDeadline) { p ->
                val bytes = BoundedStreams.readBytes(p.inputStream, maxBytes)
                if (p.waitFor() == 0) String(bytes, Charsets.UTF_8) else null
            }
        }
        if (selected != null) {
            formState.recordSuccess(selected.form)
            return selected.value
        }
        formState.recordExhaustion()
        return null
    }

    fun availableIsolated(timeoutMs: Long = CMD_TIMEOUT_MS): Boolean =
        runOutputIsolatedBounded("true", 1L, timeoutMs) != null

    override fun available(): Boolean = run("true")

    // Every render-time root gate and privileged-route observation share one bounded, single-flight
    // probe here rather than each layer (formerly the HTTP server) wrapping [availableIsolated] in its
    // own TTL cache. The bounded window avoids repeated expensive probes while allowing readiness changes
    // to be observed; isolated so the probe never waits behind the synchronized persistent control shell.
    private val availabilityCache = newAvailabilityCache()

    internal fun newAvailabilityCache(
        ttlMs: Long = AVAILABILITY_TTL_MS,
        nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
        probe: () -> Boolean = ::availableIsolated,
    ): Cached<Boolean> = Cached(ttlMs, nowMs, probe)

    /** Cached [availableIsolated]: at most one root probe per TTL window, single-flight across callers. */
    fun availableCachedIsolated(): Boolean = availabilityCache.get()

    // --- persistent shell ---

    /** Send [cmd] through the persistent root shell; returns (stdout, exitCode), or null if the shell
     *  path is unavailable/broke — the caller then falls back to a one-shot exec. */
    private fun piped(cmd: String): Pair<String, Int>? {
        if (formState.current() == SuFormPolicy.NONE_LAST_PROBE) return null
        val sh = ensureShell() ?: return null
        return try {
            sh.io.submit(Callable { transact(sh, cmd) }).get(CMD_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            // timeout / EOF / broken pipe / desync — drop the shell; the caller falls back to one-shot.
            Log.d(TAG, "persistent shell transact failed; falling back to one-shot", e)
            closeShell()
            null
        }
    }

    /** One round-trip on the shared shell. */
    private fun transact(sh: ShellHandle, cmd: String): Pair<String, Int> {
        sh.stdin.write(persistentShellRequest(cmd, SENTINEL))
        sh.stdin.flush()
        return readPersistentShellReply(sh.stdout, SENTINEL)
    }

    private fun ensureShell(): ShellHandle? {
        shell?.let { if (it.process.isAlive) return it }
        closeShell()
        if (!formState.working()) {
            oneShotRun("true")              // probe + cache a working form, or record a negative probe
            if (!formState.working()) return null
        }
        return try {
            val p = Runtime.getRuntime().exec(argvShell(formState.current()))
            drainStderr(p)
            ShellHandle(
                process = p,
                stdin = p.outputStream.bufferedWriter(),
                stdout = p.inputStream.bufferedReader(),
                io = Executors.newSingleThreadExecutor { r ->
                    Thread(r, "ha-paneld-su").apply { isDaemon = true }
                },
            ).also { shell = it }
        } catch (e: Exception) {
            Log.d(TAG, "could not open persistent su shell", e)
            null
        }
    }

    private fun closeShell() {
        shell?.let {
            runCatching { it.stdin.close() }
            runCatching { it.process.destroy() }
            runCatching { it.io.shutdownNow() }
        }
        shell = null
    }

    /** Discard the shell's own stderr in the background so a write there can't fill the pipe and wedge it. */
    private fun drainStderr(p: Process) {
        Thread {
            runCatching { p.errorStream.bufferedReader().forEachLine { /* discard */ } }
        }.apply { isDaemon = true; name = "ha-paneld-su-err" }.start()
    }

    // --- one-shot fallback (pre-0.8.3 behaviour; also the form probe) ---

    /**
     * Run a one-shot `su` [argv], including process launch and [reader], under one monotonic deadline.
     * Runtime.exec runs on the throwaway worker; if a timed-out launch returns late, its child is killed
     * before the reader can run.
     */
    private fun <T> runBounded(label: String, argv: Array<String>, timeoutMs: Long = CMD_TIMEOUT_MS, reader: (Process) -> T): T? =
        runBounded(label, argv, MonotonicDeadline(timeoutMs), reader)

    private fun <T> runBounded(
        label: String,
        argv: Array<String>,
        deadline: MonotonicDeadline,
        reader: (Process) -> T,
    ): T? {
        if (execFailureCache.shouldSkipExec()) {
            noteSkippedExec()
            return null
        }
        return runBoundedLaunch(
            deadline = deadline,
            threadName = "ha-paneld-su-1shot",
            gate = oneShotLaunchGate,
            launch = {
                try {
                    Runtime.getRuntime().exec(argv)
                } catch (error: Exception) {
                    logExecFailure("$label exec failed: ${argv.joinToString(" ")}", error)
                    null
                }
            },
            destroy = { process -> process.destroyForcibly() },
            consume = reader,
        )
    }

    /**
     * Log a launch failure at most once per class per boot, then as periodic counts, and report whether
     * `su` is structurally unusable on this device (absent, or refused to this uid by its own mode).
     */
    private fun logExecFailure(operation: String, error: Exception): Boolean {
        // Runtime.exec throws only when no child was started, so reaching here at all means this launch
        // produced no process — whether the binary is missing or this app may not execute it.
        launchCreatedNoProcess = true
        val outcome = execFailureCache.record(error)
        when (val decision = failureLog.onFailure(suFailureSignature(outcome, error))) {
            SuFailureLogThrottle.Decision.Detailed -> when (outcome) {
                SuExecFailure.FIRST_MISSING, SuExecFailure.ALREADY_MISSING ->
                    Log.d(TAG, "su binary not found; root-only operations are unavailable")
                SuExecFailure.FIRST_DENIED, SuExecFailure.ALREADY_DENIED ->
                    Log.d(TAG, "su exists but this app's uid may not execute it; root-only operations are unavailable", error)
                SuExecFailure.OTHER -> Log.d(TAG, "su $operation", error)
            }
            SuFailureLogThrottle.Decision.Silent -> Unit
            is SuFailureLogThrottle.Decision.Summary ->
                Log.d(TAG, "su launch refused ${decision.suppressed} more times since the last line")
        }
        return outcome.unusable
    }

    /** Count execs skipped because root is known unusable, and report the total at most once an hour. */
    private fun noteSkippedExec() {
        val decision = failureLog.onFailure(SKIPPED_EXEC_SIGNATURE)
        if (decision is SuFailureLogThrottle.Decision.Summary) {
            Log.d(TAG, "su unusable; skipped ${decision.suppressed} root attempts since the last line")
        }
    }

    private fun oneShotRun(cmd: String): Boolean =
        overForms { f -> runBounded("run", argvOneShot(f, cmd)) { it.waitFor() }?.takeIf { it == 0 } } != null

    /** Long-running one-shot su [cmd] (exit 0 → true), bounded to [timeoutMs]. Always a one-shot — a
     *  minutes-long op (e.g. staging + installing a large APK) must NOT occupy the shared persistent
     *  shell, whose sentinel protocol is bounded to the short [CMD_TIMEOUT_MS]. */
    fun runLong(cmd: String, timeoutMs: Long): Boolean =
        overForms { f ->
            runBounded("run-long", argvOneShot(f, cmd), timeoutMs) { it.waitFor() }?.takeIf { it == 0 }
        } != null

    private data class StdinResult(val stdout: String, val exitCode: Int)

    private fun runWithStdinLongResult(cmd: String, input: java.io.File, timeoutMs: Long): StdinResult? =
        overForms { f ->
            runBounded("stdin-long", argvOneShot(f, cmd), timeoutMs) { p ->
                val feeder = Thread {
                    runCatching { p.outputStream.use { os -> input.inputStream().use { it.copyTo(os) } } }
                }.apply { isDaemon = true; start() }
                val text = p.inputStream.bufferedReader().readText()  // read before waitFor (avoid deadlock)
                feeder.join(timeoutMs)
                StdinResult(text, p.waitFor())
            }
        }

    /** Long one-shot command with streamed stdin. Returns stdout even when the child exits non-zero. */
    fun runWithStdinLong(cmd: String, input: java.io.File, timeoutMs: Long): String? =
        runWithStdinLongResult(cmd, input, timeoutMs)?.stdout

    /** Checked variant for data restoration: non-zero exit is failure even when the command wrote stdout. */
    fun runWithStdinLongChecked(cmd: String, input: java.io.File, timeoutMs: Long): String? =
        runWithStdinLongResult(cmd, input, timeoutMs)?.takeIf { it.exitCode == 0 }?.stdout

    /** Long-running one-shot su [cmd] returning stdout (null on non-zero/failure), bounded to [timeoutMs]. */
    fun runOutputLong(cmd: String, timeoutMs: Long): String? = overForms { f ->
        runBounded("out-long", argvOneShot(f, cmd), timeoutMs) { p ->
            val text = p.inputStream.bufferedReader().readText()
            if (p.waitFor() == 0) text else null
        }
    }

    private fun oneShotOutput(cmd: String): String? = overForms { f ->
        runBounded("out", argvOneShot(f, cmd)) { p ->
            val text = p.inputStream.bufferedReader().readText() // read before waitFor (avoid deadlock)
            if (p.waitFor() == 0) text else null
        }
    }
}
