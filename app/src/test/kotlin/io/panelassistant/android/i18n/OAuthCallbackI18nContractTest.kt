package io.panelassistant.android.i18n

import io.panelassistant.android.http.HaOAuthCallbackCopy
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/** Contracts the small HTML callback surface reached from localized Setup and Configure OAuth. */
class OAuthCallbackI18nContractTest {
    private val project = File(".")
    private val assets = File(project, "src/main/assets")
    // Source-text reason: loads the shipped i18n catalogues as input data.
    private val source = SourceCatalogue.parse(File(assets, "i18n/en.json").readText())

    @Test fun `Fail-closed English callback copy stays identical to the source catalogue`() {
        val copy = HaOAuthCallbackCopy.ENGLISH
        val fallback = mapOf(
            "oauth.callback.success_heading" to copy.successHeading,
            "oauth.callback.failure_heading" to copy.failureHeading,
            "oauth.callback.action.continue" to copy.continueAction,
            "oauth.callback.action.back_to_configure" to copy.backToConfigureAction,
            "oauth.callback.action.back_to_setup" to copy.backToSetupAction,
            "oauth.callback.cancelled" to copy.cancelled,
            "oauth.callback.invalid_code" to copy.invalidCode,
            "oauth.callback.rejected" to copy.rejected,
            "oauth.callback.transient" to copy.transient,
            "oauth.callback.stale" to copy.stale,
            "oauth.callback.commit_failed" to copy.commitFailed,
            "oauth.callback.configured" to copy.configured,
            "oauth.callback.reload_may_be_needed" to copy.reloadMayBeNeeded,
            "oauth.callback.ambient_warning" to copy.ambientWarning,
        )
        val authoritative = source.strings
            .filterKeys { it.startsWith("oauth.callback.") }
            .mapValues { it.value.text }

        assertEquals(authoritative, fallback)
    }

}
