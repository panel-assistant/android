package io.panelassistant.android.http

import io.panelassistant.android.platform.DaemonLongResult
import io.panelassistant.android.platform.DaemonStreamResult
import io.panelassistant.android.control.RemoteDebugAuthorityResult
import io.panelassistant.android.control.RemoteDebugSecurityTransitionGate
import io.panelassistant.android.security.ApprovalBroker
import io.panelassistant.android.util.GuardDbAppStaging
import io.panelassistant.android.util.AppInstaller
import io.panelassistant.android.util.GuardDbCandidateInspection
import io.panelassistant.android.util.GuardDbMaintenanceClient
import io.panelassistant.android.util.GuardDbMaintenanceTransport
import io.panelassistant.android.util.GuardDbMaintenanceProtocol
import io.panelassistant.android.util.GuardDbSentinelStore
import io.panelassistant.android.util.GuardDbSettingsAuthority
import io.panelassistant.android.util.InstallProgress
import io.ktor.client.request.header
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.plugins.mutableOriginConnectionPoint
import io.ktor.server.plugins.origin
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GuardDbBootstrapRoutesTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `Hardened stage consumes only the exact direct LAN replay once`() = testApplication {
        val fixture = stageFixture()
        installRoutes(fixture)

        val challenged = postStage(fixture.token, "A", DIRECT_PEER)
        assertEquals(HttpStatusCode.Accepted, challenged.status)
        assertTrue(challenged.bodyAsText().contains("approval-required"))
        val approval = fixture.broker.pending().single()
        assertTrue(fixture.broker.approve(approval.id))

        val accepted = postStage(fixture.token, "A", DIRECT_PEER)
        assertEquals(HttpStatusCode.OK, accepted.status)
        assertTrue(accepted.bodyAsText().contains("\"role\":\"A\""))
        assertTrue(fixture.staging.load(GuardDbMaintenanceProtocol.Role.A) != null)
        assertTrue(fixture.broker.pending().isEmpty())
        assertEquals(
            "guard-db-candidate-staging-finished",
            JSONObject(InstallProgress.json()).getJSONObject("presentation").getString("code"),
        )

        val consumed = postStage(fixture.token, "A", DIRECT_PEER)
        assertEquals(HttpStatusCode.Conflict, consumed.status)
        assertTrue(consumed.bodyAsText().contains("stale-or-missing-upload"))
    }

    @Test fun `candidate body and peer changes cannot spend an approved stage request`() = testApplication {
        val fixture = stageFixture()
        installRoutes(fixture)

        assertEquals(HttpStatusCode.Accepted, postStage(fixture.token, "A", DIRECT_PEER).status)
        val exact = fixture.broker.pending().single()
        assertTrue(fixture.broker.approve(exact.id))

        fixture.inspection = fixture.inspection.copy(versionCode = fixture.inspection.versionCode + 1)
        assertEquals(HttpStatusCode.Accepted, postStage(fixture.token, "A", DIRECT_PEER).status)
        val changedCandidate = fixture.broker.pending().single()
        assertNotEquals(exact.id, changedCandidate.id)
        assertTrue(fixture.broker.deny(changedCandidate.id))
        fixture.inspection = fixture.inspection.copy(versionCode = fixture.inspection.versionCode - 1)

        fixture.inspection = fixture.inspection.copy(settingsAuthoritySha256 = "f".repeat(64))
        assertEquals(HttpStatusCode.Accepted, postStage(fixture.token, "A", DIRECT_PEER).status)
        val changedSettingsAuthority = fixture.broker.pending().single()
        assertNotEquals(exact.id, changedSettingsAuthority.id)
        assertTrue(fixture.broker.deny(changedSettingsAuthority.id))
        fixture.inspection = fixture.inspection.copy(settingsAuthoritySha256 = "e".repeat(64))

        assertEquals(HttpStatusCode.Accepted, postStage(fixture.token, "B", DIRECT_PEER).status)
        val changedBody = fixture.broker.pending().single()
        assertNotEquals(exact.id, changedBody.id)
        assertTrue(fixture.broker.deny(changedBody.id))

        assertEquals(HttpStatusCode.Accepted, postStage(fixture.token, "A", OTHER_DIRECT_PEER).status)
        val changedPeer = fixture.broker.pending().single()
        assertNotEquals(exact.id, changedPeer.id)
        assertTrue(fixture.broker.deny(changedPeer.id))

        assertEquals(HttpStatusCode.OK, postStage(fixture.token, "A", DIRECT_PEER).status)
    }

    @Test fun `route refuses loopback public and malformed peers before approval`() = testApplication {
        val fixture = stageFixture()
        installRoutes(fixture)

        listOf("127.0.0.1", "::1", "198.51.100.2", "not-an-address").forEach { peer ->
            val response = postStage(fixture.token, "A", peer)
            assertEquals(peer, HttpStatusCode.Forbidden, response.status)
            assertTrue(response.bodyAsText().contains("direct-lan-required"))
        }
        assertTrue(fixture.broker.pending().isEmpty())
        assertEquals(0, fixture.authorizationCalls)
    }

    @Test fun `Relaxed mode cannot create or seed a stage approval`() = testApplication {
        val fixture = stageFixture().apply { hardened = false }
        installRoutes(fixture)

        val refused = postStage(fixture.token, "A", DIRECT_PEER)
        assertEquals(HttpStatusCode.PreconditionFailed, refused.status)
        assertTrue(refused.bodyAsText().contains("hardened-required"))
        assertTrue(fixture.broker.pending().isEmpty())
        assertEquals(0, fixture.authorizationCalls)

        fixture.hardened = true
        assertEquals(HttpStatusCode.Accepted, postStage(fixture.token, "A", DIRECT_PEER).status)
        assertEquals(1, fixture.authorizationCalls)
        assertEquals(1, fixture.broker.pending().size)
    }

    @Test fun `ARM approval cannot be spent by changed session boot candidate or peer`() = testApplication {
        val fixture = armFixture()
        installRoutes(fixture.dependencies())
        val exactSession = "1".repeat(64)
        val budget = MIN_OVERALL_BUDGET_MS

        assertEquals(HttpStatusCode.Accepted, postArm(exactSession, budget, DIRECT_PEER).status)
        val exact = fixture.broker.pending().single()
        assertTrue(fixture.broker.approve(exact.id))

        assertEquals(HttpStatusCode.Accepted, postArm("9".repeat(64), budget, DIRECT_PEER).status)
        val changedSession = fixture.broker.pending().single()
        assertNotEquals(exact.id, changedSession.id)
        assertTrue(fixture.broker.deny(changedSession.id))

        assertEquals(HttpStatusCode.Accepted, postArm(exactSession, budget + 1L, DIRECT_PEER).status)
        val changedBudget = fixture.broker.pending().single()
        assertNotEquals(exact.id, changedBudget.id)
        assertTrue(fixture.broker.deny(changedBudget.id))

        val originalSettingsAuthority = fixture.settingsAuthority
        fixture.settingsAuthority = originalSettingsAuthority.copy(sha256 = "d".repeat(64))
        fixture.a = fixture.a.copy(settingsAuthoritySha256 = fixture.settingsAuthority.sha256)
        fixture.b = fixture.b.copy(settingsAuthoritySha256 = fixture.settingsAuthority.sha256)
        assertEquals(HttpStatusCode.Accepted, postArm(exactSession, budget, DIRECT_PEER).status)
        val changedSettingsAuthority = fixture.broker.pending().single()
        assertNotEquals(exact.id, changedSettingsAuthority.id)
        assertTrue(fixture.broker.deny(changedSettingsAuthority.id))
        fixture.settingsAuthority = originalSettingsAuthority
        fixture.a = fixture.a.copy(settingsAuthoritySha256 = originalSettingsAuthority.sha256)
        fixture.b = fixture.b.copy(settingsAuthoritySha256 = originalSettingsAuthority.sha256)

        fixture.boot = "3".repeat(64)
        assertEquals(HttpStatusCode.Accepted, postArm(exactSession, budget, DIRECT_PEER).status)
        val changedBoot = fixture.broker.pending().single()
        assertNotEquals(exact.id, changedBoot.id)
        assertTrue(fixture.broker.deny(changedBoot.id))
        fixture.boot = "2".repeat(64)

        fixture.b = fixture.b.copy(versionCode = fixture.b.versionCode + 1)
        assertEquals(HttpStatusCode.Accepted, postArm(exactSession, budget, DIRECT_PEER).status)
        val changedCandidate = fixture.broker.pending().single()
        assertNotEquals(exact.id, changedCandidate.id)
        assertTrue(fixture.broker.deny(changedCandidate.id))
        fixture.b = fixture.b.copy(versionCode = fixture.b.versionCode - 1)

        assertEquals(HttpStatusCode.Accepted, postArm(exactSession, budget, OTHER_DIRECT_PEER).status)
        val changedPeer = fixture.broker.pending().single()
        assertNotEquals(exact.id, changedPeer.id)
        assertTrue(fixture.broker.deny(changedPeer.id))

        val consumed = postArm(exactSession, budget, DIRECT_PEER)
        assertEquals(HttpStatusCode.PreconditionFailed, consumed.status)
        assertTrue(consumed.bodyAsText().contains("hardened-debug-off-proof-required"))
        assertTrue(fixture.broker.pending().isEmpty())
        assertTrue(!InstallProgress.running)
    }

    @Test fun `clock and ARM advertise and enforce one exact bounded duration budget`() = testApplication {
        val fixture = armFixture()
        installRoutes(fixture.dependencies())
        val peer = DIRECT_PEER
        val session = "1".repeat(64)

        val clock = getClock(peer)
        assertEquals(HttpStatusCode.OK, clock.status)
        assertTrue(clock.bodyAsText().contains("\"elapsed_realtime_ms\":${fixture.now}"))
        assertTrue(clock.bodyAsText().contains("\"minimum_overall_budget_ms\":$MIN_OVERALL_BUDGET_MS"))
        assertTrue(clock.bodyAsText().contains("\"maximum_overall_budget_ms\":$MAX_OVERALL_BUDGET_MS"))
        assertTrue(clock.bodyAsText().contains("\"recovery_reserve_ms\":$RECOVERY_RESERVE_MS"))
        assertEquals(HttpStatusCode.Forbidden, getClock("127.0.0.1").status)
        assertEquals(HttpStatusCode.Forbidden, getClock("198.51.100.2").status)

        listOf(
            MIN_OVERALL_BUDGET_MS - 1L,
            MAX_OVERALL_BUDGET_MS + 1L,
        ).forEach { budget ->
            val refused = postArm(session, budget, peer)
            assertEquals(budget.toString(), HttpStatusCode.BadRequest, refused.status)
            assertTrue(refused.bodyAsText().contains("invalid-overall-budget"))
        }
        assertTrue(postArm(session, 0L, peer).bodyAsText().contains("invalid-arm-request"))
        assertTrue(fixture.broker.pending().isEmpty())

        val exactMinimum = MIN_OVERALL_BUDGET_MS
        assertEquals(HttpStatusCode.Accepted, postArm(session, exactMinimum, peer).status)
        assertTrue(fixture.broker.deny(fixture.broker.pending().single().id))

        val exactMaximum = MAX_OVERALL_BUDGET_MS
        assertEquals(HttpStatusCode.Accepted, postArm(session, exactMaximum, peer).status)
        assertTrue(fixture.broker.approve(fixture.broker.pending().single().id))
        fixture.afterApproved = { fixture.now += MAX_OVERALL_BUDGET_MS * 10L }

        val replayAfterClockAdvance = postArm(session, exactMaximum, peer)
        assertEquals(HttpStatusCode.PreconditionFailed, replayAfterClockAdvance.status)
        assertTrue(replayAfterClockAdvance.bodyAsText().contains("hardened-debug-off-proof-required"))
        assertEquals("a duration budget is not reinterpreted as an absolute timestamp", 1, fixture.sentinelCommitCalls)
        assertTrue(!InstallProgress.running)
    }

    @Test fun `bootstrap status and evidence remain direct LAN typed reconciliation reads`() = testApplication {
        val fixture = armFixture()
        installRoutes(fixture.dependencies())
        val peer = DIRECT_PEER

        val status = getStatus(peer)
        assertEquals(HttpStatusCode.OK, status.status)
        assertTrue(status.bodyAsText().contains("\"phase\":\"EMPTY\""))
        assertTrue(status.bodyAsText().contains("\"error\":\"\""))
        assertTrue(status.bodyAsText().contains("\"overall_deadline_elapsed_ms\":0"))
        assertEquals(HttpStatusCode.Forbidden, getStatus("127.0.0.1").status)

        assertEquals(HttpStatusCode.BadRequest, getEvidence("bad", peer).status)
        assertEquals(HttpStatusCode.Forbidden, getEvidence("1".repeat(64), "127.0.0.1").status)
        assertEquals(HttpStatusCode.ServiceUnavailable, getEvidence("1".repeat(64), peer).status)
    }

    @Test fun `Hardened to Relaxed to Hardened transition invalidates a staged approval`() = testApplication {
        val fixture = stageFixture()
        installRoutes(fixture)

        assertEquals(HttpStatusCode.Accepted, postStage(fixture.token, "A", DIRECT_PEER).status)
        val stale = fixture.broker.pending().single()
        assertTrue(fixture.broker.approve(stale.id))

        RemoteDebugSecurityTransitionGate.mutate { fixture.hardened = false }
        RemoteDebugSecurityTransitionGate.mutate { fixture.hardened = true }

        val replay = postStage(fixture.token, "A", DIRECT_PEER)
        assertEquals(HttpStatusCode.Accepted, replay.status)
        assertTrue(replay.bodyAsText().contains("approval-required"))
        val fresh = fixture.broker.pending().single()
        assertNotEquals(stale.id, fresh.id)
        assertTrue(fixture.pending.peek(fixture.token) != null)
    }

    @Test fun `remote debug authority change after approval cannot cross the stage commit`() = testApplication {
        val fixture = stageFixture()
        installRoutes(fixture)

        assertEquals(HttpStatusCode.Accepted, postStage(fixture.token, "A", DIRECT_PEER).status)
        assertTrue(fixture.broker.approve(fixture.broker.pending().single().id))
        fixture.afterApproved = { RemoteDebugSecurityTransitionGate.mutate {} }

        val crossed = postStage(fixture.token, "A", DIRECT_PEER)
        assertEquals(HttpStatusCode.Conflict, crossed.status)
        assertTrue(crossed.bodyAsText().contains("stage-authority-changed"))
        assertTrue(fixture.pending.peek(fixture.token) != null)
        assertTrue(fixture.staging.load(GuardDbMaintenanceProtocol.Role.A) == null)
        assertTrue(!InstallProgress.running)
    }

    @Test fun `remote debug authority change after ARM approval cannot cross sentinel commit`() = testApplication {
        val fixture = armFixture()
        installRoutes(fixture.dependencies())
        val session = "1".repeat(64)
        val budget = MIN_OVERALL_BUDGET_MS

        assertEquals(HttpStatusCode.Accepted, postArm(session, budget, DIRECT_PEER).status)
        assertTrue(fixture.broker.approve(fixture.broker.pending().single().id))
        fixture.afterApproved = { RemoteDebugSecurityTransitionGate.mutate {} }

        val crossed = postArm(session, budget, DIRECT_PEER)
        assertEquals(HttpStatusCode.PreconditionFailed, crossed.status)
        assertTrue(crossed.bodyAsText().contains("hardened-debug-off-proof-required"))
        assertEquals(0, fixture.sentinelCommitCalls)
        assertTrue(fixture.sentinel.load() is io.panelassistant.android.util.GuardDbSentinelLoad.Absent)
        assertTrue(!InstallProgress.running)
    }

    private fun ApplicationTestBuilder.installRoutes(fixture: StageFixture) {
        installRoutes(fixture.dependencies())
    }

    private fun ApplicationTestBuilder.installRoutes(dependencies: GuardDbBootstrapRouteDependencies) {
        application {
            intercept(ApplicationCallPipeline.Setup) {
                context.mutableOriginConnectionPoint.remoteAddress =
                    context.request.headers[TEST_PEER_HEADER] ?: "127.0.0.1"
            }
            routing { guardDbBootstrapRoutes(dependencies) }
        }
    }

    private suspend fun ApplicationTestBuilder.postStage(token: String, role: String, peer: String) =
        client.post("/api/v1/guard-db/stage") {
            header(TEST_PEER_HEADER, peer)
            contentType(ContentType.Application.Json)
            setBody("{\"token\":\"$token\",\"role\":\"$role\"}")
        }

    private suspend fun ApplicationTestBuilder.getClock(peer: String) =
        client.get("/api/v1/guard-db/clock") { header(TEST_PEER_HEADER, peer) }

    private suspend fun ApplicationTestBuilder.getStatus(peer: String) =
        client.get("/api/v1/guard-db/status") { header(TEST_PEER_HEADER, peer) }

    private suspend fun ApplicationTestBuilder.getEvidence(session: String, peer: String) =
        client.get("/api/v1/guard-db/evidence?session=$session") { header(TEST_PEER_HEADER, peer) }

    private suspend fun ApplicationTestBuilder.postArm(session: String, budget: Long, peer: String) =
        client.post("/api/v1/guard-db/arm") {
            header(TEST_PEER_HEADER, peer)
            contentType(ContentType.Application.Json)
            setBody("{\"session\":\"$session\",\"overall_budget_ms\":$budget}")
        }

    private fun stageFixture(): StageFixture {
        val token = "guard-upload-token"
        val pending = PendingUploadStore(newToken = { token }, newDiscardId = { "discard" })
        pending.open()
        val lease = (pending.begin() as PendingUploadStore.BeginResult.Granted).lease
        val source = temporary.newFile("${System.nanoTime()}-candidate.apk").apply {
            writeBytes(byteArrayOf(1, 2, 3, 4))
        }
        check(pending.stage(lease, source)?.token == token)
        val stagingDirectory = temporary.newFolder("${System.nanoTime()}-staging")
        lateinit var fixture: StageFixture
        val staging = GuardDbAppStaging(
            directory = stagingDirectory,
            inspect = { file -> fixture.inspection.takeIf { file.isFile && file.length() == it.bytes } },
            syncDirectory = { true },
            copyAndSync = { input, output -> input.copyTo(output, overwrite = true).let { true } },
            atomicMove = { input, output -> input.renameTo(output) },
            validateFile = { it.isFile },
        )
        fixture = StageFixture(
            token = token,
            pending = pending,
            staging = staging,
            sentinel = GuardDbSentinelStore(
                temporary.newFolder("${System.nanoTime()}-sentinel"),
                syncDirectory = { true },
                validateMarker = { it.isFile },
            ),
        )
        return fixture
    }

    private fun armFixture(): ArmFixture {
        val directory = temporary.newFolder("${System.nanoTime()}-arm-staging")
        File(directory, "guard-db-candidate-a.apk").writeBytes(byteArrayOf(1, 2, 3, 4))
        File(directory, "guard-db-candidate-b.apk").writeBytes(byteArrayOf(5, 6, 7, 8))
        lateinit var fixture: ArmFixture
        val staging = GuardDbAppStaging(
            directory = directory,
            inspect = { file ->
                when (file.name) {
                    "guard-db-candidate-a.apk" -> fixture.a
                    "guard-db-candidate-b.apk" -> fixture.b
                    else -> null
                }
            },
            syncDirectory = { true },
            copyAndSync = { _, _ -> false },
            atomicMove = { _, _ -> false },
            validateFile = { it.isFile },
        )
        fixture = ArmFixture(
            staging,
            GuardDbSentinelStore(
                temporary.newFolder("${System.nanoTime()}-arm-sentinel"),
                syncDirectory = { true },
                validateMarker = { it.isFile },
            ),
            File(directory, "guard-db-settings-authority-v2").apply { writeText("S2\n") }.let { file ->
                GuardDbSettingsAuthority(
                    GuardDbSettingsAuthority.VERSION,
                    file,
                    file.length(),
                    AppInstaller.sha256(file),
                )
            },
        )
        return fixture
    }

    private class StageFixture(
        val token: String,
        val pending: PendingUploadStore,
        val staging: GuardDbAppStaging,
        val sentinel: GuardDbSentinelStore,
    ) {
        val broker = ApprovalBroker(monotonicMs = { 1_000L }, random = SecureRandom())
        var authorizationCalls = 0
        var hardened = true
        var afterApproved: () -> Unit = {}
        var inspection = GuardDbCandidateInspection(
            bytes = 4,
            sha256 = MessageDigest.getInstance("SHA-256")
                .digest(byteArrayOf(1, 2, 3, 4))
                .joinToString("") { "%02x".format(it) },
            versionCode = 568,
            signerSha256 = "c".repeat(64),
            contractMinimum = 11,
            contractMaximum = 14,
            expectedSchema = 14,
            settingsAuthorityVersion = GuardDbSettingsAuthority.VERSION,
            settingsAuthorityBytes = 3L,
            settingsAuthoritySha256 = "e".repeat(64),
        )

        fun dependencies() = GuardDbBootstrapRouteDependencies(
            pendingUploads = pending,
            staging = staging,
            inspectPending = { inspection },
            inspectInstalled = { null },
            settingsAuthority = { null },
            client = GuardDbMaintenanceClient(NoHelperTransport),
            sentinelStore = sentinel,
            bootNonce = { null },
            monotonicMs = { 1_000L },
            httpPort = { 8888 },
            hardened = { hardened },
            securityEpoch = RemoteDebugSecurityTransitionGate::authorityEpoch,
            commitSentinel = { _, _ -> GuardDbSentinelCommit.SecurityRefused },
            authorize = { call, operation, payload, summary ->
                authorizationCalls++
                val authorized = authorizeSensitiveRequest(
                    call = call,
                    hardened = true,
                    peer = call.request.origin.remoteAddress,
                    operation = operation,
                    payload = payload,
                    summary = summary,
                    broker = broker,
                )
                if (authorized) afterApproved()
                authorized
            },
            prepare = { _, _ -> false },
            contain = {},
        )
    }

    private class ArmFixture(
        val staging: GuardDbAppStaging,
        val sentinel: GuardDbSentinelStore,
        var settingsAuthority: GuardDbSettingsAuthority,
    ) {
        val broker = ApprovalBroker(monotonicMs = { 1_000L }, random = SecureRandom())
        val securityEpoch = RemoteDebugSecurityTransitionGate.authorityEpoch().takeIf { it > 0L } ?: run {
            RemoteDebugSecurityTransitionGate.mutate {}
            RemoteDebugSecurityTransitionGate.authorityEpoch()
        }
        var boot = "2".repeat(64)
        var now = 1_000L
        var afterApproved: () -> Unit = {}
        var sentinelCommitCalls = 0
        var a = GuardDbCandidateInspection(
            bytes = 4,
            sha256 = "a".repeat(64),
            versionCode = 568,
            signerSha256 = "c".repeat(64),
            contractMinimum = 11,
            contractMaximum = 14,
            expectedSchema = 14,
            settingsAuthorityVersion = settingsAuthority.version,
            settingsAuthorityBytes = settingsAuthority.bytes,
            settingsAuthoritySha256 = settingsAuthority.sha256,
        )
        var b = GuardDbCandidateInspection(
            bytes = 4,
            sha256 = "b".repeat(64),
            versionCode = 569,
            signerSha256 = "c".repeat(64),
            contractMinimum = 11,
            contractMaximum = 15,
            expectedSchema = 15,
            settingsAuthorityVersion = settingsAuthority.version,
            settingsAuthorityBytes = settingsAuthority.bytes,
            settingsAuthoritySha256 = settingsAuthority.sha256,
        )

        fun dependencies() = GuardDbBootstrapRouteDependencies(
            pendingUploads = PendingUploadStore().apply { open() },
            staging = staging,
            inspectPending = { null },
            inspectInstalled = { a },
            settingsAuthority = { settingsAuthority },
            client = GuardDbMaintenanceClient(ReadyHelperTransport),
            sentinelStore = sentinel,
            bootNonce = { boot },
            monotonicMs = { now },
            httpPort = { 8888 },
            hardened = { true },
            securityEpoch = { securityEpoch },
            commitSentinel = { expectedEpoch, _ ->
                when (val commit = RemoteDebugSecurityTransitionGate.withEpoch(expectedEpoch) {
                    sentinelCommitCalls++
                    GuardDbSentinelCommit.SecurityRefused
                }) {
                    RemoteDebugAuthorityResult.Changed -> GuardDbSentinelCommit.SecurityRefused
                    is RemoteDebugAuthorityResult.Value -> commit.value
                }
            },
            authorize = { call, operation, payload, summary ->
                val authorized = authorizeSensitiveRequest(
                    call = call,
                    hardened = true,
                    peer = call.request.origin.remoteAddress,
                    operation = operation,
                    payload = payload,
                    summary = summary,
                    broker = broker,
                )
                if (authorized) afterApproved()
                authorized
            },
            prepare = { _, _ -> false },
            contain = {},
        )
    }

    private object NoHelperTransport : GuardDbMaintenanceTransport {
        override fun send(command: String): String? = null
        override fun sendLong(command: String, timeoutMs: Long) = DaemonLongResult.NotSubmitted
        override fun sendFile(command: String, file: File, timeoutMs: Long) = DaemonStreamResult.NotSubmitted
        override fun sendBytesBounded(command: String, maxBytes: Long): ByteArray? = null
    }

    private object ReadyHelperTransport : GuardDbMaintenanceTransport {
        override fun send(command: String): String? = when (command) {
            "GUARDCAPS" ->
                "${GuardDbMaintenanceProtocol.CAPS_REPLY} AUTONOMOUS SUPERVISED TERMINAL_RETIRE"
            "GUARDSTATUS" -> "OK GUARDSTATUS 0 EMPTY NONE NONE NONE NONE 0 0 0 NONE NONE 0 0"
            else -> null
        }
        override fun sendLong(command: String, timeoutMs: Long) = DaemonLongResult.NotSubmitted
        override fun sendFile(command: String, file: File, timeoutMs: Long) = DaemonStreamResult.NotSubmitted
        override fun sendBytesBounded(command: String, maxBytes: Long): ByteArray? = null
    }

    private companion object {
        const val TEST_PEER_HEADER = "X-Test-Peer"
        val DIRECT_PEER = listOf(192, 168, 20, 30).joinToString(".")
        val OTHER_DIRECT_PEER = listOf(192, 168, 20, 31).joinToString(".")
    }
}
