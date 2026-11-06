package io.panelassistant.android.platform

import java.io.File

/**
 * The root-helper-daemon boundary — a thin seam over [io.panelassistant.android.util.HelperClient] so
 * callers can depend on an interface (and be unit-tested with a fake) instead of the concrete socket
 * client. Production injects the `HelperClient` object; tests inject a fake. Behaviour is unchanged.
 */
interface Daemon {
    /** True when the daemon answers `PING`. */
    fun available(): Boolean

    /** Fixed cpu0 governor reads; unavailable on helpers without the versioned GOVREAD capability. */
    fun cpuGovernor(): String? = null
    fun cpuAvailableGovernors(): List<String>? = null

    /** Fixed gateway observations; null means the helper could not prove a complete observation. */
    fun zigbeeState(): HelperZigbeeState? = null
    fun zigbeeHealth(): HelperZigbeeHealth? = null
    fun zigbeeRole(): String? = null

    /** Fixed current Wi-Fi status; null means unavailable or an unverified helper protocol. */
    fun wifiStatus(): HelperWifiStatus? = null

    /** Fixed ADB observations; unknown fields stay null and no root shell fallback is implied. */
    fun adbState(): HelperAdbState? = null

    /** Strict fixed CDP process/listener observation; unavailable never proves absence. */
    fun cdpState(): HelperCdpState? = null

    /** Fixed relay/GPIO observations; null means unknown, never an invitation to launch su. */
    fun relayCount(relayClass: HelperRelayClass): Int? = null
    fun relayRead(relayClass: HelperRelayClass, relay: Int): Boolean? = null
    fun gpioRead(pin: Int, timeoutMs: Long = 1_500L): HelperGpioState? = null

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
