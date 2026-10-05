package io.panelassistant.android.shizuku

import java.util.concurrent.CountDownLatch
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class BoundedCallExecutorTest {
    @Test fun rejectsWorkInsteadOfQueuingBeyondTheWorkerBound() {
        val entered = CountDownLatch(2)
        val release = CountDownLatch(1)
        val executor = BoundedCallExecutor(2, ThreadFactory { Thread(it).apply { isDaemon = true } })

        val first = executor.submit { entered.countDown(); release.await(); 1 }
        val second = executor.submit { entered.countDown(); release.await(); 2 }
        assertNotNull(first)
        assertNotNull(second)
        assertEquals(true, entered.await(2, TimeUnit.SECONDS))

        assertNull("a blocked Binder lane must not grow a queue or thread pool", executor.submit { 3 })

        release.countDown()
        assertEquals(1, first!!.get(2, TimeUnit.SECONDS))
        assertEquals(2, second!!.get(2, TimeUnit.SECONDS))
    }
}
