package io.panelassistant.android

import android.os.Handler
import androidx.appcompat.app.AppCompatActivity
import io.panelassistant.android.control.BuiltinDashboard
import io.panelassistant.android.dashboard.AutoScopeVerification
import io.panelassistant.android.dashboard.shouldHoldRendererForEntityBootstrap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * An Auto panel holds its renderer after every cold start until the startup sync confirms which dashboard
 * Auto means; the bootstrap poll then prepares the learned allow-list and builds. A relaunch that lands while
 * that preparation is running (Panel Assistant's post-update return to the dashboard, HOME, a kiosk return)
 * supersedes it through `onNewIntent`, and the relaunch itself builds nothing when no setting changed: a plain
 * return to the foreground leaves a status screen to its own retry. The superseded poll is that retry, so it
 * must keep polling. It used to return for good, and an updated bench panel stayed on "Learning — Building
 * the filtered set" with an applied, verified set until someone reloaded it.
 *
 * No Robolectric: the activity, Handler and status screens are allocated without constructors, the poll's
 * next tick is recorded rather than posted, and settings come from in-memory preferences.
 */
class DashboardEntityBootstrapRelaunchRaceTest {
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

    @Test fun `a relaunch that supersedes the released poll's preparation still leaves the dashboard built`() {
        val built = mutableListOf<Config>()
        val scheduled = mutableListOf<Runnable>()
        val work = Job()
        var relaunched = false
        var relaunchTarget: DashboardEntityGate? = null
        // The relaunch arrives while the poll is preparing: once the poll has taken its preparation epoch,
        // the next settings read is where DashboardActivity.onNewIntent supersedes it.
        val config = learnedAutoPanel(onRead = {
            val g = relaunchTarget
            if (!relaunched && g != null && g.filterPreparationEpoch > 0L) {
                relaunched = true
                g.supersedePreparation()
            }
        })
        val gate = DashboardEntityGate(
            activity = allocate(AppCompatActivity::class.java),
            main = allocate(Handler::class.java),
            scope = CoroutineScope(Dispatchers.Unconfined + work),
            statusScreens = allocate(DashboardStatusScreens::class.java),
            host = object : DashboardEntityGate.Host {
                override val activityOwner get() = owner
                override val destroyed get() = false
                override val screenAwake get() = true
                override fun buildAndLoad(config: Config) { built += config }
                override fun teardownWeb() = Unit
            },
            readConfig = { config },
            scheduleBootstrapCheck = { check, _ -> scheduled += check },
        ).also { this.gate = it; relaunchTarget = it }
        assertTrue("held until the startup sync confirms Auto", shouldHoldRendererForEntityBootstrap(config))

        AutoScopeVerification.markVerified(auto)
        runBlocking { gate.pollEntityBootstrap() }
        assertTrue("fixture: the relaunch superseded the poll's preparation", relaunched)
        assertEquals("the superseded preparation does not build", 0, built.size)

        // The relaunch found nothing to rebuild and built nothing. Run what the gate left scheduled on the
        // activity's Handler, as the panel would.
        repeat(3) {
            val due = scheduled.toList()
            scheduled.clear()
            due.forEach(Runnable::run)
            runBlocking { work.children.forEach { it.join() } }
        }

        assertEquals("the hold releases once the scope is verified, whatever relaunched meanwhile", 1, built.size)
    }

    /** An Auto panel's state after an update: a learned allow-list on record, learning on. */
    private fun learnedAutoPanel(onRead: () -> Unit): Config {
        val prefs = fakePreferences(
            initial = mapOf(
                "ha_url" to "http://ha.local:8123",
                "home_dashboard" to auto,
                "dashboard_entity_instance" to "url-key",
                "dashboard_entity_instance_origin" to "http://ha.local:8123",
                "dashboard_entity_dashboard_path" to "/",
                "dashboard_entity_learning" to true,
            ),
            onGetString = { _, _ -> onRead() },
        )
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
