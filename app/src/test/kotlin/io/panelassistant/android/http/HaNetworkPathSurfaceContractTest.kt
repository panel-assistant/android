package io.panelassistant.android.http

import io.panelassistant.android.sensors.HaNetworkPathPresentation
import io.panelassistant.android.sensors.HaNetworkPathSeverity
import io.panelassistant.android.testsupport.TestSources
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The network-path surfaces as shipped: the banner script driven by node, its copy shared with the
 * Kotlin presentation, and the OpenAPI description. The one-owner behaviour is `HaNetworkPathRuntimeTest`.
 */
class HaNetworkPathSurfaceContractTest {
    // Source-text reason: buildwatch.js user-visible banner copy must match the Kotlin presentation strings.
    private val buildwatch by lazy { TestSources.asset("buildwatch.js").readText() }

    @Test fun theScriptAndTheKotlinPresentationShareOneCopy() {
        assertTrue(buildwatch.contains("warning: \"${HaNetworkPathPresentation.BANNER_WARNING_PREFIX}\""))
        assertTrue(buildwatch.contains("severe: \"${HaNetworkPathPresentation.BANNER_SEVERE_PREFIX}\""))
        assertTrue(buildwatch.contains("\"${HaNetworkPathPresentation.BANNER_ADVICE}\""))
        assertTrue(
            buildwatch.contains(
                "\"${HaNetworkPathPresentation.BANNER_WARNING_SLOW_PREFIX.removePrefix("⚠ ")}. " +
                    "${HaNetworkPathPresentation.BANNER_SLOW_ADVICE}\"",
            ),
        )
        assertTrue(
            buildwatch.contains(
                "\"${HaNetworkPathPresentation.BANNER_SEVERE_SLOW_PREFIX.removePrefix("⚠ ")}. " +
                    "${HaNetworkPathPresentation.BANNER_SLOW_ADVICE}\"",
            ),
        )
        HaNetworkPathSeverity.entries.forEach {
            assertTrue("the row must word ${it.wireValue}", buildwatch.contains("${it.wireValue}: \""))
        }
    }

    /** The script's behaviour, driven through the real asset by node: hide, warn, escalate, retract. */
    @Test fun theBannerAndRowBehaveAsSpecifiedForEveryToken() {
        val working = java.io.File(requireNotNull(System.getProperty("user.dir")))
        val fixture = listOf(
            java.io.File(working, "app/src/test/js/ha-network-banner-test.mjs"),
            java.io.File(working, "src/test/js/ha-network-banner-test.mjs"),
        ).first(java.io.File::isFile)
        // Source-text reason: executes the shipped buildwatch.js in a node behaviour fixture.
        val asset = listOf(
            java.io.File(working, "app/src/main/assets/buildwatch.js"),
            java.io.File(working, "src/main/assets/buildwatch.js"),
        ).first(java.io.File::isFile)
        val process = ProcessBuilder("node", fixture.absolutePath, asset.absolutePath)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals(output, 0, process.waitFor())
        assertTrue(output, output.contains("ha network banner cases passed"))
    }

    @Test fun openApiHealthTokenAndStatusObjectContract() {
        // Source-text reason: openapi.json is the published API schema contract.
        val document = JSONObject(TestSources.asset("openapi.json").readText())
        val health = document.getJSONObject("paths").getJSONObject("/api/v1/health")
            .getJSONObject("get").getJSONObject("responses").getJSONObject("200").getString("description")
        listOf("ha_net=", "ha_resp=", "ha_net_p95=", "ha_net_n=", "ha_net_miss=", "ha_net_age=").forEach {
            assertTrue("OpenAPI must name the $it token", health.contains(it))
        }
        HaNetworkPathSeverity.entries.forEach { assertTrue(health.contains(it.wireValue)) }
        assertTrue(health.contains("absent"))
        val status = document.getJSONObject("paths").getJSONObject("/api/v1/status")
            .getJSONObject("get").getJSONObject("responses").getJSONObject("200")
        val schema = status.getJSONObject("content").getJSONObject("application/json").getJSONObject("schema")
        assertTrue(schema.getJSONObject("properties").has("ha_network"))
        assertTrue(schema.getJSONArray("required").toString().contains("\"ha_network\""))
        val component = document.getJSONObject("components").getJSONObject("schemas").getJSONObject("HaNetworkPath")
        listOf(
            "measuring", "state", "responsiveness", "settling", "socket", "p95_ms", "loss_percent",
            "consecutive_failures", "server_failures", "last_round_trip_age_ms",
        ).forEach {
            assertTrue("OpenAPI must document the $it property", component.getJSONObject("properties").has(it))
        }
        val states = component.getJSONObject("properties").getJSONObject("state").getJSONArray("enum").toString()
        listOf("idle", "settling", "healthy", "warning", "severe").forEach { assertTrue(states.contains("\"$it\"")) }
    }
}
