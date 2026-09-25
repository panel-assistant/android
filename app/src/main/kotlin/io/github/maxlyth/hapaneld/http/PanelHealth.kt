package io.github.maxlyth.hapaneld.http

/**
 * Pure decisions for the info-page health banner (unit-tested in PanelHealthTest). These flag the
 * states that stop a panel rendering the dashboard as the user expects but that the info map otherwise
 * reports neutrally. The "no dashboard renderer" check needs the package manager, so it lives in
 * [PanelInfo.dashboardRenderers]; the WebView-age check is pure string parsing and lives here so it's
 * testable without an Android context.
 */
object PanelHealth {
    /** Chromium major below which the current Home Assistant frontend renders blank/broken. 107 was
     *  verified blank on an NSPanel Pro; the fleet target is 138. Matches the `check_webview` cutoff in
     *  scripts/provision.sh so the in-app warning and the provisioning warning agree. */
    const val MIN_CHROMIUM = 110

    /** Chromium major parsed from a "System WebView" value like "com.android.webview 107.0.5304.105"
     *  → 107. Null when the value has no dotted four-part version (e.g. "unknown"). */
    fun chromiumMajor(webView: String): Int? =
        Regex("""(\d+)\.\d+\.\d+\.\d+""").find(webView)?.groupValues?.get(1)?.toIntOrNull()

    /** Full Chromium version from a WebView User-Agent's `Chrome/<v>` token (e.g. "147.0.7727.56"),
     *  or null. This is the TRUE engine version: a Cromite/LineageOS SystemWebView stamps the OEM
     *  stock package versionName/Code to clear the provider's signature + min-version gate (the only
     *  way to install a modern WebView on a signature-locked ROM like the TPA10), but reports its real
     *  version in the UA — so the UA, not the package version, is authoritative for the age check. */
    fun engineVersionFromUa(ua: String): String? =
        Regex("""Chrome/(\d+\.\d+\.\d+\.\d+)""").find(ua)?.groupValues?.get(1)

    /** True when the WebView is below [MIN_CHROMIUM]. [engineMajor] — the real version read from the
     *  WebView UA — is authoritative when known; otherwise fall back to the package version. Both
     *  unknown → false: don't cry wolf (a Cromite swap reports the stale OEM version yet renders fine,
     *  and an unparseable value tells us nothing). */
    fun webViewTooOld(packageVersion: String, engineMajor: Int?): Boolean {
        engineMajor?.let { return it < MIN_CHROMIUM }
        return chromiumMajor(packageVersion)?.let { it < MIN_CHROMIUM } ?: false
    }
}
