package io.panelassistant.android.assets

import io.panelassistant.android.testsupport.TestSources
import io.panelassistant.android.testsupport.Node
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the Entities page says about a template selector it refuses to read (issue #113): that allowing
 * the check adds nothing, and where the entities actually come from instead.
 */
class EntityTemplateAdvisoryUiContractTest {
    @Test fun `the template advisory row renders its note, route and nothing from the template`() {
        val fixture = TestSources.appFile("src/test/js/entity-template-advisory-test.mjs")
        // Source-text reason: executes the shipped entities.js in a node behaviour fixture.
        val asset = TestSources.appFile("src/main/assets/entities.js")
        val (code, output) = Node.run(fixture.absolutePath, asset.absolutePath)

        assertEquals(output, 0, code)
        assertTrue(output, output.contains("entity template advisory cases passed"))
    }
}
