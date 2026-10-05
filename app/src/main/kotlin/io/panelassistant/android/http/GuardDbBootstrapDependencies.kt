package io.panelassistant.android.http

import android.content.Context
import android.os.SystemClock
import io.panelassistant.android.Config
import io.panelassistant.android.control.AdbController
import io.panelassistant.android.control.CdpRelay
import io.panelassistant.android.control.RemoteDebugAuthorityResult
import io.panelassistant.android.control.RemoteDebugSecurityTransitionGate
import io.panelassistant.android.security.LocalApprovalBroker
import io.panelassistant.android.util.GuardDbAppStaging
import io.panelassistant.android.util.GuardDbArmCoordinator
import io.panelassistant.android.util.GuardDbMaintenance
import io.panelassistant.android.util.guardDbSettingsAuthorityStore
import io.panelassistant.android.util.guardDbBootNonce
import io.panelassistant.android.util.guardDbSentinelStore
import io.panelassistant.android.util.guardDbTerminalRetirementStore
import io.panelassistant.android.util.inspectGuardDbCandidate
import io.ktor.server.plugins.origin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

private const val GUARD_DB_ARM_RESPONSE_GRACE_MS = 500L

internal fun guardDbBootstrapDependencies(
    appContext: Context,
    config: Config,
    scope: CoroutineScope,
    pendingApks: PendingUploadStore,
    guardDbStaging: GuardDbAppStaging,
): GuardDbBootstrapRouteDependencies = GuardDbBootstrapRouteDependencies(
    pendingUploads = pendingApks,
    staging = guardDbStaging,
    inspectPending = { file -> inspectGuardDbCandidate(appContext, file) },
    inspectInstalled = {
        inspectGuardDbCandidate(appContext, File(appContext.applicationInfo.sourceDir))
    },
    settingsAuthority = {
        guardDbSettingsAuthorityStore(appContext).materializeExact()
    },
    client = GuardDbMaintenance.client,
    sentinelStore = guardDbSentinelStore(appContext),
    bootNonce = ::guardDbBootNonce,
    monotonicMs = SystemClock::elapsedRealtime,
    httpPort = { config.httpPort },
    hardened = { config.hardenedSecurityEnabled },
    securityEpoch = {
        RemoteDebugSecurityTransitionGate.withLock {
            val epoch = RemoteDebugSecurityTransitionGate.hardenedAuthorityEpoch()
                ?: return@withLock null
            val adb = AdbController(appContext, config)
            epoch.takeIf {
                config.hardenedSecurityEnabled && !CdpRelay.running &&
                    adb.hardenedRemoteDebugOff() && config.hardenedSecurityEnabled &&
                    !CdpRelay.running &&
                    RemoteDebugSecurityTransitionGate.hardenedAuthorityEpoch() == epoch
            }
        }
    },
    commitSentinel = { expectedEpoch, sentinel ->
        when (val authority = RemoteDebugSecurityTransitionGate.withEpoch(expectedEpoch) {
            if (sentinel.securityAuthorityEpoch != expectedEpoch ||
                RemoteDebugSecurityTransitionGate.hardenedAuthorityEpoch() != expectedEpoch ||
                !config.hardenedSecurityEnabled || CdpRelay.running
            ) {
                return@withEpoch GuardDbSentinelCommit.SecurityRefused
            }
            val adb = AdbController(appContext, config)
            if (!adb.hardenedRemoteDebugOff() || !config.hardenedSecurityEnabled ||
                CdpRelay.running ||
                RemoteDebugSecurityTransitionGate.hardenedAuthorityEpoch() != expectedEpoch
            ) return@withEpoch GuardDbSentinelCommit.SecurityRefused
            val store = guardDbSentinelStore(appContext)
            val written = store.write(sentinel)
            val load = store.load()
            if (written && load is io.panelassistant.android.util.GuardDbSentinelLoad.Valid &&
                load.sentinel == sentinel
            ) {
                GuardDbSentinelCommit.Committed(load)
            } else {
                GuardDbSentinelCommit.Failed(load)
            }
        }) {
            RemoteDebugAuthorityResult.Changed -> GuardDbSentinelCommit.SecurityRefused
            is RemoteDebugAuthorityResult.Value -> authority.value
        }
    },
    authorize = { call, operation, payload, summary ->
        authorizeSensitiveRequest(
            call = call,
            hardened = true,
            peer = call.request.origin.remoteAddress,
            operation = operation,
            payload = payload,
            summary = summary,
            broker = LocalApprovalBroker.instance,
        )
    },
    prepare = { manifest, schedule ->
        GuardDbArmCoordinator.prepare(
            appContext,
            manifest,
            schedule,
        )
    },
    contain = {
        scope.launch {
            delay(GUARD_DB_ARM_RESPONSE_GRACE_MS)
            appContext.stopService(
                android.content.Intent(appContext, io.panelassistant.android.PaneldService::class.java),
            )
            Thread {
                Thread.sleep(1_500L)
                io.panelassistant.android.GuardDbMaintenanceService.start(appContext)
            }.start()
        }
    },
    terminalRetirement = GuardDbTerminalRetirementRouteDependencies(
        client = GuardDbMaintenance.client,
        store = guardDbTerminalRetirementStore(appContext),
        hardened = { config.hardenedSecurityEnabled },
        securityEpoch = {
            RemoteDebugSecurityTransitionGate.withLock {
                val epoch = RemoteDebugSecurityTransitionGate.hardenedAuthorityEpoch()
                    ?: return@withLock null
                val adb = AdbController(appContext, config)
                epoch.takeIf {
                    config.hardenedSecurityEnabled && !CdpRelay.running &&
                        adb.hardenedRemoteDebugOff() && config.hardenedSecurityEnabled &&
                        !CdpRelay.running &&
                        RemoteDebugSecurityTransitionGate.hardenedAuthorityEpoch() == epoch
                }
            }
        },
        authorize = { call, operation, payload, summary ->
            authorizeSensitiveRequest(
                call = call,
                hardened = true,
                peer = call.request.origin.remoteAddress,
                operation = operation,
                payload = payload,
                summary = summary,
                broker = LocalApprovalBroker.instance,
            )
        },
    ),
)
