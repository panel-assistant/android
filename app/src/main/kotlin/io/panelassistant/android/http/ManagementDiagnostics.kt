package io.panelassistant.android.http

import android.content.Context
import io.panelassistant.android.RendererAdmissionPresentation
import io.panelassistant.android.camera.CameraPresentation
import io.panelassistant.android.control.PowerSafetyAssessment
import io.panelassistant.android.control.ZigbeeHealthSnapshot
import io.panelassistant.android.device.DeviceProfile
import io.panelassistant.android.storage.StorageHealthSnapshot
import io.panelassistant.android.sensors.HaNetworkPathRuntime
import io.panelassistant.android.sensors.PathProbeRuntime

/** Format diagnostics from the same snapshot that supplied the dashboard facts. */
internal fun managementDiagnosticReport(
    appContext: Context,
    profile: DeviceProfile,
    management: ManagementSnapshot,
    radio: ZigbeeHealthSnapshot?,
    storage: StorageHealthSnapshot,
    powerSafety: PowerSafetyAssessment,
    renderer: RendererAdmissionPresentation,
    camera: CameraPresentation,
    termux: () -> TermuxBridgeProbe.State,
): String =
    DiagReader.dump(
        appContext,
        profile,
        management.facts,
        radio,
        privilege = management.privilege,
        capabilityRows = management.capabilityRows,
        displaySizing = DiagReader.DisplaySizingEvidence(
            management.densityBase,
            management.densityCur,
            management.fontScale,
        ),
        storage = storage,
        powerSafety = powerSafety,
        renderer = renderer,
        camera = camera,
        wifiStabilityChronic = management.wifiChronic,
        haNetwork = HaNetworkPathRuntime.diagnosticLine(),
        haPathProbe = PathProbeRuntime.diagnosticLine(),
        termuxBridge = termux(),
    )
