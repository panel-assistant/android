package io.github.maxlyth.hapaneld.camera

import io.github.maxlyth.hapaneld.testsupport.TestSources
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraCapabilitySourceContractTest {
    // Source-text reason: pins MqttBridge.kt, deleted with the MQTT removal.

    private val bridge by lazy { TestSources.kotlin("MqttBridge.kt").readText() }

    /**
     * The bridge holds the capability as a supplier, never a value read once while the service was
     * starting. A boolean captured from a probe that threw at construction would announce the camera
     * entity from the later snapshot and then refuse the enable command that arrives for it.
     */
    @Test fun theBridgeAsksRatherThanRemembering() {
        assertTrue("private val hasCamera: () -> Boolean = { false }," in bridge)
        assertTrue("requireCameraEnableAdmission(on, hasCamera()) {" in bridge)
    }
}
