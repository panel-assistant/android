package io.github.maxlyth.hapaneld.http

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the Entities page says when a dashboard strategy runs under an allowed entity-discovery check (issue #133 follow-up). */
class EntityStrategyAllowedUiContractTest {
    @Test fun `the entities page states the strategy consequence and the pin route`() {
        val working = File(requireNotNull(System.getProperty("user.dir")))
        val fixture = listOf(
            File(working, "app/src/test/js/entity-strategy-allowed-test.mjs"),
            File(working, "src/test/js/entity-strategy-allowed-test.mjs"),
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
        assertTrue(output, output.contains("entity strategy allowed cases passed"))
    }
}
