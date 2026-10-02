package io.github.maxlyth.hapaneld.util

import io.github.maxlyth.hapaneld.AppIdentity
import io.github.maxlyth.hapaneld.BuildConfig
import io.github.maxlyth.hapaneld.panelassistant.PanelAssistantUpdatePolicy
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PanelReleaseMetadataTest {
    private val hash = "a".repeat(64)
    private val version = "1.1.0"
    private val tag = "v$version"
    private val filename = "panel-assistant-$tag.apk"
    private fun descriptor() = JSONObject()
        .put("schema", "io.github.maxlyth.hapaneld.install.v1")
        .put("apkName", filename).put("apkSha256", hash).put("releaseTag", tag)
        .put("versionName", version).put("versionCode", BuildConfig.VERSION_CODE + 1)
        .put("packageId", AppIdentity.OWN).put("signerCertificateSha256", AppInstaller.HA_PANELD.certSha256)
        .put("apkSize", 100).put("databaseCompatibility", JSONObject()).put("launchComponent", "component")
        .put("minSdk", 26).put("supportedAbis", JSONArray().put("arm64-v8a"))
    private fun protocols() = JSONObject().put("schema", "io.github.maxlyth.hapaneld.protocol.v1")
        .put("artifacts", JSONArray().put(JSONObject().put("apkSha256", hash)
            .put("protocolMin", 1).put("protocolMax", 3)))
    private fun proof(descriptor: JSONObject = descriptor(), protocols: JSONObject = protocols(),
                      checksum: String = "$hash  $filename\n") = PanelReleaseMetadata.verifiedCandidate(
        tag, version, filename, checksum.toByteArray(), protocolBytes(protocols),
        descriptor.toString().toByteArray(),
    )

    private fun protocolBytes(protocols: JSONObject): ByteArray {
        val records = protocols.getJSONArray("artifacts")
        return ("{\"artifacts\":[" + (0 until records.length()).map { records.getJSONObject(it) }
            .sortedBy { it.getString("apkSha256") }.joinToString(",") {
                "{\"apkSha256\":\"${it.getString("apkSha256")}\",\"protocolMax\":${it.getInt("protocolMax")},\"protocolMin\":${it.getInt("protocolMin")}}"
            } + "],\"schema\":\"${protocols.getString("schema")}\"}\n").toByteArray()
    }

    @Test fun exactAuthenticatedDocumentsAgreeOnApkIdentityAndRange() {
        assertEquals(PanelReleaseMetadata.Candidate(1..3, BuildConfig.VERSION_CODE.toLong() + 1), proof())
        assertNull(proof(checksum = "$hash  other.apk\n"))
        assertNull(proof(descriptor = descriptor().put("apkSha256", "b".repeat(64))))
        assertNull(proof(descriptor = descriptor().put("versionName", "1.0.0")))
        assertNull(proof(descriptor = descriptor().put("versionCode", "100")))
        assertNull(proof(descriptor = descriptor().put("packageId", AppIdentity.COUNTERPART)))
        assertNull(proof(protocols = protocols().apply {
            getJSONArray("artifacts").getJSONObject(0).put("protocolMin", 4)
        }))
        assertNull(proof(protocols = protocols().apply {
            getJSONArray("artifacts").put(getJSONArray("artifacts").getJSONObject(0))
        }))
    }

    @Test fun protocolProofRequiresCanonicalBytesWithoutDuplicateKeys() {
        val canonical = protocolBytes(protocols())
        fun read(bytes: ByteArray) = PanelReleaseMetadata.verifiedCandidate(tag, version, filename,
            "$hash  $filename\n".toByteArray(), bytes, descriptor().toString().toByteArray())
        assertEquals(PanelReleaseMetadata.Candidate(1..3, BuildConfig.VERSION_CODE.toLong() + 1), read(canonical))
        assertNull(read(canonical.dropLast(1).toByteArray()))
        assertNull(read(canonical.toString(Charsets.US_ASCII).replace("\"protocolMin\":1", "\"protocolMin\":9,\"protocolMin\":1").toByteArray()))
    }

    @Test fun publishedReleaseChecksumAuthenticatesOnlyItsExactBytes() {
        // Published v0.9.8 checksum and detached signature from the fixed Android release repository.
        val bytes = "596eb37f413a840393c963bac6e57b82b7bc3a5a8e8b0f927fe17096f507ec68  panel-assistant-v0.9.8-manual-setup-required.apk\n".toByteArray()
        val signature = java.util.Base64.getDecoder().decode(
            "ioGek3ldZNhdBRLavNlPpDOrrD91rvAA3oI9FNkNrdgALWk4uirgBj6bAqAamYzY+HUlYeytG0ug4hpJxRr2Mpts3a4jJqkTpMouEOa7zsH+6OFU9LIkaeNVQUCsPvygOlC9K3thCH0KaqoV7Nh1fFMLdHg09nGiD685xX6pjQQb/aEnUq6BNzxFQrfCbI3UGODR0Ogfng/eNXm0AjnhvUMNzJzUho5jUCKD1PEabXcb3Yyw7YL9RPMQXCCGXpbA7pifEFYRbkXDuLODc39veZK0Y5i8S9qBlXxU4SkCNb/Ux03BfUf3jRsqBimpNUgoASyp6xWVpTwtmD/cAzds/g==",
        )
        assertTrue(PanelReleaseMetadata.authentic(bytes, signature))
        assertFalse(PanelReleaseMetadata.authentic(bytes.copyOf().apply { this[0] = '0'.code.toByte() }, signature))
        assertFalse(PanelReleaseMetadata.authentic(bytes, signature.copyOf().apply { this[0] = 0 }))
    }

    @Test fun untrustedOrOversizedSignedDocumentsNeverAuthenticate() {
        assertFalse(PanelReleaseMetadata.authentic("{}".toByteArray(), ByteArray(256)))
        assertFalse(PanelReleaseMetadata.authentic(ByteArray(65537), ByteArray(256)))
        assertFalse(PanelReleaseMetadata.authentic("{}".toByteArray(), ByteArray(513)))
    }

    private fun choice(version: String) = ReleaseCatalog.Version(version, "v$version", "notes", true,
        "https://github.com/panel-assistant/android/releases/download/v$version/panel-assistant-v$version.apk")

    @Test fun pickerFiltersBeforeNewestSelectionAndIncludesStableOnPrereleasePA() {
        val policy = PanelAssistantUpdatePolicy(1, 3, true)
        val choices = SelfUpdater.admittedVersions(listOf(choice("1.2.0"), choice("1.1.0")), 1, { policy }) {
            PanelReleaseMetadata.Candidate(if (it.version == "1.2.0") 4..4 else 3..3,
                BuildConfig.VERSION_CODE.toLong() + 1)
        }
        assertEquals(listOf("1.1.0"), choices.map { it.version })
        assertEquals(listOf("1.1.0"), SelfUpdater.admittedVersions(
            listOf(choice("1.2.0-rc1"), choice("1.1.0")), 1, { policy.copy(prerelease = false) },
        ) { PanelReleaseMetadata.Candidate(3..3, BuildConfig.VERSION_CODE.toLong() + 1) }.map { it.version })
    }

    @Test fun pickerDoesNotAdvertiseUnknownMetadataOrPolicyThatDisconnectedDuringIO() {
        var policy: PanelAssistantUpdatePolicy? = PanelAssistantUpdatePolicy(1, 3, true)
        assertEquals(emptyList<ReleaseCatalog.Version>(), SelfUpdater.admittedVersions(
            listOf(choice("1.1.0")), 10, { policy },
        ) { null })
        assertEquals(emptyList<ReleaseCatalog.Version>(), SelfUpdater.admittedVersions(
            listOf(choice("1.1.0")), 10, { policy },
        ) {
            policy = null
            PanelReleaseMetadata.Candidate(3..3, BuildConfig.VERSION_CODE.toLong() + 1)
        })
    }
}
