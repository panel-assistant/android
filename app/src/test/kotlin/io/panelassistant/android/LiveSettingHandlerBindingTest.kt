package io.panelassistant.android

import io.panelassistant.android.config.ChannelOption
import io.panelassistant.android.config.SettingsRegistry
import io.panelassistant.android.panelassistant.CommandValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LiveSettingHandlerBindingTest {
    private data class Invocation(val handler: String, val arguments: List<Any?>)

    private class RecordingHandlers(private val invocations: MutableList<Invocation>) : LiveSettingHandlers {
        private fun record(handler: String, vararg arguments: Any?) {
            invocations += Invocation(handler, arguments.toList())
        }

        override fun handleWakeOnWave(on: Boolean) = record("handleWakeOnWave", on)
        override fun handleAutoSleep(on: Boolean) = record("handleAutoSleep", on)
        override fun handlePresenceMode(option: ChannelOption) = record("handlePresenceMode", option)
        override fun handlePreventIdleDim(on: Boolean, approvalRequired: Boolean) =
            record("handlePreventIdleDim", on, approvalRequired)
        override fun handleWatchdog(on: Boolean) = record("handleWatchdog", on)
        override fun handleKiosk(on: Boolean) = record("handleKiosk", on)
        override fun handleSilenceBootChime(on: Boolean) = record("handleSilenceBootChime", on)
        override fun handleAutoBright(on: Boolean) = record("handleAutoBright", on)
        override fun handleTouchSound(on: Boolean) = record("handleTouchSound", on)
        override fun handleVoiceEnabled(on: Boolean) = record("handleVoiceEnabled", on)
        override fun handleNetAdb(on: Boolean) = record("handleNetAdb", on)
        override fun handleZigbee(on: Boolean) = record("handleZigbee", on)
        override fun handleAutoBrightnessMaximum(percent: Int) = record("handleAutoBrightnessMaximum", percent)
        override fun handleAutoBrightnessMinimum(percent: Int) = record("handleAutoBrightnessMinimum", percent)
        override fun handleAutoBrightnessSensitivity(percent: Int) =
            record("handleAutoBrightnessSensitivity", percent)
        override fun handleAutoBrightnessHaEntity(entityId: String) =
            record("handleAutoBrightnessHaEntity", entityId)
        override fun handleCpuGov(option: ChannelOption) = record("handleCpuGov", option)
        override fun handleNavbar(option: ChannelOption) = record("handleNavbar", option)
        override fun handleHomeDashboard(payload: String, previousValue: String?) =
            record("handleHomeDashboard", payload, previousValue)
        override fun handleHaAreaPublishOnly() = record("handleHaAreaPublishOnly")
        override fun handleDashboardTheme(option: ChannelOption) = record("handleDashboardTheme", option)
        override fun handleIdleReturn(minutes: Int) = record("handleIdleReturn", minutes)
    }

    /** The typed value the HTTP config route hands the dispatcher for a normalized [value]. */
    private fun normalized(key: String, value: String): CommandValue =
        liveSettingValue(requireNotNull(SettingsRegistry.spec(key)) { key }, value)

    @Test fun `every live setting invokes exactly its concrete handler method with its typed value`() {
        data class Case(
            val key: String,
            val value: String,
            val previous: String? = null,
            val approvalRequired: Boolean = false,
            val expected: Invocation,
        )

        fun invocation(handler: String, vararg arguments: Any?) = Invocation(handler, arguments.toList())
        val cases = listOf(
            Case("wake_on_wave", "true", expected = invocation("handleWakeOnWave", true)),
            Case("auto_sleep", "true", expected = invocation("handleAutoSleep", true)),
            Case(
                "presence_mode",
                "home_assistant",
                expected = invocation("handlePresenceMode", ChannelOption("home_assistant", "Home Assistant device")),
            ),
            Case(
                "prevent_idle_dim",
                "true",
                expected = invocation("handlePreventIdleDim", true, false),
            ),
            Case("watchdog_enabled", "true", expected = invocation("handleWatchdog", true)),
            Case("kiosk_lock", "true", expected = invocation("handleKiosk", true)),
            Case("silence_boot_chime", "true", expected = invocation("handleSilenceBootChime", true)),
            Case("auto_brightness", "true", expected = invocation("handleAutoBright", true)),
            Case("touch_sound", "false", expected = invocation("handleTouchSound", false)),
            Case("voice_enabled", "true", expected = invocation("handleVoiceEnabled", true)),
            Case("network_adb", "true", expected = invocation("handleNetAdb", true)),
            Case("zigbee_router", "true", expected = invocation("handleZigbee", true)),
            Case(
                "auto_brightness_minimum_percent",
                "23",
                expected = invocation("handleAutoBrightnessMinimum", 23),
            ),
            Case(
                "auto_brightness_maximum_percent",
                "73",
                expected = invocation("handleAutoBrightnessMaximum", 73),
            ),
            Case(
                "auto_brightness_response_percent",
                "67",
                expected = invocation("handleAutoBrightnessSensitivity", 67),
            ),
            Case(
                "auto_brightness_ha_entity",
                "sensor.office_lux",
                expected = invocation("handleAutoBrightnessHaEntity", "sensor.office_lux"),
            ),
            Case(
                "cpu_governor",
                "Efficiency",
                expected = invocation("handleCpuGov", ChannelOption("efficiency", "Efficiency")),
            ),
            // A stored label in another case still names its option, as the navbar controller always read it.
            Case(
                "navbar_mode",
                "swipe reveal",
                expected = invocation("handleNavbar", ChannelOption("swipe_reveal", "Swipe reveal")),
            ),
            Case(
                "home_dashboard",
                "/dashboard-alpha/0",
                previous = "/lovelace/0",
                expected = invocation("handleHomeDashboard", "/dashboard-alpha/0", "/lovelace/0"),
            ),
            Case("ha_area", "Office", expected = invocation("handleHaAreaPublishOnly")),
            Case(
                "dashboard_theme",
                "Ambient",
                expected = invocation("handleDashboardTheme", ChannelOption("ambient", "Ambient")),
            ),
            Case("dashboard_idle_return_min", "15", expected = invocation("handleIdleReturn", 15)),
        )

        assertEquals(LiveSettingEffectOwner.settingKeys, cases.mapTo(linkedSetOf()) { it.key })
        cases.forEach { case ->
            val invocations = mutableListOf<Invocation>()
            dispatchLiveSetting(
                key = case.key,
                value = normalized(case.key, case.value),
                previousValue = case.previous,
                sensitiveApprovalRequired = case.approvalRequired,
                handlers = RecordingHandlers(invocations),
            )
            assertEquals(case.key, listOf(case.expected), invocations)
        }
    }

    @Test fun `a remote prevent_idle_dim keeps its approval requirement`() {
        val invocations = mutableListOf<Invocation>()
        dispatchLiveSetting(
            "prevent_idle_dim",
            CommandValue.Switch(false),
            sensitiveApprovalRequired = true,
            handlers = RecordingHandlers(invocations),
        )
        assertEquals(listOf(Invocation("handlePreventIdleDim", listOf(false, true))), invocations)
    }

    @Test fun `a value of the wrong shape fails before any handler runs`() {
        val invocations = mutableListOf<Invocation>()
        assertThrows(IllegalArgumentException::class.java) { normalized("auto_brightness_minimum_percent", "twenty") }
        assertThrows(IllegalArgumentException::class.java) { normalized("touch_sound", "maybe") }
        assertThrows(IllegalArgumentException::class.java) { normalized("navbar_mode", "Sideways") }
        assertThrows(IllegalArgumentException::class.java) {
            dispatchLiveSetting(
                "auto_brightness_minimum_percent",
                CommandValue.Text("twenty"),
                handlers = RecordingHandlers(invocations),
            )
        }
        assertEquals(emptyList<Invocation>(), invocations)
    }

    @Test fun `admitted external command channels resolve to their exact registry owner keys`() {
        val panel = "contract-panel"
        val expected = linkedMapOf(
            "cpu_governor" to "cpu_governor",
            "network_adb" to "network_adb",
            "home_dashboard" to "home_dashboard",
            "navbar" to "navbar_mode",
            "presence" to "presence_mode",
            "wake_on_wave" to "wake_on_wave",
            "auto_sleep" to "auto_sleep",
            "touch_sound" to "touch_sound",
            "watchdog" to "watchdog_enabled",
            "kiosk_lock" to "kiosk_lock",
            "silence_boot_chime" to "silence_boot_chime",
            "prevent_idle_dim" to "prevent_idle_dim",
            "zigbee_router" to "zigbee_router",
            "auto_brightness" to "auto_brightness",
            "dashboard_theme" to "dashboard_theme",
            "dashboard_idle_return" to "dashboard_idle_return_min",
        )

        assertEquals(expected, externalLiveSettingOwners)
        expected.forEach { (channel, key) -> assertEquals(key, externalLiveSettingKey(channel)) }
        assertEquals(null, externalLiveSettingKey("not_a_setting"))
    }

    @Test fun `retired auto-update settings have no live command owner`() {
        val invocations = mutableListOf<Invocation>()
        listOf("self_update", "update_channel", "companion_auto_update", "companion_update_channel", "webview_auto_update").forEach { key ->
            assertEquals(key, null, externalLiveSettingKey(key))
            assertThrows(IllegalStateException::class.java) {
                dispatchLiveSetting(key, CommandValue.Switch(true), handlers = RecordingHandlers(invocations))
            }
        }
        assertEquals(emptyList<Invocation>(), invocations)
    }

    @Test fun `HTTP-only and publish-only live owners are rejected from external command admission`() {
        val panel = "contract-panel"
        val admitted = externalLiveSettingOwners.values.toSet()
        val rejected = LiveSettingEffectOwner.settingKeys - admitted
        val expectedRejected = setOf(
            // The voice assistant is a Home Assistant satellite, never an external command switch.
            "voice_enabled",
            "auto_brightness_minimum_percent",
            "auto_brightness_maximum_percent",
            "auto_brightness_response_percent",
            "auto_brightness_ha_entity",
            "ha_area",
        )

        assertEquals(expectedRejected, rejected)
        rejected.forEach { key ->
            assertEquals(key, null, externalLiveSettingKey(key))
        }
    }
}
