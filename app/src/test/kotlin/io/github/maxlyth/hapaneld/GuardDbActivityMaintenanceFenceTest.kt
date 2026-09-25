package io.github.maxlyth.hapaneld

import io.github.maxlyth.hapaneld.testsupport.TestSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardDbActivityMaintenanceFenceTest {
    @Test fun `maintenance redirect is sticky for every later lifecycle callback`() {
        val fence = GuardDbActivityMaintenanceFence()
        var redirects = 0
        var ordinaryOpeners = 0

        listOf("onCreate", "onStart", "onResume", "onWindowFocusChanged", "onNewIntent").forEach {
            if (!fence.stop(maintenanceRequired = true) { redirects++ }) ordinaryOpeners++
        }

        assertEquals("one Activity instance redirects only once", 1, redirects)
        assertEquals("no callback after maintenance admission may construct ordinary state", 0, ordinaryOpeners)
    }

    @Test fun `ordinary startup remains open until maintenance is first observed then stays closed`() {
        val fence = GuardDbActivityMaintenanceFence()
        var redirects = 0

        assertFalse(fence.stop(maintenanceRequired = false) { redirects++ })
        assertTrue(fence.stop(maintenanceRequired = true) { redirects++ })
        assertTrue(fence.stop(maintenanceRequired = false) { redirects++ })
        assertEquals(1, redirects)
    }

    @Test fun `admin config dashboard and main callbacks fence before ordinary work`() {
        val admin = TestSources.kotlin("AdminLauncherActivity.kt").readText()
        assertGuardBefore(admin, "override fun onCreate", "KioskAdminUi.setVisible(this, true)")
        assertGuardBefore(admin, "override fun onStart", "KioskAdminUi.setVisible(this, true)")
        assertGuardBefore(admin, "override fun onResume", "setContentView(buildUi())")

        val config = TestSources.kotlin("ConfigActivity.kt").readText()
        assertGuardBefore(config, "override fun onCreate", "KioskAdminUi.setVisible(this, true)")
        assertGuardBefore(config, "override fun onStart", "KioskAdminUi.setVisible(this, true)")
        assertGuardBefore(config, "override fun onBackPressed", "web.canGoBack()")
        val configDestroy = callback(config, "override fun onDestroy")
        assertTrue(configDestroy.contains("readinessJob?.cancel()"))
        assertTrue(configDestroy.contains("if (::web.isInitialized) web.destroy()"))

        val dashboard = TestSources.kotlin("DashboardActivity.kt").readText()
        assertGuardBefore(dashboard, "override fun onCreate", "PaneldService.start(this)")
        assertGuardBefore(dashboard, "override fun onNewIntent", "if (destroyed || !BuiltinDashboard.ownsActivity(activityOwner)) return")
        assertGuardBefore(dashboard, "override fun onResume", "resumeOwnsAdmissionVisibility")
        assertGuardBefore(dashboard, "override fun onWindowFocusChanged", "if (hasFocus) applyFullscreen()")
        assertGuardBefore(dashboard, "override fun onTopResumedActivityChanged", "resumeOwnsAdmissionVisibility")
        assertGuardBefore(dashboard, "override fun onConfigurationChanged", "if (android.os.Build.VERSION.SDK_INT in 29..32)")
        assertGuardBefore(dashboard, "override fun onTrimMemory", "if (destroyed || !BuiltinDashboard.ownsActivity(activityOwner) || authLatched) return")
        assertGuardBefore(dashboard, "override fun onLowMemory", "if (!destroyed && BuiltinDashboard.ownsActivity(activityOwner)")

        val main = TestSources.kotlin("MainActivity.kt").readText()
        assertGuardBefore(main, "override fun onCreate", "supportActionBar?.hide()")
        assertGuardBefore(main, "override fun onCreate", "PaneldService.start(this)")
        assertGuardBefore(main, "override fun onStart", "updateKioskAdminVisibility()")
        val permissionResult = main.substring(
            main.indexOf("private val requestNotif"),
            main.indexOf("private fun dp", main.indexOf("private val requestNotif")),
        )
        assertTrue(permissionResult.contains("maintenanceFence.stop(this)"))
    }

    private fun assertGuardBefore(source: String, signature: String, firstOrdinaryWork: String) {
        val body = callback(source, signature)
        val guard = body.indexOf("if (maintenanceFence.stop(this)) return")
        val work = body.indexOf(firstOrdinaryWork)
        assertTrue("$signature is missing the maintenance fence", guard >= 0)
        assertTrue("$signature does ordinary work before the maintenance fence", work > guard)
    }

    private fun callback(source: String, signature: String): String {
        val start = source.indexOf(signature)
        require(start >= 0) { "missing callback $signature" }
        val next = source.indexOf("\n    override fun ", start + signature.length)
            .let { if (it < 0) source.length else it }
        return source.substring(start, next)
    }
}
