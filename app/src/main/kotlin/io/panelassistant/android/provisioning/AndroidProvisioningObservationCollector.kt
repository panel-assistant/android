package io.panelassistant.android.provisioning

import android.content.Context
import io.panelassistant.android.http.PanelInfo
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
    private val webViewEngineMajor: () -> Int?,
) : ProvisioningObservationCollector {
    constructor(context: Context) : this(
        helperIdentity = HelperClient::identityStatus,
        webViewEngineMajor = { PanelInfo.webViewStatus(context.applicationContext).engineMajor },
    )

    override suspend fun collect(): ProvisioningObservationSnapshot = coroutineScope {
        val helper = async(Dispatchers.IO) { observeHelper() }
        val webView = async(Dispatchers.IO) { observeWebView() }
        ProvisioningObservationSnapshot(
            helper = helper.await(),
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

    private fun observeWebView(): ProvisioningObservation<ProvisioningWebViewState> =
        runCatching {
            webViewEngineMajor()
                ?.let { ProvisioningObservation.Known(ProvisioningWebViewState.Active(it.toString())) }
                ?: ProvisioningObservation.Unknown(ProvisioningUnknownReason.IDENTITY_UNAVAILABLE)
        }.getOrElse {
            ProvisioningObservation.Unknown(ProvisioningUnknownReason.PROBE_FAILED)
        }
}
