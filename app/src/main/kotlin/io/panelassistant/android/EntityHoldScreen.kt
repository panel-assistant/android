package io.panelassistant.android

import android.content.Context
import android.view.View
import android.widget.Button
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the entity-hold screen's actions do; the dashboard activity supplies them. */
internal interface EntityHoldActions {
    /** Open the panel's own settings at [path]. */
    fun openSettings(path: String)

    /** Accept the flagged entities and continue; blocking, run off the main thread. True when it started. */
    fun ignoreFlagged(): Boolean

    /** Turn the automatic entity filter off; blocking, run off the main thread. True when it was turned off. */
    fun disableFilter(): Boolean

    /** Try the unusable entity filter again. */
    fun retryFilter()

    /** Run the failed or refused entity scan again. */
    fun retryScan()
}

/** The first scan's live views, which the activity updates on each bootstrap poll. */
internal class EntityLearningViews(val hero: TextView, val line: TextView, val track: NoticeTrack, val hint: TextView)

/**
 * The rows of one entity hold, in the Notice's order: the count or a hero word, the line, the actions, then
 * the explanation, which stays word for word. [filterHoldDetail] is the localised reason an
 * unusable filter was held. Disabled-while-working buttons come back only if their action did not start,
 * and only while [alive] holds, so a replaced screen is never touched.
 */
internal fun entityHoldRows(
    context: Context,
    notice: Notice,
    kind: EntityHoldKind,
    blockingIssues: Int,
    filterHoldDetail: String?,
    scope: CoroutineScope,
    alive: () -> Boolean,
    actions: EntityHoldActions,
): Pair<List<View?>, EntityLearningViews?> {
    fun text(id: Int, vararg args: Any) = context.getString(id, *args)
    return when (kind) {
        EntityHoldKind.LEARNING -> {
            // Deterministic milestones, not a spinner: nobody trusts the circle (hardware review), and the
            // count updates with the live scan on every bootstrap poll tick.
            val views = EntityLearningViews(
                notice.hero(""), notice.line(""), notice.track(ENTITY_STEPS.map(context::getString), 1_000),
                notice.footer(text(R.string.entity_scan_preparing_detail)),
            )
            listOf(
                views.hero, views.line, views.track.view,
                notice.actions(notice.action(text(R.string.open_entity_settings)) { actions.openSettings("/entities") }),
                views.hint,
                notice.footer(text(R.string.entity_learning_reload_notice)),
            ) to views
        }
        EntityHoldKind.ATTENTION, EntityHoldKind.TOO_MANY -> {
            val buttons = mutableListOf<Button>()
            if (kind == EntityHoldKind.ATTENTION) {
                buttons += notice.action(text(R.string.ignore_flagged_entities), primary = true) { button ->
                    button.isEnabled = false
                    button.setText(R.string.preparing_dashboard)
                    scope.launch {
                        val started = withContext(Dispatchers.IO) { actions.ignoreFlagged() }
                        if (!started && alive()) {
                            button.isEnabled = true
                            button.setText(R.string.ignore_flagged_entities)
                        }
                    }
                }
            }
            buttons += notice.action(text(R.string.disable_entity_filter)) { button ->
                button.isEnabled = false
                scope.launch {
                    val disabled = withContext(Dispatchers.IO) { actions.disableFilter() }
                    if (!disabled && alive()) button.isEnabled = true
                }
            }
            buttons += notice.action(text(R.string.open_entity_settings)) { actions.openSettings("/entities") }
            listOf(
                notice.hero(java.text.NumberFormat.getIntegerInstance().format(blockingIssues)),
                notice.line(text(R.string.notice_line_entities_need_decision)),
                notice.actions(*buttons.toTypedArray()),
                notice.footer(
                    if (kind == EntityHoldKind.TOO_MANY) text(R.string.too_many_flagged_entities)
                    else text(R.string.notice_attention_explanation),
                ),
            ) to null
        }
        EntityHoldKind.FILTER_HOLD, EntityHoldKind.SCAN_REFUSED, EntityHoldKind.SCAN_FAILED -> {
            val tryAgain = notice.action(text(R.string.try_again), primary = true) {
                if (kind == EntityHoldKind.FILTER_HOLD) actions.retryFilter() else actions.retryScan()
            }
            // Configure for both repairs that live there — the sign-in, and the entity-filter switch
            // (`dashboard_entity_learning`), which an Entities reset preserves. Entities only where the task
            // really is reviewing entities.
            val settings = if (kind == EntityHoldKind.SCAN_FAILED) {
                notice.action(text(R.string.open_entity_settings)) { actions.openSettings("/entities") }
            } else {
                notice.action(text(R.string.configure)) { actions.openSettings("/configure") }
            }
            listOf(
                notice.hero(text(requireNotNull(kind.heroWord)), word = true),
                notice.line(text(requireNotNull(kind.title))),
                notice.actions(tryAgain, settings),
                notice.footer(when (kind) {
                    EntityHoldKind.FILTER_HOLD -> text(R.string.entity_filter_hold_detail, requireNotNull(filterHoldDetail))
                    EntityHoldKind.SCAN_REFUSED -> text(R.string.entity_scan_auth_rejected)
                    else -> text(R.string.entity_scan_failed_detail)
                }),
            ) to null
        }
    }
}
