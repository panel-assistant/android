package io.github.maxlyth.hapaneld.util

import android.content.Context
import android.content.ContextWrapper
import io.github.maxlyth.hapaneld.device.DeviceProfile
import io.github.maxlyth.hapaneld.device.WebViewSpec
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.lang.reflect.Proxy

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

    private fun profile(pin: String): DeviceProfile {
        val recommendation = WebViewSpec("https://example.invalid/webview.apk", pin, signer, "c".repeat(64))
        return Proxy.newProxyInstance(DeviceProfile::class.java.classLoader, arrayOf(DeviceProfile::class.java)) {
            _, method, _ ->
            if (method.name == "getRecommendedWebView") recommendation else error("unexpected profile read: ${method.name}")
        } as DeviceProfile
    }

    private fun preparedReceipt(ctx: Context, pin: String): String? {
        assertTrue(WebViewInstaller.writeRollbackRecord(ctx, WebViewInstaller.PendingRollback(
            pin, "c".repeat(64), sha, signer, 0, installMayHaveStarted = false,
        )))
        WebViewInstaller.previousApk(ctx).writeText("previous signed APK")
        return null
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

    @Test fun interruptedDownloadReleasesItsReceiptWithoutSpendingThePin() {
        val ctx = context()
        val pin = "150.0.7871.63"
        val pending = WebViewInstaller.PendingRollback(
            pin, "c".repeat(64), sha, signer, 0,
            originProcess = "process-that-died", installMayHaveStarted = false,
        )
        assertTrue(WebViewInstaller.writeRollbackRecord(ctx, pending))
        WebViewInstaller.previousApk(ctx).writeText("previous signed APK")

        assertTrue(WebViewInstaller.discardUnsubmittedRollback(ctx))
        assertNull(WebViewInstaller.pendingRollback(ctx))
        assertFalse(WebViewInstaller.previousApk(ctx).exists())
        assertFalse(WebViewInstaller.alreadyRolledBackPin(ctx, pin))
    }

    @Test fun admittedInstallKeepsItsReceiptAndBackupAcrossProcessDeath() {
        val ctx = context()
        val pin = "150.0.7871.63"
        val pending = WebViewInstaller.PendingRollback(
            pin, "c".repeat(64), sha, signer, 0,
            originProcess = "process-that-died", installMayHaveStarted = false,
        )
        assertTrue(WebViewInstaller.writeRollbackRecord(ctx, pending))
        WebViewInstaller.previousApk(ctx).writeText("previous signed APK")

        assertFalse(WebViewInstaller.markInstallMayHaveStarted(ctx, "different pin"))
        assertTrue(WebViewInstaller.markInstallMayHaveStarted(ctx, pin))
        assertFalse(WebViewInstaller.discardUnsubmittedRollback(ctx))
        assertTrue(WebViewInstaller.previousApk(ctx).exists())
        assertTrue(WebViewInstaller.pendingRollback(ctx)!!.installMayHaveStarted)
        assertEquals("another WebView swap is awaiting health verification", WebViewInstaller.prepareRollback(ctx, pin, "c".repeat(64)))
    }

    @Test fun aMissingBackupCannotAdmitAnInstall() {
        val ctx = context()
        val pin = "150.0.7871.63"
        val pending = WebViewInstaller.PendingRollback(
            pin, "c".repeat(64), sha, signer, 0, installMayHaveStarted = false,
        )
        assertTrue(WebViewInstaller.writeRollbackRecord(ctx, pending))

        assertFalse(WebViewInstaller.markInstallMayHaveStarted(ctx, pin))
        assertFalse(WebViewInstaller.pendingRollback(ctx)!!.installMayHaveStarted)
    }

    @Test fun scheduledDownloadFailureClearsThePreInstallReceipt() = runBlocking {
        val ctx = context()
        val pin = "150.0.7871.63"
        val result = WebViewInstaller.heal(
            ctx, profile(pin), "147.0.7727.56", autoUpdate = true, stillBuiltin = { true },
            prepareAutoRollback = { _, _, _ -> preparedReceipt(ctx, pin) },
            installPinned = { _, _, _, _ ->
                assertFalse(WebViewInstaller.pendingRollback(ctx)!!.installMayHaveStarted)
                InstallOutcome.Retryable("download failed")
            },
        )

        assertEquals(WebViewInstaller.HealResult.Failed("download failed", terminal = false), result)
        assertNull(WebViewInstaller.pendingRollback(ctx))
        assertFalse(WebViewInstaller.previousApk(ctx).exists())
        assertFalse(WebViewInstaller.alreadyRolledBackPin(ctx, pin))
    }

    @Test fun scheduledInstallAdmissionDurablyKeepsAnUncertainReceipt() = runBlocking {
        val ctx = context()
        val pin = "150.0.7871.63"
        val result = WebViewInstaller.heal(
            ctx, profile(pin), "147.0.7727.56", autoUpdate = true, stillBuiltin = { true },
            prepareAutoRollback = { _, _, _ -> preparedReceipt(ctx, pin) },
            installPinned = { _, _, _, gate ->
                assertTrue(AppInstaller.mayCommitPinnedInstall(gate))
                InstallOutcome.Retryable("pm reply lost", mayHaveCommitted = true)
            },
        )

        assertEquals(WebViewInstaller.HealResult.Uncertain("pm reply lost"), result)
        assertTrue(WebViewInstaller.pendingRollback(ctx)!!.installMayHaveStarted)
        assertFalse(WebViewInstaller.discardUnsubmittedRollback(ctx))
        assertTrue(WebViewInstaller.previousApk(ctx).exists())
    }

    @Test fun interruptedDownloadExceptionAlsoReleasesItsReceipt() = runBlocking {
        val ctx = context()
        val pin = "150.0.7871.63"
        try {
            WebViewInstaller.heal(
                ctx, profile(pin), "147.0.7727.56", autoUpdate = true, stillBuiltin = { true },
                prepareAutoRollback = { _, _, _ -> preparedReceipt(ctx, pin) },
                installPinned = { _, _, _, _ -> throw IllegalStateException("download interrupted") },
            )
            fail("download exception must escape")
        } catch (expected: IllegalStateException) {
            assertEquals("download interrupted", expected.message)
        }
        assertNull(WebViewInstaller.pendingRollback(ctx))
        assertFalse(WebViewInstaller.previousApk(ctx).exists())
    }
}
