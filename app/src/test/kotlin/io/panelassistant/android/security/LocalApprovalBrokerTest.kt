package io.panelassistant.android.security

import io.panelassistant.android.http.exactHttpApprovalPayload
import io.panelassistant.android.http.sha256Hex
import java.security.SecureRandom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalApprovalBrokerTest {
    @Test
    fun approvalIsBoundToExactPeerPayloadAndConsumedOnce() {
        var now = 1_000L
        val broker = ApprovalBroker({ now }, SecureRandom(), ttlMs = 1_000L)

        val first = broker.request(SensitiveOperation.APK_INSTALL, "192.0.2.1", "sha256:a", "package a")
        assertEquals(ApprovalBroker.Decision.PENDING, first.first)
        assertTrue(broker.approve(first.second))

        assertEquals(
            ApprovalBroker.Decision.PENDING,
            broker.request(SensitiveOperation.APK_INSTALL, "192.0.2.2", "sha256:a", "package a").first,
        )
        assertEquals(
            ApprovalBroker.Decision.PENDING,
            broker.request(SensitiveOperation.APK_INSTALL, "192.0.2.1", "sha256:b", "package b").first,
        )
        assertEquals(
            ApprovalBroker.Decision.APPROVED,
            broker.request(SensitiveOperation.APK_INSTALL, "192.0.2.1", "sha256:a", "package a").first,
        )
        assertEquals(
            ApprovalBroker.Decision.PENDING,
            broker.request(SensitiveOperation.APK_INSTALL, "192.0.2.1", "sha256:a", "package a").first,
        )
    }

    @Test
    fun stateReportsARecordWithoutConsumingItAndAnApprovalBelongsToOneTransport() {
        var now = 1_000L
        val broker = ApprovalBroker({ now }, SecureRandom(), ttlMs = 1_000L)
        val (_, id) = broker.request(SensitiveOperation.CAMERA_ENABLE, "panel_assistant", "camera_enabled\u0000enable", "camera")
        assertEquals(ApprovalBroker.State.PENDING, broker.state(id))
        assertTrue(broker.approve(id))
        assertEquals(ApprovalBroker.State.APPROVED, broker.state(id))
        assertEquals(ApprovalBroker.State.APPROVED, broker.state(id))

        // The same operation and payload from MQTT is a different request and cannot use this approval.
        assertEquals(
            ApprovalBroker.Decision.PENDING,
            broker.request(SensitiveOperation.CAMERA_ENABLE, "mqtt", "camera_enabled\u0000enable", "camera").first,
        )
        assertEquals(
            ApprovalBroker.Decision.APPROVED to id,
            broker.request(SensitiveOperation.CAMERA_ENABLE, "panel_assistant", "camera_enabled\u0000enable", "camera"),
        )
        assertEquals(ApprovalBroker.State.ABSENT, broker.state(id))

        val (_, denied) = broker.request(SensitiveOperation.DEVICE_REBOOT, "panel_assistant", "", "reboot")
        assertTrue(broker.deny(denied))
        assertEquals(ApprovalBroker.State.ABSENT, broker.state(denied))
        val (_, expiring) = broker.request(SensitiveOperation.DEVICE_REBOOT, "panel_assistant", "x", "reboot")
        now += 1_000L
        assertEquals(ApprovalBroker.State.ABSENT, broker.state(expiring))
    }

    @Test
    fun pendingAndApprovedRequestsExpire() {
        var now = 1_000L
        val broker = ApprovalBroker({ now }, SecureRandom(), ttlMs = 100L)
        val request = broker.request(SensitiveOperation.DEVTOOLS_ENABLE, "local", "start", "start")
        assertTrue(broker.approve(request.second))
        now += 100L
        assertFalse(broker.approve(request.second))
        assertEquals(
            ApprovalBroker.Decision.PENDING,
            broker.request(SensitiveOperation.DEVTOOLS_ENABLE, "local", "start", "start").first,
        )
    }

    @Test
    fun backwardClockExpiresApprovalInsteadOfExtendingIt() {
        var now = 1_000L
        val broker = ApprovalBroker({ now }, SecureRandom(), ttlMs = 100L)
        val request = broker.request(SensitiveOperation.DEVICE_REBOOT, "local", "reboot", "reboot")
        assertTrue(broker.approve(request.second))

        now = 999L

        assertFalse(broker.approve(request.second))
        assertEquals(
            ApprovalBroker.Decision.PENDING,
            broker.request(SensitiveOperation.DEVICE_REBOOT, "local", "reboot", "reboot").first,
        )
    }

    @Test
    fun httpApprovalCannotBeConsumedByChangedMethodParametersOrBody() {
        val broker = ApprovalBroker(random = SecureRandom())
        fun payload(
            method: String = "POST",
            query: List<Pair<String, String>> = listOf("mode" to "fleet", "strict" to "1"),
            body: String = "config-a",
        ) = exactHttpApprovalPayload(method, "/api/v1/config/import", query, sha256Hex(body.toByteArray()))

        val approved = broker.request(SensitiveOperation.CONFIG_IMPORT, "192.0.2.10", payload(), "import")
        assertTrue(broker.approve(approved.second))

        assertEquals(
            ApprovalBroker.Decision.PENDING,
            broker.request(SensitiveOperation.CONFIG_IMPORT, "192.0.2.10", payload(method = "PUT"), "import").first,
        )
        assertEquals(
            ApprovalBroker.Decision.PENDING,
            broker.request(
                SensitiveOperation.CONFIG_IMPORT,
                "192.0.2.10",
                payload(query = listOf("mode" to "restore", "strict" to "1")),
                "import",
            ).first,
        )
        assertEquals(
            ApprovalBroker.Decision.PENDING,
            broker.request(SensitiveOperation.CONFIG_IMPORT, "192.0.2.10", payload(body = "config-b"), "import").first,
        )
        assertEquals(
            ApprovalBroker.Decision.APPROVED,
            broker.request(SensitiveOperation.CONFIG_IMPORT, "192.0.2.10", payload(), "import").first,
        )
    }

    @Test
    fun canonicalHttpApprovalIgnoresParameterOrderButRetainsDuplicateValues() {
        val first = exactHttpApprovalPayload(
            "POST",
            "/api/v1/install/component",
            listOf("name" to "paneld", "allow_downgrade" to "true", "name" to "paneld"),
            "body",
        )
        val reordered = exactHttpApprovalPayload(
            "post",
            "/api/v1/install/component",
            listOf("name" to "paneld", "name" to "paneld", "allow_downgrade" to "true"),
            "body",
        )
        val oneName = exactHttpApprovalPayload(
            "POST",
            "/api/v1/install/component",
            listOf("name" to "paneld", "allow_downgrade" to "true"),
            "body",
        )

        assertEquals(first, reordered)
        assertFalse(first == oneName)
    }

    @Test
    fun powerApprovalIsExactPeerPayloadBoundAndOneShot() {
        val broker = ApprovalBroker(random = SecureRandom())
        val payload = "prevent_idle_dim\u0000OFF"
        val pending = broker.request(SensitiveOperation.POWER_CONFIGURATION, "mqtt", payload, "disable guard")
        assertTrue(broker.approve(pending.second))

        assertEquals(
            ApprovalBroker.Decision.PENDING,
            broker.request(SensitiveOperation.POWER_CONFIGURATION, "other", payload, "disable guard").first,
        )
        assertEquals(
            ApprovalBroker.Decision.PENDING,
            broker.request(SensitiveOperation.POWER_CONFIGURATION, "mqtt", "prevent_idle_dim\u0000off", "disable guard").first,
        )
        assertEquals(
            ApprovalBroker.Decision.PENDING,
            broker.request(SensitiveOperation.PACKAGE_TAME, "mqtt", payload, "disable guard").first,
        )
        assertEquals(
            ApprovalBroker.Decision.APPROVED,
            broker.request(SensitiveOperation.POWER_CONFIGURATION, "mqtt", payload, "disable guard").first,
        )
        assertEquals(
            ApprovalBroker.Decision.PENDING,
            broker.request(SensitiveOperation.POWER_CONFIGURATION, "mqtt", payload, "disable guard").first,
        )
    }
}
