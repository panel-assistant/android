package io.panelassistant.android.http

import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

/**
 * Every capability row is catalogue text: each branch must render in every shipped locale with no
 * unknown key and no placeholder left unfilled.
 */
class CapabilityRowRenderingTest {
    private val placeholder = Regex("\\{[a-zA-Z_]+\\}")

    @Test fun `every row renders in every shipped locale with its placeholders filled`() {
        val locales = File("src/main/assets/i18n").list()!!.map { it.removeSuffix(".json") }
        val cases = capabilityRowCases()
        for (locale in locales) {
            val strings = testCatalogue.strings(locale)
            for ((label, cap) in cases) {
                val row = capRowsHtml(listOf(cap), strings)
                assertFalse("$locale $label left a placeholder: $row", placeholder.containsMatchIn(row))
            }
        }
    }
}
