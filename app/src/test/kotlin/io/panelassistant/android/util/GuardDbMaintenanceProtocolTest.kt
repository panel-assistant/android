package io.panelassistant.android.util

import io.panelassistant.android.platform.DaemonLongResult
import io.panelassistant.android.platform.DaemonStreamResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class GuardDbMaintenanceProtocolTest {
    private val session = "1".repeat(64)
    private val boot = "2".repeat(64)
    private val signer = "3".repeat(64)
    private val aSha = "a".repeat(64)
    private val bSha = "b".repeat(64)

    @Test fun `live helper identity parser and client require the exact v1 tuple`() {
        val sha = "c".repeat(64)
        val build = "d".repeat(64)
        val reply = "OK GUARDSELF 1 4096 $sha $build"
        val expected = GuardDbMaintenanceProtocol.SelfIdentity(4096L, sha, build)
        assertEquals(expected, GuardDbMaintenanceProtocol.parseSelfIdentity(reply))

        listOf<String?>(
            null,
            "",
            "OK GUARDSELF 1 0 $sha $build",
            "OK GUARDSELF 1 04096 $sha $build",
            "OK GUARDSELF 1 ${GuardDbMaintenanceProtocol.MAX_SELF_BYTES + 1L} $sha $build",
            "OK GUARDSELF 2 4096 $sha $build",
            "OK GUARDSTATUS 1 4096 $sha $build",
            "OK GUARDSELF 1 4096 ${"C".repeat(64)} $build",
            "OK GUARDSELF 1 4096 $sha development",
            "OK GUARDSELF 1 4096 $sha $build EXTRA",
            "OK  GUARDSELF 1 4096 $sha $build",
            "OK GUARDSELF 1 4096 $sha $build\n",
        ).forEach { malformed ->
            assertNull(malformed, GuardDbMaintenanceProtocol.parseSelfIdentity(malformed))
        }

        val transport = FakeTransport().apply { short = reply }
        assertEquals(expected, GuardDbMaintenanceClient(transport).selfIdentity())
        assertEquals(listOf("GUARDSELF"), transport.shortCommands)
        transport.short = "ERR HOLD self"
        assertNull(GuardDbMaintenanceClient(transport).selfIdentity())
    }

    @Test fun `capabilities distinguish supervision autonomy and terminal retirement`() {
        assertEquals(
            GuardDbMaintenanceProtocol.Capabilities(supervised = false, autonomous = false),
            GuardDbMaintenanceProtocol.parseCapabilities(GuardDbMaintenanceProtocol.CAPS_REPLY),
        )
        assertEquals(
            GuardDbMaintenanceProtocol.Capabilities(supervised = true, autonomous = false),
            GuardDbMaintenanceProtocol.parseCapabilities("${GuardDbMaintenanceProtocol.CAPS_REPLY} SUPERVISED"),
        )
        assertEquals(
            GuardDbMaintenanceProtocol.Capabilities(supervised = true, autonomous = true),
            GuardDbMaintenanceProtocol.parseCapabilities(
                "${GuardDbMaintenanceProtocol.CAPS_REPLY} AUTONOMOUS SUPERVISED",
            ),
        )
        assertEquals(
            GuardDbMaintenanceProtocol.Capabilities(
                supervised = true,
                autonomous = true,
                terminalRetire = true,
            ),
            GuardDbMaintenanceProtocol.parseCapabilities(
                "${GuardDbMaintenanceProtocol.CAPS_REPLY} AUTONOMOUS SUPERVISED TERMINAL_RETIRE",
            ),
        )
        assertNull(GuardDbMaintenanceProtocol.parseCapabilities("${GuardDbMaintenanceProtocol.CAPS_REPLY} EXTRA"))
        val transport = FakeTransport()
        val client = GuardDbMaintenanceClient(transport)
        listOf(
            GuardDbMaintenanceProtocol.CAPS_REPLY to false,
            "${GuardDbMaintenanceProtocol.CAPS_REPLY} SUPERVISED" to false,
            "${GuardDbMaintenanceProtocol.CAPS_REPLY} AUTONOMOUS" to false,
            "${GuardDbMaintenanceProtocol.CAPS_REPLY} AUTONOMOUS SUPERVISED" to false,
            "${GuardDbMaintenanceProtocol.CAPS_REPLY} AUTONOMOUS SUPERVISED TERMINAL_RETIRE" to true,
        ).forEach { (reply, expected) ->
            transport.short = reply
            assertEquals(reply, expected, client.supported())
        }
    }

    @Test fun `commands bind exact immutable plan generation and candidate fields`() {
        withCandidates { a, b, authority ->
            val plan = GuardDbMaintenanceProtocol.Plan(
                session,
                boot,
                signer,
                GuardDbMaintenanceProtocol.Baseline(
                    4096L, "c".repeat(64), 14, 37L, "d".repeat(64), "e".repeat(64),
                ),
                listOf(a, b),
                1_800_000L,
                authority,
            )
            assertEquals(
                "GUARDPREPARE $session $boot $signer 4096 ${"c".repeat(64)} 14 37 ${"d".repeat(64)} " +
                    "${"e".repeat(64)} 1800000 2 ${authority.bytes} ${authority.sha256}",
                GuardDbMaintenanceProtocol.prepare(plan),
            )
            assertEquals(
                "GUARDDEFINE $session 7 A 4 $aSha 568 11 14 14",
                GuardDbMaintenanceProtocol.define(session, 7L, a),
            )
            assertEquals(
                "GUARDSTREAM $session 8 B 4 $bSha",
                GuardDbMaintenanceProtocol.stream(session, 8L, b),
            )
            assertEquals(
                "GUARDSTREAM $session 9 SETTINGS ${authority.bytes} ${authority.sha256}",
                GuardDbMaintenanceProtocol.streamSettings(session, 9L, authority),
            )
            assertEquals(
                "GUARDACTION $session 9 WITHHOLD_PREMIGRATE",
                GuardDbMaintenanceProtocol.action(
                    session,
                    9L,
                    GuardDbMaintenanceProtocol.Action.WITHHOLD_PREMIGRATE,
                ),
            )
        }
    }

    @Test fun `status parser accepts exact empty and settled records`() {
        val empty = GuardDbMaintenanceProtocol.parseStatus(
            "OK GUARDSTATUS 0 EMPTY NONE NONE NONE NONE 0 0 0 NONE NONE 0 0",
        )
        assertEquals(GuardDbMaintenanceProtocol.Phase.EMPTY, empty?.phase)
        assertFalse(requireNotNull(empty).ownsMaintenance)

        val waiting = GuardDbMaintenanceProtocol.parseStatus(
            "OK GUARDSTATUS 12 WAIT_B_HEALTH $session $boot B $bSha 569 15 37 NONE NONE 1800000 1320000",
        )
        assertEquals(12L, waiting?.generation)
        assertEquals(GuardDbMaintenanceProtocol.Role.B, waiting?.role)
        assertTrue(requireNotNull(waiting).ownsMaintenance)
    }

    @Test fun `malformed empty helper status cannot authorize bootstrap export`() {
        val exact = "OK GUARDSTATUS 0 EMPTY NONE NONE NONE NONE 0 0 0 NONE NONE 0 0"
        listOf(
            exact.replace("NONE 0 0 0", "NONE -1 0 0"),
            exact.replace("NONE 0 0 0", "NONE garbage 0 0"),
            exact.replace("NONE 0 0 0", "NONE 0 -1 0"),
            exact.replace("NONE 0 0 0", "NONE 0 garbage 0"),
            exact.replace("NONE 0 0 0", "NONE 00 0 0"),
            exact.replace("NONE 0 0 0", "NONE 0 00 0"),
            exact.replace("NONE 0 0 0", "NONE 0 2147483648 0"),
        ).forEach { assertNull(it, GuardDbMaintenanceProtocol.parseStatus(it)) }
    }

    @Test fun `status parser preserves durable staging capture hold errors`() {
        listOf("CAPTURE_INTENT", "FAILED_NO_MUTATION").forEach { error ->
            val status = GuardDbMaintenanceProtocol.parseStatus(
                "OK GUARDSTATUS 6 STAGING $session $boot NONE NONE 0 0 37 $error NONE 1800000 1320000",
            )
            assertEquals(error, status?.error)
            assertEquals(GuardDbMaintenanceProtocol.Phase.STAGING, status?.phase)
        }
    }

    @Test fun `status parser rejects malformed identity fields and noncanonical numbers`() {
        val valid =
            "OK GUARDSTATUS 12 WAIT_B_HEALTH $session $boot B $bSha 569 15 37 NONE NONE 1800000 1320000"
        listOf(
            valid.replace(session, "1".repeat(63)),
            valid.replace(session, "G".repeat(64)),
            valid.replace(boot, "2".repeat(65)),
            valid.replace(boot, "A".repeat(64)),
            valid.replace(bSha, "z".repeat(64)),
            valid.replace(bSha, "B".repeat(64)),
            valid.replace("GUARDSTATUS 12", "GUARDSTATUS 012"),
            valid.replace(" B $bSha 569", " B $bSha 0"),
            valid.replace(" 1800000 1320000", " 1800000 1319999"),
            valid.replace(" 1800000 1320000", " 1800000 1800001"),
        ).forEach { assertNull(it, GuardDbMaintenanceProtocol.parseStatus(it)) }
    }

    @Test fun `status parser enforces phase-specific identity and installed role fields`() {
        listOf(
            "OK GUARDSTATUS 1 EMPTY $session $boot NONE NONE 0 0 0 NONE NONE 0 0",
            "OK GUARDSTATUS 1 PREPARED NONE NONE NONE NONE 0 0 37 NONE NONE 1800000 1320000",
            "OK GUARDSTATUS 1 PREPARED $session $boot B $bSha 569 15 37 NONE NONE 1800000 1320000",
            "OK GUARDSTATUS 1 WAIT_B_HEALTH $session $boot A $aSha 568 14 37 NONE NONE 1800000 1320000",
            "OK GUARDSTATUS 1 A_HEALTHY $session $boot NONE NONE 0 0 37 NONE NONE 1800000 1320000",
            "OK GUARDSTATUS 1 AMBIGUOUS $session $boot NONE NONE 0 0 37 PM_UNKNOWN NONE 1800000 1320000",
        ).forEach { assertNull(it, GuardDbMaintenanceProtocol.parseStatus(it)) }
        assertEquals(
            GuardDbMaintenanceProtocol.Phase.PREPARED,
            GuardDbMaintenanceProtocol.parseStatus(
                "OK GUARDSTATUS 1 PREPARED $session $boot NONE NONE 0 0 37 NONE NONE 1800000 1320000",
            )?.phase,
        )
        assertEquals(
            GuardDbMaintenanceProtocol.Phase.AMBIGUOUS,
            GuardDbMaintenanceProtocol.parseStatus(
                "OK GUARDSTATUS 1 AMBIGUOUS $session $boot NONE NONE 0 0 37 PM_UNKNOWN AMBIGUOUS 1800000 1320000",
            )?.phase,
        )
    }

    @Test fun `finalized status requires an explicit terminal outcome`() {
        val finalized =
            "OK GUARDSTATUS 20 FINALIZED $session $boot A $aSha 568 14 37 NONE CANARY_PASSED 1800000 1320000"
        assertEquals(
            GuardDbMaintenanceProtocol.Outcome.CANARY_PASSED,
            GuardDbMaintenanceProtocol.parseStatus(finalized)?.outcome,
        )
        assertTrue(requireNotNull(GuardDbMaintenanceProtocol.parseStatus(finalized)).ownsMaintenance)
        val retiring = finalized.replace("GUARDSTATUS 20 FINALIZED", "GUARDSTATUS 21 RETIRING")
        assertEquals(GuardDbMaintenanceProtocol.Phase.RETIRING,
            GuardDbMaintenanceProtocol.parseStatus(retiring)?.phase)
        assertNull(GuardDbMaintenanceProtocol.parseStatus(finalized.replace("CANARY_PASSED", "NONE")))
        assertNull(GuardDbMaintenanceProtocol.parseStatus(
            finalized.replace("CANARY_PASSED", "CANCELLED_NO_MUTATION"),
        ))
    }

    @Test fun `mutation replies are verb phase and generation exact`() {
        assertEquals(
            GuardDbMaintenanceProtocol.Result.Accepted(4L, GuardDbMaintenanceProtocol.Phase.STAGING),
            GuardDbMaintenanceProtocol.parseMutationReply("GUARDDEFINE", "OK GUARDDEFINE 4 STAGING"),
        )
        assertEquals(
            GuardDbMaintenanceProtocol.Result.Rejected("STALE", "generation"),
            GuardDbMaintenanceProtocol.parseMutationReply(
                "GUARDDEFINE",
                "ERR STALE generation",
            ),
        )
        assertEquals(
            GuardDbMaintenanceProtocol.Result.Malformed,
            GuardDbMaintenanceProtocol.parseMutationReply("GUARDDEFINE", "ERR STALE GENERATION"),
        )
        assertEquals(
            GuardDbMaintenanceProtocol.Result.Malformed,
            GuardDbMaintenanceProtocol.parseMutationReply("GUARDDEFINE", "OK GUARDACTION 4 STAGING"),
        )
    }

    @Test fun `APP retire request and receipt are exact and malformed replies remain indeterminate`() {
        val nonce = "1".repeat(64)
        val sha = "2".repeat(64)
        val build = "3".repeat(64)
        assertEquals(
            "GUARDRETIRE APP $nonce $sha $build",
            GuardDbMaintenanceProtocol.retireApp(nonce, sha, build),
        )
        assertEquals(
            GuardDbMaintenanceProtocol.AppRetireResult.Requested,
            GuardDbMaintenanceProtocol.parseAppRetireReply("OK GUARDRETIRE 1 REQUESTED"),
        )
        listOf("ARGS" to "retire", "ARMED" to "replacement", "HOLD" to "replacement").forEach {
                (code, token) ->
            assertEquals(
                GuardDbMaintenanceProtocol.AppRetireResult.Rejected(code, token),
                GuardDbMaintenanceProtocol.parseAppRetireReply("ERR $code $token"),
            )
        }
        listOf(
            null,
            "ERR INDETERMINATE replacement",
            " OK GUARDRETIRE 1 REQUESTED",
            "OK GUARDRETIRE 1 REQUESTED ",
            "OK GUARDRETIRE 01 REQUESTED",
            "OK GUARDRETIRE 1 requested",
            "ERR ARMED Replacement",
            "ERR",
        ).forEach { reply ->
            assertEquals(
                reply,
                GuardDbMaintenanceProtocol.AppRetireResult.Indeterminate,
                GuardDbMaintenanceProtocol.parseAppRetireReply(reply),
            )
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `APP retire rejects noncanonical nonce`() {
        GuardDbMaintenanceProtocol.retireApp("A".repeat(64), "2".repeat(64), "3".repeat(64))
    }

    @Test fun `TERMINAL retire binds collected evidence and only exact reply settles`() {
        val evidence = "4".repeat(64)
        assertEquals(
            "GUARDRETIRE TERMINAL $session 20 $evidence",
            GuardDbMaintenanceProtocol.retireTerminal(session, 20L, evidence),
        )
        assertEquals(
            GuardDbMaintenanceProtocol.TerminalRetireResult.Accepted(21L),
            GuardDbMaintenanceProtocol.parseTerminalRetireReply(20L, "OK GUARDRETIRE 21 EMPTY"),
        )
        assertEquals(
            GuardDbMaintenanceProtocol.TerminalRetireResult.Rejected("STALE", "generation"),
            GuardDbMaintenanceProtocol.parseTerminalRetireReply(20L, "ERR STALE generation"),
        )
        listOf(
            null,
            "ERR INDETERMINATE retirement",
            "OK GUARDRETIRE 20 EMPTY",
            "OK GUARDRETIRE 21 RETIRING",
            "OK GUARDRETIRE 021 EMPTY",
            " OK GUARDRETIRE 21 EMPTY",
        ).forEach { reply ->
            assertEquals(
                reply,
                GuardDbMaintenanceProtocol.TerminalRetireResult.Indeterminate,
                GuardDbMaintenanceProtocol.parseTerminalRetireReply(20L, reply),
            )
        }
    }

    @Test fun `TERMINAL retire client sends exactly once and preserves transport epistemics`() {
        val evidence = "4".repeat(64)
        val transport = FakeTransport()
        val client = GuardDbMaintenanceClient(transport)
        listOf(
            DaemonLongResult.Reply("OK GUARDRETIRE 21 EMPTY") to
                GuardDbMaintenanceProtocol.TerminalRetireResult.Accepted(21L),
            DaemonLongResult.Reply("ERR HOLD retirement") to
                GuardDbMaintenanceProtocol.TerminalRetireResult.Rejected("HOLD", "retirement"),
            DaemonLongResult.NotSubmitted to GuardDbMaintenanceProtocol.TerminalRetireResult.NotSubmitted,
            DaemonLongResult.Indeterminate to GuardDbMaintenanceProtocol.TerminalRetireResult.Indeterminate,
        ).forEach { (wire, expected) ->
            transport.long = wire
            transport.longCommands.clear()
            assertEquals(expected, client.retireTerminal(session, 20L, evidence))
            assertEquals(listOf("GUARDRETIRE TERMINAL $session 20 $evidence"), transport.longCommands)
        }
    }

    @Test fun `APP retire client submits exact request once and preserves transport epistemics`() {
        val nonce = "1".repeat(64)
        val sha = "2".repeat(64)
        val build = "3".repeat(64)
        val transport = FakeTransport()
        val client = GuardDbMaintenanceClient(transport)
        listOf(
            DaemonLongResult.Reply("OK GUARDRETIRE 1 REQUESTED") to
                GuardDbMaintenanceProtocol.AppRetireResult.Requested,
            DaemonLongResult.Reply("ERR HOLD replacement") to
                GuardDbMaintenanceProtocol.AppRetireResult.Rejected("HOLD", "replacement"),
            DaemonLongResult.NotSubmitted to GuardDbMaintenanceProtocol.AppRetireResult.NotSubmitted,
            DaemonLongResult.Indeterminate to GuardDbMaintenanceProtocol.AppRetireResult.Indeterminate,
        ).forEach { (wire, expected) ->
            transport.long = wire
            transport.longCommands.clear()
            assertEquals(expected, client.retireApp(nonce, sha, build))
            assertEquals(listOf("GUARDRETIRE APP $nonce $sha $build"), transport.longCommands)
        }
    }

    @Test fun `only exact durable publication failures are indeterminate`() {
        listOf(
            "draft", "artifact", "journal", "capture_intent", "capture",
            "premigrate", "restore", "terminal", "retirement",
        )
            .forEach { detail ->
                assertEquals(
                    detail,
                    GuardDbMaintenanceProtocol.Result.Indeterminate,
                    GuardDbMaintenanceProtocol.parseMutationReply("GUARDACTION", "ERR INDETERMINATE $detail"),
                )
            }
        assertEquals(
            GuardDbMaintenanceProtocol.Result.Rejected("INDETERMINATE", "unknown"),
            GuardDbMaintenanceProtocol.parseMutationReply("GUARDACTION", "ERR INDETERMINATE unknown"),
        )
        listOf("baseline", "manifest").forEach { detail ->
            assertEquals(
                GuardDbMaintenanceProtocol.Result.Rejected("INDETERMINATE", detail),
                GuardDbMaintenanceProtocol.parseMutationReply("GUARDACTION", "ERR INDETERMINATE $detail"),
            )
        }
        assertEquals(
            GuardDbMaintenanceProtocol.Result.Malformed,
            GuardDbMaintenanceProtocol.parseMutationReply("GUARDACTION", "ERR INDETERMINATE DRAFT"),
        )
        assertEquals(
            GuardDbMaintenanceProtocol.Result.Malformed,
            GuardDbMaintenanceProtocol.parseMutationReply("GUARDACTION", "ERR INDETERMINATE draft trailing"),
        )
    }

    @Test fun `health and refusal bind nonce installed identity and exact refusal`() {
        val status = GuardDbMaintenanceProtocol.Status(
            18L,
            GuardDbMaintenanceProtocol.Phase.WAIT_A_REFUSAL,
            session,
            boot,
            GuardDbMaintenanceProtocol.Role.A,
            aSha,
            568L,
            14,
            37L,
            null,
        )
        assertEquals(
            "GUARDHEALTH $session 18 $boot B $bSha 569 15 OK 37 ${"d".repeat(64)} ${"e".repeat(64)} PRESENT NA",
            GuardDbMaintenanceProtocol.health(
                status,
                GuardDbMaintenanceProtocol.Role.B,
                bSha,
                569L,
                15,
                true,
                37L,
                "d".repeat(64),
                "e".repeat(64),
                GuardDbMaintenanceProtocol.Probe.PRESENT,
                GuardDbMaintenanceProtocol.RecoveryProof.NA,
            ),
        )
        assertEquals(
            "GUARDREFUSAL $session 18 $boot A $aSha 568 PRIMARY_ABOVE_MAXIMUM_WITHOUT_PREMIGRATE",
            GuardDbMaintenanceProtocol.refusal(status, aSha, 568L),
        )
    }

    @Test fun `evidence accepts only bounded framed ASCII records`() {
        val valid =
            "OK GUARDEVIDENCE 1\n" +
                "SESSION $session\nBOOT $boot\nPACKAGE io.github.maxlyth.hapaneld\nSIGNER $signer\n" +
                "STATE 19 WAIT_B_HEALTH B $bSha 569 15 NONE NONE 1800000 1320000\n" +
                "BASELINE 4096 ${"c".repeat(64)} 14 37 ${"d".repeat(64)} ${"e".repeat(64)}\n" +
                "SETTINGS 2 3 ${"f".repeat(64)}\n" +
                "A 1 1 4 $aSha 568 11 14 14\n" +
                "B 1 1 4 $bSha 569 11 15 15\n" +
                "PREMIGRATE 0 NONE\nB_PRIMARY 0 NONE\nEND\n"
        assertTrue(GuardDbMaintenanceProtocol.validEvidence(
            valid.toByteArray(),
        ))
        assertEquals(
            GuardDbMaintenanceProtocol.Phase.WAIT_B_HEALTH,
            GuardDbMaintenanceProtocol.evidenceStatus(valid.toByteArray())?.phase,
        )
        assertFalse(GuardDbMaintenanceProtocol.validEvidence("SESSION $session\nEND\n".toByteArray()))
        assertFalse(GuardDbMaintenanceProtocol.validEvidence(
            valid.replace("SESSION $session", "PATH /data/user/0/private").toByteArray(),
        ))
        assertFalse(GuardDbMaintenanceProtocol.validEvidence(valid.replace("A 1 1", "A 0 1").toByteArray()))
        assertFalse(GuardDbMaintenanceProtocol.validEvidence(valid.replace("B 1 1 4", "B 0 0 4").toByteArray()))
        assertFalse(GuardDbMaintenanceProtocol.validEvidence(valid.replace("SETTINGS 2 3", "SETTINGS 2 03").toByteArray()))
        assertFalse(GuardDbMaintenanceProtocol.validEvidence(
            valid.replace("SIGNER $signer", "SIGNER ${"F".repeat(64)}").toByteArray(),
        ))
    }

    @Test fun `client distinguishes helper loss from submitted indeterminate action`() {
        val transport = FakeTransport()
        val client = GuardDbMaintenanceClient(transport)
        assertEquals(GuardDbMaintenanceProtocol.Result.Unreachable, client.cancel(session, 1L))
        transport.long = DaemonLongResult.Indeterminate
        assertEquals(
            GuardDbMaintenanceProtocol.Result.Indeterminate,
            client.action(session, 1L, GuardDbMaintenanceProtocol.Action.INSTALL_B),
        )
    }

    @Test fun `every mutation treats reply loss after possible write as indeterminate`() {
        withCandidates { a, b, authority ->
            val transport = FakeTransport().apply {
                long = DaemonLongResult.Indeterminate
                stream = DaemonStreamResult.Indeterminate
            }
            val client = GuardDbMaintenanceClient(transport)
            val status = GuardDbMaintenanceProtocol.Status(
                3L,
                GuardDbMaintenanceProtocol.Phase.RECOVERY_WITHHELD,
                session,
                boot,
                GuardDbMaintenanceProtocol.Role.B,
                bSha,
                569L,
                15,
                37L,
                null,
            )
            val plan = GuardDbMaintenanceProtocol.Plan(
                session,
                boot,
                signer,
                GuardDbMaintenanceProtocol.Baseline(
                    4L, "c".repeat(64), 14, 37L, "d".repeat(64), "e".repeat(64),
                ),
                listOf(a, b),
                1_800_000L,
                authority,
            )
            listOf(
                client.prepare(plan),
                client.define(session, 3L, a),
                client.stream(session, 3L, a),
                client.streamSettings(session, 3L, authority),
                client.health(status, GuardDbMaintenanceProtocol.Role.B, bSha, 569L, 15, true, 37L,
                    "d".repeat(64), "e".repeat(64),
                    GuardDbMaintenanceProtocol.Probe.PRESENT,
                    GuardDbMaintenanceProtocol.RecoveryProof.NA),
                client.refusal(status, aSha, 568L),
                client.cancel(session, 3L),
            ).forEach { assertEquals(GuardDbMaintenanceProtocol.Result.Indeterminate, it) }
        }
    }

    @Test fun `prepare accepts only the exact bounded overall duration budget`() {
        withCandidates { a, b, authority ->
            val baseline = GuardDbMaintenanceProtocol.Baseline(
                4L, "c".repeat(64), 14, 37L, "d".repeat(64), "e".repeat(64),
            )
            fun plan(budget: Long) = GuardDbMaintenanceProtocol.Plan(
                session, boot, signer, baseline, listOf(a, b), budget, authority,
            )
            listOf(
                0L,
                GuardDbMaintenanceProtocol.MIN_OVERALL_BUDGET_MS - 1L,
                GuardDbMaintenanceProtocol.MAX_OVERALL_BUDGET_MS + 1L,
            ).forEach { budget ->
                assertThrows(IllegalArgumentException::class.java) {
                    GuardDbMaintenanceProtocol.prepare(plan(budget))
                }
            }
            assertTrue(GuardDbMaintenanceProtocol.prepare(
                plan(GuardDbMaintenanceProtocol.MIN_OVERALL_BUDGET_MS),
            ).contains(" ${GuardDbMaintenanceProtocol.MIN_OVERALL_BUDGET_MS} 2 ${authority.bytes} ${authority.sha256}"))
            assertTrue(GuardDbMaintenanceProtocol.prepare(
                plan(GuardDbMaintenanceProtocol.MAX_OVERALL_BUDGET_MS),
            ).contains(" ${GuardDbMaintenanceProtocol.MAX_OVERALL_BUDGET_MS} 2 ${authority.bytes} ${authority.sha256}"))
        }
    }

    private inline fun withCandidates(
        block: (
            GuardDbMaintenanceProtocol.Candidate,
            GuardDbMaintenanceProtocol.Candidate,
            GuardDbSettingsAuthority,
        ) -> Unit,
    ) {
        val aFile = File.createTempFile("guard-a-", ".apk").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        val bFile = File.createTempFile("guard-b-", ".apk").apply { writeBytes(byteArrayOf(5, 6, 7, 8)) }
        val settingsFile = File.createTempFile("guard-settings-", ".v2").apply { writeText("S2\n") }
        try {
            val authority = GuardDbSettingsAuthority(
                GuardDbSettingsAuthority.VERSION,
                settingsFile,
                settingsFile.length(),
                AppInstaller.sha256(settingsFile),
            )
            block(
                GuardDbMaintenanceProtocol.Candidate(
                    GuardDbMaintenanceProtocol.Role.A, aFile, 4L, aSha, 568L, 11, 14, 14,
                    authority.version, authority.bytes, authority.sha256,
                ),
                GuardDbMaintenanceProtocol.Candidate(
                    GuardDbMaintenanceProtocol.Role.B, bFile, 4L, bSha, 569L, 11, 15, 15,
                    authority.version, authority.bytes, authority.sha256,
                ),
                authority,
            )
        } finally {
            aFile.delete()
            bFile.delete()
            settingsFile.delete()
        }
    }

    private class FakeTransport : GuardDbMaintenanceTransport {
        var short: String? = null
        var long: DaemonLongResult = DaemonLongResult.NotSubmitted
        var stream: DaemonStreamResult = DaemonStreamResult.NotSubmitted
        val shortCommands = mutableListOf<String>()
        val longCommands = mutableListOf<String>()
        override fun send(command: String): String? {
            shortCommands += command
            return short
        }
        override fun sendLong(command: String, timeoutMs: Long): DaemonLongResult {
            longCommands += command
            return long
        }
        override fun sendFile(command: String, file: File, timeoutMs: Long): DaemonStreamResult =
            stream
        override fun sendBytesBounded(command: String, maxBytes: Long): ByteArray? = null
    }
}
