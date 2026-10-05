package io.panelassistant.android.http

import io.panelassistant.android.security.ApprovalBroker
import io.panelassistant.android.security.SensitiveOperation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ConfigImportApprovalBindingTest {
    private val body = """{"kind":"ha-paneld-config","schema":1,"values":{"panel_id":"sample-panel"}}"""
        .toByteArray(Charsets.UTF_8)

    private val constrainedParameters = listOf(
        "mode" to listOf("fleet"),
        "strict" to listOf("1"),
        "expected_cfg" to listOf("1234abcd"),
    )

    private fun approvalPayload(
        bodyBytes: ByteArray = body,
        parameters: List<Pair<String, List<String>>> = constrainedParameters,
    ): String = exactHttpApprovalPayload(
        method = "POST",
        path = "/api/v1/config/import",
        parameters = parameters.flatMap { (name, values) -> values.map { name to it } },
        bodyDigest = sha256Hex(bodyBytes),
    )

    @Test fun behaviorAffectingImportParametersChangeTheApprovalPayload() {
        val constrained = approvalPayload()

        for (name in listOf("mode", "strict", "expected_cfg")) {
            assertNotEquals(
                "removing $name must invalidate the approval",
                constrained,
                approvalPayload(parameters = constrainedParameters.filterNot { it.first == name }),
            )
        }
        assertNotEquals(
            "changing the request body must invalidate the approval",
            constrained,
            approvalPayload(bodyBytes = body + '\n'.code.toByte()),
        )
    }

    @Test fun queryNameOrderIsCanonicalButDuplicateValueOrderRemainsBound() {
        assertEquals(
            approvalPayload(),
            approvalPayload(parameters = constrainedParameters.reversed()),
        )
        assertNotEquals(
            approvalPayload(parameters = listOf("mode" to listOf("fleet", "restore"))),
            approvalPayload(parameters = listOf("mode" to listOf("restore", "fleet"))),
        )
    }

    @Test fun approvedFleetImportCannotAuthorizeAnUnscopedReplay() {
        val broker = ApprovalBroker()
        val constrained = approvalPayload()
        val approval = broker.request(
            SensitiveOperation.CONFIG_IMPORT,
            "192.0.2.10",
            constrained,
            "Import one portable panel setting",
        )
        assertEquals(ApprovalBroker.Decision.PENDING, approval.first)
        broker.approve(approval.second)

        val unscoped = approvalPayload(
            parameters = constrainedParameters.filterNot { it.first == "mode" },
        )
        assertEquals(
            ApprovalBroker.Decision.PENDING,
            broker.request(
                SensitiveOperation.CONFIG_IMPORT,
                "192.0.2.10",
                unscoped,
                "Import panel settings",
            ).first,
        )
        assertEquals(
            ApprovalBroker.Decision.APPROVED,
            broker.request(
                SensitiveOperation.CONFIG_IMPORT,
                "192.0.2.10",
                constrained,
                "Import one portable panel setting",
            ).first,
        )
    }
}
