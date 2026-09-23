package io.github.maxlyth.hapaneld

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File

class LiveSettingHandlerBindingTest {
    private data class Invocation(val handler: String, val arguments: List<Any?>)

    private class RecordingHandlers(private val invocations: MutableList<Invocation>) : LiveSettingHandlers {
        private fun record(handler: String, vararg arguments: Any?) {
            invocations += Invocation(handler, arguments.toList())
        }

        override fun handleWakeOnWave(payload: String) = record("handleWakeOnWave", payload)
        override fun handleAutoSleep(payload: String) = record("handleAutoSleep", payload)
        override fun handleAutoSleepSource(payload: String) = record("handleAutoSleepSource", payload)
        override fun handlePreventIdleDim(payload: String, approvalRequired: Boolean) =
            record("handlePreventIdleDim", payload, approvalRequired)
        override fun handleWatchdog(payload: String) = record("handleWatchdog", payload)
        override fun handleKiosk(payload: String) = record("handleKiosk", payload)
        override fun handleSilenceBootChime(payload: String) = record("handleSilenceBootChime", payload)
        override fun handleAutoBright(payload: String) = record("handleAutoBright", payload)
        override fun handleTouchSound(payload: String) = record("handleTouchSound", payload)
        override fun handleVoiceEnabled(payload: String) = record("handleVoiceEnabled", payload)
        override fun handleNetAdb(payload: String) = record("handleNetAdb", payload)
        override fun handleZigbee(payload: String) = record("handleZigbee", payload)
        override fun handleAutoBrightnessMinimum(payload: String) =
            record("handleAutoBrightnessMinimum", payload)
        override fun handleAutoBrightnessSensitivity(payload: String) =
            record("handleAutoBrightnessSensitivity", payload)
        override fun handleAutoBrightnessHaEntity(payload: String) =
            record("handleAutoBrightnessHaEntity", payload)
        override fun handleCpuGov(payload: String) = record("handleCpuGov", payload)
        override fun handleNavbar(payload: String) = record("handleNavbar", payload)
        override fun handleCompanionAuto(payload: String, approvalRequired: Boolean) =
            record("handleCompanionAuto", payload, approvalRequired)
        override fun handleCompanionChannel(
            payload: String,
            previousValue: String?,
            approvalRequired: Boolean,
        ) = record("handleCompanionChannel", payload, previousValue, approvalRequired)
        override fun handleSelfUpdate(payload: String, approvalRequired: Boolean) =
            record("handleSelfUpdate", payload, approvalRequired)
        override fun handleWebViewAuto(payload: String, approvalRequired: Boolean) =
            record("handleWebViewAuto", payload, approvalRequired)
        override fun handleUpdateChannel(
            payload: String,
            previousValue: String?,
            approvalRequired: Boolean,
        ) = record("handleUpdateChannel", payload, previousValue, approvalRequired)
        override fun handleHomeDashboard(payload: String, previousValue: String?) =
            record("handleHomeDashboard", payload, previousValue)
        override fun handleHaAreaPublishOnly() = record("handleHaAreaPublishOnly")
    }

    @Test fun `every live setting invokes exactly its concrete handler method`() {
        data class Case(
            val key: String,
            val value: String,
            val previous: String? = null,
            val approvalRequired: Boolean = false,
            val expected: Invocation,
        )

        fun invocation(handler: String, vararg arguments: Any?) = Invocation(handler, arguments.toList())
        val cases = listOf(
            Case("wake_on_wave", "true", expected = invocation("handleWakeOnWave", "ON")),
            Case("auto_sleep", "true", expected = invocation("handleAutoSleep", "ON")),
            Case("auto_sleep_source", "panel", expected = invocation("handleAutoSleepSource", "panel")),
            Case(
                "prevent_idle_dim",
                "true",
                expected = invocation("handlePreventIdleDim", "ON", false),
            ),
            Case("watchdog_enabled", "true", expected = invocation("handleWatchdog", "ON")),
            Case("kiosk_lock", "true", expected = invocation("handleKiosk", "ON")),
            Case("silence_boot_chime", "true", expected = invocation("handleSilenceBootChime", "ON")),
            Case("auto_brightness", "true", expected = invocation("handleAutoBright", "ON")),
            Case("touch_sound", "true", expected = invocation("handleTouchSound", "ON")),
            Case("voice_enabled", "true", expected = invocation("handleVoiceEnabled", "ON")),
            Case("network_adb", "true", expected = invocation("handleNetAdb", "ON")),
            Case("zigbee_router", "true", expected = invocation("handleZigbee", "ON")),
            Case(
                "auto_brightness_minimum_percent",
                "23",
                expected = invocation("handleAutoBrightnessMinimum", "23"),
            ),
            Case(
                "auto_brightness_response_percent",
                "67",
                expected = invocation("handleAutoBrightnessSensitivity", "67"),
            ),
            Case(
                "auto_brightness_ha_entity",
                "sensor.office_lux",
                expected = invocation("handleAutoBrightnessHaEntity", "sensor.office_lux"),
            ),
            Case("cpu_governor", "Efficiency", expected = invocation("handleCpuGov", "Efficiency")),
            Case("navbar_mode", "Swipe", expected = invocation("handleNavbar", "Swipe")),
            Case(
                "companion_auto_update",
                "true",
                expected = invocation("handleCompanionAuto", "ON", false),
            ),
            Case(
                "companion_update_channel",
                "prerelease",
                previous = "stable",
                expected = invocation("handleCompanionChannel", "prerelease", "stable", false),
            ),
            Case("self_update", "true", expected = invocation("handleSelfUpdate", "ON", false)),
            Case(
                "webview_auto_update",
                "true",
                expected = invocation("handleWebViewAuto", "ON", false),
            ),
            Case(
                "update_channel",
                "prerelease",
                previous = "stable",
                expected = invocation("handleUpdateChannel", "prerelease", "stable", false),
            ),
            Case(
                "home_dashboard",
                "/dashboard-alpha/0",
                previous = "/lovelace/0",
                expected = invocation("handleHomeDashboard", "/dashboard-alpha/0", "/lovelace/0"),
            ),
            Case("ha_area", "Office", expected = invocation("handleHaAreaPublishOnly")),
        )

        assertEquals(LiveSettingEffectOwner.settingKeys, cases.mapTo(linkedSetOf()) { it.key })
        cases.forEach { case ->
            val invocations = mutableListOf<Invocation>()
            dispatchLiveSetting(
                key = case.key,
                value = case.value,
                previousValue = case.previous,
                sensitiveApprovalRequired = case.approvalRequired,
                handlers = RecordingHandlers(invocations),
            )
            assertEquals(case.key, listOf(case.expected), invocations)
        }
    }

    @Test fun `boolean and approval payload shapes are normalized before invoking owners`() {
        val invocations = mutableListOf<Invocation>()
        val handlers = RecordingHandlers(invocations)

        dispatchLiveSetting("watchdog_enabled", "false", handlers = handlers)
        dispatchLiveSetting(
            "prevent_idle_dim",
            "OFF",
            sensitiveApprovalRequired = true,
            handlers = handlers,
        )
        dispatchLiveSetting(
            "self_update",
            "false",
            sensitiveApprovalRequired = true,
            handlers = handlers,
        )

        assertEquals(
            listOf(
                Invocation("handleWatchdog", listOf("OFF")),
                Invocation("handlePreventIdleDim", listOf("OFF", true)),
                Invocation("handleSelfUpdate", listOf("OFF", true)),
            ),
            invocations,
        )
    }

    @Test fun `invalid numeric shape fails before reporting handler execution`() {
        val invocations = mutableListOf<Invocation>()
        assertThrows(IllegalArgumentException::class.java) {
            dispatchLiveSetting(
                "auto_brightness_minimum_percent",
                "twenty",
                handlers = RecordingHandlers(invocations),
            )
        }
        assertEquals(emptyList<Invocation>(), invocations)
    }

    @Test fun `both production ingress paths use the exhaustive concrete handler mapping`() {
        val source = listOf(
            File("src/main/kotlin/io/github/maxlyth/hapaneld/MqttBridge.kt"),
            File("app/src/main/kotlin/io/github/maxlyth/hapaneld/MqttBridge.kt"),
        ).first { it.isFile }.readText()
        val mqttIngress = source.substring(
            source.indexOf("private fun dispatchCommand"),
            source.indexOf("fun publishScreenOn"),
        )
        val httpIngress = source.substring(
            source.indexOf("internal fun applySetting("),
            source.indexOf("// ---- discovery ----"),
        )

        listOf(mqttIngress, httpIngress).forEach { ingress ->
            assertEquals(1, Regex("dispatchLiveSetting\\(").findAll(ingress).count())
            assertEquals(1, Regex("handlers = this").findAll(ingress).count())
        }
    }

    @Test fun `admitted MQTT topic leaves resolve to their exact registry owner keys`() {
        val panel = "contract-panel"
        val expected = linkedMapOf(
            "cpu_governor" to "cpu_governor",
            "network_adb" to "network_adb",
            "home_dashboard" to "home_dashboard",
            "navbar" to "navbar_mode",
            "wake_on_wave" to "wake_on_wave",
            "auto_sleep" to "auto_sleep",
            "touch_sound" to "touch_sound",
            "watchdog" to "watchdog_enabled",
            "kiosk_lock" to "kiosk_lock",
            "companion_auto_update" to "companion_auto_update",
            "companion_update_channel" to "companion_update_channel",
            "self_update" to "self_update",
            "webview_auto_update" to "webview_auto_update",
            "update_channel" to "update_channel",
            "silence_boot_chime" to "silence_boot_chime",
            "prevent_idle_dim" to "prevent_idle_dim",
            "zigbee_router" to "zigbee_router",
            "auto_brightness" to "auto_brightness",
            "voice_enabled" to "voice_enabled",
        )

        assertEquals(expected, externalMqttLiveSettingOwners)
        expected.forEach { (leaf, key) ->
            assertEquals(key, externalLiveSettingKey(panel, "ha-paneld/$panel/$leaf/set"))
        }
        assertEquals(null, externalLiveSettingKey(panel, "ha-paneld/$panel/not_a_setting/set"))
        assertEquals(null, externalLiveSettingKey(panel, "ha-paneld/other/watchdog/set"))
        assertEquals(null, externalLiveSettingKey(panel, "ha-paneld/$panel/watchdog/state"))
    }

    @Test fun `HTTP-only and publish-only live owners are rejected from external MQTT admission`() {
        val panel = "contract-panel"
        val admitted = externalMqttLiveSettingOwners.values.toSet()
        val rejected = LiveSettingEffectOwner.settingKeys - admitted
        val expectedRejected = setOf(
            "auto_sleep_source",
            "auto_brightness_minimum_percent",
            "auto_brightness_response_percent",
            "auto_brightness_ha_entity",
            "ha_area",
        )

        assertEquals(expectedRejected, rejected)
        rejected.forEach { key ->
            assertEquals(key, null, externalLiveSettingKey(panel, "ha-paneld/$panel/$key/set"))
        }
    }
}
