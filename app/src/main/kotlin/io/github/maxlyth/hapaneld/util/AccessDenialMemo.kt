package io.github.maxlyth.hapaneld.util

import android.util.Log
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.Paths

/**
 * Remembers app-UID reads that SELinux has proven denied, so a periodic poll stops repeating them.
 *
 * Every denied `open` writes a kernel `avc: denied` audit line (a denied property read also writes a
 * libc `Access denied finding property` line) whether or not the app logs anything, so on a panel
 * whose policy forbids a read the only way to stop that log volume is to stop issuing the read.
 *
 * Only a proven denial is remembered, never an empty or failed result: an unset property or a
 * transient failure keeps being read on every poll, so a value that appears later is still seen.
 * A remembered denial makes [read] return null, which is what the denied read itself yielded, so
 * callers that treat null as unknown (and fail closed) observe no change in meaning.
 *
 * A probe verdict, either way, holds for [ttlMs] and until [invalidate]; SELinux policy for the app
 * domain changes only with an OS update, which restarts the process, so the TTL is a backstop against
 * trusting a verdict forever rather than a signal the capability is expected to change.
 */
internal class AccessDenialMemo(
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val log: (String) -> Unit = { Log.i(TAG, it) },
) {
    init {
        require(ttlMs > 0L)
    }

    private class Verdict(val denied: Boolean, val atMs: Long)

    private val verdicts = HashMap<String, Verdict>()
    private val logged = HashSet<String>()

    /**
     * Run [read] unless [key] holds a live denial. When [suspect] flags the result, [probeDenied]
     * classifies it once per verdict lifetime: true only for a proven access denial.
     */
    fun <T> read(
        key: String,
        what: String,
        suspect: (T?) -> Boolean = { it == null },
        probeDenied: () -> Boolean,
        read: () -> T?,
    ): T? {
        if (freshVerdict(key)?.denied == true) return null
        val value = read()
        if (!suspect(value) || freshVerdict(key) != null) return value
        val denied = runCatching(probeDenied).getOrDefault(false)
        record(key, what, denied)
        return if (denied) null else value
    }

    /** Forget every verdict, so the next read of each key is issued and classified again. */
    @Synchronized
    fun invalidate() {
        verdicts.clear()
    }

    private var lastSignal: Any? = null

    /** Report the current privilege state; any change from the last report is a change signal that
     *  invalidates every verdict, since a helper or permission change may alter what the app can read. */
    @Synchronized
    fun onCapabilitySignal(signal: Any) {
        if (lastSignal != null && lastSignal != signal) invalidate()
        lastSignal = signal
    }

    @Synchronized
    private fun freshVerdict(key: String): Verdict? {
        val verdict = verdicts[key] ?: return null
        val now = nowMs()
        // A backwards clock expires the verdict rather than extending it under a new epoch.
        if (now < verdict.atMs || now - verdict.atMs >= ttlMs) {
            verdicts.remove(key)
            return null
        }
        return verdict
    }

    @Synchronized
    private fun record(key: String, what: String, denied: Boolean) {
        verdicts[key] = Verdict(denied, nowMs())
        if (denied && logged.add(key)) {
            log("$what: access denied to this app; not retried for ${ttlMs / 60_000L} min or until privileges change")
        }
    }

    companion object {
        private const val TAG = "AccessDenial"
        internal const val DEFAULT_TTL_MS = 6L * 60L * 60L * 1_000L

        /** One memo per process: the denials it records belong to the app UID, not to any caller. */
        val app = AccessDenialMemo()

        /** True only when opening [path] for reading fails with EACCES (SELinux or DAC). */
        fun openDenied(path: String): Boolean = try {
            Files.newByteChannel(Paths.get(path)).close()
            false
        } catch (_: AccessDeniedException) {
            true
        }

        /** True only when listing the directory at [path] fails with EACCES. */
        fun listDenied(path: String): Boolean = try {
            Files.newDirectoryStream(Paths.get(path)).close()
            false
        } catch (_: AccessDeniedException) {
            true
        }
    }
}
