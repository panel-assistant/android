package io.github.maxlyth.hapaneld.http

import android.content.Context
import io.github.maxlyth.hapaneld.RendererAdmissionPresentation
import io.github.maxlyth.hapaneld.camera.CameraPresentation
import io.github.maxlyth.hapaneld.control.PowerSafetyAssessment
import io.github.maxlyth.hapaneld.control.ZigbeeHealthSnapshot
import io.github.maxlyth.hapaneld.device.DeviceProfile
import io.github.maxlyth.hapaneld.storage.StorageHealthSnapshot
import io.github.maxlyth.hapaneld.sensors.HaNetworkPathRuntime
import io.github.maxlyth.hapaneld.sensors.PathProbeRuntime

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
