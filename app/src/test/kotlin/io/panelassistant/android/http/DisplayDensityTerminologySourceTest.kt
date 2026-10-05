package io.panelassistant.android.http

import io.panelassistant.android.control.fakeProfile
import kotlin.test.assertTrue
import org.junit.Test

class DisplayDensityTerminologySourceTest {
    @Test fun diagnosticsDistinguishAndroidBaseOverrideAndProfileRecommendation() {
        val line = DiagReader.displaySizingLine(
            DiagReader.DisplaySizingEvidence(
                androidBaseLogicalDpi = 160,
                currentLogicalDpi = 212,
                fontScale = 1.0f,
            ),
            fakeProfile(recommendedDensity = 240, recommendedFontScale = 1.1f),
        )

        assertTrue("android_base_logical_dpi=160" in line)
        assertTrue("current_logical_dpi=212" in line)
        assertTrue("override_dpi=212" in line)
        assertTrue("profile_recommended_dpi=240" in line)
        assertTrue("profile_recommended_font_scale=1.1" in line)
    }
}
