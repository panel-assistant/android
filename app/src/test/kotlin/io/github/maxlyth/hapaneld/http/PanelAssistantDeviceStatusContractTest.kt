package io.github.maxlyth.hapaneld.http

import io.github.maxlyth.hapaneld.testsupport.TestSources
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Binds the device projection to its sole status surface and its public grammar. */
class PanelAssistantDeviceStatusContractTest {
    private val server by lazy { TestSources.kotlin("http/PaneldServer.kt").readText() }
    private val projection by lazy { TestSources.kotlin("util/PanelAssistantDevice.kt").readText() }
    private val openApi by lazy { JSONObject(File("src/main/assets/openapi.json").readText()) }

    @Test fun statusProjectsDeviceFactsItAlreadyHoldsWithoutReachingForNewState() {
        val status = server.substring(
            server.indexOf("private fun statusJson():"),
            server.indexOf("/** A health finding"),
        )
        assertTrue(status.contains("\\\"panel_assistant_device\\\":\${"))
        assertTrue(status.contains("PanelAssistantDevice.json("))
        for (source in listOf(
            "config.friendlyName,",
            "config.manufacturer,",
            "config.model,",
            "config.haArea,",
        )) {
            assertTrue(source, status.contains(source))
        }
        // The Android release and build string no longer reaches the card as its Hardware line.
        val call = status.substring(status.indexOf("PanelAssistantDevice.json("))
            .substringBefore("},")
        assertFalse(call, call.contains("Build."))
    }

    /** The build number rides the health line on every route that answers it, after `pkg`. */
    @Test fun healthCarriesTheVersionCodeBesideThePackage() {
        assertEquals(" vc=909", versionCodeHealthToken(909))
        assertEquals(
            2,
            Regex(
                Regex.escape("\${packageHealthToken(appContext.packageName)}" +
                    "\${versionCodeHealthToken(BuildConfig.VERSION_CODE)}"),
            ).findAll(server).count(),
        )
    }

    /** Prose explains what the code must not do, so only the code may be searched for it. */
    private val projectionCode by lazy {
        projection
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
            .lines()
            .joinToString("\n") { it.substringBefore("//") }
    }

    @Test fun theProjectionReadsAndNeverWritesOrResolves() {
        for (forbidden in listOf("save", "publish", "commit", "check(", "http", "Socket")) {
            assertFalse(forbidden, projectionCode.contains(forbidden))
        }
    }

    @Test fun theProjectionRefusesToCarryAHardwareIdentifier() {
        for (forbidden in listOf("androidId", "serial", "macAddress", "SERIAL")) {
            assertFalse(forbidden, projectionCode.contains(forbidden))
        }
    }

    @Test fun openApiKeepsTheProjectionAdditiveAndStrictlyBounded() {
        val statusSchema = openApi.getJSONObject("paths").getJSONObject("/api/v1/status")
            .getJSONObject("get").getJSONObject("responses").getJSONObject("200")
            .getJSONObject("content").getJSONObject("application/json").getJSONObject("schema")
        val required = statusSchema.getJSONArray("required")
        for (index in 0 until required.length()) {
            assertFalse(required.getString(index) == "panel_assistant_device")
        }
        assertTrue(
            statusSchema.getJSONObject("properties").has("panel_assistant_device"),
        )

        val device = openApi.getJSONObject("components").getJSONObject("schemas")
            .getJSONObject("PanelAssistantDevice")
        assertEquals("object", device.getString("type"))
        assertFalse(device.getBoolean("additionalProperties"))
        assertFalse(device.has("required"))
        val properties = device.getJSONObject("properties")
        assertFalse(properties.has("hw_version"))
        for (name in listOf("name", "manufacturer", "model", "area")) {
            val field = properties.getJSONObject(name)
            assertEquals(name, "string", field.getString("type"))
            assertEquals(name, 1, field.getInt("minLength"))
            assertEquals(name, 128, field.getInt("maxLength"))
        }
        assertEquals(4, properties.length())
    }
}
