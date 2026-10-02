package io.github.maxlyth.hapaneld.platform

import java.io.File

/**
 * The root-helper-daemon boundary — a thin seam over [io.github.maxlyth.hapaneld.util.HelperClient] so
 * callers can depend on an interface (and be unit-tested with a fake) instead of the concrete socket
 * client. Production injects the `HelperClient` object; tests inject a fake. Behaviour is unchanged.
 */
interface Daemon {
    /** True when the daemon answers `PING`. */
    fun available(): Boolean

    /** Send one command; return the exact printable reply line without its LF, or null if unreachable/malformed. */
    fun send(cmd: String): String?

    /**
     * Send a long-running command with a caller-selected read timeout. [DaemonLongResult.NotSubmitted]
     * proves no command reached the daemon; [DaemonLongResult.Indeterminate] means it may still be
     * running, so caller-owned inputs must remain valid.
     */
    fun sendLong(cmd: String, timeoutMs: Long): DaemonLongResult

    /** Recheck current admission after transport preparation, before command submission. */
    fun sendLong(cmd: String, timeoutMs: Long, beforeSubmit: () -> Boolean): DaemonLongResult =
        if (beforeSubmit()) sendLong(cmd, timeoutMs) else DaemonLongResult.NotSubmitted

    /**
     * Negotiate a two-phase command, stream [source] only after the daemon replies `READY`, then wait
     * for one terminal line. The default keeps injected/older daemon implementations compatible.
     */
    fun sendFile(cmd: String, source: File, timeoutMs: Long): DaemonStreamResult =
        DaemonStreamResult.Unsupported

    /** The production transport checks on its bootstrapped connection before sending the command. */
    fun sendFile(cmd: String, source: File, timeoutMs: Long, beforeSubmit: () -> Boolean): DaemonStreamResult =
        if (beforeSubmit()) sendFile(cmd, source, timeoutMs) else DaemonStreamResult.NotSubmitted

    /** Send one command and read the full binary reply (e.g. a `SCREENCAP` PNG), or null. */
    fun sendBytes(cmd: String): ByteArray?

    /** Bounded binary reply variant; production transports enforce this while streaming. */
    fun sendBytesBounded(cmd: String, maxBytes: Long): ByteArray? =
        sendBytes(cmd)?.takeIf { it.size.toLong() <= maxBytes }
}

sealed interface DaemonLongResult {
    /** The daemon returned a terminal reply for this command. */
    data class Reply(val value: String) : DaemonLongResult

    /** Socket connection failed before command submission began. */
    data object NotSubmitted : DaemonLongResult

    /** Submission began but EOF, timeout, or I/O failure prevented a terminal reply. */
    data object Indeterminate : DaemonLongResult
}

sealed interface DaemonStreamResult {
    /** The daemon returned a terminal reply, either before or after accepting the payload. */
    data class Reply(val value: String) : DaemonStreamResult

    /** The daemon returned the legacy unknown-command `ERR` before accepting any payload. */
    data object Unsupported : DaemonStreamResult

    /** Socket connection failed before command submission began. */
    data object NotSubmitted : DaemonStreamResult

    /** Submission began but no terminal reply arrived; closing the socket ends source consumption. */
    data object Indeterminate : DaemonStreamResult
}
