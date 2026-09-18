package io.github.maxlyth.hapaneld

import io.github.maxlyth.hapaneld.testsupport.TestSources
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The foreground-start deadline, pinned by source text.
 *
 * Android gives a `startForegroundService` caller a few seconds to reach `startForeground`, and on an
 * out-of-process cold start that budget covers process creation and `Application.onCreate` too. Miss
 * it and the platform kills the process with `RemoteServiceException: startForegroundService() did
 * not then call Service.startForeground()`. Android 8.1 panels did exactly that across several
 * builds; one panel's crash buffer held 23 occurrences on 2026-09-18.
 *
 * None of this can run on the JVM, so it gets the same treatment as the camera promotion wiring in
 * `CameraForegroundWiringContractTest`: each assertion is one way the process died, or would have.
 * The cost that mattered was never *inside* `PaneldService.onCreate` — it was `Application.onCreate`
 * opening the database, which runs two full `PRAGMA quick_check` scans over a 10-24 MB file.
 */
class ForegroundStartDeadlineContractTest {

    private val application by lazy { TestSources.kotlin("HaPaneldApp.kt").readText() }
    private val service by lazy { TestSources.kotlin("PaneldService.kt").readText() }

    private fun body(source: String, name: String): String {
        val start = source.indexOf(name)
        assertTrue("$name is present", start >= 0)
        val open = source.indexOf('{', start)
        var depth = 0
        for (i in open until source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return source.substring(open, i + 1)
            }
        }
        error("unbalanced $name")
    }

    /**
     * The headline invariant: nothing on the `Application.onCreate` path may open the database.
     *
     * This is an allowlist rather than an index comparison on purpose. Asserting only that some
     * marker precedes another lets a future helper — `warmCaches()`, `reconcileState()` — be inserted
     * into the same window and still pass. Naming what may appear is what actually holds the line.
     */
    @Test fun applicationStartupNeverOpensTheDatabase() {
        val onCreate = body(application, "override fun onCreate()")
        listOf("Config(", "AppState.preferences", "EntityCatalogStore", "writableDatabase", "readableDatabase")
            .forEach { forbidden ->
                assertFalse(
                    "Application.onCreate runs inside the foreground-start deadline on a cold start, " +
                        "so it must not reach the database via `$forbidden`",
                    onCreate.contains(forbidden),
                )
            }
    }

    @Test fun theNightModeDefaultComesFromTheMirrorNotTheDatabase() {
        val onCreate = body(application, "override fun onCreate()")
        assertTrue(
            "dark_mode is read from the downgrade-compatible XML mirror, which needs no database open",
            onCreate.contains("darkModeBeforeDatabase(this)"),
        )
        val locale = TestSources.kotlin("NativeLocale.kt").readText()
        val reader = body(locale, "internal fun darkModeBeforeDatabase")
        assertTrue("the mirror read uses SharedPreferences", reader.contains("getSharedPreferences(LEGACY_CONFIG_MIRROR"))
        assertFalse("and must not itself reach the database", reader.contains("Config(") || reader.contains("AppState"))
    }

    /**
     * The database stays authoritative — a restore can write it without touching the mirror — so the
     * correction must still happen, just off the deadline and after the service has promoted.
     */
    @Test fun theDatabaseCorrectionRunsAfterThePromoteNotBeforeIt() {
        val onCreate = body(service, "override fun onCreate()")
        // lastIndexOf, not indexOf: the early-return branches promote with the same string before the
        // normal path does, and anchoring on the first of them would let the correction sit anywhere
        // after the RETIRED_BRIDGE promote — including well before the promote that actually matters.
        val promote = onCreate.lastIndexOf("startForegroundCompat(nativeString(R.string.starting), silent = true)")
        val reconcile = onCreate.indexOf("reconcileNativePresentationAfterPromotion()")
        assertTrue("the service promotes on the normal path", promote >= 0)
        assertTrue("the database-backed presentation correction exists", reconcile >= 0)
        assertTrue("and it runs only after the normal-path promote", reconcile > promote)
    }

    /**
     * Every early return in `onCreate` promotes before it does its own work, so a branch that decides
     * to hand off or stand down still answers the deadline. A `stopSelf()` before a `startForeground`
     * is the same fatal shape as never promoting at all.
     */
    @Test fun everyEarlyReturnPromotesBeforeItStopsItself() {
        val onCreate = body(service, "override fun onCreate()")
        // Each stop needs a promote of its OWN, so the window runs from the previous stop rather than
        // from the start of the method. Searching backwards from the stop instead would let one
        // branch's promote vouch for a later branch that never promoted at all — which is the exact
        // regression this test exists to catch.
        var boundary = 0
        var found = 0
        while (true) {
            val stop = onCreate.indexOf("stopSelf()", boundary)
            if (stop < 0) break
            found++
            val promote = onCreate.indexOf("startForegroundCompat(", boundary)
            assertTrue(
                "the stopSelf() at offset $stop must promote in its own branch, not borrow an earlier one",
                promote in boundary until stop,
            )
            boundary = stop + "stopSelf()".length
        }
        assertTrue("the early-return branches still exist to be checked", found >= 2)
    }

    /**
     * The margin is measured, not assumed. An absence of crashes cannot prove the deadline is met, so
     * every promote reports how long the process took to reach it; that number is the acceptance
     * instrument and the early warning for the next slow panel.
     */
    @Test fun thePromoteReportsHowLongTheProcessTookToReachIt() {
        val promote = body(service, "private fun startForegroundCompat")
        assertTrue(
            "the promote logs its latency alongside the existing line",
            promote.contains("foreground service started\${promotionLatencySuffix()}"),
        )
        // Declaration included, not just the brace body: `promotionLatencySuffix` is expression-bodied,
        // so its `runCatching` sits before the first `{` that body() would anchor on.
        val latency = service.substringAfter("private fun promotionLatencySuffix").substringBefore("\n\n")
        assertTrue("measured against process start", latency.contains("Process.getStartElapsedRealtime()"))
        assertTrue("a bad measurement must never fail a promote", latency.contains("runCatching"))
    }
}
