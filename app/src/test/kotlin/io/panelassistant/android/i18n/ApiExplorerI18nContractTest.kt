package io.panelassistant.android.i18n

import io.panelassistant.android.testsupport.TestSources
import org.junit.Assert.assertEquals
import org.junit.Test

class ApiExplorerI18nContractTest {
    // Source-text reason: the API catalogue keys consumed by the shipped api.js are a translation catalogue contract.
    private val script = TestSources.asset("api.js").readText()
    // Source-text reason: loads the shipped English catalogue as input data.
    private val english = SourceCatalogue.parse(TestSources.asset("i18n/en.json").readText())

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
