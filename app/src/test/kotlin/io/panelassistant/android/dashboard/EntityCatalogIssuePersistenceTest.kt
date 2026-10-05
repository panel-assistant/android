package io.panelassistant.android.dashboard

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EntityCatalogIssuePersistenceTest {
    @Test fun payloadBudgetIsExactlySeventyTwoKibibytes() {
        assertEquals(72 * 1024, EntityCatalogIssuePersistence.MAX_PAYLOAD_BYTES)
        assertEquals(32, EntityCatalogIssuePersistence.MAX_PRESENTATION_CODE_BYTES)
        assertEquals(10_000, EntityCatalogIssuePersistence.MAX_VIEW_TITLE_INDEX)
    }

    @Test fun synthesizedTitleMarkersAreTypeAndRangeBounded() {
        fun bounded(key: String, value: Any): JSONObject = JSONArray(
            EntityCatalogIssuePersistence.boundedJson(listOf(JSONObject().put(key, value))),
        ).getJSONObject(0)

        val minimum = bounded("view_title_index", 1)
        assertTrue(minimum.has("view_title_index"))
        assertEquals(1, minimum.getInt("view_title_index"))
        val maximum = bounded("view_title_index", 10_000L)
        assertTrue(maximum.has("view_title_index"))
        assertEquals(10_000, maximum.getInt("view_title_index"))
        listOf(0, 10_001, 1.5, "1", true).forEach { value ->
            assertFalse("unexpected view index $value", bounded("view_title_index", value).has("view_title_index"))
        }

        val kiosk = bounded("card_title_hacs_kiosk", true)
        assertTrue(kiosk.has("card_title_hacs_kiosk"))
        assertTrue(kiosk.getBoolean("card_title_hacs_kiosk"))
        listOf(false, 1, "true").forEach { value ->
            assertFalse("unexpected kiosk marker $value", bounded("card_title_hacs_kiosk", value).has("card_title_hacs_kiosk"))
        }
    }

    @Test fun presentationCodesArePrintableAsciiBoundedAndParametersAreNotPersisted() {
        fun boundedCode(value: String): JSONObject = JSONArray(
            EntityCatalogIssuePersistence.boundedJson(
                listOf(
                    JSONObject()
                        .put("blocking", true)
                        .put("presentation_code", value)
                        .put("presentation_params", JSONObject().put("future", "not-budgeted")),
                ),
            ),
        ).getJSONObject(0)

        val maximum = "x".repeat(EntityCatalogIssuePersistence.MAX_PRESENTATION_CODE_BYTES)
        val accepted = boundedCode(maximum)
        assertTrue(accepted.has("presentation_code"))
        assertEquals(maximum, accepted.getString("presentation_code"))
        assertFalse(accepted.has("presentation_params"))

        val unknownValid = boundedCode("future-code_2")
        assertTrue(unknownValid.has("presentation_code"))
        assertEquals("future-code_2", unknownValid.getString("presentation_code"))

        assertFalse(boundedCode("").has("presentation_code"))
        assertFalse(boundedCode("x".repeat(EntityCatalogIssuePersistence.MAX_PRESENTATION_CODE_BYTES + 1)).has("presentation_code"))
        assertFalse(boundedCode("selector-\u00e9").has("presentation_code"))
        assertFalse(boundedCode("selector\ncode").has("presentation_code"))
        assertFalse(boundedCode("Selector-code").has("presentation_code"))
        assertFalse(boundedCode("selector.code").has("presentation_code"))
        assertFalse(boundedCode("_selector-code").has("presentation_code"))
    }

    @Test fun ignoredIssuesRemainVisibleButStopBlocking() {
        val fingerprint = "0123456789abcdef"
        val raw = JSONArray().put(
            JSONObject().put("fingerprint", fingerprint).put("blocking", true).put("severity", "error"),
        )

        val effective = EntityCatalogIssuePersistence.applyIgnores(raw, setOf(fingerprint))
        val issue = JSONArray(effective).getJSONObject(0)

        assertTrue(issue.getBoolean("ignored"))
        assertTrue(issue.getBoolean("would_block"))
        assertFalse(issue.getBoolean("blocking"))
        assertEquals("warning", issue.getString("severity"))
        assertEquals(1 to 0, EntityCatalogIssuePersistence.counts(effective))
        assertEquals(1, EntityCatalogIssuePersistence.ignoredCount(effective))
    }
    @Test fun issueGroupsAndSourcesAreBounded() {
        val issues = List(EntityCatalogIssuePersistence.MAX_ISSUE_GROUPS + 10) { index ->
            JSONObject()
                .put("id", "issue-$index")
                .put("blocking", index % 2 == 0)
                .put("sources", JSONArray(List(EntityCatalogIssuePersistence.MAX_SOURCES_PER_GROUP + 5) { "cards[$it]" }))
        }

        val persisted = JSONArray(EntityCatalogIssuePersistence.boundedJson(issues))

        assertEquals(EntityCatalogIssuePersistence.MAX_ISSUE_GROUPS, persisted.length())
        assertEquals(
            EntityCatalogIssuePersistence.MAX_SOURCES_PER_GROUP,
            persisted.getJSONObject(0).getJSONArray("sources").length(),
        )
        assertEquals(64 to 37, EntityCatalogIssuePersistence.counts(persisted.toString()))
    }

    @Test fun advisoriesCannotEvictBlockingIssuesFromBoundedPayload() {
        val issues = List(EntityCatalogIssuePersistence.MAX_ISSUE_GROUPS) { index ->
            JSONObject()
                .put("fingerprint", "advisory-$index")
                .put("type", "limited_support")
                .put("blocking", false)
                .put("severity", "warning")
        } + JSONObject()
            .put("fingerprint", "late-blocker")
            .put("type", "unbounded_selector")
            .put("blocking", true)
            .put("severity", "error")

        val persisted = JSONArray(EntityCatalogIssuePersistence.boundedJson(issues))

        assertEquals(EntityCatalogIssuePersistence.MAX_ISSUE_GROUPS, persisted.length())
        assertTrue((0 until persisted.length()).any {
            persisted.getJSONObject(it).optString("fingerprint") == "late-blocker"
        })
        assertEquals(
            EntityCatalogIssuePersistence.MAX_ISSUE_GROUPS to 1,
            EntityCatalogIssuePersistence.counts(persisted.toString()),
        )
    }

    @Test fun nonblockingAdvisoryCannotBeIgnored() {
        val fingerprint = "advisory-fingerprint"
        val raw = JSONArray().put(
            JSONObject()
                .put("fingerprint", fingerprint)
                .put("type", "limited_support")
                .put("blocking", false)
                .put("would_block", false)
                .put("severity", "warning"),
        )

        val effective = EntityCatalogIssuePersistence.applyIgnores(raw, setOf(fingerprint))
        val issue = JSONArray(effective).getJSONObject(0)

        assertFalse(issue.getBoolean("ignored"))
        assertFalse(issue.getBoolean("blocking"))
        assertEquals("warning", issue.getString("severity"))
        assertEquals(1 to 0, EntityCatalogIssuePersistence.counts(effective))
        assertEquals(0, EntityCatalogIssuePersistence.ignoredCount(effective))
        assertFalse(EntityCatalogIssuePersistence.canIgnore(issue))

        val blocker = JSONObject().put("blocking", false).put("would_block", true)
        assertTrue(EntityCatalogIssuePersistence.canIgnore(blocker))
        blocker.put("ignorable", false)
        assertFalse(EntityCatalogIssuePersistence.canIgnore(blocker))
    }

    @Test fun payloadAndIndividualValuesAreBounded() {
        val issues = List(EntityCatalogIssuePersistence.MAX_ISSUE_GROUPS) { index ->
            JSONObject()
                .put("id", "issue-$index")
                .put("blocking", true)
                .put("message", "x".repeat(20_000))
                .put("details", JSONArray(List(16) { "y".repeat(20_000) }))
        }

        val json = EntityCatalogIssuePersistence.boundedJson(issues)
        val bytes = json.toByteArray(Charsets.UTF_8).size
        val persisted = JSONArray(json)

        assertTrue(bytes <= EntityCatalogIssuePersistence.MAX_PAYLOAD_BYTES)
        assertTrue(persisted.length() in 1..EntityCatalogIssuePersistence.MAX_ISSUE_GROUPS)
        assertTrue(persisted.getJSONObject(0).getString("message").length <= 500)
    }

    @Test fun malformedOrNonObjectStoredPayloadIsSafelyReduced() {
        assertEquals("[]", EntityCatalogIssuePersistence.boundExistingJson("not-json"))
        assertEquals(0 to 0, EntityCatalogIssuePersistence.counts("not-json"))

        val bounded = JSONArray(EntityCatalogIssuePersistence.boundExistingJson("[1,{\"blocking\":true},null]"))
        assertEquals(1, bounded.length())
        assertTrue(bounded.getJSONObject(0).getBoolean("blocking"))
    }
}
