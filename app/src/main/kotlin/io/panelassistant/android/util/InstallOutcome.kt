package io.panelassistant.android.util

/**
 * The typed result of a privileged APK install — produced by [AppInstaller.install] /
 * [AppInstaller.installLocalApk] and the helper-backed [HelperInstallTransaction], and consumed by the
 * self-updater, Companion installer, WebView healer and the APK-upload route.
 *
 * The producer classifies the outcome ONCE, so consumers switch on the type instead of re-parsing an
 * `"OK"`/error string. A [Failure] carries the exact human-readable [message] that reaches the UI /
 * InstallProgress unchanged, and distinguishes a durable package-manager/signer rejection ([Rejected])
 * from a transient failure that a later attempt may clear ([Retryable]) — the distinction the WebView
 * same-pin loop guard needs, previously recovered by sniffing the message text.
 */
sealed interface InstallOutcome {
    /** The package manager committed the install. */
    object Succeeded : InstallOutcome

    /** The install did not complete; [message] is the exact status shown to the user. */
    sealed interface Failure : InstallOutcome {
        val message: String
        val presentation: InstallPresentation?
    }

    /**
     * The package manager or the signer pin refused THIS artifact (signer/package mismatch, checksum
     * mismatch, incompatible update, …). Retrying the same APK cannot help, so a same-pin loop guard
     * treats it as durable evidence.
     */
    data class Rejected(
        override val message: String,
        override val presentation: InstallPresentation? = null,
    ) : Failure

    /**
     * A transient network, storage, privilege, or helper-staging failure. A later attempt with the same
     * pin may still succeed, so it is not durable evidence for a loop guard.
     */
    data class Retryable(
        override val message: String,
        override val presentation: InstallPresentation? = null,
        /** A submitted install lost its reply, so the package mutation may still commit. */
        val mayHaveCommitted: Boolean = false,
    ) : Failure
}
