package io.panelassistant.android.dashboard

import io.panelassistant.android.ExternalAuthProtocol
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Guards the shared injection-script core. Golden cases pin the complete reviewed document-start
 * output, including the V2-only ha-paneld telemetry transport.
 */
class InjectionScriptTest {
    private fun golden(name: String): String =
        requireNotNull(javaClass.getResourceAsStream("/injection-golden/$name")) { "missing golden $name" }
            .readBytes().toString(Charsets.UTF_8).removeSuffix("\n")

    @Test fun constantsAreTheExactLiteralsTheyReplaced() {
        assertEquals("if(window.top&&window.top!==window)return;", InjectionScript.TOP_FRAME_GUARD)
        assertEquals("selectedTheme", InjectionScript.SELECTED_THEME_KEY)
    }

    @Test fun wsTargetsComputesTheSameOriginsAndPathAsTheInlinedCallSites() {
        val single = InjectionScript.wsTargets("https://ha.example", setOf("https://ha.example"))
        assertEquals("""["wss://ha.example"]""", single.origins)
        assertEquals("\"/api/websocket\"", single.path)

        val multi = InjectionScript.wsTargets(
            "http://ha.example",
            setOf("http://ha.example", "https://ha.example"),
        )
        assertEquals("""["ws://ha.example","wss://ha.example"]""", multi.origins)
        assertEquals("\"/api/websocket\"", multi.path)

        val prefixed = InjectionScript.wsTargets("https://ha.example:8443/prefix", setOf("https://ha.example:8443/prefix"))
        assertEquals("""["wss://ha.example:8443"]""", prefixed.origins)
        assertEquals("\"/prefix/api/websocket\"", prefixed.path)
    }

    // --- byte-identity of every reviewed script builder ---

    @Test fun selectedThemeSeedIsByteIdentical() {
        assertEquals(golden("selectedTheme_seed.js"), ExternalAuthProtocol.selectedThemeJs(dark = true, onlyIfAbsent = true))
    }

    @Test fun selectedThemeToggleIsByteIdentical() {
        assertEquals(golden("selectedTheme_toggle.js"), ExternalAuthProtocol.selectedThemeJs(dark = false, onlyIfAbsent = false))
    }

    @Test fun panelDefaultsIsByteIdentical() {
        assertEquals(golden("panelDefaults.js"), ExternalAuthProtocol.panelDefaultsJs())
    }

    @Test fun entityFilterSingleOriginIsByteIdentical() {
        assertEquals(
            golden("filter_single.js"),
            EntityFilterProtocol.documentStartScript("https://ha.example", listOf("light.alpha", "sensor.temperature")),
        )
    }

    @Test fun entityFilterMultiOriginIsByteIdentical() {
        assertEquals(
            golden("filter_multi.js"),
            EntityFilterProtocol.documentStartScript(
                "http://ha.example",
                listOf("light.alpha"),
                setOf("http://ha.example", "https://ha.example"),
            ),
        )
    }

    @Test fun trafficObserverIsByteIdentical() {
        assertEquals(
            golden("traffic.js"),
            EntityFilterProtocol.trafficObserverDocumentStartScript(
                "http://ha.example",
                setOf("http://ha.example", "https://ha.example"),
            ),
        )
    }

    @Test fun entityLearningWithCostsIsByteIdentical() {
        assertEquals(
            golden("learning_costs.js"),
            EntityLearningProtocol.documentStartScript("https://ha.example", featureCostsEnabled = true),
        )
    }

    @Test fun entityLearningWithoutCostsIsByteIdentical() {
        assertEquals(
            golden("learning_nocosts.js"),
            EntityLearningProtocol.documentStartScript(
                "http://ha.example",
                setOf("http://ha.example", "https://ha.example"),
                featureCostsEnabled = false,
            ),
        )
    }
}
