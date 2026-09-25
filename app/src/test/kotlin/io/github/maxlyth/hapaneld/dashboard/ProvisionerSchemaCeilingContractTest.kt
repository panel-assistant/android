package io.github.maxlyth.hapaneld.dashboard

import io.github.maxlyth.hapaneld.testsupport.TestSources
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The provisioner refuses to snapshot a database whose `user_version` is above its own ceiling, so a
 * schema bump that forgets the provisioner turns that fail-closed gate against every panel upgraded to
 * the new release. The script ceiling and the app constant are compared so a drift names the two files to change together.
 */
class ProvisionerSchemaCeilingContractTest {
    @Test fun provisionerCeilingMatchesTheAppSchemaVersion() {
        val provisioner = "scripts/provision.sh"
        val ceiling = soleInteger(provisioner, Regex("""(?m)^DB_SUPPORTED_USER_VERSION_MAX=(\d+)$"""))
        val current = EntityCatalogSchema.CURRENT_VERSION

        assertEquals(
            "DB_SUPPORTED_USER_VERSION_MAX in $provisioner ($ceiling) must equal " +
                "EntityCatalogSchema.CURRENT_VERSION ($current); bump them together",
            current,
            ceiling,
        )
    }

    private fun soleInteger(path: String, pattern: Regex): Int {
        val matches = pattern.findAll(TestSources.repoFile(path).readText()).toList()
        assertEquals("expected exactly one ${pattern.pattern} in $path", 1, matches.size)
        return matches.single().groupValues[1].toInt()
    }
}
