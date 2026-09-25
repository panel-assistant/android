package io.github.maxlyth.hapaneld.sensors

import io.github.maxlyth.hapaneld.config.SettingsRegistry
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveProximitySurfaceContractTest {
    @Test fun homeAssistantReceivesOnlyAvailableFleetNormalizedState() {
        val mqtt = source("MqttBridge.kt")

        assertTrue(mqtt.contains("normalizedLevel?.coerceIn(0, 100)"))
        assertTrue(mqtt.contains("registryExposable(\"proximity_level\""))
        // The proximity_level discovery payload is registry-driven; its % unit now lives in the
        // single SettingsRegistry descriptor rather than a MqttBridge literal (byte-parity is pinned
        // by DiscoveryParityTest).
        assertTrue(
            SettingsRegistry.spec("proximity_level")!!.ha!!
                .buildDiscoveryJson("test", "a", "d").contains("\"unit_of_measurement\":\"%\""),
        )
        assertTrue(mqtt.contains("availability_mode\":\"all\""))
        assertTrue(mqtt.contains("numericDeadband(4.0)"))
        assertTrue(mqtt.contains("publish(stateProximityLevel, \"\", retain = true)"))
        assertTrue(
            mqtt.indexOf("publish(proximityAvailabilityTopic") <
                mqtt.indexOf("publish(availabilityTopic, \"online\""),
        )
        assertFalse(mqtt.contains("fun publishProximity(near: Boolean)"))
    }

    @Test fun htmlLaunchesExplicitSetupAndPhysicalStepsRemainOnThePanel() {
        val runtime = source("sensors/ProximityCalibrationRuntime.kt")
        val script = asset("proximity-learning.js")
        val panel = source("ProximityWizardActivity.kt")
        val help = SettingsRegistry.spec("wake_on_wave")!!.help

        assertTrue(runtime.contains("ProximityCalibrationEngine(readCalibration(),"))
        assertTrue(runtime.contains("fun localAction(action: String)"))
        assertTrue(runtime.contains("now - visibleAt > LOCAL_VISIBILITY_MS"))
        assertTrue(runtime.contains("put(\"sessionActive\", state.active)"))
        assertTrue(script.contains("Set up proximity on panel"))
        assertTrue(script.contains("Follow the instructions on the panel"))
        assertTrue(script.contains("Its detection distance cannot be adjusted"))
        assertTrue(script.contains("api/v1/proximity/calibration"))
        assertTrue(script.contains("request(\"heartbeat\", id)"))
        assertTrue(script.contains("if (error.opaque) result.setAttribute(\"lang\", \"en\")"))
        assertTrue(script.contains("start.disabled = busy || d.present === false || (!available && d.canCalibrate !== true)"))
        assertTrue(script.indexOf("active = d.sessionActive") < script.indexOf("start.hidden = active"))
        assertFalse(script.contains("post(\"save\")"))
        assertFalse(script.contains("post(\"begin\")"))
        assertTrue(panel.contains("\"intro\" -> perform(\"begin\")"))
        assertTrue(panel.contains("\"review\" -> perform(\"save\")"))
        assertTrue(help.contains("clear-to-near-to-clear"))
        assertTrue(help.contains("touch-to-wake remains available"))
    }

    @Test fun independentReportMaskReachesTheMqttStateOwnerWithoutCollapsing() {
        val reporter = source("sensors/SensorReporter.kt")
        val callbacks = source("sensors/SensorRunCallbacks.kt")
        val service = source("PaneldService.kt")
        val mqtt = source("MqttBridge.kt")

        assertTrue(reporter.contains("run.proximity(decision.near, decision.normalizedLevel, decision.reportMask)"))
        assertTrue(callbacks.contains("private val onProximity: (Boolean?, Int?, Int) -> Unit"))
        assertTrue(callbacks.contains("onProximity(near, normalizedLevel, reportMask)"))
        assertTrue(service.contains("onProximity = { near, level, reportMask ->"))
        assertTrue(service.contains("mqtt.publishProximity(near, level, reportMask)"))
        assertTrue(mqtt.contains("reportMask: Int = ProximityReportGate.BOTH"))
        assertTrue(mqtt.contains("val admitted = admit(nextNear, level, reportMask)"))
        assertTrue(mqtt.contains("proximityPublication.near?.let"))
        assertTrue(mqtt.contains("proximityPublication.level?.let"))
        assertTrue(mqtt.contains("admitted and ProximityReportGate.PRESENCE != 0"))
        assertTrue(mqtt.contains("admitted and ProximityReportGate.LEVEL != 0"))
    }

    @Test fun fixedCalibrationUsesEventAcquisitionAndOnlySchedulesNeededConfirmationTimers() {
        val reporter = source("sensors/SensorReporter.kt")
        val runtime = source("sensors/ProximityCalibrationRuntime.kt")
        val reportGate = source("sensors/ProximityReportGate.kt")
        val observe = runtime.substring(runtime.indexOf("fun observe("), runtime.indexOf("fun sourceUnavailable("))
        val handle = reporter.substring(
            reporter.indexOf("private fun handleProximity("),
            reporter.indexOf("private fun deliverProximity("),
        )

        assertTrue(reporter.contains("SensorManager.SENSOR_DELAY_NORMAL"))
        assertFalse(reporter.contains("SensorManager.SENSOR_DELAY_UI"))
        assertTrue(reporter.contains("proximityRuntime = ProximityCalibrationRuntime("))
        assertFalse(reporter.contains("ProximityLearningRuntime("))
        assertTrue(reporter.contains("calibrationTickScheduled || proximityRuntime?.needsTick() != true"))
        assertTrue(reporter.contains("proximityRuntime?.sourceUnavailable(now)"))
        assertTrue(reporter.contains("ON_CHANGE_PROBE_INTERVAL_MS"))
        assertTrue(reporter.contains("sm.unregisterListener(activeListener, sensor)"))
        assertTrue(runtime.contains("reportGate.project("))
        assertTrue(reportGate.contains("private var pendingPresence"))
        assertTrue(reportGate.contains("private var lastLevelAt"))
        assertTrue(reportGate.contains("const val PRESENCE = 1"))
        assertTrue(reportGate.contains("const val LEVEL = 2"))
        assertFalse(reportGate.contains("Thread("))
        assertFalse(reportGate.contains("Handler"))
        assertFalse(reportGate.contains("Timer"))
        assertTrue(handle.contains("val sparseForObservation = reportingSparse()"))
        assertTrue(
            handle.indexOf("val sparseForObservation = reportingSparse()") <
                handle.indexOf("cadenceClassified = false"),
        )
        assertTrue(handle.contains("proximitySampleCount = 1"))
        assertTrue(handle.contains("sparseReporting = sparseForObservation"))
        assertTrue(handle.contains("live = wakeEligible, calibrationLive = fresh"))
        assertTrue(reporter.contains("proximityCadenceWindowIsContinuous(proximitySampleCount)"))
        assertFalse(observe.contains("writeProximityBatch("))
        assertFalse(observe.contains("snapshot()"))
    }

    @Test fun vi530xCallbacksJoinTheSensorHandlerBeforeMutatingCalibrationState() {
        val reporter = source("sensors/SensorReporter.kt")
        val branchStart = reporter.indexOf("proximityAcquisition == ProximityAcquisition.VI530X")
        val vi530xBranch = reporter.substring(
            branchStart,
            reporter.indexOf("proximityAcquisition == ProximityAcquisition.ANDROID_HAL", branchStart),
        )

        assertTrue(vi530xBranch.contains("onValue = { raw ->\n                            handler.post {"))
        assertTrue(vi530xBranch.contains("onUnavailable = {\n                            handler.post {"))
        assertTrue(reporter.contains("ProximityAcquisition.VI530X -> \"helper-vi530x\""))
    }

    @Test fun explicitPersistenceAndWakeBoundariesFailClosed() {
        val runtime = source("sensors/ProximityCalibrationRuntime.kt")
        val service = source("PaneldService.kt")
        val screen = source("control/ScreenController.kt")
        val server = source("http/PaneldServer.kt")
        val configure = asset("configure.js")

        assertTrue(runtime.contains("store.writeProximityBatch("))
        assertTrue(runtime.contains("ProximityCalibrationEngine(readCalibration(), commit = { candidate ->"))
        assertTrue(runtime.contains("encode(candidate)"))
        assertFalse(runtime.contains("requestPersist("))
        assertFalse(runtime.contains("ProximityLearningEngine"))
        assertTrue(service.contains("config.wakeOnWaveGeneration != settingGeneration"))
        assertTrue(service.contains("!sensors.proximityReady()"))
        assertTrue(service.contains("sensors.proximityGeneration() != proximityGeneration"))
        assertTrue(service.contains("finally { sensors.completeProximityGesture(token, accepted) }"))
        assertTrue(screen.contains("fun reconcileObservedLit(expectedGeneration"))
        assertTrue(screen.contains("observedDarkGeneration != expectedGeneration"))
        assertTrue(server.contains("PROXIMITY_SOURCE_REQUIRED"))
        assertTrue(server.contains("hasProximity = sensors.hasProximity()"))
        assertTrue(server.contains("hasLearnedProximity = sensors.hasLearnedProximity()"))
        assertTrue(server.contains("action !in setOf(\"start\", \"cancel\", \"reset\", \"heartbeat\")"))
        assertTrue(server.contains("proximityUiRequestAllowed("))
        // Optional setup must be reachable before the user enables wave waking.
        assertTrue(server.contains("val proximityLearningEnabled = sensors.hasProximity()\n"))
        assertFalse(server.contains("val proximityLearningEnabled = sensors.hasProximity() && config.wakeOnWave"))
        assertTrue(server.contains("if (proximityLearningEnabled) \"\"\"<div id=\"proximity-learning-mount\""))
        assertTrue(configure.contains("submittedValues, \"wake_on_wave\""))
        assertTrue(configure.contains("window.location.reload();"))
        assertTrue(runtime.contains("fun isLearnedSignal(): Boolean = !closed && view.calibration?.presenceSupported == true"))
        assertTrue(mqttTombstonesAllPresenceSurfaces())
    }

    private fun mqttTombstonesAllPresenceSurfaces(): Boolean {
        val mqtt = source("MqttBridge.kt")
        return mqtt.contains("capabilitySnapshot?.hasLearnedProximity == true") &&
            mqtt.contains("registryExposable(\"proximity\", proximityAvail, hardwareAvailability(\"proximity\", learnedProximity))") &&
            mqtt.contains("registryExposable(\"proximity_level\", proximityAvail, hardwareAvailability(\"proximity_level\", learnedProximity))") &&
            mqtt.contains("\"proximity\", \"proximity_level\" -> learnedProximity") &&
            mqtt.contains("if (hasProximity) known(if (config.wakeOnWave)")
    }

    private fun source(relative: String): String = locate("src/main/kotlin/io/github/maxlyth/hapaneld/$relative").readText()

    private fun asset(name: String): String = locate("src/main/assets/$name").readText()

    private fun locate(relative: String): File = listOf(File(relative), File("app/$relative"), File("../app/$relative"))
        .firstOrNull(File::isFile) ?: error("missing test input $relative")
}
