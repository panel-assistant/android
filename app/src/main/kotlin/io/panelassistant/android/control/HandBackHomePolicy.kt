package io.panelassistant.android.control

import io.panelassistant.android.config.TamePackagePolicy

/**
 * **Handing the home screen back.** Taming a panel disables its vendor launcher, and ha-paneld then holds
 * Android's preferred-HOME entry. Removing ha-paneld from that state leaves the device with no home screen
 * at all: it sits on `com.android.settings/.FallbackHome` ("Android is starting…"), which reads as a brick,
 * and Android Settings will not uninstall the explicitly preferred Home app in the first place. So a panel
 * tamed by ha-paneld has no clean way out unless ha-paneld provides one.
 *
 * This policy decides that exit. It is deliberately Android-free so the destructive ordering has
 * deterministic tests, in the same spirit as [TameStatePolicy].
 *
 * Three rules shape it:
 *
 *  1. **Never enable a package ha-paneld did not disable.** The authority is the write-ahead ownership
 *     marker ([TameOwnedMarker]). Where no marker exists — a panel tamed by the host provisioner, which
 *     records nothing — a package may still be adopted, but only when it is named by the panel's own
 *     device profile AND Android reports it disabled *by a user action* rather than shipped disabled.
 *  2. **Fail closed.** An unusable marker snapshot, an unreadable package state, or no replacement home
 *     refuses the whole operation. A failed query never proves absence.
 *  3. **Never surrender HOME without somewhere for it to go.** Handing the role away when nothing else can
 *     take it is precisely how a panel reaches `FallbackHome`.
 */
internal object HandBackHomePolicy {

    /** Why the panel cannot be handed its home screen back. Each maps to one user-facing message. */
    enum class Refusal {
        /** The ownership markers are overflowed, malformed or unreadable, so rollback cannot be exact. */
        OWNERSHIP_UNREADABLE,

        /** A package's presence or enabled state could not be read. Absence is never inferred. */
        PACKAGE_STATE_UNKNOWN,

        /** No other app on this device can take the HOME role, so surrendering it would strand the panel. */
        NO_REPLACEMENT_HOME,
    }

    /**
     * What Android says about one package. [disabledByUser] distinguishes `COMPONENT_ENABLED_STATE_DISABLED_USER`
     * — what `pm disable-user` produces, and therefore what ha-paneld or the provisioner did — from a package
     * the firmware shipped disabled. `null` means the state could not be read, which refuses rather than
     * guesses.
     */
    data class PackageState(val present: Boolean, val disabledByUser: Boolean?)

    /** Why a record of an externally-performed disable was or was not written. */
    enum class RecordOutcome {
        /** A marker now exists for this package and hand-back will reverse it. */
        RECORDED,

        /** The package is not disabled-by-user, so ha-paneld refuses to claim it disabled it. */
        NOT_DISABLED,

        /** Presence or enabled state could not be read; nothing was claimed. */
        UNKNOWN,

        /** The marker could not be persisted. */
        FAILED,
    }

    /** A package that declares `CATEGORY_HOME`, and whether it can take the role in its current state. */
    data class HomeCandidate(val pkg: String, val component: String, val enabled: Boolean)

    /**
     * A package that must never receive the HOME role, whatever it declares.
     *
     * This is deliberately [TamePackagePolicy.isCritical] rather than a list of its own. That set already
     * names `com.android.settings` — which declares `FallbackHome`, the "Android is starting…" placeholder,
     * and is therefore a HOME candidate on every device — and it already names both of this app's
     * application ids through `AppIdentity.ALL`. A local list here had only the first, so hand back could
     * give HOME to ha-paneld's other identity: enabled mid-migration, declaring `CATEGORY_HOME`, and
     * sorting ahead of a vendor launcher. That is the same brick by another route, and the migration would
     * then uninstall the package now holding the role.
     *
     * One definition. When the untouchable set grows, this grows with it.
     */
    private fun ineligibleForHome(pkg: String): Boolean = TamePackagePolicy.isCritical(pkg)

    /**
     * The ordered work of one hand-back.
     *
     * [restore] are owned packages, reversed through the existing reconciler so each one regains its exact
     * pre-tame overlay app-op and drops its marker only on success. [adopt] are unowned packages this panel's
     * profile authorises, which are enabled but carry no overlay state to put back. [targetHome] receives the
     * HOME role once both have run.
     */
    data class Plan(
        val restore: Set<String>,
        val adopt: List<String>,
        val targetHome: HomeCandidate,
    )

    sealed interface Decision {
        data class Proceed(val plan: Plan) : Decision
        data class Refuse(val reason: Refusal) : Decision
    }

    /**
     * Decide before mutating anything.
     *
     * The replacement home is chosen *before* the packages are re-enabled, because refusing halfway through
     * would leave a panel with re-enabled vendor apps and ha-paneld still holding HOME — worse than either
     * end state. A candidate qualifies if it is already enabled, or if this plan is about to enable it.
     *
     * @param owned the write-ahead ownership snapshot; anything but [TameOwnedMarkers.Ready] refuses.
     * @param profileKnown packages this panel's device profile names as ones ha-paneld tames on this
     *   hardware. This is the only authority that can adopt an unmarked package.
     * @param observed package state for every marker, every profile-known package and every home candidate.
     * @param homeCandidates every package declaring `CATEGORY_HOME`, including disabled ones, or `null` when
     *   the query failed.
     * @param ownPackage ha-paneld's own package, which can never be its own replacement home.
     * @param currentHome the package holding the HOME role now, or null when it could not be read. When it
     *   is already a real launcher other than ha-paneld, it is the answer, and the run re-confirms it.
     */
    fun decide(
        owned: TameOwnedMarkers,
        profileKnown: Set<String>,
        observed: Map<String, PackageState>,
        homeCandidates: List<HomeCandidate>?,
        ownPackage: String,
        currentHome: String? = null,
    ): Decision {
        val markers = when (owned) {
            is TameOwnedMarkers.Ready -> owned.byPackage
            is TameOwnedMarkers.Overflow,
            is TameOwnedMarkers.Invalid,
            TameOwnedMarkers.Unavailable -> return Decision.Refuse(Refusal.OWNERSHIP_UNREADABLE)
        }
        if (homeCandidates == null) return Decision.Refuse(Refusal.PACKAGE_STATE_UNKNOWN)

        // Ownership decides rollback, exactly as the reconciler does it. A marker for an absent package is
        // still the reconciler's business (it clears the record), so it stays in the restore set.
        val restore = markers.keys.toSortedSet()

        // Adoption is the only path that touches a package with no marker, so it is the narrowest rule here:
        // named by this panel's profile, present, and disabled by a user action rather than by the firmware.
        val adoptable = profileKnown - restore
        val adopt = sortedSetOf<String>()
        for (pkg in adoptable.toSortedSet()) {
            val state = observed[pkg] ?: return Decision.Refuse(Refusal.PACKAGE_STATE_UNKNOWN)
            if (!state.present) continue
            when (state.disabledByUser) {
                null -> return Decision.Refuse(Refusal.PACKAGE_STATE_UNKNOWN)
                true -> adopt += pkg
                false -> Unit
            }
        }

        val willBeEnabled = restore + adopt
        val target = homeCandidates
            .filter { it.pkg != ownPackage }
            .filter { !ineligibleForHome(it.pkg) }
            .filter { it.enabled || it.pkg in willBeEnabled }
            .minWithOrNull(
                // A launcher that already holds the role is the answer on a panel that has been handed back
                // once already: re-confirming it makes the retry a clean no-op instead of moving HOME to
                // whatever else happens to be installed.
                compareByDescending<HomeCandidate> { it.pkg == currentHome }
                    // Otherwise a launcher this plan is about to re-enable is the panel's own home screen,
                    // ahead of an unrelated third-party launcher that merely happens to be present.
                    .thenByDescending { it.pkg in willBeEnabled }
                    .thenBy { it.pkg },
            )
            ?: return Decision.Refuse(Refusal.NO_REPLACEMENT_HOME)

        return Decision.Proceed(Plan(restore = restore, adopt = adopt.toList(), targetHome = target))
    }

    /**
     * Confirm the HOME role actually moved, by readback rather than by the actuator's exit status.
     *
     * The inverse of [TameStatePolicy.homeHandoffSecured]: that one proves ha-paneld *took* the role, this
     * one proves it *gave it away* — and to the intended package, not merely to something that is not us.
     */
    fun homeHandbackSecured(setSucceeded: Boolean, observedHome: String?, target: String, ownPackage: String): Boolean =
        setSucceeded && observedHome == target && observedHome != ownPackage

    /**
     * What one hand-back attempt achieved.
     *
     * [outstanding] is what a retry would pick up. It is what keeps the operation idempotent: a package whose
     * re-enable was not confirmed keeps its marker, so running hand back again converges rather than either
     * repeating completed work or silently declaring success.
     */
    data class Outcome(
        val restored: List<String>,
        val adopted: List<String>,
        val outstanding: List<String>,
        val homeHandedTo: String?,
    ) {
        val complete: Boolean get() = outstanding.isEmpty() && homeHandedTo != null

        /** Removing ha-paneld is only safe once it no longer holds HOME; the packages can be retried later. */
        val uninstallSafe: Boolean get() = homeHandedTo != null
    }
}
