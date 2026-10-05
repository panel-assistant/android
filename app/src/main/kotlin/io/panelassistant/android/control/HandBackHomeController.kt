package io.panelassistant.android.control

/**
 * Executes one [HandBackHomePolicy] decision, in the only order that cannot strand the panel.
 *
 * Every collaborator is injected, because the ordering is the feature: three separate loops in the running
 * app re-assert ha-paneld as HOME ([TameDesiredStateReconciler]'s re-assert pass, the admin-home repair tick,
 * and `ensureDashboardHome` on boot and config apply), so desired state has to be cleared *before* the HOME
 * role moves, or the panel silently takes it straight back.
 *
 * Nothing here trusts an actuator's exit status. A package counts as handed back only when a fresh read says
 * it is no longer disabled, and HOME counts as handed over only when a fresh read names the intended package.
 */
internal class HandBackHomeController(
    private val ownedMarkers: () -> TameOwnedMarkers,
    private val packageStates: (Set<String>) -> Map<String, HandBackHomePolicy.PackageState>,
    private val homeCandidates: () -> List<HandBackHomePolicy.HomeCandidate>?,
    /** Empties `tame_vendor_packages` and `launcher_package`. Must precede the HOME handover. */
    private val clearDesiredState: () -> Boolean,
    /** The existing marker-driven rollback: `pm enable` plus the exact pre-tame overlay app-op. */
    private val restoreOwned: () -> Unit,
    /** Re-enable an unowned package the profile authorises adopting. */
    private val enable: (String) -> Boolean,
    private val setHome: (component: String) -> Boolean,
    private val observeHome: () -> String?,
    private val ownPackage: String,
) {

    sealed interface Result {
        data class Done(val outcome: HandBackHomePolicy.Outcome) : Result
        data class Refused(val reason: HandBackHomePolicy.Refusal) : Result
    }

    /**
     * @param profileKnown packages this panel's device profile names as ones ha-paneld tames on this
     *   hardware — the only authority that may adopt a package carrying no ownership marker.
     */
    fun handBack(profileKnown: Set<String>): Result {
        val owned = ownedMarkers()
        val markerPackages = (owned as? TameOwnedMarkers.Ready)?.byPackage?.keys.orEmpty()
        val homes = homeCandidates()
        val interesting = markerPackages + profileKnown + homes.orEmpty().map { it.pkg }
        // A query that threw tells us nothing, and an empty map would read as "every package is absent" —
        // which is the inference this whole feature is forbidden to make.
        val observed = runCatching { packageStates(interesting) }.getOrNull()
            ?: return Result.Refused(HandBackHomePolicy.Refusal.PACKAGE_STATE_UNKNOWN)

        val currentHome = runCatching { observeHome() }.getOrNull()
        val plan = when (
            val decision =
                HandBackHomePolicy.decide(owned, profileKnown, observed, homes, ownPackage, currentHome)
        ) {
            is HandBackHomePolicy.Decision.Refuse -> return Result.Refused(decision.reason)
            is HandBackHomePolicy.Decision.Proceed -> decision.plan
        }

        // Clearing desired state first is what stops the re-assert loops undoing everything below. Failing
        // here is a refusal, not a partial run: re-enabling the vendor launcher while ha-paneld still intends
        // to tame it would be reverted within the next reconcile pass.
        if (!clearDesiredState()) return Result.Refused(HandBackHomePolicy.Refusal.OWNERSHIP_UNREADABLE)

        restoreOwned()
        for (pkg in plan.adopt) enable(pkg)

        // Re-read rather than trusting either actuator. A package still disabled is outstanding, and keeps
        // whatever marker it has, so a retry picks it up and converges. An unreadable state stays outstanding
        // for the same reason — this is after the mutation, so refusing now would report less than we know.
        val after = runCatching { packageStates(plan.restore + plan.adopt) }.getOrDefault(emptyMap())
        val outstanding = (plan.restore + plan.adopt).filter { pkg ->
            val state = after[pkg]
            // Unreadable counts as outstanding: it is the fail-closed direction for a retry.
            state == null || (state.present && state.disabledByUser != false)
        }.sorted()
        val restored = plan.restore.filterNot { it in outstanding }.sorted()
        val adopted = plan.adopt.filterNot { it in outstanding }.sorted()

        // The replacement home has to be genuinely enabled before the role moves; handing it to a package
        // that is still disabled is the FallbackHome failure with extra steps.
        val target = plan.targetHome
        if (target.pkg in outstanding) {
            return Result.Done(HandBackHomePolicy.Outcome(restored, adopted, outstanding, homeHandedTo = null))
        }
        val handedOver = setHome(target.component)
        val observedHome = observeHome()
        val secured = HandBackHomePolicy.homeHandbackSecured(handedOver, observedHome, target.pkg, ownPackage)
        return Result.Done(
            HandBackHomePolicy.Outcome(
                restored = restored,
                adopted = adopted,
                outstanding = outstanding,
                homeHandedTo = target.pkg.takeIf { secured },
            ),
        )
    }
}
