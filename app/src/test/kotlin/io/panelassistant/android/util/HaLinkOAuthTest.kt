package io.panelassistant.android.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class HaLinkOAuthTest {
    @Test fun `authorization-code token response requires a bounded refreshable bearer session`() {
        assertEquals(
            HaLink.OAuthTokens("access", "refresh", 1_800),
            HaLink.parseAuthorizationCodeTokens(
                """{"access_token":"access","refresh_token":"refresh","expires_in":1800,"token_type":"Bearer"}""",
            ),
        )
    }

    @Test fun `malformed incomplete and implausible token responses fail closed`() {
        listOf(
            "{}",
            "not-json",
            """{"access_token":"access","expires_in":1800}""",
            """{"access_token":"","refresh_token":"refresh","expires_in":1800}""",
            """{"access_token":"access","refresh_token":"refresh","expires_in":0}""",
            """{"access_token":"access","refresh_token":"refresh","expires_in":"30"}""",
            """{"access_token":"access","refresh_token":"refresh","expires_in":1.5}""",
            """{"access_token":"access","refresh_token":"refresh","expires_in":${HaLink.MAX_OAUTH_EXPIRES_IN_SEC + 1}}""",
            """{"access_token":"access","refresh_token":"refresh","expires_in":1800,"token_type":"MAC"}""",
        ).forEach { assertNull(it, HaLink.parseAuthorizationCodeTokens(it)) }
    }

    @Test fun `individual tokens and the complete token response are independently bounded`() {
        val oversized = "x".repeat(HaLink.MAX_OAUTH_TOKEN_CHARS + 1)
        assertNull(
            HaLink.parseAuthorizationCodeTokens(
                """{"access_token":"$oversized","refresh_token":"refresh","expires_in":1800}""",
            ),
        )
        assertNull(
            HaLink.parseAuthorizationCodeTokens(
                """{"access_token":"access","refresh_token":"$oversized","expires_in":1800}""",
            ),
        )
        val error = assertThrows(ByteLimitExceeded::class.java) {
            HaLink.readHttpBody(
                ByteArrayInputStream(ByteArray(HaLink.MAX_TOKEN_RESPONSE_BYTES.toInt() + 1)),
                HaLink.MAX_TOKEN_RESPONSE_BYTES,
            )
        }
        assertTrue(error.message.orEmpty().isNotBlank())
    }
}
