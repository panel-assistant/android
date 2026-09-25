package io.github.maxlyth.hapaneld.dashboard

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A manual pin or exclusion is the operator's own work and is never withdrawn automatically. The review
 * table is the only place a stale pin becomes visible, so its note must promise that and say where to act.
 */
class PinnedEntityReviewContractTest {
    // Source-text reason: loads the shipped English i18n catalogue as input data.
    private val catalogue = JSONObject(File("src/main/assets/i18n/en.json").readText()).getJSONObject("strings")

    @Test fun `review note catalogueContract promises that nothing is removed automatically`() {
        val note = catalogue.getJSONObject("entities.table.review.note").getString("text")
        assertTrue("the note must say a manual override is never auto-removed: $note",
            note.contains("never removed automatically"))
        assertTrue("the note must name the pinned-but-unused case: $note", note.contains("pinned by hand"))
    }
}
