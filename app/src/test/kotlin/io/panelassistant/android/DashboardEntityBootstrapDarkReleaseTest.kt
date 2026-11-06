package io.panelassistant.android

import android.os.Handler
import androidx.appcompat.app.AppCompatActivity
import io.panelassistant.android.control.BuiltinDashboard
import io.panelassistant.android.dashboard.AutoScopeVerification
import io.panelassistant.android.dashboard.shouldHoldRendererForEntityBootstrap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A panel whose home dashboard is Auto holds its renderer after every cold start, an app update
 * included, until the startup sync confirms which dashboard Auto means. When that answer arrives while
 * the screen is dark, the bootstrap poll must still build the dashboard: `buildAndLoad` loads it live
 * for a settle window and then freezes it, as on any dark cold start. A panel that waited for a wake
 * instead stayed on the "Learning" hold screen after an update, sometimes for 25 minutes, until someone
 * woke it, and Panel Assistant could not prove the update.
 *
 * No Robolectric: the activity, Handler and status screens are allocated without constructors (the
 * poll's release path does not touch them), and settings come from in-memory preferences.
 */
class DashboardEntityBootstrapDarkReleaseTest {
    private val auto = ""
    private var owner = 0L
    private var gate: DashboardEntityGate? = null

    @Before fun claim() {
        AutoScopeVerification.invalidate()
        owner = BuiltinDashboard.acquireActivityOwner()
    }

    @After fun release() {
        gate?.stopLease()
        AutoScopeVerification.invalidate()
        BuiltinDashboard.releaseActivityOwner(owner)
    }

    @Test fun `a dark Auto panel builds its dashboard as soon as the scope is verified`() {
        val built = mutableListOf<Config>()
        val config = learnedAutoPanel()
        val gate = gate(config = config, screenAwake = false, built = built).also { gate = it }
        assertTrue("held until the startup sync confirms Auto", shouldHoldRendererForEntityBootstrap(config))

        AutoScopeVerification.markVerified(auto)
        runBlocking { gate.pollEntityBootstrap() }

        assertEquals("the dashboard is built behind the dark screen, once", 1, built.size)
        assertTrue(
            "the learned allow-list was prepared for it: ${gate.entityFilterSignature}",
            gate.entityFilterSignature.startsWith("enabled:"),
        )
    }

    private fun gate(config: Config, screenAwake: Boolean, built: MutableList<Config>) = DashboardEntityGate(
        activity = allocate(AppCompatActivity::class.java),
        main = allocate(Handler::class.java),
        scope = CoroutineScope(Dispatchers.Unconfined),
        statusScreens = allocate(DashboardStatusScreens::class.java),
        host = object : DashboardEntityGate.Host {
            override val activityOwner get() = owner
            override val destroyed get() = false
            override val screenAwake get() = screenAwake
            override fun buildAndLoad(config: Config) { built += config }
            override fun teardownWeb() = Unit
            override fun reconcile() = Unit
        },
        readConfig = { config },
    )

    /** An Auto panel's state after an update: a learned allow-list on record, learning on. */
    private fun learnedAutoPanel(): Config {
        val prefs = fakePreferences(initial = mapOf(
            "ha_url" to "http://ha.local:8123",
            "home_dashboard" to auto,
            "dashboard_entity_instance" to "url-key",
            "dashboard_entity_instance_origin" to "http://ha.local:8123",
            "dashboard_entity_dashboard_path" to "/",
            "dashboard_entity_learning" to true,
        ))
        return Config(prefs.instance).apply {
            assertEquals(DashboardEntityDefaultResolverMigration.REBOOTSTRAP, migrateDashboardEntityDefaultResolver())
            assertTrue(commitDashboardEntitySubscription(true, listOf("light.office"), applied = true))
            assertTrue(clearDashboardEntityDefaultResolverRebootstrapPending())
            assertTrue("fixture precondition", dashboardEntityFilterEnabled)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> allocate(type: Class<T>): T {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val field = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }
        return unsafeClass.getMethod("allocateInstance", Class::class.java).invoke(field.get(null), type) as T
    }
}
