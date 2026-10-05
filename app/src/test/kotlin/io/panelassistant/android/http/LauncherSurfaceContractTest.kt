package io.panelassistant.android.http

import io.panelassistant.android.util.CompanionInstaller
import org.junit.Assert.assertEquals
import org.junit.Test

class LauncherSurfaceContractTest {
    @Test fun `launchable app query failure cannot suppress an installed Companion renderer`() {
        val rendererChoices = CompanionInstaller.rendererChoices(setOf(CompanionInstaller.MINIMAL_PKG))
        assertEquals(
            "{\"apps\":[],\"renderers\":[{\"pkg\":\"io.homeassistant.companion.android.minimal\"," +
                "\"label\":\"Home Assistant Companion (minimal)\"}]}",
            configureAppInventoryJson(emptyList(), rendererChoices),
        )
    }
}
