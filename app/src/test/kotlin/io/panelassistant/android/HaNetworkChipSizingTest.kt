package io.panelassistant.android

import org.junit.Assert.assertEquals
import org.junit.Test

/** The chip's geometry rule, assertable without a view. */
class HaNetworkChipSizingTest {
    @Test fun theBaselinePanelUsesTheFractionAndNothingLarger() {
        // 480x480 at density 1: 4 % of the shortest edge, and the cap is defined to be exactly this.
        assertEquals(19.2f, haNetworkChipTextSizePx(480f, 1f), 0.001f)
    }

    @Test fun aLargePanelIsCappedAtTheBaselineLogicalSize() {
        // 1200px shortest edge at density 2: the fraction would say 48px; the cap says 19.2dp = 38.4px.
        assertEquals(38.4f, haNetworkChipTextSizePx(1200f, 2f), 0.001f)
    }

    @Test fun aSmallerPanelShrinksWithTheEdge() {
        assertEquals(12.8f, haNetworkChipTextSizePx(320f, 1f), 0.001f)
    }

    @Test fun aNonPositiveDensityFallsBackToOneToOneRatherThanCollapsing() {
        assertEquals(19.2f, haNetworkChipTextSizePx(2000f, 0f), 0.001f)
    }
}
