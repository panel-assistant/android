package io.panelassistant.android.dashboard

import android.content.ContextWrapper
import io.panelassistant.android.dashboard.EntityCatalogStore.DatabaseUsage
import io.panelassistant.android.testsupport.scratchDirectory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class EntityCatalogDatabaseUsageTest {
    private val directory = scratchDirectory("catalog-database-usage").toFile()
    private val context = object : ContextWrapper(null) {
        override fun getFilesDir(): File = directory
        override fun getNoBackupFilesDir(): File = directory
        override fun getDatabasePath(name: String): File = File(directory, name)
        override fun getApplicationContext() = this
    }
    private val monotonicMs = AtomicLong(1_000L)
    private val stores = mutableListOf<EntityCatalogStore>()
    private val workers = mutableListOf<Worker<*>>()
    private val releases = mutableListOf<CountDownLatch>()
    private val measured = DatabaseUsage(100L, 200L, 3)
    private val refreshed = DatabaseUsage(400L, 500L, 3)
    private val maintained = DatabaseUsage(50L, 80L, 3)

    @After fun cleanUp() {
        releases.forEach { it.countDown() }
        workers.forEach { it.thread.join(5_000L) }
        workers.filter { it.thread.isAlive }.forEach { it.future.cancel(true) }
        workers.filter { it.thread.isAlive }.forEach { it.thread.join(5_000L) }
        try {
            assertTrue("database usage workers must terminate", workers.none { it.thread.isAlive })
        } finally {
            stores.forEach { it.close() }
            directory.deleteRecursively()
        }
    }

    @Test fun freshDatabaseUsageReusesTheMeasurement() {
        val calls = AtomicInteger()
        val store = store { calls.incrementAndGet(); measured }

        assertEquals(measured, store.databaseUsage())
        assertEquals(measured, store.databaseUsage())
        assertEquals("a fresh usage read does not probe SQLite again", 1, calls.get())
    }

    @Test fun overlappingColdDatabaseUsageConsumersShareOneMeasurement() {
        assertOverlappingConsumersShareMeasurement(expired = false)
    }

    @Test fun overlappingExpiredDatabaseUsageConsumersShareOneMeasurement() {
        assertOverlappingConsumersShareMeasurement(expired = true)
    }

    @Test fun databaseUsageExpiresAtTenThousandMonotonicMilliseconds() {
        val calls = AtomicInteger()
        val store = store { if (calls.incrementAndGet() == 1) measured else refreshed }

        assertEquals(measured, store.databaseUsage())
        monotonicMs.addAndGet(9_999L)
        assertEquals(measured, store.databaseUsage())
        assertEquals(1, calls.get())
        monotonicMs.incrementAndGet()
        assertEquals(refreshed, store.databaseUsage())
        assertEquals(2, calls.get())
    }

    @Test fun maintenancePublicationWaitsForInFlightMeasurementAndThenWins() {
        val calls = AtomicInteger()
        val entered = CountDownLatch(1)
        val release = releaseGate()
        val store = store {
            calls.incrementAndGet()
            entered.countDown()
            assertTrue("measurement released", release.await(5, TimeUnit.SECONDS))
            measured
        }
        val measurement = worker { store.databaseUsage() }
        try {
            assertTrue("measurement entered", entered.await(5, TimeUnit.SECONDS))
            val publication = worker { store.updateDatabaseUsageCache(maintained) }
            assertWaiting(publication)
            assertEquals(1, calls.get())
            release.countDown()

            assertEquals(measured, measurement.result())
            publication.result()
            assertEquals("completed maintenance supersedes the older probe", maintained, store.databaseUsage())
            assertEquals(1, calls.get())
        } finally {
            release.countDown()
        }
    }

    @Test fun maintenancePublicationIsFreshUntilExpiryThenMeasurementWins() {
        val calls = AtomicInteger()
        val store = store { calls.incrementAndGet(); refreshed }

        store.updateDatabaseUsageCache(maintained)
        assertEquals(maintained, store.databaseUsage())
        monotonicMs.addAndGet(9_999L)
        assertEquals(maintained, store.databaseUsage())
        assertEquals("known maintenance usage avoids a new probe", 0, calls.get())
        monotonicMs.incrementAndGet()
        assertEquals(refreshed, store.databaseUsage())
        assertEquals(1, calls.get())
        assertEquals(refreshed, store.databaseUsage())
        assertEquals(1, calls.get())
    }

    private fun assertOverlappingConsumersShareMeasurement(expired: Boolean) {
        val calls = AtomicInteger()
        val entered = CountDownLatch(1)
        val release = releaseGate()
        val store = store {
            calls.incrementAndGet()
            entered.countDown()
            assertTrue("measurement released", release.await(5, TimeUnit.SECONDS))
            measured
        }
        if (expired) {
            store.updateDatabaseUsageCache(maintained)
            assertEquals(maintained, store.databaseUsage())
            monotonicMs.addAndGet(10_000L)
        }
        val first = worker { store.databaseUsage() }
        try {
            assertTrue("first consumer is measuring", entered.await(5, TimeUnit.SECONDS))
            val second = worker { store.databaseUsage() }
            assertWaiting(second)
            assertEquals("overlapping consumers share the active probe", 1, calls.get())
            release.countDown()

            assertEquals(measured, first.result())
            assertEquals(measured, second.result())
            assertEquals(1, calls.get())
        } finally {
            release.countDown()
        }
    }

    private fun store(measure: () -> DatabaseUsage): EntityCatalogStore =
        EntityCatalogStore(context, monotonicMs::get, { measure() }).also { stores.add(it) }

    private fun releaseGate() = CountDownLatch(1).also { releases.add(it) }

    private class Worker<T>(val future: FutureTask<T>, val thread: Thread) {
        fun result(): T = future.get(5, TimeUnit.SECONDS)
    }

    private fun <T> worker(block: () -> T): Worker<T> {
        val future = FutureTask(Callable { block() })
        return Worker(future, Thread(future, "database-usage-${workers.size}")).also {
            workers.add(it)
            it.thread.start()
        }
    }

    private fun assertWaiting(worker: Worker<*>) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!worker.future.isDone && System.nanoTime() < deadline) {
            val state = worker.thread.state
            if (state == Thread.State.BLOCKED || state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING) {
                assertFalse("consumer cannot finish before the active probe is released", worker.future.isDone)
                return
            }
            Thread.yield()
        }
        throw AssertionError("consumer never waited for the active probe; state=${worker.thread.state}")
    }
}
