package io.github.maxlyth.hapaneld.migration

import io.github.maxlyth.hapaneld.config.SettingsRegistry
import io.github.maxlyth.hapaneld.http.migrationRestoreComplete
import io.github.maxlyth.hapaneld.http.migrationRestoreConfig
import io.github.maxlyth.hapaneld.persistence.ConfigVault
import io.github.maxlyth.hapaneld.persistence.StateBackupPolicy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MigrationRestoreTest {
    @Test fun migrationModeIsAdmittedOnlyToALoopbackRequestWhileTheRestoreStepIsOpen() {
        assertEquals(
            MigrationRestoreAdmission.ADMITTED,
            migrationRestoreAdmission(requested = true, loopbackPeer = true, restoreOpen = true),
        )
        assertEquals(
            MigrationRestoreAdmission.REFUSED,
            migrationRestoreAdmission(requested = true, loopbackPeer = false, restoreOpen = true),
        )
        assertEquals(
            MigrationRestoreAdmission.REFUSED,
            migrationRestoreAdmission(requested = true, loopbackPeer = true, restoreOpen = false),
        )
    }

    @Test fun aRestoreThatDoesNotAskForMigrationModeIsNeverInIt() {
        assertEquals(
            MigrationRestoreAdmission.NOT_REQUESTED,
            migrationRestoreAdmission(requested = false, loopbackPeer = true, restoreOpen = true),
        )
    }

    @Test fun anOrdinaryRestoreProvesTheDeviceByPanelIdExactlyAsBefore() {
        assertTrue(StateBackupPolicy.sameDevice(migrationRestore = false, panelIdMatches = true, discoveryIdMatches = false))
        assertFalse(StateBackupPolicy.sameDevice(migrationRestore = false, panelIdMatches = false, discoveryIdMatches = true))
    }

    @Test fun aMigrationRestoreProvesTheDeviceByDiscoveryIdAlone() {
        assertTrue(StateBackupPolicy.sameDevice(migrationRestore = true, panelIdMatches = false, discoveryIdMatches = true))
        assertFalse(StateBackupPolicy.sameDevice(migrationRestore = true, panelIdMatches = true, discoveryIdMatches = false))
    }

    @Test fun deviceLocalRowsReturnUnderTheMigrationProofAndNothingElseDoes() {
        val rows = listOf(
            ConfigVault.StateRow("controller-state", "k", "string", "v", 0L),
            ConfigVault.StateRow("config", "panel_id", "string", "alpha", 0L),
            ConfigVault.StateRow("startup-recovery", "k", "int", "1", 0L),
            ConfigVault.StateRow("unclassified", "k", "string", "v", 0L),
        )
        val proven = StateBackupPolicy.sameDevice(migrationRestore = true, panelIdMatches = false, discoveryIdMatches = true)
        val unproven = StateBackupPolicy.sameDevice(migrationRestore = true, panelIdMatches = true, discoveryIdMatches = false)

        assertEquals(listOf("controller-state"), StateBackupPolicy.restorableRows(rows, proven).map { it.namespace })
        assertEquals(emptyList<String>(), StateBackupPolicy.restorableRows(rows, unproven).map { it.namespace })
    }

    @Test fun theBridgesCompanionEntryForTheSuccessorIsNotCarriedIntoTheSuccessor() {
        val restored = migrationRestoreConfig(
            mapOf(
                "panel_id" to "alpha",
                "kiosk_companion_packages" to
                    "com.example.kept, io.panelassistant.android\nio.github.maxlyth.hapaneld,com.example.other",
            ),
        )

        assertEquals("com.example.kept,com.example.other", restored["kiosk_companion_packages"])
        assertEquals("alpha", restored["panel_id"])
    }

    @Test fun settingsThatNameTheWritersOwnPackageFollowTheAppToItsNewId() {
        val restored = migrationRestoreConfig(
            mapOf(
                "launcher_package" to "io.github.maxlyth.hapaneld",
                "dashboard_package" to "io.github.maxlyth.hapaneld",
                "panel_id" to "io.github.maxlyth.hapaneld",
            ),
        )

        assertEquals("io.panelassistant.android", restored["launcher_package"])
        assertEquals("io.panelassistant.android", restored["dashboard_package"])
        assertEquals("only package-valued settings are rewritten", "io.github.maxlyth.hapaneld", restored["panel_id"])
    }

    @Test fun aForeignLauncherOrRendererIsRestoredAsWritten() {
        val values = mapOf("launcher_package" to "com.example.launcher", "dashboard_package" to "builtin")

        assertSame(values, migrationRestoreConfig(values))
    }

    @Test fun aMigrationRestoreIsCompleteOnlyWhenEveryCarriedValueLanded() {
        assertTrue(migrationRestoreComplete(rawPreferencesApplied = true, carriedRows = 17, restoredRows = 17))
        assertTrue(migrationRestoreComplete(rawPreferencesApplied = true, carriedRows = 0, restoredRows = 0))
        assertFalse(migrationRestoreComplete(rawPreferencesApplied = true, carriedRows = 17, restoredRows = 16))
        assertFalse(migrationRestoreComplete(rawPreferencesApplied = true, carriedRows = 17, restoredRows = 0))
        assertFalse(migrationRestoreComplete(rawPreferencesApplied = false, carriedRows = 17, restoredRows = 17))
    }

    @Test fun aBackupWithoutCompanionPackagesIsRestoredUntouched() {
        val values = mapOf("panel_id" to "alpha")

        assertSame(values, migrationRestoreConfig(values))
    }

    @Test fun theBackupProjectionCarriesTheCredentialsTheSuccessorMustKeep() {
        // The successor never signs in again: the archive it pulls has to hold the broker password and
        // the app-held Home Assistant tokens. They are in it only while they stay settable and durable.
        val carried = SettingsRegistry.settable().filterNot { it.transient }.map { it.key }.toSet()

        listOf("panel_id", "mqtt_password", "ha_refresh_token", "kiosk_companion_packages").forEach { key ->
            assertTrue("$key must be part of the backup projection", key in carried)
        }
    }

    @Test fun onlyARestoreThatActuallyWritesAnswersTheSuccessorsWait() {
        assertTrue(claimsRestoreAttempt(migrationRestore = true, dryRun = false))
        assertFalse("a dry run writes nothing, so it must leave the real attempt open",
            claimsRestoreAttempt(migrationRestore = true, dryRun = true))
        assertFalse(claimsRestoreAttempt(migrationRestore = false, dryRun = false))
        assertFalse(claimsRestoreAttempt(migrationRestore = false, dryRun = true))
    }

    @Test fun aRestoreThatOutlivedItsWaitAnswersItsOwnAttemptAndNeverTheNextOne() {
        val attempts = RestoreAttempts()
        val first = attempts.begin()
        val firstRestore = attempts.claim()
        // The successor gave up waiting and asked again while the first restore was still running.
        val second = attempts.begin()
        val secondRestore = attempts.claim()

        firstRestore.finished(false)

        assertEquals("the first attempt is answered by its own restore", false, first.answer())
        assertFalse("the second attempt is still waiting for the restore it started", second.isCompleted)

        secondRestore.finished(true)
        assertEquals(true, second.answer())
    }

    @Test fun theRestoresOwnOutcomeSurvivesTheFailureItsCompletionHandlerReportsBehindIt() {
        val attempts = RestoreAttempts()
        val outcome = attempts.begin()
        val restore = attempts.claim()

        restore.finished(true)
        restore.finished(false)

        assertEquals("the first answer wins, so a completed restore keeps its outcome", true, outcome.answer())
    }

    @Test fun anAttemptNoRestoreAnsweredFailsAtOnceRatherThanWaitingOutTheTimeout() {
        val attempts = RestoreAttempts()
        val outcome = attempts.begin()
        val restore = attempts.claim()

        // The job was cancelled, or the request was rejected before any restore ran.
        restore.finished(false)

        assertEquals(false, outcome.answer())
    }

    /**
     * The answer, or null when nobody gave one. An unanswered attempt is exactly the defect these
     * tests guard against, so it has to fail the test rather than hang the whole run in [await].
     */
    private fun CompletableDeferred<Boolean>.answer(): Boolean? =
        runBlocking { withTimeoutOrNull(5_000) { await() } }
}
