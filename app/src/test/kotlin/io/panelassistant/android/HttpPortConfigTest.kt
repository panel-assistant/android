package io.panelassistant.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpPortConfigTest {
    @Test fun aChosenHttpPortMustPersistBeforeTheCallerCanPublishIt() {
        val prefs = fakePreferences(initial = mapOf("http_port" to 14726))
        val config = Config(prefs.instance)
        config.httpPort = 14727
        assertEquals(14727, Config(prefs.instance).httpPort)

        prefs.commitsSucceed.set(false)
        val refused = runCatching { config.httpPort = 14728 }
        assertTrue("A failed durable write must reach the caller", refused.exceptionOrNull() is IllegalStateException)
        assertEquals("The previous published port must survive a rejected write", 14727, Config(prefs.instance).httpPort)
    }
}
