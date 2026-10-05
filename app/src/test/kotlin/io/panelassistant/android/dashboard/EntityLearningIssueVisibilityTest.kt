package io.panelassistant.android.dashboard

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class EntityLearningIssueVisibilityTest {
    @Test fun automaticRuntimePromotionOnlyShowsFindingsThatPreventedAutomaticAddition() {
        val stored = JSONArray()
            .put(JSONObject().put("type", "runtime_coverage").put("blocking", false).put("ignored", false))
            .put(JSONObject().put("type", "compatibility_gap").put("blocking", false).put("ignored", false))
            .put(JSONObject().put("type", "unbounded_selector").put("blocking", true).put("ignored", false))
            .put(JSONObject().put("type", "broad_selector").put("blocking", false).put("would_block", true).put("ignored", true))
            .toString()

        val automatic = JSONObject(visibleDashboardIssuesJson(stored, showAdvisories = false))
        assertEquals(listOf("unbounded_selector", "broad_selector"), automatic.types())
        assertEquals(2, automatic.getInt("dashboard_issue_count"))
        assertEquals(1, automatic.getInt("blocking_issue_count"))
        assertEquals(1, automatic.getInt("ignored_issue_count"))

        val manual = JSONObject(visibleDashboardIssuesJson(stored, showAdvisories = true))
        assertEquals(
            listOf("unbounded_selector", "broad_selector", "runtime_coverage", "compatibility_gap"),
            manual.types(),
        )
        assertEquals(4, manual.getInt("dashboard_issue_count"))
        assertEquals(1, manual.getInt("blocking_issue_count"))
        assertEquals(1, manual.getInt("ignored_issue_count"))
    }

    @Test fun anAllowedStrategyCheckIsReportedOnlyUnderAFilteredStream() {
        fun issue(code: String, ignored: Boolean) = JSONObject().put("type", "unbounded_selector")
            .put("presentation_code", code).put("would_block", true).put("blocking", !ignored).put("ignored", ignored)
        val allowedStrategy = JSONArray().put(issue("selector-broad", ignored = true)).put(issue("dashboard-strategy", ignored = true))

        assertEquals(true, strategySelectorAllowed("filtered", allowedStrategy))
        // An unfiltered or held stream drops nothing, so there is nothing to warn about.
        assertEquals(false, strategySelectorAllowed("unfiltered", allowedStrategy))
        assertEquals(false, strategySelectorAllowed("held", allowedStrategy))
        // The same strategy check still awaiting a choice has not dropped anything yet.
        assertEquals(false, strategySelectorAllowed("filtered", JSONArray().put(issue("dashboard-strategy", ignored = false))))
        // Any other allowed check is not a strategy.
        assertEquals(false, strategySelectorAllowed("filtered", JSONArray().put(issue("selector-unbounded-or-dynamic", ignored = true))))
        assertEquals(false, strategySelectorAllowed("filtered", JSONArray()))
    }

    private fun JSONObject.types(): List<String> = getJSONArray("items").let { items ->
        List(items.length()) { index -> items.getJSONObject(index).getString("type") }
    }
}
