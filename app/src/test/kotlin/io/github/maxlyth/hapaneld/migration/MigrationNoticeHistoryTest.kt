package io.github.maxlyth.hapaneld.migration

import io.github.maxlyth.hapaneld.persistence.ConfigVault
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MigrationNoticeHistoryTest {
    @Test fun eachAcceptedAuthorityBecomesNoticeHistoryOnly() {
        for (authority in listOf("mqtt", "shadow", "native")) {
            val source = row("panel_assistant_authority", "string", authority, updatedAt = 11L)
            assertEquals(listOf(noticeRow(11L)), carry(listOf(source)))
        }
    }

    @Test fun positiveUpdateOwnerTimestampBecomesNoticeHistoryWithoutCarryingTheLease() {
        val source = row("panel_assistant_update_owner_seen_ms", "long", "1234", updatedAt = 17L)
        assertEquals(listOf(noticeRow(17L)), carry(listOf(source)))
    }

    @Test fun multiplePositiveSourcesStillProduceOneNoticeOnlyRow() {
        val sources = listOf(
            row("panel_assistant_authority", "string", "native", updatedAt = 11L),
            row("panel_assistant_update_owner_seen_ms", "long", "1234", updatedAt = 17L),
            row("panel_assistant_mqtt_discovery", "string", "withdraw", updatedAt = 19L),
        )
        assertEquals(listOf(noticeRow(17L)), carry(sources))
    }

    @Test fun noticeHistorySurvivesAnotherMigrationWithoutChangingItsMeaning() {
        val first = carry(listOf(row("panel_assistant_authority", "string", "shadow", updatedAt = 23L)))
        assertEquals(listOf(noticeRow(23L)), first)
        assertEquals(first, carry(first))
    }

    @Test fun ordinaryOrUnprovenRestoreCarriesNothing() {
        val source = listOf(row("panel_assistant_authority", "string", "mqtt"))
        assertTrue(migrationNoticeHistoryRows(source, migrationRestore = false, sameDevice = true).isEmpty())
        assertTrue(migrationNoticeHistoryRows(source, migrationRestore = true, sameDevice = false).isEmpty())
        assertTrue(migrationNoticeHistoryRows(source, migrationRestore = false, sameDevice = false).isEmpty())
    }

    @Test fun absentFalseMalformedAndForeignEvidenceCarriesNothing() {
        val invalid = listOf(
            row("panel_assistant_authority", "string", ""),
            row("panel_assistant_authority", "string", "connected"),
            row("panel_assistant_authority", "string", "Native"),
            row("panel_assistant_authority", "long", "1"),
            row("panel_assistant_update_owner_seen_ms", "long", "0"),
            row("panel_assistant_update_owner_seen_ms", "long", "-1"),
            row("panel_assistant_update_owner_seen_ms", "long", "not-a-time"),
            row("panel_assistant_update_owner_seen_ms", "string", "1234"),
            row("migration_notice_connection_seen", "boolean", "0"),
            row("migration_notice_connection_seen", "boolean", "true"),
            row("migration_notice_connection_seen", "string", "1"),
            row("panel_assistant_authority", "string", "native", namespace = "other"),
            row("mqtt_connected", "boolean", "1"),
        )
        assertTrue(carry(emptyList()).isEmpty())
        assertTrue(carry(invalid).isEmpty())
    }

    private fun carry(rows: List<ConfigVault.StateRow>) =
        migrationNoticeHistoryRows(rows, migrationRestore = true, sameDevice = true)

    private fun row(
        key: String,
        type: String,
        value: String?,
        updatedAt: Long = 1L,
        namespace: String = "config",
    ) = ConfigVault.StateRow(namespace, key, type, value, updatedAt)

    private fun noticeRow(updatedAt: Long) =
        ConfigVault.StateRow("config", "migration_notice_connection_seen", "boolean", "1", updatedAt)
}
