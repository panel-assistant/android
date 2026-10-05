package io.panelassistant.android.http

import io.panelassistant.android.control.PrivilegedRouteObservation
import io.panelassistant.android.shizuku.ShizukuBridge
import io.panelassistant.android.shizuku.ShizukuState
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class InstallCapabilityStatusTest {
    @Test fun statusContractOffersAnInstallRouteOnlyWhenThePanelCanUseOne() {
        val routes = listOf(
            Triple(false, false, false) to "none",
            Triple(true, false, false) to "api",
            Triple(false, true, false) to "api",
            Triple(false, false, true) to "api",
        )
        routes.forEach { (route, expected) ->
            val (su, helper, shizuku) = route
            val privilege = PrivilegedRouteObservation(
                directSuReady = su,
                helperRootReady = helper,
                shizuku = ShizukuBridge.Snapshot(
                    if (shizuku) ShizukuState.READY else ShizukuState.STOPPED,
                    ready = shizuku,
                ),
            )
            assertEquals(expected, JSONObject("{${installCapabilityStatusJson(privilege)}}").getString("install_capability"))
        }
    }

    @Test fun statusSchemaDocumentsTheMachineReadableRoute() {
        // Source-text reason: the shipped OpenAPI document is the public status contract.
        val api = JSONObject(File("src/main/assets/openapi.json").readText())
        val schema = api.getJSONObject("paths").getJSONObject("/api/v1/status")
            .getJSONObject("get").getJSONObject("responses").getJSONObject("200")
            .getJSONObject("content").getJSONObject("application/json").getJSONObject("schema")
        val required = schema.getJSONArray("required")
        assertEquals(1, (0 until required.length()).count { required.getString(it) == "install_capability" })
        val field = schema.getJSONObject("properties").getJSONObject("install_capability")
        assertEquals("string", field.getString("type"))
        assertEquals(listOf("api", "none"), field.getJSONArray("enum").let { values ->
            (0 until values.length()).map(values::getString)
        })
    }
}
