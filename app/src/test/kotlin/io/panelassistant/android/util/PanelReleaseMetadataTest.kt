package io.panelassistant.android.util

import io.panelassistant.android.AppIdentity
import io.panelassistant.android.BuildConfig
import io.panelassistant.android.panelassistant.PanelAssistantUpdatePolicy
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
    private val filename = if (AppIdentity.IS_BRIDGE) "ha-paneld-$tag-manual-setup-required.apk"
        else "panel-assistant-$tag-manual-setup-required.apk"
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

    /** Serialized publisher-shaped pair; the callback supplies already-authenticated document bytes.
     * The real release-key verifier is exercised separately by the published signed checksum above. */
    private class ReleasePair(val version: String, range: IntRange = 1..3) {
        val tag = "v$version"
        val release = "https://github.com/panel-assistant/android/releases/download/$tag/ha-paneld-$tag"
        val bridge = "ha-paneld-$tag-manual-setup-required.apk"
        val successor = "panel-assistant-$tag-manual-setup-required.apk"
        val ownName = listOf(successor, bridge).first(SelfUpdater.ownAssetMatch(AppIdentity.IS_BRIDGE))
        val ownHash = if (AppIdentity.IS_BRIDGE) "a".repeat(64) else "b".repeat(64)
        val ownCode = BuildConfig.VERSION_CODE.toLong() + if (AppIdentity.IS_BRIDGE) 10 else 20
        val descriptorUrl = "$release-${if (AppIdentity.IS_BRIDGE) "bridge-install" else "install"}.json"
        val choice = ReleaseCatalog.Version(version, tag, "notes", true,
            "https://github.com/panel-assistant/android/releases/download/$tag/$ownName")
        val documents = mutableMapOf<String, ByteArray>()
        val requested = mutableListOf<String>()

        init {
            fun descriptor(name: String, packageId: String, hash: String, code: Long) = JSONObject()
                .put("apkName", name).put("apkSha256", hash).put("apkSize", 12345678)
                .put("databaseCompatibility", "hapaneld-db:v1:ha-paneld.db:1:3")
                .put("launchComponent", "$packageId/io.panelassistant.android.MainActivity")
                .put("minSdk", 26).put("packageId", packageId).put("releaseTag", tag)
                .put("schema", "io.github.maxlyth.hapaneld.install.v1")
                .put("signerCertificateSha256", AppInstaller.HA_PANELD.certSha256)
                .put("supportedAbis", JSONArray().put("arm64-v8a").put("armeabi-v7a"))
                .put("versionCode", code).put("versionName", version).toString().toByteArray()
            val bridgeHash = "a".repeat(64)
            val successorHash = "b".repeat(64)
            val base = "https://github.com/panel-assistant/android/releases/download/$tag"
            documents["$base/$bridge.sha256"] = "$bridgeHash  $bridge\n".toByteArray()
            documents["$base/$successor.sha256"] = "$successorHash  $successor\n".toByteArray()
            documents["$release-bridge-install.json"] = descriptor(bridge, AppIdentity.LEGACY,
                bridgeHash, BuildConfig.VERSION_CODE.toLong() + 10)
            documents["$release-install.json"] = descriptor(successor, AppIdentity.SUCCESSOR,
                successorHash, BuildConfig.VERSION_CODE.toLong() + 20)
            documents["$release-protocol.json"] = ("{\"artifacts\":[" +
                listOf(bridgeHash, successorHash).joinToString(",") { sha ->
                    "{\"apkSha256\":\"$sha\",\"protocolMax\":${range.last},\"protocolMin\":${range.first}}"
                } + "],\"schema\":\"io.github.maxlyth.hapaneld.protocol.v1\"}\n").toByteArray()
        }

        fun read(candidate: ReleaseCatalog.Version = choice): PanelReleaseMetadata.Candidate? =
            PanelReleaseMetadata.read(candidate.tag, candidate.version, requireNotNull(candidate.apkUrl),
                authenticatedFetch = { url, remainingMs ->
                    assertTrue(remainingMs() > 0L)
                    requested += url
                    documents[url]
                }, remainingMs = { 1000L })
    }

    private fun offers(
        pairs: List<ReleasePair>, limit: Int = 10,
        policy: () -> PanelAssistantUpdatePolicy? = { PanelAssistantUpdatePolicy(1, 3, true) },
        afterRead: () -> Unit = {},
    ) = SelfUpdater.admittedVersions(pairs.map { it.choice }, limit, policy) { choice ->
        pairs.single { it.tag == choice.tag }.read(choice).also { afterRead() }
    }

    @Test fun pickerAndNoTagSelectionFollowOwnSignedApkOfProductionPair() {
        val pair = ReleasePair("1.1.0")
        val expected = pair.choice.copy(protocolRange = 1..3, authenticatedVersionCode = pair.ownCode)
        assertEquals(listOf(expected), offers(listOf(pair)))
        // resolveTarget (used by prepareChannelUpdate) asks this same offer path for its first entry.
        assertEquals(expected, offers(listOf(pair), limit = 1).firstOrNull())
        assertEquals(listOf("${pair.choice.apkUrl}.sha256", "${pair.release}-protocol.json",
            pair.descriptorUrl), pair.requested.take(3))
    }

    @Test fun pickerRefusesDescriptorForTheOtherInstalledIdentity() {
        val pair = ReleasePair("1.1.0")
        pair.documents[pair.descriptorUrl] = JSONObject(pair.documents.getValue(pair.descriptorUrl)
            .toString(Charsets.UTF_8)).put("packageId", AppIdentity.COUNTERPART).toString().toByteArray()
        assertTrue(offers(listOf(pair)).isEmpty())
    }

    @Test fun pickerRequiresRangeForItsExactApkHash() {
        val pair = ReleasePair("1.1.0")
        val protocolUrl = "${pair.release}-protocol.json"
        val protocol = JSONObject(pair.documents.getValue(protocolUrl).toString(Charsets.US_ASCII))
        val records = protocol.getJSONArray("artifacts")
        for (i in 0 until records.length()) {
            val record = records.getJSONObject(i)
            if (record.getString("apkSha256") == pair.ownHash) record.put("apkSha256", "c".repeat(64))
        }
        pair.documents[protocolUrl] = protocolBytes(protocol)
        assertTrue(offers(listOf(pair)).isEmpty())
    }

    @Test fun pickerRefusesMissingAuthenticatedMetadataWithoutHistoricalInference() {
        for (document in listOf("protocol", "descriptor", "checksum")) {
            val pair = ReleasePair("1.1.0")
            pair.documents.remove(when (document) {
                "protocol" -> "${pair.release}-protocol.json"
                "descriptor" -> pair.descriptorUrl
                else -> "${pair.choice.apkUrl}.sha256"
            })
            assertTrue(offers(listOf(pair)).isEmpty())
        }
    }

    @Test fun pickerFiltersIncompatibleNewestBeforeNoTagSelection() {
        val eligible = ReleasePair("1.1.0", 3..3)
        val incompatible = ReleasePair("1.2.0", 4..4)
        assertEquals(listOf("1.1.0"), offers(listOf(eligible, incompatible), limit = 1).map { it.version })
    }

    @Test fun pickerFollowsLiveChannelAndPrereleasePAAlsoAdmitsStable() {
        val stable = ReleasePair("1.1.0")
        val prerelease = ReleasePair("1.2.0-rc1")
        assertEquals(listOf("1.1.0"), offers(listOf(prerelease, stable),
            policy = { PanelAssistantUpdatePolicy(1, 3, false) }).map { it.version })
        assertEquals(listOf("1.2.0-rc1", "1.1.0"), offers(listOf(stable, prerelease)).map { it.version })
    }

    @Test fun pickerRequiresLivePolicyBeforeAndAfterMetadataIO() {
        val pair = ReleasePair("1.1.0")
        var policy: PanelAssistantUpdatePolicy? = null
        assertTrue(offers(listOf(pair), policy = { policy }).isEmpty())
        assertTrue(pair.requested.isEmpty())
        policy = PanelAssistantUpdatePolicy(1, 3, true)
        assertTrue(offers(listOf(pair), policy = { policy }, afterRead = { policy = null }).isEmpty())
        assertTrue(pair.requested.isNotEmpty())
    }

    @Test fun readerRefusesUnownedOrMalformedApkUrlsBeforeFetchingMetadata() {
        val pair = ReleasePair("1.1.0")
        val url = requireNotNull(pair.choice.apkUrl)
        for (invalid in listOf(url.replace("https:", "http:"), url.replace("github.com", "example.com"),
            url.replace(pair.tag, "v1.0.0"), "$url?download=1", "$url#fragment",
            url.replace(pair.ownName, "nested/${pair.ownName}"))) {
            assertNull(pair.read(pair.choice.copy(apkUrl = invalid)))
        }
        assertTrue(pair.requested.isEmpty())
    }
}
