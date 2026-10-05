package io.panelassistant.android.sensors

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.ArrayDeque

class HaCurrentUserClientTest {
    @Test fun `connected status projects only a bounded display name`() = kotlinx.coroutines.test.runTest {
        val client = HaCurrentUserClient(
            auth = HaApiSessionProvider { HaApiSession("https://ha.example", "secret") },
            transport = HaCurrentUserTransport { _, _ ->
                HaCurrentUserRead(
                    JSONObject()
                        .put("id", "private-user-id")
                        .put("name", "  Alice\nAdministrator  ")
                        .put("is_admin", true)
                        .put("credentials", "private"),
                    "de_de",
                )
            },
        )

        val status = client.status() as HaCurrentUserStatus.Connected

        assertEquals("Alice Administrator", status.displayName)
        assertTrue(status.toString().contains("Alice Administrator"))
        assertTrue(!status.toString().contains("private-user-id"))
        assertTrue(!status.toString().contains("credentials"))
        assertEquals("de-DE", status.language)
    }

    @Test fun `authentication rejection retries once then reports rejected`() = kotlinx.coroutines.test.runTest {
        val forces = mutableListOf<Boolean>()
        var calls = 0
        val client = HaCurrentUserClient(
            auth = HaApiSessionProvider { force ->
                forces += force
                HaApiSession("https://ha.example", "secret")
            },
            transport = HaCurrentUserTransport { _, _ ->
                calls++
                throw HaAuthenticationException("rejected")
            },
        )

        assertEquals(HaCurrentUserStatus.Rejected, client.status())
        assertEquals(listOf(false, true), forces)
        assertEquals(2, calls)
    }

    @Test fun `missing credentials avoid network and blank names remain anonymous`() = kotlinx.coroutines.test.runTest {
        var calls = 0
        val missing = HaCurrentUserClient(
            auth = HaApiSessionProvider { HaApiSession("https://ha.example", null) },
            transport = HaCurrentUserTransport { _, _ -> calls++; HaCurrentUserRead(JSONObject(), null) },
        )
        assertEquals(HaCurrentUserStatus.NotConfigured, missing.status())
        assertEquals(0, calls)

        val blank = HaCurrentUserClient(
            auth = HaApiSessionProvider { HaApiSession("https://ha.example", "secret") },
            transport = HaCurrentUserTransport { _, _ ->
                HaCurrentUserRead(JSONObject().put("name", "\u0000  "), null)
            },
        ).status() as HaCurrentUserStatus.Connected
        assertNull(blank.displayName)
    }

    @Test fun `language request uses the authenticated socket and parses the nested locale`() = kotlinx.coroutines.test.runTest {
        val socket = FakeSocket(
            JSONObject().put("type", "auth_required"),
            JSONObject().put("type", "auth_ok"),
            result(1, JSONObject().put("name", "Alice")),
            result(2, JSONObject().put("value", JSONObject().put("language", "zh_hans_cn"))),
        )

        val user = readRequiredHaCurrentUser(socket, "secret")
        val language = readOptionalHaLanguage(socket)

        assertEquals("Alice", user.getString("name"))
        assertEquals("zh-Hans-CN", language)
        assertEquals(
            listOf(
                setOf("type", "access_token"),
                setOf("id", "type"),
                setOf("id", "type", "key"),
            ),
            socket.sent.map { it.keys().asSequence().toSet() },
        )
        assertEquals("auth", socket.sent[0].getString("type"))
        assertEquals("secret", socket.sent[0].getString("access_token"))
        assertEquals(1, socket.sent[1].getInt("id"))
        assertEquals("auth/current_user", socket.sent[1].getString("type"))
        assertEquals(2, socket.sent[2].getInt("id"))
        assertEquals("frontend/get_user_data", socket.sent[2].getString("type"))
        assertEquals("language", socket.sent[2].getString("key"))
    }

    @Test fun `optional language failures never downgrade a successful current user`() = kotlinx.coroutines.test.runTest {
        val failed = FakeSocket(failure = IllegalStateException("optional command rejected"))
        assertNull(readOptionalHaLanguage(failed))
        assertEquals("frontend/get_user_data", failed.sent.single().getString("type"))

        val malformed = FakeSocket(JSONObject().put("type", "result").put("id", 2).put("success", true))
        assertNull(readOptionalHaLanguage(malformed))

        val status = HaCurrentUserClient(
            auth = HaApiSessionProvider { HaApiSession("https://ha.example", "secret") },
            transport = HaCurrentUserTransport { _, _ ->
                HaCurrentUserRead(JSONObject().put("name", "Alice"), null)
            },
        ).status()
        assertEquals(HaCurrentUserStatus.Connected("Alice", null), status)
    }

    @Test fun `language signal is bounded and canonicalized or discarded`() {
        assertEquals("fr-CA", canonicalHaLanguage(" fr_ca "))
        assertEquals("zh-Hans-CN", canonicalHaLanguage("ZH-hans-cn"))
        assertNull(canonicalHaLanguage("e"))
        assertNull(canonicalHaLanguage("en<script>"))
        assertNull(canonicalHaLanguage("a".repeat(64)))
        assertNull(canonicalHaLanguage("x-private"))
    }

    private class FakeSocket(
        vararg responses: JSONObject,
        private val failure: Throwable? = null,
    ) : HaCurrentUserSocket {
        private val responses = ArrayDeque(responses.toList())
        val sent = mutableListOf<JSONObject>()

        override suspend fun receive(): JSONObject {
            failure?.let { throw it }
            return responses.removeFirst()
        }

        override suspend fun send(message: JSONObject) {
            sent += JSONObject(message.toString())
        }
    }

    private companion object {
        fun result(id: Int, value: JSONObject): JSONObject = JSONObject()
            .put("id", id)
            .put("type", "result")
            .put("success", true)
            .put("result", value)
    }
}
