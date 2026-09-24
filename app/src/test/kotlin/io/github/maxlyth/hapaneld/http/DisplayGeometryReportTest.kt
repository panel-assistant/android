package io.github.maxlyth.hapaneld.http

import io.github.maxlyth.hapaneld.device.profile.BundledProfileFixtures
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DisplayGeometryReportTest {
    private val nspanel get() = BundledProfileFixtures.bundledById.getValue("nspanel-pro").profile("NSPanel86P_1.1.7")
    private val tpa10 get() = BundledProfileFixtures.bundledById.getValue("tpa10").profile()

    private fun observation(widthPx: Int, heightPx: Int, currentDpi: Int, factoryBaseDpi: Int = 160) = DisplayObservation(
        physicalWidthPx = widthPx,
        physicalHeightPx = heightPx,
        viewportWidthPx = widthPx,
        viewportHeightPx = heightPx,
        factoryBaseDpi = factoryBaseDpi,
        currentDpi = currentDpi,
    )

    /** The same route composition the server uses: resolve by physical mode, then render. */
    private fun report(observation: DisplayObservation, profile: io.github.maxlyth.hapaneld.device.DeviceProfile): JSONObject =
        DisplayGeometryReport.json(
            observation,
            profile.displayGeometry(observation.physicalWidthPx, observation.physicalHeightPx),
            profile.recommendedDensity,
        )

    @Test fun changingRenderingDpiCannotMovePhysicalDiagonalOrPpi() {
        listOf(
            Triple(nspanel, 480 to 480, listOf(120, 160, 213, 250, 320)),
            Triple(tpa10, 1920 to 1200, listOf(160, 212, 226, 240, 320)),
        ).forEach { (profile, pixels, densities) ->
            val reports = densities.map { dpi -> report(observation(pixels.first, pixels.second, dpi, factoryBaseDpi = dpi + 40), profile) }
            val physical = reports.map { it.obj("physical_size").toString() }
            assertEquals("physical size moved with logical DPI on ${profile.id}", 1, physical.toSet().size)
            // The same report does respond to DPI where it should, so an inert builder cannot pass.
            val viewports = reports.map { it.obj("viewport_dp").getDouble("width") }
            assertEquals("dp viewport ignored logical DPI on ${profile.id}", densities.size, viewports.toSet().size)
            reports.zip(densities).forEach { (json, dpi) ->
                assertEquals(dpi, json.obj("logical_dpi").int("current"))
            }
        }
        val compact = report(observation(480, 480, 250), nspanel).obj("physical_size")
        assertEquals(3.95, compact.getDouble("diagonal_in"), 0.0)
        assertEquals(171.9, compact.getDouble("ppi"), 0.0)
        val hall = report(observation(1920, 1200, 212), tpa10).obj("physical_size")
        assertEquals(226.0, hall.getDouble("ppi"), 0.0)
        assertEquals(10.02, hall.getDouble("diagonal_in"), 0.0)
    }

    @Test fun reportKeepsEveryDensityDistinctAndMarksApproximateEvidence() {
        val json = report(observation(1920, 1200, 212, factoryBaseDpi = 240), tpa10)
        assertEquals(1920, json.obj("physical_pixels").int("width"))
        assertEquals(1200, json.obj("physical_pixels").int("height"))
        val size = json.obj("physical_size")
        assertEquals("approximate", size.getString("evidence"))
        assertTrue(size.getBoolean("approximate"))
        val logical = json.obj("logical_dpi")
        assertEquals(240, logical.int("factory_base"))
        assertEquals(212, logical.int("current"))
        assertEquals(212, logical.int("recommended"))
        assertFalse("legacy ppi declares no factory base", logical.has("profile_factory_base"))
        val viewport = json.obj("viewport_dp")
        assertEquals(1449.1, viewport.getDouble("width"), 0.0)
        assertEquals(905.7, viewport.getDouble("height"), 0.0)
        assertEquals(1.325, viewport.getDouble("density_scale"), 0.0)

        val specified = report(observation(480, 480, 160), nspanel)
        assertEquals("specification", specified.obj("physical_size").getString("evidence"))
        assertFalse(specified.obj("physical_size").getBoolean("approximate"))
        assertEquals("86P", specified.obj("physical_size").getString("variant"))
        assertEquals(160, specified.obj("logical_dpi").int("profile_factory_base"))
    }

    @Test fun unknownGeometryIsAbsentAndNeverInferredFromDensity() {
        val generic = BundledProfileFixtures.bundledById.getValue("generic").profile()
        listOf(120, 160, 240, 480).forEach { dpi ->
            val json = report(observation(1280, 800, dpi, factoryBaseDpi = dpi), generic)
            assertFalse(json.has("physical_size"))
            assertTrue(json.has("viewport_dp"))
        }
        val unmatched = report(observation(720, 720, 160), nspanel)
        assertFalse("an unmatched NSPanel resolution must not borrow a variant", unmatched.has("physical_size"))
    }

    @Test fun reportMatchesTheOpenApiSchema() {
        val root = listOf(File("src/main/assets/openapi.json"), File("app/src/main/assets/openapi.json")).first { it.isFile }
        val schema = JSONObject(root.readText()).getJSONObject("components").getJSONObject("schemas").getJSONObject("DisplayGeometry")
        val path = JSONObject(root.readText()).getJSONObject("paths").getJSONObject("/api/v1/display").getJSONObject("get")
        assertEquals(
            "#/components/schemas/DisplayGeometry",
            path.getJSONObject("responses").getJSONObject("200").getJSONObject("content")
                .getJSONObject("application/json").getJSONObject("schema").getString("\$ref"),
        )
        val json = report(observation(480, 480, 160), nspanel)
        assertConforms("DisplayGeometry", schema, json)
        val required = schema.getJSONArray("required")
        (0 until required.length()).forEach { assertTrue(json.has(required.getString(it))) }
    }

    /** Asserts before reading, so a missing field fails as an assertion rather than a JSONException. */
    private fun JSONObject.obj(key: String): JSONObject {
        assertTrue("report is missing $key: $this", has(key))
        return getJSONObject(key)
    }

    private fun JSONObject.int(key: String): Int {
        assertTrue("report is missing $key: $this", has(key))
        return getInt(key)
    }

    private fun assertConforms(path: String, schema: JSONObject, value: JSONObject) {
        val properties = schema.getJSONObject("properties")
        value.keys().forEach { key ->
            assertTrue("$path.$key is not documented", properties.has(key))
            val property = properties.getJSONObject(key)
            val item = value.get(key)
            when (property.optString("type")) {
                "object" -> assertConforms("$path.$key", property, item as JSONObject)
                "integer" -> assertTrue("$path.$key is not an integer", item is Int)
                "number" -> assertTrue("$path.$key is not a number", item is Number)
                "boolean" -> assertTrue("$path.$key is not a boolean", item is Boolean)
                "string" -> {
                    assertTrue("$path.$key is not a string", item is String)
                    property.optJSONArray("enum")?.let { values ->
                        assertTrue("$path.$key=$item is not an allowed value", (0 until values.length()).any { values.getString(it) == item })
                    }
                }
            }
        }
        schema.optJSONArray("required")?.let { required ->
            (0 until required.length()).forEach { assertTrue("$path.${required.getString(it)} is missing", value.has(required.getString(it))) }
        }
        assertNotEquals(0, value.length())
    }
}
