package io.github.maxlyth.hapaneld.device.profile

import io.github.maxlyth.hapaneld.device.WebViewSpec

/** Audited artifacts compiled into the core; declarative profiles can select but cannot redefine them. */
object ProfileArtifacts {
    val webViews: Map<String, WebViewSpec> = mapOf(
        "lineageos-138-arm" to WebViewSpec(
            url = "https://github.com/panel-assistant/android/releases/download/webview-mirror/lineageos-webview-138.0.7204.63.apk",
            version = "138.0.7204.63",
            certSha256 = "518325ef7f96c0d1194c2e856b040d636166ffb846717d72fa87f4fae5be7bbb",
            apkSha256 = "3fe65c631ce9b0f8e573198a783cf8ad1013aef02b83f04e7871952879b599cf",
        ),
        "lineageos-150-arm" to WebViewSpec(
            url = "https://github.com/panel-assistant/android/releases/download/webview-mirror/lineageos-webview-150.0.7871.63-arm.apk",
            version = "150.0.7871.63",
            certSha256 = "32a2fc74d731105859e5a85df16d95f102d85b22099b8064c5d8915c61dad1e0",
            apkSha256 = "7b35eee0823ddf68cf758d903aee724de4ab1326f458e283612caa682d496f7b",
        ),
        "lineageos-150-arm64" to WebViewSpec(
            url = "https://github.com/panel-assistant/android/releases/download/webview-mirror/lineageos-webview-150.0.7871.63-arm64.apk",
            version = "150.0.7871.63",
            certSha256 = "32a2fc74d731105859e5a85df16d95f102d85b22099b8064c5d8915c61dad1e0",
            apkSha256 = "1319b1e76b4e1cb32d7019b6f7566ebb048e3c09bd0f344124122e58390b5939",
        ),
    )
}
