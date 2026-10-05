package io.panelassistant.android.http

import io.panelassistant.android.util.GuardDbMaintenanceProtocol
import io.panelassistant.android.util.GuardDbPreparedArm
import io.panelassistant.android.util.GuardDbSentinelState
import io.panelassistant.android.util.GuardDbStartupSentinel
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardDbBootstrapExportTest {
    @Test fun `proof is exact sorted minified v2 JSON with one trailing LF`() {
        val snapshot = snapshot()
        val actual = canonicalGuardDbBootstrapProof(snapshot, PEER, CAPTURE, 42L)
        val expected = "{" +
            "\"baseline\":{\"app_state_count\":37,\"database_bytes\":4," +
            "\"database_sha256\":\"${"3".repeat(64)}\"," +
            "\"ordered_app_state_sha256\":\"${"4".repeat(64)}\",\"schema\":14," +
            "\"settings_semantic_sha256\":\"${"5".repeat(64)}\"}," +
            "\"boot_nonce\":\"${"2".repeat(64)}\",\"capture_id\":\"$CAPTURE\"," +
            "\"captured_elapsed_realtime_ms\":42," +
            "\"format\":\"hapaneld-guard-db-bootstrap-proof-v2\"," +
            "\"guard\":{\"generation\":0,\"phase\":\"EMPTY\"}," +
            "\"helper\":{\"build_id\":\"${"7".repeat(64)}\",\"bytes\":123," +
            "\"capabilities_reply\":\"${GuardDbBootstrapExportSnapshot.SUPPORTED_CAPABILITIES_REPLY}\"," +
            "\"sha256\":\"${"6".repeat(64)}\"," +
            "\"status_reply\":\"${GuardDbBootstrapExportSnapshot.EMPTY_STATUS_REPLY}\"}," +
            "\"installed_a\":{\"bytes\":4,\"contract_maximum\":14,\"contract_minimum\":11," +
            "\"schema\":14,\"sha256\":\"${"a".repeat(64)}\"," +
            "\"signer_sha256\":\"${"8".repeat(64)}\",\"version_code\":568," +
            "\"version_name\":\"0.9.7-test\"},\"ok\":true,\"request_peer\":\"$PEER\"," +
            "\"security\":{\"authority_sha256\":\"${"9".repeat(64)}\"," +
            "\"cdp_relay_absent\":true,\"epoch\":41,\"remote_debug_off\":true," +
            "\"state\":\"HARDENED\"},\"session\":\"${"1".repeat(64)}\"}\n"

        assertEquals(expected, actual)
        assertTrue(actual.endsWith("}\n"))
        assertFalse(actual.dropLast(1).contains("\n"))
    }

    @Test fun `lease rejects mixed credentials without revoking the exact lease`() {
        var now = 1_000L
        val store = GuardDbBootstrapExportLeaseStore({ now }, 120_000L)
        val snapshot = snapshot()
        assertTrue(store.issue(snapshot, PEER, CAPTURE, TOKEN, DATABASE) != null)
        val exact = credentials()

        listOf(
            exact.copy(peer = "192.168.20.31"),
            exact.copy(session = "0".repeat(64)),
            exact.copy(captureId = "d".repeat(64)),
            exact.copy(token = "e".repeat(64)),
            exact.copy(session = null),
            exact.copy(captureId = null),
            exact.copy(token = null),
        ).forEach { assertNull(store.proof(it) { true }) }

        assertArrayEquals(
            canonicalGuardDbBootstrapProof(snapshot, PEER, CAPTURE, now).toByteArray(),
            store.proof(exact) { true },
        )
        assertArrayEquals(DATABASE, store.database(exact) { true }?.bytes)
    }

    @Test fun `lease expires at its bounded deadline and drift invalidates it`() {
        var now = 10L
        val store = GuardDbBootstrapExportLeaseStore({ now }, 20L)
        assertTrue(store.issue(snapshot(), PEER, CAPTURE, TOKEN, DATABASE) != null)
        now = 29L
        assertTrue(store.proof(credentials()) { true } != null)
        now = 30L
        assertNull(store.proof(credentials()) { true })

        now = 40L
        assertTrue(store.issue(snapshot(), PEER, CAPTURE, TOKEN, DATABASE) != null)
        assertNull(store.proof(credentials()) { false })
        assertNull(store.proof(credentials()) { true })
    }

    @Test fun `only the newest successful lease remains active`() {
        val store = GuardDbBootstrapExportLeaseStore({ 10L }, 20L)
        assertTrue(store.issue(snapshot(), PEER, CAPTURE, TOKEN, DATABASE) != null)
        val replacementCapture = "d".repeat(64)
        val replacementToken = "e".repeat(64)
        assertTrue(store.issue(snapshot(), PEER, replacementCapture, replacementToken, DATABASE) != null)

        assertNull(store.proof(credentials()) { true })
        assertTrue(store.proof(GuardDbBootstrapExportCredentials(
            PEER, "1".repeat(64), replacementCapture, replacementToken,
        )) { true } != null)
    }

    @Test fun `snapshot refuses non exact guard helper app and security authority`() {
        val exact = snapshot()
        assertTrue(exact.exactFor(exact.sentinel))
        assertFalse(exact.copy(sentinel = exact.sentinel.copy(state = GuardDbSentinelState.ARMED))
            .exactFor(exact.sentinel))
        assertFalse(exact.copy(prepared = exact.prepared.copy(aSha256 = "0".repeat(64)))
            .exactFor(exact.sentinel))
        assertFalse(exact.copy(installedA = exact.installedA.copy(bytes = 5L)).exactFor(exact.sentinel))
        assertFalse(exact.copy(helper = exact.helper.copy(statusReply = "ERR")).exactFor(exact.sentinel))
        assertFalse(exact.copy(security = exact.security.copy(remoteDebugOff = false)).exactFor(exact.sentinel))
    }

    private fun credentials() = GuardDbBootstrapExportCredentials(
        peer = PEER,
        session = "1".repeat(64),
        captureId = CAPTURE,
        token = TOKEN,
    )

    private fun snapshot(): GuardDbBootstrapExportSnapshot {
        val session = "1".repeat(64)
        val boot = "2".repeat(64)
        val aSha = "a".repeat(64)
        val sentinel = GuardDbStartupSentinel(
            state = GuardDbSentinelState.BASELINE_READY,
            session = session,
            bootNonce = boot,
            aSha256 = aSha,
            aVersionCode = 568L,
            aSchema = 14,
            bSha256 = "b".repeat(64),
            bVersionCode = 569L,
            bSchema = 15,
            settingsAuthorityVersion = 2,
            settingsAuthorityBytes = 3L,
            settingsAuthoritySha256 = "c".repeat(64),
            securityAuthorityEpoch = 41L,
            httpPort = 8888,
            hardened = true,
        )
        val prepared = GuardDbPreparedArm(
            session = session,
            bootNonce = boot,
            aBytes = 4L,
            aSha256 = aSha,
            aVersionCode = 568L,
            aContractMinimum = 11,
            aContractMaximum = 14,
            aSchema = 14,
            bBytes = 5L,
            bSha256 = "b".repeat(64),
            bVersionCode = 569L,
            bContractMinimum = 11,
            bContractMaximum = 15,
            bSchema = 15,
            databaseBytes = 4L,
            databaseSha256 = "3".repeat(64),
            databaseSchema = 14,
            appStateRows = 37L,
            orderedAppStateSha256 = "4".repeat(64),
            settingsSemanticSha256 = "5".repeat(64),
            overallBudgetMs = GuardDbMaintenanceProtocol.MAX_OVERALL_BUDGET_MS,
            settingsAuthorityVersion = 2,
            settingsAuthorityBytes = 3L,
            settingsAuthoritySha256 = "c".repeat(64),
            securityAuthorityEpoch = 41L,
        )
        return GuardDbBootstrapExportSnapshot(
            sentinel = sentinel,
            prepared = prepared,
            installedA = GuardDbBootstrapInstalledApp(
                4L, aSha, "8".repeat(64), 568L, "0.9.7-test", 11, 14, 14,
            ),
            helper = GuardDbBootstrapHelper(
                123L,
                "6".repeat(64),
                "7".repeat(64),
                GuardDbBootstrapExportSnapshot.SUPPORTED_CAPABILITIES_REPLY,
                GuardDbBootstrapExportSnapshot.EMPTY_STATUS_REPLY,
            ),
            security = GuardDbBootstrapSecurity("HARDENED", 41L, "9".repeat(64), true, true),
        )
    }

    private companion object {
        const val PEER = "192.168.20.30"
        val CAPTURE = "c".repeat(64)
        val TOKEN = "f".repeat(64)
        val DATABASE = byteArrayOf(1, 2, 3, 4)
    }
}
