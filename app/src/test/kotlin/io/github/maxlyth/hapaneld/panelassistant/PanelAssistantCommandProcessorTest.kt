package io.github.maxlyth.hapaneld.panelassistant

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PanelAssistantCommandProcessorTest {

    @Test fun `standard camera power commands reach the shared panel control`() {
        val descriptor = PanelAssistantChannelDescriptor(
            channel = "camera_enabled", platform = "camera", translationKey = "camera",
            uniqueSuffix = "camera", kind = PanelAssistantValueKind.BOOLEAN,
        )
        val fixture = Fixture(channels = listOf(descriptor))
        fixture.processor.onCommand(command("off", "camera_enabled", false))
        assertEquals(1, fixture.sink.submitted.size)
        assertEquals("camera_enabled", fixture.sink.submitted.single().command.channel)
        assertEquals("OFF", fixture.sink.submitted.single().command.payload)
        fixture.sink.submitted.single().done(PanelAssistantCommandResult.Applied)
        assertEquals("applied" to null, outcome(fixture.answers()))
        fixture.processor.onCommand(command("on", "camera_enabled", true))
        assertEquals("ON", fixture.sink.submitted.last().command.payload)
    }

    @Test fun `an applied command is answered once with its session and id`() {
        val fixture = Fixture()
        fixture.processor.onCommand(command("c1", "relay1", true))

        val submitted = fixture.sink.submitted.single()
        assertEquals("relay1", submitted.command.channel)
        assertEquals("ON", submitted.command.payload)
        assertNull(fixture.answers())
        submitted.done(PanelAssistantCommandResult.Applied)

        val answer = JSONObject(requireNotNull(fixture.answers()))
        assertEquals("panel_assistant/command_result", answer.getString("type"))
        assertEquals(SESSION.token, answer.getString("session"))
        assertEquals("c1", answer.getString("command_id"))
        assertEquals("applied", answer.getString("outcome"))
        assertTrue(!answer.has("code"))
        assertNull(fixture.answers())
    }

    @Test fun `a command addressed to another session never runs and is not answered`() {
        val fixture = Fixture()
        fixture.processor.onCommand(command("c1", "relay1", true, session = "previous-session"))

        assertTrue(fixture.sink.submitted.isEmpty())
        assertNull(fixture.answers())
    }

    @Test fun `a retried command id resends its recorded outcome without running again`() {
        val fixture = Fixture()
        fixture.processor.onCommand(command("c1", "relay1", true))
        // A retry while the first delivery is still running adds nothing: its one answer is still to come.
        fixture.processor.onCommand(command("c1", "relay1", true))
        assertEquals(1, fixture.sink.submitted.size)

        fixture.sink.submitted.single().done(PanelAssistantCommandResult.Refused("invalid_value"))
        assertEquals("refused" to "invalid_value", outcome(fixture.answers()))
        fixture.processor.onCommand(command("c1", "relay1", false))

        assertEquals(1, fixture.sink.submitted.size)
        assertEquals("refused" to "invalid_value", outcome(fixture.answers()))
    }

    @Test fun `commands are refused unless the session holds native authority with commands granted`() {
        listOf(
            session(authority = "shadow", capabilities = listOf("state", "commands", "approval")),
            session(authority = "mqtt", capabilities = listOf("commands")),
            session(authority = "native", capabilities = listOf("state")),
        ).forEach { refused ->
            val fixture = Fixture(session = refused)
            fixture.processor.onCommand(command("c1", "relay1", true, session = refused.token))

            assertTrue(fixture.sink.submitted.isEmpty())
            assertEquals("refused" to "authority_mismatch", outcome(fixture.answers()))
        }
    }

    @Test fun `undescribed, read-only and malformed commands are refused before anything runs`() {
        val fixture = Fixture()
        fixture.processor.onCommand(command("c1", "relay9", true))
        fixture.processor.onCommand(command("c2", "diag_cpu", 5))
        fixture.processor.onCommand(command("c3", "relay1", "ON"))
        fixture.processor.onCommand(command("c4", "relay1", true, deadlineMs = null))
        fixture.processor.onCommand(command("c5", null, true))

        assertTrue(fixture.sink.submitted.isEmpty())
        assertEquals(
            listOf(
                "refused" to "unknown_channel",
                "refused" to "not_commandable",
                "refused" to "invalid_value",
                "refused" to "invalid_value",
                "refused" to "unknown_channel",
            ),
            List(5) { outcome(fixture.answers()) },
        )
    }

    @Test fun `a command still queued past its deadline is refused as expired`() {
        val fixture = Fixture()
        fixture.processor.onCommand(command("c1", "relay1", true, deadlineMs = 500L))
        val submitted = fixture.sink.submitted.single()

        fixture.now = 500L
        assertNull(submitted.command.admit())
        fixture.now = 501L
        assertEquals(PanelAssistantCommandResult.Refused("expired"), submitted.command.admit())
    }

    @Test fun `a sensitive command answers pending approval and runs once approved on the panel`() {
        val fixture = Fixture()
        fixture.processor.onCommand(command("c1", "camera_enabled", true))
        fixture.sink.submitted.single().done(PanelAssistantCommandResult.ApprovalPending("approval-1"))
        assertEquals("pending_approval" to null, outcome(fixture.answers()))

        fixture.processor.pollApprovals(fixture.now)
        assertEquals(1, fixture.sink.submitted.size)
        assertEquals(1_000L, fixture.processor.nextDeadline(0L))

        fixture.sink.approvals["approval-1"] = PanelAssistantApprovalState.APPROVED
        fixture.now = 2_000L
        fixture.processor.pollApprovals(fixture.now)
        val replay = fixture.sink.submitted[1]
        assertEquals("ON", replay.command.payload)
        // The replay's deadline starts when the approval let it run, not when Home Assistant sent it.
        fixture.now = 2_000L + 10_000L
        assertNull(replay.command.admit())

        replay.done(PanelAssistantCommandResult.Applied)
        assertEquals("applied" to null, outcome(fixture.answers()))
        assertNull(fixture.answers())
        assertNull(fixture.processor.nextDeadline(0L))
    }

    @Test fun `a denied approval is refused and an expired one times out, neither running`() {
        val fixture = Fixture()
        fixture.processor.onCommand(command("c1", "camera_enabled", true))
        fixture.processor.onCommand(command("c2", "self_update", true))
        fixture.sink.submitted[0].done(PanelAssistantCommandResult.ApprovalPending("denied"))
        fixture.now = 1_000L
        fixture.sink.submitted[1].done(PanelAssistantCommandResult.ApprovalPending("expired"))
        fixture.answers()
        fixture.answers()

        fixture.sink.approvals["denied"] = PanelAssistantApprovalState.ABSENT
        fixture.now = TTL_MS - 1
        fixture.processor.pollApprovals(fixture.now)
        assertEquals("refused" to "approval_denied", outcome(fixture.answers()))

        fixture.sink.approvals["expired"] = PanelAssistantApprovalState.ABSENT
        fixture.now = 1_000L + TTL_MS
        fixture.processor.pollApprovals(fixture.now)
        assertEquals("refused" to "approval_timeout", outcome(fixture.answers()))
        assertEquals(2, fixture.sink.submitted.size)
    }

    @Test fun `ending the session drops a held command and withdraws its approval`() {
        val fixture = Fixture()
        fixture.processor.onCommand(command("c1", "camera_enabled", true))
        fixture.sink.submitted.single().done(PanelAssistantCommandResult.ApprovalPending("approval-1"))
        fixture.answers()

        fixture.processor.close()
        fixture.sink.approvals["approval-1"] = PanelAssistantApprovalState.APPROVED
        fixture.processor.pollApprovals(fixture.now)

        assertEquals(listOf("approval-1"), fixture.sink.withdrawn)
        assertEquals(1, fixture.sink.submitted.size)
        assertNull(fixture.answers())
    }

    @Test fun `a completion arriving after the session ended withdraws the approval it created`() {
        val fixture = Fixture()
        fixture.processor.onCommand(command("c1", "camera_enabled", true))
        val submitted = fixture.sink.submitted.single()
        fixture.processor.close()

        assertEquals(PanelAssistantCommandResult.Failed("failed"), submitted.command.admit())
        submitted.done(PanelAssistantCommandResult.ApprovalPending("late"))

        assertEquals(listOf("late"), fixture.sink.withdrawn)
        assertNull(fixture.answers())
    }

    @Test fun `a newer command for the channel supersedes one waiting for approval`() {
        val fixture = Fixture()
        fixture.processor.onCommand(command("c1", "camera_enabled", true))
        fixture.sink.submitted.single().done(PanelAssistantCommandResult.ApprovalPending("approval-1"))
        fixture.answers()

        fixture.processor.onCommand(command("c2", "camera_enabled", false))

        assertEquals(listOf("approval-1"), fixture.sink.withdrawn)
        assertEquals("superseded" to null, outcome(fixture.answers()))
        assertEquals("OFF", fixture.sink.submitted[1].command.payload)
    }

    @Test fun `an approved replay asked for approval again is final rather than a second interim`() {
        val fixture = Fixture()
        fixture.processor.onCommand(command("c1", "camera_enabled", true))
        fixture.sink.submitted.single().done(PanelAssistantCommandResult.ApprovalPending("approval-1"))
        fixture.answers()
        fixture.sink.approvals["approval-1"] = PanelAssistantApprovalState.APPROVED
        fixture.processor.pollApprovals(fixture.now)

        fixture.sink.submitted[1].done(PanelAssistantCommandResult.ApprovalPending("approval-2"))

        assertEquals("refused" to "approval_timeout", outcome(fixture.answers()))
        assertEquals(listOf("approval-2"), fixture.sink.withdrawn)
    }

    @Test fun `a stale completion from a replaced attempt changes nothing`() {
        val fixture = Fixture()
        fixture.processor.onCommand(command("c1", "camera_enabled", true))
        val first = fixture.sink.submitted.single()
        first.done(PanelAssistantCommandResult.ApprovalPending("approval-1"))
        fixture.answers()
        fixture.sink.approvals["approval-1"] = PanelAssistantApprovalState.APPROVED
        fixture.processor.pollApprovals(fixture.now)

        first.done(PanelAssistantCommandResult.Applied)
        assertNull(fixture.answers())
        fixture.sink.submitted[1].done(PanelAssistantCommandResult.Failed("hardware_unavailable"))
        assertEquals("failed" to "hardware_unavailable", outcome(fixture.answers()))
    }

    @Test fun `native action press submits one PRESS command and rejects a value`() {
        val fixture = Fixture(channels = CHANNELS + requireNotNull(PanelAssistantChannelCatalog.describe("reload")))
        val frame = JSONObject().put("id", 1).put("type", "event").put("event", JSONObject()
            .put("kind", "command").put("command_id", "press").put("session", "session-token")
            .put("channel", "reload").put("value", JSONObject.NULL).put("deadline_ms", 10_000))
        fixture.processor.onCommand(PanelAssistantTransportProtocol.sessionEvent(frame, 1L) as PanelAssistantSessionEvent.Command)
        assertEquals("reload" to "PRESS", fixture.sink.submitted.single().command.let { it.channel to it.payload })
        fixture.sink.submitted.single().done(PanelAssistantCommandResult.Applied)
        assertEquals("applied" to null, outcome(fixture.answers()))

        fixture.processor.onCommand(command("bad", "reload", true))
        assertEquals("refused" to "invalid_value", outcome(fixture.answers()))
        assertEquals(1, fixture.sink.submitted.size)
    }

    private class Submitted(val command: PanelAssistantCommand, val done: (PanelAssistantCommandResult) -> Unit)

    private class FakeSink : PanelAssistantCommandSink {
        val submitted = mutableListOf<Submitted>()
        val approvals = HashMap<String, PanelAssistantApprovalState>()
        val withdrawn = mutableListOf<String>()

        override fun submit(command: PanelAssistantCommand, done: (PanelAssistantCommandResult) -> Unit) {
            submitted += Submitted(command, done)
        }

        override fun approvalState(approvalId: String): PanelAssistantApprovalState =
            approvals[approvalId] ?: PanelAssistantApprovalState.PENDING

        override fun withdrawApproval(approvalId: String) {
            withdrawn += approvalId
        }
    }

    private class Fixture(
        session: PanelAssistantSession = SESSION,
        channels: List<PanelAssistantChannelDescriptor> = CHANNELS,
    ) {
        var now = 0L
        val sink = FakeSink()
        private var nextId = 100L
        val processor = PanelAssistantCommandProcessor(
            sink = sink,
            session = session,
            channels = channels,
            monotonicMillis = { now },
            approvalTtlMs = TTL_MS,
            log = {},
        )

        fun answers(): String? = processor.next(nextId++)
    }

    private companion object {
        const val TTL_MS = 600_000L

        fun session(authority: String = "native", capabilities: List<String> = listOf("state", "commands", "approval")) =
            PanelAssistantSession(1, "session-token", authority, capabilities, "0.3.0")

        val SESSION = session()

        val CHANNELS = listOf("relay1", "camera_enabled", "self_update", "diag_cpu").map {
            requireNotNull(PanelAssistantChannelCatalog.describe(it)) { it }
        }

        fun command(
            id: String,
            channel: String?,
            value: Any?,
            session: String = "session-token",
            deadlineMs: Long? = 10_000L,
        ) = PanelAssistantSessionEvent.Command(id, session, channel, value, deadlineMs)

        fun outcome(text: String?): Pair<String, String?> {
            val json = JSONObject(requireNotNull(text) { "no answer" })
            return json.getString("outcome") to (if (json.has("code")) json.getString("code") else null)
        }
    }
}
