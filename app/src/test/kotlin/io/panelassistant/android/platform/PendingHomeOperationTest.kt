package io.panelassistant.android.platform

import io.panelassistant.android.AppIdentity
import io.panelassistant.android.Config
import io.panelassistant.android.confirmHomeTakeover
import io.panelassistant.android.completeHomeTakeover
import io.panelassistant.android.control.FakeDaemon
import io.panelassistant.android.control.FakeRootShell
import io.panelassistant.android.control.FakeSystemEnv
import io.panelassistant.android.control.HandBackHomeController
import io.panelassistant.android.control.HandBackHomePolicy
import io.panelassistant.android.control.SystemController
import io.panelassistant.android.control.TameOwnedMarkers
import io.panelassistant.android.fakePreferences
import io.panelassistant.android.homeTakeoverPending
import io.panelassistant.android.preservePreviousHome
import io.panelassistant.android.previousHomeComponent
import io.panelassistant.android.setupEverCompleted
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exercises the receiver's operation with real HOME and handback controllers at the root boundary. */
class PendingHomeOperationTest {
    private val dashboard = ActivityRef(AppIdentity.OWN, "io.panelassistant.android.DashboardActivity")
    private val vendor = ActivityRef("com.vendor.launcher", "com.vendor.launcher.Home")

    private inner class Panel {
        val config = Config(fakePreferences().instance).apply {
            setupEverCompleted = true
            check(preservePreviousHome(vendor, listOf(vendor), armTakeover = true))
        }
        private val state = FakeSystemEnv(
            ownPackage = AppIdentity.OWN, homes = listOf(vendor, dashboard), default = vendor,
        )
        val env = object : SystemEnv by state {
            override fun previousHomeComponent() = config.previousHomeComponent
            override fun acknowledgeRetainedHome(component: String) =
                defaultHome()?.component == component && config.completeHomeTakeover()
            override fun preservePreviousHome() = config.preservePreviousHome(defaultHome(), homeActivities())
            override fun homeTakeoverAllowed() = config.setupEverCompleted
            override fun acknowledgeHomeTakeover(component: String) {
                homeActivities().find { it.component == component }?.let {
                    config.confirmHomeTakeover(it, defaultHome())
                }
            }
        }
        var beforeRootWrite: () -> Unit = {}
        var writesTakeEffect = true
        val root = FakeRootShell(onRun = { command ->
            beforeRootWrite()
            if (writesTakeEffect) state.default = state.homes.single {
                command == "cmd package set-home-activity ${it.component}"
            }
        })
        val system = SystemController(env, root, FakeDaemon(available = false), acquireHomeLock = { AutoCloseable {} })

        fun pending(
            claim: Boolean = true,
            native: () -> Boolean = { true },
            component: String = dashboard.component,
            shellIdentity: String? = "1:2",
            appIdentity: String? = "1:2",
        ) = pendingHomeOperation(config, env, component, claim, native, shellIdentity, { appIdentity },
            restoreHome = { system.restoreDashboardHome(it) }) {
            system.setHomeActivity(it)
        }

        fun home(home: ActivityRef) {
            state.homes = (state.homes + home).distinct()
            state.default = home
        }

        fun handBack(beforeClear: () -> Unit = {}): HandBackHomePolicy.Outcome {
            val controller = HandBackHomeController(
                ownedMarkers = { TameOwnedMarkers.Ready(emptyMap()) },
                packageStates = { packages -> packages.associateWith { HandBackHomePolicy.PackageState(true, false) } },
                homeCandidates = { state.homes.map { HandBackHomePolicy.HomeCandidate(it.pkg, it.component, true) } },
                clearDesiredState = { beforeClear(); config.clearHandBackDesiredState() },
                restoreOwned = {},
                enable = { true },
                setHome = { system.setHomeActivity(it, restoringHome = true) },
                observeHome = { env.defaultHome()?.component },
                ownPackage = AppIdentity.OWN,
                previousHomeComponent = { config.previousHomeComponent },
            )
            return (controller.handBack(emptySet()) as HandBackHomeController.Result.Done).outcome
        }
    }

    @Test fun `native pending setup takes home and acknowledges fresh exact readback`() {
        val panel = Panel()
        val result = panel.pending()
        assertEquals(PendingHomeResult.CLAIMED, result)
        assertEquals(1, result.code)
        assertEquals("panel-assistant-pending-home-claimed", result.data)
        assertEquals(dashboard, panel.env.defaultHome())
        assertFalse(panel.config.homeTakeoverPending)
        assertEquals(listOf("cmd package set-home-activity ${dashboard.component}"), panel.root.ran)
    }

    @Test fun companionStartupRestoresRecordedLauncherFromBuiltinHomeAndRepeatsSafely() {
        val panel = Panel()
        panel.config.setDashboardPackage("io.homeassistant.companion.android.minimal")
        panel.home(dashboard)
        panel.system.applyLauncherHomePolicy("", panel.config.dashboardPackage)
        panel.system.applyLauncherHomePolicy("", panel.config.dashboardPackage)
        assertEquals(vendor, panel.env.defaultHome())
        assertEquals(listOf("cmd package set-home-activity ${vendor.component}"), panel.root.ran)
        assertFalse(panel.config.homeTakeoverPending)
    }

    @Test fun companionNativePendingRestoresRecordedHomeAndRequiresExactReadback() {
        for (effective in listOf(true, false)) {
            val panel = Panel()
            panel.config.setDashboardPackage("io.homeassistant.companion.android.minimal")
            panel.home(dashboard)
            panel.writesTakeEffect = effective
            val result = panel.pending()
            assertEquals(if (effective) PendingHomeResult.RETAINED else PendingHomeResult.DEFERRED, result)
            assertEquals(!effective, panel.config.homeTakeoverPending)
            assertEquals(if (effective) vendor else dashboard, panel.env.defaultHome())
            assertEquals(listOf("cmd package set-home-activity ${vendor.component}"), panel.root.ran)
        }
    }

    @Test fun companionRootlessPendingNeverGrantsBuiltinShellTakeover() {
        val panel = Panel()
        panel.config.setDashboardPackage("io.homeassistant.companion.android.minimal")
        panel.home(dashboard)
        assertEquals(PendingHomeResult.DEFERRED, panel.pending(native = { false }))
        assertEquals(PendingHomeResult.DEFERRED, panel.pending(claim = false, native = { false }))
        assertEquals(dashboard, panel.env.defaultHome())
        assertTrue(panel.root.ran.isEmpty())
        assertTrue(panel.config.homeTakeoverPending)
    }

    @Test fun companionInstallPreservesLaterOwnerLauncherEvenWhilePending() {
        val panel = Panel()
        val owner = ActivityRef("com.owner.launcher", "com.owner.launcher.Home")
        panel.config.setDashboardPackage("io.homeassistant.companion.android.minimal")
        panel.home(owner)
        assertEquals(PendingHomeResult.RETAINED, panel.pending())
        assertEquals(owner, panel.env.defaultHome())
        assertTrue(panel.root.ran.isEmpty())
        assertFalse(panel.config.homeTakeoverPending)
    }

    @Test fun `late automatic request cannot undo completed native acquisition and handback`() {
        val panel = Panel()
        assertEquals(PendingHomeResult.CLAIMED, panel.pending())
        assertTrue(panel.handBack().uninstallSafe)

        assertEquals(PendingHomeResult.DEFERRED, panel.pending())
        assertEquals(vendor, panel.env.defaultHome())
        assertEquals(2, panel.root.ran.size)
        assertTrue(panel.config.setupEverCompleted)
    }

    @Test fun `handback during native capability probe revokes the later mutation`() {
        val panel = Panel()
        val result = panel.pending(native = {
            assertTrue(panel.handBack().uninstallSafe)
            true
        })
        assertEquals(PendingHomeResult.DEFERRED, result)
        assertEquals(vendor, panel.env.defaultHome())
        assertEquals(listOf("cmd package set-home-activity ${vendor.component}"), panel.root.ran)
    }

    @Test fun `handback waits for an admitted native write and leaves vendor home last`() {
        val panel = Panel()
        val rootEntered = CountDownLatch(1)
        val finishRoot = CountDownLatch(1)
        val handbackAtClear = CountDownLatch(1)
        val handbackReturned = CountDownLatch(1)
        val workers = Executors.newFixedThreadPool(3)
        panel.beforeRootWrite = {
            if (rootEntered.count > 0) {
                rootEntered.countDown()
                check(finishRoot.await(5, TimeUnit.SECONDS))
            }
        }
        try {
            val claim = workers.submit<PendingHomeResult> { panel.pending() }
            assertTrue(rootEntered.await(5, TimeUnit.SECONDS))
            val handback = workers.submit<HandBackHomePolicy.Outcome> {
                panel.handBack { handbackAtClear.countDown() }.also { handbackReturned.countDown() }
            }
            assertTrue(handbackAtClear.await(5, TimeUnit.SECONDS))
            assertFalse("handback cannot finish ahead of the admitted HOME write", handbackReturned.await(100, TimeUnit.MILLISECONDS))
            val settings = workers.submit<Boolean> { panel.config.commitVoiceEnabled(true) }
            assertTrue("unrelated settings remain writable while HOME is waiting", settings.get(1, TimeUnit.SECONDS))
            assertTrue(panel.config.voiceEnabled)
            finishRoot.countDown()
            assertEquals(PendingHomeResult.CLAIMED, claim.get(5, TimeUnit.SECONDS))
            assertTrue(handback.get(5, TimeUnit.SECONDS).uninstallSafe)
            assertEquals(vendor, panel.env.defaultHome())
            assertFalse(panel.config.homeTakeoverPending)
        } finally {
            finishRoot.countDown()
            workers.shutdownNow()
        }
    }

    @Test fun `rootless pending setup gets only the distinct shell authority and handback revokes it`() {
        val panel = Panel()
        val result = panel.pending(native = { false })
        assertEquals(PendingHomeResult.SHELL, result)
        assertEquals(2, result.code)
        assertEquals("panel-assistant-pending-home-shell", result.data)
        val locked = panel.pending(claim = false, native = { false })
        assertEquals(PendingHomeResult.SHELL_LOCKED, locked)
        assertEquals(1, locked.code)
        assertEquals("panel-assistant-pending-home-shell-locked", locked.data)
        assertTrue(panel.root.ran.isEmpty())
        assertTrue(panel.config.homeTakeoverPending)
        assertTrue(panel.handBack().uninstallSafe)
        assertEquals(PendingHomeResult.DEFERRED, panel.pending(claim = false, native = { false }))
        assertEquals(PendingHomeResult.DEFERRED, panel.pending(native = { false }))
        assertEquals(vendor, panel.env.defaultHome())
    }


    @Test fun `shell permission requires a validated matching lock inode`() {
        val panel = Panel()
        for ((shell, app) in listOf(null to "1:2", "1:2" to null, "1:3" to "1:2", "bad" to "1:2")) {
            assertEquals(PendingHomeResult.DEFERRED, panel.pending(
                claim = false, native = { false }, shellIdentity = shell, appIdentity = app,
            ))
        }
        assertTrue(panel.config.homeTakeoverPending)
        assertTrue(panel.root.ran.isEmpty())
    }

    @Test fun `shell lock identity does not bypass revocation during capability probe`() {
        val panel = Panel()
        val result = panel.pending(claim = false, native = {
            panel.config.clearHandBackDesiredState()
            false
        })
        assertEquals(PendingHomeResult.DEFERRED, result)
        assertTrue(panel.root.ran.isEmpty())
    }

    @Test fun `native failure or inexact readback never grants shell fallback or acknowledges pending`() {
        for (reportedSuccess in listOf(false, true)) {
            val panel = Panel().apply {
                writesTakeEffect = false
                root.runResult = reportedSuccess
            }
            assertEquals(PendingHomeResult.DEFERRED, panel.pending())
            assertTrue(panel.config.homeTakeoverPending)
            assertEquals(vendor, panel.env.defaultHome())
            assertEquals(1, panel.root.ran.size)
        }
    }

    @Test fun `unfinished setup and foreign home target cannot acquire deferred ownership`() {
        val panel = Panel()
        assertEquals(PendingHomeResult.DEFERRED, panel.pending(component = vendor.component))
        panel.config.setupEverCompleted = false
        assertEquals(PendingHomeResult.DEFERRED, panel.pending())
        assertEquals(PendingHomeResult.DEFERRED, panel.pending(claim = false, native = { false }))
        assertTrue(panel.root.ran.isEmpty())
    }

    @Test fun `explicit supported home repair still works without pending setup authority`() {
        val panel = Panel()
        assertTrue(panel.handBack().uninstallSafe)
        assertFalse(panel.config.homeTakeoverPending)
        assertTrue(panel.system.setHomeActivity(dashboard.component))
        assertEquals(dashboard, panel.env.defaultHome())
    }
}
