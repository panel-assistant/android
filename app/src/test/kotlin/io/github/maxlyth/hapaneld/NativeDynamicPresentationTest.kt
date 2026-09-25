package io.github.maxlyth.hapaneld

import io.github.maxlyth.hapaneld.util.HaTransportFault
import io.github.maxlyth.hapaneld.util.InstallPresentation
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeDynamicPresentationTest {
    @Test fun `every transport fault has a distinct native explanation`() {
        assertEquals(10, HaTransportFault.entries.size)
        val resources = HaTransportFault.entries.map(::haTransportFaultResource)
        assertEquals(resources.size, resources.toSet().size)
    }

    @Test fun `every failed WebView repair code has the intended retry polarity`() {
        fun presentation(code: String): InstallPresentation = InstallPresentation(
            code,
            when (code) {
                "managed-no-recommendation" -> mapOf("component" to "webview")
                "managed-up-to-date" -> mapOf("component" to "webview", "current" to "Chromium 150")
                "managed-no-newer" -> mapOf("component" to "webview", "current" to "150.0")
                "install-no-permitted-route", "install-download-too-large", "install-download-failed",
                "install-retryable-failure", "install-insufficient-storage", "install-staging-failed",
                "install-deferred-saving-state", "install-guard-db-owned", "install-durable-rejection",
                -> mapOf("component" to "webview")
                else -> emptyMap()
            },
        )

        val expected = linkedMapOf(
            "managed-no-recommendation" to WebViewRepairFailureKind.NO_RECOMMENDATION,
            "managed-up-to-date" to WebViewRepairFailureKind.NO_CHANGE,
            "managed-no-newer" to WebViewRepairFailureKind.NO_CHANGE,
            "install-no-permitted-route" to WebViewRepairFailureKind.NO_INSTALL_ROUTE,
            "install-download-too-large" to WebViewRepairFailureKind.DOWNLOAD_TOO_LARGE,
            "install-download-failed" to WebViewRepairFailureKind.DOWNLOAD,
            "install-retryable-failure" to WebViewRepairFailureKind.RETRYABLE,
            "install-insufficient-storage" to WebViewRepairFailureKind.STORAGE,
            "install-staging-failed" to WebViewRepairFailureKind.STAGING,
            "install-deferred-saving-state" to WebViewRepairFailureKind.DEFERRED,
            "install-guard-db-owned" to WebViewRepairFailureKind.DEFERRED,
            "install-durable-rejection" to WebViewRepairFailureKind.REJECTED,
            "operation-cancelled" to WebViewRepairFailureKind.CANCELLED,
        )
        expected.forEach { (code, kind) ->
            assertEquals(
                code,
                kind,
                webViewRepairFailureKind(
                    WebViewRepairProgress(
                        running = false,
                        message = "legacy detail",
                        presentation = presentation(code),
                        generation = 7L,
                        component = "System WebView",
                    ),
                ),
            )
        }

        assertEquals(null, webViewRepairFailureKind(WebViewRepairProgress(false, "legacy detail")))
        assertEquals(
            null,
            webViewRepairFailureKind(
                WebViewRepairProgress(
                    running = false,
                    message = "installed",
                    presentation = InstallPresentation(
                        "managed-install-committed",
                        mapOf("component" to "webview", "version" to "150.0"),
                    ),
                    generation = 7L,
                    component = "System WebView",
                ),
            ),
        )
        assertNotEquals(
            WebViewRepairFailureKind.DOWNLOAD,
            webViewRepairFailureKind(
                WebViewRepairProgress(
                    running = false,
                    message = "rejected",
                    presentation = presentation("install-durable-rejection"),
                    generation = 7L,
                    component = "System WebView",
                ),
            ),
        )
        listOf("paneld", "companion").forEach { component ->
            assertEquals(
                "foreign $component metadata must fail closed",
                null,
                webViewRepairFailureKind(
                    WebViewRepairProgress(
                        running = false,
                        message = "foreign failure",
                        presentation = InstallPresentation(
                            "install-retryable-failure",
                            mapOf("component" to component),
                        ),
                        generation = 8L,
                        component = if (component == "paneld") "ha-paneld" else "HA Companion",
                    ),
                ),
            )
        }
    }

    // Source-text reason: the string catalogue is a user-visible translation contract, read as data.
    @Test fun `dynamic native resources catalogue contract is complete across every release locale`() {
        val directories = listOf("values", "values-de", "values-es", "values-fr", "values-it", "values-zh-rCN")
        val required = setOf(
            "approval_list_item", "approval_request_detail", "approval_exact_detail",
            "cannot_reach_ha_detail", "ha_transport_not_ready", "ha_transport_tls_trust",
            "ha_transport_tls_other", "ha_transport_dns", "ha_transport_timeout", "ha_transport_refused",
            "ha_transport_unreachable", "ha_transport_http_status", "ha_transport_protocol", "ha_transport_unknown",
            "web_view_repair_no_change", "web_view_repair_no_install_route", "web_view_repair_download_failed",
            "web_view_repair_download_too_large", "web_view_repair_retryable",
            "web_view_repair_storage_failed", "web_view_repair_staging_failed", "web_view_repair_deferred",
            "web_view_repair_rejected", "web_view_repair_cancelled", "web_view_repair_failure_detail",
        )
        directories.forEach { directory ->
            val values = strings(File("src/main/res/$directory/strings.xml"))
            assertEquals("missing dynamic resources in $directory", emptySet<String>(), required - values.keys)
            if (directory != "values") {
                required.forEach { key ->
                    assertFalse("$directory left $key blank", values.getValue(key).isBlank())
                }
            }
        }
        val base = stringElements(File("src/main/res/values/strings.xml"))
            .filter { it.attributes.getNamedItem("translatable")?.nodeValue != "false" }
            .map { it.attributes.getNamedItem("name").nodeValue }
            .toSet()
        directories.drop(1).forEach { directory ->
            assertEquals("release resource parity drifted in $directory", base, strings(File("src/main/res/$directory/strings.xml")).keys)
        }
    }

    private fun strings(file: File): Map<String, String> {
        return stringElements(file).associate { node ->
            node.attributes.getNamedItem("name").nodeValue to node.textContent
        }
    }

    private fun stringElements(file: File) =
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).getElementsByTagName("string")
            .let { nodes -> (0 until nodes.length).map(nodes::item) }
}
