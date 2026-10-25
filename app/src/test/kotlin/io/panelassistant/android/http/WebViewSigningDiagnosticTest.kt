package io.panelassistant.android.http

import org.junit.Assert.assertEquals
import org.junit.Test

class WebViewSigningDiagnosticTest {
    @Test fun fingerprintsTheInstalledActiveProviderRatherThanInferringItsSignerFromItsName() {
        var queriedPackage: String? = null
        val line = PanelInfo.webViewSigningLine("com.google.android.webview") { packageName ->
            queriedPackage = packageName
            listOf("abc".toByteArray())
        }

        assertEquals("com.google.android.webview", queriedPackage)
        assertEquals(
            "[webview-signing] provider=com.google.android.webview cert_sha256=" +
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            line,
        )
    }

    @Test fun includesEveryCurrentSignerInStableOrder() {
        val line = PanelInfo.webViewSigningLine("com.android.webview") {
            listOf("abc".toByteArray(), "hello".toByteArray())
        }

        assertEquals(
            "[webview-signing] provider=com.android.webview cert_sha256=" +
                "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824," +
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            line,
        )
    }

    @Test fun missingProviderDoesNotQueryOrInventASigner() {
        assertEquals(
            "[webview-signing] provider=unknown cert_sha256=unavailable",
            PanelInfo.webViewSigningLine(null) { error("Must not query an unknown provider") },
        )
    }

    @Test fun inaccessibleOrEmptySigningDataIsExplicitlyUnavailable() {
        val expected = "[webview-signing] provider=com.google.android.webview cert_sha256=unavailable"
        assertEquals(expected, PanelInfo.webViewSigningLine("com.google.android.webview") { error("private error") })
        assertEquals(expected, PanelInfo.webViewSigningLine("com.google.android.webview") { emptyList() })
        assertEquals(expected, PanelInfo.webViewSigningLine("com.google.android.webview") { listOf(byteArrayOf()) })
    }
}
