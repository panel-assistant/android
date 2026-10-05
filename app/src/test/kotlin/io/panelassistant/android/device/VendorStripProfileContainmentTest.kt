package io.panelassistant.android.device

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Retained vendor adoption profiles must preserve safe home hand-back behavior. */
class VendorStripProfileContainmentTest {

    private fun repoRoot(): File {
        val working = File(requireNotNull(System.getProperty("user.dir")))
        return listOf(working.parentFile, working).first { File(it, "scripts/provision.sh").isFile }
    }

    // Source-text reason: loads the shipped TPA10 profile as input data.
    private fun profilePackages(): List<String> {
        val profile = File(repoRoot(), "app/src/main/assets/device-profiles/tpa10.yaml").readText()
        return Regex("""^\s*-\s*package:\s*(\S+)""", RegexOption.MULTILINE)
            .findAll(profile)
            .map { it.groupValues[1] }
            .toList()
    }

    @Test fun `the stock launcher is named by the profile`() {
        // Called out on its own because this is the package whose absence produces FallbackHome, and the
        // other adoption entries must not obscure its required presence.
        assertTrue(
            "tpa10.yaml must name the stock launcher so a tamed panel can be handed its home screen back",
            "com.smartos.xinch.launcher" in profilePackages(),
        )
    }

    @Test fun `no profile package is a default tame pick`() {
        // Adoption authority is not a recommendation to disable. The launcher and system UI are listed so a
        // provisioner-tamed panel can be REPAIRED; a `recommended` importance would offer to disable the
        // panel's own home screen from the vendor-packages picker.
        val profile = File(repoRoot(), "app/src/main/assets/device-profiles/tpa10.yaml").readText()
        val importances = Regex("""^\s*importance:\s*(\S+)""", RegexOption.MULTILINE)
            .findAll(profile).map { it.groupValues[1] }.toList()
        assertTrue("the profile must declare importances", importances.isNotEmpty())
        assertEquals(
            "no TPA10 vendor package may be a default tame pick",
            emptyList<String>(),
            importances.filterNot { it == "optional" },
        )
    }
}
