package io.github.maxlyth.hapaneld.http

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteControlsLayoutContractTest {
    // Source-text reason: the shipped OpenAPI document is the public API contract.
    private val openApi = File("src/main/assets/openapi.json").readText()

    @Test fun hiddenVolumeActionsRemainInTheApiContract() {
        val apiAction = openApi.substringAfter("\"/api/v1/action\"").substringBefore("\"/api/v1/input\"")

        assertTrue(apiAction.contains("volup"))
        assertTrue(apiAction.contains("voldn"))
    }
}
