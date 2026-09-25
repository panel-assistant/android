package io.github.maxlyth.hapaneld.http

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the Entities page says about a template selector it refuses to read (issue #113): that allowing
 * the check adds nothing, and where the entities actually come from instead.
 */
class EntityTemplateAdvisoryUiContractTest {
    @Test fun `the template advisory row renders its note, route and nothing from the template`() {
        val working = File(requireNotNull(System.getProperty("user.dir")))
        val fixture = listOf(
            File(working, "app/src/test/js/entity-template-advisory-test.mjs"),
            File(working, "src/test/js/entity-template-advisory-test.mjs"),
        ).first(File::isFile)
        // Source-text reason: executes the shipped entities.js in a node behaviour fixture.
        val asset = listOf(
            File(working, "app/src/main/assets/entities.js"),
            File(working, "src/main/assets/entities.js"),
        ).first(File::isFile)
        val process = ProcessBuilder("node", fixture.absolutePath, asset.absolutePath)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()

        assertEquals(output, 0, process.waitFor())
        assertTrue(output, output.contains("entity template advisory cases passed"))
    }
}
