package io.panelassistant.android.control

import io.panelassistant.android.platform.RootRunOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/**
 * The four launch classes a supported panel made visible, and what re-tests each one.
 *
 * One supported panel ships `su` mode 4750 `root:shell`, so its app uid is refused EACCES roughly every
 * 25 seconds while the binary plainly exists. That refusal is decidable from the exec permission on the
 * file, so it latches; every other refusal stays retryable.
 */
class SuRefusalCacheTest {
    private val eacces = IOException("Cannot run program \"su\": error=13, Permission denied")

    @Test
    fun `su denied by its own mode is recognised once and then skips further execs`() {
        val cache = SuExecFailureCache(deniedToCaller = { true })

        assertFalse(cache.shouldSkipExec())
        assertEquals(SuExecFailure.FIRST_DENIED, cache.record(eacces))
        assertEquals(SuExecFailure.ALREADY_DENIED, cache.record(eacces))
        assertTrue(cache.shouldSkipExec())
    }

    @Test
    fun `a mode-denied panel still reports the true outcome to callers`() {
        val cache = SuExecFailureCache(deniedToCaller = { true })
        cache.record(eacces)

        // The command genuinely never launched, which is what NO_LAUNCH means; the latch does not invent
        // an outcome, and a command that ran and failed is still RAN_FAILED.
        assertEquals(
            RootRunOutcome.NO_LAUNCH,
            classifyRootRun(ran = false, launchCreatedNoProcess = false, rootKnownUnusable = cache.shouldSkipExec()),
        )
    }

    @Test
    fun `an ordinary refusal is never latched and is found when it later becomes a grant`() {
        // su is present and executable by this uid, so the refusal was raised after the permission check
        // passed — a denied domain transition at execve, which can say yes later, so nothing may latch.
        val cache = SuExecFailureCache(deniedToCaller = { false })

        assertEquals(SuExecFailure.OTHER, cache.record(eacces))
        assertEquals(SuExecFailure.OTHER, cache.record(eacces))
        assertFalse("an ordinary refusal must leave the next probe eligible", cache.shouldSkipExec())
    }

    @Test
    fun `the denial latch clears when an executable su appears during this boot`() {
        var denied = true
        val cache = SuExecFailureCache(deniedToCaller = { denied })
        cache.record(eacces)
        assertTrue(cache.shouldSkipExec())

        denied = false                  // a manager chmod'd su, or installed an executable one on PATH

        assertFalse("root gained during uptime must still be found", cache.shouldSkipExec())
    }

    @Test
    fun `a command that fails under a working su is not a launch refusal at all`() {
        val cache = SuExecFailureCache(deniedToCaller = { true })

        // A non-zero exit never reaches the cache: nothing records, nothing latches, and the outcome says
        // the command ran and failed.
        assertFalse(cache.shouldSkipExec())
        assertEquals(
            RootRunOutcome.RAN_FAILED,
            classifyRootRun(ran = false, launchCreatedNoProcess = false, rootKnownUnusable = cache.shouldSkipExec()),
        )
    }

    @Test
    fun `a missing su latches without consulting the mode probe`() {
        var probed = false
        val cache = SuExecFailureCache(deniedToCaller = { probed = true; false })
        val missing = IOException("Cannot run program \"su\": error=2, No such file or directory")

        assertEquals(SuExecFailure.FIRST_MISSING, cache.record(missing))
        assertTrue(cache.shouldSkipExec())
        assertFalse("the missing-binary latch is unchanged and permanent", probed)
    }

    @Test
    fun `concurrent denial reports have exactly one first reporter`() {
        val cache = SuExecFailureCache(deniedToCaller = { true })
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        try {
            val reports = (1..32).map {
                pool.submit<SuExecFailure> {
                    start.await()
                    cache.record(eacces)
                }
            }

            start.countDown()
            val results = reports.map { it.get() }
            assertEquals(1, results.count { it == SuExecFailure.FIRST_DENIED })
            assertEquals(31, results.count { it == SuExecFailure.ALREADY_DENIED })
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `denial needs an EACCES-shaped launch failure, not merely a denying mode`() {
        val cache = SuExecFailureCache(deniedToCaller = { true })

        assertEquals(SuExecFailure.OTHER, cache.record(IllegalStateException("runtime failure")))
        assertFalse(cache.shouldSkipExec())
    }
}

/** The PATH sweep that makes "this uid can never exec su" decidable. */
class SuDeniedToCallerTest {
    private val path = listOf("/sbin", "/system/bin", "/system/xbin")

    @Test
    fun `an su that exists everywhere and is executable nowhere is denied`() {
        assertTrue(suDeniedToCaller(path, exists = { true }, canExecute = { false }))
    }

    @Test
    fun `one executable su anywhere on the path is not a denial`() {
        // Runtime-exec resolves a bare name like execvp: an EACCES in one directory does not end the
        // search, so a later executable su still wins and nothing may latch.
        assertFalse(
            suDeniedToCaller(
                path,
                exists = { true },
                canExecute = { it == "/system/xbin/su" },
            ),
        )
    }

    @Test
    fun `no su on the path at all is not a denial`() {
        assertFalse(suDeniedToCaller(path, exists = { false }, canExecute = { false }))
    }

    @Test
    fun `the process PATH is used when it is set, and the Android default otherwise`() {
        assertEquals(listOf("/sbin", "/system/bin"), suSearchPath("/sbin:/system/bin"))
        assertEquals(DEFAULT_SU_SEARCH_PATH, suSearchPath(null))
        assertEquals(DEFAULT_SU_SEARCH_PATH, suSearchPath(""))
    }
}
