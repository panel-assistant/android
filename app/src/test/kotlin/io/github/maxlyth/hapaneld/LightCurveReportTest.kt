package io.github.maxlyth.hapaneld

import io.github.maxlyth.hapaneld.hardware.TransferCurve
import org.junit.Assert.assertEquals
import org.junit.Test

class LightCurveReportTest {

    @Test fun keyBacklightCommandIsLinearByDefault() {
        for (level in 0..255) assertEquals("BTN $level", buttonBacklightCommand(level, TransferCurve.Identity))
        assertEquals("BTN 255", buttonBacklightCommand(999, TransferCurve.Identity))
    }

    @Test fun keyBacklightNeverCommandedReportsOffNotUnknown() {
        assertEquals("""{"state":"OFF"}""", buttonBacklightState(-1))
        assertEquals("""{"state":"OFF"}""", buttonBacklightState(0))
        assertEquals("""{"state":"ON","brightness":73}""", buttonBacklightState(73))
    }

    @Test fun keyBacklightCommandAppliesTheProfileCurve() {
        val curve = TransferCurve.Gamma(2.2, floor = 5 / 255.0)
        assertEquals("BTN 0", buttonBacklightCommand(0, curve))
        assertEquals("BTN 5", buttonBacklightCommand(1, curve))
        assertEquals("BTN ${curve.toHardware(128)}", buttonBacklightCommand(128, curve))
        assertEquals("BTN 255", buttonBacklightCommand(255, curve))
    }

    @Test fun driftOnAFrameworkCurvedPanelIsBackMappedInProportion() {
        // Setting 200 settled on node reading 80; the node then fell to 40 without a command.
        assertEquals(100, reportedBacklightDrift(curved = false, commanded = 200, effective = 40, baseline = 80))
        assertEquals(1, reportedBacklightDrift(curved = false, commanded = 200, effective = 0, baseline = 80))
    }

    @Test fun driftOnAProfileCurveIsPublishedAsRead() {
        // A profile curve's reads are already on the Home Assistant scale: a ratio would correct twice.
        assertEquals(25, reportedBacklightDrift(curved = true, commanded = 10, effective = 25, baseline = 1))
        assertEquals(1, reportedBacklightDrift(curved = true, commanded = 30, effective = 0, baseline = 25))
    }
}
