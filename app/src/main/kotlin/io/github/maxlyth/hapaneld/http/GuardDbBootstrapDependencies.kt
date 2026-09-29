package io.github.maxlyth.hapaneld.http

import android.content.Context
import android.os.SystemClock
import io.github.maxlyth.hapaneld.Config
import io.github.maxlyth.hapaneld.control.AdbController
import io.github.maxlyth.hapaneld.control.CdpRelay
import io.github.maxlyth.hapaneld.control.RemoteDebugAuthorityResult
import io.github.maxlyth.hapaneld.control.RemoteDebugSecurityTransitionGate
import io.github.maxlyth.hapaneld.security.LocalApprovalBroker
import io.github.maxlyth.hapaneld.util.GuardDbAppStaging
import io.github.maxlyth.hapaneld.util.GuardDbArmCoordinator
import io.github.maxlyth.hapaneld.util.GuardDbMaintenance
import io.github.maxlyth.hapaneld.util.guardDbSettingsAuthorityStore
import io.github.maxlyth.hapaneld.util.guardDbBootNonce
import io.github.maxlyth.hapaneld.util.guardDbSentinelStore
import io.github.maxlyth.hapaneld.util.guardDbTerminalRetirementStore
import io.github.maxlyth.hapaneld.util.inspectGuardDbCandidate
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
            if (written && load is io.github.maxlyth.hapaneld.util.GuardDbSentinelLoad.Valid &&
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
                android.content.Intent(appContext, io.github.maxlyth.hapaneld.PaneldService::class.java),
            )
            Thread {
                Thread.sleep(1_500L)
                io.github.maxlyth.hapaneld.GuardDbMaintenanceService.start(appContext)
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
