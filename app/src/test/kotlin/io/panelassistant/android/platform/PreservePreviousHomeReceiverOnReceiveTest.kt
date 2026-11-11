package io.panelassistant.android.platform

import android.content.ContextWrapper
import android.content.Intent
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

/** The installer's broadcast reaches the operations through [PreservePreviousHomeReceiver.onReceive]. */
class PreservePreviousHomeReceiverOnReceiveTest {
    private val dashboard = ActivityRef(AppIdentity.OWN, "io.panelassistant.android.DashboardActivity")
    private val vendor = ActivityRef("com.vendor.launcher", "com.vendor.launcher.Home")
    private val other = ActivityRef("com.other.launcher", "com.other.launcher.Home")

    private fun broadcast(config: Config, env: SystemEnv, extras: Map<String, String>): PreviousHomeReply {
        var reply: PreviousHomeReply? = null
        // The unit-test android.jar cannot parse a flattened component, so parse it here.
        PreservePreviousHomeReceiver(
            config = { config }, env = { env },
            parse = { it.split('/').takeIf { p -> p.size == 2 }?.let { p -> ActivityRef(p[0], p[1]) } },
            deliver = { reply = it },
        ).onReceive(
            object : ContextWrapper(null) { override fun getPackageName() = AppIdentity.OWN },
            object : Intent() { override fun getStringExtra(name: String) = extras[name] },
        )
        return checkNotNull(reply)
    }

    private fun op(name: String) = mapOf("operation" to name)
    private fun env(default: ActivityRef?, vararg homes: ActivityRef) =
        FakeSystemEnv(ownPackage = AppIdentity.OWN, homes = homes.toList(), default = default)

    @Test fun setupEligibilityFollowsSetupCompletion() {
        val prefs = fakePreferences()
        val config = Config(prefs.instance)
        val before = broadcast(config, env(vendor, vendor), op("setup_eligibility"))
        assertEquals(0, before.code)
        assertNull(before.data)
        config.setupEverCompleted = true
        val after = broadcast(config, env(vendor, vendor), op("setup_eligibility"))
        assertEquals(1, after.code)
        assertEquals("panel-assistant-setup-eligible", after.data)
    }

    @Test fun legacyInstallIsSetupEligibleThroughTheMigration() {
        // A panel configured before setup tracking: an HA URL without a setup handover.
        val config = Config(fakePreferences(initial = mapOf("ha_url" to "http://ha.local:8123")).instance)
        val reply = broadcast(config, env(vendor, vendor), op("setup_eligibility"))
        assertEquals(1, reply.code)
        assertEquals("panel-assistant-setup-eligible", reply.data)
    }

    @Test fun acknowledgeHomeClearsPendingOnlyWhenThePanelOwnsHome() {
        val config = Config(fakePreferences().instance).apply {
            check(preservePreviousHome(vendor, listOf(vendor), armTakeover = true))
        }
        assertEquals("before setup", 0, broadcast(config, env(dashboard, vendor, dashboard), op("acknowledge_home")).code)
        assertTrue(config.homeTakeoverPending)
        config.setupEverCompleted = true
        assertEquals("vendor still HOME", 0, broadcast(config, env(vendor, vendor, dashboard), op("acknowledge_home")).code)
        assertEquals("not a candidate", 0, broadcast(config, env(dashboard, vendor), op("acknowledge_home")).code)
        assertTrue(config.homeTakeoverPending)
        val ack = broadcast(config, env(dashboard, vendor, dashboard), op("acknowledge_home"))
        assertEquals(1, ack.code)
        assertNull(ack.data)
        assertFalse(config.homeTakeoverPending)
    }

    @Test fun suppliedPreviousHomeIsRecordedOnlyForAValidCandidate() {
        val config = Config(fakePreferences().instance)
        val settings = ActivityRef("com.android.settings", "com.android.settings.FallbackHome")
        val e = env(vendor, vendor, other, dashboard, settings, ActivityRef("android", "com.android.internal.app.ResolverActivity"))
        for (bad in listOf("android/com.android.internal.app.ResolverActivity", dashboard.component,
                settings.component, "com.absent/.Home", "unparseable")) {
            assertEquals(bad, 0, broadcast(config, e, mapOf("previous_home_component" to bad)).code)
            assertNull(bad, config.previousHomeComponent)
            assertFalse(bad, config.homeTakeoverPending)
        }
        val ok = broadcast(config, e, mapOf("previous_home_component" to other.component))
        assertEquals(1, ok.code)
        assertNull(ok.data)
        assertEquals(other.component, config.previousHomeComponent)
        assertTrue(config.homeTakeoverPending)
    }
}
