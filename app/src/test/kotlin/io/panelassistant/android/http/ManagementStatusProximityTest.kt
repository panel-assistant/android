package io.panelassistant.android.http

import io.panelassistant.android.PanelStatus
import io.panelassistant.android.RendererAdmissionPresentation
import io.panelassistant.android.RendererMode
import io.panelassistant.android.camera.CameraPresentation
import io.panelassistant.android.config.Capabilities
import io.panelassistant.android.control.*
import io.panelassistant.android.storage.StorageHealthSnapshot
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** The status endpoint's `proximity` component: present with a proximity source, omitted without one. */
class ManagementStatusProximityTest {
    private fun status(fixture: PaneldServerHttpFixture, proximity: () -> String?): JSONObject {
        val snapshot = ManagementSnapshot(
            emptyMap(), emptyMap(), Capabilities(), emptyList(),
            PrivilegedRouteObservation(false, false),
            null, null, 1f, false,
        )
        val power = PowerSafetyAdvisory(
            PowerSafetyAssessment(PowerRiskLevel.SAFE,
                PowerSafetyObservation(true, true, false, false, true, 60_000, true, 1, 1, false, true, "none"),
                emptyList(), "safe", "none"),
            PowerRepairCapability.APP_ONLY, PowerSafetyAdvisoryAction.NONE, null, false,
        )
        return JSONObject(
            managementStatusJson(
                fixture.config, snapshot, CompanionDb.ServerObservation.EMPTY, power,
                null, HealthAudit.storage(StorageHealthSnapshot.UNCHECKED),
                StatusHealth(emptyList(), emptyList(), { PanelStatus.DashboardRecoveryState.NONE }, { null to null }, { null }),
                renderer = { RendererAdmissionPresentation.of(RendererMode.NONE, "", "auto", null, 0, 0, null, 0) },
                camera = { CameraPresentation.absent() },
                databaseObservationNonce = null,
                permissions = { emptyMap() },
                proximity = proximity,
                english = englishCatalogue,
                profileId = { "generic" },
            ),
        )
    }

    @Test fun proximityIsATopLevelComponentOnlyWhereThePanelHasASource() {
        PaneldServerHttpFixture().use { fixture ->
            fixture.useManagementStatus {}
            val present = status(fixture) { """{"state":"calibration_required","wake_ready":false}""" }
            val proximity = present.getJSONObject("proximity")
            assertEquals("calibration_required", proximity.getString("state"))
            assertFalse(proximity.getBoolean("wake_ready"))
            // The neighbouring components are untouched.
            assertFalse("no prior direct-root loss", present.getBoolean("root_lost_since_boot"))
            assertTrue("an unknown screen-lock source stays unknown", present.isNull("screen_lock_secure"))
            assertTrue(present.has("permissions"))
            assertTrue(present.has("power_safety"))

            assertFalse(status(fixture) { null }.has("proximity"))
            // A failing read omits the component rather than failing the whole status.
            assertFalse(status(fixture) { error("sensor gone") }.has("proximity"))
        }
    }
}
