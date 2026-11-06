package io.panelassistant.android

import android.util.Log
import io.panelassistant.android.Config.Companion.CONFIG_LOCK
import io.panelassistant.android.Config.Companion.TAG

// Guided-setup state: the answers setup records, rather than infers, and their upgrade migrations.

/**
 * Whether the user has explicitly accepted this panel's identity during setup.
 *
 * Needed because `panel_id` is never blank — it auto-derives on first read — so its value cannot
 * distinguish "the user chose this" from "nobody has looked at it yet". That distinction matters: the
 * id names every entity this panel publishes to Home Assistant, and changing it later renames all of
 * them, so setup asks once and records the answer rather than inferring consent from a generated
 * default.
 *
 * Deliberately an internal pref rather than a [SettingSpec]: it is setup's own state, not a user
 * setting, so it has no place on the Configure form, in a config bundle or as a Home Assistant entity.
 */
var Config.setupIdentityConfirmed: Boolean
    get() = prefs.getBoolean(SETUP_IDENTITY_CONFIRMED_PREF, false)
    set(value) = synchronized(CONFIG_LOCK) {
        prefs.edit().putBoolean(SETUP_IDENTITY_CONFIRMED_PREF, value).commit()
        Unit
    }

/**
 * One-shot upgrade migration: a panel that was already configured before the entity-filter question
 * existed counts as having answered it.
 *
 * Without this, introducing the question strands the entire installed base. The flag defaults false, the
 * built-in renderer holds its first load until it is answered, and `setupEverCompleted` is itself new — so
 * an upgraded panel would stop showing its dashboard, and could not even earn the completion stamp that
 * would release it, because earning it requires the render that is being held. A canary deployed to two
 * configured panels reproduced exactly that.
 *
 * It runs on every start until it actually finds evidence, and only then records itself as done. The
 * earlier version ran exactly once and marked itself complete even when the configuration read back
 * blank — which happened on real hardware — leaving a configured panel permanently on the first-run path
 * with no second chance. Retrying is the safer failure: the worst case is that a panel restarted midway
 * through setup skips the question and renders unfiltered, which is recoverable from the Configure tab,
 * whereas the other direction leaves a working panel showing a setup screen instead of its dashboard.
 *
 * This is a convenience, not the safety net. The hold predicate independently exempts a panel whose
 * filter is already enabled, so a failure here can no longer strand anything.
 */
fun Config.migrateSetupQuestionsForExistingInstall() = synchronized(CONFIG_LOCK) {
    if (!prefs.getBoolean(SETUP_QUESTION_MIGRATION_PREF, false)) {
        // Guided setup OWNS this panel once the identity step has been confirmed — that flag is written by
        // the wizard and nothing else, so its presence proves any config found here was written BY the
        // journey in progress, not by a pre-existing install. Without this gate the retry below read the
        // wizard's own saves as upgrade evidence on the first mid-journey service restart and
        // durably stamped every question answered: the panel rendered unfiltered, the journey reported
        // complete, and the sign-in return dumped the user to Configure with two questions never asked
        // after setup begins. Migration is DONE for such a panel: the wizard records the
        // real answers itself.
        if (setupIdentityConfirmed) {
            prefs.edit().putBoolean(SETUP_QUESTION_MIGRATION_PREF, true).commit()
        } else if (configuredBeforeSetupQuestionTracking()) {
            prefs.edit()
                .putBoolean(SETUP_QUESTION_MIGRATION_PREF, true)
                .putBoolean(SETUP_ENTITY_FILTER_ANSWERED_PREF, true)
                // Also record that setup was reached before, so the wizard frames a later re-arm as a
                // repair ("nothing has been reset") rather than replaying a first run at this panel.
                .putBoolean(SETUP_EVER_COMPLETED_PREF, true)
                .commit()
            Log.i(TAG, "setup migration: pre-existing install, entity-filter question marked answered")
        }
    }

    migrateHomeDashboardQuestionForExistingInstall()
}

/**
 * Versioned independently from the original entity-filter migration because that v1 marker is already
 * durable on the installed base. Reusing it made the newly introduced dashboard answer unreachable on
 * upgrade and sent completed panels back to the blocking HOME_DASHBOARD step.
 */
private fun Config.migrateHomeDashboardQuestionForExistingInstall() {
    if (prefs.getBoolean(SETUP_HOME_DASHBOARD_MIGRATION_PREF, false)) return

    val preExisting = setupEverCompleted || (!setupIdentityConfirmed && configuredBeforeSetupQuestionTracking())
    if (!preExisting && !setupIdentityConfirmed) return

    prefs.edit()
        .putBoolean(SETUP_HOME_DASHBOARD_MIGRATION_PREF, true)
        .apply {
            if (preExisting) putBoolean(SETUP_HOME_DASHBOARD_CHOSEN_PREF, true)
        }
        .commit()
    if (preExisting) Log.i(TAG, "setup migration: pre-existing install, home-dashboard question marked answered")
}

/**
 * Evidence this panel was configured before the setup questions existed.
 *
 * The Home Assistant URL term is delegated to [panelConfiguredBeforeSetupTracking], which is shared
 * with the journey's copy of this question, because a handed-over URL must not read as evidence in
 * either of them. The other two terms stay here: they are this migration's own, and differ from the
 * journey's third term for reasons older than the handover.
 */
private fun Config.configuredBeforeSetupQuestionTracking(): Boolean = panelConfiguredBeforeSetupTracking(
    haUrl = haUrl,
    haSetupHandover = haSetupHandover,
    otherEvidence = dashboardEntityLearningEnabled,
)

/**
 * Whether the user has answered setup's entity-filter question — either way.
 *
 * Turning the filter ON is visible in `dashboard_entity_learning`, but a deliberate "no thanks" is
 * indistinguishable from never having been asked. Since the built-in renderer is held back from its
 * first load until this question is answered, inferring the answer from the filter setting would leave
 * anyone who declined stuck on the question forever, with a panel that never renders. So, like
 * [setupIdentityConfirmed], the answer is recorded rather than inferred. Internal setup state.
 */
var Config.setupEntityFilterAnswered: Boolean
    get() = prefs.getBoolean(SETUP_ENTITY_FILTER_ANSWERED_PREF, false)
    set(value) = synchronized(CONFIG_LOCK) {
        prefs.edit().putBoolean(SETUP_ENTITY_FILTER_ANSWERED_PREF, value).commit()
        Unit
    }

/**
 * Whether the user has chosen which dashboard this panel shows — including choosing to follow the
 * signed-in account's default. That choice leaves `home_dashboard` blank, which is indistinguishable
 * from never having been asked, so like [setupEntityFilterAnswered] the answer is recorded rather than
 * inferred. Internal setup state.
 */
var Config.setupHomeDashboardChosen: Boolean
    get() = prefs.getBoolean(SETUP_HOME_DASHBOARD_CHOSEN_PREF, false)
    set(value) = synchronized(CONFIG_LOCK) {
        prefs.edit().putBoolean(SETUP_HOME_DASHBOARD_CHOSEN_PREF, value).commit()
        Unit
    }

/**
 * A human's "yes, the dashboard is on the panel" — recorded as the configuration fingerprint it was
 * given against, because for a foreign renderer a render can only be attested, never observed. A
 * mismatch against the current fingerprint means the panel has not been shown to work as configured
 * NOW, so the journey re-arms; blank means never attested. Internal setup state, like the flag above.
 */
/**
 * Whether this panel has EVER finished setup — the bit that separates "repair" from "first run".
 * A later revoked token re-arms the journey, and the wizard must then read as a
 * repair of a configured panel, never as a factory reset: the difference between "one thing needs
 * attention" and a user believing everything they built is gone. Stamped by the two completion
 * proofs (frontend-connected, human attestation) and never cleared by re-arming.
 */
var Config.setupEverCompleted: Boolean
    get() = prefs.getBoolean(SETUP_EVER_COMPLETED_PREF, false)
    set(value) = synchronized(CONFIG_LOCK) {
        prefs.edit().putBoolean(SETUP_EVER_COMPLETED_PREF, value).commit()
        Unit
    }

/** A connected renderer is proof, but cannot answer the first-run questions for its owner. */
internal fun Config.recordBuiltinSetupCompletion() {
    val inputs = io.panelassistant.android.http.SetupJourney.Inputs(
        identityConfirmed = setupIdentityConfirmed,
        panelId = panelId,
        renderer = io.panelassistant.android.http.SetupJourney.RendererChoice.Builtin,
        haUrl = haUrl,
        haCredentialed = haToken.isNotBlank() || haRefreshToken.isNotBlank(),
        haOAuthInFlight = false,
        homeDashboardChosen = setupHomeDashboardChosen,
        entityFilterAnswered = setupEntityFilterAnswered,
        proof = io.panelassistant.android.http.SetupJourney.RenderProof(
            source = io.panelassistant.android.http.SetupJourney.ProofSource.BUILTIN_FRONTEND_CONNECTED,
            certain = true,
        ),
    )
    if (io.panelassistant.android.http.SetupJourney.evaluate(inputs).complete) setupEverCompleted = true
}

var Config.setupRenderAttestation: String
    get() = prefs.getString(SETUP_RENDER_ATTESTED_PREF, "") ?: ""
    set(value) = synchronized(CONFIG_LOCK) {
        prefs.edit().putString(SETUP_RENDER_ATTESTED_PREF, value).commit()
        Unit
    }

private const val SETUP_IDENTITY_CONFIRMED_PREF = "device_local_setup_identity_confirmed"
private const val SETUP_ENTITY_FILTER_ANSWERED_PREF = "device_local_setup_entity_filter_answered"
private const val SETUP_HOME_DASHBOARD_CHOSEN_PREF = "device_local_setup_home_dashboard_chosen"
private const val SETUP_QUESTION_MIGRATION_PREF = "device_local_setup_questions_migrated_v1"
private const val SETUP_HOME_DASHBOARD_MIGRATION_PREF = "device_local_setup_home_dashboard_migrated_v2"
private const val SETUP_RENDER_ATTESTED_PREF = "device_local_setup_render_attested"
internal const val SETUP_EVER_COMPLETED_PREF = "device_local_setup_ever_completed"
