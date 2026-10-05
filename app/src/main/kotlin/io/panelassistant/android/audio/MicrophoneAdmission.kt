package io.panelassistant.android.audio

/**
 * What the panel will admit to the microphone, asked by whoever is about to hand it out.
 *
 * Two separate questions, deliberately. [webViewCaptureAllowed] is whether the dashboard page is
 * *permitted* to record at all; [isIdle] is whether the hardware is *free* right now. A grant needs
 * both, and neither substitutes for the other: an idle microphone is an opportunity, not a licence.
 *
 * **The Android permission is not the opt-in.** Provisioning grants `RECORD_AUDIO` over adb because
 * a wall panel has nobody standing at it to answer a runtime dialog, so by the time any feature
 * wants the microphone the platform permission is simply present. That makes the platform
 * permission useless as a decision: if the WebView gate asked only "do we hold RECORD_AUDIO?", every
 * provisioned panel would silently let any page the dashboard loads open a recorder. So
 * [webViewCaptureAllowed] answers `false` until a feature that owns a real opt-in says otherwise —
 * a profile that declares `hardware.microphone` plus a setting the panel's owner turned on. No such
 * feature exists yet, which is exactly why the default is a refusal rather than a hole waiting to be
 * closed.
 *
 * This is a seam, not a policy: it holds predicates rather than a source or a config, so the gate
 * has no dependency on how, or whether, capture and its opt-in are eventually wired up.
 */
object MicrophoneAdmission {

    /**
     * The two shipped answers, each named once.
     *
     * They were written out twice — at the field and again in [reset] — until a mutation of the
     * declared default killed nothing: every test resets first, so the initializer had no assertion
     * of its own and the two copies were free to drift apart. One name, one place to change, and a
     * mutation of it is now visible to every test that asks what an unwired panel answers.
     */
    private val ALWAYS_IDLE: () -> Boolean = { true }

    /** No page may capture: the answer until a feature that owns a real opt-in replaces it. */
    private val REFUSE_WEB_VIEW_CAPTURE: () -> Boolean = { false }

    /**
     * Whether the shared microphone is free. Answers `true` until something points it at a real
     * [MicrophoneSource], which keeps the gate inert in a build where nothing leases the microphone.
     */
    @Volatile
    var isIdle: () -> Boolean = ALWAYS_IDLE
        private set

    /**
     * Whether the dashboard page may capture audio at all. Answers `false` until a feature that owns
     * an explicit opt-in replaces it. Read [MicrophoneAdmission]'s note on why this is not the same
     * question as holding the Android permission.
     */
    @Volatile
    var webViewCaptureAllowed: () -> Boolean = REFUSE_WEB_VIEW_CAPTURE
        private set

    /** Answer [isIdle] from a live source: idle exactly while no lease is held. */
    fun observe(source: MicrophoneSource) {
        isIdle = { source.state.value is MicState.Closed }
    }

    /** Answer [isIdle] from an arbitrary predicate. */
    fun answerIdleWith(predicate: () -> Boolean) {
        isIdle = predicate
    }

    /**
     * Hand the WebView opt-in decision to the feature that owns it. Nothing in the build calls this
     * yet; when something does, it must be answering a real capability plus a real setting, never
     * the mere presence of the Android permission.
     */
    fun allowWebViewCaptureWhen(predicate: () -> Boolean) {
        webViewCaptureAllowed = predicate
    }

    /** Restore the defaults: idle, and no page may capture. */
    fun reset() {
        isIdle = ALWAYS_IDLE
        webViewCaptureAllowed = REFUSE_WEB_VIEW_CAPTURE
    }
}
