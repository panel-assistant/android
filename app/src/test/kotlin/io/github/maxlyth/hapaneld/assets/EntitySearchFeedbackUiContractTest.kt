package io.github.maxlyth.hapaneld.assets

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Entities-page catalogue search feedback (issue #114) and its one-shot reveal, run against the shipped script. */
class EntitySearchFeedbackUiContractTest {
    @Test fun `search feedback and the never-scroll routes behave as specified`() {
        val working = File(requireNotNull(System.getProperty("user.dir")))
        val fixture = listOf(
            File(working, "app/src/test/js/entity-search-feedback-test.mjs"),
            File(working, "src/test/js/entity-search-feedback-test.mjs"),
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
        assertTrue(output, output.contains("entity search feedback cases passed"))
    }
}
