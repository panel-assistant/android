package io.panelassistant.android.dashboard

/**
 * True while automatic learning has no allow-list that can safely be installed at document start.
 *
 * This is deliberately a renderer-side invariant rather than a provisioning convention. Activity,
 * watchdog and HOME relaunches can happen at any point during a catalog synchronization; none may turn
 * an empty automatic learner into an ordinary unfiltered Home Assistant WebSocket connection.
 */
/**
 * [autoScopeUnverified] extends the same invariant to a filter that exists but may describe the wrong
 * dashboard. Under `Auto` a retained allow-list was learned for whatever the account default used to
 * resolve to, and that can change while the panel is stopped. Installing it at document start would
 * filter the newly-opened dashboard through the previous dashboard's ids — cards that silently stop
 * updating — so an unverified `Auto` scope holds exactly as an absent list does, and for the same
 * reason: the renderer must never install an allow-list nobody can vouch for.
 */
internal fun shouldHoldRendererForEntityBootstrap(
    learningEnabled: Boolean,
    filterEnabled: Boolean,
    autoScopeUnverified: Boolean = false,
): Boolean = learningEnabled && (!filterEnabled || autoScopeUnverified)

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
