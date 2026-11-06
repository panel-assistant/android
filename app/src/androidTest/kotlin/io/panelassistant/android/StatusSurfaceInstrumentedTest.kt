package io.panelassistant.android

import io.panelassistant.android.config.SettingsRegistry
import android.app.Activity
import android.content.res.Configuration
import android.content.res.Resources
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicReference
import java.util.Locale
import io.panelassistant.android.sensors.HaLifecycle
import io.panelassistant.android.sensors.HaLifecycleReason
import io.panelassistant.android.sensors.HaLifecycleState

/**
 * Runtime evidence for the branded status frame, measured rather than read.
 *
 * The unit gate for this frame asserts SOURCE TEXT, because it has no view instrumentation: it can
 * prove the wiring is present and cannot prove the drawn result is right. Four consecutive review
 * rounds found defects of exactly that kind — a mark that moved, a screen that kept a stale palette,
 * content pushed above the scroll origin, actions with no state. Every assertion here executes the
 * real views on a real Android runtime and measures what they actually do.
 *
 * The 480x480 geometry is ENFORCED, not requested: see the precondition below. On a larger device
 * every measurement here has room to spare and would pass without meaning anything.
 */
@RunWith(AndroidJUnit4::class)
class StatusSurfaceInstrumentedTest {

    /**
     * Every measurement below is only meaningful on the smallest supported panel, so the geometry is a
     * precondition rather than a comment.
     *
     * Without it this whole file passes on any device: a 1920x1200 tablet has room for anything, so
     * "the actions are on screen" and "the content is reachable" become statements about the emulator
     * that happened to be attached. The density check matters as much as the pixel one — the frame
     * chooses its compact tier from logical dp, so 480px at density 2.0 is a 240dp panel and a
     * different layout entirely.
     */
    @Before
    fun theDeviceIsTheSmallestSupportedPanel() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val display = context.getSystemService(WindowManager::class.java).defaultDisplay
        val real = DisplayMetrics().also { @Suppress("DEPRECATION") display.getRealMetrics(it) }
        val usable = context.resources.displayMetrics
        panelHeightPx = real.heightPixels

        // The PHYSICAL panel, which is what identifies the device.
        assertEquals(
            "this suite measures a 480x480 panel; the display is ${real.widthPixels}x${real.heightPixels}px",
            480 to 480,
            real.widthPixels to real.heightPixels,
        )
        // Density 1.0, so px and dp coincide and every measurement below reads as both. The frame
        // picks its compact tier from logical dp, so 480px at density 2.0 would be a 240dp panel and
        // a different layout entirely — the pixel check alone would not catch that.
        assertEquals("px and dp must coincide on this panel", 1.0f, usable.density, 0.01f)
        // `resources.displayMetrics` reports 480x432 here, deducting the 48px navigation bar, but the
        // status frame is drawn by a fullscreen activity that receives the whole 480. Measurements are
        // taken against the window each test actually gets, asserted per test, rather than against
        // either figure assumed in advance.
    }

    /** Physical panel height, read once in the precondition so no measurement hard-codes it. */
    private var panelHeightPx = 0

    private fun requestedExpansionLocales(): List<String> {
        val supported = listOf("cs", "pt-BR")
        val requested = InstrumentationRegistry.getArguments().getString("locale") ?: return supported
        require(requested in supported || requested == "en") { "unsupported locale test selection: $requested" }
        return listOf(requested)
    }

    @Test
    fun expansionSelectionPersistsAndEnglishRemainsAnEscape() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val original = Config(context).uiLanguage
        try {
            val expectedRetry = mapOf("cs" to "Zkusit znovu", "pt-BR" to "Tentar novamente", "en" to "Retry")
            for (locale in requestedExpansionLocales() + "en") {
                Config(context).setRaw(requireNotNull(SettingsRegistry.spec("ui_language")), locale)
                assertEquals("the persisted selection survives a new Config reader", locale, Config(context).uiLanguage)
                instrumentation.runOnMainSync { NativeLocale.apply(Config(context).uiLanguage) }
                assertEquals("native resource selection follows the persisted selection", expectedRetry.getValue(locale), context.nativeString(R.string.retry))
            }
        } finally {
            Config(context).setRaw(requireNotNull(SettingsRegistry.spec("ui_language")), original)
            instrumentation.runOnMainSync { NativeLocale.apply(original) }
            assertEquals("the saved selection is restored", original, Config(context).uiLanguage)
        }
    }

    @Test
    fun czechDiagnosticSuffixesPreserveTheirWordBoundariesAfterCompilation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val original = Config(context).uiLanguage
        try {
            Config(context).setRaw(requireNotNull(SettingsRegistry.spec("ui_language")), "cs")
            instrumentation.runOnMainSync { NativeLocale.apply("cs") }
            onFrame(locale = "cs") { activity, _ ->
                assertEquals(
                    "compiled diagnostic suffixes retain their separator at both production resource seams",
                    listOf(
                        "probe · výsledek ok",
                        "probe · postup vpřed: 42",
                        "storage (42 MiB volných v souborovém systému)",
                        "database během startup",
                    ),
                    listOf(
                        "probe" + activity.getString(R.string.guard_db_outcome, "ok"),
                        "probe" + activity.getString(R.string.guard_db_forward_deadline, 42),
                        "storage" + context.nativeString(R.string.storage_capacity_suffix, 42),
                        "database" + context.nativeString(R.string.database_during_suffix, "startup"),
                    ),
                )
            }
        } finally {
            Config(context).setRaw(requireNotNull(SettingsRegistry.spec("ui_language")), original)
            instrumentation.runOnMainSync { NativeLocale.apply(original) }
        }
    }

    private fun onFrame(
        dark: Boolean = true,
        fontScale: Float = 0f,
        locale: String? = null,
        block: (Activity, StatusSurface) -> Unit,
    ) {
        StatusSurfaceTestHost.fontScaleOverride = fontScale
        StatusSurfaceTestHost.localeTagOverride = locale
        try {
            ActivityScenario.launch(StatusSurfaceTestHost::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    locale?.let { assertEquals(it, activity.resources.configuration.locales[0].toLanguageTag()) }
                    if (fontScale > 0f) {
                        assertEquals(
                            "the font-scale override did not reach the activity",
                            fontScale,
                            activity.resources.configuration.fontScale,
                            0.001f,
                        )
                    }
                    val surface = StatusSurface(activity, dark)
                    activity.setContentView(surface.root)
                    block(activity, surface)
                }
            }
        } finally {
            StatusSurfaceTestHost.fontScaleOverride = 0f
            StatusSurfaceTestHost.localeTagOverride = null
        }
    }

    /** The phase used for the overflow measurements: the tallest real screen the frame has to draw. */
    private fun StatusSurface.tallestPhase(): List<View> = listOf(
        notice.hero("12"),
        notice.line("entities need a decision before the dashboard opens"),
        notice.actions(
            notice.action("Ignore flagged entities and continue", primary = true) {},
            notice.action("Disable entity filter") {},
            notice.action("Open entity settings") {},
        ),
        notice.footer(
            "Nothing is wrong with Home Assistant. The panel needs an answer about safety checks found " +
                "while reading your entities before it can open the dashboard. The same choices are " +
                "available from another device at http://192.0.2.10:8888/entities.",
        ),
    )

    private fun StatusSurface.show(rows: List<View>) = setBody("Action needed", *rows.toTypedArray())

    /**
     * How far down the rows themselves actually reach.
     *
     * NOT the body container's height: the scroller sets `isFillViewport`, so a body shorter than the
     * viewport is stretched to it and its height measures the window instead of the content. Reading
     * it that way made this test report the frame SHRINKING at a larger font scale — the band grew, so
     * the viewport it was being pinned to got smaller.
     */
    private fun StatusSurface.bodyExtent(): Int = notice.view.bottom

    private fun View.topInWindow(): Int {
        val xy = IntArray(2)
        getLocationInWindow(xy)
        return xy[1]
    }

    private fun View.bottomInWindow(): Int = topInWindow() + height

    private fun Activity.settle() {
        val root = findViewById<View>(android.R.id.content)
        root.measure(
            View.MeasureSpec.makeMeasureSpec(root.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(root.height, View.MeasureSpec.EXACTLY),
        )
        root.layout(root.left, root.top, root.right, root.bottom)
    }

    /** The lock-up at the start of the card's brand row. */
    private fun StatusSurface.mark(): View {
        val brandRow = notice.view.getChildAt(0) as ViewGroup
        return (brandRow.getChildAt(0) as ViewGroup).getChildAt(0)
    }

    private fun StatusSurface.scroller(): ScrollView = scroll

    /**
     * The mark does not move when the phase changes.
     *
     * This is the requirement the whole frame exists for, and until now it was only ever asserted as
     * source text. Here it is measured: the mark's window position is recorded across three phases of
     * very different height, including one that replaces every row.
     */
    @Test
    fun theMarkHoldsItsPositionAcrossPhaseChanges() {
        onFrame { activity, surface ->
            val positions = mutableListOf<Int>()
            listOf(
                listOf(surface.notice.hero("Checking", word = true), surface.notice.line("Home Assistant compatibility")),
                surface.tallestPhase(),
                listOf(surface.notice.footer("waiting 12s")),
            ).forEach { rows ->
                surface.show(rows)
                activity.settle()
                positions += surface.mark().topInWindow()
            }
            assertEquals("the mark moved between phases: $positions", 1, positions.toSet().size)
            assertTrue("the mark must actually be laid out", surface.mark().height > 0)
        }
    }

    /** Every row of a phase, and every action, is inside the panel rather than below its fold. */
    @Test
    fun theTallestPhaseKeepsItsActionsOnScreen() {
        onFrame { activity, surface ->
            val rows = surface.tallestPhase()
            surface.show(rows)
            activity.settle()
            val panelBottom = activity.findViewById<View>(android.R.id.content).height
            assertEquals("the frame must fill the panel", panelHeightPx, panelBottom)
            val actions = (rows[2] as ViewGroup).let { group ->
                (0 until group.childCount).map(group::getChildAt).filterIsInstance<Button>()
            }
            assertEquals("all three requested actions must actually be attached", 3, actions.size)
            actions.forEach { action ->
                assertTrue("fixture sanity: an action must be laid out", action.height > 0)
                assertTrue(
                    "an action ends at ${action.bottomInWindow()} on a ${panelBottom}px panel",
                    action.bottomInWindow() <= panelBottom,
                )
            }
        }
    }

    /** A replacement phase starts at the top of its own content, not where the last one was scrolled. */
    @Test
    fun aPhaseChangeReturnsToTheTopOfTheNewContent() {
        onFrame { activity, surface ->
            surface.show(
                listOf(surface.notice.line("Entity filter needs attention")) +
                    (1..12).map { surface.notice.footer("Filler row $it so the body genuinely overflows") },
            )
            activity.settle()
            surface.scroller().scrollTo(0, 400)
            assertNotEquals("the fixture must actually scroll", 0, surface.scroller().scrollY)
            surface.show(listOf(surface.notice.line("Home Assistant sign-in rejected")))
            activity.settle()
            assertEquals("a new phase must start at its own top", 0, surface.scroller().scrollY)
        }
    }

    /** Actions look different when held and when disabled — both kinds, not only the emphasised one. */
    @Test
    fun everyActionShowsItsPressedAndDisabledStates() {
        onFrame { activity, surface ->
            listOf(
                surface.notice.action("Retry") {},
                surface.notice.action("Ignore flagged entities and continue", primary = true) {},
            ).forEach { action: Button ->
                surface.show(listOf(surface.notice.actions(action)))
                activity.settle()
                val idle = action.background.constantState
                action.isPressed = true
                action.refreshDrawableState()
                val pressed = action.background.current.constantState
                action.isPressed = false
                action.isEnabled = false
                action.refreshDrawableState()
                val disabled = action.background.current.constantState
                assertNotEquals("'${action.text}' looks the same when held", idle, pressed)
                assertNotEquals("'${action.text}' looks the same when disabled", pressed, disabled)
                assertNotEquals(
                    "'${action.text}' label does not change when disabled",
                    action.textColors.getColorForState(intArrayOf(android.R.attr.state_enabled), 0),
                    action.textColors.getColorForState(intArrayOf(-android.R.attr.state_enabled), 0),
                )
            }
        }
    }

    /**
     * At an enlarged font scale the frame grows, and every row stays reachable by scrolling.
     *
     * An earlier version of this test proved nothing, and review was right to say so:
     * it never set a font scale, and its reachability expression compared a subtraction to the same
     * subtraction, so it was true whatever the frame did. This one enlarges the scale for real through
     * the host's base context, measures the same phase at both scales, and requires the enlarged one
     * to actually overflow — otherwise the scrolling half would be vacuous in turn.
     */
    @Test
    fun aLargeFontScaleGrowsTheFrameRatherThanClippingIt() {
        var atDeviceScale = 0
        onFrame { activity, surface ->
            surface.show(surface.tallestPhase())
            activity.settle()
            atDeviceScale = surface.bodyExtent()
            assertTrue("fixture sanity: the phase must lay out at all", atDeviceScale > 0)
        }

        onFrame(fontScale = LARGE_FONT_SCALE) { activity, surface ->
            val rows = surface.tallestPhase()
            surface.show(rows)
            activity.settle()

            val scroller = surface.scroller()
            assertTrue(
                "the frame must GROW with the font scale: ${atDeviceScale}px then ${surface.bodyExtent()}px",
                surface.bodyExtent() > atDeviceScale,
            )
            val overflow = surface.bodyExtent() - scroller.height
            assertTrue(
                "the fixture must actually overflow at ${LARGE_FONT_SCALE}x, or the scrolling below " +
                    "proves nothing (rows reach ${surface.bodyExtent()}px, viewport ${scroller.height}px)",
                overflow > 0,
            )

            // Reaching the end must be possible, and the scroller must actually go there — a clamp
            // short of the overflow is exactly the "content with no way to reach it" failure.
            scroller.scrollTo(0, overflow)
            activity.settle()
            assertEquals("the body cannot be scrolled to its end", overflow, scroller.scrollY)

            val lastRow = rows.last()
            assertTrue("the final row must be laid out, not collapsed", lastRow.height > 0)
            assertTrue(
                "the final row ends at ${lastRow.bottomInWindow()} below a viewport ending at " +
                    "${scroller.bottomInWindow()}",
                lastRow.bottomInWindow() <= scroller.bottomInWindow(),
            )
        }
    }

    @Test
    fun automaticNativePortugueseKeepsTheBrazilianRegionBoundary() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val systemResources = Resources.getSystem()
        val originalSystem = Configuration(systemResources.configuration)
        val originalLanguage = Config(context).uiLanguage
        fun localizedRetry(language: String): String = context.createConfigurationContext(
            Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(language)) },
        ).getString(R.string.retry)
        val english = localizedRetry("en")
        val brazilian = localizedRetry("pt-BR")
        assertNotEquals("this boundary must be measured against the packaged Portuguese translation", english, brazilian)
        try {
            for ((signal, expected) in listOf("pt" to "en", "pt-PT" to "en", "pt-BR" to "pt-BR", "de-DE" to "de", "ru-RU" to "uk")) {
                instrumentation.runOnMainSync {
                    @Suppress("DEPRECATION")
                    systemResources.updateConfiguration(
                        Configuration(originalSystem).apply { setLocale(Locale.forLanguageTag(signal)) },
                        systemResources.displayMetrics,
                    )
                    NativeLocale.apply("auto")
                }
                val processContext = context.createConfigurationContext(
                    Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(signal)) },
                )
                assertEquals("automatic $signal resolves the correct native resource",
                    localizedRetry(expected), processContext.nativeString(R.string.retry))
            }
        } finally {
            instrumentation.runOnMainSync {
                @Suppress("DEPRECATION")
                systemResources.updateConfiguration(originalSystem, systemResources.displayMetrics)
                NativeLocale.apply(originalLanguage)
            }
            assertEquals("process locale is restored", originalSystem.locales, systemResources.configuration.locales)
        }
    }

    @Ignore("Known 1.0.0 failure, fixed in 1.0.1: Czech at 200% font wraps a status line instead of ellipsizing it on a 480x480 panel.")
    @Test
    fun czechAndBrazilianPortugueseStatusAndProximityTextRemainReadableAndReachable() {
        for (locale in requestedExpansionLocales()) for (dark in listOf(false, true)) {
            for (scale in listOf(1.0f, LARGE_FONT_SCALE)) onFrame(dark, scale, locale) { activity, surface ->
                val n = surface.notice
                val phases = listOf(
                    listOf(
                        n.hero("9999"),
                        n.line(activity.getText(R.string.notice_line_entities_need_decision)),
                        n.actions(
                            n.action(activity.getText(R.string.ignore_flagged_entities), primary = true) {},
                            n.action(activity.getText(R.string.disable_entity_filter)) {},
                            n.action(activity.getText(R.string.open_entity_settings)) {},
                        ),
                        n.footer(activity.getString(R.string.notice_attention_explanation)),
                    ),
                    listOf(
                        n.hero("0:14"),
                        n.line(activity.getText(R.string.cannot_reach_ha)),
                        n.actions(n.action(activity.getText(R.string.retry), primary = true) {}, n.action(activity.getText(R.string.configure)) {}),
                        n.footer(activity.getString(R.string.cannot_reach_ha_detail,
                            activity.getString(R.string.ha_transport_dns), "UnknownHostException: homeassistant.example.invalid")),
                    ),
                    listOf(
                        n.line(activity.getText(R.string.proximity_wizard_review)),
                        n.footer(activity.getText(R.string.proximity_wizard_review_neither)),
                        n.footer(activity.getText(R.string.proximity_wizard_review_hint)),
                        n.actions(
                            n.action(activity.getText(R.string.proximity_wizard_save), primary = true) {},
                            n.action(activity.getText(R.string.proximity_wizard_cancel)) {},
                        ),
                    ),
                )
                for (rows in phases) {
                    surface.show(rows)
                    activity.settle()
                    assertEquals("a localized frame fills the physical panel", panelHeightPx, surface.root.height)
                    rows.flatMap { row -> if (row is ViewGroup) (0 until row.childCount).map(row::getChildAt) else listOf(row) }
                        .filterIsInstance<TextView>().forEach { assertTextFits(it, "$locale $scale") }
                    val scroller = surface.scroller()
                    val overflow = (surface.bodyExtent() - scroller.height).coerceAtLeast(0)
                    scroller.scrollTo(0, overflow)
                    activity.settle()
                    assertEquals("$locale $scale: final action is reachable", overflow, scroller.scrollY)
                    assertTrue("$locale $scale: final action is within the viewport", rows.last().bottomInWindow() <= scroller.bottomInWindow())
                }
            }
        }
    }

    @Ignore("Known 1.0.0 failure, fixed in 1.0.1: Czech at 200% font pushes the approval actions below a 480x480 panel.")
    @Test
    fun czechAndBrazilianPortugueseApprovalDialogsKeepTheirEvidenceAndActionsReachable() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        for (locale in requestedExpansionLocales()) for (scale in listOf(1.0f, LARGE_FONT_SCALE)) {
            StatusSurfaceTestHost.localeTagOverride = locale
            StatusSurfaceTestHost.fontScaleOverride = scale
            try {
                ActivityScenario.launch(StatusSurfaceTestHost::class.java).use { scenario ->
                    lateinit var dialog: AlertDialog
                    scenario.onActivity { activity ->
                        assertEquals(locale, activity.resources.configuration.locales[0].toLanguageTag())
                        assertEquals(scale, activity.resources.configuration.fontScale, 0.001f)
                        dialog = AlertDialog.Builder(activity)
                            .setTitle(activity.getText(R.string.approval_op_backup_restore))
                            .setMessage(activity.getString(R.string.approval_request_detail,
                                "Restore backup: 123456789 bytes; SHA-256: " + "a".repeat(64), "192.0.2.123"))
                            .setPositiveButton(R.string.approve, null)
                            .setNegativeButton(R.string.deny, null)
                            .setNeutralButton(R.string.cancel, null)
                            .show()
                    }
                    instrumentation.waitForIdleSync()
                    scenario.onActivity {
                        val decor = checkNotNull(dialog.window).decorView
                        assertTrue("$locale $scale: approval dialog fits the panel", decor.width in 1..480 && decor.height in 1..panelHeightPx)
                        val texts = textViews(decor).filter { it.visibility == View.VISIBLE && it.text.isNotEmpty() }
                        assertTrue("the actual approval evidence was loaded", texts.any { it.text.contains("a".repeat(64)) })
                        texts.forEach { assertTextFits(it, "$locale $scale approval") }
                        for (which in listOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL)) {
                            val button = dialog.getButton(which)
                            assertTrue("$locale $scale: approval action remains visible", button.isShown && button.height > 0)
                            assertTrue("$locale $scale: approval action stays on the panel", button.topInWindow() >= 0 && button.bottomInWindow() <= panelHeightPx)
                        }
                        val message = checkNotNull(dialog.findViewById<TextView>(android.R.id.message))
                        val scroll = checkNotNull(generateSequence(message.parent) { it.parent }
                            .filterIsInstance<androidx.core.widget.NestedScrollView>().firstOrNull()) {
                            "$locale $scale: approval evidence has no scroll containment"
                        }
                        val bottom = (scroll.getChildAt(0).height - scroll.height + scroll.paddingTop + scroll.paddingBottom).coerceAtLeast(0)
                        scroll.scrollTo(0, bottom)
                        assertEquals("$locale $scale: evidence can scroll to the end", bottom, scroll.scrollY)
                        assertTrue("$locale $scale: final evidence is reachable", message.bottomInWindow() <= scroll.bottomInWindow())
                        dialog.dismiss()
                    }
                }
            } finally {
                StatusSurfaceTestHost.localeTagOverride = null
                StatusSurfaceTestHost.fontScaleOverride = 0f
            }
        }
    }

    @Ignore("Known 1.0.0 failure, fixed in 1.0.1: the test host opens this card in en-US when Czech is requested.")
    @Test
    fun currentLifecycleCardKeepsItsStepsClockAndDetailsReadable() {
        val config = Config(InstrumentationRegistry.getInstrumentation().targetContext)
        val originalTheme = config.dashboardThemeDark
        data class Case(val name: String, val state: HaLifecycleState, val elapsed: Long?, val expected: Long?, val text: Int, val clock: String? = null, val connected: Boolean = false, val expectedTrack: List<Int>? = null)
        val cases = listOf(
            Case("stopping measured", HaLifecycleState.SHUTTING_DOWN, 30_000L, 113_000L, R.string.ha_until_back, "1:23"),
            Case("starting measured", HaLifecycleState.STARTING, 90_000L, 113_000L, R.string.ha_until_back, "0:23"),
            Case("past usual", HaLifecycleState.STARTING, 999_000L, 380_000L, R.string.ha_past_usual, "+10:19"),
            Case("reloading", HaLifecycleState.BACK_ONLINE, 118_000L, 113_000L, R.string.ha_reloading_detail),
            Case("offline", HaLifecycleState.CONNECTION_LOST, null, null, R.string.ha_offline),
            Case("stopping unmeasured", HaLifecycleState.SHUTTING_DOWN, null, null, R.string.ha_not_measured),
            Case("starting unmeasured", HaLifecycleState.STARTING, null, null, R.string.ha_not_measured),
            Case("dashboard connected during startup", HaLifecycleState.STARTING, 80_000L, 100_000L, R.string.ha_reloading_detail, "0:20", connected = true, expectedTrack = listOf(10_000, 10_000, 4_000)),
        )
        val failures = mutableListOf<String>()
        try {
            for (locale in requestedExpansionLocales()) for (dark in listOf(false, true)) {
                config.setDashboardThemeDark(dark)
                for (scale in listOf(1.0f, LARGE_FONT_SCALE)) onFrame(dark, scale, locale) { activity, _ ->
                    val root = FrameLayout(activity)
                    activity.setContentView(root)
                    for (case in cases) {
                        val renderer = RendererAdmissionRuntime.Live(1L, null, case.connected, null)
                        val bar = HaLifecycleBar.attach(activity, root, { renderer })
                        val description = "$locale font=$scale dark=$dark ${case.name}"
                        try {
                            val snapshot = HaLifecycle.Snapshot(case.state,
                                false, 1L, if (case.state == HaLifecycleState.BACK_ONLINE) HaLifecycle.DEFAULT_BACK_ONLINE_WINDOW_MS else 0L,
                                reason = HaLifecycleReason.CORE_UPDATE, elapsedMs = case.elapsed, expectedMs = case.expected)
                            bar.update(snapshot)
                            case.expectedTrack?.let { expected ->
                                val progress = viewGroups(root).flatMap { parent ->
                                    (0 until parent.childCount).map(parent::getChildAt).filterIsInstance<ProgressBar>()
                                }.map { it.progress }
                                assertEquals("$description: the displayed track is the shared restart clock", expected, progress)
                            }
                            activity.settle()
                            val texts = textViews(root).filter { it.isShown && it.text.isNotEmpty() }
                            assertTrue("$description: the production detail is visible", texts.any { it.text.toString() == activity.getString(case.text) })
                            assertTrue("$description: Panel Assistant owns the notice", texts.any { it.text.toString() == "Panel Assistant" })
                            case.clock?.let { clock ->
                                assertTrue("$description: the exact countdown is visible", texts.any { it.text.toString() == clock })
                            }
                            fun duration(milliseconds: Long): String {
                                val seconds = milliseconds / 1_000L
                                return if (seconds <= 180L) activity.getString(R.string.ha_exact_seconds, seconds.toInt())
                                else activity.getString(R.string.ha_exact_minutes_seconds, (seconds / 60L).toInt(), (seconds % 60L).toInt())
                            }
                            val expectedFooter = when {
                                case.state == HaLifecycleState.CONNECTION_LOST -> null
                                case.state == HaLifecycleState.BACK_ONLINE -> activity.getString(R.string.ha_restarted_in, duration(118_000L))
                                case.expected != null -> activity.getString(R.string.ha_usually, activity.getString(R.string.ha_reason_core_update), duration(case.expected))
                                else -> activity.getString(R.string.ha_reason_core_update)
                            }
                            expectedFooter?.let { footer ->
                                assertTrue("$description: the complete measured footer is visible", texts.any { it.text.toString() == footer })
                            }
                            for (text in texts) {
                                assertTextFits(text, "$description text='${text.text}'")
                                assertTrue("$description: '${text.text}' stays on the panel", text.topInWindow() >= 0 && text.bottomInWindow() <= panelHeightPx)
                                assertTrue("$description: '${text.text}' stays inside its parents",
                                    generateSequence(text.parent) { it.parent }.filterIsInstance<ViewGroup>().all { parent ->
                                        text.topInWindow() >= parent.topInWindow() && text.bottomInWindow() <= parent.bottomInWindow()
                                    })
                            }
                            if (case.state != HaLifecycleState.CONNECTION_LOST) {
                                for (step in listOf(R.string.ha_step_stopping, R.string.ha_step_starting, R.string.ha_step_reloading)) {
                                    assertTrue("$description: every restart step remains visible", texts.any { it.text.toString() == activity.getString(step) })
                                }
                            }
                            if (case.connected) {
                                assertTrue("$description: Reloading labels the current step while Home Assistant is still starting",
                                    texts.count { it.text.toString() == activity.getString(R.string.ha_step_reloading) } >= 2)
                                bar.update(snapshot.copy(state = HaLifecycleState.BACK_ONLINE))
                                activity.settle()
                                assertTrue("$description: ready and connected closes the notice", textViews(root).none { it.isShown })
                                assertEquals("$description: closing preserves the renderer's container", 1, root.childCount)
                            }
                        } catch (failure: AssertionError) {
                            failures += "$description: ${failure.message}"
                        } finally {
                            bar.detach()
                            assertEquals("the test retires the native card and its callbacks", 0, root.childCount)
                        }
                    }
                }
            }
            assertTrue("native lifecycle clipping:\n${failures.joinToString("\n")}", failures.isEmpty())
        } finally {
            originalTheme?.let(config::setDashboardThemeDark) ?: config.clearDashboardThemeDark()
            assertEquals("the original observed dashboard theme is restored", originalTheme, config.dashboardThemeDark)
        }
    }

    @Ignore("Known 1.0.0 failure, fixed in 1.0.1: Czech at 200% font clips the last proximity intro line on a 480x480 panel.")
    @Test
    fun czechAndBrazilianPortugueseRealProximityActivityKeepsItsCopyAndActionsReachable() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        fun shell(command: String): String = android.os.ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(command),
        ).bufferedReader().use { it.readText().trim() }
        assertEquals("this test changes and restores font scale only on the AOSP emulator", "1", shell("getprop ro.kernel.qemu"))
        val originalFont = (shell("settings get system font_scale").toFloatOrNull() ?: 1f)
        val config = Config(instrumentation.targetContext)
        val originalLanguage = config.uiLanguage
        val owner = Any()
        val snapshot = AtomicReference("")
        try {
            ProximityWizardHost.attach(owner, status = snapshot::get, action = { true })
            for (scale in listOf(1.0f, LARGE_FONT_SCALE)) {
                shell("settings put system font_scale $scale")
                for (locale in requestedExpansionLocales()) {
                    config.setRaw(requireNotNull(SettingsRegistry.spec("ui_language")), locale)
                    val phases = listOf(
                        Triple("intro", "", R.string.proximity_wizard_intro),
                        Triple("waves", "WAVE", R.string.proximity_wizard_wave_twice),
                        Triple("review", "", R.string.proximity_wizard_review),
                        Triple("failed", "", R.string.proximity_wizard_failed),
                    )
                    fun publish(stage: String, cue: String) {
                        snapshot.set(JSONObject().put("sessionId", "locale-layout-test")
                            .put("stage", stage).put("cue", cue).put("health", "healthy")
                            .put("presenceSupported", true).put("waveSupported", true)
                            .put("wavePattern", "double").put("raw", 65535.0)
                            .put("acceptedGestures", 19).put("requiredGestures", 20)
                            .put("cueRemainingMs", 1000).put("cueDurationMs", 2000)
                            .put("canSave", true).toString())
                    }
                    publish("intro", "")
                    ActivityScenario.launch(ProximityWizardActivity::class.java).use { scenario ->
                        for ((stage, cue, title) in phases) {
                            publish(stage, cue)
                            var ready = false
                            val deadline = SystemClock.uptimeMillis() + 5000
                            do {
                                instrumentation.waitForIdleSync()
                                scenario.onActivity { activity ->
                                    val root = activity.findViewById<View>(android.R.id.content)
                                    ready = activity.resources.configuration.locales[0].toLanguageTag() == locale &&
                                        activity.resources.configuration.fontScale == scale &&
                                        textViews(root).any { it.isShown && it.text.toString() == activity.getString(title) }
                                }
                                if (!ready) SystemClock.sleep(25)
                            } while (!ready && SystemClock.uptimeMillis() < deadline)
                            assertTrue("$locale $scale $stage: the production activity rendered its resource text", ready)
                            scenario.onActivity { activity ->
                                activity.settle()
                                val root = activity.findViewById<View>(android.R.id.content)
                                val visible = textViews(root).filter { it.isShown && it.text.isNotEmpty() }
                                visible.forEach { assertTextFits(it, "$locale $scale proximity $stage") }
                                visible.filterIsInstance<Button>().forEach { button ->
                                    assertTrue("$locale $scale $stage: action stays on the panel", button.topInWindow() >= 0 && button.bottomInWindow() <= panelHeightPx)
                                }
                                val scroll = checkNotNull(viewGroups(root).filterIsInstance<ScrollView>().firstOrNull())
                                val bottom = (scroll.getChildAt(0).height - scroll.height + scroll.paddingTop + scroll.paddingBottom).coerceAtLeast(0)
                                scroll.scrollTo(0, bottom)
                                assertEquals("$locale $scale $stage: copy can scroll to the end", bottom, scroll.scrollY)
                                for (text in visible.filterNot { it is Button }) {
                                    assertTrue("$locale $scale $stage: text stays inside its parents",
                                        generateSequence(text.parent) { it.parent }.filterIsInstance<ViewGroup>()
                                            .takeWhile { it !== scroll }.all { parent -> text.bottomInWindow() <= parent.bottomInWindow() })
                                }
                            }
                        }
                    }
                }
            }
        } finally {
            ProximityWizardHost.detach(owner)
            config.setRaw(requireNotNull(SettingsRegistry.spec("ui_language")), originalLanguage)
            instrumentation.runOnMainSync { NativeLocale.apply(originalLanguage) }
            shell("settings put system font_scale $originalFont")
            assertEquals("the emulator's saved language was restored", originalLanguage, config.uiLanguage)
            assertEquals("the emulator's font setting was restored", originalFont, (shell("settings get system font_scale").toFloatOrNull() ?: 1f), 0.001f)
        }
    }

    private fun viewGroups(view: View): List<ViewGroup> = if (view is ViewGroup) {
        listOf(view) + (0 until view.childCount).flatMap { viewGroups(view.getChildAt(it)) }
    } else emptyList()

    private fun textViews(view: View): List<TextView> = when (view) {
        is TextView -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { textViews(view.getChildAt(it)) }
        else -> emptyList()
    }

    private fun assertTextFits(view: TextView, context: String) {
        val layout = checkNotNull(view.layout) { "$context: text was never laid out" }
        assertTrue("$context: '${view.text}' has a nonzero drawing area", view.width > view.compoundPaddingLeft + view.compoundPaddingRight && view.height > 0)
        assertEquals("$context: every character remains in the layout", view.text.length, layout.getLineEnd(layout.lineCount - 1))
        for (line in 0 until layout.lineCount) {
            assertEquals("$context: line $line is not ellipsized", 0, layout.getEllipsisCount(line))
            assertTrue("$context: line $line fits the available width", layout.getLineMax(line) <= layout.width + 1f)
        }
        assertTrue("$context: final text line is not clipped vertically", layout.getLineBottom(layout.lineCount - 1) <= view.height - view.compoundPaddingTop - view.compoundPaddingBottom)
        view.text.toString().codePoints().filter { Character.isLetter(it) }.distinct().forEach { codepoint ->
            assertTrue("$context: missing glyph U+${codepoint.toString(16)}", view.paint.hasGlyph(String(Character.toChars(codepoint))))
        }
    }

    private companion object {
        /** Android 14's largest accessibility font size, so this is a real user setting, not a stress value. */
        const val LARGE_FONT_SCALE = 2.0f
    }
}
