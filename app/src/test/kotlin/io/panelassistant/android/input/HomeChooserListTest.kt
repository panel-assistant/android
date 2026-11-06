package io.panelassistant.android.input

import io.panelassistant.android.control.FakeDaemon
import io.panelassistant.android.control.FakeRootShell
import io.panelassistant.android.control.FakeSystemEnv
import io.panelassistant.android.control.SystemController
import io.panelassistant.android.platform.ActivityRef
import org.junit.Assert.*
import org.junit.Test
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

/** Drives the accessibility answer and its native-tree reader, including Android's duplicate app labels. */
class HomeChooserListTest {
    private val own = "io.panelassistant.android"
    private val dashboard = ChooserHome(ActivityRef(own, "$own.DashboardActivity"), "Panel Assistant", "Home Assistant Dashboard")
    private val launcher = ChooserHome(ActivityRef(own, "$own.AdminLauncherActivity"), "Panel Assistant", "Panel launcher")
    private val resolver = ActivityRef("android", "com.android.internal.app.ResolverActivity")

    // Reduced native captures: both app labels match; no checked/selected marker changes on selection.
    // Only button_always becomes enabled. The activity labels come from package metadata at runtime.
    private fun nativeWindow(enabled: Boolean, rows: List<ChooserHome> = listOf(dashboard, launcher), id: Int = 7): ChooserWindow {
        val xml = """<node bounds="[0,0][1784,1200]">
            <node resource-id="android:id/title" text="&#x200e;Select a Home app&#x200f;"/>
            <node resource-id="android:id/resolver_list">${rows.mapIndexed { i, home -> """
                <node enabled="true" bounds="[621,${943 + 79 * i}][1299,${1022 + 79 * i}]">
                    <node><node resource-id="android:id/text1" text="${home.appLabel}"/>
                    <node resource-id="android:id/text2" text="${home.activityLabel}"/></node>
                </node>""" }.joinToString("")}</node>
            <node resource-id="android:id/button_always" enabled="$enabled" bounds="[1176,1113][1274,1189]"/>
        </node>"""
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml.byteInputStream())
        fun read(element: Element): ChooserNode {
            val box = Regex("\\d+").findAll(element.getAttribute("bounds")).map { it.value.toInt() }.toList()
            return ChooserNode(
                element.getAttribute("resource-id"), element.getAttribute("text"), element.getAttribute("enabled") != "false",
                box.takeIf { it.size == 4 }?.let { (it[0] + it[2]) / 2 to (it[1] + it[3]) / 2 },
                (0 until element.childNodes.length).mapNotNull { element.childNodes.item(it) as? Element }.map(::read),
            )
        }
        return readChooserWindow("android", id, read(document.documentElement))
    }

    private inner class Panel(launcherSetting: String = "") {
        val env = FakeSystemEnv(ownPackage = own, homes = listOf(dashboard.activity, launcher.activity), default = resolver)
        val root = FakeRootShell(available = false, runResult = false)
        val daemon = FakeDaemon(available = false)
        val system = SystemController(env, root, daemon, builtinForeground = { true }, homeWarning = {})
        var setup: Boolean? = true
        var target = configuredChooserHome(own, launcherSetting, "builtin", true, listOf(dashboard, launcher))
        var window = ChooserWindow(own, null, null, null)
        var rows = listOf(dashboard, launcher)
        var selectedHome: ChooserHome? = null
        var ignoreSelection = false
        var ignoreAlways = false
        var acceptedGesture = true
        var acceptedHome = true
        var shows = 0
        val taps = mutableListOf<Pair<Int, Int>>()
        val messages = mutableListOf<String>()
        var scheduled = false
        val answer = HomeChooserAnswer(
            "Panel Assistant", { env.defaultHome() }, { setup }, { window },
            showChooser = { shows++; if (acceptedHome) window = nativeWindow(false, rows); acceptedHome },
            dismiss = { window = ChooserWindow(own, null, null, null); true },
            tap = { x, y ->
                taps += x to y
                if (acceptedGesture) {
                    val row = window.rows.singleOrNull { it.centre == x to y }
                    if (row != null && !ignoreSelection) {
                        selectedHome = rows.single { it.appLabel == row.appLabel && it.activityLabel == row.activityLabel }
                        window = nativeWindow(true, rows, window.windowId!!)
                    } else if (window.alwaysCentre == x to y && window.alwaysEnabled == true && !ignoreAlways) {
                        env.default = selectedHome?.activity
                        window = ChooserWindow(own, null, null, null)
                    }
                }
                acceptedGesture
            },
            answerAgainIn = { scheduled = true }, log = { messages += it }, intendedHome = { target },
        )
        fun settle() {
            answer.answer()
            repeat(30) {
                if (!scheduled) return
                scheduled = false
                answer.answer()
            }
            error("HOME recovery did not settle within its budget")
        }
    }

    @Test fun rootlessUpdateRestoresConfiguredDashboardAndFreshHomeProof() {
        val panel = Panel()
        panel.system.applyLauncherHomePolicy("", "builtin", true)
        assertEquals("home_resolver", panel.system.homeUiProof("builtin", false).reason)
        assertEquals(1, panel.root.ran.count { it.contains("set-home-activity") })
        assertEquals(1, panel.daemon.sent.count { it.startsWith("SETHOME ") })
        panel.answer.connected()
        panel.settle()
        assertEquals(dashboard.activity, panel.env.defaultHome())
        assertEquals("ready", panel.system.homeUiProof("builtin", false).state)
        assertEquals(listOf(960 to 982, 1225 to 1151), panel.taps)
    }

    @Test fun configuredPanelLauncherIsSelectedEvenWhenDashboardIsFirst() {
        val panel = Panel(own)
        panel.settle()
        assertEquals(launcher.activity, panel.env.defaultHome())
        assertEquals(listOf(960 to 1061, 1225 to 1151), panel.taps)
    }

    @Test fun alreadyVisibleListIsFirstReopenedThroughHome() {
        val panel = Panel().apply { window = nativeWindow(false) }
        panel.settle()
        assertEquals(1, panel.shows)
        assertEquals(dashboard.activity, panel.env.defaultHome())
    }

    @Test fun failedHomePressCannotAuthoriseALaterSpontaneousList() {
        val panel = Panel().apply { acceptedHome = false }
        panel.answer.answer()
        panel.window = nativeWindow(false)
        panel.settle()
        assertEquals(resolver, panel.env.defaultHome())
        assertTrue(panel.taps.isEmpty())
        assertTrue(panel.shows <= 1 + HomeChooserAnswer.RETRIES)
    }

    @Test fun freshInstallAndUnknownSetupNeverSelectAList() {
        for (setup in listOf(false, null)) {
            val panel = Panel().apply { this.setup = setup; window = nativeWindow(false) }
            panel.settle()
            assertEquals(resolver, panel.env.defaultHome())
            assertEquals(0, panel.shows)
            assertTrue(panel.taps.isEmpty())
        }
    }

    @Test fun foreignHomeAndForeignChooserAreLeftAlone() {
        val foreign = ChooserHome(ActivityRef("com.vendor", "com.vendor.Home"), "Vendor", "Home")
        val panel = Panel().apply { env.default = foreign.activity; window = nativeWindow(false) }
        panel.settle()
        assertEquals(foreign.activity, panel.env.defaultHome())
        assertTrue(panel.taps.isEmpty())
        val other = Panel().apply { window = nativeWindow(false, listOf(foreign)) }
        other.settle()
        assertEquals(0, other.shows)
        assertTrue(other.taps.isEmpty())
    }

    @Test fun duplicateTargetLabelsAreNeverGuessed() {
        val panel = Panel().apply { rows = listOf(dashboard, dashboard) }
        panel.settle()
        assertEquals(resolver, panel.env.defaultHome())
        assertTrue(panel.taps.isEmpty())
        assertNull(configuredChooserHome(own, "", "builtin", true, listOf(dashboard, dashboard.copy(activity = launcher.activity))))
    }

    @Test fun preselectedListIsNotConfirmedWithoutOurSelection() {
        val panel = Panel()
        panel.answer.answer() // Our HOME press.
        panel.window = nativeWindow(true)
        panel.settle()
        assertTrue(panel.taps.isEmpty())
        assertEquals(resolver, panel.env.defaultHome())
    }

    @Test fun changedWindowOrRowsInvalidateSelectionBeforeAlways() {
        for (changeWindow in listOf(true, false)) {
            val panel = Panel()
            panel.answer.answer() // HOME.
            panel.answer.answer() // Select configured activity.
            panel.window = if (changeWindow) nativeWindow(true, id = 8) else nativeWindow(true, listOf(launcher, dashboard))
            panel.settle()
            assertEquals(resolver, panel.env.defaultHome())
            assertEquals(1, panel.taps.size)
        }
    }

    @Test fun changedConfiguredActivityOrHomeOwnerInvalidatesSelection() {
        for (changedOwner in listOf(true, false)) {
            val panel = Panel()
            panel.answer.answer()
            panel.answer.answer()
            if (changedOwner) panel.env.default = launcher.activity else panel.target = launcher
            panel.settle()
            assertEquals(if (changedOwner) launcher.activity else resolver, panel.env.defaultHome())
            assertEquals(1, panel.taps.size)
        }
    }

    @Test fun failedAndIgnoredGesturesDoNotConfirmOrLoop() {
        val failed = Panel().apply { acceptedGesture = false }
        failed.settle()
        assertEquals(1, failed.taps.size)
        assertEquals(resolver, failed.env.defaultHome())
        val ignored = Panel().apply { ignoreSelection = true }
        ignored.settle()
        assertEquals(HomeChooserAnswer.TAPS, ignored.taps.size)
        assertEquals(resolver, ignored.env.defaultHome())
        val ignoredAlways = Panel().apply { ignoreAlways = true }
        ignoredAlways.settle()
        assertEquals(1 + HomeChooserAnswer.TAPS, ignoredAlways.taps.size)
        assertEquals(resolver, ignoredAlways.env.defaultHome())
    }

    @Test fun unconfiguredUnreadyOrForeignRendererHasNoOwnActivityToSelect() {
        assertNull(configuredChooserHome(own, "", "builtin", false, listOf(dashboard, launcher)))
        assertNull(configuredChooserHome(own, "", "com.other.renderer", true, listOf(dashboard, launcher)))
        assertNull(configuredChooserHome(own, "", "builtin", true, listOf(launcher)))
        assertEquals(dashboard, configuredChooserHome(own, "", "", true, listOf(dashboard, launcher)))
        val panel = Panel().apply { target = null }
        panel.settle()
        assertEquals(resolver, panel.env.defaultHome())
        assertTrue(panel.taps.isEmpty())
    }
}
