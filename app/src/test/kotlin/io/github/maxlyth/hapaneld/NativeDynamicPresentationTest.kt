package io.github.maxlyth.hapaneld

import io.github.maxlyth.hapaneld.util.HaTransportFault
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class NativeDynamicPresentationTest {
    @Test fun `every transport fault has a distinct native explanation`() {
        assertEquals(10, HaTransportFault.entries.size)
        val resources = HaTransportFault.entries.map(::haTransportFaultResource)
        assertEquals(resources.size, resources.toSet().size)
    }

    // Source-text reason: the string catalogue is a user-visible translation contract, read as data.
    @Test fun `dynamic native resources catalogue contract is complete across every release locale`() {
        val directories = listOf("values", "values-de", "values-es", "values-fr", "values-it", "values-zh-rCN")
        val required = setOf(
            "approval_list_item", "approval_request_detail", "approval_exact_detail",
            "cannot_reach_ha_detail", "ha_transport_not_ready", "ha_transport_tls_trust",
            "ha_transport_tls_other", "ha_transport_dns", "ha_transport_timeout", "ha_transport_refused",
            "ha_transport_unreachable", "ha_transport_http_status", "ha_transport_protocol", "ha_transport_unknown",
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
