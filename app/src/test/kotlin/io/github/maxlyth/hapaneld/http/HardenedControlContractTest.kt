package io.github.maxlyth.hapaneld.http

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HardenedControlContractTest {
    // Source-text reason: pins MqttBridge.kt, deleted with the MQTT removal.
    private val mqtt by lazy {
        listOf(
            File("src/main/kotlin/io/github/maxlyth/hapaneld/MqttBridge.kt"),
            File("app/src/main/kotlin/io/github/maxlyth/hapaneld/MqttBridge.kt"),
        ).first { it.isFile }.readText()
    }

    // Source-text reason: user-visible English labels for the security modes and remote-ADB refusals.
    private val englishStrings by lazy {
        listOf(
            File("src/main/res/values/strings.xml"),
            File("app/src/main/res/values/strings.xml"),
        ).first { it.isFile }.readText()
    }

    private fun englishString(name: String): String {
        val marker = "<string name=\"$name\">"
        require(englishStrings.contains(marker)) { "missing English string resource $name" }
        return englishStrings.substringAfter(marker).substringBefore("</string>")
    }

    @Test fun mqttExplicitInstallsAndRebootRemainApprovalGated() {
        val dispatch = mqtt.substring(mqtt.indexOf("private fun dispatchCommand"), mqtt.indexOf("fun publishScreenOn"))
        assertTrue(dispatch.contains("cmdUpdateCompanion ->"))
        assertTrue(dispatch.contains("cmdUpdatePaneld ->"))
        assertTrue(dispatch.contains("cmdReboot ->"))
        assertTrue(dispatch.contains("SensitiveOperation.APK_INSTALL"))
        assertTrue(dispatch.contains("SensitiveOperation.DEVICE_REBOOT"))

        // Hardened mode publishes a closed list of operations it protects, and a dashboard reload is on
        // it. The MQTT arm shipped ungated while the HTTP route was gated, so a documented protection
        // was absent on the transport an automation actually uses. Approval must precede the reload.
        val reloadArm = dispatch.substring(dispatch.indexOf("cmdReload ->"), dispatch.indexOf("cmdReboot ->"))
        assertTrue(reloadArm.contains("SensitiveOperation.DASHBOARD_RELOAD"))
        assertTrue(reloadArm.indexOf("authorizeRemoteSensitive(") < reloadArm.indexOf("system.reloadDashboard("))

        val serviceApply = mqtt.substring(mqtt.indexOf("internal fun applySetting"), mqtt.indexOf("// ---- discovery ----"))
        assertTrue(serviceApply.contains("sensitiveApprovalRequired = false"))
        assertTrue(serviceApply.contains("dispatchLiveSetting("))
        assertTrue(serviceApply.contains("handlers = this"))
        assertFalse(serviceApply.contains("\"mqtt\""))
    }

    @Test fun networkAdbCannotBeEnabledOverMqttUnderHardenedMode() {
        val handler = mqtt.substring(mqtt.indexOf("override fun handleNetAdb"), mqtt.indexOf("private fun authorizeRemoteSensitive"))
        assertTrue(handler.contains("on && config.hardenedSecurityEnabled"))
        assertFalse(handler.contains("LocalApprovalBroker"))
    }

    @Test fun mqttPowerSafetyReductionsCannotBypassApproval() {
        val handler = mqtt.substring(
            mqtt.indexOf("override fun handlePreventIdleDim"),
            mqtt.indexOf("override fun handleTouchSound"),
        )
        assertTrue(handler.contains("PowerSafetyMutationPolicy.parseGuardSwitch(payload)"))
        assertTrue(handler.contains("SensitiveOperation.POWER_CONFIGURATION"))
        assertTrue(handler.contains("\"prevent_idle_dim\\u0000${'$'}payload\""))
        assertTrue(handler.indexOf("authorizeRemoteSensitive(") < handler.indexOf("config.setPreventIdleDim(on)"))

        val dispatch = mqtt.substring(
            mqtt.indexOf("internal fun dispatchLiveSetting"),
            mqtt.indexOf("internal fun liveSettingApplyResult"),
        )
        assertTrue(dispatch.contains("if (sensitiveApprovalRequired) value else onOff"))
    }

    @Test fun securityModeAndRemoteAdbLabelCatalogueContract() {
        listOf("unable_verify_remote_adb_detail", "turn_off_remote_adb_first_detail").forEach { name ->
            val message = englishString(name)
            assertTrue(message.contains("classic network ADB"))
            assertTrue(message.contains("Android Wireless debugging"))
        }
        assertTrue(englishString("security_mode_relaxed_summary").startsWith("Relaxed mode is on."))
        assertTrue(englishString("use_relaxed_mode") == "Use Relaxed mode")
        assertTrue(englishString("enable_hardened_mode") == "Enable Hardened mode")
        assertFalse(englishStrings.contains("Convenience mode"))
    }
}
