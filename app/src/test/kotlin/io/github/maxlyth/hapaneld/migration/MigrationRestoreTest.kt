package io.github.maxlyth.hapaneld.migration

import io.github.maxlyth.hapaneld.config.SettingsRegistry
import io.github.maxlyth.hapaneld.http.migrationRestoreConfig
import io.github.maxlyth.hapaneld.persistence.ConfigVault
import io.github.maxlyth.hapaneld.persistence.StateBackupPolicy
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
            ConfigVault.StateRow("config", "panel_id", "string", "kitchen", 0L),
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
                "panel_id" to "kitchen",
                "kiosk_companion_packages" to
                    "com.example.kept, io.panelassistant.android\nio.github.maxlyth.hapaneld,com.example.other",
            ),
        )

        assertEquals("com.example.kept,com.example.other", restored["kiosk_companion_packages"])
        assertEquals("kitchen", restored["panel_id"])
    }

    @Test fun aBackupWithoutCompanionPackagesIsRestoredUntouched() {
        val values = mapOf("panel_id" to "kitchen")

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
}
