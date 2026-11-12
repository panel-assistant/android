package io.panelassistant.android.util

import io.panelassistant.android.http.DiagReader
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.UnknownHostException

/** The service's scheduled Companion lookup asks GitHub only when a Companion app is installed. */
class UpdateCheckerScheduleTest {
    // A cap no real reader asks under, so the target this leaves behind is never reused elsewhere.
    private val cap = "2026.5.4-schedule-test"
    private val release = CompanionInstaller.Target(
        version = "2026.5.4",
        apkUrl = "https://github.com/home-assistant/android/releases/download/2026.5.4/app-full-release.apk",
        releaseUrl = "https://github.com/home-assistant/android/releases/tag/2026.5.4",
        newestVersion = "2026.5.4",
        capped = false,
        tag = "2026.5.4",
    )
    private var lookups = 0
    private var checked = 0
    private val previousObserver = UpdateChecker.onChecked

    init { UpdateChecker.onChecked = { checked++ } }

    private fun fakeGithub(): CompanionInstaller.Target { lookups++; return release }

    @After fun restore() = runBlocking {
        UpdateChecker.check(null, "stable", cap) { null }
        UpdateChecker.onChecked = previousObserver
        UpdateChecker.lastScheduled = null
    }

    @Test fun theScheduledCheckMakesNoRequestWithoutACompanion() = runBlocking {
        UpdateChecker.scheduledCheck(null, "stable", cap, ::fakeGithub)
        assertEquals("GitHub was asked with no Companion installed", 0, lookups)
        assertEquals(UpdateChecker.SCHEDULED_SKIPPED, UpdateChecker.lastScheduled?.result)
        assertEquals("observers still hear the scheduled check completed", 1, checked)
    }

    @Test fun theScheduledCheckStillRunsWithACompanionInstalled() = runBlocking {
        UpdateChecker.scheduledCheck(CompanionInstaller.FULL_PKG to "2026.4.1-full", "stable", cap, ::fakeGithub)
        assertEquals(1, lookups)
        assertEquals(UpdateChecker.SCHEDULED_OK, UpdateChecker.lastScheduled?.result)
        assertEquals(listOf("2026.5.4"), UpdateChecker.available.map { it.latestVersion })
    }

    @Test fun aFailedScheduledLookupRecordsItsFailureClassForDiag() = runBlocking {
        UpdateChecker.scheduledCheck(CompanionInstaller.FULL_PKG to "2026.4.1-full", "stable", cap) {
            throw UnknownHostException("api.github.com")
        }
        val attempt = UpdateChecker.lastScheduled!!
        assertEquals("UnknownHostException", attempt.result)
        val line = DiagReader.updateCheckLine(attempt)
        assertTrue(line, line.startsWith("[update-check] ") && line.endsWith(": UnknownHostException"))
    }

    @Test fun theManualRefreshStillAsksWithoutACompanion() = runBlocking {
        // The status?refresh=1 route and the post-install refresh call check directly, never the schedule.
        assertEquals(UpdateChecker.SCHEDULED_OK, UpdateChecker.check(null, "stable", cap, ::fakeGithub))
        assertEquals(1, lookups)
        assertEquals("a manual refresh is not a scheduled attempt", null, UpdateChecker.lastScheduled)
    }
}
