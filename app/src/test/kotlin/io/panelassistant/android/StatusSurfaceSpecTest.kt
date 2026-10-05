package io.panelassistant.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The layout evidence for the branded status frame.
 *
 * There is no view instrumentation in this gate — no Robolectric, no screenshot harness — so the
 * geometry is asserted where it is decided, in [statusSurfaceSpec] and its companions, and
 * [StatusSurface] is held to containing no size of its own by [StatusSurfaceWiringContractTest].
 *
 * The panel sizes below are the real supported extremes: 480x480 is the smallest panel the project
 * supports and the one every phase has to survive, 1920x1200 the largest.
 */
class StatusSurfaceSpecTest {

    /**
     * WCAG relative luminance and contrast ratio.
     *
     * The previous version of these assertions compared arithmetic RGB averages, which is not a
     * contrast measurement: it passed a secondary control sitting at about 1.3:1 against the page.
     */
    private fun luminance(hex: String): Double {
        fun channel(value: Int): Double {
            val c = value / 255.0
            return if (c <= 0.04045) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
        }
        val v = hex.removePrefix("#")
        return 0.2126 * channel(v.substring(0, 2).toInt(16)) +
            0.7152 * channel(v.substring(2, 4).toInt(16)) +
            0.0722 * channel(v.substring(4, 6).toInt(16))
    }

    private fun contrast(a: String, b: String): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    /**
     * A held or disabled action is visibly different from an idle one, in both themes.
     *
     * Added after its mutation SURVIVED: an earlier edit to add this silently matched nothing, so the
     * palette carried held and disabled surfaces that no assertion compared. The entity-filter
     * recovery disables these buttons while it works, so "disabled looks like enabled" is a screen
     * lying about what it is doing.
     */
    @Test
    fun everyActionStateIsVisiblyDistinct() {
        listOf(true, false).forEach { dark ->
            val p = statusPalette(dark)
            val theme = if (dark) "dark" else "light"
            assertNotEquals("$theme: a press must change the surface", p.actionSurface, p.actionPressed)
            assertNotEquals("$theme: disabled must differ from idle", p.actionSurface, p.actionDisabled)
            assertTrue(
                "$theme: a disabled action must recede toward the page rather than stand out",
                contrast(p.actionDisabled, p.background) < contrast(p.actionSurface, p.background),
            )
        }
    }

    /** Body and heading text must clear WCAG AA against the surface behind it, in both themes. */
    @Test
    fun everyTextColourClearsAaAgainstItsOwnBackground() {
        listOf(true, false).forEach { dark ->
            val p = statusPalette(dark)
            val theme = if (dark) "dark" else "light"
            assertTrue("$theme body text: ${contrast(p.body, p.background)}", contrast(p.body, p.background) >= 4.5)
            assertTrue("$theme small print: ${contrast(p.subtle, p.background)}", contrast(p.subtle, p.background) >= 4.5)
            assertTrue("$theme error heading: ${contrast(p.error, p.background)}", contrast(p.error, p.background) >= 4.5)
            assertTrue("$theme primary label: ${contrast(p.actionText, p.actionBackground)}", contrast(p.actionText, p.actionBackground) >= 4.5)
            assertTrue("$theme secondary label: ${contrast(p.body, p.actionSurface)}", contrast(p.body, p.actionSurface) >= 4.5)
        }
    }

    /**
     * A control must be distinguishable from the page it sits on.
     *
     * A secondary fill sits close to the page deliberately, so its visible BOUNDARY carries the
     * contrast — 3:1 is what WCAG asks of a user-interface component. Asserting the fill instead is
     * what let a 1.3:1 control through.
     */
    @Test
    fun everyActionIsDistinguishableFromThePage() {
        listOf(true, false).forEach { dark ->
            val p = statusPalette(dark)
            val theme = if (dark) "dark" else "light"
            assertTrue("$theme secondary border: ${contrast(p.actionBorder, p.background)}", contrast(p.actionBorder, p.background) >= 3.0)
            assertTrue("$theme primary fill: ${contrast(p.actionBackground, p.background)}", contrast(p.actionBackground, p.background) >= 3.0)
        }
    }

    private val smallestWidthDp = 480
    private val smallestHeightDp = 480

    // --- the brand row is fixed ------------------------------------------------------------------




    // --- everything fits the smallest panel -------------------------------------------------------





    // --- the compact tier -------------------------------------------------------------------------

    /**
     * Compact is genuinely smaller everywhere it claims to be, or the tier is decoration.
     *
     * Restored after removing the fit model took it with it: three mutations survived, which is the
     * battery reporting that these sizes had stopped being asserted at all.
     */
    @Test
    fun everyCompactSizeIsSmallerThanItsRoomyCounterpart() {
        val compact = statusSurfaceSpec(480, 480)
        val roomy = statusSurfaceSpec(1_920, 1_200)
        assertTrue(compact.brandHeightDp < roomy.brandHeightDp)
        assertTrue(compact.brandTopInsetDp < roomy.brandTopInsetDp)
        assertTrue(compact.brandBottomInsetDp < roomy.brandBottomInsetDp)
        assertTrue(compact.headingSp < roomy.headingSp)
        assertTrue(compact.detailSp < roomy.detailSp)
        assertTrue(compact.rowGapDp < roomy.rowGapDp)
    }

    /** The reading column is capped on a wide panel and inset on a narrow one. */
    @Test
    fun theColumnIsCappedOnWidePanelsAndInsetOnNarrowOnes() {
        assertEquals(480 - STATUS_SIDE_INSET_DP, statusSurfaceSpec(480, 480).columnWidthDp)
        assertEquals(STATUS_COLUMN_MAX_DP, statusSurfaceSpec(1_920, 1_200).columnWidthDp)
        // A full-width action is capped below the column: a button running the full reading width
        // reads as a banner rather than something to press.
        assertEquals(STATUS_ACTION_MAX_DP, statusSurfaceSpec(1_920, 1_200).actionWidthDp)
        assertEquals(STATUS_ACTION_MAX_DP, statusSurfaceSpec(480, 480).actionWidthDp)
        assertTrue(statusSurfaceSpec(480, 480).actionWidthDp < statusSurfaceSpec(480, 480).columnWidthDp)
        assertEquals(320 - STATUS_SIDE_INSET_DP, statusSurfaceSpec(320, 480).actionWidthDp)
    }

    /** A panel narrower than the inset must still produce usable positive widths, not a crash. */
    @Test
    fun anAbsurdlyNarrowPanelStillProducesPositiveWidths() {
        val spec = statusSurfaceSpec(16, 16)
        assertTrue(spec.columnWidthDp > 0)
        assertTrue(spec.actionWidthDp > 0)
    }

    @Test
    fun theSmallestPanelTakesTheCompactTierAndALargeOneDoesNot() {
        assertTrue(statusSurfaceSpec(smallestWidthDp, smallestHeightDp).compact)
        assertTrue(statusSurfaceSpec(480, STATUS_COMPACT_HEIGHT_DP - 1).compact)
        assertTrue(!statusSurfaceSpec(480, STATUS_COMPACT_HEIGHT_DP).compact)
        assertTrue(!statusSurfaceSpec(1_920, 1_200).compact)
    }


    // --- the action control -----------------------------------------------------------------------

    /** Both tiers, since a control that only reads as one on a single panel size is not fixed. */
    private val bothTiers get() = listOf(statusSurfaceSpec(480, 480), statusSurfaceSpec(1_920, 1_200))

    /**
     * An action is the standing screen's control, on every panel size.
     *
     * Asserted against the shared constants rather than against repeated numbers, because the point of
     * the change is that there is now one control and not two. A tier split here would be the first
     * step back toward the divergence: the standing screen draws the same button on a 480x480 panel and
     * on a 1920x1200 one, and that screen is the accepted one.
     */
    @Test
    fun anActionIsTheStandingScreensControlOnEveryPanelSize() {
        bothTiers.forEach { spec ->
            assertEquals(STATUS_ACTION_CORNER_DP, spec.actionCornerDp)
            assertEquals(STATUS_ACTION_PADDING_H_DP, spec.actionPaddingHorizontalDp)
            assertEquals(STATUS_ACTION_PADDING_V_DP, spec.actionPaddingVerticalDp)
            assertEquals(STATUS_ACTION_LABEL_SP, spec.actionLabelSp, 0f)
            assertEquals(STATUS_ACTION_SIDE_GAP_DP, spec.actionSideGapDp)
            assertEquals(STATUS_ACTION_STACK_GAP_DP, spec.actionGapDp)
            assertEquals(STATUS_ACTION_GROUP_GAP_DP, spec.actionGroupGapDp)
        }
    }

    /**
     * The control has room around its label, because on the panel it had none.
     *
     * Observed at vc595 on a 480x480 panel: the label touches the border on both sides. Reasoning had
     * said the replaced drawable would leave about 12dp of its padding behind; reasoning was wrong.
     * These are the properties that make it a control rather than a word with a line drawn round it.
     */
    @Test
    fun theControlIsBigEnoughToReadAsOne() {
        bothTiers.forEach { spec ->
            assertTrue("the label must be held off the border", spec.actionPaddingHorizontalDp > 0)
            assertTrue("vertical padding must be positive", spec.actionPaddingVerticalDp > 0)
            // The touch target still governs at a normal font scale. If padding alone made the control
            // tall, the 48dp floor would have stopped meaning anything.
            assertTrue(
                "padding alone must not exceed the touch target",
                2 * spec.actionPaddingVerticalDp < spec.actionHeightDp,
            )
            // A stadium was built here and rejected on sight; this is what stops the corner drifting
            // back toward one.
            assertTrue(
                "a corner of ${spec.actionCornerDp}dp against a ${spec.actionHeightDp}dp control is a pill",
                spec.actionCornerDp * 4 < spec.actionHeightDp,
            )
            assertTrue("two actions side by side must be separated", spec.actionSideGapDp > 0)
            // Stacked, they are separated by more than two lines of prose are, or a column of actions
            // reads as one block.
            assertTrue(
                "actions ${spec.actionGapDp}dp apart against rows ${spec.rowGapDp}dp apart",
                spec.actionGapDp > spec.rowGapDp,
            )
            // And the group is separated from what surrounds it by MORE than its members are from
            // each other. With the two conflated, the controls sat closer to the sentence explaining
            // them than to one another, so they read as part of the text instead of as the thing to
            // press. This is the ordering that makes a group look like a group.
            assertTrue(
                "a group ${spec.actionGroupGapDp}dp from its surroundings against " +
                    "${spec.actionGapDp}dp between its own actions",
                spec.actionGroupGapDp > spec.actionGapDp,
            )
        }
    }

    /** The label is sized by the frame, between the running commentary and the heading. */
    @Test
    fun theActionLabelIsSizedByTheFrame() {
        bothTiers.forEach { spec ->
            assertTrue("${spec.actionLabelSp} must read above the detail line", spec.actionLabelSp > spec.detailSp)
            assertTrue("${spec.actionLabelSp} must not compete with the heading", spec.actionLabelSp < spec.headingSp)
        }
    }


    // --- theme ------------------------------------------------------------------------------------

    /** A configured dashboard theme is the panel's decision and outranks both fallbacks. */
    @Test
    fun aConfiguredThemeWinsOverTheSystemAndTheStoredPreference() {
        assertTrue(statusSurfaceDark(forcedDark = null, configuredDark = true, systemDark = false, storedDark = false, sdkInt = 33))
        assertTrue(!statusSurfaceDark(forcedDark = null, configuredDark = false, systemDark = true, storedDark = true, sdkInt = 33))
        assertTrue(statusSurfaceDark(forcedDark = null, configuredDark = true, systemDark = false, storedDark = false, sdkInt = 26))
    }

    /** Android 10 and newer have a real system night setting; older panels do not, so the panel's own
     *  stored preference is the only honest answer there. */
    @Test
    fun theSystemSettingIsFollowedOnlyWhereAndroidActuallyHasOne() {
        assertTrue(statusSurfaceDark(forcedDark = null, configuredDark = null, systemDark = true, storedDark = false, sdkInt = 29))
        assertTrue(!statusSurfaceDark(forcedDark = null, configuredDark = null, systemDark = false, storedDark = true, sdkInt = 29))
        assertTrue(statusSurfaceDark(forcedDark = null, configuredDark = null, systemDark = false, storedDark = true, sdkInt = 28))
        assertTrue(!statusSurfaceDark(forcedDark = null, configuredDark = null, systemDark = true, storedDark = false, sdkInt = 28))
    }

    /**
     * An explicit Dark/Light policy outranks every other input, including a theme already observed
     * from Home Assistant. These screens stand in for the dashboard, so a panel told to show a dark
     * dashboard must not show a light screen on the way to it — and it must be right on the FIRST
     * frame after the change, not one renderer observation later.
     */
    @Test
    fun anExplicitThemePolicyOutranksEveryOtherInput() {
        // Against a contrary observed theme, both directions, on both Android tiers.
        assertTrue(statusSurfaceDark(forcedDark = true, configuredDark = false, systemDark = false, storedDark = false, sdkInt = 33))
        assertTrue(!statusSurfaceDark(forcedDark = false, configuredDark = true, systemDark = true, storedDark = true, sdkInt = 33))
        assertTrue(statusSurfaceDark(forcedDark = true, configuredDark = false, systemDark = false, storedDark = false, sdkInt = 26))
        assertTrue(!statusSurfaceDark(forcedDark = false, configuredDark = true, systemDark = true, storedDark = true, sdkInt = 26))
        // Follow surrenders the decision entirely: it must not act as a third opinion.
        assertTrue(statusSurfaceDark(forcedDark = null, configuredDark = true, systemDark = false, storedDark = false, sdkInt = 33))
        assertTrue(!statusSurfaceDark(forcedDark = null, configuredDark = false, systemDark = true, storedDark = true, sdkInt = 33))
    }

    // --- keeping the frame between phases ---------------------------------------------------------

    /** A frame is kept for the next phase, and only a theme change earns a rebuild. */
    @Test
    fun theFrameIsKeptUnlessTheThemeChanges() {
        val square = statusSurfaceSpec(480, 480)
        assertTrue("the same theme must reuse the frame", statusSurfaceReusable(square, true, square, true))
        assertTrue("the same theme must reuse the frame", statusSurfaceReusable(square, false, square, false))
        assertTrue(
            "a theme change must rebuild — the artwork and every colour differ",
            !statusSurfaceReusable(square, true, square, false),
        )
        assertTrue(
            "there is nothing to reuse before the first frame exists",
            !statusSurfaceReusable(null, null, square, true),
        )
    }

    /**
     * A frame built for one panel shape is not reused on another.
     *
     * The dashboard declares `orientation|screenSize` in its `configChanges`, so Android hands it a
     * new size without recreating it. A frame kept across that change would keep the old band height,
     * column width and type scale on a panel that is no longer that shape.
     */
    @Test
    fun theFrameIsRebuiltWhenThePanelGeometryChanges() {
        // A rotation that crosses the compact threshold, which is the one that genuinely changes the
        // layout: above the column and action caps the spec is width-insensitive, so rotating a large
        // panel legitimately needs no rebuild. The first fixture chosen here was two large landscapes
        // that produced an identical spec, and the assertion below caught it.
        val portrait = statusSurfaceSpec(480, 800)
        val landscape = statusSurfaceSpec(800, 480)
        assertNotEquals("the fixture must actually change the layout", portrait, landscape)
        assertTrue(
            "a rotation must rebuild the frame",
            !statusSurfaceReusable(portrait, true, landscape, true),
        )
        assertTrue(
            "a size change must rebuild the frame",
            !statusSurfaceReusable(statusSurfaceSpec(480, 480), true, statusSurfaceSpec(1_920, 1_200), true),
        )
        assertTrue(
            "an unchanged panel must still reuse it",
            statusSurfaceReusable(landscape, true, statusSurfaceSpec(800, 480), true),
        )
    }

    /** Both themes are complete and genuinely different — a half-defined palette would draw invisible
     *  text on one of them. */
    @Test
    fun bothThemesAreCompleteAndDistinct() {
        val dark = statusPalette(true)
        val light = statusPalette(false)
        assertNotEquals(dark.background, light.background)
        assertNotEquals(dark.body, light.body)
        assertNotEquals(dark.subtle, light.subtle)
        assertNotEquals(dark.accent, light.accent)
        assertNotEquals(dark.track, light.track)
        assertNotEquals(dark.error, light.error)
        assertNotEquals(dark.actionBorder, light.actionBorder)
        assertNotEquals(dark.actionPressed, light.actionPressed)
        assertNotEquals(dark.actionDisabled, light.actionDisabled)
        listOf(dark, light).forEach { palette ->
            listOf(
                palette.background, palette.body, palette.subtle, palette.accent, palette.track,
                palette.actionBackground, palette.actionText, palette.actionSurface,
                palette.actionBorder, palette.actionPressed, palette.actionDisabled, palette.error,
            ).forEach { hex ->
                assertTrue("not a colour: $hex", Regex("^#[0-9a-fA-F]{6}$").matches(hex))
            }
        }
    }




}
