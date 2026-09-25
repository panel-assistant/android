package io.github.maxlyth.hapaneld.http

import io.github.maxlyth.hapaneld.util.InstallPresentation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InstallServerPresentationContractTest {
    @Test fun statusWarningOverlayPreservesOrderNullsAndEmptyCardinality() {
        val warnings = listOf("first English warning", "arbitrary diagnostic", "third English warning")
        val presentations = listOf(
            InstallPresentation("status-no-renderer"),
            null,
            InstallPresentation("status-power-caution"),
        )

        assertEquals(
            """[{"code":"status-no-renderer","params":{}},null,{"code":"status-power-caution","params":{}}]""",
            installWarningPresentationsJson(warnings, presentations),
        )
        assertEquals("[]", installWarningPresentationsJson(emptyList(), emptyList()))
    }

    @Test fun statusWarningOverlayFailsClosedOnMismatchOrImpossibleCardinality() {
        assertNull(
            installWarningPresentationsJson(
                listOf("one", "two"),
                listOf(InstallPresentation("status-no-renderer")),
            ),
        )
        assertNull(
            installWarningPresentationsJson(
                List(12) { "warning-$it" },
                List(12) { InstallPresentation("status-no-renderer") },
            ),
        )
    }
}
