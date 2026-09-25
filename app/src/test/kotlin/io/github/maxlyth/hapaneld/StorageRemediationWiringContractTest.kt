package io.github.maxlyth.hapaneld

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The service owns remediation's lifecycle: it runs only after a completed daily observation, inside
 * the same serialized queue as every storage read, and its database steps reach the store only
 * through the ladder's operations. Behaviour of the ladder itself is pinned by StorageRemediationTest.
 */
class StorageRemediationWiringContractTest {
    private val service by lazy {
        listOf(
            File("src/main/kotlin/io/github/maxlyth/hapaneld/PaneldService.kt"),
            File("app/src/main/kotlin/io/github/maxlyth/hapaneld/PaneldService.kt"),
        ).first(File::isFile).readText()
    }

    private fun between(start: String, end: String): String =
        service.substring(service.indexOf(start).also { check(it >= 0) { start } }, service.indexOf(end, service.indexOf(start)))

    @Test fun remediationRunsOnlyAfterACompletedDailyObservation() {
        assertEquals("one caller", 2, Regex("runStorageMaintenance\\(").findAll(service).count())
        val daily = between("name = \"storage-health\"", "private suspend fun runQueuedStorageHealthObservation")
        val complete = daily.substring(daily.indexOf("StorageHealthObservationAttempt.Complete ->"))
        assertTrue("maintenance follows a completed observation", complete.trimStart().let {
            it.indexOf("runStorageMaintenance()") in 0 until it.indexOf("StorageHealthObservationAttempt.Stopped")
        })
    }

    @Test fun remediationIsSerializedWithEveryStorageReadAndPublishesItsMeasuredResult() {
        val maintenance = between("private suspend fun runStorageMaintenance()", "private fun storageRemediationOperations(")
        assertTrue("serialized through the observation queue", "storageHealthObservationQueue.run" in maintenance)
        assertTrue("stops with the service", "teardownBoundary.isStopping" in maintenance)
        assertTrue("the plan decides between retention and remediation", "storageMaintenancePlan(snapshot)" in maintenance)
        assertTrue("the result reaches every surface", "StorageHealthRuntime.recordRemediation(summary)" in maintenance)
    }

    @Test fun theLaddersOperationsAreTheOnlyRouteToTheDatabaseSteps() {
        val operations = between("private fun storageRemediationOperations(", "/** One bounded observation attempt")
        listOf("vacuumDatabase()", "truncateDatabaseWal()", "writeVerifiedConfigurationBackup()").forEach { step ->
            assertEquals("$step is reached only from the ladder", 1, Regex(Regex.escape(step)).findAll(service).count())
            assertTrue(step in operations)
        }
        assertTrue("the post-run verdict comes from a fresh observation on the held signal",
            "runStorageHealthObservation(signal)" in operations)
        assertTrue("a companion data backup or restore withholds lifecycle ownership",
            "companionDataOperationState.isPending()" in operations)
        assertTrue("only files older than this process can be orphans, against its start in the oldest clock epoch",
            "ProcessStartWallClock.orphanBoundary(" in operations && "SystemClock.elapsedRealtime()" in operations &&
                ") ?: return emptyList()" in operations)
        assertTrue("a Guard DB claim temporary needs the staging's own file proof",
            "appOwnedDisposableFileRules(cacheDir, filesDir, ::validGuardDbAppFile)" in operations)
        assertTrue("orphaned temporary directories are swept with the same proof",
            "appOwnedDisposableDirectoryRules(cacheDir).map(sweeper::sweep)" in operations)
        val app = listOf(
            File("src/main/kotlin/io/github/maxlyth/hapaneld/HaPaneldApp.kt"),
            File("app/src/main/kotlin/io/github/maxlyth/hapaneld/HaPaneldApp.kt"),
        ).first(File::isFile).readText()
        val attach = app.substring(app.indexOf("override fun attachBaseContext"), app.indexOf("override fun onCreate"))
        assertTrue("the start is captured at the earliest point the process runs, with its uptime anchor",
            "ProcessStartWallClock.capture(" in attach && "getStartElapsedRealtime()" in attach &&
                "startElapsed,\n" in attach)
        val create = app.substring(app.indexOf("override fun onCreate"))
        assertTrue("every clock step is observed from the start of onCreate, before any early return",
            create.indexOf("observeWallClockSteps()") in 0 until create.indexOf("return"))
        assertTrue("the observer records the step against uptime",
            "Intent.ACTION_TIME_CHANGED" in app &&
                "ProcessStartWallClock.observe(System.currentTimeMillis(), android.os.SystemClock.elapsedRealtime())" in app)
    }
}
