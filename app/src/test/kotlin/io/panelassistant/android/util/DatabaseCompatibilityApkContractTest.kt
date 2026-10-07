package io.panelassistant.android.util

import io.panelassistant.android.BuildConfig
import io.panelassistant.android.dashboard.EntityCatalogSchema
import io.panelassistant.android.testsupport.TestSources
import io.panelassistant.android.util.DatabaseCompatibilityApkContract.Boundary
import io.panelassistant.android.util.DatabaseCompatibilityApkContract.Parsed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

class DatabaseCompatibilityApkContractTest {
    @Test fun generatedApkContractMatchesTheSchemaAuthority() {
        val expected = DatabaseCompatibilityApkContract.encode(
            Boundary(
                formatVersion = 1,
                databaseName = "ha-paneld.db",
                minimumSchema = EntityCatalogSchema.MINIMUM_SUPPORTED_VERSION,
                maximumSchema = EntityCatalogSchema.CURRENT_VERSION,
            ),
        )
        assertEquals(
            expected,
            BuildConfig.DATABASE_COMPATIBILITY,
        )
    }

    @Test fun mergedDebugManifestCarriesTheExactDynamicSchemaContract() {
        val expected = DatabaseCompatibilityApkContract.encode(
            Boundary(
                formatVersion = 1,
                databaseName = "ha-paneld.db",
                minimumSchema = EntityCatalogSchema.MINIMUM_SUPPORTED_VERSION,
                maximumSchema = EntityCatalogSchema.CURRENT_VERSION,
            ),
        )
        // Source-text reason: the merged manifest is the shipped APK metadata contract the installer reads.
        val merged = TestSources.appFile(
            "build/intermediates/merged_manifests/debug/processDebugManifest/AndroidManifest.xml",
        ).readText()

        assertTrue(merged.contains("android:name=\"${DatabaseCompatibilityApkContract.METADATA_NAME}\""))
        assertTrue(merged.contains("android:value=\"$expected\""))
    }

    @Test fun mergedManifestAlsoCarriesBothContractsUnderTheNamesThePreviousInstallerReads() {
        // Source-text reason: the merged manifest is the APK metadata. A 0.9.10 panel installs this build
        // with its own installer, which reads only the pre-0.9.11 names; without them it refuses the update.
        val merged = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder()
            .parse(TestSources.appFile("build/intermediates/merged_manifests/debug/processDebugManifest/AndroidManifest.xml"))
        val android = "http://schemas.android.com/apk/res/android"
        val nodes = merged.getElementsByTagName("meta-data")
        val values = (0 until nodes.length).map { nodes.item(it) as Element }
            .filter { (it.parentNode as Element).tagName == "application" }
            .groupBy({ it.getAttributeNS(android, "name") }, { it.getAttributeNS(android, "value") })
        for ((current, previous) in listOf(
            DatabaseCompatibilityApkContract.METADATA_NAME to "io.github.maxlyth.hapaneld.DATABASE_COMPATIBILITY",
            AppInstaller.PROTOCOL_METADATA_NAME to "io.github.maxlyth.hapaneld.PANEL_ASSISTANT_PROTOCOL",
        )) {
            assertEquals("$current must appear once: $values", 1, values[current]?.size)
            assertEquals("$previous must carry the same value as $current", values[current], values[previous])
        }
        assertTrue(DatabaseCompatibilityApkContract.parse(values.getValue(DatabaseCompatibilityApkContract.METADATA_NAME).single()) is Parsed.Valid)
        assertTrue(AppInstaller.parseNativeProtocolMetadata(values.getValue(AppInstaller.PROTOCOL_METADATA_NAME).single()) != null)
    }

    @Test fun parsesTheFiniteSignedContract() {
        assertEquals(
            Parsed.Valid(Boundary(1, "ha-paneld.db", 11, 14)),
            DatabaseCompatibilityApkContract.parse("hapaneld-db:v1:ha-paneld.db:11:14"),
        )
    }

    @Test fun missingMetadataIsNotCollapsedIntoMalformedOrADefaultBoundary() {
        assertEquals(Parsed.Missing, DatabaseCompatibilityApkContract.parse(null))
    }

    @Test fun malformedOrIncoherentMetadataNeverProducesABoundary() {
        val malformed = listOf(
            "",
            "hapaneld-db:v1:ha-paneld.db:11",
            "hapaneld-db:v1:ha-paneld.db:11:14:ignored",
            "hapaneld-db:v2:ha-paneld.db:11:14",
            "hapaneld-db:v1:other.db:11:14",
            "hapaneld-db:v1:ha-paneld.db:0:14",
            "hapaneld-db:v1:ha-paneld.db:011:14",
            "hapaneld-db:v1:ha-paneld.db:+11:14",
            "hapaneld-db:v1:ha-paneld.db:15:14",
            "hapaneld-db:v1:ha-paneld.db:11:not-a-number",
        )

        malformed.forEach { raw ->
            assertTrue("must reject $raw", DatabaseCompatibilityApkContract.parse(raw) is Parsed.Malformed)
        }
    }

    @Test fun encoderCannotCreateAContractTheParserWouldReject() {
        val boundary = Boundary(1, "ha-paneld.db", 11, 14)
        val encoded = DatabaseCompatibilityApkContract.encode(boundary)

        assertEquals("hapaneld-db:v1:ha-paneld.db:11:14", encoded)
        assertEquals(Parsed.Valid(boundary), DatabaseCompatibilityApkContract.parse(encoded))
    }
}
