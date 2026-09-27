package io.github.maxlyth.hapaneld.http

import io.github.maxlyth.hapaneld.DiscoveryResult

/**
 * Pure decision for "what does this panel still need before it can show a dashboard, and what is the one
 * next thing to do?" — the single authority every setup surface renders from.
 *
 * Built the way [HealthAudit] is, and for the same reason: the answer was previously re-derived
 * independently by the Configure tab, the dashboard banner, the Install tab, the panel's standing screen
 * and the renderer itself, so they disagreed and each dropped cases the others handled. [SetupBanner]
 * still owns the MQTT progress *wording*; this composes it rather than replacing it.
 *
 * Two properties matter more than the stage list:
 *
 * 1. **In-flight is never presented as a fault.** A broker that is merely mid-connect must not be reported
 *    as needing attention — that false positive is the exact regression [SetupBanner] was extracted to
 *    prevent, and it is what makes a user "fix" something that was about to succeed on its own.
 * 2. **MQTT does not block a dashboard.** `PostUpdateReturnPolicy` already states that MQTT is optional
 *    and must not strand a panel, yet [HealthAudit] gates its no-renderer finding on a configured broker.
 *    Those two readings contradict each other. This model resolves it explicitly with [Step.blocking]:
 *    MQTT gates *control*, not *render*, so a user whose broker is broken can still be walked to a
 *    working dashboard instead of being trapped behind it. Setup asks for Home Assistant BEFORE MQTT —
 *    see the [Stage] doc for why the area mechanism forces that order — but ordering is a walk choice,
 *    not a precondition, and only preconditions may be encoded as blockers.
 *
 * Nothing here is persisted. Every input is derived from live config and runtime state, which is what lets
 * the journey re-arm by itself when a working panel later loses its broker, its token or its renderer:
 * there is no stored cursor to go stale, so "setup" and "repair" are the same computation.
 *
 * Pure — no Android imports, unit-tested in SetupJourneyTest.
 */
object SetupJourney {
    /**
     * Ordered: earlier stages are prerequisites for later ones — and the order IS the walk order.
     *
     * Home Assistant comes BEFORE MQTT, deliberately reversed from the original design ("MQTT first,
     * it's what the product is for"). The area mechanism forced the choice: `suggested_area` applies
     * only at the device's FIRST registration, the device registers the moment MQTT connects, and the
     * valid area names are only readable after HA sign-in. This is the one sequence in which a panel
     * lands in its correct Home Assistant area for EVERY user — the alternatives were an admin-only
     * WebSocket move or free-typed phantom areas. MQTT still precedes the entity filter, so the journey
     * cannot read complete while the broker was never even asked about.
     */
    enum class Stage {
        IDENTITY,
        RENDERER,
        HA_URL,
        HA_CREDENTIALS,
        HOME_DASHBOARD,
        MQTT_BROKER,
        MQTT_CREDENTIALS,
        MQTT_CONNECTION,
        ENTITY_FILTER,
        RENDER_PROOF,
    }

    enum class Status {
        /** Done, and proven where proof is possible. */
        SATISFIED,

        /** Needs the user. This is the only status that may be presented as an instruction. */
        BLOCKED,

        /** Working on it; the user should wait, not act. */
        IN_FLIGHT,

        /** Not observable — say so rather than guess. */
        UNKNOWN,

        /** Deliberately not part of this panel's journey. */
        SKIPPED,
    }

    /**
     * @param blocking whether a dashboard genuinely cannot render until this is satisfied. False for the
     *   MQTT stages: they gate Home Assistant control, not rendering.
     */
    data class Step(
        val stage: Stage,
        val status: Status,
        val blocking: Boolean,
        val detail: String = "",
    )

    data class Journey(
        val steps: List<Step>,
        /** The one thing to do next, or null when nothing is waiting on the user. */
        val next: Stage?,
        /** Every blocking stage satisfied — a dashboard should be on screen. */
        val complete: Boolean,
    ) {
        fun step(stage: Stage): Step = steps.first { it.stage == stage }

        /**
         * Whether setup is waiting on a *person*, as opposed to waiting on the panel.
         *
         * This, not [complete], is what first-run affordances must be gated on — the "Set up" tab and the
         * "Setting up this panel?" banner. [complete] includes [Stage.RENDER_PROOF], and for the built-in
         * renderer that proof is process-scoped: it is re-earned when Home Assistant's frontend next reports
         * itself connected. So after any restart — an ordinary app upgrade, a service restart, a panel that
         * has been asleep — a fully configured panel is briefly incomplete with nothing missing, and offering
         * it guided setup is wrong. Reported on a configured panel that showed both affordances while its
         * only unsatisfied stage was `render_proof: awaiting_frontend`.
         *
         * A stage that is BLOCKED is an instruction; IN_FLIGHT means wait. Only the former is a reason to
         * invite someone into the wizard. A re-armed panel that genuinely lost its broker or its credential
         * still has a blocked stage, so real repairs keep their entry point.
         */
        val needsUser: Boolean get() = steps.any { it.blocking && it.status == Status.BLOCKED }
    }

    /** Canonical MQTT lifecycle tokens, mapped once so callers never string-match prose. */
    enum class MqttSetupState {
        CONNECTED,
        ANNOUNCING,
        CONNECTING,
        DISCOVERING,
        AUTH_RETRYING,
        AUTH_FAILED,
        UNREACHABLE,
        CONFIG_ERROR,
        DISABLED,
        UNKNOWN,
        ;

        companion object {
            /**
             * Map a [io.github.maxlyth.hapaneld.MqttBridge.state] token. Two vocabularies exist in this
             * codebase — these canonical tokens, and the human prose on the info page — and feeding one
             * into a reader expecting the other degrades silently to "transient", which reads as "all
             * fine, keep waiting" forever. SetupJourneyTest pins every documented token to a non-UNKNOWN
             * value so adding a state cannot quietly disable the guidance.
             */
            fun of(canonical: String): MqttSetupState = when (canonical.trim().lowercase()) {
                "connected" -> CONNECTED
                "announcing" -> ANNOUNCING
                "connecting" -> CONNECTING
                "discovering" -> DISCOVERING
                "auth-retrying" -> AUTH_RETRYING
                "auth-failed" -> AUTH_FAILED
                "unreachable" -> UNREACHABLE
                "config-error" -> CONFIG_ERROR
                "disabled" -> DISABLED
                else -> UNKNOWN
            }
        }
    }

    sealed interface RendererChoice {
        /** ha-paneld's own renderer, whether chosen explicitly or resolved from Auto. */
        data object Builtin : RendererChoice

        /** A third-party renderer such as the Home Assistant Companion app. */
        data class Foreign(val pkg: String, val installed: Boolean) : RendererChoice

        /** Nothing resolves — an explicit choice that is absent, or no candidate at all. */
        data object Unresolved : RendererChoice
    }

    /** How we know a dashboard rendered. Ranked by strength; see [RenderProof]. */
    enum class ProofSource {
        NONE,

        /** Home Assistant's own frontend reported itself connected inside the panel's WebView. */
        BUILTIN_FRONTEND_CONNECTED,

        /** A foreign renderer is foregrounded. Proves the app is running, NOT that a dashboard drew. */
        FOREIGN_FOREGROUND_ONLY,

        /** A human looking at the panel said it is there. */
        USER_ATTESTED,
    }

    /**
     * @param certain whether [source] actually establishes that a dashboard rendered. Foregrounding does
     *   not: the app could be showing its own onboarding, a TLS error or "unable to connect".
     * @param fingerprint the configuration the proof was observed against, so changing the URL, the
     *   renderer or the credential voids it and the journey re-arms as data rather than by an event.
     */
    data class RenderProof(
        val source: ProofSource = ProofSource.NONE,
        val certain: Boolean = false,
        val observedAtMs: Long? = null,
        val fingerprint: String = "",
    )

    data class Inputs(
        /** The user has explicitly accepted the panel identity; a generated value alone is not consent. */
        val identityConfirmed: Boolean,
        val panelId: String,
        val brokerConfigured: Boolean,
        val mqttUserConfigured: Boolean,
        val mqttPasswordConfigured: Boolean,
        val mqtt: MqttSetupState,
        val renderer: RendererChoice,
        val haUrl: String,
        val haCredentialed: Boolean,
        val haOAuthInFlight: Boolean,
        val discovery: DiscoveryResult = DiscoveryResult(),
        /**
         * The system WebView is too old to render a current Home Assistant frontend.
         *
         * Checked as part of choosing a renderer because it decides whether the chosen one can work at all,
         * and it must be raised BEFORE the Home Assistant URL and sign-in: a user who signs in first and
         * discovers this afterwards has done the whole journey for a panel that was never going to render.
         */
        val webViewTooOld: Boolean = false,
        /** A known-good build is pinned for this panel, so setup can offer to install it rather than only explain. */
        val webViewFixable: Boolean = false,
        /**
         * The user has answered the entity-filter question — either way. Enabling it is observable from
         * config, but *declining* is not distinguishable from never having been asked, so like
         * [identityConfirmed] the answer is recorded rather than inferred. Without this the question would
         * re-arm on every poll for anyone who said no, and the panel would never render.
         */
        val entityFilterAnswered: Boolean = false,
        /**
         * The user has chosen which Home Assistant dashboard this panel shows — including choosing "follow
         * the account's default". Like [entityFilterAnswered], picking the default leaves no config trace
         * (`home_dashboard` stays blank), so the answer is recorded rather than inferred. Asked BEFORE the
         * entity-filter question because the filter's learned allow-list and its settled entity count are
         * both keyed by the dashboard path: answering the filter first would scope it to a dashboard the
         * user is about to change.
         */
        val homeDashboardChosen: Boolean = false,
        val proof: RenderProof = RenderProof(),
        /** Fingerprint of the CURRENT configuration, compared against [RenderProof.fingerprint]. */
        val currentFingerprint: String = "",
        /**
         * Panel Assistant granted this panel native authority: its entities and commands travel over Panel
         * Assistant's connection, so the MQTT steps do not apply unless the owner configured a broker anyway.
         */
        val panelAssistantNative: Boolean = false,
    )

    private val BLOCKING = setOf(
        Stage.IDENTITY,
        Stage.RENDERER,
        Stage.HA_URL,
        Stage.HA_CREDENTIALS,
        Stage.HOME_DASHBOARD,
        Stage.ENTITY_FILTER,
        Stage.RENDER_PROOF,
    )

    fun evaluate(inputs: Inputs): Journey {
        val steps = buildList {
            add(identityStep(inputs))
            add(rendererStep(inputs))
            addAll(haSteps(inputs))
            add(homeDashboardStep(inputs))
            addAll(mqttSteps(inputs))
            add(entityFilterStep(inputs))
            add(renderProofStep(inputs))
        }
        return Journey(steps = steps, next = nextStage(steps), complete = isComplete(steps))
    }

    /**
     * The single next action, in **stage order** — the order setup actually walks.
     *
     * Ranking blocking stages first was tried and is wrong: it made severity reorder the walk under the
     * user's feet whenever blocking and non-blocking steps interleaved. [Step.blocking] governs whether
     * the journey can *finish* without a stage, and therefore whether the UI may offer to skip it; it
     * must not govern what comes next — the [Stage] declaration order alone is the walk.
     *
     * STRICT walk order — blocked or in-flight, whichever comes first. Ranking blocked above in-flight
     * globally was also tried and is also wrong: with a later stage blocked, an in-flight sign-in was
     * jumped over — the wizard abandoned "waiting for you at the panel" for the broker card while
     * sign-in was still in progress. The walk never jumps over a step that
     * is mid-flight; the wizard's own verify ladder owns how a transient is presented. [Status.UNKNOWN]
     * is never reported: it means "not observable", which is not an instruction. Where proof genuinely
     * needs a human it is [Status.BLOCKED] instead, and a non-actionable UNKNOWN can only arise
     * alongside an earlier blocked stage, so it never dead-ends.
     */
    private fun nextStage(steps: List<Step>): Stage? =
        steps.firstOrNull { it.status == Status.BLOCKED || it.status == Status.IN_FLIGHT }?.stage

    private fun isComplete(steps: List<Step>): Boolean =
        steps.none { it.blocking && it.status != Status.SATISFIED && it.status != Status.SKIPPED }

    private fun identityStep(inputs: Inputs): Step {
        // panel_id is never blank — it auto-derives on first read — so the value cannot tell us whether
        // the user ever saw it. Since it names every entity this panel publishes and renaming it later
        // renames all of them, an explicit confirmation is recorded rather than inferred.
        val status = if (inputs.identityConfirmed) Status.SATISFIED else Status.BLOCKED
        return Step(Stage.IDENTITY, status, blocking = true, detail = inputs.panelId)
    }

    private fun mqttSteps(inputs: Inputs): List<Step> {
        if (inputs.panelAssistantNative && !inputs.brokerConfigured) {
            return listOf(
                Step(Stage.MQTT_BROKER, Status.SKIPPED, blocking = false),
                Step(Stage.MQTT_CREDENTIALS, Status.SKIPPED, blocking = false),
                Step(Stage.MQTT_CONNECTION, Status.SKIPPED, blocking = false),
            )
        }
        if (inputs.mqtt == MqttSetupState.DISABLED && !inputs.brokerConfigured) {
            return listOf(
                Step(Stage.MQTT_BROKER, Status.BLOCKED, blocking = false),
                Step(Stage.MQTT_CREDENTIALS, Status.BLOCKED, blocking = false),
                Step(Stage.MQTT_CONNECTION, Status.SKIPPED, blocking = false),
            )
        }
        val broker = Step(
            Stage.MQTT_BROKER,
            if (inputs.brokerConfigured) Status.SATISFIED else Status.BLOCKED,
            blocking = false,
        )
        val credentials = Step(
            Stage.MQTT_CREDENTIALS,
            when {
                inputs.mqtt == MqttSetupState.AUTH_FAILED -> Status.BLOCKED
                // A rejection inside the retry window may still recover; do not send the user after it yet.
                inputs.mqtt == MqttSetupState.AUTH_RETRYING -> Status.IN_FLIGHT
                inputs.mqttUserConfigured && inputs.mqttPasswordConfigured -> Status.SATISFIED
                inputs.brokerConfigured -> Status.BLOCKED
                else -> Status.BLOCKED
            },
            blocking = false,
        )
        val connection = Step(
            Stage.MQTT_CONNECTION,
            when (inputs.mqtt) {
                MqttSetupState.CONNECTED -> Status.SATISFIED
                MqttSetupState.ANNOUNCING,
                MqttSetupState.CONNECTING,
                MqttSetupState.DISCOVERING,
                MqttSetupState.AUTH_RETRYING,
                -> Status.IN_FLIGHT
                MqttSetupState.AUTH_FAILED,
                MqttSetupState.UNREACHABLE,
                MqttSetupState.CONFIG_ERROR,
                -> Status.BLOCKED
                MqttSetupState.DISABLED -> Status.SKIPPED
                // Configured but the bridge has not reported yet: transient, never a fault.
                MqttSetupState.UNKNOWN -> if (inputs.brokerConfigured) Status.IN_FLIGHT else Status.BLOCKED
            },
            blocking = false,
            detail = inputs.mqtt.name.lowercase(),
        )
        return listOf(broker, credentials, connection)
    }

    private fun rendererStep(inputs: Inputs): Step = when (val choice = inputs.renderer) {
        // Note this must NOT inherit HealthAudit's "only once a broker is configured" precondition: a
        // renderer choice is valid, and checkable, before MQTT exists.
        is RendererChoice.Builtin ->
            // An engine below the frontend's floor renders blank or broken however well everything else is
            // configured, so this is a blocked renderer rather than a warning carried to the end. Raised only
            // for the built-in renderer: it shares the system WebView, but another app's internals are not
            // ours to declare broken. The copy says switching renderers will not help, because they share it.
            if (inputs.webViewTooOld) {
                Step(
                    Stage.RENDERER,
                    Status.BLOCKED,
                    blocking = true,
                    detail = if (inputs.webViewFixable) "webview_too_old_fixable" else "webview_too_old",
                )
            } else {
                Step(Stage.RENDERER, Status.SATISFIED, blocking = true, detail = "builtin")
            }
        is RendererChoice.Foreign -> Step(
            Stage.RENDERER,
            if (choice.installed) Status.SATISFIED else Status.BLOCKED,
            blocking = true,
            detail = choice.pkg,
        )
        RendererChoice.Unresolved -> Step(Stage.RENDERER, Status.BLOCKED, blocking = true)
    }

    /**
     * Home Assistant's address and this panel's own sign-in, asked whatever renders the dashboard.
     *
     * A foreign renderer such as the Companion app signs itself in, and these steps used to be skipped for
     * it. But Panel Assistant's connection is this app's own signed-in session, so a panel set up that way
     * could never run under Panel Assistant: its owner skipped the MQTT page, the wizard ended, and nothing
     * connected (maintainer, a Companion-app panel on a Home Assistant without MQTT, 2026-09-27).
     */
    private fun haSteps(inputs: Inputs): List<Step> {
        val url = Step(
            Stage.HA_URL,
            if (inputs.haUrl.isNotBlank()) Status.SATISFIED else Status.BLOCKED,
            blocking = true,
            // Carry WHY discovery could not fill this, so the UI can explain instead of showing a blank.
            detail = if (inputs.haUrl.isNotBlank()) inputs.haUrl else inputs.discovery.outcome.name.lowercase(),
        )
        val credentials = Step(
            Stage.HA_CREDENTIALS,
            when {
                inputs.haCredentialed -> Status.SATISFIED
                inputs.haOAuthInFlight -> Status.IN_FLIGHT
                inputs.haUrl.isBlank() -> Status.BLOCKED
                else -> Status.BLOCKED
            },
            blocking = true,
        )
        return listOf(url, credentials)
    }

    /**
     * Whether the user has chosen the dashboard this panel displays.
     *
     * Home Assistant no longer remembers this per device: since 2025.12 the default dashboard lives in the
     * signed-in account's server-side profile, and a wall panel's dedicated user starts with none, so the
     * effective default falls to the system default or Home Assistant's own fallback — rarely what a wall
     * panel should show. Asked immediately after sign-in and BEFORE the entity filter because the filter's
     * learned state is keyed by the dashboard path (see [Inputs.homeDashboardChosen]).
     *
     * Only meaningful for the built-in renderer: a foreign renderer navigates itself.
     */
    private fun homeDashboardStep(inputs: Inputs): Step {
        if (inputs.renderer !is RendererChoice.Builtin) {
            return Step(Stage.HOME_DASHBOARD, Status.SKIPPED, blocking = true, detail = "foreign_renderer")
        }
        // The list of dashboards to offer needs a live, signed-in Home Assistant; before that the question
        // is not yet answerable. UNKNOWN rather than BLOCKED for the same reason as the entity filter: an
        // earlier stage already owns the instruction.
        if (inputs.haUrl.isBlank() || !inputs.haCredentialed) {
            return Step(Stage.HOME_DASHBOARD, Status.UNKNOWN, blocking = true, detail = "prerequisites_missing")
        }
        val status = if (inputs.homeDashboardChosen) Status.SATISFIED else Status.BLOCKED
        return Step(Stage.HOME_DASHBOARD, status, blocking = true)
    }

    /**
     * Whether the entity-filter question has been answered — asked *before* the first render, not after it.
     *
     * It used to be a suggestion on the completion screen, on the reasoning that the offer only makes sense
     * once the user has seen the wall of entities. Hardware reversed that: on a weak panel the unfiltered
     * first load is slow and laggy, so by the time the wall has been seen the product has already made its
     * first impression, and made it badly. Asking first costs one screen; asking after costs the impression.
     *
     * Only the built-in renderer's subscription is ours to narrow — a foreign renderer owns its own — so for
     * anything else this is [Status.SKIPPED] rather than a question we cannot act on.
     */
    private fun entityFilterStep(inputs: Inputs): Step {
        if (inputs.renderer !is RendererChoice.Builtin) {
            return Step(Stage.ENTITY_FILTER, Status.SKIPPED, blocking = true, detail = "foreign_renderer")
        }
        // The question needs a live Home Assistant to count against, so before sign-in it is not yet
        // answerable. UNKNOWN rather than BLOCKED: an earlier stage is already blocked and owns the
        // instruction, and reporting two things to do at once is how a wizard stops being one.
        if (inputs.haUrl.isBlank() || !inputs.haCredentialed) {
            return Step(Stage.ENTITY_FILTER, Status.UNKNOWN, blocking = true, detail = "prerequisites_missing")
        }
        val status = if (inputs.entityFilterAnswered) Status.SATISFIED else Status.BLOCKED
        return Step(Stage.ENTITY_FILTER, status, blocking = true)
    }

    /**
     * Whether a dashboard is actually on screen — the only definition of done that matters, since every
     * earlier stage can be satisfied while the panel still shows nothing useful.
     *
     * For a foreign renderer this is genuinely not observable: foregrounding needs privilege and only
     * proves the app is running, its stored configuration proves configuration, and a screenshot needs a
     * human to judge. So the honest answer is [Status.UNKNOWN] plus a request for attestation, never an
     * automated claim.
     */
    private fun renderProofStep(inputs: Inputs): Step {
        // An unanswered entity-filter question counts as a missing prerequisite because the renderer is
        // deliberately held until it is answered. Without this the proof step would report IN_FLIGHT —
        // "loading, go and look" — while the panel has been told on purpose not to load anything, which is
        // the wizard telling the user to watch for something it is itself preventing.
        val blockedEarlier = inputs.renderer is RendererChoice.Unresolved ||
            (inputs.renderer is RendererChoice.Builtin &&
                (inputs.haUrl.isBlank() || !inputs.haCredentialed ||
                    !inputs.homeDashboardChosen || !inputs.entityFilterAnswered))
        if (blockedEarlier) {
            return Step(Stage.RENDER_PROOF, Status.UNKNOWN, blocking = true, detail = "prerequisites_missing")
        }
        val stale = inputs.proof.fingerprint.isNotBlank() &&
            inputs.currentFingerprint.isNotBlank() &&
            inputs.proof.fingerprint != inputs.currentFingerprint
        if (stale) {
            // The panel rendered, but not against this configuration. Re-arm rather than coast on it. The
            // built-in renderer reloads and re-signals by itself, so that is a wait; a foreign renderer
            // cannot re-prove anything on its own and needs asking again.
            val status = if (inputs.renderer is RendererChoice.Foreign) Status.BLOCKED else Status.IN_FLIGHT
            return Step(Stage.RENDER_PROOF, status, blocking = true, detail = "stale_proof")
        }
        return when {
            inputs.proof.certain -> Step(
                Stage.RENDER_PROOF,
                Status.SATISFIED,
                blocking = true,
                detail = inputs.proof.source.name.lowercase(),
            )
            // Not observable, so it must be asked — BLOCKED rather than UNKNOWN, because "confirm you can
            // see your dashboard" is a real instruction. Left as UNKNOWN this stage stalled the journey
            // with nothing reported as next: incomplete, and silent about why.
            inputs.renderer is RendererChoice.Foreign -> Step(
                Stage.RENDER_PROOF,
                Status.BLOCKED,
                blocking = true,
                detail = "foreign_renderer_unobservable",
            )
            else -> Step(Stage.RENDER_PROOF, Status.IN_FLIGHT, blocking = true, detail = "awaiting_frontend")
        }
    }
}
