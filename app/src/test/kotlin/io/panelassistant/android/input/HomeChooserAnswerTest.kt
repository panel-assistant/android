package io.panelassistant.android.input

import io.panelassistant.android.platform.ActivityRef
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A Meta Portal after an in-place update, driven through the answer the accessibility service runs:
 * HOME is unowned, the chooser shows on HOME, and a tap on its Always makes the dashboard HOME, unless
 * the app's own start-up brings the dashboard over the chooser first.
 */
class HomeChooserAnswerTest {
    private val resolver = ActivityRef("android", "com.android.internal.app.ResolverActivity")
    private val dashboard = ActivityRef("io.panelassistant.android", "io.panelassistant.android.DashboardActivity")
    private val chooser = ChooserWindow("android", "Use Panel Assistant as Home", true, 817 to 623)
    private val dashboardWindow = ChooserWindow("io.panelassistant.android", null, null, null)

    private inner class Portal {
        var home = resolver
        var front = dashboardWindow
        /** Whether the panel has ever been set up; null while the service is still reading it. */
        var setupDone: Boolean? = true
        /** The chooser HOME shows while unowned; Android names the app it last chose in its title. */
        var offered = chooser
        /** An installer sets HOME over ADB straight after the chooser is shown, before it is answered. */
        var claimedAfterShow = false
        /** The start-up's dashboard launches arriving while a tap is on its way, one per tap. */
        var coveringStarts = 0
        /** The start-up's dashboard launches arriving before a chooser just shown is answered. */
        var coveredShows = 0
        var homePresses = 0
        var taps = 0
        /** Taps Android accepts on a chooser that then stays, as an Always that never takes would. */
        var ignoredTaps = false
        private var due: Long? = null

        val answer = HomeChooserAnswer(
            appName = "Panel Assistant",
            defaultHome = { home },
            setupEverCompleted = { setupDone },
            activeWindow = { front },
            showChooser = {
                homePresses++
                if (home == resolver) front = offered
                if (claimedAfterShow) home = dashboard
                if (coveredShows > 0) { coveredShows--; front = dashboardWindow }
                true
            },
            dismiss = { front = dashboardWindow; true },
            tap = { x, y ->
                taps++
                if (!ignoredTaps) {
                    if (coveringStarts > 0) { coveringStarts--; front = dashboardWindow }
                    if (front == offered && x to y == offered.alwaysCentre) { home = dashboard; front = dashboardWindow }
                }
                true
            },
            answerAgainIn = { due = it },
            log = {},
        )

        /** What the service runs: the answer on connect, on each chooser window, and when a recheck falls due. */
        fun settle() {
            answer.answer()
            repeat(20) {
                when {
                    due != null -> { due = null; answer.answer() }
                    front == offered && !ignoredTaps -> answer.answer()
                    else -> return
                }
            }
            error("the answer never settled")
        }
    }

    @Test fun anUpdateLeavesTheDashboardAsHome() {
        val portal = Portal()
        portal.answer.connected()
        portal.settle()
        assertEquals(dashboard, portal.home)
        assertEquals(1, portal.homePresses)
    }

    @Test fun aTapTheStartUpCoveredIsAnsweredAgain() {
        // A Meta Portal, 2026-10-15: the dashboard came up 170 ms before the tap, which landed on it.
        val portal = Portal().apply { coveringStarts = 1 }
        portal.answer.connected()
        portal.settle()
        assertEquals(dashboard, portal.home)
        assertEquals(2, portal.homePresses)
    }

    @Test fun aChooserTheStartUpCoveredBeforeItWasAnsweredIsShownAgain() {
        // A Meta Portal, 2026-10-15: the dashboard came up 190 ms after HOME was pressed, before the answer.
        val portal = Portal().apply { coveredShows = 1 }
        portal.answer.connected()
        portal.settle()
        assertEquals(dashboard, portal.home)
        assertEquals(2, portal.homePresses)
    }

    @Test fun bothCoversInOneUpdateAreAnswered() {
        val portal = Portal().apply { coveredShows = 1; coveringStarts = 1 }
        portal.answer.connected()
        portal.settle()
        assertEquals(dashboard, portal.home)
        assertEquals(3, portal.homePresses)
    }

    @Test fun aChooserWhoseAlwaysNeverTakesIsShownAtMostThreeTimes() {
        val portal = Portal().apply { coveringStarts = Int.MAX_VALUE }
        portal.answer.connected()
        portal.settle()
        assertEquals(resolver, portal.home)
        assertEquals(3, portal.homePresses)
    }

    @Test fun anAlwaysThatNeverTakesIsTappedAtMostThreeTimes() {
        // The tap is accepted and the chooser stays: no window event comes, only the rechecks.
        val portal = Portal().apply { ignoredTaps = true }
        portal.answer.connected()
        portal.settle()
        assertEquals(resolver, portal.home)
        assertEquals(1, portal.homePresses)
        assertEquals(3, portal.taps)
    }

    @Test fun aChooserOfferingAnotherAppThatThisServiceShowedIsDismissed() {
        val portal = Portal().apply { front = dashboardWindow }
        portal.answer.connected()
        portal.answer.answer()
        portal.front = chooser.copy(title = "Use Portal Launcher as Home")
        portal.answer.answer()
        assertEquals(dashboardWindow, portal.front)
        assertEquals(resolver, portal.home)
    }

    @Test fun anOwnedHomeIsLeftAlone() {
        val portal = Portal().apply { home = dashboard }
        portal.answer.connected()
        portal.settle()
        assertEquals(0, portal.homePresses)
    }

    private val standingScreen = ChooserWindow("io.panelassistant.android", "Not set up", null, null)
    private val vendorChooser = chooser.copy(title = "Use Shelly Stargate as Home")

    @Test fun aFreshInstallLeavesHomeToTheInstaller() {
        // Shelly X2i, 2026-10-15: the service bound 4 s after the installer started the app, while HOME was
        // unowned and before the installer set it, and its press showed a chooser offering the vendor launcher.
        val panel = Portal().apply { setupDone = false; front = standingScreen; offered = vendorChooser }
        panel.answer.connected()
        panel.settle()
        assertEquals(0, panel.homePresses)
        assertEquals(standingScreen, panel.front)
        panel.home = dashboard // the installer's set-home-activity
        panel.settle()
        assertEquals(0, panel.homePresses)
        assertEquals(standingScreen, panel.front)
    }

    @Test fun aChooserItShowedIsDismissedOnceHomeIsOwned() {
        // Connect, the chooser is shown, an installer makes the dashboard HOME before it is answered.
        val panel = Portal().apply { offered = vendorChooser; claimedAfterShow = true }
        panel.answer.connected()
        panel.answer.answer() // on connect: shows the chooser
        assertEquals(vendorChooser, panel.front)
        panel.answer.answer() // the recheck VERIFY_MS later
        assertEquals(1, panel.homePresses)
        assertEquals(dashboard, panel.home)
        assertEquals(dashboardWindow, panel.front)
    }

    @Test fun anUpdateWhoseSetupStateIsStillBeingReadIsAnsweredOnceItIsRead() {
        val portal = Portal().apply { setupDone = null }
        portal.answer.connected()
        portal.settle()
        assertEquals(0, portal.homePresses)
        portal.setupDone = true // the read lands and the service answers again
        portal.settle()
        assertEquals(dashboard, portal.home)
        assertEquals(1, portal.homePresses)
    }

    @Test fun aLaterResolverOnTheSameConnectionIsLeftAlone() {
        val portal = Portal()
        portal.answer.connected()
        portal.settle()
        assertEquals(dashboard, portal.home)
        val openWith = ChooserWindow("android", "Open with", true, 700 to 600)
        portal.front = openWith
        portal.answer.answer()
        assertEquals(openWith, portal.front)
    }
}
