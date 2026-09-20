package io.github.maxlyth.hapaneld.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

class SuExecFailureCacheTest {
    @Test
    fun `missing su is cached after the first definitive launch failure`() {
        val cache = SuExecFailureCache()
        val missing = IOException("Cannot run program \"su\": error=2, No such file or directory")

        assertFalse(cache.shouldSkipExec())
        assertEquals(SuExecFailure.FIRST_MISSING, cache.record(missing))
        assertTrue(cache.shouldSkipExec())
        assertEquals(SuExecFailure.ALREADY_MISSING, cache.record(missing))
    }

    @Test
    fun `nested ENOENT launch cause is classified as missing`() {
        val cache = SuExecFailureCache()
        val wrapped = RuntimeException("launch failed", IOException("error=2, missing executable"))

        assertEquals(SuExecFailure.FIRST_MISSING, cache.record(wrapped))
        assertTrue(cache.shouldSkipExec())
    }

    @Test
    fun `permission and other launch failures retain normal diagnostics and retries`() {
        // An su that exists and is executable by this uid: EACCES here is a root manager or SELinux
        // saying no, which can change. The mode-denied case is covered in SuRefusalCacheTest.
        val cache = SuExecFailureCache(deniedToCaller = { false })

        assertEquals(
            SuExecFailure.OTHER,
            cache.record(IOException("Cannot run program \"su\": error=13, Permission denied")),
        )
        assertEquals(SuExecFailure.OTHER, cache.record(IllegalStateException("runtime failure")))
        assertFalse(cache.shouldSkipExec())
    }

    @Test
    fun `concurrent missing reports have exactly one first reporter`() {
        val cache = SuExecFailureCache()
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        try {
            val reports = (1..32).map {
                pool.submit<SuExecFailure> {
                    start.await()
                    cache.record(IOException("error=2, No such file or directory"))
                }
            }

            start.countDown()
            val results = reports.map { it.get() }
            assertEquals(1, results.count { it == SuExecFailure.FIRST_MISSING })
            assertEquals(31, results.count { it == SuExecFailure.ALREADY_MISSING })
            assertTrue(cache.shouldSkipExec())
        } finally {
            pool.shutdownNow()
        }
    }
}
