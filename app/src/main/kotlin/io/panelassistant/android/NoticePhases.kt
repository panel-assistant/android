package io.panelassistant.android

import io.panelassistant.android.dashboard.EntityBootstrapProblem

/**
 * What each native panel screen puts in its [Notice]'s slots, decided without Android so every phase is
 * assertable at the seam the screen calls (`NoticeTest`). The screens turn these into words and views.
 */
/**
 * The reload notice's line for a deliberate reload's [reason], or null to show the caller's own reason.
 * The two reasons the panel itself gives most are worded as sentences of their own.
 */
@androidx.annotation.StringRes
internal fun reloadNoticeReason(reason: String): Int? = when (reason) {
    io.panelassistant.android.control.BuiltinDashboard.LEARNING_RELOAD_REASON, "updating the entity filter" ->
        R.string.reload_optimising_entities
    "applying your settings", "clearing the dashboard’s stored data", "" -> R.string.reload_applying_changes
    else -> null
}

/**
 * The waiting-for-network notice at one moment: the step (0 Network, 1 Connecting, 2 Opening), the hero
 * clock, and the track's fills out of 1,000.
 *
 * The hero counts down to the panel's learned usual wait while there is time left on it, and counts the
 * elapsed time otherwise (still learning, or past the usual). The track is one timeline, the same
 * [networkWaitProgress] spread over the three steps, and never shows a step already passed as unfilled.
 */
internal data class NetworkWaitNotice(val step: Int, val heroMs: Long, val countingDown: Boolean, val fills: List<Int>)

internal fun networkWaitNotice(
    snapshot: StartupNetworkSnapshot,
    elapsedMs: Long,
    estimateMs: Long,
    learned: Boolean,
): NetworkWaitNotice {
    val step = when {
        !snapshot.interfacePresent || !snapshot.linkUp || !snapshot.addressAssigned -> 0
        !snapshot.defaultNetwork -> 1
        else -> 2
    }
    val elapsed = elapsedMs.coerceAtLeast(0L)
    val countingDown = learned && elapsed < estimateMs
    val progress = networkWaitProgress(elapsed, estimateMs)
    val fills = List(3) { i -> if (i < step) 1_000 else (progress * 3 - i * 1_000).coerceIn(0, 1_000) }
    return NetworkWaitNotice(step, if (countingDown) estimateMs - elapsed else elapsed, countingDown, fills)
}

/** The pill of a compatibility screen: work in flight, a sign-in, a retry, or an action. */
@androidx.annotation.StringRes
internal fun admissionNoticePill(progress: Boolean, signIn: Boolean, retries: Boolean): Int = when {
    progress -> R.string.notice_pill_starting_up
    signIn -> R.string.notice_pill_sign_in
    retries -> R.string.notice_pill_retrying
    else -> R.string.notice_pill_action_needed
}

/** The line and the prompt beside the QR for an outcome a phone can fix, or null when none can. */
internal data class AdmissionQrSlots(@androidx.annotation.StringRes val line: Int, @androidx.annotation.StringRes val prompt: Int)

internal fun admissionQrSlots(outcome: AdmissionOutcome?): AdmissionQrSlots? = when (outcome) {
    AdmissionOutcome.SIGN_IN_REQUIRED -> AdmissionQrSlots(R.string.notice_line_sign_in_needed, R.string.notice_qr_sign_in)
    AdmissionOutcome.CREDENTIAL_REFUSED -> AdmissionQrSlots(R.string.notice_line_sign_in_stopped, R.string.notice_qr_sign_in_again)
    else -> null
}

/**
 * Which entity hold the panel shows, in the order today's screen decided it: an unusable filter first, then
 * entities awaiting review (which the panel can or cannot offer to ignore), then a refused or failed scan,
 * else the first scan running. Each carries its pill word and, where it has no number, its hero word and
 * its title as the line.
 */
internal enum class EntityHoldKind(
    @androidx.annotation.StringRes val pill: Int,
    @androidx.annotation.StringRes val heroWord: Int?,
    @androidx.annotation.StringRes val title: Int?,
) {
    LEARNING(R.string.notice_pill_learning, null, null),
    ATTENTION(R.string.notice_pill_action_needed, null, null),
    TOO_MANY(R.string.notice_pill_action_needed, null, null),
    FILTER_HOLD(R.string.notice_pill_action_needed, R.string.notice_hero_filter_failed, R.string.optimized_subscription_unavailable),
    SCAN_REFUSED(R.string.notice_pill_sign_in, R.string.notice_hero_filter_refused, R.string.ha_refused_entity_scan),
    SCAN_FAILED(R.string.notice_pill_action_needed, R.string.notice_hero_filter_failed, R.string.dashboard_scan_failed),
}

internal fun entityHoldKind(
    blockingIssues: Int,
    filterHold: Boolean,
    problem: EntityBootstrapProblem?,
    canIgnoreBlockingIssues: Boolean,
): EntityHoldKind = when {
    filterHold -> EntityHoldKind.FILTER_HOLD
    blockingIssues > 0 -> if (canIgnoreBlockingIssues) EntityHoldKind.ATTENTION else EntityHoldKind.TOO_MANY
    problem == EntityBootstrapProblem.AUTHENTICATION -> EntityHoldKind.SCAN_REFUSED
    problem != null -> EntityHoldKind.SCAN_FAILED
    else -> EntityHoldKind.LEARNING
}

/** The first scan's progress: the count read so far (the hero), the line, and the step on the track. */
internal data class EntityLearningNotice(
    val count: Int?,
    @androidx.annotation.StringRes val line: Int,
    val step: Int,
    val expectedTotal: Int? = null,
) {
    val fills: List<Int> get() = listOf(
        if (step > 0) 1_000 else expectedTotal?.let { ((count ?: 0).coerceAtLeast(0).toLong() * 1_000 / it).coerceAtMost(990).toInt() } ?: 0,
        0, 0,
    )
}

internal fun entityLearningNotice(stage: Int?, count: Int?, expectedTotal: Int? = null): EntityLearningNotice =
    if (stage == null || stage <= 1) expectedTotal?.takeIf { it > 0 && count != null }?.let {
        EntityLearningNotice(count, R.string.notice_line_reading_entities_total, 0, it)
    } ?: EntityLearningNotice(count, R.string.notice_line_reading_entities, 0)
    else EntityLearningNotice(count, R.string.notice_line_building_filter, 1)

/** The standing screen's bold line and its hint for one setup stage. The identity hint takes the config button's label. */
internal data class SetupStageLine(@androidx.annotation.StringRes val line: Int, @androidx.annotation.StringRes val hint: Int)

/**
 * The standing screen's line for the setup journey's `next` stage: a passer-by always learns what stage the
 * panel is at and whether they need to act. Null when setup is complete or the stage has no line.
 */
internal fun setupStageLine(complete: Boolean, next: String, rendererDetail: String): SetupStageLine? = when {
    complete -> null // the launcher's own auto-return takes over; show the pre-wizard screen
    next == "identity" -> SetupStageLine(R.string.panel_not_set_up, R.string.panel_not_set_up_hint)
    // A too-old engine is the one renderer problem a passer-by cannot act on here, so name it as the
    // panel's own fault rather than asking for a choice that would not help.
    next == "renderer" && rendererDetail.startsWith("webview_too_old") ->
        SetupStageLine(R.string.browser_engine_too_old, R.string.browser_engine_too_old_hint)
    next == "renderer" -> SetupStageLine(R.string.choose_dashboard_app, R.string.continue_setup_browser)
    next == "ha_url" -> SetupStageLine(R.string.connected_next_ha_address, R.string.continue_setup_browser)
    next == "ha_credentials" -> SetupStageLine(R.string.almost_there_sign_in, R.string.continue_or_sign_in_here)
    next == "home_dashboard" -> SetupStageLine(R.string.choose_home_dashboard, R.string.continue_setup_browser)
    // The panel is deliberately holding its first load here, so it must say that rather than look
    // stalled — and say the wait ends in a browser, since nothing on this screen can end it.
    next == "entity_filter" -> SetupStageLine(R.string.one_important_question_left, R.string.finish_browser_optimize)
    next == "render_proof" -> SetupStageLine(R.string.loading_dashboard, R.string.nothing_to_do_here)
    else -> null
}

/**
 * The standing screen's pill: "Setup" while a later setup stage is running, "Not set up" on a panel that
 * has never completed setup, and none on a panel that has. A panel that has completed once shows "Setup"
 * only while a person is needed ([needsUser]), so its process-scoped render_proof wait shows no pill.
 */
@androidx.annotation.StringRes
internal fun standingNoticePill(stagePresent: Boolean, identity: Boolean, needsUser: Boolean, everCompleted: Boolean): Int? = when {
    stagePresent && !identity && (needsUser || !everCompleted) -> R.string.notice_pill_setup
    !everCompleted -> R.string.notice_pill_not_set_up
    else -> null
}

/** The waiting-for-network track's steps, in [networkWaitNotice]'s order. */
internal val NETWORK_STEPS = listOf(R.string.network_step_network, R.string.network_step_connecting, R.string.network_step_opening)

/** The first entity scan's track steps, in [entityLearningNotice]'s order. */
internal val ENTITY_STEPS = listOf(R.string.entity_step_read, R.string.entity_step_filter, R.string.entity_step_open)
