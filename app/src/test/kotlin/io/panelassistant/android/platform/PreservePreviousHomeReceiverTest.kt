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
