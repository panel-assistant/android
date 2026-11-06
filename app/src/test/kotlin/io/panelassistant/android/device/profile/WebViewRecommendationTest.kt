package io.panelassistant.android.device.profile

import io.panelassistant.android.device.WebViewSpec
import org.junit.Assert.assertEquals
import org.junit.Test

class WebViewRecommendationTest {
    @Test fun `bundled profiles select the qualified WebView for each platform class`() {
        val expected = mapOf(
            "nspanel-pro" to WebViewSpec(
                url = "https://panel-assistant.io/downloads/webview/3fe65c631ce9b0f8e573198a783cf8ad1013aef02b83f04e7871952879b599cf.apk",
                version = "138.0.7204.63",
                certSha256 = "518325ef7f96c0d1194c2e856b040d636166ffb846717d72fa87f4fae5be7bbb",
                apkSha256 = "3fe65c631ce9b0f8e573198a783cf8ad1013aef02b83f04e7871952879b599cf",
                versionCode = 720406301L,
                size = 244902814L,
                abis = setOf("arm64-v8a", "armeabi-v7a"),
                minSdk = 26,
                maxSdk = 28,
            ),
            "tpa10" to WebViewSpec(
                url = "https://panel-assistant.io/downloads/webview/0dc261c3f37e9ac16a4f593ef316a097fc59815bb2082a8e38c519647bbcb3cc.apk",
                version = "156.0.8078.47",
                certSha256 = "32a2fc74d731105859e5a85df16d95f102d85b22099b8064c5d8915c61dad1e0",
                apkSha256 = "0dc261c3f37e9ac16a4f593ef316a097fc59815bb2082a8e38c519647bbcb3cc",
                versionCode = 807804700L,
                size = 98126751L,
                abis = setOf("armeabi-v7a"),
                minSdk = 29,
            ),
            "s9e" to WebViewSpec(
                url = "https://panel-assistant.io/downloads/webview/1319b1e76b4e1cb32d7019b6f7566ebb048e3c09bd0f344124122e58390b5939.apk",
                version = "150.0.7871.63",
                certSha256 = "32a2fc74d731105859e5a85df16d95f102d85b22099b8064c5d8915c61dad1e0",
                apkSha256 = "1319b1e76b4e1cb32d7019b6f7566ebb048e3c09bd0f344124122e58390b5939",
                versionCode = 787106301L,
                size = 262187199L,
                abis = setOf("arm64-v8a", "armeabi-v7a"),
                minSdk = 29,
            ),
        )

        // Exercise packaged YAML selection and validation, including its compiled artifact lookup.
        assertEquals(expected, expected.keys.associateWith { BundledProfileFixtures.profile(it).recommendedWebView })
    }
}
