package io.panelassistant.android.http

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
            config.setFriendlyName("Managed settings panel")
            val before = config.voiceWakeWords
            val renderer = RendererPreparationCoordinator(
                builtinPackage = "builtin",
                state = { RendererPreparationState("builtin", "") },
                borrow = { BorrowedRendererSettings("http://ha.test", "", "", 0, "", null) },
                persist = { true },
            )
            val transaction = AcceptedConfigTransaction(
                config = config,
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
                    config = config,
                    configLiveValues = { emptyMap() },
                    renderedLiveValues = { emptyMap() },
                    pendingLiveSettings = { emptyMap() },
                    stalledLiveSettings = { emptySet() },
                    proximityJson = { "{}" },
                    powerSafetyJson = { "{}" },
                    haAreaCatalogJson = { "{}" },
                ),
                applySetting = { _, _ -> LiveSettingRequestOutcome.APPLIED },
                onEntityTargetChanged = {},
                setEntityLearningEnabled = { true },
                effectiveDashboardIsBuiltin = { false },
                requestTameReconcileAfterCommit = { true },
                snapInvalidate = {},
                onReconfigure = {},
            )
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
                    commit = { accepted, admit -> transaction.applyAccepted(accepted, admit = admit) == ApplyAcceptedResult.Applied },
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
}
