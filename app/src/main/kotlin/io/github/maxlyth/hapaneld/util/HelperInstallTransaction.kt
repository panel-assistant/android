package io.github.maxlyth.hapaneld.util

import io.github.maxlyth.hapaneld.platform.Daemon
import io.github.maxlyth.hapaneld.platform.DaemonLongResult
import io.github.maxlyth.hapaneld.platform.DaemonStreamResult
import java.io.File

/**
 * Owns one helper-backed APK install through terminal daemon reply. A current helper receives bytes
 * over the authenticated socket, so its SELinux domain never opens app-private storage. Closing that
 * socket ends source consumption even when the install outcome is unknown.
 *
 * An older helper rejects the stream verb before payload and falls back to the path-based transaction:
 * the source is then claimed into unique persistent staging and retained after an indeterminate call.
 */
internal class HelperInstallTransaction(
    private val daemon: Daemon,
    private val timeoutMs: Long = INSTALL_TIMEOUT_MS,
    private val staging: HelperInstallStaging = HelperInstallStaging.shared,
) {
    fun install(
        apk: File,
        stagingDir: File,
        beforeSubmit: (() -> InstallOutcome.Failure?)? = null,
    ): InstallOutcome {
        if (!apk.isFile || apk.length() <= 0L) {
            apk.delete()
            return InstallOutcome.Retryable("install failed: invalid APK input")
        }
        var refusal: InstallOutcome.Failure? = null
        val submissionAllowed = beforeSubmit?.let { admit ->
            {
                refusal = admit()
                refusal == null
            }
        }
        val streamCommand = "INSTALLSTREAM ${apk.length()}"
        val streamed = if (submissionAllowed == null) daemon.sendFile(streamCommand, apk, timeoutMs)
            else daemon.sendFile(streamCommand, apk, timeoutMs, submissionAllowed)
        when (streamed) {
            is DaemonStreamResult.Reply -> {
                apk.delete()
                return daemonInstallReply(streamed.value)
            }
            DaemonStreamResult.NotSubmitted -> {
                apk.delete()
                return refusal ?: InstallOutcome.Retryable("install failed: daemon unreachable")
            }
            DaemonStreamResult.Indeterminate -> {
                apk.delete()
                return InstallOutcome.Retryable("install outcome unknown: streamed input released", mayHaveCommitted = true)
            }
            DaemonStreamResult.Unsupported -> Unit
        }

        val owned = staging.claim(apk, stagingDir)
            ?: return InstallOutcome.Retryable("install failed: could not claim helper staging")
        // Unsupported accepted no payload. Claiming a legacy input is still preparation: this is a
        // fresh installer submission, so authority may have changed during negotiation or staging.
        val installCommand = "INSTALL ${owned.absolutePath}"
        val result = if (submissionAllowed == null) daemon.sendLong(installCommand, timeoutMs)
            else daemon.sendLong(installCommand, timeoutMs, submissionAllowed)
        return when (result) {
            is DaemonLongResult.Reply -> {
                staging.release(owned, delete = true)
                daemonInstallReply(result.value)
            }
            DaemonLongResult.NotSubmitted -> {
                staging.release(owned, delete = true)
                refusal ?: InstallOutcome.Retryable("install failed: daemon unreachable")
            }
            DaemonLongResult.Indeterminate -> {
                staging.release(owned, delete = false)
                InstallOutcome.Retryable("install outcome unknown: helper staging retained for safety", mayHaveCommitted = true)
            }
        }
    }

    /** Preserve retryable helper admission/staging failures ([InstallOutcome.Retryable]) instead of
     * collapsing them into the durable package-manager rejection ([InstallOutcome.Rejected]) used by
     * WebView's same-pin loop guard. */
    private fun daemonInstallReply(reply: String): InstallOutcome = when (reply.trim()) {
        "OK" -> InstallOutcome.Succeeded
        "BUSY" -> InstallOutcome.Retryable("install failed: daemon busy")
        "STREAMERR" -> InstallOutcome.Retryable("install failed: daemon stream staging failed")
        else -> InstallOutcome.Rejected("install failed: daemon install failed")
    }

    companion object {
        internal const val INSTALL_TIMEOUT_MS = 180_000L
        internal const val STAGING_DIR = "helper-install-staging"
        internal const val STAGING_PREFIX = "hapaneld-helper-install-"
    }
}
