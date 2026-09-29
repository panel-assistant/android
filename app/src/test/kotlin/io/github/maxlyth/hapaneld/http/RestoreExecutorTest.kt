package io.github.maxlyth.hapaneld.http

import android.content.ContextWrapper
import io.github.maxlyth.hapaneld.DashboardEntityBackupState
import io.github.maxlyth.hapaneld.migration.RestoreAttempt
import io.github.maxlyth.hapaneld.util.InstallProgress
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RestoreExecutorTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun importsPrecedeConfigRearmingAndRemainAfterRollback() = runBlocking {
        val catalog = wakeWordCatalog(folder.newFolder())
        PaneldServerHttpFixture().use { fixture ->
            val ticket = requireNotNull(InstallProgress.start("restore"))
            var notified = false
            var rolledBack = false
            val answers = mutableListOf<Boolean>()
            try {
                val executor = executor(
                    fixture,
                    wakeWordsChanged = {
                        assertEquals(listOf("porch"), catalog.available().map { it.id })
                        notified = true
                    },
                    commit = RestoreConfigCommit { _, _, _, _, publish, _, _ ->
                        assertTrue(notified)
                        assertEquals(listOf("porch"), catalog.available().map { it.id })
                        publish("durable")
                        error("configuration failed after rearming")
                    },
                    rollback = { _, _, _, _ -> rolledBack = true; true },
                )
                executor.execute(
                    RestoreConfigPlan(emptyMap(), emptyList(), emptyList()),
                    null, null, null, null, emptyMap(), emptyList(), false, true,
                    listOf(importedWakeWord("porch")), catalog, ticket, RestoreAttempt { answers += it },
                )
                val result = JSONObject(InstallProgress.json()).getJSONObject("result")
                assertTrue(rolledBack)
                assertEquals("failed", result.getString("status"))
                assertEquals("succeeded", result.getJSONObject("wake_words").getString("status"))
                assertEquals(listOf("porch"), catalog.exportImported().map { it.id })
                assertEquals(listOf(false), answers)
            } finally {
                InstallProgress.finish(ticket, "test cleanup")
            }
        }
    }

    @Test fun refusedModelsMakeSuccessfulConfigPartialAndDoNotCompleteMigration() = runBlocking {
        val catalog = wakeWordCatalog(folder.newFolder(), accepts = false)
        PaneldServerHttpFixture().use { fixture ->
            val ticket = requireNotNull(InstallProgress.start("restore"))
            var committed = false
            val answers = mutableListOf<Boolean>()
            try {
                val executor = executor(
                    fixture,
                    commit = RestoreConfigCommit { _, _, _, _, publish, _, _ ->
                        committed = true
                        publish("durable")
                        1
                    },
                    rollback = { _, _, _, _ -> error("partial import does not roll back configuration") },
                )
                executor.execute(
                    RestoreConfigPlan(emptyMap(), emptyList(), emptyList()),
                    null, null, null, null, emptyMap(), emptyList(), false, true,
                    listOf(importedWakeWord("porch")), catalog, ticket, RestoreAttempt { answers += it },
                )
                val status = JSONObject(InstallProgress.json())
                val result = status.getJSONObject("result")
                assertTrue(committed)
                assertEquals("partial", result.getString("status"))
                assertEquals("partial", result.getJSONObject("wake_words").getString("status"))
                assertEquals(0, result.getJSONObject("wake_words").getInt("items"))
                assertTrue(status.getString("message").contains("1 wake word not restored (porch)"))
                assertEquals(listOf(false), answers)
                assertTrue(catalog.exportImported().isEmpty())
            } finally {
                InstallProgress.finish(ticket, "test cleanup")
            }
        }
    }

    @Test fun rollbackUsesLatestDurableRevisionAndSameTicket() = runBlocking {
        for (rollbackSucceeds in listOf(true, false)) {
            PaneldServerHttpFixture().use { fixture ->
                val ticket = requireNotNull(InstallProgress.start("restore"))
                val before = mapOf("panel_id" to "prior-panel")
                var rollbackCount = 0
                val answers = mutableListOf<Boolean>()
                try {
                    val executor = executor(
                        fixture,
                        currentValues = { before },
                        commit = RestoreConfigCommit { _, _, _, receivedTicket, publish, _, _ ->
                            assertSame(ticket, receivedTicket)
                            publish("first-durable-revision")
                            publish("latest-durable-revision")
                            error("failure after live settings were persisted")
                        },
                        rollback = { values, _, expected, receivedTicket ->
                            assertEquals(before, values)
                            assertEquals("latest-durable-revision", expected)
                            assertSame(ticket, receivedTicket)
                            rollbackCount++
                            rollbackSucceeds
                        },
                    )
                    executor.execute(
                        RestoreConfigPlan(mapOf("panel_id" to "new-panel"), emptyList(), emptyList()),
                        null, null, null, null, emptyMap(), emptyList(), false, false,
                        emptyList(), null,
                        ticket, RestoreAttempt { answers += it },
                    )
                    val result = JSONObject(InstallProgress.json()).getJSONObject("result")
                    assertEquals(1, rollbackCount)
                    assertEquals(if (rollbackSucceeds) "failed" else "partial", result.getString("status"))
                    assertEquals(
                        if (rollbackSucceeds) "rolled_back" else "rollback_failed",
                        result.getJSONObject("config").getString("status"),
                    )
                    assertEquals(listOf(false), answers)
                    assertFalse(InstallProgress.running)
                } finally {
                    InstallProgress.finish(ticket, "test cleanup")
                }
            }
        }
    }

    @Test fun incompleteArchiveReportsItsMissingStateAfterSuccessfulApply() = runBlocking {
        PaneldServerHttpFixture().use { fixture ->
            val ticket = requireNotNull(InstallProgress.start("restore"))
            val answers = mutableListOf<Boolean>()
            var committed = false
            try {
                val executor = executor(
                    fixture,
                    commit = RestoreConfigCommit { values, _, _, receivedTicket, publish, _, afterApply ->
                        assertSame(ticket, receivedTicket)
                        committed = true
                        publish("committed")
                        afterApply()
                        values.size
                    },
                    rollback = { _, _, _, _ -> error("successful restore must not roll back") },
                )
                executor.execute(
                    RestoreConfigPlan(mapOf("panel_id" to "new-panel"), emptyList(), emptyList()),
                    null, null, null, null, emptyMap(), emptyList(), true, false,
                    emptyList(), null,
                    ticket, RestoreAttempt { answers += it },
                )
                val status = JSONObject(InstallProgress.json())
                assertEquals(true, committed)
                assertEquals("succeeded", status.getJSONObject("result").getString("status"))
                assertEquals("Restore completed; this backup carried no panel state", status.getString("message"))
                assertEquals(listOf(true), answers)
                assertFalse(InstallProgress.running)
            } finally {
                InstallProgress.finish(ticket, "test cleanup")
            }
        }
    }

    private fun executor(
        fixture: PaneldServerHttpFixture,
        currentValues: () -> Map<String, String> = { emptyMap() },
        wakeWordsChanged: () -> Unit = { error("no successful wake word import") },
        commit: RestoreConfigCommit,
        rollback: suspend (Map<String, String>, DashboardEntityBackupState, String, InstallProgress.Ticket) -> Boolean,
    ) = RestoreExecutor(
        appContext = object : ContextWrapper(null) {},
        config = fixture.config,
        currentValues = currentValues,
        revisionValues = { values, _ -> values },
        commitConfig = commit,
        rollbackConfig = rollback,
        restoreCompanion = { error("no Companion payload") },
        reconcileAfterCompanionRestore = {},
        profileAdmin = null,
        onProfileRestart = { error("no profile payload") },
        onProfileRestartAbort = { error("no profile payload") },
        onDurableStateRestored = { error("no state payload") },
        onWakeWordsChanged = wakeWordsChanged,
    )
}
