package io.github.maxlyth.hapaneld.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The hand-back decision, which is what stands between a tamed panel and `FallbackHome`.
 *
 * Every case here is a state a real panel reaches: a panel carrying ownership markers for its whole vendor
 * stack, a panel tamed only by the host provisioner (which records nothing), a firmware that ships vendor
 * apps disabled, and the retry after a partial failure.
 */
class HandBackHomePolicyTest {

    private val own = "io.github.maxlyth.hapaneld"
    private val launcher = "com.smartos.xinch.launcher"
    private val monitor = "com.smartos.xinch.monitor"

    private fun ready(vararg pkgs: String): TameOwnedMarkers =
        TameOwnedMarkers.Ready(pkgs.associateWith { TameOwnedMarker(it, "deny") })

    private fun present(disabledByUser: Boolean?) =
        HandBackHomePolicy.PackageState(present = true, disabledByUser = disabledByUser)

    private fun homes(vararg candidates: HandBackHomePolicy.HomeCandidate) = candidates.toList()

    private fun home(pkg: String, enabled: Boolean) =
        HandBackHomePolicy.HomeCandidate(pkg, "$pkg/.Launcher", enabled)

    private fun decide(
        owned: TameOwnedMarkers = ready(),
        profileKnown: Set<String> = emptySet(),
        observed: Map<String, HandBackHomePolicy.PackageState> = emptyMap(),
        homeCandidates: List<HandBackHomePolicy.HomeCandidate>? = homes(
            home(own, enabled = true),
            home(launcher, enabled = false),
        ),
    ) = HandBackHomePolicy.decide(owned, profileKnown, observed, homeCandidates, own)

    private fun proceed(decision: HandBackHomePolicy.Decision): HandBackHomePolicy.Plan {
        assertTrue("expected a plan, got $decision", decision is HandBackHomePolicy.Decision.Proceed)
        return (decision as HandBackHomePolicy.Decision.Proceed).plan
    }

    private fun refusal(decision: HandBackHomePolicy.Decision): HandBackHomePolicy.Refusal {
        assertTrue("expected a refusal, got $decision", decision is HandBackHomePolicy.Decision.Refuse)
        return (decision as HandBackHomePolicy.Decision.Refuse).reason
    }

    // ── recorded-set replay ────────────────────────────────────────────────────────────────────────

    @Test
    fun `every owned package is replayed and nothing else is touched`() {
        val plan = proceed(
            decide(
                owned = ready(launcher, monitor),
                // A profile-known package that is NOT disabled must not be swept in just for being listed.
                profileKnown = setOf(launcher, monitor, "com.smartos.xinch.provision"),
                observed = mapOf("com.smartos.xinch.provision" to present(disabledByUser = false)),
            ),
        )
        assertEquals(setOf(launcher, monitor), plan.restore)
        assertEquals(emptyList<String>(), plan.adopt)
    }

    @Test
    fun `a package ha-paneld never disabled is never enabled`() {
        // The firmware shipped this one disabled: present, but not disabled by any user action.
        val plan = proceed(
            decide(
                owned = ready(launcher),
                profileKnown = setOf(launcher, "com.vendor.shipped.off"),
                observed = mapOf("com.vendor.shipped.off" to present(disabledByUser = false)),
            ),
        )
        assertEquals(setOf(launcher), plan.restore)
        assertFalse("a firmware-disabled package must never be adopted", "com.vendor.shipped.off" in plan.adopt)
    }

    @Test
    fun `an unowned package outside the profile is never adopted however it is disabled`() {
        val plan = proceed(
            decide(
                owned = ready(launcher),
                profileKnown = setOf(launcher),
                observed = mapOf("com.unrelated.app" to present(disabledByUser = true)),
            ),
        )
        assertEquals(emptyList<String>(), plan.adopt)
    }

    // ── empty record ───────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a host-tamed panel with no markers adopts exactly its profile packages`() {
        val plan = proceed(
            decide(
                owned = ready(),
                profileKnown = setOf(launcher, monitor),
                observed = mapOf(
                    launcher to present(disabledByUser = true),
                    monitor to present(disabledByUser = true),
                ),
            ),
        )
        assertEquals(emptySet<String>(), plan.restore)
        assertEquals(listOf(launcher, monitor), plan.adopt)
        assertEquals(launcher, plan.targetHome.pkg)
    }

    @Test
    fun `an untamed panel still hands the home role to an enabled launcher`() {
        // Nothing recorded and nothing adoptable, but ha-paneld still holds the preferred-HOME entry, which
        // is what stops Settings uninstalling it. Handing the role over is the whole point of the action.
        val plan = proceed(
            decide(
                owned = ready(),
                profileKnown = emptySet(),
                homeCandidates = homes(home(own, enabled = true), home("com.other.launcher", enabled = true)),
            ),
        )
        assertEquals(emptySet<String>(), plan.restore)
        assertEquals(emptyList<String>(), plan.adopt)
        assertEquals("com.other.launcher", plan.targetHome.pkg)
    }

    @Test
    fun `a panel whose only home app is ha-paneld refuses rather than stranding itself`() {
        assertEquals(
            HandBackHomePolicy.Refusal.NO_REPLACEMENT_HOME,
            refusal(decide(homeCandidates = homes(home(own, enabled = true)))),
        )
    }

    @Test
    fun `a launcher this plan re-enables outranks an unrelated launcher that is already enabled`() {
        val plan = proceed(
            decide(
                owned = ready(launcher),
                homeCandidates = homes(
                    home(own, enabled = true),
                    home("com.aaa.other", enabled = true),
                    home(launcher, enabled = false),
                ),
            ),
        )
        assertEquals(launcher, plan.targetHome.pkg)
    }

    // ── fail closed ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `an unusable ownership snapshot refuses instead of guessing`() {
        for (snapshot in listOf(
            TameOwnedMarkers.Unavailable,
            TameOwnedMarkers.Overflow(257),
            TameOwnedMarkers.Invalid(3),
        )) {
            assertEquals(
                "snapshot $snapshot must refuse",
                HandBackHomePolicy.Refusal.OWNERSHIP_UNREADABLE,
                refusal(decide(owned = snapshot)),
            )
        }
    }

    @Test
    fun `an unreadable package state refuses rather than inferring absence`() {
        assertEquals(
            HandBackHomePolicy.Refusal.PACKAGE_STATE_UNKNOWN,
            refusal(
                decide(
                    profileKnown = setOf(monitor),
                    observed = mapOf(monitor to present(disabledByUser = null)),
                ),
            ),
        )
    }

    @Test
    fun `a profile package with no observation at all refuses`() {
        assertEquals(
            HandBackHomePolicy.Refusal.PACKAGE_STATE_UNKNOWN,
            refusal(decide(profileKnown = setOf(monitor), observed = emptyMap())),
        )
    }

    @Test
    fun `a failed home query refuses rather than assuming no launcher exists`() {
        assertEquals(
            HandBackHomePolicy.Refusal.PACKAGE_STATE_UNKNOWN,
            refusal(decide(homeCandidates = null)),
        )
    }

    @Test
    fun `an absent profile package is skipped without refusing`() {
        // An uninstalled package has no state to read and nothing to re-enable; that is not the same as an
        // unreadable one. The home candidate is enabled here so the only thing under test is the skip.
        val plan = proceed(
            decide(
                profileKnown = setOf(monitor),
                observed = mapOf(monitor to HandBackHomePolicy.PackageState(present = false, disabledByUser = null)),
                homeCandidates = homes(home(own, enabled = true), home(launcher, enabled = true)),
            ),
        )
        assertEquals(emptyList<String>(), plan.adopt)
        assertEquals(launcher, plan.targetHome.pkg)
    }

    // ── home handback readback ─────────────────────────────────────────────────────────────────────

    @Test
    fun `the home handback is only secured when readback names the intended package`() {
        assertTrue(HandBackHomePolicy.homeHandbackSecured(true, launcher, launcher, own))
        assertFalse(
            "the actuator's exit status alone is not proof",
            HandBackHomePolicy.homeHandbackSecured(false, launcher, launcher, own),
        )
        assertFalse(
            "readback showing ha-paneld still holds HOME is a failure",
            HandBackHomePolicy.homeHandbackSecured(true, own, own, own),
        )
        assertFalse(
            "landing on some other launcher is not the handback that was planned",
            HandBackHomePolicy.homeHandbackSecured(true, "com.third.party", launcher, own),
        )
        assertFalse(
            "an unreadable home is not a confirmation",
            HandBackHomePolicy.homeHandbackSecured(true, null, launcher, own),
        )
    }

    // ── partial failure and retry ──────────────────────────────────────────────────────────────────

    @Test
    fun `a partial run is incomplete and names exactly what a retry must pick up`() {
        val outcome = HandBackHomePolicy.Outcome(
            restored = listOf(launcher),
            adopted = emptyList(),
            outstanding = listOf(monitor),
            homeHandedTo = launcher,
        )
        assertFalse("an outstanding package means the run is not complete", outcome.complete)
        assertEquals(listOf(monitor), outcome.outstanding)
    }

    @Test
    fun `uninstall is safe once home moved even while packages remain outstanding`() {
        // The two questions are separate: HOME is what strands the panel and what blocks Settings, whereas a
        // vendor app that is still disabled is an inconvenience a retry fixes.
        val outcome = HandBackHomePolicy.Outcome(
            restored = emptyList(),
            adopted = emptyList(),
            outstanding = listOf(monitor),
            homeHandedTo = launcher,
        )
        assertTrue(outcome.uninstallSafe)
        assertFalse(outcome.complete)
    }

    @Test
    fun `uninstall is never safe while ha-paneld still holds home`() {
        val outcome = HandBackHomePolicy.Outcome(
            restored = listOf(launcher, monitor),
            adopted = emptyList(),
            outstanding = emptyList(),
            homeHandedTo = null,
        )
        assertFalse(outcome.uninstallSafe)
        assertFalse(outcome.complete)
    }

    @Test
    fun `a retry after everything succeeded plans no package work and still confirms home`() {
        // Second run on a converged panel: markers are gone, the profile packages are enabled again, so the
        // plan is empty apart from re-confirming the home role. This is what makes the action idempotent.
        val plan = proceed(
            decide(
                owned = ready(),
                profileKnown = setOf(launcher, monitor),
                observed = mapOf(
                    launcher to present(disabledByUser = false),
                    monitor to present(disabledByUser = false),
                ),
                homeCandidates = homes(home(own, enabled = true), home(launcher, enabled = true)),
            ),
        )
        assertEquals(emptySet<String>(), plan.restore)
        assertEquals(emptyList<String>(), plan.adopt)
        assertEquals(launcher, plan.targetHome.pkg)
    }
}
