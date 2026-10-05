package io.panelassistant.android.http

import io.panelassistant.android.platform.DaemonLongResult
import io.panelassistant.android.platform.DaemonStreamResult
import io.panelassistant.android.security.ApprovalBroker
import io.panelassistant.android.util.GuardDbAppStaging
import io.panelassistant.android.util.GuardDbArmManifest
import io.panelassistant.android.util.GuardDbExactARefusalProof
import io.panelassistant.android.util.GuardDbMaintenanceClient
import io.panelassistant.android.util.GuardDbMaintenanceProtocol
import io.panelassistant.android.util.GuardDbMaintenanceTransport
import io.panelassistant.android.util.GuardDbPreparedArm
import io.panelassistant.android.util.GuardDbPreparedArmLoad
import io.panelassistant.android.util.GuardDbPreparedArmStore
import io.panelassistant.android.util.GuardDbSentinelState
import io.panelassistant.android.util.GuardDbSentinelStore
import io.panelassistant.android.util.GuardDbSettingsAuthority
import io.panelassistant.android.util.GuardDbStartupSentinel
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
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.io.File
import java.security.SecureRandom
import java.util.ArrayDeque
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GuardDbMaintenanceServerRoutesTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `successor admits direct LAN and refuses loopback before route work`() = testApplication {
        val fixture = fixture()
        install(fixture)

        assertEquals(HttpStatusCode.OK, getHealth(DIRECT_PEER).status)
        assertEquals(HttpStatusCode.Forbidden, getHealth("127.0.0.1").status)
        assertEquals(HttpStatusCode.Forbidden, getHealth("::1").status)
        assertTrue(fixture.transport.commands.isEmpty())
        assertTrue(fixture.broker.pending().isEmpty())
    }

    @Test fun `public action route refuses rollback before approval or helper claim`() = testApplication {
        val fixture = fixture().apply { transport.currentStatus = rollbackRequired() }
        install(fixture)

        val response = postAction(
            actionBody(fixture.session, 10L, GuardDbMaintenanceProtocol.Action.ROLLBACK.name),
            DIRECT_PEER,
        )

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("invalid-action"))
        assertTrue(fixture.broker.pending().isEmpty())
        assertTrue(fixture.transport.commands.isEmpty())
    }

    @Test fun `approved finalize turns rollback cause into terminal typed outcome`() = testApplication {
        val cause = "HEALTH_FAILED"
        val terminalOutcome = GuardDbMaintenanceProtocol.Outcome.ROLLED_BACK_HEALTH_FAILED.name
        val fixture = fixture().apply { transport.currentStatus = aHealthy(error = cause) }
        install(fixture)
        val body = actionBody(fixture.session, 10L)

        assertEquals(HttpStatusCode.Accepted, postAction(body, DIRECT_PEER).status)
        assertTrue(fixture.broker.approve(fixture.broker.pending().single().id))
        repeat(2) { fixture.transport.statusReplies.add(fixture.aHealthy(error = cause)) }
        fixture.transport.statusReplies.add(fixture.finalized(terminalOutcome))

        val response = postAction(body, DIRECT_PEER)
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("\"outcome\":\"$terminalOutcome\""))
        assertEquals(1, fixture.transport.commands.count { it.startsWith("GUARDACTION ") })
        assertEquals(1, fixture.restartCalls)
    }

    @Test fun `ARM commit revalidates exact custody authority and submits once after approval`() {
        testApplication {
            val fixture = fixture(GuardDbSentinelState.BASELINE_READY).apply {
                transport.armSupported = true
                transport.currentStatus = empty()
            }
            install(fixture)
            val body = generationBody(fixture.session, 0L)
            assertEquals(HttpStatusCode.Accepted, postArmCommit(body, DIRECT_PEER).status)
            assertTrue(fixture.broker.approve(fixture.broker.pending().single().id))
            fixture.afterExactManifest = {
                fixture.loadedPrepared = fixture.prepared.copy(databaseSha256 = "9".repeat(64))
            }

            val changed = postArmCommit(body, DIRECT_PEER)
            assertEquals(HttpStatusCode.Conflict, changed.status)
            assertTrue(changed.bodyAsText().contains("arm-commit-authority-changed"))
            assertEquals(3, fixture.exactManifestCalls)
            assertTrue(fixture.transport.commands.none { it.startsWith("GUARDPREPARE ") })
            assertTrue(fixture.promotedSessions.isEmpty())
        }

        testApplication {
            val fixture = fixture(GuardDbSentinelState.BASELINE_READY).apply {
                transport.armSupported = true
                transport.currentStatus = empty()
            }
            install(fixture)
            val body = generationBody(fixture.session, 0L)
            assertEquals(HttpStatusCode.Accepted, postArmCommit(body, DIRECT_PEER).status)
            assertTrue(fixture.broker.approve(fixture.broker.pending().single().id))
            fixture.transport.afterStatusRead = {
                fixture.transport.currentStatus = fixture.staging()
            }

            val changed = postArmCommit(body, DIRECT_PEER)
            assertEquals(HttpStatusCode.Conflict, changed.status)
            assertTrue(changed.bodyAsText().contains("arm-commit-authority-changed"))
            assertEquals(3, fixture.exactManifestCalls)
            assertTrue(fixture.transport.commands.none { it.startsWith("GUARDPREPARE ") })
            assertTrue(fixture.promotedSessions.isEmpty())
        }

        testApplication {
            val fixture = fixture(GuardDbSentinelState.BASELINE_READY).apply {
                transport.armSupported = true
                transport.currentStatus = empty()
            }
            install(fixture)
            val body = generationBody(fixture.session, 0L)
            assertEquals(HttpStatusCode.Accepted, postArmCommit(body, DIRECT_PEER).status)
            val consumedApproval = fixture.broker.pending().single().id
            assertTrue(fixture.broker.approve(consumedApproval))
            fixture.security.mode = SecurityMode.CHANGED

            val held = postArmCommit(body, DIRECT_PEER)
            assertEquals(HttpStatusCode.Accepted, held.status)
            assertTrue(held.bodyAsText().contains(
                "\"retry_policy\":\"fresh-approval-after-exact-empty\"",
            ))
            withTimeout(1_000L) { fixture.security.commitObserved.await() }
            assertEquals(3, fixture.exactManifestCalls)
            assertTrue(fixture.transport.commands.none { it.startsWith("GUARDPREPARE ") })
            assertTrue(fixture.promotedSessions.isEmpty())

            fixture.security.mode = SecurityMode.VALUE
            val freshApprovalRequired = postArmCommit(body, DIRECT_PEER)
            assertEquals(HttpStatusCode.Accepted, freshApprovalRequired.status)
            assertTrue(freshApprovalRequired.bodyAsText().contains("approval-required"))
            val freshApproval = fixture.broker.pending().single().id
            assertNotEquals(consumedApproval, freshApproval)
            assertTrue(fixture.broker.approve(freshApproval))
            repeat(3) { fixture.transport.statusReplies.add(fixture.empty()) }
            fixture.transport.statusReplies.add(fixture.waitBHealth())
            fixture.transport.longReplies.add(DaemonLongResult.Reply("OK GUARDPREPARE 1 STAGING"))
            fixture.transport.longReplies.add(DaemonLongResult.Reply("OK GUARDDEFINE 2 STAGING"))
            fixture.transport.longReplies.add(DaemonLongResult.Reply("OK GUARDDEFINE 3 STAGING"))
            fixture.transport.longReplies.add(DaemonLongResult.Indeterminate)
            fixture.transport.streamReplies.add(DaemonStreamResult.Reply("OK GUARDSTREAM 4 STAGING"))
            fixture.transport.streamReplies.add(DaemonStreamResult.Reply("OK GUARDSTREAM 5 STAGING"))
            fixture.transport.streamReplies.add(DaemonStreamResult.Reply("OK GUARDSTREAM 6 STAGING"))

            val retried = postArmCommit(body, DIRECT_PEER)
            assertEquals(HttpStatusCode.Accepted, retried.status)
            assertTrue(retried.bodyAsText().contains(
                "\"retry_policy\":\"fresh-approval-after-exact-empty\"",
            ))
            withTimeout(1_000L) { fixture.promoted.await() }
            assertEquals(1, fixture.transport.commands.count { it.startsWith("GUARDPREPARE ") })
            assertEquals(listOf(fixture.session), fixture.promotedSessions)
        }

        testApplication {
            val fixture = fixture(GuardDbSentinelState.BASELINE_READY).apply {
                transport.armSupported = true
                transport.currentStatus = empty()
            }
            install(fixture)
            val body = generationBody(fixture.session, 0L)
            assertEquals(HttpStatusCode.Accepted, postArmCommit(body, DIRECT_PEER).status)
            assertTrue(fixture.broker.approve(fixture.broker.pending().single().id))
            repeat(3) { fixture.transport.statusReplies.add(fixture.empty()) }
            fixture.transport.statusReplies.add(fixture.waitBHealth())
            fixture.transport.longReplies.add(DaemonLongResult.Reply("OK GUARDPREPARE 1 STAGING"))
            fixture.transport.longReplies.add(DaemonLongResult.Reply("OK GUARDDEFINE 2 STAGING"))
            fixture.transport.longReplies.add(DaemonLongResult.Reply("OK GUARDDEFINE 3 STAGING"))
            fixture.transport.longReplies.add(DaemonLongResult.Indeterminate)
            fixture.transport.streamReplies.add(DaemonStreamResult.Reply("OK GUARDSTREAM 4 STAGING"))
            fixture.transport.streamReplies.add(DaemonStreamResult.Reply("OK GUARDSTREAM 5 STAGING"))
            fixture.transport.streamReplies.add(DaemonStreamResult.Reply("OK GUARDSTREAM 6 STAGING"))

            val accepted = postArmCommit(body, DIRECT_PEER)
            assertEquals(HttpStatusCode.Accepted, accepted.status)
            assertTrue(accepted.bodyAsText().contains("submitting-custody"))
            assertTrue(accepted.bodyAsText().contains(
                "\"retry_policy\":\"fresh-approval-after-exact-empty\"",
            ))
            withTimeout(1_000L) { fixture.promoted.await() }
            assertEquals(4, fixture.exactManifestCalls)
            assertEquals(1, fixture.transport.commands.count { it.startsWith("GUARDPREPARE ") })
            assertEquals(2, fixture.transport.commands.count { it.startsWith("GUARDDEFINE ") })
            assertEquals(3, fixture.transport.commands.count { it.startsWith("GUARDSTREAM ") })
            assertEquals(1, fixture.transport.commands.count { it.startsWith("GUARDACTION ") })
            assertEquals(listOf(fixture.session), fixture.promotedSessions)
        }
    }

    @Test fun `action approval is exact to peer body generation and canonical preview status`() = testApplication {
        val fixture = fixture()
        install(fixture)
        val body = actionBody(fixture.session, 10L)

        assertEquals(HttpStatusCode.Accepted, postAction(body, DIRECT_PEER).status)
        val exact = fixture.broker.pending().single()
        assertTrue(fixture.broker.approve(exact.id))

        assertEquals(HttpStatusCode.Accepted, postAction(body, OTHER_DIRECT_PEER).status)
        val changedPeer = fixture.broker.pending().single()
        assertNotEquals(exact.id, changedPeer.id)
        assertTrue(fixture.broker.deny(changedPeer.id))

        assertEquals(HttpStatusCode.Accepted, postAction(
            "{\"action\":\"FINALIZE\",\"generation\":10,\"session\":\"${fixture.session}\"}",
            DIRECT_PEER,
        ).status)
        val changedRawBody = fixture.broker.pending().single()
        assertNotEquals(exact.id, changedRawBody.id)
        assertTrue(fixture.broker.deny(changedRawBody.id))

        assertEquals(HttpStatusCode.Conflict, postAction(
            actionBody("9".repeat(64), 10L), DIRECT_PEER,
        ).status)
        assertEquals(HttpStatusCode.Conflict, postAction(
            actionBody(fixture.session, 11L), DIRECT_PEER,
        ).status)

        fixture.transport.currentStatus = fixture.aHealthy(error = "PREVIEW_CHANGED")
        assertEquals(HttpStatusCode.Accepted, postAction(body, DIRECT_PEER).status)
        val changedStatus = fixture.broker.pending().single()
        assertNotEquals(exact.id, changedStatus.id)
        assertTrue(fixture.broker.deny(changedStatus.id))
        fixture.transport.currentStatus = fixture.aHealthy()

        fixture.transport.statusReplies.add(fixture.aHealthy())
        fixture.transport.statusReplies.add(fixture.aHealthy())
        fixture.transport.statusReplies.add(fixture.finalized())
        assertEquals(HttpStatusCode.OK, postAction(body, DIRECT_PEER).status)
        assertEquals(1, fixture.transport.commands.count { it.startsWith("GUARDACTION ") })
        assertEquals(1, fixture.restartCalls)
        assertEquals(HttpStatusCode.Accepted, postAction(body, DIRECT_PEER).status)
        assertEquals(1, fixture.restartCalls)
    }

    @Test fun `approved action revalidates status and security at the mutation boundary`() {
        testApplication {
            val fixture = fixture()
            install(fixture)
            val body = actionBody(fixture.session, 10L)
            assertEquals(HttpStatusCode.Accepted, postAction(body, DIRECT_PEER).status)
            assertTrue(fixture.broker.approve(fixture.broker.pending().single().id))
            fixture.security.beforeAction = {
                fixture.transport.currentStatus = fixture.aHealthy(error = "POST_APPROVAL_CHANGED")
            }

            val response = postAction(body, DIRECT_PEER)
            assertEquals(HttpStatusCode.Conflict, response.status)
            assertTrue(response.bodyAsText().contains("stale-transition"))
            assertTrue(fixture.transport.commands.none { it.startsWith("GUARDACTION ") })
        }

        listOf(
            SecurityMode.CHANGED to HttpStatusCode.Conflict,
            SecurityMode.REFUSED to HttpStatusCode.PreconditionFailed,
        ).forEach { (mode, expected) ->
            testApplication {
                val fixture = fixture()
                install(fixture)
                val body = actionBody(fixture.session, 10L)
                assertEquals(HttpStatusCode.Accepted, postAction(body, DIRECT_PEER).status)
                assertTrue(fixture.broker.approve(fixture.broker.pending().single().id))
                fixture.security.mode = mode

                assertEquals(expected, postAction(body, DIRECT_PEER).status)
                assertTrue(fixture.transport.commands.none { it.startsWith("GUARDACTION ") })
                assertEquals(0, fixture.restartCalls)
            }
        }
    }

    @Test fun `indeterminate and malformed FINALIZE replies reconcile only through exact status`() {
        listOf(
            DaemonLongResult.Indeterminate,
            DaemonLongResult.Reply("malformed"),
        ).forEach { reply ->
            testApplication {
                val fixture = fixture().apply { transport.longResult = reply }
                install(fixture)
                val body = actionBody(fixture.session, 10L)
                assertEquals(HttpStatusCode.Accepted, postAction(body, DIRECT_PEER).status)
                assertTrue(fixture.broker.approve(fixture.broker.pending().single().id))
                fixture.transport.statusReplies.add(fixture.aHealthy())
                fixture.transport.statusReplies.add(fixture.aHealthy())
                fixture.transport.statusReplies.add(fixture.finalized())

                assertEquals(HttpStatusCode.OK, postAction(body, DIRECT_PEER).status)
                assertEquals(1, fixture.transport.commands.count { it.startsWith("GUARDACTION ") })
                assertEquals(1, fixture.restartCalls)
            }
        }

        testApplication {
            val fixture = fixture().apply {
                transport.longResult = DaemonLongResult.Indeterminate
                exactFinal = false
            }
            install(fixture)
            val body = actionBody(fixture.session, 10L)
            assertEquals(HttpStatusCode.Accepted, postAction(body, DIRECT_PEER).status)
            assertTrue(fixture.broker.approve(fixture.broker.pending().single().id))
            fixture.transport.statusReplies.add(fixture.aHealthy())
            fixture.transport.statusReplies.add(fixture.aHealthy())
            fixture.transport.statusReplies.add(fixture.finalized())

            val held = postAction(body, DIRECT_PEER)
            assertEquals(HttpStatusCode.Accepted, held.status)
            assertTrue(held.bodyAsText().contains("indeterminate-poll-status"))
            assertEquals(0, fixture.restartCalls)
            assertEquals(1, fixture.exactFinalCalls)
        }
    }

    @Test fun `refusal admits only exact WAIT_A_REFUSAL and binds repeated proof`() {
        testApplication {
            val fixture = fixture().apply { transport.currentStatus = waitARefusal() }
            install(fixture)
            val body = generationBody(fixture.session, 7L)
            assertEquals(HttpStatusCode.Accepted, postRefusal(body, DIRECT_PEER).status)
            val exact = fixture.broker.pending().single()
            assertTrue(fixture.broker.approve(exact.id))

            val original = fixture.proof
            fixture.proof = original.copy(databaseInventorySha256 = "9".repeat(64))
            assertEquals(HttpStatusCode.Accepted, postRefusal(body, DIRECT_PEER).status)
            val changedProof = fixture.broker.pending().single()
            assertNotEquals(exact.id, changedProof.id)
            assertTrue(fixture.broker.deny(changedProof.id))
            fixture.proof = original

            fixture.proofReplies.add(original)
            fixture.proofReplies.add(original.copy(databaseInventorySha256 = "7".repeat(64)))
            val changedAtCommit = postRefusal(body, DIRECT_PEER)
            assertEquals(HttpStatusCode.Conflict, changedAtCommit.status)
            assertTrue(changedAtCommit.bodyAsText().contains("refusal-authority-changed"))
            assertTrue(fixture.transport.commands.none { it.startsWith("GUARDREFUSAL ") })

            assertEquals(HttpStatusCode.Accepted, postRefusal(body, DIRECT_PEER).status)
            assertTrue(fixture.broker.approve(fixture.broker.pending().single().id))
            fixture.transport.statusReplies.add(fixture.waitARefusal())
            fixture.transport.statusReplies.add(fixture.waitARefusal())
            fixture.transport.statusReplies.add(fixture.aRefused())
            assertEquals(HttpStatusCode.OK, postRefusal(body, DIRECT_PEER).status)
            assertEquals(1, fixture.transport.commands.count { it.startsWith("GUARDREFUSAL ") })
            assertEquals(7, fixture.refusalProofCalls)
        }

        testApplication {
            val fixture = fixture().apply {
                transport.currentStatus = recoveryWithheld()
            }
            install(fixture)
            val refused = postRefusal(generationBody(fixture.session, 7L), DIRECT_PEER)
            assertEquals(HttpStatusCode.Conflict, refused.status)
            assertTrue(refused.bodyAsText().contains("refusal-not-pending"))
            assertTrue(fixture.broker.pending().isEmpty())
            assertEquals(0, fixture.refusalProofCalls)
            assertTrue(fixture.transport.commands.none { it.startsWith("GUARDREFUSAL ") })
        }
    }

    private fun ApplicationTestBuilder.install(fixture: Fixture) {
        application {
            intercept(ApplicationCallPipeline.Setup) {
                context.mutableOriginConnectionPoint.remoteAddress =
                    context.request.headers[TEST_PEER_HEADER] ?: "127.0.0.1"
            }
            fixture.server.configureGuardDbMaintenanceApplication(this)
        }
    }

    private suspend fun ApplicationTestBuilder.getHealth(peer: String) =
        client.get("/health") { header(TEST_PEER_HEADER, peer) }

    private suspend fun ApplicationTestBuilder.postAction(body: String, peer: String) =
        client.post("/api/v1/guard-db/action") {
            header(TEST_PEER_HEADER, peer)
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private suspend fun ApplicationTestBuilder.postArmCommit(body: String, peer: String) =
        client.post("/api/v1/guard-db/arm/commit") {
            header(TEST_PEER_HEADER, peer)
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private suspend fun ApplicationTestBuilder.postRefusal(body: String, peer: String) =
        client.post("/api/v1/guard-db/refusal") {
            header(TEST_PEER_HEADER, peer)
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private fun actionBody(
        session: String,
        generation: Long,
        action: String = GuardDbMaintenanceProtocol.Action.FINALIZE.name,
    ) = "{\"session\":\"$session\",\"generation\":$generation,\"action\":\"$action\"}"

    private fun generationBody(session: String, generation: Long) =
        "{\"session\":\"$session\",\"generation\":$generation}"

    private fun fixture(state: GuardDbSentinelState = GuardDbSentinelState.ARMED): Fixture =
        Fixture(temporary.newFolder("fixture-${System.nanoTime()}"), state)

    private class Fixture(directory: File, sentinelState: GuardDbSentinelState) {
        val session = "1".repeat(64)
        val boot = "2".repeat(64)
        val aSha = "a".repeat(64)
        val bSha = "b".repeat(64)
        val transport = ScriptedTransport()
        val broker = ApprovalBroker(monotonicMs = { 1_000L }, random = SecureRandom())
        val security = FakeSecurity()
        var restartCalls = 0
        var exactFinal = true
        var exactFinalCalls = 0
        var refusalProofCalls = 0
        var proof = GuardDbExactARefusalProof(aSha, 568L, bSha, 569L, "8".repeat(64))
        val proofReplies = ArrayDeque<GuardDbExactARefusalProof>()
        var exactManifestCalls = 0
        var afterExactManifest: () -> Unit = {}
        val promotedSessions = mutableListOf<String>()
        val promoted = CompletableDeferred<Unit>()
        val sentinel = GuardDbStartupSentinel(
            state = sentinelState,
            session = session,
            bootNonce = boot,
            aSha256 = aSha,
            aVersionCode = 568L,
            aSchema = 14,
            bSha256 = bSha,
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
            bBytes = 4L,
            bSha256 = bSha,
            bVersionCode = 569L,
            bContractMinimum = 11,
            bContractMaximum = 15,
            bSchema = 15,
            databaseBytes = 4096L,
            databaseSha256 = "3".repeat(64),
            databaseSchema = 14,
            appStateRows = 37L,
            orderedAppStateSha256 = "4".repeat(64),
            settingsSemanticSha256 = "5".repeat(64),
            overallBudgetMs = 1_800_000L,
            settingsAuthorityVersion = 2,
            settingsAuthorityBytes = 3L,
            settingsAuthoritySha256 = "c".repeat(64),
            securityAuthorityEpoch = 41L,
        )
        var loadedPrepared = prepared
        private val aFile = File(directory, "arm-a.apk").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        private val bFile = File(directory, "arm-b.apk").apply { writeBytes(byteArrayOf(5, 6, 7, 8)) }
        private val settingsFile = File(directory, "guard-db-settings-authority-v2").apply {
            writeBytes(byteArrayOf(1, 2, 3))
        }
        private val settingsAuthority = GuardDbSettingsAuthority(
            GuardDbSettingsAuthority.VERSION,
            settingsFile,
            settingsFile.length(),
            "c".repeat(64),
        )
        private val manifest = GuardDbArmManifest(
            session = session,
            bootNonce = boot,
            a = GuardDbMaintenanceProtocol.Candidate(
                GuardDbMaintenanceProtocol.Role.A, aFile, 4L, aSha, 568L, 11, 14, 14,
                settingsAuthority.version, settingsAuthority.bytes, settingsAuthority.sha256,
            ),
            b = GuardDbMaintenanceProtocol.Candidate(
                GuardDbMaintenanceProtocol.Role.B, bFile, 4L, bSha, 569L, 11, 15, 15,
                settingsAuthority.version, settingsAuthority.bytes, settingsAuthority.sha256,
            ),
            overallBudgetMs = 1_800_000L,
            settingsAuthority = settingsAuthority,
            securityAuthorityEpoch = 41L,
        )
        private val staging = GuardDbAppStaging(
            directory = directory,
            inspect = { null },
            syncDirectory = { true },
            copyAndSync = { _, _ -> false },
            atomicMove = { _, _ -> false },
            validateFile = { it.isFile },
        )
        val server = GuardDbMaintenanceServer(
            context = null,
            sentinel = sentinel,
            client = GuardDbMaintenanceClient(transport),
            staging = staging,
            preparedStore = GuardDbPreparedArmStore(directory),
            sentinelStore = GuardDbSentinelStore(directory),
            security = security,
            broker = broker,
            onFinalized = { restartCalls++ },
            loadPrepared = { GuardDbPreparedArmLoad.Valid(loadedPrepared) },
            exactManifest = {
                exactManifestCalls++
                val after = afterExactManifest
                afterExactManifest = {}
                after()
                manifest
            },
            promoteArmed = {
                promotedSessions += it
                promoted.complete(Unit)
                true
            },
            refusalProof = {
                refusalProofCalls++
                if (proofReplies.isEmpty()) proof else proofReplies.removeFirst()
            },
            exactFinalStatus = { exactFinalCalls++; exactFinal },
        )

        init {
            transport.currentStatus = aHealthy()
        }

        fun aHealthy(error: String = "NONE") = status(
            10L, GuardDbMaintenanceProtocol.Phase.A_HEALTHY, "A", aSha, 568L, 14,
            error = error,
        )

        fun finalized(outcome: String = GuardDbMaintenanceProtocol.Outcome.CANARY_PASSED.name) = status(
            11L, GuardDbMaintenanceProtocol.Phase.FINALIZED, "A", aSha, 568L, 14,
            outcome = outcome,
        )

        fun waitARefusal() = status(
            7L, GuardDbMaintenanceProtocol.Phase.WAIT_A_REFUSAL, "B", bSha, 569L, 15,
        )

        fun aRefused() = status(
            8L, GuardDbMaintenanceProtocol.Phase.A_REFUSED, "B", bSha, 569L, 15,
        )

        fun recoveryWithheld() = status(
            7L, GuardDbMaintenanceProtocol.Phase.RECOVERY_WITHHELD, "B", bSha, 569L, 15,
        )

        fun rollbackRequired() =
            "OK GUARDSTATUS 10 ROLLBACK_REQUIRED $session $boot NONE NONE 0 0 " +
                "37 NONE NONE 1800000 1320000"

        fun empty() = "OK GUARDSTATUS 0 EMPTY NONE NONE NONE NONE 0 0 0 NONE NONE 0 0"

        fun staging() = "OK GUARDSTATUS 1 STAGING $session $boot NONE NONE 0 0 " +
            "37 NONE NONE 1800000 1320000"

        fun waitBHealth() = status(
            7L, GuardDbMaintenanceProtocol.Phase.WAIT_B_HEALTH, "B", bSha, 569L, 15,
        )

        private fun status(
            generation: Long,
            phase: GuardDbMaintenanceProtocol.Phase,
            role: String,
            sha: String,
            versionCode: Long,
            schema: Int,
            error: String = "NONE",
            outcome: String = "NONE",
        ) = "OK GUARDSTATUS $generation ${phase.name} $session $boot $role $sha $versionCode $schema " +
            "37 $error $outcome 1800000 1320000"
    }

    private enum class SecurityMode { VALUE, CHANGED, REFUSED }

    private class FakeSecurity : GuardDbMaintenanceSecurityAuthority {
        var epoch: Long? = 41L
        var mode = SecurityMode.VALUE
        var beforeAction: () -> Unit = {}
        val commitObserved = CompletableDeferred<Unit>()

        override fun readyEpoch(): Long? = epoch

        override fun <T> commit(
            expectedEpoch: Long,
            action: () -> T,
        ): GuardDbMaintenanceSecurityResult<T> {
            val result = when {
                mode == SecurityMode.CHANGED || epoch != expectedEpoch -> GuardDbMaintenanceSecurityResult.Changed
                mode == SecurityMode.REFUSED -> GuardDbMaintenanceSecurityResult.Refused
                else -> {
                    beforeAction()
                    GuardDbMaintenanceSecurityResult.Value(action())
                }
            }
            commitObserved.complete(Unit)
            return result
        }
    }

    private class ScriptedTransport : GuardDbMaintenanceTransport {
        var currentStatus = ""
        val statusReplies = ArrayDeque<String>()
        val longReplies = ArrayDeque<DaemonLongResult>()
        val streamReplies = ArrayDeque<DaemonStreamResult>()
        var longResult: DaemonLongResult = DaemonLongResult.Indeterminate
        var armSupported = false
        var afterStatusRead: () -> Unit = {}
        val commands = mutableListOf<String>()

        override fun send(command: String): String? {
            commands += command
            return when (command) {
                "GUARDCAPS" -> if (armSupported) {
                    "${GuardDbMaintenanceProtocol.CAPS_REPLY} AUTONOMOUS SUPERVISED TERMINAL_RETIRE"
                } else null
                "GUARDSTATUS" -> {
                    val reply = if (statusReplies.isEmpty()) currentStatus else statusReplies.removeFirst()
                    val after = afterStatusRead
                    afterStatusRead = {}
                    after()
                    reply
                }
                else -> null
            }
        }

        override fun sendLong(command: String, timeoutMs: Long): DaemonLongResult {
            commands += command
            return if (longReplies.isEmpty()) longResult else longReplies.removeFirst()
        }

        override fun sendFile(command: String, file: File, timeoutMs: Long): DaemonStreamResult {
            commands += command
            return if (streamReplies.isEmpty()) DaemonStreamResult.NotSubmitted else streamReplies.removeFirst()
        }

        override fun sendBytesBounded(command: String, maxBytes: Long): ByteArray? = null
    }

    private companion object {
        const val TEST_PEER_HEADER = "X-Test-Peer"
        val DIRECT_PEER = listOf(192, 168, 20, 30).joinToString(".")
        val OTHER_DIRECT_PEER = listOf(192, 168, 20, 31).joinToString(".")
    }
}
