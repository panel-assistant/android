package io.panelassistant.android.http

import io.ktor.client.request.accept
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.panelassistant.android.Config
import io.panelassistant.android.LiveSettingAuthority
import io.panelassistant.android.config.Capabilities
import io.panelassistant.android.config.SettingsRegistry
import io.panelassistant.android.panelassistant.PanelAssistantCommandResult
import io.panelassistant.android.panelassistant.PanelAssistantReportResult
import io.panelassistant.android.panelassistant.PanelAssistantStateReporter
import io.panelassistant.android.security.LocalApprovalBroker
import io.panelassistant.android.testsupport.PanelRuntimeRig
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The dashboard theme select and the idle-return number (ha-integration #17): a change from Panel Assistant
 * reaches the setting and comes back as the entity's state, and a change on the panel's own pages reaches
 * Panel Assistant at once, through the reporter a session sends `report_state` from.
 */
internal class RuntimeSettingEntitiesTest : PanelRuntimeRig() {
    private val rigs = mutableListOf<Rig>()

    @After fun tearDown() {
        rigs.forEach { it.close() }
    }

    private val timeout = java.util.concurrent.atomic.AtomicInteger(45_000)

    private fun started(configure: (Config) -> Unit = {}): Rig = rig(
        deviceSecure = false,
        screenOffTimeout = io.panelassistant.android.control.ScreenTimeoutController(
            read = timeout::get,
            write = { timeout.set(it); true },
        ),
        configure = { config ->
            config.ensurePanelId { "entity_panel" }
            config.setRaw(requireNotNull(SettingsRegistry.spec("friendly_name")), "Entity panel")
            configure(config)
        },
    ).also { it.start(); rigs += it }

    /** A Panel Assistant session past its full sync; [state] reads what it has reported for a channel. */
    private class Session(rig: Rig) {
        private val reporter = PanelAssistantStateReporter(log = {})
        private val reported = mutableMapOf<String, JSONObject>()
        private var id = 1L

        init {
            rig.core.addStateSink(reporter.bindShape(rig.core::nativeChannelShape))
            assertTrue(runBlocking { rig.core.observeForNativeHello { true } })
            drainStatePump()
            reporter.open(reporter.offer().descriptors)
            repeat(2) { assertTrue("full sync step $it", send()) }
            assertTrue(reporter.fullSyncComplete())
        }

        private fun send(): Boolean {
            val message = reporter.next(id, "entity-session", 0L) ?: return false
            val observations = JSONObject(message).getJSONArray("observations")
            for (i in 0 until observations.length()) observations.getJSONObject(i).let { reported[it.getString("channel")] = it }
            reporter.onResult(PanelAssistantReportResult.Acknowledged(id++, emptyMap()), 0L)
            return true
        }

        /** The channel's latest reported observation once [expected] holds, sending each delta as it comes. */
        fun state(channel: String, expected: (JSONObject) -> Boolean = { true }): JSONObject {
            val deadline = System.nanoTime() + 5_000_000_000L
            while (System.nanoTime() < deadline) {
                drainStatePump()
                while (send()) Unit
                reported[channel]?.takeIf(expected)?.let { return it }
                Thread.sleep(5)
            }
            error("$channel never reported as expected; last ${reported[channel]}")
        }

        fun value(channel: String, expected: Any): JSONObject = state(channel) { it.opt("value") == expected }
    }

    @Test fun panelAssistantChangesTheThemeAndIdleReturnAndSeesThemBack() {
        val rig = started()
        val session = Session(rig)
        assertEquals("follow", session.state("dashboard_theme").get("value"))
        assertEquals(0, session.state("dashboard_idle_return").get("value"))

        assertEquals(PanelAssistantCommandResult.Applied, submitNative(rig, "dashboard_theme", "dark"))
        assertEquals("Dark", rig.config.dashboardTheme)
        session.value("dashboard_theme", "dark")

        assertEquals(PanelAssistantCommandResult.Applied, submitNative(rig, "dashboard_idle_return", "15"))
        assertEquals(15, rig.config.dashboardIdleReturnMin)
        session.value("dashboard_idle_return", 15)
    }

    @Test fun fractionalIdleReturnCommandsAgreeWithConfigureCanonicalSaves() {
        val rig = started()
        val session = Session(rig)
        val spec = requireNotNull(SettingsRegistry.spec("dashboard_idle_return_min"))
        val authority = LiveSettingAuthority(setOf(spec.key))
        val server = routeServer(rig.config) { key, normalized ->
            authority.applyOrQueueOutcome(key, normalized, rig.config.getRaw(spec)) { appliedKey, value, before ->
                rig.commands.applySetting(appliedKey, value, before)
            }
        }
        val native = mutableListOf<String>()
        val configure = mutableListOf<String>()
        testApplication {
            application {
                routing { route("/api/v1") { with(server) { installDirectConfigPostRoute { Capabilities() } } } }
            }
            // The packaged Configure control sends these canonical strings after its change event.
            // The paired browser test exercises that normalization for the same three inputs.
            for ((input, rounded) in listOf("2.4" to "2", "2.5" to "3", "2.6" to "3")) {
                assertEquals(PanelAssistantCommandResult.Applied, submitNative(rig, "dashboard_idle_return", input))
                val stored = rig.config.getRaw(spec)
                assertEquals(stored.toInt(), rig.config.dashboardIdleReturnMin)
                session.value("dashboard_idle_return", stored.toInt())
                native += stored

                val response = client.submitForm("/api/v1/config", Parameters.build { append(spec.key, rounded) }) {
                    accept(ContentType.Application.Json)
                }
                assertEquals(response.bodyAsText(), HttpStatusCode.OK, response.status)
                assertEquals(rounded, rig.config.getRaw(spec))
                session.value("dashboard_idle_return", rounded.toInt())
                configure += rig.config.getRaw(spec)
            }
            // Raw HTTP stays strict: normalization belongs to the typed caller, not integer storage.
            val refused = client.submitForm("/api/v1/config", Parameters.build { append(spec.key, "2.5") }) {
                accept(ContentType.Application.Json)
            }
            assertEquals(HttpStatusCode.BadRequest, refused.status)
            assertTrue(refused.bodyAsText().contains("expected an integer"))
            assertEquals("3", rig.config.getRaw(spec))
        }
        println("idle-return native stored/reported=$native Configure stored/reported=$configure")
        assertEquals(listOf("2", "3", "3"), configure)
        assertEquals(configure, native)
    }

    @Test fun aChangeOnTheSettingsPageReachesPanelAssistantWithoutWaitingForAPass() {
        val rig = started()
        val session = Session(rig)
        val authority = LiveSettingAuthority(setOf("dashboard_theme", "dashboard_idle_return_min"))
        val server = routeServer(rig.config) { key, normalized ->
            val previous = rig.config.getRaw(requireNotNull(SettingsRegistry.spec(key)))
            authority.applyOrQueueOutcome(key, normalized, previous) { appliedKey, value, before ->
                rig.commands.applySetting(appliedKey, value, before)
            }
        }

        testApplication {
            application {
                routing { route("/api/v1") { with(server) { installDirectConfigPostRoute { Capabilities() } } } }
            }
            val response = client.submitForm(
                url = "/api/v1/config",
                formParameters = Parameters.build {
                    append("dashboard_theme", "Light")
                    append("dashboard_idle_return_min", "20")
                },
            ) { accept(ContentType.Application.Json) }
            assertEquals(response.bodyAsText(), HttpStatusCode.OK, response.status)
        }

        assertEquals("Light", rig.config.dashboardTheme)
        session.value("dashboard_theme", "light")
        session.value("dashboard_idle_return", 20)
    }

    /** The select shows the owner's choice; an Ambient flip changes only the scheme it resolves to. */
    @Test fun anAmbientFlipLeavesTheSelectOnAmbient() {
        val rig = started { it.setRaw(requireNotNull(SettingsRegistry.spec("dashboard_theme")), "Ambient") }
        val session = Session(rig)
        session.value("dashboard_theme", "ambient")

        rig.config.setDashboardAmbientDark(true)
        rig.core.reconcileAll()
        assertEquals("ambient", session.state("dashboard_theme").get("value"))
    }

    /** Idle return acts only in the built-in renderer, so another dashboard app leaves the number unavailable. */
    @Test fun idleReturnIsUnavailableUnderAnotherDashboardApp() {
        val rig = started { it.setDashboardPackage("com.example.dashboard") }
        val session = Session(rig)
        assertEquals("unavailable", session.state("dashboard_idle_return").getString("state"))
    }

    /**
     * Neither entity needs approval on the panel in Hardened mode, so neither is a setting the promise keeps off
     * Home Assistant; turning Prevent idle dim off from the same session still asks.
     */
    @Test fun inHardenedModeTheNewEntitiesApplyWithoutAnApprovalWhilePreventIdleDimStillAsks() {
        val rig = started { assertTrue(it.setSecurityMode(Config.SecurityMode.HARDENED)) }
        val broker = LocalApprovalBroker.instance
        val before = broker.pending().map { it.id }.toSet()
        try {
            assertEquals(PanelAssistantCommandResult.Applied, submitNative(rig, "dashboard_theme", "light"))
            assertEquals(PanelAssistantCommandResult.Applied, submitNative(rig, "dashboard_idle_return", "5"))
            assertEquals(emptyList<String>(), broker.pending().map { it.id }.filter { it !in before })

            val reduction = submitNative(rig, "prevent_idle_dim", "OFF")
            assertTrue("$reduction", reduction is PanelAssistantCommandResult.ApprovalPending)
        } finally {
            broker.pending().filter { it.id !in before }.forEach { broker.deny(it.id) }
        }
    }

    /**
     * Prevent idle dim holds Android's screen-off timeout at "never", so the number is unavailable while it is on
     * and follows the timeout both ways once it is off; turning it off stays the approval-gated step.
     */
    @Test fun theScreenOffTimeoutFollowsAndroidBothWaysOnlyWhilePreventIdleDimIsOff() {
        val rig = started()
        val session = Session(rig)
        assertEquals("unavailable", session.state("screen_off_timeout").getString("state"))
        assertTrue(submitNative(rig, "screen_off_timeout", "300") is PanelAssistantCommandResult.Failed)

        assertEquals(PanelAssistantCommandResult.Applied, submitNative(rig, "prevent_idle_dim", "OFF"))
        session.value("screen_off_timeout", timeout.get() / 1_000)

        assertEquals(PanelAssistantCommandResult.Applied, submitNative(rig, "screen_off_timeout", "300"))
        assertEquals(300_000, timeout.get())
        session.value("screen_off_timeout", 300)

        // Changed in Android's own settings, which the service's observer turns into this reconcile.
        timeout.set(900_000)
        rig.core.reconcile("screen_off_timeout")
        session.value("screen_off_timeout", 900)
    }

    @Test fun inHardenedModeTheScreenOffTimeoutAppliesWithoutAnApprovalOncePreventIdleDimIsOff() {
        val rig = started {
            it.setRaw(requireNotNull(SettingsRegistry.spec("prevent_idle_dim")), "false")
            assertTrue(it.setSecurityMode(Config.SecurityMode.HARDENED))
        }
        val broker = LocalApprovalBroker.instance
        val before = broker.pending().map { it.id }.toSet()
        try {
            assertEquals(PanelAssistantCommandResult.Applied, submitNative(rig, "screen_off_timeout", "120"))
            assertEquals(120_000, timeout.get())
            assertEquals(emptyList<String>(), broker.pending().map { it.id }.filter { it !in before })
        } finally {
            broker.pending().filter { it.id !in before }.forEach { broker.deny(it.id) }
        }
    }
}
