package io.github.maxlyth.hapaneld.util

import android.content.ContextWrapper
import kotlinx.coroutines.runBlocking
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

    @Test fun restoreRefusesChangedApkBytesBeforeAnyPackageOrInstallAdmission() = runBlocking {
        val apk = files.newFile("previous.apk").apply { writeText("changed bytes") }
        val ctx = ContextWrapper(null)

        val result = AppInstaller.restorePinnedWebView(ctx, apk, "b".repeat(64), "a".repeat(64))
        assertTrue(result is InstallOutcome.Rejected)
        assertEquals("refused (APK checksum mismatch)", (result as InstallOutcome.Rejected).message)
        assertTrue(apk.exists())
    }
}
