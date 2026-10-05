package io.panelassistant.android.mqtt

import io.panelassistant.android.cameraSnapshotAvailabilityTopic
import io.panelassistant.android.cameraSnapshotConfigPayload
import io.panelassistant.android.cameraSnapshotDiscoveryJson
import io.panelassistant.android.cameraSnapshotPublications
import io.panelassistant.android.cameraSnapshotUrlTopic
import io.panelassistant.android.config.Capabilities
import io.panelassistant.android.config.SettingsRegistry
import io.panelassistant.android.dualAvailabilityFragment
import io.panelassistant.android.mqttKnownConfigTopics
import io.panelassistant.android.requireCameraEnableAdmission
import io.panelassistant.android.testsupport.TestSources
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The camera's Home Assistant surface: one master switch, one snapshot image entity, and the wiring
 * both need to exist at all. The switch is the interesting one because a Home Assistant entity for a
 * settings key is longer than a registry line — a command topic that is not admitted is delivered by
 * the wildcard subscription and then dropped in silence, and an entity missing from the discovery
 * tombstone superset can never have its retained config cleared again.
 *
 * Every assertion below is written to fail as an assertion rather than as a thrown lookup: a missing
 * JSON key is checked with `has` before it is read, so breaking the payload reds this test with a
 * message about the payload instead of a parse error that says nothing.
 */
class CameraMqttSurfaceTest {
    private val panel = "test"
    private val device = """"device":{"identifiers":["ha-paneld-test"]}"""
    private val panelAvailability = "ha-paneld/$panel/availability"
    // Source-text reason: pins MqttBridge.kt, deleted with the MQTT removal.
    private val mqtt by lazy { TestSources.kotlin("MqttBridge.kt").readText() }

    private fun slice(text: String, from: String, to: String): String {
        val start = text.indexOf(from)
        val end = text.indexOf(to)
        assertTrue("marker not found: $from", start >= 0)
        assertTrue("marker not found: $to", end > start)
        return text.substring(start, end)
    }

    private fun field(payload: JSONObject, key: String): String {
        assertTrue("discovery payload is missing $key", payload.has(key))
        return payload.optString(key)
    }

    @Test fun cameraSettingStaysLocalAndCapabilityGatedWhileTheNativeCameraControlsIt() {
        val spec = SettingsRegistry.spec("camera_enabled")!!
        assertNull("a native camera replaces the redundant configuration switch", spec.ha)
        assertTrue(spec.availableWhen(Capabilities(hasCamera = true)))
        assertFalse(spec.availableWhen(Capabilities()))
        assertEquals("false", spec.default)
    }

    @Test fun theCameraCeilingsStayOnThePanelAndNeverBecomeEntities() {
        // The caps bound what a stream URL may ask for; they are not things to operate from a dashboard,
        // and the plan and the privacy contract both record the switch as the only camera value HA sees.
        listOf("camera_resolution", "camera_fps", "camera_kbps").forEach { key ->
            val spec = SettingsRegistry.spec(key)
            assertNotNull("$key must stay in the settings registry", spec)
            assertNull("$key must not become a Home Assistant entity", spec!!.ha)
        }
    }

    @Test fun bothCameraEntitiesAreInTheDiscoveryTombstoneSuperset() {
        val known = mqttKnownConfigTopics(panel)
        assertTrue(
            "without a tombstone the switch's retained config can never be cleared again",
            known.contains("homeassistant/switch/test_camera_enabled/config"),
        )
        assertTrue(
            "without a tombstone the image entity's retained config can never be cleared again",
            known.contains("homeassistant/image/test_camera_snapshot/config"),
        )
    }

    @Test fun theSnapshotEntityCarriesAUrlAndNeverImageBytes() {
        val avail = dualAvailabilityFragment(panelAvailability, cameraSnapshotAvailabilityTopic(panel))
        val payload = JSONObject(cameraSnapshotDiscoveryJson(panel, avail, device))
        assertEquals("test_camera_snapshot", field(payload, "unique_id"))
        assertEquals(cameraSnapshotUrlTopic(panel), field(payload, "url_topic"))
        // Image bytes on the broker would be a frame stored outside the panel, and a retained one would
        // sit on the broker's disk. Home Assistant makes the two topics mutually exclusive; so do we.
        assertFalse("the snapshot entity must never carry image bytes", payload.has("image_topic"))
        // Home Assistant forbids content_type alongside url_topic and derives it from the response.
        assertFalse(payload.has("content_type"))

        assertEquals("all", field(payload, "availability_mode"))
        val availability = payload.optJSONArray("availability")
        assertNotNull("the snapshot entity needs its own availability beside the panel's", availability)
        assertEquals(2, availability!!.length())
        assertEquals(panelAvailability, availability.optJSONObject(0)?.optString("topic"))
        assertEquals(
            cameraSnapshotAvailabilityTopic(panel),
            availability.optJSONObject(1)?.optString("topic"),
        )
    }

    @Test fun aProfileWithoutACameraGoesOfflineAndDropsTheRetainedUrl() {
        val published = cameraSnapshotPublications(
            panel = panel, announced = false, enabled = true,
            url = "http://192.0.2.7:8888/api/v1/camera/snapshot.jpg", refreshUrl = true,
        )
        assertEquals(
            listOf(
                cameraSnapshotAvailabilityTopic(panel) to "offline",
                cameraSnapshotUrlTopic(panel) to "",
            ),
            published.map { it.topic to it.payload },
        )
    }

    @Test fun anEnabledCameraPublishesTheUrlAheadOfTheOnlineEdge() {
        val url = "http://192.0.2.7:8888/api/v1/camera/snapshot.jpg"
        val published = cameraSnapshotPublications(
            panel = panel, announced = true, enabled = true, url = url, refreshUrl = true,
        )
        assertEquals(
            listOf(
                cameraSnapshotUrlTopic(panel) to url,
                cameraSnapshotAvailabilityTopic(panel) to "online",
            ),
            published.map { it.topic to it.payload },
        )
    }

    @Test fun aRepublishWithoutARefreshLeavesTheUrlUntouched() {
        // Home Assistant drops its cached frame on every message, so an unasked republish would make a
        // card somebody left open fetch again. Only a fresh enable and an announcement ask for that.
        val published = cameraSnapshotPublications(
            panel = panel, announced = true, enabled = true,
            url = "http://192.0.2.7:8888/api/v1/camera/snapshot.jpg", refreshUrl = false,
        )
        assertEquals(
            listOf(cameraSnapshotAvailabilityTopic(panel) to "online"),
            published.map { it.topic to it.payload },
        )
    }

    @Test fun theUrlTopicNeverCarriesAnUnusableValueWhileTheEntityExists() {
        // Home Assistant validates every message on the URL topic as a URL and logs an error otherwise,
        // so "the camera is off", "no LAN address" and "blank address" are all said on availability.
        val cases = listOf(
            Triple("switch off", false, "http://192.0.2.7:8888/api/v1/camera/snapshot.jpg"),
            Triple("no address", true, null),
            Triple("blank address", true, ""),
        )
        cases.forEach { (name, enabled, url) ->
            val published = cameraSnapshotPublications(
                panel = panel, announced = true, enabled = enabled, url = url, refreshUrl = true,
            )
            assertEquals(
                "$name must publish availability alone",
                listOf(cameraSnapshotAvailabilityTopic(panel) to "offline"),
                published.map { it.topic to it.payload },
            )
        }
    }

    @Test fun everySiteTheCameraSwitchNeedsIsWired() {
        val admitted = slice(mqtt, "private val stateCommandTopics", "private val actionCommandTopics")
        assertTrue(
            "an unadmitted command topic is delivered by the wildcard subscription and then dropped",
            admitted.contains("cmdCameraEnabled"),
        )

        val dispatch = slice(mqtt, "private fun dispatchCommand", "fun publishScreenOn")
        assertTrue(dispatch.contains("cmdCameraEnabled -> handleCameraEnabled(payload)"))

        val handler = slice(mqtt, "private fun handleCameraEnabled", "private fun publishCameraSnapshot")
        val authorizeAt = handler.indexOf("authorizeRemoteSensitive(")
        val writeAt = handler.indexOf("config.setCameraEnabled(")
        assertTrue("the enable direction must ask for local approval", authorizeAt >= 0)
        assertTrue("the handler must write the master switch", writeAt >= 0)
        assertTrue("approval must precede the write, or a refusal arms the camera anyway", authorizeAt < writeAt)
        assertTrue("camera approval must not be bypassed on Relaxed panels", handler.contains("always = true"))
        assertTrue("direct MQTT ON must be guarded by the active profile", handler.contains("hasCamera"))
        assertTrue(handler.contains("SensitiveOperation.CAMERA_ENABLE"))
        assertTrue(
            "the owner must be actuated, not only the preference written",
            handler.contains("onCameraEnabledChanged()"),
        )
        assertTrue(handler.contains("""stateConverger.reconcile("camera_enabled", force = true)"""))

        val converger = slice(mqtt, "private fun createStateConverger", "private fun ensureCapabilityChannels")
        assertTrue(
            "without a state channel the switch never reports back what the panel did",
            converger.contains("""channel("camera_enabled", stateCameraEnabled)"""),
        )

        val discovery = slice(mqtt, "private fun publishDiscovery", "private fun jsonEsc")
        assertTrue(discovery.contains("cameraSnapshotDiscoveryJson(panel, cameraSnapshotAvail, device)"))

        // The switch is opt-in by the assertion above, so the image has to follow the same decision.
        // Announcing it on `hasCamera` alone left a fresh camera panel showing Home Assistant a
        // permanently unavailable image entity and no switch able to arm it.
        val announce = slice(discovery, "cameraSnapshotAnnounced =", "publishCameraSnapshot(refreshUrl = true)")
        assertTrue(announce.contains("""config.haExposed("camera_enabled", false)"""))
        // The withdrawal must be published, not skipped: a config topic written earlier in this bridge
        // generation survives until something overwrites it.
        assertTrue(announce.contains("cameraSnapshotConfigPayload(cameraSnapshotAnnounced)"))
        assertFalse(announce.contains("if (cameraSnapshotAnnounced) {"))
    }

    @Test fun hidingTheCameraSwitchWithdrawsTheImageEntityAndItsRetainedState() {
        val avail = dualAvailabilityFragment(panelAvailability, cameraSnapshotAvailabilityTopic(panel))
        var built = 0
        val discoveryJson = { built++; cameraSnapshotDiscoveryJson(panel, avail, device) }

        // Exposed: the image is announced with a real discovery document.
        val announced = cameraSnapshotConfigPayload(announced = true, discoveryJson = discoveryJson)
        assertEquals("test_camera_snapshot", field(JSONObject(announced), "unique_id"))
        assertEquals(1, built)

        // Exposed -> hidden: an EMPTY payload, which is what removes a retained config from Home
        // Assistant. A blank string here is the entire fix; anything non-empty leaves the entity live.
        val withdrawn = cameraSnapshotConfigPayload(announced = false, discoveryJson = discoveryJson)
        assertEquals("", withdrawn)
        // Nothing is built for a withdrawal, so the tombstone cannot carry a stale document by accident.
        assertEquals(1, built)

        // The retained URL and availability withdraw with it, so no address survives the transition.
        val cleanup = cameraSnapshotPublications(
            panel, announced = false, enabled = false, url = "http://panel.invalid/snapshot.jpg",
            refreshUrl = true,
        )
        assertEquals(2, cleanup.size)
        assertEquals(cameraSnapshotAvailabilityTopic(panel), cleanup[0].topic)
        assertEquals("offline", cleanup[0].payload)
        assertEquals(cameraSnapshotUrlTopic(panel), cleanup[1].topic)
        assertEquals("", cleanup[1].payload)

        // A panel that was never exposed publishes the same withdrawal rather than nothing at all.
        assertEquals("", cameraSnapshotConfigPayload(announced = false) { error("never built") })
    }

    @Test fun cameraOffNeverNeedsApprovalEvenWhenTheProfileHasNoCamera() {
        var approvals = 0

        requireCameraEnableAdmission(on = false, hasCamera = false) { approvals++ }

        assertEquals(0, approvals)
    }

    @Test fun cameraOnAlwaysRunsLocalApprovalOnACameraCapableProfile() {
        var approvals = 0

        requireCameraEnableAdmission(on = true, hasCamera = true) { approvals++ }

        assertEquals(1, approvals)
    }

    @Test fun cameraOnIsRefusedBeforeApprovalWhenTheProfileHasNoCamera() {
        var approvals = 0
        var refused = false

        try {
            requireCameraEnableAdmission(on = true, hasCamera = false) { approvals++ }
        } catch (_: IllegalStateException) {
            refused = true
        }

        assertTrue(refused)
        assertEquals(0, approvals)
    }
}
