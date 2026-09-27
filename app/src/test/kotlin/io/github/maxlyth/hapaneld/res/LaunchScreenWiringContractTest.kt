package io.github.maxlyth.hapaneld.res

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LaunchScreenWiringContractTest {
    private fun englishString(name: String): String {
        // Source-text reason: reads the shipped English string catalogue, a user-visible copy contract.
        val xml = listOf(
            File("src/main/res/values/strings.xml"),
            File("app/src/main/res/values/strings.xml"),
        ).first { it.isFile }.readText()
        return Regex("""<string name="${Regex.escape(name)}"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .find(xml)?.groupValues?.get(1)
            ?: error("missing English string resource: $name")
    }

    @Test fun qrIntroDescribesTheOnPanelDashboardAndLauncherCopyContract() {
        val description = englishString("panel_generic_description")
        assertTrue(description.contains("dashboard, app launcher and panel controls"))
        assertTrue(description.contains("speaker and sensors to Home Assistant over your local network"))
        assertTrue(description.contains("Configure the panel from "))
        assertTrue(description.contains("a browser using the address below"))
        assertFalse(description.contains("running in the background"))
        assertFalse(description.contains("runs in the background so Home Assistant can control"))
    }
}
