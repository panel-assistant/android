package io.github.maxlyth.hapaneld

import io.github.maxlyth.hapaneld.testsupport.TestSources
import java.util.Properties
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseIdentityContractTest {
    // The release identity is declared once, in app/version.properties. Pinning the numbers here as well
    // meant every version allocation had to edit this test, and a forgotten edit failed CI on the release
    // commit twice. The contract is that the build carries exactly what that file declares.
    @Test fun buildConfigCarriesTheDeclaredReleaseIdentity() {
        // Source-text reason: loads version.properties as input data.
        val declared = Properties().apply { TestSources.appFile("version.properties").inputStream().use { load(it) } }
        val versionName = requireNotNull(declared.getProperty("versionName"))
        val versionCode = requireNotNull(declared.getProperty("versionCode")).toInt()
        assertTrue("versionName $versionName is not a release version", Regex("""\d+\.\d+\.\d+(-rc\d+)?""").matches(versionName))
        assertTrue("versionCode $versionCode must be positive", versionCode > 0)
        assertEquals(versionName, BuildConfig.VERSION_NAME)
        assertEquals(versionCode, BuildConfig.VERSION_CODE)
    }
}
