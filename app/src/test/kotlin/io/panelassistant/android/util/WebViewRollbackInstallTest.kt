package io.panelassistant.android.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WebViewRollbackInstallTest {
    @get:Rule val files = TemporaryFolder()

    @Test fun selectedRendererMustStillBeAdmittedAtInstallCommit() {
        assertTrue(AppInstaller.mayCommitPinnedInstall(null))
        assertTrue(AppInstaller.mayCommitPinnedInstall { true })
        assertFalse(AppInstaller.mayCommitPinnedInstall { false })
        assertFalse(AppInstaller.mayCommitPinnedInstall { error("renderer selection unavailable") })
    }

    @Test fun aRetiredWebViewRollbackLeavesNothingBehindAndASecondSweepIsHarmless() {
        val dir = files.newFolder()
        val retired = listOf(
            "webview-previous.apk", "webview-previous.json", "webview-rollback-attempted.json", "webview-rollback-diagnostic.txt",
        ).map { java.io.File(dir, it).apply { writeText("left by an older build") } }
        val unrelated = java.io.File(dir, "config-revisions").apply { writeText("kept") }

        AppInstaller.deleteRetiredWebViewRollback(dir)
        AppInstaller.deleteRetiredWebViewRollback(dir)

        assertEquals(emptyList<java.io.File>(), retired.filter { it.exists() })
        assertTrue(unrelated.exists())
    }
}
