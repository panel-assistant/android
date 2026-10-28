package io.panelassistant.android.dashboard

import io.panelassistant.android.Config
import io.panelassistant.android.dashboardEntityFilterEnabled
import io.panelassistant.android.dashboardEntityLearningEnabled

/**
 * True while automatic learning has no allow-list that can safely be installed at document start.
 *
 * Activity, watchdog and HOME relaunches can happen at any point during a catalog synchronization; none
 * may install a list nobody can vouch for, so the renderer holds behind a native screen instead.
 */
/**
 * [autoScopeUnverified] extends the same rule to a filter that exists but may describe the wrong
 * dashboard. Under `Auto` a retained allow-list was learned for whatever the account default used to
 * resolve to, and that can change while the panel is stopped. Installing it at document start would
 * filter the newly-opened dashboard through the previous dashboard's ids — cards that silently stop
 * updating — so an unverified `Auto` scope holds exactly as an absent list does.
 */
internal fun shouldHoldRendererForEntityBootstrap(
    learningEnabled: Boolean,
    filterEnabled: Boolean,
    autoScopeUnverified: Boolean,
): Boolean = learningEnabled && (!filterEnabled || autoScopeUnverified)

/**
 * Whether learning has a list the renderer may install. Status reports its reason (`hold_reason`) even
 * after the renderer was released, because that is what learning is still waiting for.
 */
internal fun entityLearningHold(config: Config): Boolean = shouldHoldRendererForEntityBootstrap(
    learningEnabled = config.dashboardEntityLearningEnabled,
    filterEnabled = config.dashboardEntityFilterEnabled,
    autoScopeUnverified = AutoScopeVerification.unverified(config.homeDashboard),
)

/**
 * The one hold decision: the renderer applies it, and status, the entity query and the Configure notice
 * report it. The hold is never terminal: once [RendererEntityHold.BOUND_MS] has passed the renderer is
 * released to an unfiltered dashboard rather than kept off screen ([RendererEntityHold]).
 */
internal fun shouldHoldRendererForEntityBootstrap(config: Config): Boolean =
    entityLearningHold(config) && RendererEntityHold.released == null

/** A learner may continue synchronizing while another renderer is selected, but its completion must not
 * launch the built-in activity unless that activity is still the effective renderer. The caller resolves
 * Auto before this comparison so the automatic built-in renderer is treated consistently.
 *
 * While the wizard's entity-filter question is still OPEN on a first run (never answered, setup never
 * completed), a filter change must not reload either: the renderer is deliberately held on the question
 * screen, and the answer route performs the one release itself. Without this the wizard's enable click
 * produced two back-to-back relaunches — the config commit's reload and the answer's launch, 350 ms
 * apart on hardware — the first flashes of what the observer then counted as a reload storm. */
internal fun shouldReloadBuiltinAfterEntityFilterChange(
    effectiveDashboardPackage: String,
    builtinPackage: String,
    setupEntityFilterAnswered: Boolean = true,
    setupEverCompleted: Boolean = true,
): Boolean = effectiveDashboardPackage == builtinPackage &&
    (setupEntityFilterAnswered || setupEverCompleted)
