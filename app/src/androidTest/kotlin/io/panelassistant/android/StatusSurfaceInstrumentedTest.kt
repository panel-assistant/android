package io.panelassistant.android

import android.app.Activity
import android.util.DisplayMetrics
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.ScrollView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

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
        assertTrue(
            "logical ${real.widthPixels}x${real.heightPixels}dp must select the compact tier",
            (real.heightPixels / usable.density).toInt() < STATUS_COMPACT_HEIGHT_DP,
        )
    }

    /** Physical panel height, read once in the precondition so no measurement hard-codes it. */
    private var panelHeightPx = 0

    private fun onFrame(
        dark: Boolean = true,
        fontScale: Float = 0f,
        block: (Activity, StatusSurface) -> Unit,
    ) {
        StatusSurfaceTestHost.fontScaleOverride = fontScale
        try {
            ActivityScenario.launch(StatusSurfaceTestHost::class.java).use { scenario ->
                scenario.onActivity { activity ->
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
        }
    }

    /** The phase used for the overflow measurements: the tallest real screen the frame has to draw. */
    private fun StatusSurface.tallestPhase(): List<View> = listOf(
        heading("Entity filter needs attention"),
        detail(
            "Nothing is wrong with Home Assistant. Too many entities were flagged to review on the " +
                "panel. Open the Entities page in panel settings and simplify the dashboard, or turn " +
                "the entity filter off.",
        ),
        action("Ignore flagged entities and continue", fullWidth = true) {},
        action("Disable entity filter", fullWidth = true) {},
        action("Open entity settings", fullWidth = true) {},
    )

    /**
     * How far down the rows themselves actually reach.
     *
     * NOT the body container's height: the scroller sets `isFillViewport`, so a body shorter than the
     * viewport is stretched to it and its height measures the window instead of the content. Reading
     * it that way made this test report the frame SHRINKING at a larger font scale — the band grew, so
     * the viewport it was being pinned to got smaller.
     */
    private fun StatusSurface.bodyExtent(): Int {
        val content = scroller().getChildAt(0) as ViewGroup
        return (0 until content.childCount).maxOf { content.getChildAt(it).bottom }
    }

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

    private fun StatusSurface.mark(): View {
        val band = (root.getChildAt(0) as ViewGroup).getChildAt(0) as ViewGroup
        return band.getChildAt(0)
    }

    private fun StatusSurface.scroller(): ScrollView =
        (root.getChildAt(0) as ViewGroup).getChildAt(1) as ScrollView

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
                arrayOf(surface.heading("Checking Home Assistant compatibility")),
                arrayOf(
                    surface.heading("Entity filter needs attention"),
                    surface.detail(
                        "Nothing is wrong with Home Assistant. Too many entities were flagged to " +
                            "review on the panel.",
                    ),
                    surface.action("Ignore flagged entities and continue", fullWidth = true) {},
                    surface.action("Disable entity filter", fullWidth = true) {},
                    surface.action("Open entity settings", fullWidth = true) {},
                ),
                arrayOf(surface.caption("waiting 12s")),
            ).forEach { rows ->
                surface.setBody(*rows)
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
            surface.setBody(*rows.toTypedArray())
            activity.settle()
            val panelBottom = activity.findViewById<View>(android.R.id.content).height
            assertEquals("the frame must fill the panel", panelHeightPx, panelBottom)
            rows.filterIsInstance<Button>().forEach { action ->
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
            surface.setBody(
                surface.heading("Entity filter needs attention"),
                *(1..12).map { surface.detail("Filler row $it so the body genuinely overflows") }
                    .toTypedArray(),
            )
            activity.settle()
            surface.scroller().scrollTo(0, 400)
            assertNotEquals("the fixture must actually scroll", 0, surface.scroller().scrollY)
            surface.setBody(surface.heading("Home Assistant sign-in rejected"))
            activity.settle()
            assertEquals("a new phase must start at its own top", 0, surface.scroller().scrollY)
        }
    }

    /** Actions look different when held and when disabled — both kinds, not only the emphasised one. */
    @Test
    fun everyActionShowsItsPressedAndDisabledStates() {
        onFrame { activity, surface ->
            listOf(
                surface.action("Retry") {},
                surface.action("Ignore flagged entities and continue", primary = true) {},
            ).forEach { action: Button ->
                surface.setBody(action)
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
            surface.setBody(*surface.tallestPhase().toTypedArray())
            activity.settle()
            atDeviceScale = surface.bodyExtent()
            assertTrue("fixture sanity: the phase must lay out at all", atDeviceScale > 0)
        }

        onFrame(fontScale = LARGE_FONT_SCALE) { activity, surface ->
            val rows = surface.tallestPhase()
            surface.setBody(*rows.toTypedArray())
            activity.settle()

            val scroller = surface.scroller()
            val content = scroller.getChildAt(0) as ViewGroup
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
            assertEquals(
                "fixture sanity: the last row is the final action",
                content.getChildAt(content.childCount - 1),
                lastRow,
            )
            assertTrue("the final action must be laid out, not collapsed", lastRow.height > 0)
            assertTrue(
                "the final action ends at ${lastRow.bottomInWindow()} below a viewport ending at " +
                    "${scroller.bottomInWindow()}",
                lastRow.bottomInWindow() <= scroller.bottomInWindow(),
            )

            // The mark is outside the scroller, so scrolling to the end must not have moved it.
            assertTrue("the mark left the screen", surface.mark().topInWindow() >= 0)
            assertTrue(
                "the mark must stay above the body it heads",
                surface.mark().bottomInWindow() <= scroller.topInWindow(),
            )
        }
    }

    private companion object {
        /** Android 14's largest accessibility font size, so this is a real user setting, not a stress value. */
        const val LARGE_FONT_SCALE = 2.0f
    }
}
