package io.github.maxlyth.hapaneld.util

import io.github.maxlyth.hapaneld.BuildConfig
import io.github.maxlyth.hapaneld.dashboard.EntityCatalogSchema
import io.github.maxlyth.hapaneld.testsupport.TestSources
import io.github.maxlyth.hapaneld.util.DatabaseCompatibilityApkContract.Boundary
import io.github.maxlyth.hapaneld.util.DatabaseCompatibilityApkContract.Parsed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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
