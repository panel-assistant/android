package io.panelassistant.android.assets

import io.panelassistant.android.testsupport.TestSources
import io.panelassistant.android.testsupport.Node
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Entities-page catalogue search feedback (issue #114) and its one-shot reveal, run against the shipped script. */
class EntitySearchFeedbackUiContractTest {
    @Test fun `search feedback and the never-scroll routes behave as specified`() {
        val fixture = TestSources.appFile("src/test/js/entity-search-feedback-test.mjs")
        // Source-text reason: executes the shipped entities.js in a node behaviour fixture.
        val asset = TestSources.appFile("src/main/assets/entities.js")
        val (code, output) = Node.run(fixture.absolutePath, asset.absolutePath)

        assertEquals(output, 0, code)
        assertTrue(output, output.contains("entity search feedback cases passed"))
    }
}
