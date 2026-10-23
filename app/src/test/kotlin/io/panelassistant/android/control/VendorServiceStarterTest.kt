package io.panelassistant.android.control

import io.panelassistant.android.device.VendorService
import io.panelassistant.android.platform.SystemEnv
import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VendorServiceStarterTest {
    private val stargate = VendorService("cloud.shelly.stargate", "cloud.shelly.stargate.activities.MainActivity", 8021)

    /** A panel whose vendor service opens its port [opensAfterPolls] probes after the vendor start. */
    private open class Panel(
        var listening: Boolean = false,
        var overlays: Boolean = true,
        var interactive: Boolean = true,
        var opensAfterPolls: Int? = 2,
        var dashboardReturnMs: Long = 0,
    ) : VendorServiceStarter.Seams {
        val events = mutableListOf<String>()
        var clock = 0L
        var frontAt: Long? = null
        var uncoveredWhileVendorInFront = false
        private var probesSinceStart: Int? = null

        override fun listening(port: Int): Boolean {
            probesSinceStart = probesSinceStart?.plus(1)
            val opened = opensAfterPolls?.let { n -> probesSinceStart?.let { it > n } } == true
            if (opened) listening = true
            return listening
        }
        override fun canDrawOverlays() = overlays
        override fun interactive() = interactive
        override fun addCover(): Boolean { events += "cover"; return true }
        override fun removeCover() { events += "uncover"; if (!dashboardInFront()) uncoveredWhileVendorInFront = true }
        override fun startVendor(service: VendorService) { events += "start:${service.className}"; probesSinceStart = 0 }
        override fun bringDashboardBack() { events += "dashboard"; frontAt = clock + dashboardReturnMs }
        override fun dashboardInFront() = frontAt?.let { clock >= it } == true
        override fun now() = clock
        override fun sleep(ms: Long) { clock += ms }
        override fun stopping() = false
    }

    private val direct = Executor { it.run() }
    private fun starter(panel: Panel, service: VendorService? = stargate) = VendorServiceStarter(service, panel, direct)

    /** Vendor settings are re-asserted whenever the service is found up or brought up, never otherwise. */
    @Test fun theUpFollowUpRunsOnlyWhileTheServiceAnswers() {
        for ((panel, expected) in listOf(Panel(listening = true) to 1, Panel() to 1, Panel(opensAfterPolls = null) to 0, Panel(overlays = false) to 0)) {
            var ups = 0
            VendorServiceStarter(stargate, panel, direct, onUp = { ups++ }).request()
            assertEquals(expected, ups)
        }
    }

    @Test fun noDeclaredServiceDoesNothing() {
        val panel = Panel()
        val starter = starter(panel, service = null)
        starter.request()
        assertEquals(emptyList<String>(), panel.events)
        assertEquals(VendorServiceStarter.State.NONE, starter.state)
        assertEquals("[vendor-service] port=- state=skipped:none attempts=0", starter.diagnosticLine())
    }

    @Test fun anAlreadyListeningServiceIsLeftAlone() {
        val panel = Panel(listening = true)
        val starter = starter(panel)
        starter.request()
        assertEquals(emptyList<String>(), panel.events)
        assertEquals("[vendor-service] port=8021 state=up attempts=0", starter.diagnosticLine())
    }

    @Test fun withoutTheOverlayGrantTheVendorScreenIsNeverShown() {
        val panel = Panel(overlays = false)
        val starter = starter(panel)
        starter.request()
        assertEquals(emptyList<String>(), panel.events)
        assertEquals(VendorServiceStarter.State.NO_OVERLAY, starter.state)
    }

    @Test fun aDarkScreenDefersToTheNextScreenOn() {
        val panel = Panel(interactive = false)
        val starter = starter(panel)
        starter.request()
        assertEquals(emptyList<String>(), panel.events)
        assertEquals(VendorServiceStarter.State.DEFERRED, starter.state)

        panel.interactive = true
        starter.request()
        assertEquals(VendorServiceStarter.State.STARTED, starter.state)
    }

    @Test fun theVendorStartsUnderTheCoverAndTheDashboardReturnsBeforeItLifts() {
        val panel = Panel(opensAfterPolls = 3)
        val starter = starter(panel)
        starter.request()
        assertEquals(listOf("cover", "start:cloud.shelly.stargate.activities.MainActivity", "dashboard", "uncover"), panel.events)
        assertEquals("[vendor-service] port=8021 state=started attempts=1", starter.diagnosticLine())
        // Three polls at 200 ms before the port opened, then the cover held for 700 ms over the dashboard.
        assertEquals(3 * VendorServiceStarter.POLL_MS + VendorServiceStarter.COVER_HOLD_MS, panel.clock)
    }

    @Test fun aPortThatNeverOpensStillReturnsTheDashboardAndLiftsTheCover() {
        val panel = Panel(opensAfterPolls = null)
        val starter = starter(panel)
        starter.request()
        assertEquals(listOf("cover", "start:cloud.shelly.stargate.activities.MainActivity", "dashboard", "uncover"), panel.events)
        assertEquals(VendorServiceStarter.State.FAILED, starter.state)
        assertTrue(panel.clock >= VendorServiceStarter.START_TIMEOUT_MS)
    }

    /** Shelly 2.8.2 opened its listener after the covered wait; the start then still counts, uncovered. */
    @Test fun aListenerThatOpensAfterTheCoveredWaitStillCountsAndRunsTheFollowUp() {
        val polls = (VendorServiceStarter.START_TIMEOUT_MS / VendorServiceStarter.POLL_MS).toInt() + 5
        val panel = Panel(opensAfterPolls = polls)
        var ups = 0
        val starter = VendorServiceStarter(stargate, panel, direct, onUp = { ups++ })
        starter.request()
        assertEquals(VendorServiceStarter.State.STARTED, starter.state)
        assertEquals(1, ups)
        assertEquals("one start, and the cover is gone before the late watch", listOf("cover", "start:cloud.shelly.stargate.activities.MainActivity", "dashboard", "uncover"), panel.events)
    }

    @Test fun aThrowingVendorStartStillLiftsTheCoverAndNeverEscapes() {
        val panel = object : VendorServiceStarter.Seams by Panel() {
            val events = mutableListOf<String>()
            override fun addCover(): Boolean { events += "cover"; return true }
            override fun removeCover() { events += "uncover" }
            override fun bringDashboardBack() { events += "dashboard" }
            override fun startVendor(service: VendorService) = throw SecurityException("not exported")
        }
        val starter = VendorServiceStarter(stargate, panel, direct)
        starter.request()
        assertEquals(listOf("cover", "dashboard", "uncover"), panel.events)
        assertEquals(VendorServiceStarter.State.FAILED, starter.state)
    }

    @Test fun startsAreRateLimitedAndCappedPerProcess() {
        val panel = Panel(opensAfterPolls = null)
        val starter = starter(panel)
        fun starts() = panel.events.count { it.startsWith("start:") }

        starter.request()
        assertEquals(1, starts())
        // A screen-on soon after a failed start does not start the vendor again.
        starter.request()
        assertEquals(1, starts())

        repeat(5) {
            panel.clock += VendorServiceStarter.RETRY_INTERVAL_MS
            starter.request()
        }
        assertEquals(VendorServiceStarter.MAX_ATTEMPTS, starts())
        assertEquals("[vendor-service] port=8021 state=failed attempts=3", starter.diagnosticLine())
    }

    @Test fun theCoverStaysUntilTheDashboardIsBackInFront() {
        // A cold vendor start on the X2i kept the dashboard from returning within the fixed hold.
        val panel = Panel(dashboardReturnMs = 2_500)
        val starter = starter(panel)
        starter.request()
        assertEquals(VendorServiceStarter.State.STARTED, starter.state)
        assertEquals(false, panel.uncoveredWhileVendorInFront)
        assertTrue(panel.clock >= panel.frontAt!! + VendorServiceStarter.COVER_HOLD_MS)
    }

    /** Exercise the same controller return path used by the service's Android seams. */
    private fun configuredPanel(
        launcher: String = "",
        dashboard: String = "",
        builtinReady: Boolean = true,
        observable: Boolean = true,
    ): Pair<Panel, FakeSystemEnv> {
        val own = "io.panelassistant.android"
        val external = "org.example.dashboard"
        val env = FakeSystemEnv(ownPackage = own, launchers = mapOf(external to "$external/.Main"))
        val panel = object : Panel(dashboardReturnMs = 2_500) {
            private var restored: () -> Boolean = { false }
            private fun focused(component: String) = observable &&
                env.directStarts.lastOrNull() == component && frontAt?.let { clock >= it } == true
            private val controller = SystemController(
                env = object : SystemEnv by env {
                    override fun directStart(component: String): Boolean {
                        frontAt = clock + dashboardReturnMs
                        return env.directStart(component)
                    }
                },
                root = FakeRootShell(available = false, runResult = false),
                daemon = object : io.panelassistant.android.platform.Daemon by FakeDaemon(available = false) {
                    override fun send(cmd: String): String? = when (cmd) {
                        "APPSTATE $external" -> if (!observable) null else if (focused("$external/.Main")) "FG" else "BG"
                        else -> null
                    }
                    override fun sendLong(cmd: String, timeoutMs: Long): io.panelassistant.android.platform.DaemonLongResult =
                        send(cmd)?.let(io.panelassistant.android.platform.DaemonLongResult::Reply)
                            ?: io.panelassistant.android.platform.DaemonLongResult.NotSubmitted
                },
                builtinForeground = { focused("$own/.DashboardActivity") },
                adminForeground = { focused("$own/.AdminLauncherActivity") },
            )
            override fun bringDashboardBack() {
                restored = controller.returnAfterVendorStart(launcher, dashboard, builtinReady)
            }
            override fun dashboardInFront() = restored()
        }
        return panel to env
    }

    @Test fun theConfiguredExternalDashboardReturnsUnderTheCover() {
        val (panel, env) = configuredPanel(dashboard = "org.example.dashboard", builtinReady = false)
        starter(panel).request()
        assertEquals(listOf("org.example.dashboard/.Main"), env.directStarts)
        assertEquals(false, panel.uncoveredWhileVendorInFront)
        assertTrue(panel.clock >= panel.frontAt!! + VendorServiceStarter.COVER_HOLD_MS)
    }

    @Test fun panelAdminReturnsUnderTheCoverWhenSelectedAsHome() {
        val (panel, env) = configuredPanel(launcher = "io.panelassistant.android")
        starter(panel).request()
        assertEquals(listOf("io.panelassistant.android/.AdminLauncherActivity"), env.directStarts)
        assertEquals(false, panel.uncoveredWhileVendorInFront)
        assertTrue(panel.clock >= panel.frontAt!! + VendorServiceStarter.COVER_HOLD_MS)
    }

    @Test fun theReadyBuiltinDashboardReturnsUnderTheCover() {
        val (panel, env) = configuredPanel()
        starter(panel).request()
        assertEquals(listOf("io.panelassistant.android/.DashboardActivity"), env.directStarts)
        assertEquals(false, panel.uncoveredWhileVendorInFront)
        assertTrue(panel.clock >= panel.frontAt!! + VendorServiceStarter.COVER_HOLD_MS)
    }

    @Test fun anUnreadyBuiltinDashboardReturnsToAdminWithoutOpeningFirstRun() {
        val (panel, env) = configuredPanel(builtinReady = false)
        starter(panel).request()
        assertEquals(listOf("io.panelassistant.android/.AdminLauncherActivity"), env.directStarts)
        assertEquals(false, panel.uncoveredWhileVendorInFront)
    }

    @Test fun anUnobservedSurfaceStillReleasesTheCoverAfterTheExistingTimeout() {
        val (panel, env) = configuredPanel(dashboard = "org.example.dashboard", observable = false)
        starter(panel).request()
        assertEquals(listOf("org.example.dashboard/.Main"), env.directStarts)
        assertEquals("uncover", panel.events.last())
        assertEquals(panel.frontAt!! - panel.dashboardReturnMs + VendorServiceStarter.RETURN_TIMEOUT_MS +
            VendorServiceStarter.COVER_HOLD_MS, panel.clock)
    }
}
