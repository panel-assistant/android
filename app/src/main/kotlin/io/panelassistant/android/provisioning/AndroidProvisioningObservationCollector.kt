package io.panelassistant.android.provisioning

import android.content.Context
import io.panelassistant.android.http.PanelInfo
import io.panelassistant.android.shizuku.ShizukuBridge
import io.panelassistant.android.shizuku.ShizukuState
import io.panelassistant.android.util.HelperClient
import io.panelassistant.android.util.HelperIdentityStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * Independent local probes for the stage-one plan. There is deliberately no release lookup and no
 * mutation. Probe failures are contained to their own item.
 */
internal class AndroidProvisioningObservationCollector(
    private val helperIdentity: () -> HelperIdentityStatus,
    private val shizukuState: () -> ShizukuState,
    private val webViewEngineMajor: () -> Int?,
) : ProvisioningObservationCollector {
    constructor(context: Context) : this(
        helperIdentity = HelperClient::identityStatus,
        shizukuState = { ShizukuBridge.state },
        webViewEngineMajor = { PanelInfo.webViewStatus(context.applicationContext).engineMajor },
    )

    override suspend fun collect(): ProvisioningObservationSnapshot = coroutineScope {
        val helper = async(Dispatchers.IO) { observeHelper() }
        val shizuku = async(Dispatchers.IO) { observeShizuku() }
        val webView = async(Dispatchers.IO) { observeWebView() }
        ProvisioningObservationSnapshot(
            helper = helper.await(),
            shizuku = shizuku.await(),
            webView = webView.await(),
        )
    }

    private fun observeHelper(): ProvisioningObservation<ProvisioningHelperState> =
        runCatching {
            when (helperIdentity()) {
                is HelperIdentityStatus.Compatible ->
                    ProvisioningObservation.Known(ProvisioningHelperState.COMPATIBLE)
                HelperIdentityStatus.ReachableUnverified ->
                    ProvisioningObservation.Known(ProvisioningHelperState.REACHABLE_UNVERIFIED)
                HelperIdentityStatus.Missing ->
                    ProvisioningObservation.Known(ProvisioningHelperState.MISSING)
                is HelperIdentityStatus.Incompatible ->
                    ProvisioningObservation.Known(ProvisioningHelperState.INCOMPATIBLE)
            }
        }.getOrElse {
            ProvisioningObservation.Unknown(ProvisioningUnknownReason.PROBE_FAILED)
        }

    private fun observeShizuku(): ProvisioningObservation<ProvisioningShizukuState> =
        runCatching {
            when (shizukuState()) {
                ShizukuState.READY ->
                    ProvisioningObservation.Known(ProvisioningShizukuState.READY)
                ShizukuState.DISABLED ->
                    ProvisioningObservation.Known(ProvisioningShizukuState.CONSENT_DISABLED)
                ShizukuState.PERMISSION_REQUIRED, ShizukuState.MANUAL_GRANT_REQUIRED ->
                    ProvisioningObservation.Known(ProvisioningShizukuState.PERMISSION_REQUIRED)
                ShizukuState.MANAGER_MISSING ->
                    ProvisioningObservation.Known(ProvisioningShizukuState.MANAGER_MISSING)
                ShizukuState.STOPPED ->
                    ProvisioningObservation.Known(ProvisioningShizukuState.SERVICE_NOT_RUNNING)
                ShizukuState.BINDING ->
                    ProvisioningObservation.Unknown(ProvisioningUnknownReason.NOT_READY)
                ShizukuState.MANAGER_UNTRUSTED, ShizukuState.INCOMPATIBLE ->
                    ProvisioningObservation.Unknown(ProvisioningUnknownReason.IDENTITY_UNAVAILABLE)
                ShizukuState.ERROR ->
                    ProvisioningObservation.Unknown(ProvisioningUnknownReason.PROBE_FAILED)
            }
        }.getOrElse {
            ProvisioningObservation.Unknown(ProvisioningUnknownReason.PROBE_FAILED)
        }

    private fun observeWebView(): ProvisioningObservation<ProvisioningWebViewState> =
        runCatching {
            webViewEngineMajor()
                ?.let { ProvisioningObservation.Known(ProvisioningWebViewState.Active(it.toString())) }
                ?: ProvisioningObservation.Unknown(ProvisioningUnknownReason.IDENTITY_UNAVAILABLE)
        }.getOrElse {
            ProvisioningObservation.Unknown(ProvisioningUnknownReason.PROBE_FAILED)
        }
}
