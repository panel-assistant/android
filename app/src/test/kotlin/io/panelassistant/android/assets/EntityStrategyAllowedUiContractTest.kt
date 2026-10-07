package io.panelassistant.android.assets

import io.panelassistant.android.testsupport.TestSources
import io.panelassistant.android.testsupport.Node
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the Entities page says when a dashboard strategy runs under an allowed entity-discovery check (issue #133 follow-up). */
class EntityStrategyAllowedUiContractTest {
    @Test fun `the entities page states the strategy consequence and the pin route`() {
        val fixture = TestSources.appFile("src/test/js/entity-strategy-allowed-test.mjs")
        // Source-text reason: executes the shipped entities.js in a node behaviour fixture.
        val asset = TestSources.appFile("src/main/assets/entities.js")
        val (code, output) = Node.run(fixture.absolutePath, asset.absolutePath)

        assertEquals(output, 0, code)
        assertTrue(output, output.contains("entity strategy allowed cases passed"))
    }
}
