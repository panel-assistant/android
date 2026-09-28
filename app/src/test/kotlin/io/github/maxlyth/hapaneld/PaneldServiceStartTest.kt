package io.github.maxlyth.hapaneld

import org.junit.Assert.assertEquals
import org.junit.Test

class PaneldServiceStartTest {
    @Test fun visibleOreoActivityStartsWithoutArmingTheForegroundDeadline() {
        for (sdk in 26..27) {
            val requests = mutableListOf<String>()
            dispatchPanelServiceStart(
                sdk, fromVisibleActivity = true,
                startOrdinary = { requests += "ordinary" },
                startForeground = { requests += "foreground" },
            )
            assertEquals("API $sdk", listOf("ordinary"), requests)
        }
    }

    @Test fun backgroundAndNewerStartsKeepTheirRequiredRoute() {
        for ((sdk, visible, expected) in listOf(
            Triple(25, false, "ordinary"),
            Triple(27, false, "foreground"),
            Triple(28, true, "foreground"),
        )) {
            val requests = mutableListOf<String>()
            dispatchPanelServiceStart(sdk, visible, { requests += "ordinary" }, { requests += "foreground" })
            assertEquals("API $sdk, visible=$visible", listOf(expected), requests)
        }
    }
}
