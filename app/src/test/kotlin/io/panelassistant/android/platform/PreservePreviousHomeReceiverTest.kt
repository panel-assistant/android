package io.panelassistant.android.platform


import io.panelassistant.android.AppIdentity
import io.panelassistant.android.Config
import io.panelassistant.android.control.FakeSystemEnv
import io.panelassistant.android.fakePreferences
import io.panelassistant.android.homeTakeoverPending
import io.panelassistant.android.preservePreviousHome
import io.panelassistant.android.previousHomeComponent
import io.panelassistant.android.setupEverCompleted
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The receiver's synchronous operations: setup eligibility, acknowledgement and previous-HOME capture. */
class PreservePreviousHomeReceiverTest {
    private val dashboard = ActivityRef(AppIdentity.OWN, "io.panelassistant.android.DashboardActivity")
    private val vendor = ActivityRef("com.vendor.launcher", "com.vendor.launcher.Home")
    private val other = ActivityRef("com.other.launcher", "com.other.launcher.Home")

    private fun config() = Config(fakePreferences().instance)
    private fun env(default: ActivityRef?, vararg homes: ActivityRef) =
        FakeSystemEnv(ownPackage = AppIdentity.OWN, homes = homes.toList(), default = default)

    private fun run(config: Config, env: SystemEnv, op: String?, supplied: String? = null) =
        // The unit-test android.jar cannot parse a flattened component, so parse it here.
        previousHomeOperation(config, env, AppIdentity.OWN, op, supplied) {
            it.split('/').takeIf { parts -> parts.size == 2 }?.let { parts -> ActivityRef(parts[0], parts[1]) }
        }

    @Test fun setupEligibilityRefusedBeforeSetup() {
        val reply = run(config(), env(vendor, vendor), "setup_eligibility")
        assertEquals(0, reply.code)
        assertNull(reply.data)
    }

    @Test fun setupEligibilityAnsweredAfterSetup() {
        val c = config().apply { setupEverCompleted = true }
        val reply = run(c, env(vendor, vendor), "setup_eligibility")
        assertEquals(1, reply.code)
        assertEquals("panel-assistant-setup-eligible", reply.data)
    }

    @Test fun externalRendererKeepsSetupEligibilityAndRetiresPendingHome() {
        val c = config().apply {
            setupEverCompleted = true
            setDashboardPackage("io.homeassistant.companion.android.minimal")
            check(preservePreviousHome(vendor, listOf(vendor), armTakeover = true))
        }
        val e = env(vendor, vendor, dashboard)
        assertEquals("panel-assistant-setup-eligible", run(c, e, "setup_eligibility").data)
        repeat(2) {
            val reply = run(c, e, "dashboard_home_policy")
            assertEquals(2, reply.code)
            assertEquals("panel-assistant-home-retained", reply.data)
            assertFalse(c.homeTakeoverPending)
            assertEquals(vendor, e.defaultHome())
        }
        c.setLauncherPackage(AppIdentity.OWN)
        assertEquals("an explicit Panel launcher remains an override", "panel-assistant-home-builtin",
            run(c, e, "dashboard_home_policy").data)
    }

    @Test fun restoreReplyUsesOnlyEligibleRecordedHomeAndLockedFreshPolicy() {
        val c = config().apply {
            setupEverCompleted = true
            setDashboardPackage("io.homeassistant.companion.android.minimal")
            check(preservePreviousHome(vendor, listOf(vendor), armTakeover = true))
        }
        val e = env(dashboard, vendor, dashboard, other)
        val restore = run(c, e, "dashboard_home_policy")
        assertEquals(3, restore.code)
        assertEquals("panel-assistant-home-restore:${vendor.component}", restore.data)
        fun locked(identity: String?, target: String = vendor.component) = previousHomeOperation(
            c, e, AppIdentity.OWN, "restore_previous_home_shell_lock_eligibility", target,
            shellLockIdentity = identity, lockIdentity = { "1:2" })
        assertEquals(0, locked(null).code)
        assertEquals(0, locked("1:3").code)
        assertEquals(0, locked("1:2", other.component).code)
        assertEquals("panel-assistant-home-restore-shell-locked", locked("1:2").data)
        assertEquals(0, run(c, e, "acknowledge_retained_home", vendor.component).code)
        assertTrue(c.homeTakeoverPending)
        e.default = other
        assertEquals("panel-assistant-home-retained", run(c, e, "dashboard_home_policy").data)
        assertEquals(0, locked("1:2").code)
        assertEquals(other, e.defaultHome())
        e.default = dashboard
        e.homes = listOf(dashboard)
        assertEquals(0, run(c, e, "dashboard_home_policy").code)
    }

    @Test fun deferredRestoreEligibilityIsRevokedWhenPendingIsRetired() {
        val c = config().apply {
            setupEverCompleted = true
            setDashboardPackage("io.homeassistant.companion.android.minimal")
            check(preservePreviousHome(vendor, listOf(vendor), armTakeover = true))
        }
        val e = env(dashboard, vendor, dashboard)
        fun query(operation: String) = previousHomeOperation(c, e, AppIdentity.OWN, operation,
            vendor.component, shellLockIdentity = "1:2", lockIdentity = { "1:2" }, requirePending = true)
        assertEquals(3, query("dashboard_home_policy").code)
        assertEquals(1, query("restore_previous_home_shell_lock_eligibility").code)
        e.default = vendor
        assertEquals(1, run(c, e, "acknowledge_retained_home", vendor.component).code)
        e.default = dashboard
        assertEquals(0, query("dashboard_home_policy").code)
        assertEquals(0, query("restore_previous_home_shell_lock_eligibility").code)
    }

    @Test fun restoredHomeAcknowledgementNeedsExactFreshHomeAndCompletedSetup() {
        val c = config().apply {
            setDashboardPackage("io.homeassistant.companion.android.minimal")
            check(preservePreviousHome(vendor, listOf(vendor), armTakeover = true))
        }
        val e = env(vendor, vendor, dashboard)
        assertEquals(0, run(c, e, "dashboard_home_policy").code)
        assertEquals(0, run(c, e, "acknowledge_retained_home", vendor.component).code)
        c.setupEverCompleted = true
        assertEquals(0, run(c, e, "acknowledge_retained_home", other.component).code)
        assertTrue(c.homeTakeoverPending)
        assertEquals("panel-assistant-home-retained", run(c, e, "acknowledge_retained_home", vendor.component).data)
        assertFalse(c.homeTakeoverPending)
    }

    @Test fun acknowledgeClearsPendingOnlyWhenOwnHomeAfterSetup() {
        fun armed(setup: Boolean) = config().apply {
            setupEverCompleted = setup
            check(preservePreviousHome(vendor, listOf(vendor), armTakeover = true))
        }
        val ok = armed(true)
        assertTrue(ok.homeTakeoverPending)
        assertEquals(1, run(ok, env(dashboard, vendor, dashboard), "acknowledge_home").code)
        assertFalse(ok.homeTakeoverPending)

        val notSetUp = armed(false)
        assertEquals(0, run(notSetUp, env(dashboard, vendor, dashboard), "acknowledge_home").code)
        assertTrue(notSetUp.homeTakeoverPending)

        val foreignDefault = armed(true)
        assertEquals(0, run(foreignDefault, env(vendor, vendor, dashboard), "acknowledge_home").code)
        assertTrue(foreignDefault.homeTakeoverPending)

        val notCandidate = armed(true)
        assertEquals(0, run(notCandidate, env(dashboard, vendor), "acknowledge_home").code)
        assertTrue(notCandidate.homeTakeoverPending)
    }

    @Test fun suppliedPreviousHomeRejectedWhenUnsafe() {
        val android = ActivityRef("android", "com.android.internal.app.ResolverActivity")
        val panel = ActivityRef(AppIdentity.OWN, "io.panelassistant.android.DashboardActivity")
        val settings = ActivityRef("com.android.settings", "com.android.settings.FallbackHome")
        val legacy = ActivityRef(AppIdentity.LEGACY, "x.Home")
        for (bad in listOf(android, panel, legacy, settings, other)) {
            val c = config()
            val e = env(vendor, vendor, android, panel, legacy, settings)
            assertEquals(bad.component, 0, run(c, e, null, bad.component).code)
            assertNull(bad.component, c.previousHomeComponent)
            assertFalse(bad.component, c.homeTakeoverPending)
        }
        val c = config()
        assertEquals(0, run(c, env(vendor, vendor), null, "not a component").code)
        assertNull(c.previousHomeComponent)
    }

    @Test fun suppliedValidCandidateIsRecordedAndArmsPending() {
        val c = config()
        assertEquals(1, run(c, env(vendor, vendor, other), null, other.component).code)
        assertEquals(other.component, c.previousHomeComponent)
        assertTrue(c.homeTakeoverPending)
    }

    @Test fun unsuppliedUsesDefaultHome() {
        val c = config()
        assertEquals(1, run(c, env(vendor, vendor), null).code)
        assertEquals(vendor.component, c.previousHomeComponent)
    }
}
