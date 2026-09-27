package io.github.maxlyth.hapaneld.util

import android.content.Context
import android.content.ContextWrapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WebViewRollbackReceiptTest {
    @get:Rule val files = TemporaryFolder()
    private val sha = "a".repeat(64)
    private val signer = "b".repeat(64)

    private fun context(): Context {
        val dir = files.newFolder()
        return object : ContextWrapper(null) {
            override fun getFilesDir() = dir
            override fun getCacheDir() = dir
        }
    }

    @Test fun healthyHandshakeConsumesTheSavedProviderWithoutMarkingThePinBad() {
        val ctx = context()
        val pending = WebViewInstaller.PendingRollback("150.0.7871.63", "c".repeat(64), sha, signer, 0)
        assertTrue(WebViewInstaller.writeRollbackRecord(ctx, pending))
        WebViewInstaller.previousApk(ctx).writeText("previous signed APK")
        WebViewInstaller.recordRollbackDiagnostic(ctx, "prior pin failed")

        val armed = WebViewInstaller.armRollback(ctx, 1_000L)
        assertEquals(1_000L + WebViewInstaller.SWAP_HEALTH_DEADLINE_MS, armed?.deadlineWallMs)
        assertEquals(armed, WebViewInstaller.acceptRollbackProbe(ctx))
        assertNull(WebViewInstaller.pendingRollback(ctx))
        assertFalse(WebViewInstaller.previousApk(ctx).exists())
        assertNull(WebViewInstaller.rollbackDiagnostic(ctx))
        assertFalse(WebViewInstaller.alreadyRolledBackPin(ctx, pending.pinVersion))
    }

    @Test fun blankOrCrashingTimeoutClaimsExactlyOneRestoreAndPersistsTheBadPin() {
        val ctx = context()
        val pending = WebViewInstaller.PendingRollback("150.0.7871.63", "c".repeat(64), sha, signer, 0)
        assertTrue(WebViewInstaller.writeRollbackRecord(ctx, pending))
        assertNotNull(WebViewInstaller.armRollback(ctx, 1_000L))

        assertEquals(pending.pinVersion, WebViewInstaller.claimRollback(ctx)?.pinVersion)
        assertNull(WebViewInstaller.claimRollback(ctx))
        assertNull(WebViewInstaller.pendingRollback(ctx))
        assertTrue(WebViewInstaller.alreadyRolledBackPin(ctx, pending.pinVersion))
        assertFalse(WebViewInstaller.alreadyRolledBackPin(ctx, "151.0.0.0"))
        assertTrue(WebViewInstaller.madeInThisProcess(WebViewInstaller.attemptedRollback(ctx)!!))
    }

    @Test fun returnedRestoreFailureDoesNotRetryButKeepsTheOnlySavedProvider() {
        val ctx = context()
        val pending = WebViewInstaller.PendingRollback("150.0.7871.63", "c".repeat(64), sha, signer, 0)
        assertTrue(WebViewInstaller.writeRollbackRecord(ctx, pending))
        WebViewInstaller.previousApk(ctx).writeText("previous signed APK")

        assertNotNull(WebViewInstaller.claimRollback(ctx))
        assertNull(WebViewInstaller.claimRollback(ctx))
        assertTrue(WebViewInstaller.previousApk(ctx).exists())
    }

    @Test fun failedAttemptRecordWriteCannotSpendTheOnlyRestore() {
        val ctx = context()
        val pending = WebViewInstaller.PendingRollback("150.0.7871.63", "c".repeat(64), sha, signer, 0)
        assertTrue(WebViewInstaller.writeRollbackRecord(ctx, pending))
        val obstruction = File(ctx.filesDir, "webview-rollback-attempted.json.tmp")
        assertTrue(obstruction.mkdir())

        assertNull(WebViewInstaller.claimRollback(ctx))
        assertEquals(pending, WebViewInstaller.pendingRollback(ctx))
        assertFalse(WebViewInstaller.alreadyRolledBackPin(ctx, pending.pinVersion))
        assertTrue(obstruction.delete())
        assertNotNull(WebViewInstaller.claimRollback(ctx))
        assertNull(WebViewInstaller.pendingRollback(ctx))
    }

    @Test fun consumingTheInstallerAliasDoesNotConsumeTheOnlySavedProvider() {
        val ctx = context()
        val saved = WebViewInstaller.previousApk(ctx).apply { writeText("previous signed APK") }
        val alias = WebViewInstaller.stageRestoreApk(ctx)
        assertNotNull(alias)
        assertEquals(saved.readText(), alias!!.readText())
        assertTrue(alias.delete())
        assertTrue("installer consumption must leave the saved provider available", saved.isFile)
        assertEquals("previous signed APK", saved.readText())
    }
}
