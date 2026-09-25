package io.github.maxlyth.hapaneld.storage

import io.github.maxlyth.hapaneld.testsupport.TestSources
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class StorageHealthTest {
    @Test fun freeSpaceEntryAndClearBandsAreHysteretic() {
        val policy = StorageHealthPolicy(freeSpaceThresholds())

        assertEquals(StorageHealthSeverity.HEALTHY, policy.evaluate(StorageHealthSeverity.UNCHECKED, observation(180, 200)))
        assertEquals(StorageHealthSeverity.WARNING, policy.evaluate(StorageHealthSeverity.HEALTHY, observation(100, 200)))
        assertEquals(StorageHealthSeverity.WARNING, policy.evaluate(StorageHealthSeverity.WARNING, observation(149, 200)))
        assertEquals(StorageHealthSeverity.HEALTHY, policy.evaluate(StorageHealthSeverity.WARNING, observation(150, 200)))
        assertEquals(StorageHealthSeverity.CRITICAL, policy.evaluate(StorageHealthSeverity.HEALTHY, observation(25, 200)))
        assertEquals(StorageHealthSeverity.CRITICAL, policy.evaluate(StorageHealthSeverity.CRITICAL, observation(49, 200)))
        assertEquals(StorageHealthSeverity.WARNING, policy.evaluate(StorageHealthSeverity.CRITICAL, observation(50, 200)))
    }

    @Test fun percentAndWalSignalsUseTheirOwnEntryAndClearBands() {
        val thresholds = StorageHealthThresholds(
            warningFreeBytesEntry = 1,
            warningFreeBytesClear = 2,
            criticalFreeBytesEntry = 0,
            criticalFreeBytesClear = 1,
            warningUsedPercentEntry = 80.0,
            warningUsedPercentClear = 70.0,
            criticalUsedPercentEntry = 95.0,
            criticalUsedPercentClear = 90.0,
            warningWalBytesEntry = 100,
            warningWalBytesClear = 50,
            criticalWalBytesEntry = 200,
            criticalWalBytesClear = 150,
        )
        val policy = StorageHealthPolicy(thresholds)

        assertEquals(StorageHealthSeverity.WARNING, policy.evaluate(StorageHealthSeverity.HEALTHY, observation(200, 1_000)))
        assertEquals(StorageHealthSeverity.WARNING, policy.evaluate(StorageHealthSeverity.WARNING, observation(299, 1_000)))
        assertEquals(StorageHealthSeverity.HEALTHY, policy.evaluate(StorageHealthSeverity.WARNING, observation(300, 1_000)))
        assertEquals(StorageHealthSeverity.CRITICAL, policy.evaluate(StorageHealthSeverity.HEALTHY, observation(1_000, 1_000, wal = 200)))
        assertEquals(StorageHealthSeverity.CRITICAL, policy.evaluate(StorageHealthSeverity.CRITICAL, observation(1_000, 1_000, wal = 151)))
        assertEquals(StorageHealthSeverity.WARNING, policy.evaluate(StorageHealthSeverity.CRITICAL, observation(1_000, 1_000, wal = 150)))
        assertEquals(StorageHealthSeverity.HEALTHY, policy.evaluate(StorageHealthSeverity.WARNING, observation(1_000, 1_000, wal = 50)))
    }

    @Test fun unknownCapacityDoesNotFabricatePressureAndMetricsAreNormalized() {
        val authority = StorageHealthAuthority(StorageHealthPolicy(freeSpaceThresholds()))
        val snapshot = authority.refresh(
            observation(
                usable = Long.MAX_VALUE,
                total = -1,
                wal = -1,
                quickCheck = StorageQuickCheck.NOT_RUN,
            ).copy(
                checkedAtMillis = -5,
                mainDatabaseBytes = -1,
                sidecarBytes = -2,
                pageSizeBytes = -3,
                pageCount = 3,
                freelistCount = Long.MAX_VALUE,
                schemaVersion = -4,
            ),
        )

        assertEquals(StorageHealthSeverity.UNCHECKED, snapshot.severity)
        assertEquals(0L, snapshot.checkedAtMillis)
        assertEquals(0L, snapshot.totalBytes)
        assertNull(snapshot.usedPercent)
        assertEquals(0L, snapshot.mainDatabaseBytes)
        assertEquals(0L, snapshot.walBytes)
        assertEquals(0L, snapshot.sidecarBytes)
        assertEquals(3L, snapshot.freelistCount)
        assertEquals(0, snapshot.schemaVersion)

        assertEquals(StorageHealthSeverity.WARNING,
            StorageHealthPolicy(freeSpaceThresholds()).evaluate(StorageHealthSeverity.WARNING, observation(0, 0)))
        assertEquals(StorageHealthSeverity.WARNING,
            StorageHealthPolicy(freeSpaceThresholds()).evaluate(
                StorageHealthSeverity.UNCHECKED,
                observation(0, 0, wal = 100),
            ))
    }

    @Test fun databaseFailureLatchesAcrossRefreshAndClearsOnlyAfterARealWrite() {
        val authority = StorageHealthAuthority(StorageHealthPolicy(freeSpaceThresholds()))
        authority.refresh(observation(180, 200))

        val failed = authority.recordDatabaseFailure(" Config write / token=secret ", SQLiteFullException())
        assertEquals(StorageHealthSeverity.DATABASE_FAILURE, failed.severity)
        assertEquals(StorageHealthSeverity.HEALTHY, failed.pressureSeverity)
        assertEquals(StorageDatabaseFailureKind.STORAGE_FULL, failed.databaseFailureKind)
        assertEquals("database", failed.databaseFailureOperation)

        assertEquals(StorageHealthSeverity.DATABASE_FAILURE, authority.recordDatabaseWriteSuccess().severity)

        val refreshed = authority.refresh(observation(25, 200))
        assertEquals(StorageHealthSeverity.DATABASE_FAILURE, refreshed.severity)
        assertEquals(StorageHealthSeverity.CRITICAL, refreshed.pressureSeverity)

        assertEquals(StorageHealthSeverity.DATABASE_FAILURE, authority.recordDatabaseWriteSuccess().severity)

        val warning = authority.refresh(observation(50, 200))
        assertEquals(StorageHealthSeverity.DATABASE_FAILURE, warning.severity)
        assertEquals(StorageHealthSeverity.WARNING, warning.pressureSeverity)
        assertEquals(StorageHealthSeverity.DATABASE_FAILURE, authority.recordDatabaseWriteSuccess().severity)

        val healthy = authority.refresh(observation(150, 200))
        assertEquals(StorageHealthSeverity.DATABASE_FAILURE, healthy.severity)
        assertEquals(StorageHealthSeverity.HEALTHY, healthy.pressureSeverity)
        val recovered = authority.recordDatabaseWriteSuccess()
        assertEquals(StorageHealthSeverity.HEALTHY, recovered.severity)
        assertNull(recovered.databaseFailureKind)
        assertNull(recovered.databaseFailureOperation)
    }

    @Test fun failedQuickCheckRequiresBothACleanCheckAndLaterWrite() {
        val authority = StorageHealthAuthority(StorageHealthPolicy(freeSpaceThresholds()))
        val corrupt = authority.refresh(observation(180, 200, quickCheck = StorageQuickCheck.FAILED))
        assertEquals(StorageHealthSeverity.DATABASE_FAILURE, corrupt.severity)
        assertEquals(StorageDatabaseFailureKind.CORRUPTION, corrupt.databaseFailureKind)
        assertEquals("quick-check", corrupt.databaseFailureOperation)

        assertEquals(StorageHealthSeverity.DATABASE_FAILURE, authority.recordDatabaseWriteSuccess().severity)
        assertEquals(StorageHealthSeverity.DATABASE_FAILURE,
            authority.refresh(observation(180, 200, quickCheck = StorageQuickCheck.OK)).severity)
        assertEquals(StorageHealthSeverity.HEALTHY, authority.recordDatabaseWriteSuccess().severity)
    }

    @Test fun probeThatBeganBeforeFailureCannotSupplyRecoveryEvidence() {
        val authority = StorageHealthAuthority(StorageHealthPolicy(freeSpaceThresholds()))
        authority.refresh(observation(180, 200))

        val staleProbe = authority.beginObservation()
        authority.recordDatabaseFailure("catalog-sync", SQLiteDiskIOException())
        authority.refresh(observation(180, 200, quickCheck = StorageQuickCheck.OK), staleProbe)
        assertEquals(StorageHealthSeverity.DATABASE_FAILURE, authority.recordDatabaseWriteSuccess().severity)

        val postFailureProbe = authority.beginObservation()
        authority.refresh(observation(180, 200, quickCheck = StorageQuickCheck.OK), postFailureProbe)
        assertEquals(StorageHealthSeverity.HEALTHY, authority.recordDatabaseWriteSuccess().severity)
    }

    @Test fun staleProbePublishingAfterFreshProbeInvalidatesItsRecoveryEvidence() {
        val authority = StorageHealthAuthority(StorageHealthPolicy(freeSpaceThresholds()))
        authority.refresh(observation(180, 200))

        val staleProbe = authority.beginObservation()
        authority.recordDatabaseFailure("catalog-sync", SQLiteDiskIOException())
        val postFailureProbe = authority.beginObservation()
        authority.refresh(observation(180, 200, quickCheck = StorageQuickCheck.OK), postFailureProbe)
        authority.refresh(observation(180, 200, quickCheck = StorageQuickCheck.OK), staleProbe)
        assertEquals(StorageHealthSeverity.DATABASE_FAILURE, authority.recordDatabaseWriteSuccess().severity)

        val replacementProbe = authority.beginObservation()
        authority.refresh(observation(180, 200, quickCheck = StorageQuickCheck.OK), replacementProbe)
        assertEquals(StorageHealthSeverity.HEALTHY, authority.recordDatabaseWriteSuccess().severity)
    }

    @Test fun failureClassificationIsBoundedTypedAndNeverPublishesExceptionText() {
        assertEquals(StorageDatabaseFailureKind.STORAGE_FULL, classifyDatabaseFailure(SQLiteFullException()))
        assertEquals(StorageDatabaseFailureKind.IO, classifyDatabaseFailure(SQLiteDiskIOException()))
        assertEquals(StorageDatabaseFailureKind.CORRUPTION, classifyDatabaseFailure(SQLiteCorruptException()))
        assertEquals(StorageDatabaseFailureKind.BUSY, classifyDatabaseFailure(SQLiteBusyException()))
        assertEquals(StorageDatabaseFailureKind.STORAGE_FULL,
            classifyDatabaseFailure(IllegalStateException("wrapper", IllegalArgumentException("database or disk is full"))))
        assertEquals(StorageDatabaseFailureKind.UNKNOWN, classifyDatabaseFailure(IllegalStateException("password=hunter2")))

        val snapshot = StorageHealthAuthority(StorageHealthPolicy()).recordDatabaseFailure(
            "write user@example.test password=hunter2",
            IllegalStateException("database path /data/user/0/private and password=hunter2"),
        )
        assertFalse(snapshot.toString().contains("/data/user"))
        assertFalse(snapshot.toString().contains("hunter2"))
        assertEquals("database", snapshot.databaseFailureOperation)
        assertEquals("app-state-write", sanitizeDatabaseOperation("app_state:config"))
        assertEquals("catalog-maintenance", sanitizeDatabaseOperation("catalog-maintenance"))

        val checkpoint = StorageHealthAuthority(StorageHealthPolicy()).recordDatabaseFailure(
            "database-checkpoint",
            SQLiteBusyException(),
        )
        assertEquals(StorageDatabaseFailureKind.BUSY, checkpoint.databaseFailureKind)
        assertEquals("database-checkpoint", checkpoint.databaseFailureOperation)
    }

    @Test fun subscriberDeliveryCannotRegressWhenConcurrentWritersRace() {
        val authority = StorageHealthAuthority(StorageHealthPolicy(freeSpaceThresholds()))
        val deliveries = Collections.synchronizedList(mutableListOf<Long>())
        val firstDeliveryEntered = CountDownLatch(1)
        val releaseFirstDelivery = CountDownLatch(1)
        val subscription = authority.subscribe { snapshot ->
            deliveries += snapshot.checkedAtMillis
            if (snapshot.checkedAtMillis == 1L) {
                firstDeliveryEntered.countDown()
                assertTrue(releaseFirstDelivery.await(2, TimeUnit.SECONDS))
            }
        }
        val first = thread { authority.refresh(observation(180, 200).copy(checkedAtMillis = 1)) }
        assertTrue(firstDeliveryEntered.await(2, TimeUnit.SECONDS))
        val second = thread { authority.refresh(observation(180, 200).copy(checkedAtMillis = 2)) }
        assertFalse("second writer must wait behind first publication", second.joinWithin(100))
        releaseFirstDelivery.countDown()
        first.join()
        second.join()
        subscription.close()

        assertEquals(listOf(0L, 1L, 2L), deliveries)
    }

    @Test fun oneBrokenSubscriberDoesNotStarveTheOthersAndCloseUnsubscribes() {
        val authority = StorageHealthAuthority(StorageHealthPolicy(freeSpaceThresholds()))
        authority.subscribe { if (it.checkedAtMillis > 0) error("broken") }
        val received = mutableListOf<Long>()
        val subscription = authority.subscribe { received += it.checkedAtMillis }

        authority.refresh(observation(180, 200).copy(checkedAtMillis = 1))
        subscription.close()
        authority.refresh(observation(180, 200).copy(checkedAtMillis = 2))

        assertEquals(listOf(0L, 1L), received)
    }

    @Test fun runtimeStyleDispatchLeavesTheWriterThreadAndSuppressesQueuedWorkAfterClose() {
        val authority = StorageHealthAuthority(StorageHealthPolicy(freeSpaceThresholds()))
        val executor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "storage-listener-test") }
        val received = Collections.synchronizedList(mutableListOf<Pair<Long, String>>())
        val delivered = CountDownLatch(2)
        try {
            val subscription = StorageHealthListenerDispatch(executor).subscribe(authority) { snapshot ->
                received += snapshot.checkedAtMillis to Thread.currentThread().name
                delivered.countDown()
            }
            val writerName = "sqlite-writer-test"
            thread(name = writerName) {
                authority.refresh(observation(180, 200).copy(checkedAtMillis = 1))
            }.join()
            assertTrue(delivered.await(2, TimeUnit.SECONDS))
            assertEquals(listOf(0L, 1L), received.map { it.first })
            assertTrue(received.all { it.second == "storage-listener-test" && it.second != writerName })

            subscription.close()
            authority.refresh(observation(180, 200).copy(checkedAtMillis = 2))
            executor.shutdown()
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS))
            assertEquals(listOf(0L, 1L), received.map { it.first })
        } finally {
            executor.shutdownNow()
        }
    }

    @Test fun reentrantPublicationIsQueuedBehindTheSnapshotAlreadyBeingDelivered() {
        val authority = StorageHealthAuthority(StorageHealthPolicy(freeSpaceThresholds()))
        val received = mutableListOf<Long>()
        authority.subscribe { snapshot ->
            if (snapshot.checkedAtMillis == 1L) {
                authority.refresh(observation(180, 200).copy(checkedAtMillis = 2))
            }
        }
        authority.subscribe { received += it.checkedAtMillis }

        authority.refresh(observation(180, 200).copy(checkedAtMillis = 1))

        assertEquals(listOf(0L, 1L, 2L), received)
    }

    @Test fun invertedThresholdBandsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            StorageHealthThresholds(warningFreeBytesEntry = 200, warningFreeBytesClear = 100)
        }
        assertThrows(IllegalArgumentException::class.java) {
            StorageHealthThresholds(warningWalBytesEntry = 10, warningWalBytesClear = 11)
        }
    }

    private fun freeSpaceThresholds() = StorageHealthThresholds(
        warningFreeBytesEntry = 100,
        warningFreeBytesClear = 150,
        criticalFreeBytesEntry = 25,
        criticalFreeBytesClear = 50,
        warningUsedPercentEntry = 100.0,
        warningUsedPercentClear = 100.0,
        criticalUsedPercentEntry = 100.0,
        criticalUsedPercentClear = 100.0,
        warningWalBytesEntry = 100,
        warningWalBytesClear = 50,
        criticalWalBytesEntry = 200,
        criticalWalBytesClear = 150,
    )

    private fun observation(
        usable: Long,
        total: Long,
        wal: Long = 0,
        quickCheck: StorageQuickCheck = StorageQuickCheck.OK,
    ) = StorageHealthObservation(
        checkedAtMillis = 123,
        usableBytes = usable,
        totalBytes = total,
        mainDatabaseBytes = 10,
        walBytes = wal,
        sidecarBytes = 2,
        pageSizeBytes = 4_096,
        pageCount = 10,
        freelistCount = 2,
        schemaVersion = 14,
        quickCheck = quickCheck,
    )

    private fun Thread.joinWithin(milliseconds: Long): Boolean {
        join(milliseconds)
        return !isAlive
    }

    private class SQLiteFullException : RuntimeException("private full detail")
    private class SQLiteDiskIOException : RuntimeException("private io detail")
    private class SQLiteCorruptException : RuntimeException("private corruption detail")
    private class SQLiteBusyException : RuntimeException("private busy detail")

    @Test fun sanitizingAnAlreadySanitizedOperationLeavesItAlone() {
        // Capture sanitizes and every render boundary sanitizes again, so a label that survives one
        // pass but not two would reach users as the generic fallback while looking correct in the
        // captured state. Every value the function can return must be a fixed point of itself.
        val everyOutput = KNOWN_DATABASE_OPERATIONS + setOf("app-state-write", "database")
        for (operation in everyOutput) {
            assertEquals(
                "sanitize must be idempotent for the value it can itself produce",
                operation,
                sanitizeDatabaseOperation(operation),
            )
        }
    }

    @Test fun everyAppStateNamespaceCollapsesToOneStableLabel() {
        assertEquals("app-state-write", sanitizeDatabaseOperation("app_state:renderer"))
        assertEquals("app-state-write", sanitizeDatabaseOperation("app_state"))
        assertEquals("app-state-write", sanitizeDatabaseOperation(sanitizeDatabaseOperation("app_state:mqtt")))
    }

    // Source-text reason: reads the installer's published operation enum from the shipped install.js as
    // input; a label the installer can render must never collapse to the generic "database".
    @Test fun everyOperationTheInstallerRendersSurvivesSanitizing() {
        val installer = TestSources.asset("install.js").readText()
        val enum = Regex("""operation:\[([^\]]*)\]""").find(installer)?.groupValues?.get(1)
        val labels = Regex(""""([a-z0-9_-]+)"""").findAll(requireNotNull(enum) { "install.js has no operation enum" })
            .map { it.groupValues[1] }.filterNot { it == "database" }.toList()
        assertTrue("the enum scan must find the labels, found $labels", labels.size >= 20)
        assertEquals(emptyList<String>(), labels.filterNot { sanitizeDatabaseOperation(it) == it })
    }
}
