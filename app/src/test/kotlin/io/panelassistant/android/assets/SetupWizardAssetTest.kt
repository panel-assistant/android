package io.panelassistant.android.assets

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.json.JSONObject
import org.junit.Test

/** The guided-setup wizard's user-visible copy, checked against the shipped English catalogue. */
class SetupWizardAssetTest {
    // Source-text reason: loads the shipped English translation catalogue as input data.
    private val english = JSONObject(listOf(
        File("src/main/assets/i18n/en.json"),
        File("app/src/main/assets/i18n/en.json"),
    ).first { it.isFile }.readText()).getJSONObject("strings")

    private fun english(key: String): String = english.getJSONObject(key).getString("text")

    @Test fun theSignInCopyIsHonestAboutAccountIdentityCatalogueContract() {
        // The inherit-your-session case is softened to "may" because it only happens when this browser
        // already holds a Home Assistant session. Must stay jargon-free.
        val visibleCopy = listOf(
            "setup.sign_in.browser.explanation",
            "setup.sign_in.browser.account_note",
            "setup.sign_in.panel.explanation",
        ).joinToString(" ", transform = ::english)
        assertTrue(visibleCopy.contains("the panel may connect as you"))
        assertFalse("must not overstate the inherited-session case", visibleCopy.contains("connect as YOU"))
        listOf("OAuth", "session", "cookie", "private window", "incognito").forEach {
            assertFalse("\"$it\" is jargon for this audience", visibleCopy.contains(it, ignoreCase = it != "OAuth"))
        }
    }
}
