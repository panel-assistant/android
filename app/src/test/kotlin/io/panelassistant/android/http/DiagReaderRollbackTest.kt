package io.panelassistant.android.http

import android.content.ContextWrapper
import io.panelassistant.android.util.WebViewInstaller
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DiagReaderRollbackTest {
    @get:Rule val files = TemporaryFolder()

    @Test fun savedWebViewRollbackReasonIsFormattedForDiagnosticDump() {
        val context = object : ContextWrapper(null) {
            override fun getFilesDir() = files.root
            override fun getPackageName() = "io.github.maxlyth.hapaneld"
        }
        val reason = "built-in dashboard did not connect within 90s after WebView 150.0.0.0; previous provider restored"
        WebViewInstaller.recordRollbackDiagnostic(context, reason)

        assertEquals(
            "[webview-rollback] reason=$reason",
            DiagReader.webViewRollbackLine(context),
        )

        WebViewInstaller.recordRollbackDiagnostic(context, "error at https://internal.example/path\nsecret")
        assertEquals("[webview-rollback] reason=details omitted", DiagReader.webViewRollbackLine(context))
    }
}
