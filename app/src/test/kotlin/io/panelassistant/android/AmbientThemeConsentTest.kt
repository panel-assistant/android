package io.panelassistant.android

import android.content.ContextWrapper
import io.panelassistant.android.control.BuiltinDashboard
import io.panelassistant.android.control.FakeDaemon
import io.panelassistant.android.control.FakeRootShell
import io.panelassistant.android.control.FakeSystemEnv
import io.panelassistant.android.control.SystemController
import io.panelassistant.android.util.DashboardTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.*
import org.junit.Test

class AmbientThemeConsentTest {
    @Test fun anAmbientVerdictReconcilesWithoutExplicitRetryConsent() {
        val ha = LoopbackHa()
        val rig = DashboardRendererRig(prefs = mapOf(
            "ha_url" to ha.url, "ha_token" to "expired",
            "ha_token_expiry" to System.currentTimeMillis() / 1000 - 10,
            "ha_refresh_token" to "ambient-refused", "ha_client_id" to "client",
            "dashboard_theme" to DashboardTheme.AMBIENT, "auto_brightness" to true,
            "dashboard_theme_ambient_dark" to false,
        ))
        try {
            HaCredentialManager.forgetRefusal()
            BuiltinDashboard.clearRendererLatch()
            BuiltinDashboard.setActivityForeground(rig.owner, true)
            BuiltinDashboard.setActivityAuthLatched(rig.owner, true)
            BuiltinDashboard.setAppliedThemeSignature(rig.owner, DashboardTheme.LIGHT)
            ha.answer(400, """{"error":"invalid_grant"}""")
            assertTrue(HaCredentialManager.resolve(rig.config).rejected)
            assertEquals(1, ha.requests.get())
            val start = "START io.panelassistant.android/.DashboardActivity"
            val daemon = FakeDaemon(replies = mapOf(start to "OK"))
            val system = SystemController(FakeSystemEnv(ownPackage = "io.panelassistant.android"), FakeRootShell(), daemon, sdkInt = 29)
            val controls = DashboardRendererRig.allocate(ServiceSystemControls::class.java)
            ServiceSystemControls::class.java.getDeclaredField("system").apply { isAccessible = true }.set(controls, system)
            val display = ServiceDisplay(
                service = ContextWrapper(null), scope = CoroutineScope(Dispatchers.Unconfined),
                teardownBoundary = ServiceTeardownBoundary(), config = { rig.config },
                profile = { error("unused") }, runtime = { error("unused") }, runtimeInstalled = { false },
                liveSettingAuthority = { error("unused") }, haLinks = { error("unused") },
                sensors = { error("unused") }, sensorsReady = { false }, controls = { controls },
            )
            val reconcile = ServiceDisplay::class.java.getDeclaredMethod("reconcileAmbientTheme").apply { isAccessible = true }
            rig.config.setDashboardAmbientDark(true)
            repeat(2) { reconcile.invoke(display) }
            assertEquals("one launch per resolution", listOf(start), daemon.sent)
            assertFalse("room light is not retry consent", BuiltinDashboard.consumeReloadRequest())
            assertEquals("", BuiltinDashboard.consumeReloadReason())
            assertTrue(BuiltinDashboard.authLatched)
            assertTrue(HaCredentialManager.resolve(rig.config).rejected)
            assertEquals("no repeated refused token request", 1, ha.requests.get())

            // Once the page carries the verdict the request is spent, so a page that later drifts
            // from it (a relaunch baked another scheme) is rebuilt again.
            BuiltinDashboard.setAppliedThemeSignature(rig.owner, rig.config.dashboardThemeEffective)
            reconcile.invoke(display)
            assertEquals("a page that carries the verdict is left alone", listOf(start), daemon.sent)
            BuiltinDashboard.setAppliedThemeSignature(rig.owner, DashboardTheme.LIGHT)
            reconcile.invoke(display)
            assertEquals("a page that drifted from the verdict is rebuilt again", listOf(start, start), daemon.sent)
        } finally {
            BuiltinDashboard.consumeSupersededReload()
            HaCredentialManager.forgetRefusal()
            rig.close()
            ha.close()
        }
    }
}
