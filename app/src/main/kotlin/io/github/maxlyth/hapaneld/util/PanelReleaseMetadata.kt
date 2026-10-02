package io.github.maxlyth.hapaneld.util

import io.github.maxlyth.hapaneld.AppIdentity
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/** Bounded, release-key-authenticated eligibility proof; APK bytes are still verified at install. */
internal object PanelReleaseMetadata {
    private const val MAX_DOCUMENT_BYTES = 65536L
    private const val MAX_SIGNATURE_BYTES = 512L
    private const val PUBLIC_KEY = "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA3LH+db6kzNld/ERP612xUOOG6TINFvuKJKinQAWi6Gfm2jCmW4plhw+w4vXgP8B8FpY0SLatUVo3EeAi+f1KEHj0syPi7Sx781o1oc9LicQG4LjWVZPe+m4AkPl9ByopobQwYTXOjaq6ZFpFgAZeNwQ44hg5o9iVKtxpnnjHEc/m6o9TBySQvxDWF3RxCDyPLNBqhrsgKsDlAyh+dtA8aJpQsDUJoX42xsRvA1hkRCpnWdEs1Bwfyv0ztlOxj7MxeFrFxWc3mnUyGhsn6rCTO+ygQ2m7FHp3D5t1+wFIendluEzUC+y9MpUHmoyq/lFrVuA8EOiy1U+z7Lr1vBWfLQIDAQAB"
    private val key = KeyFactory.getInstance("RSA")
        .generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(PUBLIC_KEY)))

    data class Candidate(val range: IntRange, val versionCode: Long)

    fun read(tag: String, version: String, apkUrl: String, remainingMs: () -> Long): Candidate? = runCatching {
        if (!ReleaseCatalog.validTag(tag)) return null
        val asset = URL(apkUrl)
        val prefix = "/panel-assistant/android/releases/download/$tag/"
        if (asset.protocol != "https" || asset.host != "github.com" || !asset.path.startsWith(prefix) ||
            asset.query != null || asset.ref != null) return null
        val filename = asset.path.removePrefix(prefix)
        if (!Regex("[A-Za-z0-9._-]+\\.apk").matches(filename)) return null
        val release = "https://github.com/panel-assistant/android/releases/download/$tag/ha-paneld-$tag"
        val checksum = authenticated("$apkUrl.sha256", remainingMs) ?: return null
        val protocol = authenticated("$release-protocol.json", remainingMs) ?: return null
        val descriptor = authenticated("$release-install.json", remainingMs) ?: return null
        verifiedCandidate(tag, version, filename, checksum, protocol, descriptor)
    }.getOrNull()

    /** The authenticated checksum names the selected asset; both JSON documents bind its digest. */
    internal fun verifiedCandidate(
        tag: String, version: String, filename: String,
        checksum: ByteArray, protocolBytes: ByteArray, descriptorBytes: ByteArray,
    ): Candidate? = runCatching {
        val digestLine = checksum.toString(Charsets.US_ASCII).trimEnd('\n')
        val matched = Regex("([0-9a-f]{64})  ([A-Za-z0-9._-]+)").matchEntire(digestLine) ?: return null
        if (matched.groupValues[2] != filename) return null
        val hash = matched.groupValues[1]
        val descriptor = JSONObject(descriptorBytes.toString(Charsets.UTF_8))
        if (descriptor.keys().asSequence().toSet() != setOf("apkName", "apkSha256", "apkSize",
                "databaseCompatibility", "launchComponent", "minSdk", "packageId", "releaseTag", "schema",
                "signerCertificateSha256", "supportedAbis", "versionCode", "versionName")) return null
        if (descriptor.optString("schema") != "io.github.maxlyth.hapaneld.install.v1" ||
            descriptor.optString("apkName") != filename || descriptor.optString("apkSha256") != hash ||
            descriptor.optString("releaseTag") != tag || descriptor.optString("versionName") != version ||
            descriptor.optString("packageId") != AppIdentity.OWN ||
            descriptor.optString("signerCertificateSha256") != AppInstaller.HA_PANELD.certSha256) return null
        val code = when (val value = descriptor.opt("versionCode")) {
            is Int -> value.toLong()
            is Long -> value
            else -> return null
        }
        if (code <= 0L) return null
        val protocol = JSONObject(protocolBytes.toString(Charsets.UTF_8))
        if (protocol.keys().asSequence().toSet() != setOf("schema", "artifacts") ||
            protocol.optString("schema") != "io.github.maxlyth.hapaneld.protocol.v1") return null
        val artifacts = protocol.optJSONArray("artifacts") ?: return null
        if (artifacts.length() !in 1..500) return null
        val records = linkedMapOf<String, IntRange>()
        for (i in 0 until artifacts.length()) {
            val record = artifacts.optJSONObject(i) ?: return null
            if (record.keys().asSequence().toSet() != setOf("apkSha256", "protocolMin", "protocolMax")) return null
            val sha = record.opt("apkSha256") as? String ?: return null
            val low = record.opt("protocolMin") as? Int ?: return null
            val high = record.opt("protocolMax") as? Int ?: return null
            if (!Regex("[0-9a-f]{64}").matches(sha) || low < 1 || high < low ||
                records.put(sha, low..high) != null) return null
        }
        val canonical = "{\"artifacts\":[" + records.toSortedMap().entries.joinToString(",") { (sha, range) ->
            "{\"apkSha256\":\"$sha\",\"protocolMax\":${range.last},\"protocolMin\":${range.first}}"
        } + "],\"schema\":\"io.github.maxlyth.hapaneld.protocol.v1\"}\n"
        if (!protocolBytes.contentEquals(canonical.toByteArray(Charsets.US_ASCII))) return null
        Candidate(records[hash] ?: return null, code)
    }.getOrNull()

    private fun authenticated(url: String, remainingMs: () -> Long): ByteArray? {
        val body = fetch(url, MAX_DOCUMENT_BYTES, remainingMs) ?: return null
        val signature = fetch("$url.sig", MAX_SIGNATURE_BYTES, remainingMs) ?: return null
        return body.takeIf { authentic(body, signature) }
    }

    internal fun authentic(body: ByteArray, signature: ByteArray): Boolean = runCatching {
        if (body.size > MAX_DOCUMENT_BYTES || signature.size > MAX_SIGNATURE_BYTES) return false
        Signature.getInstance("SHA256withRSA").run {
            initVerify(key)
            update(body)
            verify(signature)
        }
    }.getOrDefault(false)

    private fun fetch(url: String, maxBytes: Long, remainingMs: () -> Long): ByteArray? {
        var next = URL(url)
        repeat(6) {
            if (next.protocol != "https" || remainingMs() <= 0L) return null
            val connection = next.openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            val timeout = minOf(8000L, remainingMs()).coerceAtLeast(1L).toInt()
            connection.connectTimeout = timeout
            connection.readTimeout = timeout
            try {
                val status = connection.responseCode
                if (status in 300..399) {
                    next = AppInstaller.httpsRedirect(next, connection.getHeaderField("Location") ?: return null)
                        ?: return null
                    connection.closeBody()
                } else if (status == 200) {
                    if (connection.contentLengthLong > maxBytes) return null
                    return connection.inputStream.use { input ->
                        val output = java.io.ByteArrayOutputStream()
                        AppInstaller.copyBeforeDeadline(input, output, maxBytes, remainingMs)
                        output.toByteArray()
                    }
                } else {
                    connection.closeBody()
                    return null
                }
            } finally { connection.disconnect() }
        }
        return null
    }
}
