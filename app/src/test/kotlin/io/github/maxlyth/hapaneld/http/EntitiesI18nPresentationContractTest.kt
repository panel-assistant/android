package io.github.maxlyth.hapaneld.http

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EntitiesI18nPresentationContractTest {
    private fun source(vararg candidates: String): String = candidates.map(::File).first(File::isFile).readText()

    private val server = source(
        "src/main/kotlin/io/github/maxlyth/hapaneld/http/PaneldServer.kt",
        "app/src/main/kotlin/io/github/maxlyth/hapaneld/http/PaneldServer.kt",
    )
    private val browser = source("src/main/assets/entities.js", "app/src/main/assets/entities.js")

    @Test fun `entities route accounts for localized chrome reused labels and English evidence`() {
        val route = server.substringAfter("get(\"/entities\") {").substringBefore("get(\"/api\") {")

        assertTrue(route.contains("strings.languages(setOf(\"shell.\", \"configure.hardened.\", \"entities.\"))"))
        assertTrue(route.contains("strings.resolve(\"settings.dashboard_entity_learning.label\").language"))
        assertTrue(route.contains("strings.resolve(\"configure.group.dashboard\").language"))
        assertTrue(route.contains("AppLocale.ENGLISH"))
        assertTrue(route.contains("HttpHeaders.ContentLanguage"))
        assertTrue(route.contains("entitiesBody(strings)"))
    }

    @Test fun `browser projection exposes additive per-record language provenance safely`() {
        val payload = server.substringAfter("private fun browserI18nPayload").substringBefore("private fun navBar")

        assertTrue(payload.contains("localized.language"))
        assertTrue(payload.contains("\\\"languages\\\":{${'$'}languages}"))
        listOf("<", ">", "&", "\\u2028", "\\u2029").forEach { escaped ->
            assertTrue("payload must keep escaping $escaped", payload.contains("replace(\"$escaped\""))
        }
    }

    @Test fun `all reviewed Entities keys are bound without touching a catalogue`() {
        val literals = Regex("[\\\"'](entities\\.[a-z0-9_.-]+)[\\\"']")
            .findAll(server + browser.replace("\\u0073", "s"))
            .map { it.groupValues[1] }
            .toSet()
        val computedPrefixes = setOf(
            "entities.bulk.", "entities.bulk.exclude_all_confirm", "entities.bulk.exclude_selected_confirm",
            "entities.bulk.pin_all_confirm", "entities.bulk.pin_selected_confirm", "entities.issue.potential",
            "entities.issues.summary.allowed", "entities.issues.summary.blocking",
            "entities.issues.summary.blocking_allowed", "entities.issues.summary.notes", "entities.scan.blocked",
            "entities.search.section.", "entities.search.section.current", "entities.search.section.review",
            "entities.search.section.suggested", "entities.status.blocking", "entities.status.catalogued",
            "entities.status.ignored", "entities.status.stream", "entities.status.suggested",
            "entities.status.unresolved", "entities.table.", "entities.table.current",
            "entities.table.review", "entities.table.suggested",
        )

        assertEquals("the reviewed presentation contract is exactly 229 finite keys", 229, (literals - computedPrefixes).size)
        assertTrue(literals.containsAll(setOf(
            "entities.status.state.learning", "entities.status.state.observing",
            "entities.issue.view.untitled", "entities.issue.card.kiosk_mode_configuration",
            "entities.bulk.pin_selected_confirm.one", "entities.bulk.pin_selected_confirm.other",
        )))
    }

    @Test fun `typed issue and synthesized title vocabularies are closed and never inferred from prose`() {
        assertTrue(browser.contains("'kiosk_mode-limited-support':{summary:'entities.issue.kio\\u0073k-mode-limited-support.summary'"))
        assertTrue(browser.contains("'kiosk_mode-dynamic-javascript':{summary:'entities.issue.kio\\u0073k-mode-dynamic-javascript.summary'"))
        assertTrue(browser.contains("issue.view_title_index"))
        assertTrue(browser.contains("issue.card_title_hacs_kiosk===true"))
        assertFalse(browser.contains("view_title==='View"))
        assertFalse(browser.contains("card_title==='HACS Kiosk Mode configuration'"))
        assertTrue(browser.contains("Object.prototype.hasOwnProperty.call(issue,'presentation_params')"))
    }

    @Test fun `raw rule evidence is limited to the five reviewed mixed families`() {
        val declaration = browser.substringAfter("var mixedRuleCodes=").substringBefore(";")
        val codes = Regex("\\\"([^\\\"]+)\\\":true").findAll(declaration).map { it.groupValues[1] }.toSet()
        assertEquals(
            setOf(
                "selector-broad", "area-broad", "map-dynamic-enumeration",
                "button-card-dynamic-features", "selector-total-budget",
            ),
            codes,
        )
        assertTrue(browser.contains("ruleEvidence=summary.localized&&mixedRuleCodes[issueCode(issue)]?rawRule:''"))
        assertTrue(browser.contains("appendLabelled(row,t('entities.issue.rule','Rule'),ruleEvidence,'code','en')"))
    }
}
