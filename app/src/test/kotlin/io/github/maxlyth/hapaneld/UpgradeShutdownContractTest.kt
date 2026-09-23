package io.github.maxlyth.hapaneld

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpgradeShutdownContractTest {
    @Test fun orderlyShutdownDrainsProducersThenFreezesAndProvesDatabaseAfterFinalFlush() {
        val source = source("PaneldService.kt")
        val destroy = source.substring(source.indexOf("override fun onDestroy()"), source.indexOf("private fun finishTeardownAsync("))
        val finalizer = source.substring(source.indexOf("private fun finishTeardownAsync("), source.indexOf("private fun runFinalizerStep("))

        assertFalse(destroy.contains("AppState.freezeForServiceShutdown(this)"))
        assertTrue(finalizer.indexOf("entityLearning.close()") < finalizer.indexOf("AppState.freezeForServiceShutdown(this)"))
        assertTrue(finalizer.indexOf("AppState.freezeForServiceShutdown(this)") < finalizer.lastIndexOf("AppState.flush(this, stateFlushMs)"))
        assertTrue(finalizer.lastIndexOf("AppState.flush(this, stateFlushMs)") < finalizer.indexOf("AppState.proveCleanServiceShutdown"))
        assertTrue(finalizer.indexOf("runBoundedShutdownProof(proofMs)") < finalizer.indexOf("AppState.proveCleanServiceShutdown"))
        assertTrue(finalizer.indexOf("AppState.proveCleanServiceShutdown") < finalizer.indexOf("completed = true"))
        assertTrue(source.contains("UpgradeShutdownCoordinator.holdAfterCleanShutdown"))
        val terminal = source.substring(source.indexOf("val heldForUpgrade"), source.indexOf("private fun scheduleKioskReassert()"))
        assertTrue(terminal.contains("if (!heldForUpgrade)"))
        assertTrue(terminal.contains("restartLease.completeTeardown()"))
        assertFalse(terminal.substring(terminal.indexOf("} else {", terminal.indexOf("if (!heldForUpgrade)"))).contains("restartLease.completeTeardown()"))
    }

    @Test fun receiverWireSurfaceIsDumpProtectedAndHasNoSecondQuiescencePath() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val receiver = source("upgrade/UpgradeControlReceiver.kt")

        assertTrue(manifest.contains("android:name=\".UpgradeControlReceiver\""))
        assertTrue(manifest.contains("android:permission=\"android.permission.DUMP\""))
        assertTrue(receiver.contains("context.stopService(Intent(context, PaneldService::class.java))"))
        assertFalse(receiver.contains("restartAfterInternalBoundary"))
        assertTrue(receiver.contains("HAPANELD_UPGRADE_ERROR_V1"))
        assertFalse(receiver.contains("wal_checkpoint"))
        assertFalse(receiver.contains("AppState.flush"))
    }

    @Test fun checkpointAndDigestAreOneOrderedProofWithoutSQLiteOpenHelperUse() {
        val source = source("persistence/AppState.kt")
        val proof = source.substring(source.indexOf("private fun proveStableDatabase("), source.indexOf("private fun scalarLong("))

        val digestCall = proof.indexOf("sha256WithinBudget(databaseFile, budget)")
        assertTrue(proof.indexOf("PRAGMA wal_checkpoint(TRUNCATE)") < digestCall)
        assertTrue(proof.indexOf("closeDatabase()") < digestCall)
        assertTrue(digestCall < proof.indexOf("walBytesAfterDigest"))
        assertTrue(proof.contains("MessageDigest.getInstance"))
        assertTrue(source.contains("ShutdownProofBudget(timeoutMs)"))
        assertTrue(proof.contains("budget.hasTime()"))
        assertTrue(proof.contains("sha256WithinBudget(databaseFile, budget)"))
        assertTrue(proof.contains("busy = busy"))
        assertFalse(source.contains("helper.use"))
    }

    @Test fun renewalDispatchAndWatchdogUseTheSameMonotonicDeadline() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val receiver = source("upgrade/UpgradeControlReceiver.kt")
        val coordinator = source("upgrade/UpgradeShutdownCoordinator.kt")
        assertTrue(manifest.contains("io.github.maxlyth.hapaneld.action.RENEW_UPGRADE"))
        assertTrue(receiver.contains("RENEW_UPGRADE_ACTION -> renew(context, intent)"))
        assertTrue(receiver.contains("if (!UpgradeShutdownCoordinator.renew(context, nonce)) return failResult(\"renewal_failed\")"))
        assertTrue(receiver.contains("resultData = formatUpgradeRenewed(nonce)"))
        assertTrue(coordinator.contains("gate.arm(nonce, completion, SystemClock.elapsedRealtime() + UPGRADE_HOLD_TIMEOUT_MS)"))
        assertTrue(coordinator.contains("gate.renew(nonce, SystemClock.elapsedRealtime(), UPGRADE_HOLD_TIMEOUT_MS)"))
        val renewal = coordinator.substring(coordinator.indexOf("fun renew(context:"), coordinator.indexOf("private fun scheduleWatchdog("))
        assertTrue(renewal.contains("scheduleWatchdog(context, nonce)"))
        assertTrue(renewal.indexOf("gate.renew(") < renewal.indexOf("scheduleWatchdog(context, nonce)"))
        assertTrue(coordinator.contains("cancelAndResume(appContext, nonce, \"watchdog_expired\", SystemClock.elapsedRealtime())"))
        assertTrue(coordinator.contains("val cancelled = gate.cancel(nonce, reason, expiredAtMillis)"))
    }

    private fun source(path: String): String =
        File("src/main/kotlin/io/github/maxlyth/hapaneld/$path").readText()
}
