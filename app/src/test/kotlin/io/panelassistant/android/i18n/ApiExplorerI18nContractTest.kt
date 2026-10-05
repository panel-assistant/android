package io.panelassistant.android.i18n

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class ApiExplorerI18nContractTest {
    private fun source(vararg candidates: String): String = candidates.map(::File).first(File::isFile).readText()

    // Source-text reason: the API catalogue keys consumed by the shipped api.js are a translation catalogue contract.
    private val script = source("src/main/assets/api.js", "app/src/main/assets/api.js")
    // Source-text reason: loads the shipped English catalogue as input data.
    private val english = SourceCatalogue.parse(
        source("src/main/assets/i18n/en.json", "app/src/main/assets/i18n/en.json"),
    )

    @Test fun `external API browser asset parses as JavaScript`() {
        // Source-text reason: syntax check of a shipped asset.
        val file = listOf(File("src/main/assets/api.js"), File("app/src/main/assets/api.js")).first(File::isFile)
        val process = ProcessBuilder("node", "--check", file.absolutePath).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals("api.js is not valid JavaScript:\n$output", 0, process.waitFor())
    }

    @Test fun `English API catalogue is the exact frozen browser consumer set`() {
        val records = english.strings.keys.filterTo(sortedSetOf()) { it.startsWith("api.") }
        val consumers = Regex("[\\\"'](api\\.[a-z0-9._-]+)[\\\"']")
            .findAll(script)
            .mapTo(sortedSetOf()) { it.groupValues[1] }

        assertEquals(10, records.size)
        assertEquals(consumers, records)
        assertEquals(
            setOf(
                "api.action.send",
                "api.approval.conditional",
                "api.error.load_spec",
                "api.group.other",
                "api.header.back_to_panel",
                "api.intro.import",
                "api.intro.live",
                "api.intro.network",
                "api.request.body",
                "api.status.error",
            ),
            records,
        )
    }

}
