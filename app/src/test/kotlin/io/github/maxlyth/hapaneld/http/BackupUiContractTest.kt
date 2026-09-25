package io.github.maxlyth.hapaneld.http

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupUiContractTest {
    /**
     * A panel with no HA Companion installed must not mention it at all — no include-login checkbox, no
     * "needs the current helper" note, and nothing about it in the bundle description or restore warning.
     */
    @Test fun theBackupCardIsSilentAboutTheCompanionWhenItIsNotInstalled() {
        listOf(true, false).forEach { helper ->
            val copy = backupCompanionCopy(installed = false, helper = helper)
            assertEquals(BackupCompanionCopy(showLoginChoice = false, explainHelperRequirement = false), copy)
        }
    }

    @Test fun theCompanionLoginIsOfferedOnlyWithBothTheAppAndTheHelper() {
        val offered = backupCompanionCopy(installed = true, helper = true)
        assertTrue("the include-login choice belongs here", offered.showLoginChoice)
        assertTrue("the helper requirement must not also show", !offered.explainHelperRequirement)

        // Installed but the helper is stale: explain why, and do not promise it in the bundle or warning.
        val explained = backupCompanionCopy(installed = true, helper = false)
        assertTrue("must explain the helper requirement", explained.explainHelperRequirement)
        assertTrue("must not offer the checkbox", !explained.showLoginChoice)
    }
}
