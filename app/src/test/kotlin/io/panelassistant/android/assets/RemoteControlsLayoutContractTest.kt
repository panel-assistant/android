package io.panelassistant.android.assets

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteControlsLayoutContractTest {
    // Source-text reason: the shipped OpenAPI document is the public API contract.
    private val openApi = JSONObject(File("src/main/assets/openapi.json").readText())

    @Test fun hiddenVolumeActionsRemainInTheApiContract() {
        val actions = openApi
            .getJSONObject("paths")
            .getJSONObject("/api/v1/action")
            .getJSONObject("post")
            .getJSONObject("requestBody")
            .getJSONObject("content")
            .getJSONObject("application/x-www-form-urlencoded")
            .getJSONObject("schema")
            .getJSONObject("properties")
            .getJSONObject("a")
            .getJSONArray("enum")
        val values = (0 until actions.length()).map { actions.getString(it) }

        assertTrue(values.contains("volup"))
        assertTrue(values.contains("voldn"))
    }
}
