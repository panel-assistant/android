package io.panelassistant.android.http

import android.content.ContextWrapper
import io.panelassistant.android.backup.CompanionRestore
import io.panelassistant.android.config.Capabilities
import io.panelassistant.android.device.profile.ProfileActivationPhase
import io.panelassistant.android.device.profile.ProfileActivationState
import io.panelassistant.android.device.profile.ProfileAdmin
import io.panelassistant.android.device.profile.ProfileBackup
import io.panelassistant.android.device.profile.ProfileBackupDecodeResult
import io.panelassistant.android.device.profile.ProfileBackupRestorePlan
import io.panelassistant.android.device.profile.ProfileSelection
import io.panelassistant.android.device.profile.ProfileStatus
import io.panelassistant.android.DashboardEntityBackupState
import io.panelassistant.android.util.InstallProgress
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RestoreExecutorTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun aFailedOrdinaryStateWriteIsPartialAndKeepsCommittedConfiguration() = runBlocking {
        PaneldServerHttpFixture().use { fixture ->
            val ticket = requireNotNull(InstallProgress.start("restore"))
            var attempts = 0
            val executor = executor(
                fixture,
                applyState = { attempts++; 0 },
                commit = RestoreConfigCommit { _, _, _, _, publish, _, afterApply ->
                    publish("durable")
                    afterApply()
                    1
                },
                rollback = { _, _, _, _ -> error("state loss must not undo committed configuration") },
            )
            executor.execute(
                RestoreConfigPlan(emptyMap(), emptyList(), emptyList()),
                null, null, null, null,
                listOf(io.panelassistant.android.persistence.ConfigVault.StateRow("controller-state", "saved", "int", "4", 1L)),
                false, emptyList(), null, ticket,
            )
            val result = JSONObject(InstallProgress.json()).getJSONObject("result")
            assertEquals(1, attempts)
            assertEquals("partial", result.getString("status"))
            assertEquals("succeeded", result.getJSONObject("config").getString("status"))
            assertEquals("partial", result.getJSONObject("state").getString("status"))
            assertEquals("admitted=1; skipped=0; failed=1", result.getJSONObject("state").getString("detail"))
        }
    }

    @Test fun importsPrecedeConfigRearmingAndRemainAfterRollback() = runBlocking {
        val catalog = wakeWordCatalog(folder.newFolder())
        PaneldServerHttpFixture().use { fixture ->
            val ticket = requireNotNull(InstallProgress.start("restore"))
            var notified = false
            var rolledBack = false
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
                    null, null, null, null, emptyList(), false,
                    listOf(importedWakeWord("porch")), catalog, ticket,
                )
                val result = JSONObject(InstallProgress.json()).getJSONObject("result")
                assertTrue(rolledBack)
                assertEquals("partial", result.getString("status"))
                assertEquals(1, result.getJSONObject("wake_words").getInt("items"))
                assertEquals("succeeded", result.getJSONObject("wake_words").getString("status"))
                assertEquals(listOf("porch"), catalog.exportImported().map { it.id })
            } finally {
                InstallProgress.finish(ticket, "test cleanup")
            }
        }
    }

    @Test fun refusedModelsMakeSuccessfulConfigPartial() = runBlocking {
        val catalog = wakeWordCatalog(folder.newFolder(), accepts = false)
        PaneldServerHttpFixture().use { fixture ->
            val ticket = requireNotNull(InstallProgress.start("restore"))
            var committed = false
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
                    null, null, null, null, emptyList(), false,
                    listOf(importedWakeWord("porch")), catalog, ticket,
                )
                val status = JSONObject(InstallProgress.json())
                val result = status.getJSONObject("result")
                assertTrue(committed)
                assertEquals("partial", result.getString("status"))
                assertEquals("partial", result.getJSONObject("wake_words").getString("status"))
                assertEquals(0, result.getJSONObject("wake_words").getInt("items"))
                assertTrue(status.getString("message").contains("1 wake word not restored (porch)"))
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
                        null, null, null, null, emptyList(), false,
                        emptyList(), null,
                        ticket,
                    )
                    val result = JSONObject(InstallProgress.json()).getJSONObject("result")
                    assertEquals(1, rollbackCount)
                    assertEquals(if (rollbackSucceeds) "failed" else "partial", result.getString("status"))
                    assertEquals(
                        if (rollbackSucceeds) "rolled_back" else "rollback_failed",
                        result.getJSONObject("config").getString("status"),
                    )
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
                    null, null, null, null, emptyList(), true,
                    emptyList(), null,
                    ticket,
                )
                val status = JSONObject(InstallProgress.json())
                assertEquals(true, committed)
                assertEquals("succeeded", status.getJSONObject("result").getString("status"))
                assertEquals("Restore completed; this backup carried no panel state", status.getString("message"))
                assertFalse(InstallProgress.running)
            } finally {
                InstallProgress.finish(ticket, "test cleanup")
            }
        }
    }

    @Test fun executionRechecksAvailabilityInsteadOfTrustingThePreview() = runBlocking {
        PaneldServerHttpFixture().use { fixture ->
            var caps = Capabilities(networkAdb = true)
            val requested = RestoreConfigPlan(mapOf("panel_id" to "restored", "network_adb" to "true"), emptyList(), emptyList())
            val preview = requested.withAvailableLiveSettings(caps)
            assertTrue(preview.values.containsKey("network_adb"))
            caps = Capabilities(networkAdb = false)
            val ticket = requireNotNull(InstallProgress.start("restore"))
            try {
                var applied: Map<String, String>? = null
                executor(
                    fixture, capabilities = { caps },
                    commit = RestoreConfigCommit { values, _, _, _, publish, _, _ ->
                        applied = values
                        publish("durable")
                        values.size
                    },
                    rollback = { _, _, _, _ -> error("known skips do not roll back supported values") },
                ).execute(
                    preview, null, null, null, null, emptyList(), false,
                    emptyList(), null, ticket,
                )
                assertEquals(mapOf("panel_id" to "restored"), applied)
                val status = JSONObject(InstallProgress.json())
                val result = status.getJSONObject("result")
                assertEquals("partial", result.getString("status"))
                assertEquals("partial", result.getJSONObject("config").getString("status"))
                assertTrue(result.getJSONObject("config").getString("detail").contains("network_adb"))
                assertEquals("restore-partial", status.getJSONObject("presentation").getString("code"))
            } finally {
                InstallProgress.finish(ticket, "test cleanup")
            }
        }
    }

    @Test fun initiallyUnavailableLiveValuesAreNotRetriedWhenSupportedApplyRollsBack() = runBlocking {
        PaneldServerHttpFixture().use { fixture ->
            val ticket = requireNotNull(InstallProgress.start("restore"))
            var rollbackValues: Map<String, String>? = null
            try {
                executor(
                    fixture,
                    capabilities = { Capabilities(networkAdb = false) },
                    currentValues = { mapOf("panel_id" to "before", "network_adb" to "false") },
                    commit = RestoreConfigCommit { _, _, _, _, publish, _, _ ->
                        publish("durable")
                        error("supported setting failed")
                    },
                    rollback = { values, _, _, _ -> rollbackValues = values; true },
                ).execute(
                    RestoreConfigPlan(mapOf("panel_id" to "restored", "network_adb" to "true"), emptyList(), emptyList()),
                    null, null, null, null, emptyList(), false,
                    emptyList(), null, ticket,
                )
                assertEquals(mapOf("panel_id" to "before"), rollbackValues)
                val result = JSONObject(InstallProgress.json()).getJSONObject("result")
                assertEquals("failed", result.getString("status"))
                assertEquals("rolled_back", result.getJSONObject("config").getString("status"))
            } finally {
                InstallProgress.finish(ticket, "test cleanup")
            }
        }
    }

    @Test fun privilegeLostDuringApplyDoesNotHideAPreviouslySupportedRollbackFailure() = runBlocking {
        PaneldServerHttpFixture().use { fixture ->
            var caps = Capabilities(networkAdb = true)
            val ticket = requireNotNull(InstallProgress.start("restore"))
            var rollbackValues: Map<String, String>? = null
            try {
                executor(
                    fixture, capabilities = { caps },
                    currentValues = { mapOf("panel_id" to "before", "network_adb" to "false") },
                    commit = RestoreConfigCommit { values, _, _, _, publish, _, _ ->
                        assertEquals("true", values["network_adb"])
                        publish("durable")
                        caps = Capabilities(networkAdb = false)
                        error("network ADB apply failed after privilege was lost")
                    },
                    rollback = { values, _, _, _ -> rollbackValues = values; false },
                ).execute(
                    RestoreConfigPlan(mapOf("network_adb" to "true"), emptyList(), emptyList()),
                    null, null, null, null, emptyList(), false,
                    emptyList(), null, ticket,
                )
                assertNotNull("rollback must run", rollbackValues)
                assertEquals("false", requireNotNull(rollbackValues)["network_adb"])
                val result = JSONObject(InstallProgress.json()).getJSONObject("result")
                assertEquals("partial", result.getString("status"))
                assertEquals("rollback_failed", result.getJSONObject("config").getString("status"))
            } finally {
                InstallProgress.finish(ticket, "test cleanup")
            }
        }
    }

    @Test fun companionApplyFailureRollsBackConfigAndIsNotReportedAsSuccess() = runBlocking {
        PaneldServerHttpFixture().use { fixture ->
            val ticket = requireNotNull(InstallProgress.start("restore"))
            var rolledBack = false
            val database = folder.newFile("companion.db").apply { writeText("db") }
            try {
                executor(
                    fixture,
                    commit = RestoreConfigCommit { values, _, _, _, publish, afterCommitBeforeRenderer, _ ->
                        publish("durable")
                        afterCommitBeforeRenderer(RendererConfigEffects(false, false, false, null), values.size, "durable")
                        values.size
                    },
                    rollback = { _, _, _, _ -> rolledBack = true; true },
                    restoreCompanion = {
                        CompanionApplyResult(false, InstallProgress.ComponentResult(InstallProgress.Outcome.FAILED, 0))
                    },
                ).execute(
                    RestoreConfigPlan(emptyMap(), emptyList(), emptyList()),
                    null, null, null,
                    CompanionRestore.Plan(
                        "io.homeassistant.companion.android.minimal",
                        listOf(CompanionRestore.FilePayload(CompanionRestore.DATABASE_FILE, database, deleteOnClose = false)),
                    ),
                    emptyList(), false, emptyList(), null, ticket,
                )
                val result = JSONObject(InstallProgress.json()).getJSONObject("result")
                assertTrue(rolledBack)
                assertEquals("failed", result.getString("status"))
                assertEquals("rolled_back", result.getJSONObject("config").getString("status"))
            } finally {
                InstallProgress.finish(ticket, "test cleanup")
            }
        }
    }

    @Test fun rejectedProfileCatalogRollsBackConfigAndReportsFailure() = runBlocking {
        PaneldServerHttpFixture().use { fixture ->
            val ticket = requireNotNull(InstallProgress.start("restore"))
            var rolledBack = false
            val status = ProfileStatus(1, ProfileSelection.Auto, null, ProfileActivationState(ProfileActivationPhase.ACTIVE))
            // The interface's own restoreBackup answers REJECTED.
            val admin = object : ProfileAdmin {
                override fun schema() = error("unused")
                override fun drivers() = error("unused")
                override fun status() = status
                override fun list() = error("unused")
                override fun preview(rawYaml: String) = error("unused")
                override fun importProfile(rawYaml: String, previewToken: String) = error("unused")
                override fun exportProfile(ref: io.panelassistant.android.device.profile.ProfileRef) = error("unused")
                override fun select(selection: ProfileSelection, expectedCatalogRevision: Long) = error("unused")
                override fun rollbackToLastKnownGood(expectedCatalogRevision: Long) = error("unused")
                override fun deleteProfile(ref: io.panelassistant.android.device.profile.ProfileRef, expectedCatalogRevision: Long) = error("unused")
            }
            try {
                executor(
                    fixture,
                    commit = RestoreConfigCommit { values, _, _, _, publish, _, afterApply ->
                        publish("durable")
                        afterApply()
                        values.size
                    },
                    rollback = { _, _, _, _ -> rolledBack = true; true },
                    profileAdmin = admin,
                ).execute(
                    RestoreConfigPlan(emptyMap(), emptyList(), emptyList()),
                    null,
                    ProfileBackupDecodeResult(ProfileBackup(revisions = emptyList(), selection = ProfileSelection.Auto, active = null, lastKnownGood = null), emptyList()),
                    ProfileBackupRestorePlan(status, 1, true, emptyList(), emptyList(), emptyList(), false),
                    null, emptyList(), false, emptyList(), null, ticket,
                )
                val result = JSONObject(InstallProgress.json()).getJSONObject("result")
                assertTrue(rolledBack)
                assertEquals("failed", result.getString("status"))
                assertEquals("rolled_back", result.getJSONObject("config").getString("status"))
            } finally {
                InstallProgress.finish(ticket, "test cleanup")
            }
        }
    }

    private fun executor(
        fixture: PaneldServerHttpFixture,
        currentValues: () -> Map<String, String> = { emptyMap() },
        capabilities: () -> Capabilities = { Capabilities(networkAdb = true) },
        wakeWordsChanged: () -> Unit = { error("no successful wake word import") },
        commit: RestoreConfigCommit,
        rollback: suspend (Map<String, String>, DashboardEntityBackupState, String, InstallProgress.Ticket) -> Boolean,
        applyState: (List<io.panelassistant.android.persistence.ConfigVault.StateRow>) -> Int = { error("no state payload") },
        restoreCompanion: (CompanionRestore.Plan) -> CompanionApplyResult = { error("no Companion payload") },
        profileAdmin: ProfileAdmin? = null,
    ) = RestoreExecutor(
        appContext = object : ContextWrapper(null) {},
        config = fixture.config,
        currentValues = currentValues,
        capabilities = capabilities,
        revisionValues = { values, _ -> values },
        commitConfig = commit,
        rollbackConfig = rollback,
        restoreCompanion = restoreCompanion,
        reconcileAfterCompanionRestore = {},
        profileAdmin = profileAdmin,
        onProfileRestart = { error("no profile payload") },
        onProfileRestartAbort = { error("no profile payload") },
        onDurableStateRestored = { error("no state payload") },
        onWakeWordsChanged = wakeWordsChanged,
        applyState = applyState,
    )
}
