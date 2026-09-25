package io.github.maxlyth.hapaneld.device

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The provisioner's vendor-strip list must stay inside the TPA10 profile's package list.
 *
 * These are two separate definitions of "vendor apps ha-paneld disables on this hardware" — one in Bash, one
 * in the profile the panel ships — and only the profile travels with the device. Hand-back may re-enable an
 * unowned package **only** when the profile names it, so a package the provisioner disables but the profile
 * omits can never be handed back: the panel has no record of it and no authority to adopt it. That is exactly
 * how the stock launcher came to be the one package that could strand a panel.
 *
 * Containment, not equality: the profile legitimately covers more than the script does (it also names
 * `com.smartos.xinch.smarthome`, which the app tames and the script does not). Requiring equality would push
 * someone to change host behaviour to satisfy a test.
 */
class VendorStripProfileContainmentTest {

    private fun repoRoot(): File {
        val working = File(requireNotNull(System.getProperty("user.dir")))
        return listOf(working.parentFile, working).first { File(it, "scripts/provision.sh").isFile }
    }

    private fun provisionerStripList(): List<String> {
        val script = File(repoRoot(), "scripts/provision.sh").readText()
        val assignment = Regex("""VENDOR_STRIP_PACKAGES="([^"]*)"""").find(script)
        assertTrue(
            "scripts/provision.sh must declare VENDOR_STRIP_PACKAGES as one quoted list; " +
                "an inlined loop would put the list back out of this test's reach",
            assignment != null,
        )
        return requireNotNull(assignment).groupValues[1].split(Regex("\\s+")).filter { it.isNotBlank() }
    }

    // Source-text reason: loads the shipped TPA10 profile as input data (provisioner/profile contract).
    private fun profilePackages(): List<String> {
        val profile = File(repoRoot(), "app/src/main/assets/device-profiles/tpa10.yaml").readText()
        return Regex("""^\s*-\s*package:\s*(\S+)""", RegexOption.MULTILINE)
            .findAll(profile)
            .map { it.groupValues[1] }
            .toList()
    }

    @Test fun `every package the provisioner disables is named by the profile`() {
        val strip = provisionerStripList()
        val profile = profilePackages().toSet()

        // Prove the test can see something, so a rename that empties either side fails loudly rather than
        // passing vacuously on two empty sets.
        assertTrue("the provisioner strip list must not be empty", strip.isNotEmpty())
        assertTrue("the TPA10 profile must name packages", profile.isNotEmpty())

        assertEquals(
            "every vendor package scripts/provision.sh disables must be named in tpa10.yaml, or a panel " +
                "tamed by the provisioner cannot be handed it back",
            emptyList<String>(),
            strip.filterNot { it in profile }.sorted(),
        )
    }

    @Test fun `the stock launcher is named by the profile`() {
        // Called out on its own because this is the package whose absence produces FallbackHome, and the
        // containment test above would still pass if someone removed it from BOTH lists.
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
