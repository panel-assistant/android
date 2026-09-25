package io.github.maxlyth.hapaneld.http

import io.github.maxlyth.hapaneld.control.RemoteDebugSecurityTransitionGate
import io.github.maxlyth.hapaneld.platform.DaemonLongResult
import io.github.maxlyth.hapaneld.platform.DaemonStreamResult
import io.github.maxlyth.hapaneld.security.ApprovalBroker
import io.github.maxlyth.hapaneld.util.GuardDbAppStaging
import io.github.maxlyth.hapaneld.util.GuardDbMaintenanceClient
import io.github.maxlyth.hapaneld.util.GuardDbMaintenanceProtocol
import io.github.maxlyth.hapaneld.util.GuardDbMaintenanceTransport
import io.github.maxlyth.hapaneld.util.GuardDbProcessAdmission
import io.github.maxlyth.hapaneld.util.GuardDbSentinelLoad
import io.github.maxlyth.hapaneld.util.GuardDbTerminalRetirement
import io.github.maxlyth.hapaneld.util.GuardDbTerminalRetirementLoad
import io.github.maxlyth.hapaneld.util.GuardDbTerminalRetirementState
import io.github.maxlyth.hapaneld.util.GuardDbTerminalRetirementStore
import io.github.maxlyth.hapaneld.util.InstallProgress
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.plugins.mutableOriginConnectionPoint
import io.ktor.server.plugins.origin
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.io.File
import java.security.SecureRandom
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GuardDbTerminalRetirementRoutesTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `exact approved terminal retirement sends once and identical completion is idempotent`() =
        testApplication {
            val fixture = fixture()
            fixture.transport.onRetire = {
                assertTrue(InstallProgress.running)
                assertTrue(InstallProgress.start("crossing package mutation") == null)
            }
            install(fixture)

            val challenged = postRetirement(fixture.body, DIRECT_PEER)
            assertEquals(HttpStatusCode.Accepted, challenged.status)
            assertTrue(challenged.bodyAsText().contains("approval-required"))
            assertTrue(fixture.broker.approve(fixture.broker.pending().single().id))

            val accepted = postRetirement(fixture.body, DIRECT_PEER)
            assertEquals(HttpStatusCode.OK, accepted.status)
            assertTrue(accepted.bodyAsText().contains("\"state\":\"empty\""))
            assertEquals(listOf(fixture.retireCommand), fixture.transport.longCommands)
            assertFalse(InstallProgress.running)
            assertEquals(
                "guard-db-retirement-settled",
                JSONObject(InstallProgress.json()).getJSONObject("presentation").getString("code"),
            )
            assertEquals(
                GuardDbTerminalRetirementState.COMPLETE,
                (fixture.store.load() as GuardDbTerminalRetirementLoad.Valid).retirement.state,
            )

            val repeated = postRetirement(fixture.body, DIRECT_PEER)
            assertEquals(HttpStatusCode.OK, repeated.status)
            assertEquals("durable completion never resubmits", 1, fixture.transport.longCommands.size)
            assertTrue(fixture.broker.pending().isEmpty())
        }

    @Test fun `completed retirement clears its staged candidate pair and a replay never clears a new one`() =
        testApplication {
            val fixture = fixture()
            val pair = fixture.stagePair()
            install(fixture)
            assertEquals(HttpStatusCode.Accepted, postRetirement(fixture.body, DIRECT_PEER).status)
            pair.forEach { assertTrue("an unapproved retirement touches nothing", it.exists()) }
            assertTrue(fixture.broker.approve(fixture.broker.pending().single().id))

            assertEquals(HttpStatusCode.OK, postRetirement(fixture.body, DIRECT_PEER).status)
            pair.forEach { assertFalse("${it.name} outlived its finished canary", it.exists()) }

            val next = fixture.stagePair()
            assertEquals(HttpStatusCode.OK, postRetirement(fixture.body, DIRECT_PEER).status)
            next.forEach { assertTrue("an unapproved replay cleared ${it.name}", it.exists()) }
        }

    @Test fun `staged pair survives a retirement still RETIRING and is cleared when EMPTY completes it`() =
        testApplication {
            val fixture = fixture().apply {
                transport.longResult = DaemonLongResult.Indeterminate
                transport.onRetire = { transport.statusReply = retiringStatus }
            }
            val pair = fixture.stagePair()
            install(fixture)
            assertEquals(HttpStatusCode.Accepted, postRetirement(fixture.body, DIRECT_PEER).status)
            assertTrue(fixture.broker.approve(fixture.broker.pending().single().id))

            assertEquals(HttpStatusCode.Accepted, postRetirement(fixture.body, DIRECT_PEER).status)
            pair.forEach { assertTrue("${it.name} cleared before the helper finished retiring", it.exists()) }

            fixture.transport.statusReply = fixture.emptyStatus
            assertEquals(HttpStatusCode.OK, postRetirement(fixture.body, DIRECT_PEER).status)
            pair.forEach { assertFalse("${it.name} outlived its finished canary", it.exists()) }
        }

    @Test fun `a session record keeps the staged pair without holding completion back`() = testApplication {
        val fixture = fixture().apply { sessionRecord = true }
        val pair = fixture.stagePair()
        install(fixture)
        assertEquals(HttpStatusCode.Accepted, postRetirement(fixture.body, DIRECT_PEER).status)
        assertTrue(fixture.broker.approve(fixture.broker.pending().single().id))

        assertEquals(HttpStatusCode.OK, postRetirement(fixture.body, DIRECT_PEER).status)

        assertEquals(
            GuardDbTerminalRetirementState.COMPLETE,
            (fixture.store.load() as GuardDbTerminalRetirementLoad.Valid).retirement.state,
        )
        pair.forEach { assertTrue("a referenced ${it.name} was removed", it.exists()) }
    }

    @Test fun `peer Hardened and exact body gates run before approval or native send`() = testApplication {
        val fixture = fixture()
        install(fixture)

        listOf("127.0.0.1", "::1", "198.51.100.4", "bad-peer").forEach { peer ->
            assertEquals(peer, HttpStatusCode.Forbidden, postRetirement(fixture.body, peer).status)
        }
        assertEquals(
            HttpStatusCode.BadRequest,
            postRetirement(fixture.body.dropLast(1) + ",\"extra\":1}", DIRECT_PEER).status,
        )
        assertEquals(
            HttpStatusCode.BadRequest,
            postRetirement(fixture.body.replace(fixture.session, "A".repeat(64)), DIRECT_PEER).status,
        )
        assertEquals(
            HttpStatusCode.PayloadTooLarge,
            postRetirement("{" + "x".repeat(4096) + "}", DIRECT_PEER).status,
        )
        fixture.hardened = false
        assertEquals(HttpStatusCode.PreconditionFailed, postRetirement(fixture.body, DIRECT_PEER).status)
        assertTrue(fixture.broker.pending().isEmpty())
        assertTrue(fixture.transport.longCommands.isEmpty())
    }

    @Test fun `approval binds raw body bytes and cannot be spent by equivalent JSON formatting`() =
        testApplication {
            val fixture = fixture()
            install(fixture)
            assertEquals(HttpStatusCode.Accepted, postRetirement(fixture.body, DIRECT_PEER).status)
            val exact = fixture.broker.pending().single()
            assertTrue(fixture.broker.approve(exact.id))

            val reformatted = fixture.body.replace(",\"generation\"", ", \"generation\"")
            val changed = postRetirement(reformatted, DIRECT_PEER)
            assertEquals(HttpStatusCode.Accepted, changed.status)
            val second = fixture.broker.pending().single()
            assertTrue(exact.id != second.id)
            assertTrue(fixture.broker.deny(second.id))
            assertTrue(fixture.transport.longCommands.isEmpty())

            assertEquals(HttpStatusCode.OK, postRetirement(fixture.body, DIRECT_PEER).status)
            assertEquals(1, fixture.transport.longCommands.size)
        }

    @Test fun `physical approval is bound to the same direct LAN peer and is one use`() =
        testApplication {
            val fixture = fixture()
            install(fixture)
            assertEquals(HttpStatusCode.Accepted, postRetirement(fixture.body, DIRECT_PEER).status)
            val first = fixture.broker.pending().single()
            assertTrue(fixture.broker.approve(first.id))

            val otherPeer = listOf(192, 168, 20, 31).joinToString(".")
            assertEquals(HttpStatusCode.Accepted, postRetirement(fixture.body, otherPeer).status)
            val second = fixture.broker.pending().single()
            assertTrue(first.id != second.id)
            assertTrue(fixture.broker.deny(second.id))
            assertTrue(fixture.transport.longCommands.isEmpty())

            assertEquals(HttpStatusCode.OK, postRetirement(fixture.body, DIRECT_PEER).status)
            assertEquals(1, fixture.transport.longCommands.size)
            assertEquals(HttpStatusCode.OK, postRetirement(fixture.body, DIRECT_PEER).status)
            assertEquals(1, fixture.transport.longCommands.size)
        }

    @Test fun `evidence status and epoch changes after approval invalidate without submission`() =
        testApplication {
            val evidenceChanged = fixture()
            install(evidenceChanged)
            assertEquals(HttpStatusCode.Accepted, postRetirement(evidenceChanged.body, DIRECT_PEER).status)
            assertTrue(evidenceChanged.broker.approve(evidenceChanged.broker.pending().single().id))
            evidenceChanged.afterApproved = {
                evidenceChanged.transport.evidence = evidenceChanged.transport.evidence
                    .toString(Charsets.US_ASCII)
                    .replace("SIGNER ${"3".repeat(64)}", "SIGNER ${"4".repeat(64)}")
                    .toByteArray(Charsets.US_ASCII)
            }
            val changedEvidence = postRetirement(evidenceChanged.body, DIRECT_PEER)
            assertEquals(HttpStatusCode.Conflict, changedEvidence.status)
            assertTrue(changedEvidence.bodyAsText().contains("retirement-authority-changed"))
            assertTrue(evidenceChanged.transport.longCommands.isEmpty())
        }

    @Test fun `status change after approval cannot cross transition gate`() =
        testApplication {
            val statusChanged = fixture()
            install(statusChanged)
            assertEquals(HttpStatusCode.Accepted, postRetirement(statusChanged.body, DIRECT_PEER).status)
            assertTrue(statusChanged.broker.approve(statusChanged.broker.pending().single().id))
            statusChanged.afterApproved = { statusChanged.transport.statusReply = statusChanged.activeStatus }
            assertEquals(HttpStatusCode.Conflict, postRetirement(statusChanged.body, DIRECT_PEER).status)
            assertTrue(statusChanged.transport.longCommands.isEmpty())
        }

    @Test fun `security epoch change after approval cannot cross transition gate`() = testApplication {
            val epochChanged = fixture()
            install(epochChanged)
            assertEquals(HttpStatusCode.Accepted, postRetirement(epochChanged.body, DIRECT_PEER).status)
            assertTrue(epochChanged.broker.approve(epochChanged.broker.pending().single().id))
            epochChanged.afterApproved = { RemoteDebugSecurityTransitionGate.mutate {} }
            val crossed = postRetirement(epochChanged.body, DIRECT_PEER)
            assertEquals(HttpStatusCode.Conflict, crossed.status)
            assertTrue(crossed.bodyAsText().contains("retirement-authority-changed"))
            assertTrue(epochChanged.transport.longCommands.isEmpty())
        }

    @Test fun `active ambiguous mismatched evidence and wrong hash refuse before approval`() =
        testApplication {
            val fixture = fixture()
            install(fixture)

            fixture.transport.statusReply = fixture.activeStatus
            assertEquals(HttpStatusCode.Conflict, postRetirement(fixture.body, DIRECT_PEER).status)
            fixture.transport.statusReply = fixture.ambiguousStatus
            assertEquals(HttpStatusCode.Conflict, postRetirement(fixture.body, DIRECT_PEER).status)
            fixture.transport.statusReply = fixture.finalStatus

            fixture.transport.capsReply =
                "${GuardDbMaintenanceProtocol.CAPS_REPLY} AUTONOMOUS SUPERVISED"
            val legacy = postRetirement(fixture.body, DIRECT_PEER)
            assertEquals(HttpStatusCode.ServiceUnavailable, legacy.status)
            assertTrue(legacy.bodyAsText().contains("terminal-retirement-helper-required"))
            fixture.transport.capsReply =
                "${GuardDbMaintenanceProtocol.CAPS_REPLY} AUTONOMOUS SUPERVISED TERMINAL_RETIRE"

            val wrongHash = fixture.body.replace(fixture.evidenceSha256, "f".repeat(64))
            val refusedHash = postRetirement(wrongHash, DIRECT_PEER)
            assertEquals(HttpStatusCode.Conflict, refusedHash.status)
            assertTrue(refusedHash.bodyAsText().contains("evidence-hash-mismatch"))

            fixture.transport.evidence = fixture.transport.evidence.toString(Charsets.US_ASCII)
                .replace("STATE 20 FINALIZED", "STATE 19 FINALIZED")
                .toByteArray(Charsets.US_ASCII)
            val mismatched = postRetirement(fixture.body, DIRECT_PEER)
            assertEquals(HttpStatusCode.Conflict, mismatched.status)
            assertTrue(mismatched.bodyAsText().contains("evidence-status-mismatch"))
            assertTrue(fixture.broker.pending().isEmpty())
            assertTrue(fixture.transport.longCommands.isEmpty())
        }

    @Test fun `indeterminate submission reconciles one exact EMPTY probe without replay`() =
        testApplication {
            val fixture = fixture().apply {
                transport.longResult = DaemonLongResult.Indeterminate
                transport.onRetire = { transport.statusReply = emptyStatus }
            }
            install(fixture)
            assertEquals(HttpStatusCode.Accepted, postRetirement(fixture.body, DIRECT_PEER).status)
            assertTrue(fixture.broker.approve(fixture.broker.pending().single().id))

            val settled = postRetirement(fixture.body, DIRECT_PEER)
            assertEquals(HttpStatusCode.OK, settled.status)
            assertEquals(1, fixture.transport.longCommands.size)
            assertEquals(
                "indeterminate submission performs exactly one reconciliation probe",
                fixture.transport.statusCallsAtRetire + 1,
                fixture.transport.statusCalls,
            )
            assertEquals(
                GuardDbTerminalRetirementState.COMPLETE,
                (fixture.store.load() as GuardDbTerminalRetirementLoad.Valid).retirement.state,
            )
        }

    @Test fun `definite not submitted becomes retryable and requires a new physical approval`() =
        testApplication {
            val fixture = fixture().apply {
                transport.longResult = DaemonLongResult.NotSubmitted
            }
            install(fixture)
            assertEquals(HttpStatusCode.Accepted, postRetirement(fixture.body, DIRECT_PEER).status)
            assertTrue(fixture.broker.approve(fixture.broker.pending().single().id))

            val notSubmitted = postRetirement(fixture.body, DIRECT_PEER)
            assertEquals(HttpStatusCode.ServiceUnavailable, notSubmitted.status)
            assertTrue(notSubmitted.bodyAsText().contains("reapproval-required"))
            assertEquals(
                GuardDbTerminalRetirementState.RETRYABLE,
                (fixture.store.load() as GuardDbTerminalRetirementLoad.Valid).retirement.state,
            )
            assertFalse(InstallProgress.running)

            val challengedAgain = postRetirement(fixture.body, DIRECT_PEER)
            assertEquals(HttpStatusCode.Accepted, challengedAgain.status)
            assertTrue(fixture.broker.approve(fixture.broker.pending().single().id))
            fixture.transport.longResult = DaemonLongResult.Reply("OK GUARDRETIRE 21 EMPTY")
            assertEquals(HttpStatusCode.OK, postRetirement(fixture.body, DIRECT_PEER).status)
            assertEquals(2, fixture.transport.longCommands.size)
        }

    @Test fun `definite native rejection becomes retryable and never reuses spent approval`() =
        testApplication {
            val fixture = fixture().apply {
                transport.longResult = DaemonLongResult.Reply("ERR STALE generation")
            }
            install(fixture)
            assertEquals(HttpStatusCode.Accepted, postRetirement(fixture.body, DIRECT_PEER).status)
            assertTrue(fixture.broker.approve(fixture.broker.pending().single().id))
            val rejected = postRetirement(fixture.body, DIRECT_PEER)
            assertEquals(HttpStatusCode.Conflict, rejected.status)
            assertTrue(rejected.bodyAsText().contains("reapproval-required"))
            assertEquals(
                GuardDbTerminalRetirementState.RETRYABLE,
                (fixture.store.load() as GuardDbTerminalRetirementLoad.Valid).retirement.state,
            )
            assertFalse(InstallProgress.running)
            assertEquals(HttpStatusCode.Accepted, postRetirement(fixture.body, DIRECT_PEER).status)
            assertEquals(1, fixture.transport.longCommands.size)
        }

    @Test fun `crashed durable FINALIZED intent stays fenced and requires approval before idempotent retry`() =
        testApplication {
            val fixture = fixture()
            assertTrue(fixture.store.writeIntent(fixture.intent))
            GuardDbProcessAdmission.update(GuardDbSentinelLoad.Absent)
            GuardDbProcessAdmission.updateTerminalRetirement(fixture.store.load())
            install(fixture)

            val challenged = postRetirement(fixture.body, DIRECT_PEER)
            assertEquals(HttpStatusCode.Accepted, challenged.status)
            assertEquals(
                GuardDbTerminalRetirementState.INTENT,
                (fixture.store.load() as GuardDbTerminalRetirementLoad.Valid).retirement.state,
            )
            assertTrue(InstallProgress.running)
            assertFalse(GuardDbProcessAdmission.ordinaryMutationsAllowed())
            assertTrue(fixture.transport.longCommands.isEmpty())
            assertTrue(fixture.broker.approve(fixture.broker.pending().single().id))

            assertEquals(HttpStatusCode.OK, postRetirement(fixture.body, DIRECT_PEER).status)
            assertEquals(1, fixture.transport.longCommands.size)
            assertEquals(
                GuardDbTerminalRetirementState.COMPLETE,
                (fixture.store.load() as GuardDbTerminalRetirementLoad.Valid).retirement.state,
            )
            assertTrue(GuardDbProcessAdmission.ordinaryMutationsAllowed())
            assertFalse(InstallProgress.running)
        }

    @Test fun `queued first command winning after reapproval challenge is only status reconciled`() =
        testApplication {
            val fixture = fixture()
            assertTrue(fixture.store.writeIntent(fixture.intent))
            install(fixture)

            assertEquals(HttpStatusCode.Accepted, postRetirement(fixture.body, DIRECT_PEER).status)
            assertTrue(fixture.broker.approve(fixture.broker.pending().single().id))
            fixture.transport.statusReply = fixture.retiringStatus

            val pending = postRetirement(fixture.body, DIRECT_PEER)
            assertEquals(HttpStatusCode.Accepted, pending.status)
            assertTrue(pending.bodyAsText().contains("\"state\":\"retiring\""))
            assertTrue(fixture.transport.longCommands.isEmpty())
            assertTrue(InstallProgress.running)

            fixture.transport.statusReply = fixture.emptyStatus
            assertEquals(HttpStatusCode.OK, postRetirement(fixture.body, DIRECT_PEER).status)
            assertTrue(fixture.transport.longCommands.isEmpty())
            assertFalse(InstallProgress.running)
        }

    @Test fun `serialized retry rejection reconciles queued first command EMPTY without another replay`() =
        testApplication {
            val fixture = fixture().apply {
                transport.longResult = DaemonLongResult.Reply("ERR HOLD retirement")
                transport.onRetire = { transport.statusReply = emptyStatus }
            }
            assertTrue(fixture.store.writeIntent(fixture.intent))
            install(fixture)
            assertEquals(HttpStatusCode.Accepted, postRetirement(fixture.body, DIRECT_PEER).status)
            assertTrue(fixture.broker.approve(fixture.broker.pending().single().id))

            val settled = postRetirement(fixture.body, DIRECT_PEER)

            assertEquals(HttpStatusCode.OK, settled.status)
            assertEquals(1, fixture.transport.longCommands.size)
            assertEquals(
                GuardDbTerminalRetirementState.COMPLETE,
                (fixture.store.load() as GuardDbTerminalRetirementLoad.Valid).retirement.state,
            )
            assertFalse(InstallProgress.running)
        }

    @Test fun `RETIRING intent only polls and later EMPTY completes without replay`() = testApplication {
        val fixture = fixture().apply {
            transport.longResult = DaemonLongResult.Indeterminate
            transport.onRetire = { transport.statusReply = retiringStatus }
        }
        install(fixture)
        assertEquals(HttpStatusCode.Accepted, postRetirement(fixture.body, DIRECT_PEER).status)
        assertTrue(fixture.broker.approve(fixture.broker.pending().single().id))

        val pending = postRetirement(fixture.body, DIRECT_PEER)
        assertEquals(HttpStatusCode.Accepted, pending.status)
        assertTrue(pending.bodyAsText().contains("\"settlement\":\"poll-status\""))
        assertEquals(1, fixture.transport.longCommands.size)
        assertTrue(InstallProgress.running)
        assertTrue(InstallProgress.start("crossing package mutation") == null)

        fixture.transport.statusReply = fixture.emptyStatus
        val complete = postRetirement(fixture.body, DIRECT_PEER)
        assertEquals(HttpStatusCode.OK, complete.status)
        assertEquals(1, fixture.transport.longCommands.size)
        assertFalse(InstallProgress.running)
    }

    @Test fun `fresh process settles durable intent from EMPTY without replay and removes mutation fence`() =
        testApplication {
            val fixture = fixture()
            assertTrue(fixture.store.writeIntent(fixture.intent))
            GuardDbProcessAdmission.update(GuardDbSentinelLoad.Absent)
            GuardDbProcessAdmission.updateTerminalRetirement(fixture.store.load())
            assertFalse(GuardDbProcessAdmission.ordinaryMutationsAllowed())
            fixture.transport.statusReply = fixture.emptyStatus
            install(fixture)

            val settled = postRetirement(fixture.body, DIRECT_PEER)

            assertEquals(HttpStatusCode.OK, settled.status)
            assertTrue(fixture.transport.longCommands.isEmpty())
            assertTrue(fixture.broker.pending().isEmpty())
            assertEquals(
                GuardDbTerminalRetirementState.COMPLETE,
                (fixture.store.load() as GuardDbTerminalRetirementLoad.Valid).retirement.state,
            )
            assertTrue(GuardDbProcessAdmission.ordinaryMutationsAllowed())
            assertFalse(InstallProgress.running)
        }

    @Test fun `visible completion after failed parent fsync is redurabilized before idempotent success`() =
        testApplication {
            var syncCalls = 0
            val fixture = fixture(syncDirectory = {
                syncCalls += 1
                syncCalls != 2
            })
            install(fixture)
            assertEquals(HttpStatusCode.Accepted, postRetirement(fixture.body, DIRECT_PEER).status)
            assertTrue(fixture.broker.approve(fixture.broker.pending().single().id))

            val uncertain = postRetirement(fixture.body, DIRECT_PEER)
            assertEquals(HttpStatusCode.ServiceUnavailable, uncertain.status)
            assertTrue(uncertain.bodyAsText().contains("retirement-completion-not-durable"))
            assertEquals(2, syncCalls)
            assertEquals(
                GuardDbTerminalRetirementState.COMPLETE,
                (fixture.store.load() as GuardDbTerminalRetirementLoad.Valid).retirement.state,
            )
            assertTrue(InstallProgress.running)
            assertEquals(1, fixture.transport.longCommands.size)

            val settled = postRetirement(fixture.body, DIRECT_PEER)
            assertEquals(HttpStatusCode.OK, settled.status)
            assertEquals(3, syncCalls)
            assertEquals(1, fixture.transport.longCommands.size)
            assertFalse(InstallProgress.running)
        }

    private fun ApplicationTestBuilder.install(fixture: Fixture) {
        application {
            intercept(ApplicationCallPipeline.Setup) {
                context.mutableOriginConnectionPoint.remoteAddress =
                    context.request.headers[TEST_PEER_HEADER] ?: "127.0.0.1"
            }
            routing {
                route("/api/v1/guard-db") {
                    guardDbTerminalRetirementRoute(fixture.dependencies(), fixture.staging)
                }
            }
        }
    }

    private suspend fun ApplicationTestBuilder.postRetirement(body: String, peer: String) =
        client.post("/api/v1/guard-db/evidence/retire") {
            header(TEST_PEER_HEADER, peer)
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private fun fixture(syncDirectory: (File) -> Boolean = { true }): Fixture {
        if (RemoteDebugSecurityTransitionGate.authorityEpoch() <= 0L) {
            RemoteDebugSecurityTransitionGate.mutate {}
        }
        return Fixture(
            store = GuardDbTerminalRetirementStore(
                temporary.newFolder("retirement-${System.nanoTime()}"),
                syncDirectory = syncDirectory,
                validateFile = { it.isFile },
            ),
            securityEpoch = RemoteDebugSecurityTransitionGate.authorityEpoch(),
            stagingDirectory = temporary.newFolder("staging-${System.nanoTime()}"),
        )
    }

    private class Fixture(
        val store: GuardDbTerminalRetirementStore,
        val securityEpoch: Long,
        private val stagingDirectory: File,
    ) {
        var sessionRecord = false
        val staging = GuardDbAppStaging(
            stagingDirectory,
            inspect = { null },
            syncDirectory = { true },
            validateFile = { it.isFile },
            sessionRecordPresent = { sessionRecord },
        )

        fun stagePair(): List<File> = listOf("a", "b").map { role ->
            File(stagingDirectory, "guard-db-candidate-$role.apk").apply { writeText("candidate $role") }
        }

        val session = "1".repeat(64)
        private val boot = "2".repeat(64)
        private val aSha = "a".repeat(64)
        val finalStatus =
            "OK GUARDSTATUS 20 FINALIZED $session $boot A $aSha 568 14 37 NONE CANARY_PASSED 1800000 1320000"
        val retiringStatus =
            "OK GUARDSTATUS 21 RETIRING $session $boot A $aSha 568 14 37 NONE CANARY_PASSED 1800000 1320000"
        val activeStatus =
            "OK GUARDSTATUS 19 WAIT_A_HEALTH $session $boot A $aSha 568 14 37 NONE NONE 1800000 1320000"
        val ambiguousStatus =
            "OK GUARDSTATUS 20 AMBIGUOUS $session $boot NONE NONE 0 0 37 PM_UNKNOWN AMBIGUOUS 1800000 1320000"
        val emptyStatus = "OK GUARDSTATUS 0 EMPTY NONE NONE NONE NONE 0 0 0 NONE NONE 0 0"
        private val evidence = (
            "OK GUARDEVIDENCE 1\n" +
                "SESSION $session\nBOOT $boot\nPACKAGE io.github.maxlyth.hapaneld\n" +
                "SIGNER ${"3".repeat(64)}\n" +
                "STATE 20 FINALIZED A $aSha 568 14 NONE CANARY_PASSED 1800000 1320000\n" +
                "BASELINE 4096 ${"c".repeat(64)} 14 37 ${"d".repeat(64)} ${"e".repeat(64)}\n" +
                "SETTINGS 2 3 ${"f".repeat(64)}\n" +
                "A 1 1 4 $aSha 568 11 14 14\n" +
                "B 1 1 4 ${"b".repeat(64)} 569 11 15 15\n" +
                "PREMIGRATE 0 NONE\nB_PRIMARY 0 NONE\nEND\n"
            ).toByteArray(Charsets.US_ASCII)
        val transport = FakeTransport(finalStatus, evidence)
        val evidenceSha256 = sha256Hex(evidence)
        val intent = GuardDbTerminalRetirement(
            state = GuardDbTerminalRetirementState.INTENT,
            session = session,
            finalGeneration = 20L,
            bootNonce = boot,
            aSha256 = aSha,
            aVersionCode = 568L,
            aSchema = 14,
            outcome = GuardDbMaintenanceProtocol.Outcome.CANARY_PASSED,
            evidenceSha256 = evidenceSha256,
        )
        val body =
            "{\"session\":\"$session\",\"generation\":20,\"evidence_sha256\":\"$evidenceSha256\"}"
        val retireCommand = "GUARDRETIRE TERMINAL $session 20 $evidenceSha256"
        val broker = ApprovalBroker(monotonicMs = { 1_000L }, random = SecureRandom())
        var hardened = true
        var afterApproved: () -> Unit = {}

        fun dependencies() = GuardDbTerminalRetirementRouteDependencies(
            client = GuardDbMaintenanceClient(transport),
            store = store,
            hardened = { hardened },
            securityEpoch = { securityEpoch },
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
        )
    }

    private class FakeTransport(
        var statusReply: String,
        var evidence: ByteArray,
    ) : GuardDbMaintenanceTransport {
        var statusCalls = 0
        var statusCallsAtRetire = -1
        var capsReply =
            "${GuardDbMaintenanceProtocol.CAPS_REPLY} AUTONOMOUS SUPERVISED TERMINAL_RETIRE"
        var longResult: DaemonLongResult = DaemonLongResult.Reply("OK GUARDRETIRE 21 EMPTY")
        val longCommands = mutableListOf<String>()
        var onRetire: () -> Unit = {}

        override fun send(command: String): String? = when (command) {
            "GUARDCAPS" -> capsReply
            "GUARDSTATUS" -> statusReply.also { statusCalls++ }
            else -> null
        }

        override fun sendLong(command: String, timeoutMs: Long): DaemonLongResult {
            longCommands += command
            statusCallsAtRetire = statusCalls
            onRetire()
            return longResult
        }

        override fun sendFile(command: String, file: File, timeoutMs: Long) =
            DaemonStreamResult.NotSubmitted

        override fun sendBytesBounded(command: String, maxBytes: Long): ByteArray? =
            evidence.takeIf { command.startsWith("GUARDEVIDENCE ") && it.size <= maxBytes }
    }

    private companion object {
        const val TEST_PEER_HEADER = "X-Test-Peer"
        val DIRECT_PEER = listOf(192, 168, 20, 30).joinToString(".")
    }
}
