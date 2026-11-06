package io.panelassistant.android.control

import io.panelassistant.android.testsupport.TestSources
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoBrightnessHistoryPartitionContractTest {
    // Source-text reason: the remote handlers need the Android service to construct.
    private val dispatcher = source("PanelCommandDispatcher.kt")

    @Test fun `sensitivity reapplies policy without restarting the ambient source`() {
        val handler = dispatcher.substringAfter("override fun handleAutoBrightnessSensitivity")
            .substringBefore("override fun handleAutoBrightnessMaximum")

        assertTrue(handler.contains("autoBright.reapplyLatest()"))
        assertFalse(handler.contains("onAutoBrightnessConfigChanged()"))
    }

    private fun source(relative: String): String {
        return TestSources.appFile("src/main/kotlin/io/panelassistant/android/$relative").readText()
    }
}
