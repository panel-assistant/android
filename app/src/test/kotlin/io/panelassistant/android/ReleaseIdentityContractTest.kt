package io.panelassistant.android

import io.panelassistant.android.testsupport.TestSources
import java.util.Properties
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseIdentityContractTest {
    // The release identity is declared once, in app/version.properties. Pinning the numbers here as well
    // meant every version allocation had to edit this test, and a forgotten edit failed CI on the release
    // commit twice. Release builds carry that identity; debug builds keep a stable development identity.
    @Test fun buildConfigCarriesTheDeclaredReleaseIdentity() {
        // Source-text reason: loads version.properties as input data.
        val declared = Properties().apply { TestSources.appFile("version.properties").inputStream().use { load(it) } }
        val versionName = requireNotNull(declared.getProperty("versionName"))
        val versionCode = requireNotNull(declared.getProperty("versionCode")).toInt()
        assertTrue("versionName $versionName is not a release version", Regex("""\d+\.\d+\.\d+(-(rc|preview)\d+)?""").matches(versionName))
        assertTrue("versionCode $versionCode must be positive", versionCode > 0)
        if (BuildConfig.DEBUG) {
            assertEquals("1.0.0", BuildConfig.VERSION_NAME)
            assertEquals(1, BuildConfig.VERSION_CODE)
        } else {
            assertEquals(versionName, BuildConfig.VERSION_NAME)
            assertEquals(versionCode, BuildConfig.VERSION_CODE)
        }
    }
}
