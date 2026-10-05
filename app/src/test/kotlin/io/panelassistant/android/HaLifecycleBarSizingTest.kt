package io.panelassistant.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The notice's geometry, asserted without inflating a view.
 *
 * The rule under test is that the notice has a logical dp cap: its text must not keep growing just
 * because the display has more pixels, while runtime logical-density overrides remain respected.
 */
class HaLifecycleBarSizingTest {

    private val baselineEdge = 480f
    private val baselineDensity = 1f

    // A large panel measured on hardware: 1920x1200 at 226 dpi.
    private val largeEdge = 1200f
    private val largeDensity = 226f / 160f

    @Test fun theAcceptedFourEightyRenderingIsUnchanged() {
        // The 480x480 rendering was signed off on hardware. If this moves, previously accepted
        // visual evidence stops describing the build.
        val sizes = haLifecycleTextSizes(baselineEdge, baselineDensity)
        assertEquals(52.8f, sizes.headlinePx, 0.01f)
        assertEquals(20.16f, sizes.detailPx, 0.01f)
    }

    @Test fun aLargePanelIsCappedInsteadOfScalingWithItsPixels() {
        val sizes = haLifecycleTextSizes(largeEdge, largeDensity)
        // The uncapped fraction would be 132px, which took ~40% of that panel's screen height.
        assertTrue(
            "the headline must not follow the display's pixel count, was ${sizes.headlinePx}",
            sizes.headlinePx < 132f * 0.6f,
        )
        assertEquals(52.8f * largeDensity, sizes.headlinePx, 0.01f)
        assertEquals(20.16f * largeDensity, sizes.detailPx, 0.01f)
    }

    @Test fun theCapUsesLogicalDensityRatherThanADeviceIndependentPixelConstant() {
        // The whole point of expressing the cap in dp: at twice the logical density the same dp size
        // needs twice the pixels. A pixel-valued cap would shrink the text in logical units.
        val single = haLifecycleTextSizes(4000f, 1f)
        val double = haLifecycleTextSizes(4000f, 2f)
        assertEquals(2f * single.headlinePx, double.headlinePx, 0.01f)
        assertEquals(2f * single.detailPx, double.detailPx, 0.01f)
    }

    @Test fun aPanelSmallerThanTheReferenceStillUsesTheFraction() {
        // The cap must only ever bound growth. A smaller panel keeps scaling down, or the notice would
        // overflow a display narrower than the reference one.
        val sizes = haLifecycleTextSizes(320f, 1f)
        assertEquals(320f * 0.11f, sizes.headlinePx, 0.01f)
        assertTrue("a smaller panel must get smaller text", sizes.headlinePx < 52.8f)
    }

    @Test fun theHeadlineAlwaysDominatesTheSupportingLine() {
        // The two sizes are capped independently, so nothing structurally stops the caps crossing. If
        // they ever did, the supporting line would compete with the headline for attention.
        for (edge in listOf(320f, 480f, 800f, 1200f, 2160f)) {
            for (density in listOf(1f, 1.4125f, 2f, 3f)) {
                val sizes = haLifecycleTextSizes(edge, density)
                assertTrue(
                    "headline must lead at ${edge}px/${density}x",
                    sizes.headlinePx > sizes.detailPx,
                )
            }
        }
    }

    @Test fun anImpossibleDensityFallsBackRatherThanHidingTheNotice() {
        // A zero or negative density would collapse a dp-valued cap to zero and render invisible text,
        // which is a worse failure than an oversized notice.
        for (broken in listOf(0f, -1f)) {
            val sizes = haLifecycleTextSizes(1200f, broken)
            assertTrue("text must remain visible at density $broken", sizes.headlinePx > 0f)
            assertEquals(52.8f, sizes.headlinePx, 0.01f)
        }
    }
}
