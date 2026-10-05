package io.panelassistant.android

import android.content.Intent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardHomeRoutingTest {
    private val own = "io.github.maxlyth.hapaneld"

    @Test fun `explicit panel admin routes only real home intents`() {
        assertTrue(shouldRouteDashboardHomeToAdmin(own, own, Intent.ACTION_MAIN, setOf(Intent.CATEGORY_HOME)))
        assertFalse(shouldRouteDashboardHomeToAdmin("", own, Intent.ACTION_MAIN, setOf(Intent.CATEGORY_HOME)))
        assertFalse(shouldRouteDashboardHomeToAdmin("com.vendor", own, Intent.ACTION_MAIN, setOf(Intent.CATEGORY_HOME)))
        assertFalse(shouldRouteDashboardHomeToAdmin(own, own, null, null))
        assertFalse(shouldRouteDashboardHomeToAdmin(own, own, Intent.ACTION_MAIN, setOf(Intent.CATEGORY_LAUNCHER)))
    }
}
