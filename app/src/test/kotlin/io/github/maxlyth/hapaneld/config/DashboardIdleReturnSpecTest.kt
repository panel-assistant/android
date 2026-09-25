package io.github.maxlyth.hapaneld.config

import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardIdleReturnSpecTest {
    @Test fun idleReturnIsDeclaredAsBuiltInRendererOnly() {
        val spec = SettingsRegistry.spec("dashboard_idle_return_min")
        assertTrue(spec != null)
        assertTrue(spec!!.help.startsWith("Built-in renderer:"))
    }
}
