package io.panelassistant.android.http

import io.panelassistant.android.config.SettingsRegistry
import io.panelassistant.android.LiveSettingRequestOutcome
import io.panelassistant.android.control.SystemController
import io.panelassistant.android.panelassistant.PanelAssistantManagedSettings
import io.panelassistant.android.platform.ActivityRef
import io.panelassistant.android.platform.SystemEnv
import io.panelassistant.android.util.BorrowedRendererSettings
import io.panelassistant.android.util.RendererPreparationCoordinator
import io.panelassistant.android.util.RendererPreparationState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** A settings write that queued behind another settings commit is admitted again at the commit itself. */
class ManagedSettingsCommitBoundaryTest {
    @Test fun aWriteWhoseSessionEndedWhileQueuedCommitsNothing() = runBlocking {
        check(sessionEndsWhileQueued = true)
    }

    @Test fun aWriteStillAdmittedAtTheCommitIsApplied() = runBlocking {
        check(sessionEndsWhileQueued = false)
    }

    private suspend fun check(sessionEndsWhileQueued: Boolean) = kotlinx.coroutines.coroutineScope {
        PaneldServerHttpFixture().use { fixture ->
            val config = fixture.config
            config.setRaw(requireNotNull(SettingsRegistry.spec("friendly_name")), "Managed settings panel")
            val before = config.voiceWakeWords
            val renderer = renderer()
            val transaction = transaction(fixture, renderer) { _, _ -> LiveSettingRequestOutcome.APPLIED }
            // Another settings commit holds the store while this write queues behind it.
            val holding = CountDownLatch(1)
            val release = CountDownLatch(1)
            val holder = Thread { renderer.transaction { holding.countDown(); release.await(5, TimeUnit.SECONDS) } }
            holder.start()
            holding.await(5, TimeUnit.SECONDS)
            val sessionOpen = java.util.concurrent.atomic.AtomicBoolean(true)
            val write = async(Dispatchers.IO) {
                PanelAssistantManagedSettings.apply(
                    mapOf("voice_wake_words" to """["hey_jarvis"]"""),
                    admit = { sessionOpen.get() },
                    validate = { it },
                    commit = { accepted, admit -> transaction.applyAccepted(accepted, admit = admit) is ApplyAcceptedResult.Applied },
                )
            }
            Thread.sleep(200)
            if (sessionEndsWhileQueued) sessionOpen.set(false)
            release.countDown()
            val code = write.await()
            holder.join(5_000)
            if (sessionEndsWhileQueued) {
                assertEquals("expired", code)
                assertEquals(before, config.voiceWakeWords)
            } else {
                assertNull(code)
                assertEquals("""["hey_jarvis"]""", config.voiceWakeWords)
            }
        }
    }

    /** Enabling the Assist entity in Home Assistant writes voice_enabled over the session (maintainer, 2026-10-15). */
    @Test fun panelAssistantTurnsVoiceOnThroughTheLiveSettingLane() = runBlocking {
        PaneldServerHttpFixture().use { fixture ->
            fixture.config.setRaw(requireNotNull(SettingsRegistry.spec("friendly_name")), "Managed settings panel")
            val applied = mutableListOf<Pair<String, String>>()
            val transaction = transaction(fixture, renderer()) { key, value ->
                applied += key to value
                LiveSettingRequestOutcome.APPLIED
            }
            val caps = io.panelassistant.android.config.Capabilities(
                microphone = io.panelassistant.android.audio.MicrophonePresence.PROVEN,
            )
            val code = PanelAssistantManagedSettings.apply(
                mapOf("voice_enabled" to "true"),
                admit = { true },
                validate = { values ->
                    val raw = io.ktor.http.Parameters.build { values.forEach { (key, value) -> append(key, value) } }
                    (normalizeConfigPostParameters(raw, caps) as? ConfigPostParameters.Ok)
                        ?.values?.let { ok -> ok.names().associateWith { ok[it].orEmpty() } }
                },
                commit = { accepted, admit -> transaction.applyAccepted(accepted, admit = admit) is ApplyAcceptedResult.Applied },
            )
            assertNull(code)
            assertEquals(listOf("voice_enabled" to "true"), applied)
        }
    }

    /** Renaming the panel's device in Home Assistant sends friendly_name over the session; the panel takes it. */
    @Test fun aHomeAssistantRenameBecomesThePanelsChosenName() = runBlocking {
        PaneldServerHttpFixture().use { fixture ->
            val reconfigured = mutableListOf<Set<String>>()
            val transaction = transaction(fixture, renderer(), onReconfigure = { reconfigured += it }) { _, _ -> LiveSettingRequestOutcome.APPLIED }
            fixture.config.setRaw(requireNotNull(SettingsRegistry.spec("friendly_name")), "Old name")
            val code = PanelAssistantManagedSettings.apply(
                mapOf("friendly_name" to "Garden room"),
                admit = { true },
                validate = { values ->
                    val raw = io.ktor.http.Parameters.build { values.forEach { (key, value) -> append(key, value) } }
                    (normalizeConfigPostParameters(raw, io.panelassistant.android.config.Capabilities()) as? ConfigPostParameters.Ok)
                        ?.values?.let { ok -> ok.names().associateWith { ok[it].orEmpty() } }
                },
                commit = { accepted, admit -> transaction.applyAccepted(accepted, admit = admit) is ApplyAcceptedResult.Applied },
            )
            assertNull(code)
            assertEquals("Garden room", fixture.config.chosenFriendlyName)
            assertEquals("Garden room", fixture.config.friendlyName)
            // The service's reconfigure is what carries the name on to Android's device name.
            assertEquals(listOf(setOf("friendly_name")), reconfigured)
        }
    }

    private fun renderer() = RendererPreparationCoordinator(
        builtinPackage = "builtin",
        state = { RendererPreparationState("builtin", "") },
        borrow = { BorrowedRendererSettings("http://ha.test", "", "", 0, "", null) },
        persist = { true },
    )

    private fun transaction(
        fixture: PaneldServerHttpFixture,
        renderer: RendererPreparationCoordinator,
        onReconfigure: (Set<String>) -> Unit = {},
        applySetting: (String, String) -> LiveSettingRequestOutcome,
    ) = AcceptedConfigTransaction(
        config = fixture.config,
        revisions = RevisionStore(fixture.directory),
        rendererPreparation = renderer,
        system = SystemController(object : SystemEnv {
            override val ownPackage = "io.panelassistant.android"
            override fun isInstalled(pkg: String) = false
            override fun launchComponent(pkg: String): String? = null
            override fun homeActivities(): List<ActivityRef> = emptyList()
            override fun defaultHome(): ActivityRef? = null
            override fun directStart(component: String) = false
        }),
        values = ConfigValueProjection(
            config = fixture.config,
            configLiveValues = { emptyMap() },
            renderedLiveValues = { emptyMap() },
            pendingLiveSettings = { emptyMap() },
            stalledLiveSettings = { emptySet() },
            proximityJson = { "{}" },
            powerSafetyJson = { "{}" },
            haAreaCatalogJson = { "{}" },
        ),
        applySetting = applySetting,
        onEntityTargetChanged = {},
        setEntityLearningEnabled = { true },
        effectiveDashboardIsBuiltin = { false },
        requestTameReconcileAfterCommit = { true },
        snapInvalidate = {},
        onReconfigure = onReconfigure,
    )
}
