package io.github.maxlyth.hapaneld.util

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Recent GitHub releases for a repo, for the Install-tab version picker. Fetches up to N releases,
 * filters by channel (stable = non-prerelease only; prerelease = all releases, newest first), and
 * exposes each as a pickable version with its release-notes URL + whether it carries an installable APK
 * asset. Also resolves the APK asset URL for an exact tag (install-a-specific-version). Network — call
 * OFF the main / MQTT thread.
 */
object ReleaseCatalog {
    private const val TAG = "ha-paneld/releases"
    internal const val MAX_API_RESPONSE_BYTES = 2L * 1024L * 1024L

    /** A raw release as read from the API, before channel filtering. */
    data class Raw(val tag: String, val prerelease: Boolean, val notesUrl: String, val apkUrl: String?, val olderAppId: Boolean = false)

    /** A pickable version for the UI: [version] is the display label, [tag] the API/install key. */
    data class Version(
        val version: String,
        val tag: String,
        val notesUrl: String,
        val installable: Boolean,
        // Direct APK asset URL (null when the release ships none) — surfaced so a NO-ROOT panel's
        // Install tab can offer the download for a manual `adb install -r` from the admin machine.
        val apkUrl: String? = null,
        val unavailableReason: String? = if (installable) null else "no_matching_asset",
        val maxVersion: String? = null,
    )

    /** One installable release, retaining the source tag alongside its normalised display version.
     *
     * The tag is deliberately retained rather than reconstructed from [version]. A release resolver is
     * free to use a tag that is not simply `v<version>`; consumers that need to initiate an exact
     * installation must therefore keep the value GitHub supplied.
     */
    data class ApkTarget(
        val version: String,
        val tag: String,
        val apkUrl: String,
        val prerelease: Boolean,
    )

    /** A tag is only ever interpolated into a GitHub API path — restrict it to release-tag characters so
     *  a crafted value can't escape the path. */
    private val TAG_RE = Regex("^[A-Za-z0-9._-]{1,64}$")
    fun validTag(tag: String): Boolean = TAG_RE.matches(tag)

    /**
     * Pure selection: keep the first [limit] releases newest-first, dropping prereleases unless [channel]
     * is "prerelease"; map each tag to a display version via [normalize]. Unit-tested in ReleaseCatalogTest.
     */
    internal fun select(raw: List<Raw>, channel: String, limit: Int, normalize: (String) -> String): List<Version> =
        raw.asSequence()
            .filter { channel == "prerelease" || !it.prerelease }
            .take(limit)
            .map { Version(normalize(it.tag), it.tag, it.notesUrl, it.apkUrl != null, it.apkUrl,
                if (it.apkUrl != null) null else if (it.olderAppId) "older_app_id" else "no_matching_asset") }
            .toList()

    /** The newest release on [channel], preserving the release object as the authority boundary. Keeping
     *  the tag and APK URL in one [Raw] prevents a list parser from pairing the newest tag with an asset
     *  found later in a different release. */
    internal fun newest(raw: List<Raw>, channel: String): Raw? =
        raw.firstOrNull { channel == "prerelease" || !it.prerelease }

    /** Fetch + parse the releases list for [repo]; [apkMatch] picks the installable asset by name. Returns
     *  an empty list on any network/parse error (the picker degrades to "no versions").
     *
     *  Stable filtering discards the prereleases interleaved in the release list — some repos
     *  (home-assistant/android) publish many betas between stable tags, so the last [limit] releases may
     *  hold only a couple of stable ones. Fetch a larger page for the stable channel so [limit] stable
     *  versions still surface; the prerelease channel keeps everything, so one page of [limit] is enough. */
    fun list(
        repo: String,
        channel: String,
        limit: Int,
        apkMatch: (String) -> Boolean,
        olderAppMatch: ((String) -> Boolean)? = null,
        maxVersion: String? = null,
        normalize: (String) -> String,
    ): List<Version> = runCatching {
        listPages(channel, limit, maxVersion, normalize) { page, perPage ->
            fetch(repo, perPage, apkMatch, page, olderAppMatch)
        }
    }.getOrElse { Log.w(TAG, "list $repo failed", it); emptyList() }

    /** Capped pickers keep one recent blocked release and apply the limit to compliant releases.
     * Paging is bounded; the Companion caller retains its exact known-good cap fallback. */
    internal fun listPages(
        channel: String,
        limit: Int,
        maxVersion: String?,
        normalize: (String) -> String,
        fetchPage: (Int, Int) -> List<Raw>,
    ): List<Version> {
        if (limit <= 0) return emptyList()
        if (maxVersion == null) return select(fetchPage(1, pageSize(channel, limit)), channel, limit, normalize)
        val compliant = mutableListOf<Version>()
        var blocked: Version? = null
        for (page in 1..4) {
            val raw = fetchPage(page, MAX_STABLE_PAGE)
            for (version in select(raw, channel, raw.size, normalize)) {
                if (CompanionInstaller.withinCap(version.version, maxVersion) && version.installable) {
                    if (compliant.size < limit) compliant += version
                } else if (blocked == null) {
                    blocked = if (CompanionInstaller.withinCap(version.version, maxVersion)) version else
                        version.copy(installable = false, apkUrl = null,
                            unavailableReason = "above_panel_limit", maxVersion = maxVersion)
                }
            }
            if (compliant.size >= limit || raw.size < MAX_STABLE_PAGE) break
        }
        return listOfNotNull(blocked) + compliant
    }

    /**
     * Releases requested per page. The stable channel over-fetches to find enough stable releases, but
     * never past [MAX_STABLE_PAGE]: measured 2026-09-11, 100 home-assistant/android releases were
     * 2.5 MB and 80 were 2.08 MB against the [MAX_API_RESPONSE_BYTES] bound, so a larger page refused
     * every stable Companion lookup. 50 were 1.4 MB and still held six stable releases.
     */
    internal fun pageSize(channel: String, limit: Int): Int =
        if (channel == "prerelease") limit else minOf(MAX_STABLE_PAGE, limit * 8)

    internal const val MAX_STABLE_PAGE = 50

    /** Resolve the installable APK asset URL for an exact [tag] in [repo], or null (unknown tag / no asset
     *  / invalid tag). */
    fun apkUrl(repo: String, tag: String, apkMatch: (String) -> Boolean): String? {
        if (!validTag(tag)) return null
        return runCatching {
            val json = get("https://api.github.com/repos/$repo/releases/tags/$tag") ?: return null
            assetUrl(JSONObject(json), apkMatch)
        }.getOrElse { Log.w(TAG, "apkUrl $repo $tag failed", it); null }
    }

    /** Resolve the newest installable release on [channel] as one coherent tag/asset pair. A newest
     *  release without the expected APK returns null; it is never silently combined with or replaced by
     *  an older release. */
    fun newestApk(
        repo: String,
        channel: String,
        apkMatch: (String) -> Boolean,
        normalize: (String) -> String,
    ): Pair<String, String>? = newestApkTarget(repo, channel, apkMatch, normalize)
        ?.let { it.version to it.apkUrl }

    /** The newest release on [channel] with an APK, retaining its exact source tag. */
    fun newestApkTarget(
        repo: String,
        channel: String,
        apkMatch: (String) -> Boolean,
        normalize: (String) -> String,
    ): ApkTarget? = runCatching {
        val release = if (channel == "prerelease") {
            newest(fetch(repo, 10, apkMatch), channel)
        } else {
            val json = get("https://api.github.com/repos/$repo/releases/latest") ?: return null
            raw(JSONObject(json), apkMatch)
        } ?: return null
        apkTarget(release, normalize)
    }.getOrElse { Log.w(TAG, "newestApk $repo failed", it); null }

    /** Pure tag-preserving shape used by [newestApkTarget]. */
    internal fun apkTarget(release: Raw?, normalize: (String) -> String): ApkTarget? =
        release?.apkUrl?.let { apk ->
            ApkTarget(normalize(release.tag), release.tag, apk, release.prerelease)
        }

    private fun fetch(repo: String, limit: Int, apkMatch: (String) -> Boolean,
        page: Int = 1, olderAppMatch: ((String) -> Boolean)? = null): List<Raw> {
        val json = get("https://api.github.com/repos/$repo/releases?per_page=$limit&page=$page") ?: return emptyList()
        val arr = JSONArray(json)
        return (0 until arr.length()).map { i -> raw(arr.getJSONObject(i), apkMatch, olderAppMatch) }
    }

    internal fun raw(release: JSONObject, apkMatch: (String) -> Boolean, olderAppMatch: ((String) -> Boolean)? = null): Raw = Raw(
        release.getString("tag_name"),
        release.optBoolean("prerelease", false),
        release.optString("html_url", ""),
        assetUrl(release, apkMatch),
        olderAppMatch?.let { assetUrl(release, it) != null } ?: false,
    )

    private fun assetUrl(release: JSONObject, apkMatch: (String) -> Boolean): String? {
        val assets = release.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (apkMatch(a.optString("name"))) return a.optString("browser_download_url").ifEmpty { null }
        }
        return null
    }

    private fun get(url: String): String? {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 8_000; conn.readTimeout = 8_000
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        return try {
            if (conn.responseCode == 200) {
                conn.inputStream.use { readResponse(it, conn.contentLengthLong) }
            } else null
        } finally {
            conn.disconnect()
        }
    }

    /** Read a GitHub API response under a semantic cap even when Content-Length is absent or false. */
    internal fun readResponse(
        input: InputStream,
        declaredBytes: Long = -1L,
        maxBytes: Long = MAX_API_RESPONSE_BYTES,
    ): String {
        if (declaredBytes > maxBytes) throw ByteLimitExceeded(maxBytes)
        return String(BoundedStreams.readBytes(input, maxBytes), Charsets.UTF_8)
    }
}
