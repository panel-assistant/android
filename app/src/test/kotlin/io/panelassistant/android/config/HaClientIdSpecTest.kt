package io.panelassistant.android.config

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HaClientIdSpecTest {
    @Test fun oauthImplementationFieldsStayStoredButOnlyLongLivedTokenIsUserFacing() {
        val clientId = SettingsRegistry.spec("ha_client_id")
        val refreshToken = SettingsRegistry.spec("ha_refresh_token")
        val tokenExpiry = SettingsRegistry.spec("ha_token_expiry")
        val longLivedToken = SettingsRegistry.spec("ha_token")

        assertNotNull("client id must remain available to token import and refresh", clientId)
        assertNotNull("refresh token must remain available to token import and refresh", refreshToken)
        assertNotNull("access-token expiry must travel with private OAuth backups", tokenExpiry)
        assertTrue("token provenance is derived, not a user-facing preference", clientId!!.hidden)
        assertTrue("refresh credentials are managed automatically", refreshToken!!.hidden)
        assertTrue("token expiry is managed automatically", tokenExpiry!!.hidden)
        assertTrue("public config reads and ordinary exports must redact token expiry", tokenExpiry.secret)
        assertEquals(SettingType.LONG, tokenExpiry.type)
        assertEquals(Scope.DEVICE, tokenExpiry.scope)
        assertTrue("full backup/export loops must include the expiry registry entry",
            tokenExpiry in SettingsRegistry.settable())
        assertFalse("the understandable LLT fallback remains configurable", longLivedToken!!.hidden)
        assertEquals("Long-lived access token", longLivedToken.label)
        assertFalse("token replacement semantics do not need to clutter the UI helper",
            longLivedToken.help.contains("blank keeps", ignoreCase = true))
    }
}
