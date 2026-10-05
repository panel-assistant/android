package io.panelassistant.android

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class DashboardSqliteReadTest {
    @Test fun pendingDatabaseReadLeavesRendererThreadFreeToHandleAnotherEvent() {
        val renderer = Executors.newSingleThreadExecutor { task -> Thread(task, "renderer-test-main") }
        val dispatcher = renderer.asCoroutineDispatcher()
        val readStarted = CountDownLatch(1)
        val finishRead = CountDownLatch(1)
        val handledEvent = CountDownLatch(1)
        try {
            runBlocking {
                val scope = CoroutineScope(dispatcher)
                val read = scope.launch {
                    assertEquals(7, readActivityStateOffMain {
                        assertTrue(Thread.currentThread().name != "renderer-test-main")
                        readStarted.countDown()
                        finishRead.await(2, TimeUnit.SECONDS)
                        7
                    })
                }
                assertTrue(readStarted.await(2, TimeUnit.SECONDS))
                val event = scope.launch { handledEvent.countDown() }
                assertTrue("renderer waited for the database read", handledEvent.await(500, TimeUnit.MILLISECONDS))
                finishRead.countDown()
                read.join()
                event.join()
            }
        } finally {
            finishRead.countDown()
            dispatcher.close()
        }
    }
}
