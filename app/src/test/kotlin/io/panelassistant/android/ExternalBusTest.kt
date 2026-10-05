package io.panelassistant.android

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalBusProtocolTest {
    @Test fun `config-get keeps exact id and advertises only implemented capabilities`() {
        assertEquals(
            ExternalBusProtocol.Incoming.ConfigGet(7),
            ExternalBusProtocol.parse("""{"id":7,"type":"config/get"}"""),
        )
        val js = ExternalBusProtocol.configResult(7, "0.9.6-test")
        val reply = messageOf(js)
        assertEquals(7, reply.getInt("id"))
        assertEquals("result", reply.getString("type"))
        assertTrue(reply.getBoolean("success"))
        val result = reply.getJSONObject("result")
        assertEquals(
            setOf(
                "hasSettingsScreen", "canWriteTag", "hasExoPlayer", "canCommissionMatter",
                "canImportThreadCredentials", "hasAssist", "hasBarCodeScanner", "canSetupImprov",
                "downloadFileSupported", "hasEntityAddTo", "hasAssistSettings", "appVersion",
            ),
            result.keys().asSequence().toSet(),
        )
        assertEquals("0.9.6-test", result.getString("appVersion"))
        assertEquals(0, result.getInt("hasBarCodeScanner"))
        assertTrue(result.getBoolean("hasSettingsScreen"))
        result.keys().asSequence()
            .filterNot { it in setOf("appVersion", "hasBarCodeScanner", "hasSettingsScreen") }
            .forEach { assertFalse("capability $it must remain off", result.getBoolean(it)) }
    }

    @Test fun `malformed ids and result shapes are rejected rather than coerced`() {
        listOf(
            """{"type":"config/get"}""",
            """{"id":"7","type":"config/get"}""",
            """{"id":7.5,"type":"config/get"}""",
            """{"id":-1,"type":"config/get"}""",
            """{"id":2147483648,"type":"config/get"}""",
            """{"id":1,"type":"result","success":"true"}""",
            """{"type":"result","success":true}""",
        ).forEach { assertTrue(it, ExternalBusProtocol.parse(it) is ExternalBusProtocol.Incoming.Malformed) }
        assertTrue(ExternalBusProtocol.parse("not json") is ExternalBusProtocol.Incoming.Malformed)
        assertTrue(
            ExternalBusProtocol.parse("x".repeat(ExternalBusProtocol.MAX_MESSAGE_CHARS + 1))
                is ExternalBusProtocol.Incoming.Malformed,
        )
        val tooDeep = """{"type":"future/message","payload":""" + "[".repeat(33) + "]".repeat(33) + "}"
        assertEquals(
            ExternalBusProtocol.Incoming.Malformed("too-deep"),
            ExternalBusProtocol.parse(tooDeep),
        )
    }

    @Test fun `known lifecycle messages are typed and future messages stay harmless`() {
        assertEquals(
            ExternalBusProtocol.Incoming.ConnectionStatus(ExternalBusProtocol.ConnectionEvent.CONNECTED),
            ExternalBusProtocol.parse("""{"type":"connection-status","payload":{"event":"connected"}}"""),
        )
        assertEquals(
            ExternalBusProtocol.Incoming.ConnectionStatus(ExternalBusProtocol.ConnectionEvent.DISCONNECTED),
            ExternalBusProtocol.parse("""{"type":"connection-status","event":"disconnected"}"""),
        )
        assertEquals(
            ExternalBusProtocol.Incoming.ConfigScreenShow,
            ExternalBusProtocol.parse("""{"type":"config_screen/show","id":5}"""),
        )
        assertEquals(
            ExternalBusProtocol.Incoming.FrontendLoaded,
            ExternalBusProtocol.parse("""{"type":"frontend/loaded"}"""),
        )
        assertEquals(
            ExternalBusProtocol.Incoming.ThemeUpdate,
            ExternalBusProtocol.parse("""{"type":"theme-update"}"""),
        )
        assertEquals(
            ExternalBusProtocol.Incoming.Unknown("future/message"),
            ExternalBusProtocol.parse("""{"type":"future/message","payload":{"anything":true}}"""),
        )
        assertEquals(
            ExternalBusProtocol.Incoming.Unknown("connection-status"),
            ExternalBusProtocol.parse("""{"type":"connection-status","event":"future-state"}"""),
        )
    }

    @Test fun `failed result retains bounded error details`() {
        val parsed = ExternalBusProtocol.parse(
            """{"id":9,"type":"result","success":false,"error":{"code":"unknown_command","message":"not supported"}}""",
        ) as ExternalBusProtocol.Incoming.Result
        assertEquals(9, parsed.id)
        assertFalse(parsed.success)
        assertEquals("unknown_command", parsed.error?.code)
        assertEquals("not supported", parsed.error?.message)

        val sanitized = ExternalBusProtocol.parse(
            """{"id":10,"type":"result","success":false,"error":{"code":"bad\u0000code","message":"line\nbreak\tend"}}""",
        ) as ExternalBusProtocol.Incoming.Result
        assertEquals("bad code", sanitized.error?.code)
        assertEquals("line break end", sanitized.error?.message)
    }

    @Test fun `navigate and kiosk commands have the frontend exact object framing`() {
        val navigate = messageOf(ExternalBusProtocol.navigate(7, "lovelace/0?theme=dark&return=/#kitchen"))
        assertEquals(7, navigate.getInt("id"))
        assertEquals("command", navigate.getString("type"))
        assertEquals("navigate", navigate.getString("command"))
        assertEquals(
            "/lovelace/0?theme=dark&return=/#kitchen",
            navigate.getJSONObject("payload").getString("path"),
        )
        assertTrue(navigate.getJSONObject("payload").getJSONObject("options").getBoolean("replace"))

        val kiosk = messageOf(ExternalBusProtocol.setKioskMode(8, true))
        assertEquals("kiosk_mode/set", kiosk.getString("command"))
        assertTrue(kiosk.getJSONObject("payload").getBoolean("enable"))
    }

    private fun messageOf(script: String): JSONObject {
        assertTrue(script.startsWith("externalBus(") && script.endsWith(");"))
        return JSONObject(script.removePrefix("externalBus(").removeSuffix(");"))
    }
}

class ExternalBusControllerTest {
    @Test fun `disabled native kiosk causes no command on a fresh document`() {
        val controller = ExternalBusController()
        val session = controller.beginDocument(1, kioskEnabled = false)
        assertNull(controller.onConnection(session, true))
        assertNull(controller.onFrontendLoaded(session))
        assertEquals(0, controller.pendingCount())
    }

    @Test fun `kiosk waits for connection and frontend loaded then correlates success`() {
        val controller = ExternalBusController()
        val session = controller.beginDocument(2, kioskEnabled = true)
        assertNull(controller.onFrontendLoaded(session))
        val command = requireNotNull(controller.onConnection(session, true))
        assertEquals(ExternalBusController.CommandKind.KioskMode(true), command.kind)

        val wrong = controller.onResult(session, result(command.id + 1, success = true))
        assertFalse(wrong.matched)
        assertEquals(1, controller.pendingCount())

        val done = controller.onResult(session, result(command.id, success = true))
        assertTrue(done.matched)
        assertEquals(command.id, done.id)
        assertTrue(done.success)
        assertEquals(0, controller.pendingCount())
        assertNull(controller.onConnection(session, false))
        assertNull("ordinary reconnect must not reassert", controller.onConnection(session, true))
    }

    @Test fun `failed or timed out kiosk retries once then stays quiet`() {
        val controller = ExternalBusController()
        val session = controller.beginDocument(3, kioskEnabled = true)
        controller.onConnection(session, true)
        val first = requireNotNull(controller.onFrontendLoaded(session))
        val failed = controller.onResult(
            session,
            result(first.id, false, "unknown_command", "unsupported"),
        )
        assertTrue(failed.retryKiosk)
        assertEquals("unknown_command", failed.error?.code)

        val second = requireNotNull(controller.retryKiosk(session))
        val timedOut = controller.onTimeout(session, second.id)
        assertTrue(timedOut.matched)
        assertFalse(timedOut.retryKiosk)
        assertNull(controller.retryKiosk(session))
        assertEquals(0, controller.pendingCount())
    }

    @Test fun `new document rejects stale messages and reasserts enabled kiosk once`() {
        val controller = ExternalBusController()
        val old = controller.beginDocument(4, kioskEnabled = true)
        controller.onConnection(old, true)
        val oldCommand = requireNotNull(controller.onFrontendLoaded(old))

        val current = controller.beginDocument(4, kioskEnabled = true)
        assertFalse(controller.owns(old))
        assertFalse(controller.onResult(old, result(oldCommand.id, true)).matched)
        assertNull(controller.onConnection(current, true))
        assertTrue(controller.onFrontendLoaded(current) != null)
    }

    @Test fun `renderer generation replacement rejects stale session`() {
        val controller = ExternalBusController()
        val old = controller.beginDocument(7, kioskEnabled = false)
        val current = controller.beginDocument(8, kioskEnabled = false)
        assertFalse(controller.owns(old))
        assertTrue(controller.owns(current))
        assertNull(controller.navigate(old, "/stale"))
    }

    @Test fun `live kiosk disable sends correlated false only after prior enable`() {
        val controller = ExternalBusController()
        val session = controller.beginDocument(9, kioskEnabled = true)
        controller.onConnection(session, true)
        val enable = requireNotNull(controller.onFrontendLoaded(session))
        controller.onResult(session, result(enable.id, true))
        val disable = requireNotNull(controller.updateKioskPreference(session, false))
        assertEquals(ExternalBusController.CommandKind.KioskMode(false), disable.kind)
        assertTrue(controller.onResult(session, result(disable.id, true)).success)
        assertNull(controller.updateKioskPreference(session, false))
    }

    @Test fun `pending command retention stays bounded and late evicted results are ignored`() {
        val controller = ExternalBusController(pendingLimit = 3)
        val session = controller.beginDocument(10, kioskEnabled = false)
        val commands = (1..5).map { requireNotNull(controller.navigate(session, "/$it")) }
        assertEquals(3, controller.pendingCount())
        assertEquals(listOf(commands[0].id), commands[3].evictedIds)
        assertEquals(listOf(commands[1].id), commands[4].evictedIds)
        assertFalse(controller.onResult(session, result(commands.first().id, true)).matched)
        assertTrue(controller.onResult(session, result(commands.last().id, true)).matched)
    }

    private fun result(
        id: Int,
        success: Boolean,
        code: String? = null,
        message: String? = null,
    ) = ExternalBusProtocol.Incoming.Result(
        id,
        success,
        if (code == null && message == null) null else ExternalBusProtocol.CommandError(code, message),
    )
}
