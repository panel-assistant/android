package io.panelassistant.android.control

import io.panelassistant.android.Config
import io.panelassistant.android.persistence.SqliteStatePreferences
import io.panelassistant.android.persistence.StateMutation
import io.panelassistant.android.persistence.StateNamespacePersistence
import java.util.concurrent.Executors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ordering of a hand-back, which is the part that can strand a panel.
 *
 * Desired state has to be cleared before the HOME role moves, the replacement home has to be genuinely
 * enabled before it receives the role, and nothing may be believed on an actuator's say-so.
 */
class HandBackHomeControllerTest {

    private val own = "io.github.maxlyth.hapaneld"
    private val launcher = "com.smartos.xinch.launcher"
    private val monitor = "com.smartos.xinch.monitor"

    /**
     * A panel that answers every query, records what was done to it, and re-enables when asked.
     *
     * [homePkgs] is a list of package names rather than ready-made candidates on purpose: a candidate's
     * `enabled` flag is derived from the panel's live state on every query, so a launcher re-enabled by one
     * run is visibly enabled to the next. A static candidate list would have hidden the retry behaviour.
     */
    private class Panel(
        var markers: MutableMap<String, String> = mutableMapOf(),
        var disabled: MutableSet<String> = mutableSetOf(),
        var installed: MutableSet<String> = mutableSetOf(),
        var homePkgs: MutableList<String>? = null,
        var currentHome: String? = "io.github.maxlyth.hapaneld",
        var desiredCleared: Boolean = false,
        var clearSucceeds: Boolean = true,
        var enableRefuses: Set<String> = emptySet(),
        var setHomeSucceeds: Boolean = true,
        var stateReadFails: Boolean = false,
        var previousHome: String? = null,
        var observedComponentOverride: String? = null,
    ) {
        val log = mutableListOf<String>()

        fun states(pkgs: Set<String>): Map<String, HandBackHomePolicy.PackageState> {
            if (stateReadFails) throw IllegalStateException("package query failed")
            return pkgs.associateWith { pkg ->
                HandBackHomePolicy.PackageState(
                    present = pkg in installed,
                    disabledByUser = if (pkg in installed) pkg in disabled else null,
                )
            }
        }

        private fun homes(): List<HandBackHomePolicy.HomeCandidate>? = homePkgs?.map { pkg ->
            HandBackHomePolicy.HomeCandidate(pkg, "$pkg/.Launcher", enabled = pkg in installed && pkg !in disabled)
        }

        fun controller(clear: (() -> Boolean)? = null): HandBackHomeController = HandBackHomeController(
            ownedMarkers = {
                TameOwnedMarkers.Ready(markers.mapValues { (pkg, mode) -> TameOwnedMarker(pkg, mode) })
            },
            packageStates = ::states,
            homeCandidates = ::homes,
            clearDesiredState = clear ?: {
                log += "clear-desired"
                if (clearSucceeds) desiredCleared = true
                clearSucceeds
            },
            restoreOwned = {
                log += "restore-owned"
                // The real reconciler re-enables every owned package and drops its marker on success.
                for (pkg in markers.keys.toList()) {
                    if (pkg in enableRefuses) continue
                    disabled -= pkg
                    markers -= pkg
                }
            },
            enable = { pkg ->
                log += "enable:$pkg"
                if (pkg in enableRefuses) false else { disabled -= pkg; true }
            },
            setHome = { component ->
                log += "set-home:$component"
                if (setHomeSucceeds) currentHome = component.substringBefore('/')
                setHomeSucceeds
            },
            observeHome = { observedComponentOverride ?: currentHome?.let { "$it/.Launcher" } },
            previousHomeComponent = { previousHome },
            ownPackage = "io.github.maxlyth.hapaneld",
        )
    }

    @Test fun `saved original home wins over another current launcher and survives retry`() {
        val panel = tamedPanel()
        val other = "com.other.launcher"
        panel.installed += other
        panel.homePkgs!! += other
        panel.currentHome = other
        panel.previousHome = "$launcher/.Launcher"
        repeat(2) {
            val result = panel.controller().handBack(emptySet()) as HandBackHomeController.Result.Done
            assertTrue(result.outcome.uninstallSafe)
            assertEquals(launcher, panel.currentHome)
        }
    }

    @Test fun `missing original home refuses without clearing desired state`() {
        val panel = tamedPanel()
        panel.previousHome = "com.missing/.Home"
        assertEquals(
            HandBackHomeController.Result.Refused(HandBackHomePolicy.Refusal.ORIGINAL_HOME_MISSING, "com.missing/.Home"),
            panel.controller().handBack(emptySet()),
        )
        assertTrue(panel.log.isEmpty())
    }

    @Test fun `different activity in original package cannot authorize uninstall`() {
        val panel = tamedPanel()
        panel.previousHome = "$launcher/.Launcher"
        panel.observedComponentOverride = "$launcher/.OtherHome"
        val result = panel.controller().handBack(emptySet()) as HandBackHomeController.Result.Done
        assertFalse(result.outcome.uninstallSafe)
    }

    @Test fun `abandoned setup can restore original home without deleting deferred selections`() {
        val panel = tamedPanel()
        panel.previousHome = "$launcher/.Launcher"
        val result = panel.controller(clear = { true }).handBack(emptySet()) as HandBackHomeController.Result.Done
        assertTrue(result.outcome.uninstallSafe)
        assertEquals(launcher, panel.currentHome)
        assertFalse(panel.desiredCleared)
        assertTrue(panel.markers.isEmpty())
    }

    /** The common shape: ha-paneld owns the vendor launcher and one other app, and holds HOME. */
    private fun tamedPanel() = Panel(
        markers = mutableMapOf(launcher to "default", monitor to "deny"),
        disabled = mutableSetOf(launcher, monitor),
        installed = mutableSetOf(own, launcher, monitor),
        homePkgs = mutableListOf(own, launcher),
    )

    /** A config store whose disk refuses writes, so only what reached the disk survives a restart. */
    private class RefusingDisk(initial: Map<String, Any>) : StateNamespacePersistence {
        val disk = initial.toMutableMap()
        override fun initialize(): Map<String, Any> = disk.toMap()
        override fun persist(mutation: StateMutation): Boolean = false
        override fun replace(snapshot: Map<String, Any>): Boolean = false
    }

    @Test
    fun `a desired-state clear that did not reach the disk refuses before HOME moves`() {
        val disk = RefusingDisk(
            mapOf("launcher_package" to own, "tame_vendor_packages" to "$launcher $monitor"),
        )
        val writer = Executors.newSingleThreadExecutor()
        try {
            val config = Config(SqliteStatePreferences(disk, writer))
            val panel = tamedPanel()

            val result = panel.controller(clear = config::clearHandBackDesiredState).handBack(setOf(launcher, monitor))

            // The clear is what stops the admin-home repair tick and the tame reconciler taking everything
            // back after a restart; a clear that is not durable must refuse, not hand HOME over.
            assertEquals(
                HandBackHomeController.Result.Refused(HandBackHomePolicy.Refusal.OWNERSHIP_UNREADABLE),
                result,
            )
            assertFalse(panel.log.any { it.startsWith("set-home:") || it == "restore-owned" })
            assertEquals(own, panel.currentHome)
            // Nothing was published that the disk does not hold.
            assertEquals(own, config.launcherPackage)
            assertEquals(listOf(launcher, monitor), config.tameVendorPackages)
        } finally {
            writer.shutdownNow()
        }
    }

    private fun done(result: HandBackHomeController.Result): HandBackHomePolicy.Outcome {
        assertTrue("expected a completed run, got $result", result is HandBackHomeController.Result.Done)
        return (result as HandBackHomeController.Result.Done).outcome
    }

    @Test
    fun `a tamed panel is restored and the vendor launcher becomes home`() {
        val panel = tamedPanel()
        val outcome = done(panel.controller().handBack(setOf(launcher, monitor)))

        assertEquals(listOf(launcher, monitor), outcome.restored)
        assertEquals(emptyList<String>(), outcome.outstanding)
        assertEquals(launcher, outcome.homeHandedTo)
        assertTrue(outcome.complete)
        assertEquals(launcher, panel.currentHome)
    }

    @Test
    fun `desired state is cleared before the home role moves`() {
        val panel = tamedPanel()
        panel.controller().handBack(setOf(launcher, monitor))

        val clear = panel.log.indexOf("clear-desired")
        val setHome = panel.log.indexOfFirst { it.startsWith("set-home:") }
        assertTrue("both steps must have run: ${panel.log}", clear >= 0 && setHome >= 0)
        assertTrue("desired state must be cleared first, or the re-assert loops undo it: ${panel.log}", clear < setHome)
    }

    @Test
    fun `packages are re-enabled before the home role moves`() {
        val panel = tamedPanel()
        panel.controller().handBack(setOf(launcher, monitor))

        val restore = panel.log.indexOf("restore-owned")
        val setHome = panel.log.indexOfFirst { it.startsWith("set-home:") }
        assertTrue("the launcher must be enabled before it is made home: ${panel.log}", restore < setHome)
    }

    @Test
    fun `a failure to clear desired state refuses instead of half-running`() {
        val panel = tamedPanel().apply { clearSucceeds = false }
        val result = panel.controller().handBack(setOf(launcher, monitor))

        assertTrue(result is HandBackHomeController.Result.Refused)
        assertFalse("nothing may be re-enabled once the run is refused", "restore-owned" in panel.log)
        assertFalse("HOME must not move", panel.log.any { it.startsWith("set-home:") })
        assertEquals(own, panel.currentHome)
    }

    @Test
    fun `a host-tamed panel with no markers adopts and hands over`() {
        val panel = Panel(
            markers = mutableMapOf(),
            disabled = mutableSetOf(launcher, monitor),
            installed = mutableSetOf(own, launcher, monitor),
            homePkgs = mutableListOf(own, launcher),
        )
        val outcome = done(panel.controller().handBack(setOf(launcher, monitor)))

        assertEquals(listOf(launcher, monitor), outcome.adopted)
        assertEquals(emptyList<String>(), outcome.restored)
        assertEquals(launcher, outcome.homeHandedTo)
        assertTrue(outcome.complete)
    }

    // ── partial failure and retry ──────────────────────────────────────────────────────────────────

    @Test
    fun `a package that refuses to re-enable is outstanding and keeps its marker`() {
        val panel = tamedPanel().apply { enableRefuses = setOf(monitor) }
        val outcome = done(panel.controller().handBack(setOf(launcher, monitor)))

        assertEquals(listOf(monitor), outcome.outstanding)
        assertEquals(listOf(launcher), outcome.restored)
        assertFalse(outcome.complete)
        assertTrue("an unfinished package must keep its ownership record", monitor in panel.markers)
        // HOME still moved: the launcher came back, so the panel is no longer at risk of FallbackHome.
        assertEquals(launcher, outcome.homeHandedTo)
        assertTrue(outcome.uninstallSafe)
    }

    @Test
    fun `retrying after a partial failure converges`() {
        val panel = tamedPanel().apply { enableRefuses = setOf(monitor) }
        val first = done(panel.controller().handBack(setOf(launcher, monitor)))
        assertFalse(first.complete)

        // Whatever blocked the package clears; the same call now finishes the job.
        panel.enableRefuses = emptySet()
        val second = done(panel.controller().handBack(setOf(launcher, monitor)))

        assertEquals(emptyList<String>(), second.outstanding)
        assertTrue(second.complete)
        assertTrue("every package is enabled again", panel.disabled.isEmpty())
        assertTrue("every ownership record is discharged", panel.markers.isEmpty())
    }

    @Test
    fun `a second run on a converged panel is a no-op that still confirms home`() {
        val panel = tamedPanel()
        done(panel.controller().handBack(setOf(launcher, monitor)))
        panel.log.clear()

        val outcome = done(panel.controller().handBack(setOf(launcher, monitor)))
        assertTrue(outcome.complete)
        assertEquals(emptyList<String>(), outcome.restored)
        assertEquals(emptyList<String>(), outcome.adopted)
        assertEquals(launcher, outcome.homeHandedTo)
    }

    // ── never strand the panel ─────────────────────────────────────────────────────────────────────

    @Test
    fun `the home role is not handed to a launcher that failed to re-enable`() {
        val panel = tamedPanel().apply { enableRefuses = setOf(launcher, monitor) }
        val outcome = done(panel.controller().handBack(setOf(launcher, monitor)))

        assertFalse("HOME must not move to a disabled launcher", panel.log.any { it.startsWith("set-home:") })
        assertEquals(null, outcome.homeHandedTo)
        assertFalse(outcome.uninstallSafe)
        assertEquals(own, panel.currentHome)
    }

    @Test
    fun `an unconfirmed home handover is not reported as handed over`() {
        val panel = tamedPanel().apply { setHomeSucceeds = false }
        val outcome = done(panel.controller().handBack(setOf(launcher, monitor)))

        assertEquals(null, outcome.homeHandedTo)
        assertFalse(outcome.uninstallSafe)
    }

    @Test
    fun `a panel whose only home app is ha-paneld refuses before touching anything`() {
        val panel = tamedPanel().apply { homePkgs = mutableListOf(own) }
        val result = panel.controller().handBack(setOf(launcher, monitor))

        assertTrue(result is HandBackHomeController.Result.Refused)
        assertEquals(
            HandBackHomePolicy.Refusal.NO_REPLACEMENT_HOME,
            (result as HandBackHomeController.Result.Refused).reason,
        )
        assertTrue("nothing may be mutated on a refusal: ${panel.log}", panel.log.isEmpty())
        assertFalse(panel.desiredCleared)
    }

    @Test
    fun `an unreadable home query refuses before touching anything`() {
        val panel = tamedPanel().apply { homePkgs = null }
        val result = panel.controller().handBack(setOf(launcher, monitor))

        assertTrue(result is HandBackHomeController.Result.Refused)
        assertTrue("nothing may be mutated on a refusal: ${panel.log}", panel.log.isEmpty())
    }

    @Test
    fun `an unreadable package query refuses rather than inferring absence`() {
        val panel = tamedPanel().apply { stateReadFails = true }
        val result = panel.controller().handBack(setOf(launcher, monitor))

        assertTrue(result is HandBackHomeController.Result.Refused)
        assertTrue("nothing may be mutated on a refusal: ${panel.log}", panel.log.isEmpty())
    }
}
