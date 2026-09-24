package io.github.maxlyth.hapaneld

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.hardware.camera2.CameraManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.CancellationSignal
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.OperationCanceledException
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.pm.PackageInfoCompat
import androidx.webkit.WebViewCompat
import io.github.maxlyth.hapaneld.camera.CameraCapabilityReason
import io.github.maxlyth.hapaneld.camera.cameraCapabilityReason
import io.github.maxlyth.hapaneld.control.AdbController
import io.github.maxlyth.hapaneld.device.DeviceProfile
import io.github.maxlyth.hapaneld.device.probe.AndroidPassiveProfileProbe
import io.github.maxlyth.hapaneld.device.profile.ProfileDraftFactory
import io.github.maxlyth.hapaneld.device.profile.RuntimeProfileRegistry
import io.github.maxlyth.hapaneld.persistence.AppState
import io.github.maxlyth.hapaneld.persistence.CleanDatabaseProof
import io.github.maxlyth.hapaneld.persistence.StateQuiescence
import io.github.maxlyth.hapaneld.upgrade.UpgradeShutdownCoordinator
import io.github.maxlyth.hapaneld.upgrade.UpgradeShutdownClaim
import io.github.maxlyth.hapaneld.input.EvdevButtonClient
import io.github.maxlyth.hapaneld.control.AutoBrightnessController
import io.github.maxlyth.hapaneld.control.AutoSleepController
import io.github.maxlyth.hapaneld.control.BootChimeController
import io.github.maxlyth.hapaneld.control.BrightnessController
import io.github.maxlyth.hapaneld.control.BuiltinDashboard
import io.github.maxlyth.hapaneld.control.BrightnessPreferenceOrigin
import io.github.maxlyth.hapaneld.control.CpuController
import io.github.maxlyth.hapaneld.control.CompanionDb
import io.github.maxlyth.hapaneld.control.CompanionDataOperationGate
import io.github.maxlyth.hapaneld.control.CompanionDataOperationState
import io.github.maxlyth.hapaneld.control.CdpRelay
import io.github.maxlyth.hapaneld.control.RemoteDebugSecurityTransitionGate
import io.github.maxlyth.hapaneld.control.NavbarController
import io.github.maxlyth.hapaneld.control.PowerController
import io.github.maxlyth.hapaneld.control.PowerSafetyController
import io.github.maxlyth.hapaneld.control.PrivilegeRoute
import io.github.maxlyth.hapaneld.control.PrivilegedRouteObservation
import io.github.maxlyth.hapaneld.control.NavigateController
import io.github.maxlyth.hapaneld.control.RelayController
import io.github.maxlyth.hapaneld.control.OverlayWakeTap
import io.github.maxlyth.hapaneld.control.ScreenController
import io.github.maxlyth.hapaneld.control.WakeOutcome
import io.github.maxlyth.hapaneld.control.AndroidWifiDiagnostics
import io.github.maxlyth.hapaneld.control.AndroidWifiOutageStore
import io.github.maxlyth.hapaneld.control.WifiDiagnosticDemand
import io.github.maxlyth.hapaneld.control.WifiDiagnosticAdmissionTracker
import io.github.maxlyth.hapaneld.control.WifiOutageTracker
import io.github.maxlyth.hapaneld.control.WifiOutageCounts
import io.github.maxlyth.hapaneld.control.wifiOutageChronic
import io.github.maxlyth.hapaneld.control.wifiOutageStatusText
import io.github.maxlyth.hapaneld.control.availability
import io.github.maxlyth.hapaneld.control.Su
import io.github.maxlyth.hapaneld.control.SystemController
import io.github.maxlyth.hapaneld.control.TameController
import io.github.maxlyth.hapaneld.control.KioskController
import io.github.maxlyth.hapaneld.control.LedEffectController
import io.github.maxlyth.hapaneld.control.WatchdogController
import io.github.maxlyth.hapaneld.control.TouchSoundController
import io.github.maxlyth.hapaneld.control.resolveTouchSoundIntent
import io.github.maxlyth.hapaneld.control.VolumeController
import io.github.maxlyth.hapaneld.control.ZigbeeController
import io.github.maxlyth.hapaneld.control.AndroidZigbeeGatewayHealthSource
import io.github.maxlyth.hapaneld.control.ZigbeeHealthMonitor
import io.github.maxlyth.hapaneld.control.ZigbeeObservation
import io.github.maxlyth.hapaneld.control.observeTypedShellCapability
import io.github.maxlyth.hapaneld.control.preventIdleDimDiagnostic
import io.github.maxlyth.hapaneld.device.LedMechanism
import io.github.maxlyth.hapaneld.hardware.LedController
import io.github.maxlyth.hapaneld.hardware.LedFactory
import io.github.maxlyth.hapaneld.hardware.Rk3576LedController
import io.github.maxlyth.hapaneld.hardware.SocketLedController
import io.github.maxlyth.hapaneld.config.Capabilities
import io.github.maxlyth.hapaneld.config.SettingValue
import io.github.maxlyth.hapaneld.config.defaultBool
import io.github.maxlyth.hapaneld.config.SettingsRegistry
import io.github.maxlyth.hapaneld.dashboard.EntityLearningManager
import io.github.maxlyth.hapaneld.dashboard.EntityLearningRuntime
import io.github.maxlyth.hapaneld.http.PaneldServer
import io.github.maxlyth.hapaneld.http.PanelInfo
import io.github.maxlyth.hapaneld.http.ManagementProjection
import io.github.maxlyth.hapaneld.http.ConfigDiscoverySuggestions
import io.github.maxlyth.hapaneld.http.DiagReader
import io.github.maxlyth.hapaneld.http.AutoBrightnessHttpAction
import io.github.maxlyth.hapaneld.http.AutoBrightnessHttpApi
import io.github.maxlyth.hapaneld.http.AutoBrightnessHttpValidation
import io.github.maxlyth.hapaneld.http.AutoSleepHttpApi
import io.github.maxlyth.hapaneld.http.retainCompanionLeaseUntilHelperIdle
import io.github.maxlyth.hapaneld.logship.LogCapture
import io.github.maxlyth.hapaneld.logship.LogShipper
import io.github.maxlyth.hapaneld.media.AudioPlaybackCoordinator
import io.github.maxlyth.hapaneld.migration.AndroidIdentityMigration
import io.github.maxlyth.hapaneld.migration.AndroidSuccessorHandoffPorts
import io.github.maxlyth.hapaneld.migration.IdentityMigrationGate
import io.github.maxlyth.hapaneld.migration.StartDisposition
import io.github.maxlyth.hapaneld.migration.SuccessorMigrationRunner
import io.github.maxlyth.hapaneld.migration.SuccessorHandoff
import io.github.maxlyth.hapaneld.provisioning.AndroidProvisioningObservationCollector
import io.github.maxlyth.hapaneld.provisioning.ProvisioningActivationSnapshot
import io.github.maxlyth.hapaneld.provisioning.ProvisioningCoordinator
import io.github.maxlyth.hapaneld.provisioning.ProvisioningCoreIdentity
import io.github.maxlyth.hapaneld.provisioning.toProvisioningProfile
import io.github.maxlyth.hapaneld.security.ApprovalBroker
import io.github.maxlyth.hapaneld.security.LocalApprovalBroker
import io.github.maxlyth.hapaneld.sensors.SensorReporter
import io.github.maxlyth.hapaneld.sensors.SensorLightPublisher
import io.github.maxlyth.hapaneld.sensors.submitIlluminanceIfExposed
import io.github.maxlyth.hapaneld.sensors.HaAmbientLuxSubscriber
import io.github.maxlyth.hapaneld.sensors.HaAmbientSourceValidation
import io.github.maxlyth.hapaneld.sensors.HaAmbientSourcePhase
import io.github.maxlyth.hapaneld.sensors.DashboardHaApiSessionProvider
import io.github.maxlyth.hapaneld.sensors.HaExactEntityStreamOwner
import io.github.maxlyth.hapaneld.sensors.HaLifecycleCoordinator
import io.github.maxlyth.hapaneld.sensors.HaLifecycleRuntime
import io.github.maxlyth.hapaneld.sensors.HaLifecycleState
import io.github.maxlyth.hapaneld.sensors.HaNetworkPath
import io.github.maxlyth.hapaneld.sensors.PathProbeMonitor
import io.github.maxlyth.hapaneld.sensors.PathProbeRuntime
import io.github.maxlyth.hapaneld.sensors.IcmpEchoSource
import io.github.maxlyth.hapaneld.sensors.HaNetworkPathMonitor
import io.github.maxlyth.hapaneld.sensors.HaNetworkPathRuntime
import io.github.maxlyth.hapaneld.sensors.HaPresenceSourceManager
import io.github.maxlyth.hapaneld.sensors.HaSiteMetadataClient
import io.github.maxlyth.hapaneld.sensors.KtorHaAmbientTransport
import io.github.maxlyth.hapaneld.mqtt.MqttAddressFamilyPolicy
import io.github.maxlyth.hapaneld.panelassistant.KtorPanelAssistantTransportConnector
import io.github.maxlyth.hapaneld.panelassistant.PanelAssistantApprovalState
import io.github.maxlyth.hapaneld.panelassistant.PanelAssistantCommand
import io.github.maxlyth.hapaneld.panelassistant.PanelAssistantCommandProcessor
import io.github.maxlyth.hapaneld.panelassistant.PanelAssistantCommandResult
import io.github.maxlyth.hapaneld.panelassistant.PanelAssistantCommandSink
import io.github.maxlyth.hapaneld.panelassistant.PanelAssistantHelloIdentity
import io.github.maxlyth.hapaneld.panelassistant.PanelAssistantShadowReporter
import io.github.maxlyth.hapaneld.panelassistant.PanelAssistantTransportOwner
import io.github.maxlyth.hapaneld.panelassistant.PanelAssistantTransportDemand
import io.github.maxlyth.hapaneld.panelassistant.panelAssistantTransportDemand
import io.github.maxlyth.hapaneld.sensors.KtorHaExactEntityStreamTransport
import io.github.maxlyth.hapaneld.storage.StorageDatabaseFailureKind
import io.github.maxlyth.hapaneld.storage.StorageHealthObservation
import io.github.maxlyth.hapaneld.storage.StorageHealthObservationQueue
import io.github.maxlyth.hapaneld.storage.StorageHealthRecoveryLifecycle
import io.github.maxlyth.hapaneld.storage.StorageHealthRuntime
import io.github.maxlyth.hapaneld.storage.StorageHealthSeverity
import io.github.maxlyth.hapaneld.storage.StorageHealthSnapshot
import io.github.maxlyth.hapaneld.storage.StorageQuickCheck
import io.github.maxlyth.hapaneld.util.localIpv4
import io.github.maxlyth.hapaneld.util.localIpv6
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import io.github.maxlyth.hapaneld.util.LatestOperationPolicy
import io.github.maxlyth.hapaneld.util.LatestOperationTimeoutPolicy
import io.github.maxlyth.hapaneld.util.ServiceRuntimeOwner
import io.github.maxlyth.hapaneld.util.SingleFlightExecutor
import io.github.maxlyth.hapaneld.util.SuccessStickyProbe
import io.github.maxlyth.hapaneld.metrics.FeatureCostOperation
import io.github.maxlyth.hapaneld.metrics.FeatureCostOutcome
import io.github.maxlyth.hapaneld.metrics.FeatureCosts
import io.github.maxlyth.hapaneld.util.BorrowedRendererSettings
import io.github.maxlyth.hapaneld.util.BundledHelperInstaller
import io.github.maxlyth.hapaneld.util.RendererPreparationCoordinator
import io.github.maxlyth.hapaneld.util.RendererPreparationState
import io.github.maxlyth.hapaneld.util.MonotonicDeadline
import io.github.maxlyth.hapaneld.util.LatestDispatcher
import io.github.maxlyth.hapaneld.util.submit
import io.github.maxlyth.hapaneld.util.OwnedThread
import io.github.maxlyth.hapaneld.util.awaitSuccessful
import io.github.maxlyth.hapaneld.util.awaitTrue
import io.github.maxlyth.hapaneld.util.ProfileRestartCoordinator
import io.github.maxlyth.hapaneld.util.ServiceRestartBarrier
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import io.github.maxlyth.hapaneld.util.UpdateChecker
import io.github.maxlyth.hapaneld.util.CompanionInstaller
import io.github.maxlyth.hapaneld.util.CompanionOperationStatus
import io.github.maxlyth.hapaneld.util.AppInstaller
import io.github.maxlyth.hapaneld.util.InstallProgress
import io.github.maxlyth.hapaneld.util.InstallOperationResult
import io.github.maxlyth.hapaneld.util.InstallPresentation
import io.github.maxlyth.hapaneld.util.HelperClient
import io.github.maxlyth.hapaneld.util.GuardDbProcessAdmission
import io.github.maxlyth.hapaneld.util.GuardDbSentinelLoad
import io.github.maxlyth.hapaneld.util.HelperInstallReconciler
import io.github.maxlyth.hapaneld.util.HelperInstallTransaction
import io.github.maxlyth.hapaneld.util.SelfUpdater
import io.github.maxlyth.hapaneld.util.WebViewInstaller
import io.github.maxlyth.hapaneld.mqtt.ConnectionSupervisor
import io.github.maxlyth.hapaneld.mqtt.HeartbeatAdmission
import io.github.maxlyth.hapaneld.mqtt.isAuthRecoveryState
import io.github.maxlyth.hapaneld.mqtt.SoftwareComponent
import io.github.maxlyth.hapaneld.mqtt.SoftwareTarget
import io.github.maxlyth.hapaneld.mqtt.SoftwareUpdateSources
import io.github.maxlyth.hapaneld.util.PanelAssistantUpdateLease
import io.github.maxlyth.hapaneld.platform.AndroidScreenPower
import io.github.maxlyth.hapaneld.platform.AndroidSystemEnv
import io.github.maxlyth.hapaneld.util.periodic
import io.github.maxlyth.hapaneld.util.SystemProps
import io.github.maxlyth.hapaneld.dashboard.shouldReloadBuiltinAfterEntityFilterChange
import io.github.maxlyth.hapaneld.shizuku.ShizukuBridge
import java.io.File
import java.net.Inet4Address
import java.net.InetAddress
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** A failed candidate may undo only the channel it committed; a newer concurrent choice is preserved. */
internal fun failedSelfUpdateChannelRollback(
    currentChannel: String,
    failedChannel: String,
    previousChannel: String,
): String? = previousChannel.takeIf { currentChannel == failedChannel }

/** A committed channel is retained only when the exact prepared APK reports package installation. */
internal suspend fun installCommittedSelfUpdateChannel(
    install: suspend () -> io.github.maxlyth.hapaneld.http.SelfUpdateChannelInstallResult,
    rollback: () -> Unit,
    onInstalled: () -> Unit = {},
): io.github.maxlyth.hapaneld.http.SelfUpdateChannelInstallResult {
    var installed = false
    try {
        return install().also {
            installed = it.installed
            if (installed) onInstalled()
        }
    } finally {
        if (!installed) rollback()
    }
}

/** Preserve producer-selected metadata while adapting the server-owned channel result to progress. */
internal fun selfUpdateChannelOperationResult(
    result: io.github.maxlyth.hapaneld.http.SelfUpdateChannelInstallResult,
): InstallOperationResult = InstallOperationResult(result.message, result.presentation)

/** A scope canceled before the promoted install body starts still owes cleanup before ticket release. */
internal fun cleanupCanceledCommittedSelfUpdateChannel(
    cause: Throwable?,
    installed: Boolean = false,
    discardPrepared: () -> Unit,
    rollback: () -> Unit,
    finishProgress: () -> Unit,
) {
    if (cause == null) return
    try {
        discardPrepared()
    } finally {
        try {
            if (!installed) rollback()
        } finally {
            finishProgress()
        }
    }
}

internal fun shouldDisableAutoBrightnessForMissingSource(
    enabled: Boolean,
    haEntity: String,
    hasLocalSensor: Boolean,
): Boolean = enabled && haEntity.isBlank() && !hasLocalSensor

/** Hard outer owner for synchronous SQLite/file proof. Its daemon worker cannot retain process exit. */
internal fun <T> runBoundedShutdownProof(timeoutMs: Long, proof: () -> T): T? {
    if (timeoutMs <= 0L) return null
    val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "ha-paneld-shutdown-db-proof").apply { isDaemon = true }
    }
    val future = executor.submit(Callable(proof))
    return try {
        future.get(timeoutMs, TimeUnit.MILLISECONDS)
    } catch (_: TimeoutException) {
        null
    } catch (_: ExecutionException) {
        null
    } catch (_: java.util.concurrent.CancellationException) {
        null
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        null
    } finally {
        future.cancel(true)
        executor.shutdownNow()
    }
}

internal fun defaultNetworkIpv4(addresses: Iterable<InetAddress>): String? =
    addresses.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.hostAddress

internal fun preferredAdminHomeRepairRoute(
    privilege: PrivilegedRouteObservation,
): PrivilegeRoute? = preferredAdminHomeRepairRoute(
    helperRootReady = privilege.helperRootReady,
    directSuReady = privilege.directSuReady,
)

internal fun preferredAdminHomeRepairRoute(
    helperRootReady: Boolean,
    directSuReady: Boolean,
): PrivilegeRoute? = when {
    helperRootReady -> PrivilegeRoute.DAEMON
    directSuReady -> PrivilegeRoute.SU
    else -> null
}

internal fun shouldAttemptPeriodicAdminHomeRepair(
    serviceStopping: Boolean,
    adminHomeSelected: Boolean,
    admittedRoute: PrivilegeRoute?,
    policyGeneration: Long,
    failedGeneration: Long,
): Boolean = !serviceStopping && adminHomeSelected && admittedRoute != null &&
    failedGeneration != policyGeneration

internal sealed interface StorageHealthNotificationDecision {
    data object KeepExisting : StorageHealthNotificationDecision
    data object Cancel : StorageHealthNotificationDecision
    data class Show(val title: String, val body: String) : StorageHealthNotificationDecision
}

/** Pure notification projection: the storage authority owns thresholds, hysteresis and failure latching. */
internal fun storageHealthNotificationDecision(
    snapshot: StorageHealthSnapshot,
): StorageHealthNotificationDecision = when (snapshot.severity) {
    StorageHealthSeverity.UNCHECKED -> StorageHealthNotificationDecision.KeepExisting
    StorageHealthSeverity.HEALTHY,
    StorageHealthSeverity.WARNING -> StorageHealthNotificationDecision.Cancel
    StorageHealthSeverity.CRITICAL -> {
        val capacity = if (snapshot.totalBytes > 0L) {
            " (${snapshot.usableBytes.coerceAtLeast(0L) / (1024L * 1024L)} MiB filesystem free)"
        } else ""
        StorageHealthNotificationDecision.Show(
            title = "Panel storage/database pressure critical",
            body = "Storage or database-file pressure is critical$capacity. " +
                "Open ha-paneld and review free space and WAL growth before changing configuration.",
        )
    }
    StorageHealthSeverity.DATABASE_FAILURE -> {
        // The operation class is the one detail that turns "something failed" into a reproducible
        // report; it is a closed internal vocabulary, so it carries no query text or user data.
        val during = snapshot.databaseFailureOperationLabel?.let { " during $it" }.orEmpty()
        StorageHealthNotificationDecision.Show(
            title = "Panel database needs attention",
            body = when (snapshot.databaseFailureKind) {
                StorageDatabaseFailureKind.STORAGE_FULL ->
                    "A panel database write$during failed when storage was full. Open ha-paneld; recovery is not yet verified."
                StorageDatabaseFailureKind.IO ->
                    "The panel database reported a disk I/O failure$during. Open ha-paneld; retry only after resolving the cause."
                StorageDatabaseFailureKind.CORRUPTION ->
                    "The panel database failed its integrity check$during. Preserve it and open ha-paneld diagnostics before further writes."
                StorageDatabaseFailureKind.BUSY ->
                    "The panel database remained busy or locked$during. Open ha-paneld diagnostics and wait for a clean check before retrying."
                StorageDatabaseFailureKind.UNKNOWN,
                null -> "The panel database reported a failure$during. Preserve it and open ha-paneld diagnostics before retrying."
            },
        )
    }
}

internal enum class StorageHealthObservationQuality { COMPLETE, FAILED, INCOMPLETE }

/** Pure admission rule for the daily probe's bounded retry loop. */
internal fun storageHealthObservationQuality(
    observation: StorageHealthObservation,
): StorageHealthObservationQuality = when {
    observation.quickCheck == StorageQuickCheck.FAILED -> StorageHealthObservationQuality.FAILED
    observation.checkedAtMillis <= 0L || observation.totalBytes <= 0L ||
        observation.mainDatabaseBytes <= 0L || observation.pageSizeBytes <= 0L ||
        observation.pageCount <= 0L || observation.schemaVersion <= 0 ||
        observation.quickCheck == StorageQuickCheck.NOT_RUN -> StorageHealthObservationQuality.INCOMPLETE
    else -> StorageHealthObservationQuality.COMPLETE
}

internal fun storageHealthObservationNeedsRetry(observation: StorageHealthObservation): Boolean =
    storageHealthObservationQuality(observation) == StorageHealthObservationQuality.INCOMPLETE

internal sealed interface StorageHealthObservationAttempt {
    data object Complete : StorageHealthObservationAttempt
    data object Retry : StorageHealthObservationAttempt
    data object Stopped : StorageHealthObservationAttempt
}

/** A stopped or complete observation must not spend another prompt-recovery attempt. */
internal fun storageHealthRecoveryAttemptComplete(attempt: StorageHealthObservationAttempt): Boolean =
    attempt != StorageHealthObservationAttempt.Retry

internal enum class ServiceStartupDisposition {
    RUNNING,
    PROFILE_ACTIVATION_ROLLBACK,
    DEGRADED,
}

internal const val INSTALLED_SUCCESSOR_HANDOFF_ACTION =
    "io.github.maxlyth.hapaneld.action.HANDOFF_INSTALLED_SUCCESSOR"

internal fun installedHandoffWakeRequested(isBridge: Boolean, action: String?): Boolean =
    isBridge && action == INSTALLED_SUCCESSOR_HANDOFF_ACTION

/** Retains only an explicit host handoff wake until this service's startup is healthy. */
internal class InstalledSuccessorHandoffWake {
    private var requested = false
    private var healthy = false

    @Synchronized fun request(): Boolean {
        requested = true
        return consumeIfHealthy()
    }

    @Synchronized fun markStartupHealthy(): Boolean {
        healthy = true
        return consumeIfHealthy()
    }

    private fun consumeIfHealthy(): Boolean {
        if (!healthy || !requested) return false
        requested = false
        return true
    }
}

internal data class StartupRecoveryDecision(val restart: Boolean, val nextAttempt: Int)

internal fun shouldForceFreshProcessAfterExternalRecovery(
    attempt: Int,
    maxAttempts: Int,
    kioskSafe: Boolean,
    navbarSafe: Boolean,
    screenSafe: Boolean,
    relaySafe: Boolean,
): Boolean = attempt >= maxAttempts && screenSafe && !(kioskSafe && navbarSafe && relaySafe)

/** Main-safe phase zero for both Android destruction and explicit process replacement. */
internal fun beginAudioTeardown(
    closeAdmission: () -> Unit,
    cancelCurrent: () -> Unit,
) {
    closeAdmission()
    cancelCurrent()
}

/** Continue the complete owner sweep while retaining any ambiguous cleanup result for the final gate. */
internal class ServiceOwnerCleanupTracker {
    private val complete = AtomicBoolean(true)

    fun run(block: () -> Unit): Throwable? = try {
        block()
        null
    } catch (error: Throwable) {
        complete.set(false)
        error
    }

    fun record(result: Boolean) {
        if (!result) complete.set(false)
    }

    fun isComplete(): Boolean = complete.get()
}

/** Reuse the onDestroy screen owner; a second mutator could wake a successor after barrier release. */
internal fun proveScreenSafeForBoundary(
    existingOwner: CompletableFuture<Boolean>,
): Boolean = existingOwner.getNow(false)

/** Retry pending durable kiosk cleanup after the boot escape window without blocking unrelated service
 * startup. The retry count is finite so permanently unavailable root never creates steady background
 * load; retained recovery markers make the next service start retry again. */
internal fun recoverAndMaybeEnableKiosk(
    escapeDelayMs: Long,
    retryDelayMs: Long,
    maxAttempts: Int,
    shouldContinue: () -> Boolean,
    recover: () -> Boolean,
    enabled: () -> Boolean,
    enable: () -> Boolean,
    pause: (Long) -> Unit = Thread::sleep,
): Boolean {
    require(escapeDelayMs >= 0L)
    require(retryDelayMs >= 0L)
    require(maxAttempts > 0)
    return try {
        pause(escapeDelayMs)
        repeat(maxAttempts) { attempt ->
            if (!shouldContinue()) return false
            if (recover()) {
                if (!shouldContinue()) return false
                return !enabled() || enable()
            }
            if (attempt + 1 < maxAttempts) pause(retryDelayMs)
        }
        false
    } catch (_: InterruptedException) {
        false
    }
}

/** Bound process-level recovery for transient startup failures; clock rollback/reboot opens a new window. */
internal fun startupRecoveryDecision(
    previousAttempts: Int,
    previousAtMs: Long,
    nowMs: Long,
    maxAttempts: Int = 3,
    windowMs: Long = 10L * 60L * 1_000L,
): StartupRecoveryDecision {
    val sameWindow = previousAtMs in 1..nowMs && nowMs - previousAtMs <= windowMs
    val next = (if (sameWindow) previousAttempts.coerceAtLeast(0) else 0) + 1
    return StartupRecoveryDecision(next <= maxAttempts, next)
}

internal fun awaitServiceStartup(
    startup: Future<Boolean>,
    profileActivationGeneration: Long?,
): ServiceStartupDisposition {
    val healthy = runCatching { startup.get() }.getOrDefault(false)
    return when {
        healthy -> ServiceStartupDisposition.RUNNING
        profileActivationGeneration != null -> ServiceStartupDisposition.PROFILE_ACTIVATION_ROLLBACK
        else -> ServiceStartupDisposition.DEGRADED
    }
}

/** Confirm declared Zigbee hardware when readable; preserve declared capability across probe failure. */
internal fun zigbeeCapabilityPresent(
    declaredGateway: Boolean,
    observation: ZigbeeObservation,
): Boolean = declaredGateway && (!observation.probeSucceeded || observation.present)

/**
 * Whether this panel offers a camera at all. [declared] is the profile's tri-state `hardware.camera`
 * and [observed] is what enumerating Android's cameras found, or null while that probe has not answered.
 *
 * The direction is deliberately the opposite of [zigbeeCapabilityPresent]. A zigbee gateway lives at a
 * profile-supplied path that nothing can discover, so there an observation may only veto a declaration.
 * A camera is enumerated by `CameraManager`, which is Android's authoritative and universal answer, so
 * here an observation may grant one: a board whose profile says nothing still offers the camera Android
 * can see, instead of every owner of camera hardware having to author a profile first.
 *
 * A declaration still wins in both directions. The two boards that declare a camera are unaffected by a
 * probe that throws, and a board whose enumerated device is not a usable room camera is suppressed with
 * an explicit `camera: false`.
 *
 * The boolean is derived from [cameraCapabilityReason] rather than restated here, because the panel now
 * has to explain the answer as well as act on it, and a capability that disagreed with its own stated
 * reason would be the worst of both.
 */
internal fun cameraCapabilityPresent(declared: Boolean?, observed: Boolean?): Boolean =
    cameraCapabilityReason(declared, observed).capable

internal fun commitBorrowedRendererTarget(
    commit: () -> Boolean,
    onCommitted: () -> Unit,
): Boolean {
    val committed = commit()
    if (committed) onCommitted()
    return committed
}

internal fun adaptiveHaSource(enabled: Boolean, configuredEntity: String): String? =
    configuredEntity.trim().takeIf { enabled && it.isNotEmpty() }

/** Prefer the hostname advertised by Home Assistant for the MQTT suggestion; retain an IP fallback
 * when the advertisement is absent or malformed. Hostnames avoid pinning a panel to one IPv4 address
 * and let the broker resolve over IPv6 where the network supports it. */
internal fun mqttBrokerSuggestionFromHaUrl(haUrl: String): String? = runCatching {
    val host = java.net.URI(haUrl.trim()).host?.trim()?.removePrefix("[")?.removeSuffix("]")
        ?.takeIf { it.isNotEmpty() } ?: return@runCatching null
    val authorityHost = if (host.contains(':')) "[$host]" else host
    "tcp://$authorityHost:1883"
}.getOrNull()

internal fun prepareEntityLearningStartup(
    startMdns: () -> Unit,
    reconcileRenderer: () -> RendererPreparationCoordinator.Result,
    startLearning: () -> Unit,
): RendererPreparationCoordinator.Result {
    startMdns()
    return reconcileRenderer().also { result ->
        if (result != RendererPreparationCoordinator.Result.CLOSED) startLearning()
    }
}

/**
 * Reconstruct the process-local Companion launch gate only when the app-private marker proves that
 * this app may have submitted a helper transaction before process death. Ordinary helper absence
 * without that marker is not a launch block. A retained lease is released only after an affirmative
 * IDLE status also clears the marker durably.
 */
internal fun restoreCompanionLaunchSuppression(
    packageName: String,
    operationState: CompanionDataOperationState,
    operationStatus: () -> CompanionOperationStatus,
    retain: (CompanionDataOperationGate.Lease) -> Unit,
): CompanionOperationStatus {
    if (!operationState.isPending()) {
        return CompanionOperationStatus.IDLE
    }
    val status = runCatching(operationStatus).getOrDefault(CompanionOperationStatus.UNAVAILABLE)
    val resolvedPackage = packageName.takeIf(CompanionDataOperationGate::isCompanionPackage)
        ?: CompanionInstaller.MINIMAL_PKG
    when (status) {
        CompanionOperationStatus.IDLE -> {
            if (!operationState.clear()) {
                CompanionDataOperationGate.acquire(resolvedPackage)?.let(retain)
            }
        }
        CompanionOperationStatus.BUSY,
        CompanionOperationStatus.UNSUPPORTED,
        CompanionOperationStatus.UNAVAILABLE ->
            CompanionDataOperationGate.acquire(resolvedPackage)?.let(retain)
    }
    return status
}

/** Start a replacement network runtime without making native HA link resolution depend on MQTT reaching
 * an onConnected callback. This keeps live HA URL/token changes effective when MQTT is disabled. */
internal fun startReconfiguredNetworkRuntime(
    startMdns: () -> Unit,
    resolveHaLink: () -> Unit,
    startMqtt: () -> Unit,
) {
    startMdns()
    resolveHaLink()
    startMqtt()
}

internal enum class NetworkAvailableAction { NONE, RECONNECT, RETRY_DISCOVERY }

/** Blank-broker discovery is a recoverable waiting state, while explicit auth rejection and a stopped
 * bridge retain their own lifecycle policies. */
internal fun networkAvailableAction(state: String, configuredBroker: String): NetworkAvailableAction = when {
    state == "connected" || state == "announcing" || state == "disabled" || state == "config-error" ||
        isAuthRecoveryState(state) -> NetworkAvailableAction.NONE
    state == "discovering" && configuredBroker.isBlank() -> NetworkAvailableAction.RETRY_DISCOVERY
    else -> NetworkAvailableAction.RECONNECT
}

/** Values whose change requires replacing the concrete MQTT/mDNS runtime rather than reannouncing it. */
internal class NetworkRuntimeIdentity(
    internal val panelId: String,
    internal val friendlyName: String,
    internal val httpPort: Int,
    internal val broker: String,
    private val user: String,
    private val password: String,
    private val addressFamily: String = SettingsRegistry.DEFAULT_MQTT_ADDRESS_FAMILY,
) {
    override fun equals(other: Any?): Boolean = other is NetworkRuntimeIdentity &&
        panelId == other.panelId && friendlyName == other.friendlyName && httpPort == other.httpPort &&
        broker == other.broker && user == other.user && password == other.password &&
        addressFamily == other.addressFamily

    override fun hashCode(): Int {
        var result = panelId.hashCode()
        result = 31 * result + friendlyName.hashCode()
        result = 31 * result + httpPort
        result = 31 * result + broker.hashCode()
        result = 31 * result + user.hashCode()
        result = 31 * result + password.hashCode()
        result = 31 * result + addressFamily.hashCode()
        return result
    }

    override fun toString(): String = "NetworkRuntimeIdentity(redacted)"

    internal fun mqttCredentials(): MqttCredentialsSnapshot =
        MqttCredentialsSnapshot(broker, user, password, addressFamily)
}

internal data class MqttProjectionIdentity(
    val manufacturer: String,
    val model: String,
    val exposures: List<Pair<String, Boolean>>,
)

internal class HaLinkIdentity(
    private val url: String,
    private val accessToken: String,
    private val refreshToken: String,
    private val tokenExpiry: Long,
    private val clientId: String,
) {
    override fun equals(other: Any?): Boolean = other is HaLinkIdentity && url == other.url &&
        accessToken == other.accessToken && refreshToken == other.refreshToken &&
        tokenExpiry == other.tokenExpiry && clientId == other.clientId

    override fun hashCode(): Int = listOf(url, accessToken, refreshToken, tokenExpiry, clientId).hashCode()
    override fun toString(): String = "HaLinkIdentity(redacted)"
}

internal data class ConfigRefreshEffects(val reannounceMqtt: Boolean, val resolveHaLink: Boolean)

internal data class ConfigOwnerRefreshPlan(
    val adaptiveBrightness: Boolean,
    val autoSleep: Boolean,
    val logShipping: Boolean,
    val keepAwake: Boolean,
    val launcherHome: Boolean,
    val rendererTarget: Boolean,
    val haLifecycle: Boolean,
    val camera: Boolean,
    val panelAssistantTransport: Boolean,
)

internal fun configOwnerRefreshPlan(changedKeys: Set<String>): ConfigOwnerRefreshPlan {
    val ha = setOf("ha_url", "ha_token", "ha_refresh_token", "ha_token_expiry", "ha_client_id")
    return ConfigOwnerRefreshPlan(
        adaptiveBrightness = changedKeys.any(ha::contains),
        autoSleep = changedKeys.any((ha + setOf("panel_id", "auto_sleep_source"))::contains),
        logShipping = changedKeys.any(setOf(
            "log_ship_enabled", "log_ship_host", "log_ship_port", "log_ship_protocol",
        )::contains),
        keepAwake = "keep_awake" in changedKeys,
        launcherHome = changedKeys.any(setOf("launcher_package", "dashboard_package", "ha_url")::contains),
        rendererTarget = changedKeys.any((ha + setOf("dashboard_package", "home_dashboard"))::contains),
        // Lifecycle demand follows the renderer selection AND the credentials, because it is only worth
        // holding a socket open for a panel that both renders a dashboard and can authenticate.
        haLifecycle = changedKeys.any((ha + "dashboard_package")::contains),
        // The master switch closes a live session on this lane too, not only on the watchdog tick.
        camera = "camera_enabled" in changedKeys,
        // Credentials only; the owner itself ignores a change that leaves the credential identity equal.
        panelAssistantTransport = changedKeys.any(ha::contains),
    )
}

/**
 * Whether to hold the shared Home Assistant socket open purely to observe lifecycle events.
 *
 * Pure — unit-tested in `HaLifecycleDemandTest`. Kept separate from the renderer so the rule is stated
 * once: only the built-in renderer has a native surface to show the outage on, and without credentials
 * there is nothing to authenticate with.
 */
internal fun haLifecycleWatchWanted(builtinRendererSelected: Boolean, credentialsPresent: Boolean): Boolean =
    builtinRendererSelected && credentialsPresent

/**
 * Whether a lifecycle-watch refresh may run now. Pure so the deferral is provable.
 *
 * Enabling waits for the CURRENT renderer to settle, on every path — the launch-latency decision is
 * void if an onboarding save can open the socket early through a different door. Disabling is never
 * deferred: settlement resets when a renderer is released, so a deferred disable after deselecting the
 * built-in renderer would wait forever while the socket stayed open.
 */
internal fun haLifecycleRefreshPermitted(rendererSettled: Boolean, wanted: Boolean): Boolean =
    rendererSettled || !wanted

internal fun nextLiveSettingRetryAttempt(currentAttempt: Int, maximumAttempts: Int = 3): Int? =
    (currentAttempt + 1).takeIf { it < maximumAttempts }

internal data class NetworkConfigurationSnapshot(
    val runtime: NetworkRuntimeIdentity,
    val projection: MqttProjectionIdentity,
    val haLink: HaLinkIdentity,
)

internal fun configRefreshEffects(
    activeProjection: MqttProjectionIdentity,
    nextProjection: MqttProjectionIdentity,
    activeHaLink: HaLinkIdentity,
    nextHaLink: HaLinkIdentity,
): ConfigRefreshEffects = ConfigRefreshEffects(
    reannounceMqtt = activeProjection != nextProjection,
    resolveHaLink = activeHaLink != nextHaLink,
)

internal fun refreshAmbientSourceBinding(
    restartSource: Boolean,
    selectedSource: String?,
    markUnavailable: () -> Unit,
    setSource: (String?) -> Unit,
) {
    if (restartSource) {
        markUnavailable()
        setSource(null)
    }
    setSource(selectedSource)
}

internal fun applyAutoSleepAreaFailOff(
    expectedEpoch: Long,
    expectedSettingGeneration: Long,
    epochIsCurrent: (Long) -> Boolean,
    persistOff: (Long) -> AutoSleepWriteResult?,
    refreshController: () -> Unit,
    applyBridge: () -> LiveSettingApplyResult,
): LiveSettingApplyResult {
    if (!epochIsCurrent(expectedEpoch)) return LiveSettingApplyResult.APPLIED
    return when (persistOff(expectedSettingGeneration)) {
        null -> LiveSettingApplyResult.APPLIED
        AutoSleepWriteResult.FAILED -> LiveSettingApplyResult.FAILED
        AutoSleepWriteResult.COMMITTED -> {
            refreshController()
            applyBridge()
        }
        AutoSleepWriteResult.UNCHANGED -> applyBridge()
    }
}

internal fun applyAutoSleepFencedReplay(
    fence: Long,
    currentGeneration: Long,
    currentEnabled: Boolean,
    persistOff: (Long) -> AutoSleepWriteResult?,
    refreshController: () -> Unit,
    applyBridge: () -> LiveSettingApplyResult,
): LiveSettingApplyResult = when {
    currentGeneration == fence -> applyBridge()
    currentEnabled && currentGeneration + 1L == fence -> when (persistOff(currentGeneration)) {
        AutoSleepWriteResult.COMMITTED -> {
            refreshController()
            applyBridge()
        }
        AutoSleepWriteResult.UNCHANGED -> applyBridge()
        AutoSleepWriteResult.FAILED -> LiveSettingApplyResult.FAILED
        null -> LiveSettingApplyResult.APPLIED
    }
    else -> LiveSettingApplyResult.APPLIED
}

internal fun acceptCommittedAutoSleepSetting(
    authority: LiveSettingAuthority,
    refreshController: () -> Unit,
) {
    authority.discard("auto_sleep")
    refreshController()
}

/**
 * Persistent foreground service. Hosts the Ktor HTTP listener, the JmDNS advertiser, the MQTT
 * control bridge and the hardware controllers for the panel's lifetime. Declared
 * `foregroundServiceType=specialUse` because a wall-panel on-LAN agent has no analogue among the
 * predefined FGS types.
 *
 * The service itself draws no UI and never takes HOME foreground. Renderer ownership remains in the
 * configured dashboard activity, whether that is the built-in Home Assistant renderer or a launchable
 * external dashboard application.
 */
class PaneldService : Service() {
    private var guardDbRedirect = false
    // Application-id migration: a retired bridge or a passive successor constructs nothing below.
    private var identityMigrationStandby = false
    private var identityMigrationSticky = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // One dedicated transition lane owns initial start, config rebuilds, reconnects, and final teardown.
    // Observations capture the generation and concrete MQTT/mDNS pair together, so watchdog/network work cannot read a generation from one runtime and then reach a replacement through a mutable field.
    private data class NetworkRuntime(val mqtt: MqttBridge, val mdns: MdnsAdvertiser)
    private lateinit var runtime: ServiceRuntimeOwner<NetworkRuntime>
    private val reconfigureKeysLock = Any()
    private val pendingReconfigureKeys = linkedSetOf<String>()
    private val liveSettingRetryAttempts = mutableMapOf<String, Int>()
    private lateinit var restartLease: ServiceRestartBarrier.Lease
    private lateinit var mainHandler: Handler
    private lateinit var liveSettingAuthority: LiveSettingAuthority
    private lateinit var config: Config
    private lateinit var appliedNetworkConfiguration: NetworkConfigurationSnapshot
    private lateinit var server: PaneldServer
    private lateinit var rendererPreparation: RendererPreparationCoordinator
    private lateinit var entityLearning: EntityLearningManager
    // Voice-assistant phase authority. The voice-coordinator lane drives it via set(); this service only
    // wires its change listener to the current MQTT bridge generation (below, alongside the learned-
    // proximity listener) and reads it into buildMqtt()'s voiceState supplier.
    private val voiceStateAuthority = io.github.maxlyth.hapaneld.assist.VoiceStateAuthority()
    private var storageHealthSubscription: AutoCloseable? = null
    private val storageHealthLifecycleLock = Any()
    private var storageHealthRecoveryLifecycle: StorageHealthRecoveryLifecycle? = null
    private val storageHealthObservationQueue = StorageHealthObservationQueue(
        create = ::CancellationSignal,
        cancel = CancellationSignal::cancel,
    )
    private lateinit var companionDataOperationState: CompanionDataOperationState
    private val mqtt: MqttBridge get() = runtime.current().mqtt
    // A Panel Assistant entry owning the ha-paneld update entity; see PanelAssistantUpdateLease.
    private val panelAssistantUpdateLease by lazy {
        PanelAssistantUpdateLease(
            wallNowMs = System::currentTimeMillis,
            uptimeMs = android.os.SystemClock::elapsedRealtime,
            readPersisted = { config.panelAssistantUpdateOwnerSeenMs },
            writePersisted = config::setPanelAssistantUpdateOwnerSeenMs,
        )
    }
    // Observer identities for InstallProgress and UpdateChecker, so detach clears only this service's.
    // Progress only schedules a republish: lane claims include database maintenance, which must not
    // gain a configuration write from here.
    private val softwareProgressObserver: () -> Unit = {
        runCatching { mqtt.publishSoftwareUpdates() }
    }
    private val softwareCatalogObserver: () -> Unit = {
        runCatching { config.setSoftwareUpdateTargets(UpdateChecker.persistableTargets()) }
        runCatching { mqtt.publishSoftwareUpdates() }
    }
    private val mdns: MdnsAdvertiser get() = runtime.current().mdns
    // Default-network callback that nudges an MQTT reconnect when the network returns (see registerNetworkCallback).
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    private data class MdnsRevalidation(
        val observed: ServiceRuntimeOwner.Observation<NetworkRuntime>,
        val lanIp: String?,
    )
    private val mdnsRevalidation = LatestDispatcher.singleSlot<MdnsRevalidation>(
        threadName = "ha-paneld-mdns-revalidation",
        consume = { request ->
            val current = runtime.observe() ?: return@singleSlot
            if (current.generation != request.observed.generation || current.value !== request.observed.value) {
                return@singleSlot
            }
            current.value.mdns.start(request.lanIp)
        },
        onFailure = { failure -> Log.w(TAG, "mDNS revalidation failed", failure) },
    )
    private lateinit var mdnsRuntimeReconciler: MdnsRuntimeReconciler<NetworkRuntime>
    // MQTT watchdog runs on a DEDICATED thread (not Dispatchers.IO), so slow/contended su can't starve it.
    @Volatile private var mqttWatchdogAlive = false
    @Volatile private var mqttWatchdog: OwnedThread? = null
    private val launcherHomePolicyGeneration = java.util.concurrent.atomic.AtomicLong()
    private val launcherHomeFailedGeneration = java.util.concurrent.atomic.AtomicLong(Long.MIN_VALUE)
    @Volatile private var kioskReassert: OwnedThread? = null
    private val wakeOnWaveWorker = SingleFlightExecutor("ha-paneld-wake-on-wave")
    // Adopting a wake nobody here performed must not depend on the broker being up, so it gets its own
    // worker rather than sharing the MQTT sync tick. Single-flight is right for it: the work is
    // idempotent and generation-guarded, so a dropped duplicate costs nothing.
    private var proximityWizard: ProximityWizardCoordinator? = null
    private val screenWakeWorker = SingleFlightExecutor("ha-paneld-screen-reconcile")
    @Volatile private var screenOnReceiver: BroadcastReceiver? = null
    @Volatile private var webViewRebindReceiver: BroadcastReceiver? = null

    /**
     * Whether this panel could repair its own Android System WebView, computed away from the screen.
     *
     * Cached rather than asked for on demand because deciding it costs a privileged probe: a cold
     * [Su.availableCachedIsolated] forks `su` and waits for it, and the caller is a blocked status screen
     * being drawn on the main thread. Null until the first answer lands, which the screen renders as
     * saying nothing rather than as a confident "this panel cannot".
     */
    @Volatile private var webViewRepairCapability: WebViewRepairCapability? = null
    private val lightMqttPublisher = SensorLightPublisher(
        publish = { lux ->
            if (!teardownBoundary.isStopping) {
                runCatching { mqtt.publishLight(lux) }
                    .onFailure { Log.w(TAG, "light MQTT publication failed", it) }
            }
        },
    )
    private lateinit var sensors: SensorReporter
    private lateinit var wifiDiagnostics: AndroidWifiDiagnostics
    private lateinit var wifiOutageTracker: WifiOutageTracker
    private lateinit var logShipper: LogShipper
    private lateinit var logCaptureApp: LogCapture
    private lateinit var logCaptureSystem: LogCapture

    // Controllers are fields so the MQTT bridge can be rebuilt on a panel_id change.
    private lateinit var brightness: BrightnessController
    private lateinit var autoBright: AutoBrightnessController
    private lateinit var autoSleep: AutoSleepController
    private lateinit var haAmbientLux: HaAmbientLuxSubscriber
    private lateinit var haExactEntityStream: HaExactEntityStreamOwner
    private lateinit var panelAssistantTransport: PanelAssistantTransportOwner
    // Outlives bridge generations: each new bridge binds its converger here, and the transport owner
    // reports what it records only on a session Panel Assistant accepts in shadow mode.
    private val panelAssistantShadow = PanelAssistantShadowReporter()

    // Native transport commands reach the current bridge generation's ordered command authority and the
    // same approval broker as every other remote command.
    private val panelAssistantCommands = object : PanelAssistantCommandSink {
        override fun submit(command: PanelAssistantCommand, done: (PanelAssistantCommandResult) -> Unit) {
            val bridge = runCatching { runtime.current().mqtt }.getOrNull()
                ?: return done(PanelAssistantCommandResult.Failed(PanelAssistantCommandProcessor.CODE_FAILED))
            bridge.submitPanelAssistantCommand(command, done)
        }

        override fun approvalState(approvalId: String): PanelAssistantApprovalState =
            when (LocalApprovalBroker.instance.state(approvalId)) {
                ApprovalBroker.State.PENDING -> PanelAssistantApprovalState.PENDING
                ApprovalBroker.State.APPROVED -> PanelAssistantApprovalState.APPROVED
                ApprovalBroker.State.ABSENT -> PanelAssistantApprovalState.ABSENT
            }

        override fun withdrawApproval(approvalId: String) {
            LocalApprovalBroker.instance.deny(approvalId)
        }
    }
    private lateinit var haLifecycle: HaLifecycleCoordinator
    private lateinit var haNetworkPath: HaNetworkPathMonitor
    private lateinit var haPathProbe: PathProbeMonitor
    private val haSocketClock: () -> Long = { android.os.SystemClock.elapsedRealtime() }
    private val rendererSettledForLifecycle: () -> Unit = { runCatching { refreshHaLifecycleWatch() } }
    private lateinit var haSiteMetadata: HaSiteMetadataClient
    private var brightnessObserver: ContentObserver? = null
    private var haCandidateIdentity = ""
    private val adaptiveSiteGeneration = java.util.concurrent.atomic.AtomicLong()
    @Volatile private var lastObservedCommandedBrightness = -1
    private lateinit var screen: ScreenController
    private lateinit var led: LedController
    // Effect loop for the LED — owned here (not the MQTT bridge) so a bridge rebuild never orphans it.
    private lateinit var ledEffect: LedEffectController
    private lateinit var camera: io.github.maxlyth.hapaneld.camera.CameraSessionOwner
    // The RTSP transport for the camera trial. It listens only while the camera switch is on, holds no
    // thread until then, and is drained before the camera owner so no client can reattach mid-stop.
    private lateinit var cameraStream: io.github.maxlyth.hapaneld.camera.CameraRtspServer
    private lateinit var navigate: NavigateController
    private lateinit var volume: VolumeController
    private lateinit var audio: AudioPlaybackCoordinator
    private lateinit var voice: io.github.maxlyth.hapaneld.assist.VoiceAssistantCoordinator
    // One coalesced restart per burst of voice_* changes: a bundle import writes every key in turn and
    // must not rearm the listener once per key.
    @Volatile private var voiceRestart: kotlinx.coroutines.Job? = null
    private val voicePrefsListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key != null && key.startsWith("voice_")) {
            voiceRestart?.cancel()
            voiceRestart = scope.launch {
                kotlinx.coroutines.delay(VOICE_SETTINGS_COALESCE_MS)
                voice.start()
            }
        }
    }
    private lateinit var system: SystemController
    private lateinit var tame: TameController
    private lateinit var navbar: NavbarController
    private lateinit var watchdog: WatchdogController
    private lateinit var kiosk: KioskController
    private lateinit var kioskSettings: KioskSettingCoordinator
    private lateinit var touchSound: TouchSoundController
    private lateinit var bootChime: BootChimeController
    private lateinit var zigbee: ZigbeeController
    private lateinit var zigbeeHealth: ZigbeeHealthMonitor
    private lateinit var relay: RelayController
    private lateinit var cpu: CpuController
    private lateinit var adb: AdbController
    private lateinit var power: PowerController
    private lateinit var powerSafety: PowerSafetyController
    private lateinit var profile: DeviceProfile
    private lateinit var profileRegistry: RuntimeProfileRegistry
    private lateinit var passiveProfileProbe: AndroidPassiveProfileProbe
    private lateinit var profileRestart: ProfileRestartCoordinator
    private lateinit var identityMigration: AndroidIdentityMigration
    private lateinit var recoveryRestart: ProfileRestartCoordinator
    private lateinit var webViewRebindRestart: ProfileRestartCoordinator
    private lateinit var activeProfileIdentity: String
    private var profileActivationGeneration: Long? = null
    // One-time-start guard for onStartCommand (see there for why). Reset in onDestroy.
    @Volatile private var started = false
    private val installedHandoffWake = InstalledSuccessorHandoffWake()
    // This generation was created inside a process that had already committed to exiting, so it owns
    // nothing and must not be torn down as though it did. Never cleared: a stood-down generation stays
    // stood down for as long as the doomed process lives.
    @Volatile private var standingDown = false
    // The text of the notification currently on screen, so re-issuing startForeground to change the
    // claimed service types does not silently rewrite what the panel is telling its owner.
    @Volatile private var foregroundStatusText = "Starting\u2026"
    // Whether the microphone foreground-service type is currently claimed. Only ever true while
    // something holds a microphone lease.
    @Volatile private var microphoneForeground = false
    private val teardownBoundary = ServiceTeardownBoundary()
    private val restartAfterInternalBoundary = AtomicBoolean(false)
    private var upgradeShutdownClaim: UpgradeShutdownClaim? = null
    private val screenExitRecoveryOwner = AtomicReference<CompletableFuture<Boolean>?>(null)
    private val startupRecoveryPrefs by lazy {
        AppState.preferences(this, "startup-recovery", "ha-paneld-startup-recovery")
    }

    override fun onCreate() {
        super.onCreate()
        if (GuardDbProcessAdmission.maintenanceRequired()) {
            // A stale START_STICKY or explicit component intent may still name PaneldService after a
            // package replacement. Promote promptly, hand off to the narrow writer-free service, and
            // return before Config/AppState/profile/controller construction.
            startForegroundCompat(nativeString(R.string.guard_db_activity_label), silent = true)
            guardDbRedirect = true
            GuardDbMaintenanceService.start(this)
            stopSelf()
            return
        }
        when (IdentityMigrationGate.disposition(this)) {
            StartDisposition.RETIRED_BRIDGE -> {
                // This bridge handed the panel to the successor. A sticky restart, a boot or an explicit
                // intent must never bind the port or resume a kiosk loop beside it.
                startForegroundCompat(nativeString(R.string.starting), silent = true)
                identityMigrationStandby = true
                stopSelf()
                return
            }
            StartDisposition.PASSIVE_SUCCESSOR -> {
                // The legacy app still owns the panel. Run only the migration, with no Config, server,
                // MQTT, mDNS or controller, until the legacy app has released it.
                startForegroundCompat(nativeString(R.string.starting), silent = true)
                identityMigrationStandby = true
                identityMigrationSticky = true
                SuccessorMigrationRunner.runPassive(this, scope)
                return
            }
            StartDisposition.HELD_SUCCESSOR, StartDisposition.NORMAL -> Unit
        }
        // Android starts the foreground-service deadline before onCreate. Promote before profile/DB/
        // controller construction: slow root-backed initialization must never consume that deadline.
        // The bootstrap notification is deliberately silent until persisted notification policy is read.
        startForegroundCompat(nativeString(R.string.starting), silent = true)
        // stopSelf() clears START_STICKY, so an app-internal boundary has to re-arm its own start
        // request before exiting, and Android delivers that request into the still-live process. The
        // generation it creates here cannot outlive the exit: it could only claim the staged profile
        // activation, open the database and attach hardware owners alongside the outgoing runtime, then
        // die before proving any of it healthy — which is precisely what left the fresh process an
        // orphaned APPLYING to roll back. Stand down before any of that exists.
        if (PROCESS_BOUNDARY_COMMITMENT.admitServiceGeneration() == ServiceGenerationAdmission.STAND_DOWN) {
            standDownForCommittedProcessBoundary()
            return
        }
        restartLease = SERVICE_RESTART_BARRIER.enter()
        config = Config(this)
        config.migrateLiveStore()   // carry persisted settings across a schema bump before anything reads them
        // Must run BEFORE ensurePanelId and before any renderer starts: it decides, once, whether this panel
        // predates the entity-filter question, and a panel that predates it must never be held to answer it.
        config.migrateLogShipTcpDefault()
        config.migrateAutoSleepSource()
        config.migrateSetupQuestionsForExistingInstall()
        // Must run BEFORE ensurePanelId: a generated panel id takes its suffix from this identity, and
        // this is also where a panel decides, once, whether it owes Home Assistant a final publication
        // of the legacy Android-id device identifier.
        config.ensureDeviceUid()
        config.ensurePanelId()      // materialize the generated identity before MQTT/mDNS snapshot it
        reconcileNativePresentationAfterPromotion()
        // Same reason, same window: Application.onCreate only registered the Shizuku Binder listeners,
        // because reading its consent opens the database. Derive the bridge's real state here, after
        // the promote, exactly as the locale/night-mode correction above is.
        ShizukuBridge.activateAfterPromotion()
        updateForegroundStatus(nativeString(R.string.starting))
        liveSettingAuthority = LiveSettingAuthority.persistent(this, MqttBridge.APPLY_SETTING_KEYS)
        // Resolve one immutable profile revision before constructing any hardware owner. Activations are
        // restart-bound, so every controller below observes this exact object for the service lifetime.
        profileRegistry = RuntimeProfileRegistry(this)
        val resolvedProfile = profileRegistry.resolveForStartup()
        profile = resolvedProfile.profile
        activeProfileIdentity = resolvedProfile.summary.ref.let { "${it.id}@${it.revision}" }
        profileActivationGeneration = resolvedProfile.activationGeneration
        val provisioningReader = ProvisioningCoordinator(
            core = ProvisioningCoreIdentity(
                version = BuildConfig.VERSION_NAME,
                versionCode = BuildConfig.VERSION_CODE,
            ),
            profile = resolvedProfile.toProvisioningProfile(),
            collector = AndroidProvisioningObservationCollector(this),
            monotonicMs = { android.os.SystemClock.elapsedRealtime() },
        )
        resolvedProfile.issues.forEach { issue ->
            Log.w(TAG, "profile ${issue.severity.name.lowercase()} ${issue.path}: ${issue.message}")
        }
        passiveProfileProbe = AndroidPassiveProfileProbe(this)
        mainHandler = Handler(Looper.getMainLooper())
        profileRestart = ProfileRestartCoordinator(
            schedule = { delayMs, action -> mainHandler.postDelayed(action, delayMs) },
            restartProcess = {
                // START_STICKY is already the field-established restart route used for WebView
                // replacement. It avoids Android 12+'s background foreground-service start ban.
                requestSafeProcessBoundary("activating staged profile")
            },
            safeToRestart = { !InstallProgress.running &&
                !GuidedSetupPresence.activelyWalked(android.os.SystemClock.elapsedRealtime()) },
            shouldAbandon = { teardownBoundary.isStopping },
        )
        recoveryRestart = ProfileRestartCoordinator(
            schedule = { delayMs, action -> mainHandler.postDelayed(action, delayMs) },
            restartProcess = {
                requestSafeProcessBoundary("bounded runtime recovery")
            },
            safeToRestart = { !InstallProgress.running &&
                !GuidedSetupPresence.activelyWalked(android.os.SystemClock.elapsedRealtime()) },
            shouldAbandon = { teardownBoundary.isStopping },
            responseGraceMs = RECOVERY_RESTART_GRACE_MS,
        )
        // A WebView provider binds once per process, so the only way a panel parked on "Secure dashboard
        // bridge unavailable" can ever see a newly installed engine is on the far side of a process
        // boundary — the same route activateWebView already takes for the installs ha-paneld performs
        // itself. Single-flight, so one provider install asks exactly once, and it survives the wait:
        // see webViewRebindRestartCoordinator for why nothing except teardown may discard it.
        webViewRebindRestart = webViewRebindRestartCoordinator(
            schedule = { delayMs, action -> mainHandler.postDelayed(action, delayMs) },
            restartProcess = {
                requestSafeProcessBoundary("binding a newly installed WebView provider")
            },
            destructiveOperationRunning = { InstallProgress.running },
            guidedSetupBeingWalked = {
                GuidedSetupPresence.activelyWalked(android.os.SystemClock.elapsedRealtime())
            },
            serviceStopping = { teardownBoundary.isStopping },
        )
        config.attachProfile(profile)   // supplies per-panel manufacturer/model defaults
        appliedNetworkConfiguration = currentNetworkConfigurationSnapshot()
        sensors = SensorReporter(this, config, profile)
        wifiOutageTracker = WifiOutageTracker(store = AndroidWifiOutageStore(this))
        wifiDiagnostics = AndroidWifiDiagnostics(this) {
            Su.runOutputIsolatedBounded(
                "cmd wifi status 2>/dev/null || dumpsys wifi",
                maxBytes = 128 * 1024L,
                timeoutMs = 2_000L,
            )
        }
        // Shared demand-driven logcat captures (one subprocess + one redaction pass each): the app
        // source feeds both remote shipping and the :8888 live log viewer; the system source (su)
        // only the viewer. Idle-stopped — no subprocess runs until something subscribes.
        logCaptureApp = LogCapture.app(scope)
        logCaptureSystem = LogCapture.system(scope)
        // Optional remote log shipping (off + inert unless a sink host is configured). Started in
        // onStartCommand alongside the other network subsystems; restarted on a /config change.
        logShipper = LogShipper(config, scope, logCaptureApp)

        brightness = BrightnessController(this)
        screen = ScreenController(
            brightness,
            AndroidScreenPower(this),
            wakeTap = OverlayWakeTap(this),
            route = profile.screenOff,
        )
        autoBright = AutoBrightnessController(this, brightness, config, screen::actuateBrightnessIfOn)
        haSiteMetadata = HaSiteMetadataClient(config)
        val haSessionAuthority = DashboardHaApiSessionProvider(config)
        val haApi = KtorHaAmbientTransport()
        // The layer-3 probe is constructed before the transport so the transport can report the
        // address each route connects on straight to it. It measures the path the dashboard is
        // actually using; a fresh resolution could name a family the connect race already rejected.
        haPathProbe = PathProbeMonitor(
            source = IcmpEchoSource(),
            // The layer-3 verdict goes straight to the one owner of the prominent warning, so a
            // measured path fault raises the banner and the panel chip rather than sitting in a
            // diagnostics line nobody reads until they are already debugging.
            // Guarded: the probe is constructed first, and a diagnostic must never be the thing
            // that crashes the service it is diagnosing.
            onVerdict = { severity, cause ->
                if (::haNetworkPath.isInitialized) haNetworkPath.onPathProbeVerdict(severity, cause)
            },
        )
        haExactEntityStream = HaExactEntityStreamOwner(
            scope = scope,
            auth = haSessionAuthority,
            transport = KtorHaExactEntityStreamTransport(
                haApi,
                socketFamilyPolicy = { MqttAddressFamilyPolicy.fromConfig(config.mqttAddressFamily) },
                monotonicMillis = haSocketClock,
                onRouteConnected = { address -> haPathProbe.onRouteConnected(address) },
            ),
            monotonicMillis = haSocketClock,
        )
        // The native transport's own long-lived socket, on the same credential authority as the stream
        // above and the same address-family policy as every other Home Assistant socket.
        panelAssistantTransport = PanelAssistantTransportOwner(
            scope = scope,
            auth = haSessionAuthority,
            connector = KtorPanelAssistantTransportConnector(
                socketFamilyPolicy = { MqttAddressFamilyPolicy.fromConfig(config.mqttAddressFamily) },
            ),
            monotonicMillis = haSocketClock,
            shadow = panelAssistantShadow,
            commands = panelAssistantCommands,
            onAuthority = config::setPanelAssistantAuthority,
            authority = config::panelAssistantAuthority,
            mqttDiscovery = config::panelAssistantMqttDiscovery,
            onMqttDiscovery = { value ->
                config.setPanelAssistantMqttDiscovery(value)
                runtime.observe()?.value?.mqtt?.refreshPanelAssistantDiscovery()
            },
            embedKeys = io.github.maxlyth.hapaneld.http.PanelAssistantEmbedKeys.instance,
        )
        haLifecycle = HaLifecycleCoordinator(
            // elapsedRealtime, not wall clock: a Home Assistant restart is exactly when NTP is likely to
            // step the panel's clock, and the back-online window must not be shortened or extended by it.
            nowMs = { android.os.SystemClock.elapsedRealtime() },
            onChanged = {
                // Read the canonical snapshot rather than trusting a payload, so a diagnostic line
                // cannot report something the machine no longer holds.
                Log.i(TAG, "Home Assistant lifecycle: ${HaLifecycleRuntime.snapshot()?.state?.wireValue}")
                BuiltinDashboard.onHaLifecycleChanged()
            },
        )
        // The network-path monitor rides the same socket, the same clock and the same renderer poke.
        // Installed BEFORE it is bound, because binding announces the current demand at once and that
        // first verdict must already be readable by whoever the poke wakes.
        haNetworkPath = HaNetworkPathMonitor(
            nowMs = haSocketClock,
            // Second attribution signal: while Home Assistant has announced a shutdown or a start
            // (over the socket, or the broker will on a non-admin account), an unanswered probe is
            // the server's doing and must not be counted as loss. Read outside the monitor's lock.
            haRestarting = {
                when (HaLifecycleRuntime.snapshot()?.state) {
                    HaLifecycleState.SHUTTING_DOWN, HaLifecycleState.STARTING -> true
                    else -> false
                }
            },
            onChanged = { BuiltinDashboard.onHaLifecycleChanged() },
            // Startup is a known high-latency period whose figures describe this panel's own load
            // rather than the path, so observations inside it are discarded rather than judged. The
            // process start is read on the same monotonic clock every probe is stamped with.
            path = HaNetworkPath(processStartElapsedMs = android.os.Process.getStartElapsedRealtime()),
        )
        haAmbientLux = HaAmbientLuxSubscriber(
            scope = scope,
            auth = haSessionAuthority,
            transport = haApi,
            streamOwner = haExactEntityStream,
            onSample = { sample -> autoBright.submitHaLux(sample.lux) },
            onStatus = { status ->
                autoBright.setHaSourceAvailable(status.phase == HaAmbientSourcePhase.LIVE)
            },
        )
        screen.onWakeCompleted = {
            scope.launch(Dispatchers.Default) { autoBright.reapplyLatest() }
            if (::autoSleep.isInitialized) autoSleep.noteScreenWoken()
        }
        // After a LOCAL touch-wake, tell HA the screen is on so `light.<panel>_screen` tracks reality.
        screen.onWakeByTap = { automaticEpoch ->
            // Manual tap-wakes are already observed by the shared raw-touch listener; only an exact
            // automatic epoch is additional causal proof for the auto-sleep learner.
            if (automaticEpoch != null && ::autoSleep.isInitialized) autoSleep.noteTapWake(automaticEpoch)
            runCatching { mqtt.publishScreenOn() }
        }
        led = LedFactory.detect(profile)
        ledEffect = LedEffectController(led)
        // Camera trial. Owned here, beside the LED it borrows for off-screen indication and the screen
        // whose intended-off state decides the handover; nothing runs until a subscriber asks for a frame.
        // The stream transport reads the owner through the FIELD so construction order does not matter:
        // it only asks for the camera once a client connects, which cannot happen before the owner
        // exists because the owner is what turns listening on.
        cameraStream = io.github.maxlyth.hapaneld.camera.CameraRtspServer(
            source = { camera },
            log = { Log.w(TAG, "camera stream: $it") },
        )
        camera = io.github.maxlyth.hapaneld.camera.CameraSessionOwner(
            context = this,
            hasCamera = { cameraPresent() },
            capabilityReason = { cameraReason() },
            enabled = { config.cameraEnabled },
            defaultResolution = { config.cameraResolution },
            defaultFps = { config.cameraFps },
            defaultKbps = { config.cameraKbps },
            exposureEv = { config.cameraExposureEv },
            transport = cameraStream,
            permissionGranted = {
                androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
            },
            indicator = io.github.maxlyth.hapaneld.camera.CameraIndicator(
                context = this,
                ledEffect = ledEffect,
                // Re-derive from persisted intent through whichever bridge is current, never a snapshot.
                restoreLed = { runCatching { runtime.current().mqtt.reapplyStoredLed() } },
                screenOff = { screen.isIntendedOff() },
                cameraLensOffsetPx = profile.cameraLensOffsetPx,
            ),
            foreground = io.github.maxlyth.hapaneld.camera.AndroidCameraForegroundGate(this),
        )
        navigate = NavigateController(this)
        volume = VolumeController(this)
        audio = AudioPlaybackCoordinator(
            AudioPlayer.factory(cacheDir),
            onFailure = { error -> Log.w(TAG, "audio playback failed: ${error.javaClass.simpleName}") },
        )
        // Arms only when the setting is on and the profile declares a microphone. The profile is the
        // authority: the platform feature flag reports one on hardware that captures silence. No
        // wake-word listener ships yet, so the feature is press-to-speak: it holds no microphone while
        // idle and takes one only for the length of a run.
        voice = io.github.maxlyth.hapaneld.assist.voiceAssistantCoordinator(
            context = this,
            config = config,
            scope = scope,
            audio = audio,
            microphoneAvailable = { profile.hasMicrophone },
            foregroundMicrophone = ::setMicrophoneForegroundActive,
            state = voiceStateAuthority,
            engineFactory = io.github.maxlyth.hapaneld.assist.WakeWordEngineFactory.NONE,
        )
        system = SystemController(AndroidSystemEnv(this))
        companionDataOperationState = CompanionDataOperationState.from(this)
        entityLearning = EntityLearningManager(
            context = this,
            config = config,
            scope = scope,
            // The runtime owner is initialized here; onStartCommand starts its mDNS instance before the
            // learner can synchronize, so UUID discovery has a live resolver from the first attempt.
            resolveInstanceUuid = { urls -> runtime.current().mdns.discoverHaInstanceUuid(urls) },
            onFilterChanged = {
                // Synchronization can finish while another renderer is deliberately selected (for example
                // during a staged cutover). Persist the learned set, but never let that background work
                // launch DashboardActivity behind the configured renderer's back.
                if (shouldReloadBuiltinAfterEntityFilterChange(
                        system.resolveDashboard(config.dashboardPackage),
                        SystemController.BUILTIN_DASHBOARD,
                        setupEntityFilterAnswered = config.setupEntityFilterAnswered,
                        setupEverCompleted = config.setupEverCompleted,
                    )) {
                    system.reloadDashboard(
                        SystemController.BUILTIN_DASHBOARD,
                        reason = "applying the entity filter",
                    )
                }
            },
        )
        watchdog = WatchdogController(system, config)
        kiosk = KioskController(this, system, config, profile.appCanSu)
        kioskSettings = KioskSettingCoordinator(
            canEnable = kiosk::canEnablePersistentPolicy,
            actuate = kiosk::apply,
            persist = config::commitKioskLock,
        )
        // On-device unlock gesture (7 corner taps): persist OFF + clear the lock + tell HA — off the main
        // thread (the gesture fires on the overlay's touch listener; root + HiveMQ must not run there).
        kiosk.onUnlockRequested = {
            Thread {
                if (teardownBoundary.isStopping) return@Thread
                kioskSettings.apply(false) {
                    if (!teardownBoundary.isStopping) mqtt.publishKioskState()
                }
            }.apply { isDaemon = true; name = "kiosk-unlock" }.start()
        }
        // When taming disables a vendor home launcher (e.g. eWeLink), immediately re-assert the dashboard
        // as home and foreground it — otherwise the panel sits on our admin launcher until the 300s
        // watchdog returns to the dashboard (a ~5-minute gap on boot). Activation is deferred until the
        // predecessor service has left the process; tame() itself runs in the service scope off-main.
        tame = TameController(this) {
            system.applyLauncherHomePolicy(
                config.launcherPackage,
                config.dashboardPackage,
                config.haUrl.isNotBlank(),
            )
            system.launchHome(config.dashboardPackage)
        }
        // The navbar gets the CONFIGURED dashboard value (may be the "builtin" sentinel), never
        // dashboardTarget(): that resolves builtin to our own package name (for perf attribution), and
        // reloadDashboard(ownPackage) would take the foreign-app path — `am force-stop` on ourselves.
        navbar = NavbarController(
            this, system, volume, brightness, { config.launcherPackage }, { config.dashboardPackage },
            profile.appCanSu, profile.hasRecents,
            onBrightnessChanged = { level ->
                autoBright.noteExternalBrightness(level, BrightnessPreferenceOrigin.PANEL_CONTROLS)
            },
            onBrightnessApplied = { mqtt.publishScreenOn() },
            onVolumeChanged = { mqtt.publishVolume() },
        )
        touchSound = TouchSoundController(this, profile.touchClickGain)
        bootChime = BootChimeController(this, config)
        zigbee = ZigbeeController(profile)
        zigbeeHealth = ZigbeeHealthMonitor(
            configuredOn = { config.zigbeeRouterConfigured && config.zigbeeRouterEnabled },
            source = AndroidZigbeeGatewayHealthSource(profile.zigbeeGatewayDir, zigbee),
            onContain = {
                config.setZigbeeRouterEnabled(false)
                runCatching { mqtt.publishZigbeeRouterState() }
            },
            onSnapshot = { snapshot, _ ->
                runCatching { mqtt.publishZigbeeHealth(snapshot) }
            },
        )
        relay = RelayController(profile)
        cpu = CpuController(profile)
        adb = AdbController(this, config)
        power = PowerController(this)
        powerSafety = PowerSafetyController(
            context = this,
            power = power,
            screenOffMechanism = profile.screenOff.name.lowercase(),
            screenRouteSelection = screen::routeSelection,
            directRootExpected = profile.appCanSu,
        )

        autoSleep = AutoSleepController(
            context = this,
            scope = scope,
            config = config,
            screen = screen,
            onScreenChanged = {
                if (::runtime.isInitialized && !teardownBoundary.isStopping) {
                    runCatching { mqtt.publishScreenState() }
                }
            },
            onProjectionChanged = {
                if (::runtime.isInitialized && !teardownBoundary.isStopping) {
                    runCatching { runtime.observe()?.value?.mqtt?.publishAutoSleepActivity() }
                }
            },
            onNoArea = { expectedEpoch ->
                if (::runtime.isInitialized && !teardownBoundary.isStopping) {
                    val expectedSettingGeneration = config.autoSleepGeneration
                    val admitted = liveSettingAuthority.applyOrQueueIf(
                        key = "auto_sleep",
                        value = "false",
                        previousValue = "true",
                        fence = expectedSettingGeneration + 1L,
                        expected = {
                            config.autoSleep && config.autoSleepGeneration == expectedSettingGeneration &&
                                autoSleep.isCurrentConfigurationEpoch(expectedEpoch)
                        },
                    ) { _, _, _ ->
                        applyAutoSleepAreaFailOff(
                            expectedEpoch = expectedEpoch,
                            expectedSettingGeneration = expectedSettingGeneration,
                            epochIsCurrent = autoSleep::isCurrentConfigurationEpoch,
                            persistOff = { generation ->
                                config.setAutoSleepIf(
                                    expected = true,
                                    expectedGeneration = generation,
                                    on = false,
                                )
                            },
                            refreshController = { refreshAutoSleepPresence() },
                            applyBridge = {
                                runtime.observe()?.value?.mqtt?.convergeAutoSleep(
                                    expectedGeneration = expectedSettingGeneration + 1L,
                                    expectedValue = false,
                                ) ?: LiveSettingApplyResult.DEFERRED
                            },
                        )
                    }
                    if (!admitted) {
                        Log.w(TAG, "stale or failed auto-sleep Area-removal fail-off was not admitted")
                    }
                }
            },
            sourceManagerFactory = { offerAggregate ->
                HaPresenceSourceManager(
                    scope = scope,
                    config = config,
                    streamOwner = haExactEntityStream,
                    offerAggregate = offerAggregate,
                )
            },
        )

        runtime = ServiceRuntimeOwner(
            initial = NetworkRuntime(
                buildMqtt(appliedNetworkConfiguration.runtime),
                buildMdns(appliedNetworkConfiguration.runtime),
            ),
            threadName = "ha-paneld-runtime",
            onError = { operation, error -> Log.e(TAG, "runtime $operation failed", error) },
            onRecoverySaturated = {
                if (!teardownBoundary.isStopping && !recoveryRestart.request()) {
                    Log.w(TAG, "runtime recovery restart is already scheduled")
                }
            },
            latestOperation = LatestOperationPolicy(
                name = "network-reconfigure",
                timeout = LatestOperationTimeoutPolicy(
                    budgetMs = NETWORK_RECONFIGURE_BUDGET_MS,
                    schedule = { task, delayMs -> mainHandler.postDelayed(task, delayMs) },
                    cancel = mainHandler::removeCallbacks,
                    onTimeout = ::onNetworkReconfigureTimeout,
                ),
                operation = { performNetworkReconfigure(this) },
            ),
        )
        mdnsRuntimeReconciler = MdnsRuntimeReconciler(runtime, ::revalidateMdns)
        rendererPreparation = RendererPreparationCoordinator(
            builtinPackage = SystemController.BUILTIN_DASHBOARD,
            state = { RendererPreparationState(
                config.dashboardPackage,
                config.haUrl,
                config.rendererLaunchPending,
            ) },
            borrow = borrow@{
                val login = CompanionDb.readLogin(this, Su) ?: return@borrow null
                BorrowedRendererSettings(
                    url = login.url,
                    accessToken = login.accessToken,
                    refreshToken = login.refreshToken,
                    tokenExpiry = login.expirySec,
                    clientId = CompanionDb.COMPANION_CLIENT_ID,
                    zoom = CompanionDb.readPageZoom(this, Su)?.coerceIn(50, 300),
                )
            },
            persist = { borrowed ->
                commitBorrowedRendererTarget(
                    commit = {
                        config.setBorrowedRendererSettings(
                            url = borrowed.url,
                            accessToken = borrowed.accessToken,
                            refreshToken = borrowed.refreshToken,
                            tokenExpiry = borrowed.tokenExpiry,
                            clientId = borrowed.clientId,
                            zoom = borrowed.zoom,
                        )
                    },
                    onCommitted = entityLearning::onTargetConfigurationChanged,
                )
            },
            completeLaunch = config::completeRendererLaunch,
        )
        identityMigration = AndroidIdentityMigration(
            context = this,
            scope = scope,
            httpPort = { config.httpPort },
            androidId = { config.androidId },
            mqttState = { runtime.current().mqtt.state },
            offerHandoff = { offerSuccessorHandoff() },
            // The restore is durable; only a fresh process runs wholly from the restored configuration.
            requestRestart = { recoveryRestart.request() },
        )
        server = PaneldServer(
            config, cacheDir, scope, this, sensors, profile, system, volume, audio::submit, ::reconfigure,
            // The bridge is replaceable. If its command admission is already draining, retain the
            // persisted setting and replay it against the replacement before reconfigure completes.
            { k, v ->
                if (k == "kiosk_lock") {
                    SettingValue.parseBool(v)?.let { on ->
                        if (kioskSettings.apply(on) { mqtt.publishKioskState() }) {
                            LiveSettingRequestOutcome.APPLIED
                        } else {
                            LiveSettingRequestOutcome.REJECTED
                        }
                    } ?: LiveSettingRequestOutcome.REJECTED
                } else {
                    liveSettingAuthority.applyOrQueueOutcomeObserved(
                        key = k,
                        value = v,
                        previousValue = previousLiveSettingValue(k),
                    ) { key, value, previous -> applyLiveSettingObserved(mqtt, key, value, previous) }
                }
            },
            onProximityCalibration = { action, id -> proximityWizard?.remote(action, id) == true },
            pendingLiveSettings = liveSettingAuthority::pendingSnapshot,
            stalledLiveSettings = liveSettingAuthority::pendingStalledSnapshot,
            assistPipelines = io.github.maxlyth.hapaneld.assist.HaAssistPipelineDirectory(config),
            voiceTest = io.github.maxlyth.hapaneld.assist.VoiceTestTrigger { voice.trigger() },
            // Controller-sourced setting values (their state isn't in the config namespace) so the
            // config form/schema/dashboard show live truth. Called on Ktor IO threads (su-safe).
            configLiveValues = ::currentConfigLiveValues,
            managementProjection = ::managementProjection,
            recommendedDensity = profile.recommendedDensity,
            recommendedFontScale = profile.recommendedFontScale,
            // Vendor taming: the controller and this panel's curated recommendations (picker group 1).
            tame = tame, tameProfileCandidates = profile.tameVendorCandidates,
            // Live log viewer sources (Logs tab). System is gated on Su.available() per request.
            logApp = logCaptureApp, logSystem = logCaptureSystem,
            logShipStatus = logShipper::status,
            effectiveBrightness = { brightness.getBrightness() },
            onRepairCompanionUrl = { repairCompanionUrl() },
            onInstallComponent = { name, action, version -> installComponent(name, action, version) },
            // Panel Assistant declares that it owns the ha-paneld update entity on its status poll.
            onPanelAssistantUpdateOwner = {
                if (panelAssistantUpdateLease.observe()) runCatching { mqtt.publishSoftwareUpdates() }
            },
            prepareSelfUpdateChannel = ::prepareSelfUpdateChannel,
            onSelfUpdateChannelCommitted = ::completeSelfUpdateChannelChange,
            powerSafety = { powerSafety.assess(config.keepAwake, config.preventIdleDim) },
            freshPowerSafetyRepairCapability = powerSafety::repairCapabilityFresh,
            onRepairPowerSafety = ::repairPowerSafety,
            // One-line EFR32 radio status for the Install-tab Radio card; null when this panel has no radio.
            radioStatus = { if (profile.zigbeeGatewayDir != null) zigbeeHealth.snapshot() else null },
            camera = camera,
            panelAssistantTransportFacts = { panelAssistantTransport.facts() },
            releasePanelAssistantTransport = { panelAssistantTransport.releaseToMqtt() },
            // Captures the FIELD, not a snapshot, so it follows reconfigure()'s bridge reassignment.
            // A bridge generation built from credentials that no longer match the persisted config is
            // mid-swap: whatever state it reports was earned by the OLD credentials (on a fresh panel,
            // the anonymous discovery connect's auth rejection), so report the truthful transient
            // instead — the wizard was blaming freshly-typed correct credentials for it.
            mqttState = {
                val bridge = runtime.current().mqtt
                if (bridge.servesMqttConfiguration(
                        config.mqttBroker,
                        config.mqttUser,
                        config.mqttPassword,
                        config.mqttAddressFamily,
                    )
                ) {
                    bridge.state
                } else "connecting"
            },
            onZigbeeJoinRetry = { mqtt.requestZigbeeJoin() },
            // LAN ha-paneld peers over mDNS for the header panel switcher. Captures the `mdns` FIELD (not a
            // snapshot) so it follows reconfigure()'s reassignment; browsePeers null-guards the swap window.
            peers = { mdns.browsePeers() },
            // Report the current advertiser rather than a startup snapshot: DHCP may not have completed
            // when the service began, and the operator needs to see a later failed/deferred advertisement.
            mdnsWarningProjection = {
                val health = mdns.health()
                mdnsHealthWarning(health) to mdnsHealthPresentation(health)
            },
            configDiscoverySuggestions = {
                val active = runtime.current()
                val existingOrActiveBroker = config.mqttBroker.ifBlank { active.mqtt.activeBroker }
                val haDiscovery = active.mdns.discoverHaBaseUrlDetailed(existingOrActiveBroker)
                val discoveredHa = haDiscovery.value
                val broker = config.mqttBroker.ifBlank {
                    // Hostname form FIRST: the auto-connected bridge's activeBroker is always a raw
                    // IPv4 (discoverHaIp), and letting it shadow the hostname suggestion pinned fresh
                    // panels to one address family. Safe against multi-instance LANs because
                    // discoverHaBaseUrlDetailed already selects the HA whose addresses match the active
                    // broker, so a non-empty suggestion names the same machine by its advertised host.
                    mqttBrokerSuggestionFromHaUrl(discoveredHa)
                        ?: active.mqtt.activeBroker.ifBlank {
                            active.mdns.discoverHaIp()?.let { "tcp://$it:1883" } ?: ""
                        }
                }
                ConfigDiscoverySuggestions(
                    mqttBroker = broker,
                    haUrl = discoveredHa,
                    haDiscovery = haDiscovery,
                )
            },
            rendererPreparation = rendererPreparation,
            entityLearning = entityLearning,
            autoBrightnessHttpApi = adaptiveBrightnessHttpApi(),
            autoSleepHttpApi = object : AutoSleepHttpApi {
                override fun statusJson(): String = autoSleep.statusJson()
                override suspend fun historyJson(hours: Int): String = autoSleep.historyJson(hours)
                override suspend fun prerequisite() = autoSleep.prerequisite()
                override fun setSourceIncluded(areaKey: String, sourceKey: String, included: Boolean) =
                    autoSleep.setSourceIncluded(areaKey, sourceKey, included)
                override fun noteAreaChanged() {
                    refreshAutoSleepPresence()
                }
            },
            companionDataOperationState = companionDataOperationState,
            profileAdmin = profileRegistry,
            profileTemplate = {
                val report = passiveProfileProbe.report()
                ProfileDraftFactory.create(report.facts, report).rawYaml
            },
            profileDeviceDraft = {
                passiveProfileProbe.report().takeIf { profile.id == "generic" }
                    ?.let(ProfileDraftFactory::fromReport)
            },
            profileReport = passiveProfileProbe::report,
            profileProbe = { passiveProfileProbe.report() },
            onProfileRestart = { profileRestart.request() },
            onDurableStateRestored = { wifiOutageTracker.adoptRestoredRecord() },
            profileRestartAllowed = { !InstallProgress.running },
            onProfileRestartAbort = profileRegistry::abortPendingActivation,
            identityMigration = identityMigration,
            provisioningReader = provisioningReader,
            provisioningActivation = {
                val status = profileRegistry.status()
                ProvisioningActivationSnapshot(
                    phase = status.activation.phase,
                    activeRef = status.active?.ref,
                    generation = status.activation.generation,
                )
            },
            storageHealth = StorageHealthRuntime::snapshot,
            refreshStorageHealth = ::refreshStorageHealthForStatus,
        )
        storageHealthSubscription = StorageHealthRuntime.subscribe(::onStorageHealthSnapshot)
        sensors.setLearnedProximityListener {
            server.invalidateCapabilitySnapshot()
            runtime.observe()?.value?.mqtt?.notifyLearnedProximityChanged()
        }
        sensors.setLightAvailabilityListener {
            server.invalidateCapabilitySnapshot()
            runtime.observe()?.value?.mqtt?.notifyLightAvailabilityChanged()
        }
        // Resolves the CURRENT bridge generation rather than closing over one, so a bridge rebuild
        // (reconfigure) never leaves a stale generation publishing a superseded voice_state.
        voiceStateAuthority.setChangeListener {
            runtime.observe()?.value?.mqtt?.publishVoiceState()
        }
    }

    private fun refreshAutoSleepPresence(): Boolean {
        val accepted = autoSleep.refresh()
        if (accepted && ::sensors.isInitialized) {
            autoSleep.noteProximityState(
                sensors.proximityPresenceNear().takeIf { sensors.proximityPresenceReady() },
            )
        }
        return accepted
    }

    private fun buildMqtt(
        identity: NetworkRuntimeIdentity,
        stalePanelId: String? = null,
    ): MqttBridge {
        val credentials = identity.mqttCredentials()
        // One lease per bridge generation. Registering it retires the previous generation's
        // MQTT-sourced lifecycle claims — the birth that would retract them is not retained, so the
        // replacement channel gets no replay — and makes every superseded bridge's queued callback a
        // no-op. The connection read is derived from the live bridge, never copied.
        val lease = HaLifecycleRuntime.MqttLease()
        if (::haLifecycle.isInitialized &&
            HaLifecycleRuntime.installMqttLease(haLifecycle, lease) {
                runtime.observe()?.value?.mqtt?.isConnected() == true
            }
        ) {
            BuiltinDashboard.onHaLifecycleChanged()
        }
        return MqttBridge(
            config, brightness, screen, led, ledEffect, navigate, volume, system, navbar, watchdog, touchSound, bootChime, zigbee, relay, cpu, adb,
            accessibilityEnabled(), profile.evdevButtons.isNotEmpty(),
            { capabilitiesSnapshot() },
            sensors.hasProximity(),
            sensors.hasTemperature(), sensors.hasHumidity(),
            profile.hasCht8305,
            // Button backlight is a distinct profiled node (TPA10), not a property of the RGB backend:
            // SMT1019 also uses SocketLedController for RGB but has no button-backlight node.
            profile.hasButtonBacklight,
            hasMicrophone = profile.hasMicrophone,
            hasCamera = { cameraPresent() },
            autoBright = autoBright,
            onAutoBrightnessConfigChanged = { refreshAdaptiveBrightnessInputs() },
            autoSleepActivity = { autoSleep.activitySnapshot() },
            configUrl = { localIpv4()?.let { "http://$it:${identity.httpPort}/" } },
            // Where Home Assistant fetches a still from. Re-derived per announcement like configUrl, so a
            // DHCP move cannot leave the image entity pointing at an address this panel no longer holds.
            cameraSnapshotUrl = {
                localIpv4()?.let {
                    io.github.maxlyth.hapaneld.util.LocalAdminEndpoint.url(
                        it, identity.httpPort, "/api/v1/camera/snapshot.jpg",
                    )
                }
            },
            // A camera switch commanded from Home Assistant performs the same actuation the reconfigure
            // fan-out performs for a Configure-page change, so the owner has one authority, not two.
            onCameraEnabledChanged = { if (::camera.isInitialized) runCatching { camera.onEnabledChanged() } },
            // When no broker is configured, find HA on the LAN via mDNS and default to its :1883.
            discoverHaIp = { mdns.discoverHaIp() },
            // HA's advertised base URL (from zeroconf) for the "Open in HA" device link.
            discoverHaUrl = { broker -> mdns.discoverHaBaseUrl(broker) },
            // Companion install/update button → run off-thread (network + su).
            onUpdateCompanion = {
                if (!installComponent("companion", "reinstall", "")) {
                    Log.w(TAG, "Companion manual update skipped: another destructive operation is running")
                }
            },
            // ha-paneld self-update (off-thread): force=true from the update_paneld button + a pre-release→
            // stable channel switch; force=false lets isNewer gate (no auto-downgrade off an rc).
            onSelfUpdate = { force ->
                if (!installComponent("paneld", if (force) "reinstall" else "update", "")) {
                    Log.w(TAG, "self-update skipped: another destructive operation is running")
                }
            },
            onSelfUpdateChannelChange = ::launchSelfUpdateChannelChange,
            softwareUpdateSources = ::softwareUpdateSources,
            // An admitted update-entity install names one exact catalog tag, so it runs the same
            // signer-pinned, database-admitted exact-version path as the Install page's version picker.
            onSoftwareInstall = { component, tag -> installComponent(component.wire, "update", tag) },
            onDashboardTargetChanged = entityLearning::onTargetConfigurationChanged,
            onDirectKioskSetting = { on ->
                kioskSettings.apply(on)
            },
            onExternalSettingApplied = liveSettingAuthority::discard,
            zigbeeHealth = zigbeeHealth::snapshot,
            storageHealth = StorageHealthRuntime::snapshot,
            onZigbeeExplicitRetry = zigbeeHealth::explicitRetry,
            voiceState = { voiceStateAuthority.current().wireValue },
            stalePanelId = stalePanelId,
            profileIdentity = activeProfileIdentity,
            profileButtonEventTypes = profile.evdevButtons.mapTo(linkedSetOf()) { it.eventType },
            runtimePanelId = identity.panelId,
            runtimeFriendlyName = identity.friendlyName,
            runtimeBroker = credentials.broker,
            runtimeMqttUser = credentials.user,
            runtimeMqttPassword = credentials.password,
            runtimeMqttAddressFamily = credentials.addressFamily,
            wifiDiagnostics = wifiDiagnostics::snapshot,
            wifiOutages = { wifiOutageTracker.counts() },
            learnedProximityEligibility = sensors::hasLearnedProximity,
            onAutoSleepConfigChanged = {
                acceptCommittedAutoSleepSetting(liveSettingAuthority) { refreshAutoSleepPresence() }
            },
            // This bridge generation's lease, registered with the runtime as the live broker channel
            // just below. A bridge that outlives its service OR its own replacement cannot report.
            haLifecycleLease = lease,
        ).also { bridge -> bridge.addStateSink(panelAssistantShadow.bind(bridge::stateChannelKeys)) }
    }

    private fun buildMdns(identity: NetworkRuntimeIdentity): MdnsAdvertiser = MdnsAdvertiser(
        this,
        config,
        runtimePanelId = identity.panelId,
        runtimeFriendlyName = identity.friendlyName,
        runtimeHttpPort = identity.httpPort,
    )

    /** Make the journalled desired value immediately visible/durable, but pass the previous value through
     * to transition-sensitive handlers. Transient HA-fed inputs intentionally have no persisted value. */
    /**
     * Settle this panel's touch-sound intent exactly once, and return it.
     *
     * Reporting has to be a pure function of durable intent. The platform `SOUND_EFFECTS_ENABLED` flag is
     * writable by firmware and by any other app holding `WRITE_SETTINGS`, so a reported value that
     * followed it drifted with no user action; the next unrelated Configure save then posted a value that
     * no longer matched, `DirectConfigMutationPlan` counted touch sound as changed, and the live-setting
     * journal queued an apply for a key nobody touched — permanently visible on a panel that cannot write
     * the flag at all.
     *
     * So an unresolved panel is adopted here, from its own actuation record first and from a single
     * reading of the flag second, and the answer is committed. From then on configuration `contains` the
     * key, [resolveTouchSoundIntent] short-circuits on it, and later drift can move the hardware but never
     * the setting. A failed commit changes nothing durable: the same evidence resolves again next boot.
     */
    private fun resolveTouchSoundIntentOnce(): Boolean {
        val resolution = resolveTouchSoundIntent(
            persistedIntent = config.touchSoundIntent,
            controllerRecord = touchSound.recordedState(),
            observedHardware = touchSound.observedPlatformState(),
            registryDefault = requireNotNull(SettingsRegistry.spec("touch_sound")).defaultBool(),
        )
        if (resolution.needsAdoption) {
            if (config.commitTouchSound(resolution.enabled)) {
                Log.i(TAG, "adopted touch sound intent ${resolution.enabled} (${resolution.origin})")
            } else {
                Log.w(TAG, "could not adopt touch sound intent (${resolution.origin}); retrying next boot")
            }
        }
        return resolution.enabled
    }

    private fun previousLiveSettingValue(key: String): String? = SettingsRegistry.spec(key)?.let(config::getRaw)

    private fun applyLiveSettingObserved(
        bridge: MqttBridge,
        key: String,
        value: String,
        previousValue: String? = null,
    ): LiveSettingApplication {
        val spec = SettingsRegistry.spec(key)
            ?: return LiveSettingApplication.immediate(LiveSettingApplyResult.FAILED)
        val previous = previousValue ?: config.getRaw(spec)
        // update_channel owns a two-phase candidate transaction. Its bridge handler starts the exact
        // preflight and the service commits only after compatibility admission; the ordinary live-setting
        // rule (persist before actuation) would create the forbidden configuration-mutation bypass.
        if (key == "update_channel") {
            return bridge.applySettingObserved(key, value, previous.takeUnless { spec.transient })
        }
        // Network ADB must retain its durable ownership marker until the controller has cleared every
        // classic/TLS property and authoritatively read them all inactive. Persisting false here first
        // made AdbController.set(false) conclude that the listener was external and skip teardown.
        // voice_enabled must not be persisted ahead of handleVoiceEnabled's live hasMicrophone check —
        // this generic path runs before dispatchSetting, so pre-persisting ON here would durably record
        // the setting even when the bridge refuses to act on it for lack of a microphone. handleVoiceEnabled
        // owns persistence itself (mirrors kiosk_lock/network_adb, whose actuators are also the sole writer).
        val actuationOwnsPersistence =
            key == "navbar_mode" || key == "kiosk_lock" || key == "network_adb" || key == "voice_enabled"
        if (!spec.transient && !actuationOwnsPersistence && !config.commitRaw(spec, value)) {
            return LiveSettingApplication.immediate(LiveSettingApplyResult.FAILED)
        }
        return bridge.applySettingObserved(key, value, previous.takeUnless { spec.transient })
    }

    private fun replayLiveSettingObserved(
        bridge: MqttBridge,
        key: String,
        value: String,
        previousValue: String?,
        fence: Long?,
    ): LiveSettingApplication {
        if (key != "auto_sleep" || fence == null) {
            return applyLiveSettingObserved(bridge, key, value, previousValue)
        }
        return LiveSettingApplication.immediate(applyAutoSleepFencedReplay(
            fence = fence,
            currentGeneration = config.autoSleepGeneration,
            currentEnabled = config.autoSleep,
            persistOff = { generation ->
                config.setAutoSleepIf(expected = true, expectedGeneration = generation, on = false)
            },
            refreshController = { refreshAutoSleepPresence() },
            applyBridge = { bridge.convergeAutoSleep(expectedGeneration = fence, expectedValue = false) },
        ))
    }

    /**
     * Apply config the HTTP page has already written to [config], then rebuild MQTT + mDNS. A renamed
     * panel's discovery cleanup is handed to the replacement bridge so it runs on a live connection.
     */
    private fun reconfigure(changedKeys: Set<String>) {
        enqueueReconfigure(changedKeys, resetLiveRetries = true)
    }

    private fun enqueueReconfigure(changedKeys: Set<String>, resetLiveRetries: Boolean) {
        synchronized(reconfigureKeysLock) {
            pendingReconfigureKeys += changedKeys
            if (resetLiveRetries) changedKeys.forEach { liveSettingRetryAttempts[it] = 0 }
        }
        if (configOwnerRefreshPlan(changedKeys).launcherHome) {
            launcherHomePolicyGeneration.incrementAndGet()
            launcherHomeFailedGeneration.set(Long.MIN_VALUE)
            scope.launch(Dispatchers.IO) {
                runCatching {
                    system.applyLauncherHomePolicy(
                        config.launcherPackage,
                        config.dashboardPackage,
                        config.haUrl.isNotBlank(),
                    )
                }.onFailure { Log.w(TAG, "launcher HOME policy apply failed", it) }
            }
        }
        when (runtime.requestLatest()) {
            ServiceRuntimeOwner.LatestAdmission.COALESCED ->
                FeatureCosts.registry.recordCoalesced(FeatureCostOperation.NETWORK_RECONFIGURE)
            ServiceRuntimeOwner.LatestAdmission.CLOSED ->
                FeatureCosts.registry.recordDropped(FeatureCostOperation.NETWORK_RECONFIGURE)
            ServiceRuntimeOwner.LatestAdmission.ACCEPTED -> Unit
        }
        FeatureCosts.registry.setBacklog(
            FeatureCostOperation.NETWORK_RECONFIGURE,
            runtime.pendingLatestCount(),
        )
    }

    /** Borrow the last management proof and the existing never-blank cadence. One failed periodic
     * mutation stays suppressed until the next explicit config generation; startup/config apply already
     * owns an immediate unrestricted attempt. */
    private fun repairAdminHomeFromCapturedRoute() {
        val generation = launcherHomePolicyGeneration.get()
        val route = server.lastPrivilegeObservation()?.let(::preferredAdminHomeRepairRoute)
        if (!shouldAttemptPeriodicAdminHomeRepair(
                serviceStopping = teardownBoundary.isStopping,
                adminHomeSelected = system.isAdminLauncherSelection(config.launcherPackage),
                admittedRoute = route,
                policyGeneration = generation,
                failedGeneration = launcherHomeFailedGeneration.get(),
            )
        ) return
        val repaired = runCatching { system.ensureAdminLauncherHome(checkNotNull(route)) }
            .onFailure { Log.w(TAG, "could not reassert Panel admin as HOME", it) }
            .getOrDefault(false)
        if (!repaired && launcherHomePolicyGeneration.get() == generation) {
            launcherHomeFailedGeneration.set(generation)
        }
    }

    private fun registerBrightnessPreferenceObserver() {
        lastObservedCommandedBrightness = brightness.getCommanded()
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                val level = runCatching {
                    Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS)
                }.getOrNull() ?: return
                val prior = lastObservedCommandedBrightness
                lastObservedCommandedBrightness = level
                if (brightness.consumeOwnedSettingChange(level) || screen.observedDark() == true) return
                autoBright.noteExternalBrightness(level, BrightnessPreferenceOrigin.ANDROID_SYSTEM, prior)
            }
        }
        contentResolver.registerContentObserver(
            Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS),
            false,
            observer,
        )
        brightnessObserver = observer
    }

    private fun refreshAdaptiveBrightnessInputs(restartSource: Boolean = true) {
        if (shouldDisableAutoBrightnessForMissingSource(
                enabled = config.autoBrightness,
                haEntity = config.autoBrightnessHaEntity,
                hasLocalSensor = sensors.hasLight(),
            )
        ) {
            Log.w(TAG, "Disabling auto-brightness: no local light sensor or HA illuminance source is configured")
            config.setAutoBrightness(false)
        }
        val enabled = config.autoBrightness
        val selected = adaptiveHaSource(enabled, config.autoBrightnessHaEntity)
        val candidateIdentity = config.haUrl.trim()
        if (candidateIdentity != haCandidateIdentity) {
            haCandidateIdentity = candidateIdentity
            haAmbientLux.invalidateCandidates()
        }
        refreshAmbientSourceBinding(
            restartSource = restartSource,
            selectedSource = selected,
            markUnavailable = { autoBright.setHaSourceAvailable(false) },
            setSource = haAmbientLux::setSource,
        )
        val generation = adaptiveSiteGeneration.incrementAndGet()
        if (enabled) {
            scope.launch {
                val site = haSiteMetadata.fetch().metadata
                if (site != null && !teardownBoundary.isStopping && adaptiveSiteGeneration.get() == generation) {
                    autoBright.updateSite(site.latitude, site.longitude, site.timeZone)
                }
                if (selected != null && !teardownBoundary.isStopping && adaptiveSiteGeneration.get() == generation) {
                    try {
                        val seed = haAmbientLux.loadHistory(selected)
                        if (!teardownBoundary.isStopping && adaptiveSiteGeneration.get() == generation) {
                            autoBright.seedHaHistory(seed)
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Throwable) {
                        Log.i(TAG, "Home Assistant ambient history bootstrap unavailable (${error.javaClass.simpleName})")
                    }
                }
            }
        }
        autoBright.reapplyLatest()
    }

    private fun adaptiveBrightnessHttpApi(): AutoBrightnessHttpApi = object : AutoBrightnessHttpApi {
        override fun statusJson(): String {
            val runtime = autoBright.status()
            val haStatus = haAmbientLux.latestStatus()
            val selected = config.autoBrightnessHaEntity.trim().takeIf(String::isNotBlank)
            val candidate = selected?.let { id ->
                haAmbientLux.latestCandidates().items.firstOrNull { it.entityId == id }
            }
            return JSONObject().apply {
                put("available", true)
                put("state", if (runtime.enabled) "enabled" else "disabled")
                put("mode", runtime.mode?.name?.lowercase() ?: "waiting")
                put("paused", runtime.manualPreference.active)
                put("preferenceActive", runtime.manualPreference.active)
                put("localSourcePresent", sensors.hasLight())
                put("sourceLabel", candidate?.friendlyName ?: selected ?: if (sensors.hasLight()) {
                    "Panel ambient sensor"
                } else {
                    "No ambient light source"
                })
                put("entityId", selected ?: JSONObject.NULL)
                put("sourceAvailable", runtime.sourceAvailable)
                put("sourceRevision", runtime.sourceRevision)
                put("latestLux", runtime.latestLux ?: JSONObject.NULL)
                put("expectedLux", runtime.expectedLux ?: JSONObject.NULL)
                put("automaticTarget", runtime.automaticTarget ?: JSONObject.NULL)
                put("appliedTarget", runtime.appliedTarget ?: JSONObject.NULL)
                put("minimumPercent", config.autoBrightnessMinimumPercent)
                put(
                    "minimumBrightness",
                    io.github.maxlyth.hapaneld.control.AdaptiveLuxCurve.percentToBrightness(
                        config.autoBrightnessMinimumPercent,
                    ),
                )
                put("autoAuthority", runtime.manualPreference.autoAuthority)
                put("manualRemainingMs", runtime.manualPreference.remainingMs)
                if (selected != null) put("detail", haStatus.detail)
            }.toString()
        }

        override fun historyJson(hours: Int, sensitivity: Int?, minimumPercent: Int?): String {
            val nowEpochMinute = System.currentTimeMillis() / 60_000L
            val cutoffMinute = nowEpochMinute - hours * 60L
            val previewSensitivity = sensitivity ?: config.autoBrightnessResponsePercent
            val previewMinimum = minimumPercent ?: config.autoBrightnessMinimumPercent
            val snapshot = autoBright.chartSnapshot(
                previewSensitivity,
                previewMinimum,
            )
            val points = snapshot.points
                .filter { it.epochMinute >= cutoffMinute }
            val zone = autoBright.timeZone()
            val today = Calendar.getInstance(zone).apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val dayMetadata = mutableMapOf<Int, Pair<String, Int>>()
            return JSONObject().apply {
                put("available", true)
                put("hours", hours)
                put("bucket_minutes", 5)
                put("sensitivity", previewSensitivity)
                put("minimum_percent", previewMinimum)
                put("minimum_brightness", io.github.maxlyth.hapaneld.control.AdaptiveLuxCurve.percentToBrightness(previewMinimum))
                put("now_epoch_minute", nowEpochMinute)
                put("sourceRevision", snapshot.sourceRevision)
                // Freshness describes the newest point actually returned to this chart request,
                // rather than a newer raw minute that has not completed its five-minute bucket.
                put("latestEpochMinute", points.lastOrNull()?.epochMinute ?: JSONObject.NULL)
                put("time_zone", zone.id)
                put("points", JSONArray().apply {
                    points.forEach { point -> put(JSONObject().apply {
                        val local = Calendar.getInstance(zone).apply {
                            timeInMillis = point.epochMinute * 60_000L
                        }
                        val dayKey = local.get(Calendar.YEAR) * 400 + local.get(Calendar.DAY_OF_YEAR)
                        val (localDay, dayAge) = dayMetadata.getOrPut(dayKey) {
                            val day = String.format(
                                Locale.US,
                                "%04d-%02d-%02d",
                                local.get(Calendar.YEAR),
                                local.get(Calendar.MONTH) + 1,
                                local.get(Calendar.DAY_OF_MONTH),
                            )
                            val localMidnight = local.clone() as Calendar
                            localMidnight.set(Calendar.HOUR_OF_DAY, 0)
                            localMidnight.set(Calendar.MINUTE, 0)
                            localMidnight.set(Calendar.SECOND, 0)
                            localMidnight.set(Calendar.MILLISECOND, 0)
                            var age = 0
                            while (localMidnight.before(today) && age <= 7) {
                                localMidnight.add(Calendar.DAY_OF_MONTH, 1)
                                age += 1
                            }
                            day to age
                        }
                        put("epochMinute", point.epochMinute)
                        put("localDay", localDay)
                        put("minuteOfDay", local.get(Calendar.HOUR_OF_DAY) * 60 + local.get(Calendar.MINUTE))
                        put("dayAge", dayAge)
                        put("observedMeanLux", point.observedMeanLux)
                        put("minLux", point.minLux)
                        put("maxLux", point.maxLux)
                        put("expectedLux", point.expectedLux)
                        put("proposedBrightness", point.proposedBrightness)
                    }) }
                })
            }.toString()
        }

        override fun haSourcesJson(query: String, limit: Int): String {
            haAmbientLux.refreshCandidates()
            val projection = haAmbientLux.latestCandidates()
            val needle = query.lowercase()
            val items = projection.items.asSequence()
                .filter { needle.isBlank() || it.entityId.lowercase().contains(needle) || it.friendlyName.lowercase().contains(needle) }
                .take(limit)
            return JSONObject().apply {
                put("available", projection.error.isBlank())
                put("refreshing", haAmbientLux.candidateRefreshInFlight())
                if (projection.error.isNotBlank()) put("detail", projection.error)
                put("items", JSONArray().apply { items.forEach { source -> put(JSONObject().apply {
                    put("entityId", source.entityId)
                    put("friendlyName", source.friendlyName)
                    put("unit", source.unit)
                    put("currentLux", source.currentLux ?: JSONObject.NULL)
                    put("available", source.available)
                    put("lastUpdatedEpochMs", source.lastUpdatedEpochMs ?: JSONObject.NULL)
                }) } })
            }.toString()
        }

        override suspend fun validateHaSource(entityId: String): AutoBrightnessHttpValidation =
            when (val result = haAmbientLux.validateSource(entityId)) {
                is HaAmbientSourceValidation.Ready -> AutoBrightnessHttpValidation(
                    AutoBrightnessHttpAction.ok(),
                    result.authOwner,
                )
                is HaAmbientSourceValidation.Rejected -> AutoBrightnessHttpValidation(
                    AutoBrightnessHttpAction(
                        statusCode = result.statusCode,
                        json = JSONObject().put("ok", false).put("error", result.error)
                            .put("message", result.message).toString(),
                    ),
                )
            }

        override suspend fun selectHaSource(entityId: String?): AutoBrightnessHttpAction {
            if (entityId != null) {
                val previous = config.autoBrightnessHaEntity
                val validation = validateHaSource(entityId)
                if (validation.action.statusCode !in 200..299) return validation.action
                val committed = config.synchronizedTransaction {
                    if (validation.authOwner == null || config.haAuthSnapshot().stableOwner() != validation.authOwner ||
                        config.autoBrightnessHaEntity != previous
                    ) false else config.applyBatch { config.setAutoBrightnessHaEntity(entityId) }
                }
                if (!committed) return AutoBrightnessHttpAction(
                    409,
                    """{"ok":false,"error":"ha-source-validation-stale","message":"Home Assistant settings changed during the check. Try again."}""",
                )
            } else {
                config.setAutoBrightnessHaEntity("")
            }
            refreshAdaptiveBrightnessInputs()
            autoBright.reapplyLatest()
            return AutoBrightnessHttpAction.ok()
        }

        override fun resetHistory(): AutoBrightnessHttpAction {
            autoBright.resetHistory()
            return AutoBrightnessHttpAction.ok()
        }

        override fun resumeFullAuto(): AutoBrightnessHttpAction {
            autoBright.resumeFullAuto()
            return AutoBrightnessHttpAction.ok()
        }
    }

    private fun performNetworkReconfigure(
        mutation: ServiceRuntimeOwner<NetworkRuntime>.LatestMutation,
    ) {
        val executionDeadline = mutation.deadline
        if (executionDeadline.remainingMs() <= 0L) return
        val changedKeys = synchronized(reconfigureKeysLock) {
            pendingReconfigureKeys.toSet().also { pendingReconfigureKeys.clear() }
        }
        val ownerRefresh = configOwnerRefreshPlan(changedKeys)
        // A committed configuration application starts a fresh comparison epoch while process-lifetime
        // startup evidence remains available in the same fixed-cardinality diagnostics projection.
        FeatureCosts.beginEpoch()
        FeatureCosts.registry.setBacklog(
            FeatureCostOperation.NETWORK_RECONFIGURE,
            runtime.pendingLatestCount(),
        )
        var cost: io.github.maxlyth.hapaneld.metrics.FeatureCostRegistry.Span? = null
        var obligationsCompleted = false
        try {
            val desired = currentNetworkConfigurationSnapshot()
            val replacementRequired =
                desired.runtime != appliedNetworkConfiguration.runtime || !mutation.isRunning
            val adaptiveSourceRestartRequired =
                replacementRequired || desired.haLink != appliedNetworkConfiguration.haLink
            val operation = if (replacementRequired) FeatureCostOperation.NETWORK_RECONFIGURE
                else FeatureCostOperation.CONFIG_LIVE_REFRESH
            val operationCost = FeatureCosts.registry.span(operation)
                .work(units = if (replacementRequired) 1 else 0)
            cost = operationCost
            if (ownerRefresh.adaptiveBrightness) {
                refreshAdaptiveBrightnessInputs(restartSource = adaptiveSourceRestartRequired)
            }
            if (!replacementRequired) {
                val active = mutation.current.mqtt
                val replayKeys = liveSettingAuthority.pendingSnapshot().keys.intersect(changedKeys)
                if (replayKeys.isNotEmpty()) {
                    liveSettingAuthority.replayKeysObserved(replayKeys) { key, value, previous, fence ->
                        replayLiveSettingObserved(active, key, value, previous, fence)
                    }
                    scheduleLiveSettingRetries(replayKeys)
                }
                refreshLiveConfiguration(
                    active,
                    desired,
                    ownerRefresh = ownerRefresh,
                    evaluateMqttEffects = true,
                    cost = operationCost,
                )
                obligationsCompleted = true
                return
            }
            val completed = mutation.replace(
                retire = { previous ->
                    // FIRST-CONFIGURATION FAST PATH (behavioral contract): a panel whose
                    // applied runtime never had a broker AND whose probe never connected has published
                    // nothing, owns nothing and owes nobody an offline — every fence below protects
                    // state that provably does not exist, and paying full ceremony here is why a fresh
                    // panel's first MQTT save felt like minutes instead of seconds. Best-effort stop on
                    // a tight budget, nothing to prove, straight to build.
                    val firstConfiguration = previous.mqtt.configuredBroker.isEmpty() &&
                        previous.mqtt.activeBroker.isEmpty()
                    if (firstConfiguration) {
                        Log.i(TAG, "first-configuration fast path: no prior broker state to retire")
                        val quick = MonotonicDeadline(FIRST_CONFIG_RETIRE_MS)
                        val mdnsRetirement = previous.mdns.retire(quick)
                        val mqttRetirement = previous.mqtt.stop(
                            deadline = quick,
                            publishOffline = false,
                            clearDiscovery = false,
                        )
                        mqttRetirement.ownersDrained.awaitTrue(quick)
                        mqttRetirement.finalization.awaitSuccessful(quick)
                        mdnsRetirement.awaitTrue(quick)
                        return@replace
                    }
                    // The replacement uses the same availability topic unless the panel id changed. Do not race
                    // its retained online with a late offline from the retiring client. A genuinely different
                    // broker still needs an explicit offline because the replacement cannot clean that broker.
                    val publishOffline = mqttReconfigurePublishesOffline(
                        previous.mqtt.panelId,
                        desired.runtime.panelId,
                        previous.mqtt.configuredBroker,
                        desired.runtime.broker,
                    )
                    // Start both independent retirement fences before awaiting either result. They share one
                    // monotonic budget; no locally reset phase timeout can accumulate beyond it.
                    val mdnsRetirement = previous.mdns.retire(executionDeadline)
                    val mqttRetirement = previous.mqtt.stop(
                        deadline = executionDeadline,
                        publishOffline = publishOffline,
                        clearDiscovery = publishOffline,
                    )
                    val mqttOwnersDrained = mqttRetirement.ownersDrained.awaitTrue(executionDeadline)
                    val mqttFinalized = mqttRetirement.finalization.awaitSuccessful(executionDeadline)
                    val mdnsStopped = mdnsRetirement.awaitTrue(executionDeadline)
                    val withinDeadline = executionDeadline.remainingMs() > 0L
                    check(mqttOwnersDrained && mqttFinalized && mdnsStopped && withinDeadline) {
                        "retiring network runtime did not prove cleanup " +
                            "(mqttOwners=$mqttOwnersDrained mqttFinal=$mqttFinalized " +
                            "mdns=$mdnsStopped withinDeadline=$withinDeadline)"
                    }
                },
                build = { previous ->
                    val stalePanelId = previous.mqtt.panelId.takeIf { it != desired.runtime.panelId }
                    NetworkRuntime(
                        buildMqtt(desired.runtime, stalePanelId),
                        buildMdns(desired.runtime),
                    )
                },
                start = { replacement ->
                    startReconfiguredNetworkRuntime(
                        startMdns = replacement.mdns::start,
                        resolveHaLink = replacement.mqtt::maybeResolveHaLink,
                        startMqtt = replacement.mqtt::start,
                    )
                },
                complete = { replacement ->
                    liveSettingAuthority.replayKeysObserved(MqttBridge.APPLY_SETTING_KEYS) { key, value, previous, fence ->
                        replayLiveSettingObserved(replacement.mqtt, key, value, previous, fence)
                    }
                    // Publish only the immutable configuration this concrete replacement consumed. A newer
                    // config can arrive while start() is blocked; retaining this older snapshot makes the
                    // conflated rerun replace/re-project again instead of mistaking that newer config for live.
                    refreshLiveConfiguration(
                        replacement.mqtt,
                        desired,
                        ownerRefresh = ownerRefresh,
                        evaluateMqttEffects = false,
                        cost = operationCost,
                    )
                    Log.i(
                        TAG,
                        "reconfigured: panel=${desired.runtime.panelId} " +
                            "broker=${desired.runtime.broker.ifEmpty { "(disabled)" }}",
                    )
                },
            )
            if (!completed) {
                operationCost.outcome(FeatureCostOutcome.REJECTED)
                if (executionDeadline.remainingMs() > 0L) requestNetworkRecovery()
            } else {
                obligationsCompleted = true
                // A default-network callback can also land while replacement owns the unobservable
                // RECONFIGURING state. Reconcile its retained topology against the published successor.
                mdnsRuntimeReconciler.runtimeRunning()
            }
        } catch (e: InterruptedException) {
            cost?.outcome(FeatureCostOutcome.CANCELLED)
            Thread.currentThread().interrupt()
        } catch (e: Exception) {
            cost?.outcome(FeatureCostOutcome.FAILURE)
            Log.w(TAG, "network reconfigure failed", e)
            if (executionDeadline.remainingMs() > 0L) requestNetworkRecovery()
        } finally {
            if (obligationsCompleted && ownerRefresh.panelAssistantTransport) {
                runCatching { refreshPanelAssistantTransport() }
            }
            if (!obligationsCompleted) synchronized(reconfigureKeysLock) {
                pendingReconfigureKeys += changedKeys
            }
            if (executionDeadline.remainingMs() <= 0L && !Thread.currentThread().isInterrupted) {
                cost?.outcome(FeatureCostOutcome.REJECTED)
            }
            cost?.close()
        }
    }

    private fun scheduleLiveSettingRetries(attemptedKeys: Set<String>) {
        val remaining = liveSettingAuthority.pendingSnapshot().keys.intersect(attemptedKeys)
        val retry = synchronized(reconfigureKeysLock) {
            attemptedKeys.minus(remaining).forEach(liveSettingRetryAttempts::remove)
            remaining.filterTo(linkedSetOf()) { key ->
                val next = nextLiveSettingRetryAttempt(liveSettingRetryAttempts[key] ?: 0)
                if (next == null) false else {
                    liveSettingRetryAttempts[key] = next
                    true
                }
            }
        }
        if (retry.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            kotlinx.coroutines.delay(1_000)
            val stillPending = liveSettingAuthority.pendingSnapshot().keys.intersect(retry)
            if (stillPending.isNotEmpty()) enqueueReconfigure(stillPending, resetLiveRetries = false)
        }
    }

    private fun requestNetworkRecovery() {
        if (!teardownBoundary.isStopping) recoveryRestart.request()
    }

    private fun onNetworkReconfigureTimeout() {
        FeatureCosts.registry.setBacklog(
            FeatureCostOperation.NETWORK_RECONFIGURE,
            runtime.pendingLatestCount(),
        )
        requestNetworkRecovery()
    }

    private fun currentNetworkIdentity(): NetworkRuntimeIdentity {
        val credentials = config.mqttCredentialsSnapshot()
        return NetworkRuntimeIdentity(
            config.panelId,
            config.friendlyName,
            config.httpPort,
            credentials.broker.trim(),
            credentials.user,
            credentials.password,
            credentials.addressFamily,
        )
    }

    private fun currentNetworkConfigurationSnapshot(): NetworkConfigurationSnapshot =
        config.synchronizedTransaction {
            NetworkConfigurationSnapshot(
                runtime = currentNetworkIdentity(),
                projection = currentMqttProjection(),
                haLink = currentHaLinkIdentity(),
            )
        }

    private fun currentMqttProjection(): MqttProjectionIdentity = MqttProjectionIdentity(
        manufacturer = config.manufacturer,
        model = config.model,
        exposures = SettingsRegistry.SPECS.asSequence()
            .filter { it.ha != null }
            .map { it.key to config.haExposed(it.key, it.haExposedByDefault) }
            .toList(),
    )

    private fun currentHaLinkIdentity(): HaLinkIdentity = HaLinkIdentity(
        config.haUrl.trim().trimEnd('/'),
        config.haToken,
        config.haRefreshToken,
        config.haTokenExpiry,
        config.haClientId,
    )

    private fun refreshLiveConfiguration(
        bridge: MqttBridge,
        desired: NetworkConfigurationSnapshot,
        ownerRefresh: ConfigOwnerRefreshPlan,
        evaluateMqttEffects: Boolean,
        cost: io.github.maxlyth.hapaneld.metrics.FeatureCostRegistry.Span,
    ) {
        if (evaluateMqttEffects) {
            val effects = configRefreshEffects(
                appliedNetworkConfiguration.projection,
                desired.projection,
                appliedNetworkConfiguration.haLink,
                desired.haLink,
            )
            bridge.refreshConfiguration(effects.reannounceMqtt, effects.resolveHaLink)
            cost.work(
                units = (if (effects.reannounceMqtt) 1L else 0L) +
                    (if (effects.resolveHaLink) 1L else 0L),
            )
        }
        appliedNetworkConfiguration = desired
        if (ownerRefresh.logShipping) runCatching { logShipper.reconfigure() }
        if (ownerRefresh.keepAwake) runCatching { power.apply(config.keepAwake) }
        if (ownerRefresh.autoSleep) runCatching { refreshAutoSleepPresence() }
        if (ownerRefresh.rendererTarget) {
            io.github.maxlyth.hapaneld.http.PerfReader.updateRendererTarget(rendererTargetSnapshot())
        }
        if (ownerRefresh.haLifecycle) runCatching { refreshHaLifecycleWatch() }
        if (ownerRefresh.camera && ::camera.isInitialized) runCatching { camera.onEnabledChanged() }
        // A camera switch moved on the Configure page, by a bundle import or by provisioning must reach
        // Home Assistant too; otherwise its switch keeps a position the panel has already left.
        if (ownerRefresh.camera) runCatching { mqtt.publishCameraState() }
    }

    private fun currentPanelAssistantTransportDemand(): PanelAssistantTransportDemand? {
        val auth = config.haAuthSnapshot()
        return panelAssistantTransportDemand(
            credential = auth.stableOwner(),
            accessTokenPresent = auth.accessToken.isNotBlank(),
            identity = PanelAssistantHelloIdentity(
                did = panelAssistantDiscoveryId(config.androidId),
                appVersion = BuildConfig.VERSION_NAME,
                appVersionCode = BuildConfig.VERSION_CODE,
            ),
        )
    }

    /** Start, keep or stop native transport only on the successfully started, admitted service owner. */
    private fun refreshPanelAssistantTransport() {
        if (!::panelAssistantTransport.isInitialized) return
        val demand = currentPanelAssistantTransportDemand()
        runtime.runIfRunning {
            if (!teardownBoundary.isStopping) panelAssistantTransport.replaceDemand(demand)
        }
    }

    /**
     * Start or stop the lifecycle watch to match the current renderer and credentials. Safe to call
     * repeatedly: an unchanged demand is a no-op inside the stream owner.
     */
    private fun refreshHaLifecycleWatch() {
        if (!::haExactEntityStream.isInitialized || !::system.isInitialized) return
        val wanted = haLifecycleWatchWanted(
            builtinRendererSelected = system.isBuiltinDashboardTarget(config.dashboardPackage),
            credentialsPresent = config.haUrl.isNotBlank() &&
                (config.haToken.isNotBlank() || config.haRefreshToken.isNotBlank()),
        )
        // The deferral gates ENABLING only. Deferring a disable would strand the socket open after the
        // built-in renderer is deselected — the released renderer resets settled, so the disabling
        // refresh would wait for a settle that is never coming.
        if (!haLifecycleRefreshPermitted(BuiltinDashboard.rendererSettled, wanted)) return
        haExactEntityStream.replaceLifecycleWatch(wanted)
        val watchChanged = HaLifecycleRuntime.setWatching(haLifecycle, wanted)
        // A refusal describes a session on the route just switched off; keeping it would show the next
        // session's user a verdict they were never given.
        if (!wanted) haLifecycle.onSocketWatchStopped()
        // Switching the watch off retires everything consumers can render (an unreportable holder
        // answers null), so they must be told — otherwise the native card keeps describing an outage
        // for a feature that is no longer watching, and redraws it from that state on resume.
        if (watchChanged) BuiltinDashboard.onHaLifecycleChanged()
        // Warm the brand mark now rather than during an outage, when Home Assistant is exactly what is
        // unreachable. Off the main thread; failure is silent and leaves a text-only banner.
        if (wanted) scope.launch(Dispatchers.IO) { HaBrandIcon.prefetch(this@PaneldService, config.haUrl) }
    }

    /** Controller reads shared by the dashboard facts, live values and capability projection. */
    private data class ManagementControllerObservation(
        val cpuTier: String?,
        val cpuGovernorsAvailable: Boolean,
        val networkAdbPersisted: Boolean,
        val networkAdbActive: Boolean,
        val zigbee: ZigbeeObservation,
        val relayCount: Int,
        val buttonLedCount: Int,
    )

    private fun observeManagementControllers(privilege: PrivilegedRouteObservation): ManagementControllerObservation {
        val persistedAdb = adb.isPersisted()
        return ManagementControllerObservation(
            cpuTier = cpu.currentTier(allowRootFallback = privilege.directSuReady),
            cpuGovernorsAvailable = cpu.available(allowRootFallback = privilege.directSuReady),
            networkAdbPersisted = persistedAdb,
            // A persisted ha-paneld intent already determines the displayed state; avoid five property
            // reads merely to rediscover that its boot reassertion is owned here.
            networkAdbActive = !persistedAdb && adb.isActive(allowRootCrossCheck = privilege.directSuReady),
            zigbee = zigbee.observe(includeRole = true, directSuReady = privilege.directSuReady),
            relayCount = relay.count(allowRootProbe = privilege.directSuReady),
            buttonLedCount = relay.ledCount(),
        )
    }

    /**
     * Controller-sourced values, which is why `touch_sound` is deliberately absent.
     *
     * A key in this map overrides the persisted registry value on every reporting surface, so it must
     * name state the panel genuinely owns at that instant. Touch sound is not such a state: its platform
     * flag has other writers, and letting an observation of it stand in for the setting made an unrelated
     * save post a stale value and queue an apply the user never asked for. Its authority is the persisted
     * intent, which [io.github.maxlyth.hapaneld.http.PaneldServer.effectiveValue] reaches by falling
     * through to configuration.
     */
    private fun projectLiveValues(cpuTier: String?, networkAdbPersisted: Boolean): Map<String, String> =
        mapOf(
            "cpu_governor" to (cpuTier ?: "Auto"),
            "network_adb" to networkAdbPersisted.toString(),
            "zigbee_router" to config.zigbeeRouterEnabled.toString(),
        )

    /** Fresh controller values for direct equality, config export and concurrency checks. Direct POST
     * planning also consumes the transient CPU tier, so a full payload cannot reapply an unchanged tier. */
    private fun currentConfigLiveValues(): Map<String, String> = mapOf(
        "cpu_governor" to (cpu.currentTier(allowRootFallback = false) ?: "Auto"),
        "network_adb" to adb.isPersisted().toString(),
        "zigbee_router" to config.zigbeeRouterEnabled.toString(),
    )

    private fun managementProjection(privilege: PrivilegedRouteObservation): ManagementProjection {
        val controllers = observeManagementControllers(privilege)
        val diagnostic = DiagReader.capabilities(this, profile, privilege)
        // ONE outage read feeds both the diagnostics row and the /diag gate. Reading the tracker
        // twice would let an episode age out between them, and a report could then omit the line
        // while printing a row that says the Wi-Fi needs attention, or the reverse.
        val wifi = wifiOutageTracker.counts()
        return ManagementProjection(
            facts = panelInfo(controllers, diagnostic.rgbLedReady, wifi),
            live = projectLiveValues(
                cpuTier = controllers.cpuTier,
                networkAdbPersisted = controllers.networkAdbPersisted,
            ),
            capabilities = capabilitiesSnapshot(privilege, controllers),
            capabilityRows = diagnostic.rows,
            wifiChronic = wifiOutageChronic(wifi),
        )
    }

    /** Ordered facts for the info page (`GET /`). */
    private fun panelInfo(
        controllers: ManagementControllerObservation,
        rgbLedReady: Boolean,
        wifiOutages: WifiOutageCounts,
    ): Map<String, String> {
        // activeBroker reflects auto-discovery (tcp://<ha-ip>:1883) when no broker is configured.
        val broker = mqtt.activeBroker.ifBlank { config.mqttBroker }
        val host = broker.substringAfter("://").substringBefore(":").ifBlank { "?" }
        val auto = config.mqttBroker.isBlank() && mqtt.activeBroker.isNotBlank()
        val mqttStatus = when (mqtt.state) {
            "connected" -> "$host · connected" + (if (auto) " (auto)" else "")
            "announcing" -> "$host · connected, announcing…" + (if (auto) " (auto)" else "")
            "auth-retrying" -> "$host · auth retrying…"
            "auth-failed" -> "$host · reachable, auth rejected — check username/password"
            "unreachable" -> "$host · unreachable"
            "connecting" -> "$host · connecting…"
            "config-error" -> "$host · invalid or unsupported broker URL"
            else -> "disabled"
        }
        val pv = SystemProps.get("ro.product.version")
        val appDatabase = runCatching { PanelInfo.databaseSummary(entityLearning.databaseUsage()) }.getOrNull()
        val extras = linkedMapOf(
            "panel_id" to config.panelId,
            "Friendly name" to config.friendlyName,
            "HTTP port" to config.httpPort.toString(),
            "Local IP" to (localIpv4() ?: "?"),
            "Local IPv6" to (localIpv6() ?: "—"),
            "MQTT" to mqttStatus,
            // Host-free state + liveness age + family preference. Deliberately separate from "MQTT"
            // (which carries the broker host and is omitted from /diag): this row IS included in a
            // /diag dump, so a pasted report finally answers "is this panel broker-connected?".
            "MQTT state" to mqtt.statusPublic(),
            "Security mode" to if (config.hardenedSecurityEnabled) {
                "Hardened · high-impact remote actions need physical on-panel approval"
            } else {
                "Relaxed"
            },
            // Wakelock/Wi-Fi-lock intent vs reality — a panel that should be keep-awake but isn't
            // holding the lock is a strong hint for stalled-idle-connection reports.
            "Keep panel responsive" to if (config.keepAwake) (if (power.isHeld()) "on · power locks held" else "on · power lock NOT held") else "off",
            "Prevent idle dim" to preventIdleDimDiagnostic(config.preventIdleDim, brightness.screenOffTimeoutMs()),
            "Android dashboard lock" to if (config.kioskLock) "on" else "off",
            "mDNS" to mdns.statusPublic(),
            "Platform" to profile.displayName,
            "SoC" to (profile.soc?.displayText() ?: profile.socClass),
            "Model" to profile.panelModelLabel(pv),
            "LED" to ledLabel(rgbLedReady),
            "Light sensor" to sensorRow(sensors.hasLight(), profile.lightTech, sensors.lightDesc()),
            "Proximity" to sensorRow(sensors.hasProximity(), profile.proximityTech, sensors.proximityDesc()),
            // a11y service = software back/recents nav, NOT physical buttons (NSPanel Pro has none).
            "Nav actions (a11y)" to yesNo(accessibilityEnabled()),
            // Soft navbar mode + whether the overlay can actually be drawn (SYSTEM_ALERT_WINDOW). Test the
            // modes that draw a bar rather than "not Off": Native draws nothing either, so warning about a
            // missing overlay permission there would be a false alarm on every natively-barred panel.
            "Navbar" to (
                config.navbarMode +
                    if (config.navbarMode in NavbarController.OVERLAY_MODES && !canDrawOverlays()) {
                        " · no overlay permission"
                    } else {
                        ""
                    }
                ),
            "Zigbee" to controllers.zigbee.status,
            "Relays" to controllers.relayCount.let { if (it > 0) it.toString() else "none" },
            "CPU profile" to (controllers.cpuTier ?: "n/a"),
            "Network ADB" to when {
                controllers.networkAdbPersisted -> "persistent (5555) · re-asserted by ha-paneld at boot"
                controllers.networkAdbActive -> "active (5555) · external — not persisted by ha-paneld"
                else -> "off"
            },
            "Log shipping" to logShipper.statusText(),
            "Audio playback" to audio.snapshot().statusText(),
        )
        appDatabase?.let { extras["App database"] = it }
        // Rolling 24-hour Wi-Fi outage count — present only once the window holds an episode or
        // dropped evidence, so a stable panel (or an Ethernet panel) never carries a zero row.
        wifiOutageStatusText(wifiOutages)?.let { extras["Wi-Fi stability"] = it }
        if (pv.isNotEmpty()) extras["Product version"] = pv
        // Recent "changed outside MQTT" events (brightness/volume/backlight/governor) — shown only when
        // something has actually synced, so it doesn't clutter a steady panel. Flows to /diag too.
        mqtt.recentSyncEvents().takeIf { it.isNotEmpty() }?.let { extras["Local-state sync"] = it.joinToString(" · ") }
        extras["State convergence"] = mqtt.convergenceStatus()
        return PanelInfo.collect(this, extras, profile)
    }

    private fun ledLabel(rgbLedReady: Boolean): String {
        if (!rgbLedReady) return "none"
        return when {
        led is Rk3576LedController -> "Rockchip /dev/ledjni (RGB)"
        led is SocketLedController && profile.ledMechanism == LedMechanism.RK3576_IOCTL_DAEMON -> "Rockchip /dev/ledjni helper daemon (RGB)"
        led is SocketLedController && profile.ledMechanism == LedMechanism.SYSFS_DAEMON -> "sysfs helper daemon (RGB)"
        led is SocketLedController -> "helper daemon (RGB)"
        led.colorCapable() -> "RGB"
        else -> "brightness"
        }
    }

    private fun yesNo(b: Boolean) = if (b) "yes" else "no"

    /** "no", or "yes" with any declared technology + runtime value-type/range appended ("yes · Infrared ·
     *  Binary · near/far (0 / 5 cm)"). */
    private fun sensorRow(present: Boolean, tech: String?, desc: String?): String =
        if (!present) "no" else "yes" + listOfNotNull(tech, desc).joinToString("") { " · $it" }

    // MQTT recovery runs each heartbeat. Preserve the established positive-sticky/backoff contract so
    // a declared gateway is discovered after late root startup without spawning a root process forever.
    /**
     * Whether Android enumerates any camera on this board.
     *
     * Sticky in BOTH directions, unlike the zigbee probe: camera hardware does not appear or vanish at
     * runtime, so an empty list is a definitive answer rather than a failed probe. Only a throw is
     * retried, which is the camera service not yet being up early in boot. That keeps the privacy
     * contract's "idle cost is zero" promise — a panel without a camera enumerates once, not forever.
     *
     * The acceptance rule is exactly the one the session's own `chooseCamera` applies: any enumerated
     * camera counts, because that function prefers a front-facing lens but never filters on it. So the
     * panel cannot offer a camera that the session would then refuse to open as `no_camera_id`.
     */
    private val cameraPresence = SuccessStickyProbe(
        probe = { getSystemService(CameraManager::class.java)?.cameraIdList?.isNotEmpty() },
        initialBackoffMs = 5_000L,
        maxBackoffMs = 300_000L,
    )

    /** The profile's declaration, or what Android enumerates when the profile is silent. */
    private fun cameraPresent(): Boolean = cameraReason().capable

    /**
     * Why this panel does or does not offer a camera, from the same two inputs as the capability. This is
     * the only place either input is read, so the answer the panel acts on and the answer it shows a user
     * are the same answer.
     */
    private fun cameraReason(): CameraCapabilityReason =
        cameraCapabilityReason(profile.cameraDeclared, cameraPresence.get())

    private val zigbeePresence = SuccessStickyProbe(
        probe = {
            zigbee.observe(includeRole = false)
                .takeIf { it.probeSucceeded && it.present }
                ?.let { true }
        },
        initialBackoffMs = 5_000L,
        maxBackoffMs = 300_000L,
    )

    /** This panel's capability snapshot for the settings registry's availableWhen gates
     *  (the Configure form/schema + the dashboard's read-only values card). */
    private fun capabilitiesSnapshot(): Capabilities {
        val privilege = observeTypedShellCapability(
            directSuProbe = Su::available,
            helperRootProbe = HelperClient::available,
            shizukuSnapshot = ShizukuBridge::snapshot,
        )
        return capabilitiesSnapshot(
            directSuReady = privilege.directSuReady,
            shizukuReady = privilege.shizuku.ready,
            typedShellControlReady = privilege.typedShellControlReady,
            controllers = null,
        )
    }

    private fun capabilitiesSnapshot(
        privilege: PrivilegedRouteObservation,
        controllers: ManagementControllerObservation,
    ): Capabilities = capabilitiesSnapshot(
        directSuReady = privilege.directSuReady,
        shizukuReady = privilege.shizuku.ready,
        typedShellControlReady = privilege.typedShellControlReady,
        controllers = controllers,
    )

    private fun capabilitiesSnapshot(
        directSuReady: Boolean,
        shizukuReady: Boolean,
        typedShellControlReady: Boolean,
        controllers: ManagementControllerObservation?,
    ): Capabilities {
        val cost = FeatureCosts.registry.span(FeatureCostOperation.CAPABILITY_SNAPSHOT)
        return try {
            val wifi = wifiDiagnostics.snapshot(
                WifiDiagnosticDemand(ssid = true, rssi = true, privilegedRoute = directSuReady),
            )
            val wifiAvailable = wifi.availability()
            Capabilities(
                hasProximity = sensors.hasProximity(),
                hasLearnedProximity = sensors.hasLearnedProximity(),
                hasLight = sensors.lightAvailable(),
                hasTemperature = sensors.hasTemperature(),
                hasHumidity = sensors.hasHumidity(),
                hasWifi = wifiAvailable.rssi,
                hasWifiSsid = wifiAvailable.ssid,
                hasCht8305 = profile.hasCht8305,
                hasCamera = cameraPresent(),
                hasMicrophone = profile.hasMicrophone,
                appCanSu = profile.appCanSu,
                hasRecents = profile.hasRecents,
                // Declared physical buttons. Populated because a capability whose value is always its
                // `false` default is indistinguishable from "this panel has none", and the navbar
                // no-way-out default reads it as evidence that a panel has no navigation affordance.
                hasEvdevButtons = profile.evdevButtons.isNotEmpty(),
                profileId = profile.id,
                // Carried so a consumer can tell a real declaration from an unpopulated `false`.
                hardwareDeclarationsKnown = profile.declarationsFromCatalog,
                // Profile declaration only, deliberately not the Android/vendor navbar-visibility signals
                // that seed the fresh-install default: those are known to misreport in both directions,
                // and this decides whether "Native" may be selected rather than merely suggested.
                hasNativeNavbar = profile.hasNativeNavbar,
                cpuGovernors = controllers?.cpuGovernorsAvailable ?: cpu.available(),
                // AdbController.available() is exactly Su.available(); reuse this snapshot's one root
                // authority probe rather than opening another shell transaction.
                networkAdb = directSuReady,
                zigbeePresent = controllers?.let {
                    zigbeeCapabilityPresent(
                        declaredGateway = profile.zigbeeGatewayDir != null,
                        observation = it.zigbee,
                    )
                } ?: (profile.zigbeeGatewayDir != null && zigbeePresence.get() == true),
                relays = controllers?.relayCount ?: relay.count(),
                buttonLeds = controllers?.buttonLedCount ?: relay.ledCount(),
                hasSystemDarkMode = Build.VERSION.SDK_INT >= 29,   // Android 10+ has the system dark/light setting
                companionInstalled = UpdateChecker.COMPANION_PKGS.any {
                    runCatching { packageManager.getPackageInfo(it, 0) }.isSuccess
                },
                webViewManaged = profile.recommendedWebView != null,
                shizukuReady = shizukuReady,
                canInstallVerifiedApps = typedShellControlReady,
                canCaptureAndInput = typedShellControlReady,
                canSetDisplay = typedShellControlReady,
            ).also { cost.work(units = 1) }
        } catch (e: Exception) {
            cost.outcome(FeatureCostOutcome.FAILURE)
            throw e
        } finally {
            cost.close()
        }
    }

    /** Run one destructive operation under the same process-wide ticket used by HTTP restore/APK routes. */
    private suspend fun completeOperation(
        progress: InstallProgress.Ticket,
        logLabel: String,
        operation: suspend () -> InstallOperationResult,
        after: suspend (String) -> Unit = {},
    ): String {
        var result = "cancelled"
        var resultPresentation: InstallPresentation? = InstallPresentation("operation-cancelled")
        try {
            val completed = try {
                operation()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "$logLabel failed", e)
                InstallOperationResult("error: ${e.message}")
            }
            result = completed.message
            resultPresentation = completed.presentation
            Log.i(TAG, "$logLabel: $result")
            after(result)
            // Keep the destructive-operation lane until post-install activation is complete. WebView
            // activation deliberately waits before requesting a process boundary; releasing the lane
            // first lets the blocked screen start a second install that the pending restart kills.
            InstallProgress.finish(progress, result, presentation = resultPresentation)
            return result
        } finally {
            // Covers cancellation, fatal errors, and direct callers; stale tickets cannot clear a successor.
            InstallProgress.finish(progress, result, presentation = resultPresentation)
        }
    }

    /** Start an asynchronous destructive operation only when the shared lane is free. */
    private fun launchOperation(
        component: String,
        owner: String,
        logLabel: String,
        operation: suspend () -> InstallOperationResult,
        after: suspend (String) -> Unit = {},
        onStarted: (InstallProgress.Ticket) -> Unit = {},
    ): Boolean {
        val progress = InstallProgress.start(
            component,
            InstallPresentation("operation-working", mapOf("owner" to owner)),
        ) ?: return false
        onStarted(progress)
        val job = scope.launch { completeOperation(progress, logLabel, operation, after) }
        // A cancelled job can complete before its body starts, so the body's finally is not sufficient.
        InstallProgress.finishOnFailure(progress, job)
        return true
    }

    /** Run a scheduled destructive operation inline, or skip it when another owner holds the lane. */
    private val successorHandoffGate = kotlinx.coroutines.sync.Mutex()
    @Volatile private var lastSuccessorHandoffDetail: String? = null

    /**
     * Bridge build only: install and start the successor identity. One offer at a time, whether the
     * periodic pass or the HTTP trigger asked; the outcome is logged only when it changes, because a
     * panel that never migrates repeats the same refusal on every pass.
     */
    internal suspend fun offerSuccessorHandoff(allowInstall: Boolean = true): SuccessorHandoff.Outcome? {
        if (!AppIdentity.IS_BRIDGE) return null
        successorHandoffGate.lock()
        try {
            // A finite host request may have waited behind another offer during teardown.
            if (teardownBoundary.isStopping) return null
            return SuccessorHandoff(AndroidSuccessorHandoffPorts(this, config, system))
                .offer(AppIdentity.SUCCESSOR, allowInstall)
                .also { outcome ->
                    if (outcome.detail != lastSuccessorHandoffDetail) {
                        lastSuccessorHandoffDetail = outcome.detail
                        Log.i(TAG, "successor handoff: ${outcome.detail}")
                    }
                }
        } finally {
            successorHandoffGate.unlock()
        }
    }

    private fun requestInstalledSuccessorHandoff(reason: String) {
        if (!AppIdentity.IS_BRIDGE) return
        if (teardownBoundary.isStopping) {
            Log.i(TAG, "installed successor handover skipped: $reason, stopping")
            return
        }
        Log.i(TAG, "installed successor handover queued: $reason, scopeActive=${scope.coroutineContext[Job]?.isActive}")
        scope.launch {
            Log.i(TAG, "installed successor handover entered: $reason, stopping=${teardownBoundary.isStopping}")
            try {
                if (!teardownBoundary.isStopping) offerSuccessorHandoff(allowInstall = false)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w(TAG, "installed successor handover failed: $reason", error)
            }
        }.invokeOnCompletion { failure ->
            // Also records cancellation before the coroutine body gets its first dispatch.
            Log.i(TAG, "installed successor handover completed: $reason, result=${failure?.javaClass?.simpleName ?: "completed"}")
        }
    }

    private suspend fun runOperation(
        component: String,
        owner: String,
        logLabel: String,
        operation: suspend () -> InstallOperationResult,
        after: suspend (String) -> Unit = {},
    ): String? {
        val progress = InstallProgress.start(
            component,
            InstallPresentation("operation-working", mapOf("owner" to owner)),
        ) ?: run {
            Log.i(TAG, "$logLabel skipped: another destructive operation is running")
            return null
        }
        return completeOperation(progress, logLabel, operation, after)
    }

    /** Activate a successfully installed WebView provider in whichever renderer owns the dashboard. */
    private suspend fun activateWebView(result: WebViewInstaller.HealResult, verb: String) {
        if (result !is WebViewInstaller.HealResult.Installed) return
        kotlinx.coroutines.delay(2_000)
        if (config.dashboardPackage.isBlank() || config.dashboardPackage == io.github.maxlyth.hapaneld.control.SystemController.BUILTIN_DASHBOARD) {
            // A WebView provider binds once per process. The asynchronous trigger has already replied,
            // while the operation ticket stays owned until this boundary is requested; START_STICKY
            // then restarts the service and HOME on the new provider.
            Log.i(TAG, "WebView $verb — restarting process so the built-in renderer binds the new provider")
            kotlinx.coroutines.delay(1_000)
            requestSafeProcessBoundary("binding the $verb WebView provider")
            return
        }
        system.reloadDashboard(config.dashboardPackage)
    }

    /** Scheduled WebView auto-update (opt-in, update tick): advance the System WebView to the profile's
     *  pinned build when it's newer than the running engine. A loop guard skips re-downloading a version
     *  that already installed but never became the provider; it clears when the pinned version advances. */
    private suspend fun autoUpdateWebView(): WebViewInstaller.HealResult {
        val rec = profile.recommendedWebView
            ?: return WebViewInstaller.HealResult.NoAction(
                "skipped: no managed WebView",
                InstallPresentation("managed-no-recommendation", mapOf("component" to "webview")),
            )
        val engineMajor = io.github.maxlyth.hapaneld.http.PanelInfo.webViewStatus(this@PaneldService).engineMajor
        if (io.github.maxlyth.hapaneld.util.WebViewInstaller.shouldSkipAutoUpdate(config.webViewAutoLastVersion, rec.version, rec.major, engineMajor)) {
            val skipped = "skipped: ${rec.version} already attempted but engine is ${engineMajor ?: "?"}; manual heal may be needed"
            Log.w(TAG, "WebView auto-update: $skipped")
            return WebViewInstaller.HealResult.NoAction(
                skipped,
                InstallPresentation(
                    "managed-attempt-recorded",
                    mapOf(
                        "component" to "webview",
                        "version" to rec.version,
                        "current" to (engineMajor?.toString() ?: "?"),
                    ),
                ),
            )
        }
        val r = io.github.maxlyth.hapaneld.util.WebViewInstaller.heal(
            this@PaneldService, profile, engineMajor = engineMajor, force = false, autoUpdate = true,
        )
        // Persist only terminal evidence. A signature-locked provider rejection should not re-download
        // forever, but a transient network/staging/storage/root failure must retry on the next daily tick.
        // A pin bump clears the guard; the manual button always retries regardless of the marker. The
        // caller owns successful provider activation so manual, component, and scheduled paths cannot drift.
        if (io.github.maxlyth.hapaneld.util.WebViewInstaller.shouldRecordAutoAttempt(r)) {
            config.setWebViewAutoLastVersion(rec.version)
        }
        return r
    }

    /** Companion internal_url repair (Install-tab button): copy each server's external_url into a blank
     *  internal_url so HA 2026.7 stops rejecting the dashboard with "Missing 'Host' header". Off-thread
     *  (su force-stops + relaunches the Companion). */
    private fun repairCompanionUrl(): Boolean {
        return launchOperation(
            component = "Companion URL repair",
            owner = "companion-url-repair",
            logLabel = "Companion internal_url repair",
            operation = {
                val result = io.github.maxlyth.hapaneld.control.CompanionDb.repairInternalUrlResult(
                    this@PaneldService,
                    io.github.maxlyth.hapaneld.control.Su,
                )
                InstallOperationResult(result.message, result.presentation)
            },
        )
    }

    private suspend fun prepareSelfUpdateChannel(
        requested: String,
        force: Boolean,
    ): io.github.maxlyth.hapaneld.http.SelfUpdateChannelPreflight =
        when (val prepared = SelfUpdater.admitConfigCoupledChannel(
            SelfUpdater.prepareChannelUpdate(this@PaneldService, requested, force),
        )) {
            is SelfUpdater.ChannelPreparation.Unresolved ->
                io.github.maxlyth.hapaneld.http.SelfUpdateChannelPreflight.Unresolved(
                    prepared.message,
                    prepared.presentation,
                )
            is SelfUpdater.ChannelPreparation.UpToDate ->
                io.github.maxlyth.hapaneld.http.SelfUpdateChannelPreflight.UpToDate(
                    prepared.message,
                    prepared.presentation,
                )
            is SelfUpdater.ChannelPreparation.Refused ->
                io.github.maxlyth.hapaneld.http.SelfUpdateChannelPreflight.Refused(
                    prepared.message,
                    prepared.presentation,
                )
            is SelfUpdater.ChannelPreparation.Ready -> {
                val candidate = prepared.prepared
                io.github.maxlyth.hapaneld.http.SelfUpdateChannelPreflight.Ready(
                    message = prepared.message,
                    requiresRecovery = prepared.databaseDisposition ==
                        AppInstaller.SelfInstallDatabaseDisposition.RECOVER,
                    revalidateForConfigCommit = {
                        AppInstaller.revalidatePreparedDirectForConfigCommit(
                            this@PaneldService,
                            candidate,
                        )
                    },
                    install = {
                        SelfUpdater.installPreparedOutcome(this@PaneldService, candidate).let {
                            io.github.maxlyth.hapaneld.http.SelfUpdateChannelInstallResult(
                                message = it.message,
                                installed = it.installed,
                                presentation = it.presentation,
                            )
                        }
                    },
                    discardPrepared = candidate::close,
                    presentation = prepared.presentation,
                )
            }
        }

    /** MQTT channel switches own the install lane from preflight through the exact prepared install. */
    private fun launchSelfUpdateChannelChange(requested: String, previous: String): Boolean = launchOperation(
        component = "ha-paneld",
        owner = "paneld",
        logLabel = "self-update channel $previous -> $requested",
        operation = {
            when (val preflight = prepareSelfUpdateChannel(
                requested,
                force = previous == "prerelease" && requested == "stable",
            )) {
                is io.github.maxlyth.hapaneld.http.SelfUpdateChannelPreflight.Ready -> preflight.use {
                    if (it.requiresRecovery) {
                        return@launchOperation InstallOperationResult(
                            "refused: an update-channel change cannot recover an older database snapshot",
                            InstallPresentation(
                                "install-durable-rejection",
                                mapOf("component" to "paneld"),
                            ),
                        )
                    }
                    var commitRefusal: String? = null
                    if (!config.synchronizedTransaction {
                            if (config.updateChannel != previous || !config.selfUpdate) {
                                false
                            } else {
                                // Revalidate the exact bytes, signer, boundary and current database as
                                // DIRECT at the final boundary before this channel preference mutates.
                                // DB_COMPAT_MUTATION_ANCHOR: MQTT_CONFIG_COMMIT
                                commitRefusal = it.revalidateForConfigCommit()
                                if (commitRefusal != null) false
                                else config.applyBatch { config.setUpdateChannel(requested) }
                            }
                        }
                    ) {
                        return@launchOperation InstallOperationResult(
                            commitRefusal?.let { refusal -> "refused: $refusal" }
                                ?: "refused: update channel changed during compatibility preflight",
                            InstallPresentation(
                                "install-durable-rejection",
                                mapOf("component" to "paneld"),
                            ),
                        )
                    }
                    mqtt.publishSelfUpdateChannelState()
                    val result = installCommittedSelfUpdateChannel(
                        install = it.install,
                        rollback = { rollbackSelfUpdateChannel(requested, previous) },
                    )
                    selfUpdateChannelOperationResult(result)
                }
                is io.github.maxlyth.hapaneld.http.SelfUpdateChannelPreflight.UpToDate -> {
                    if (!config.synchronizedTransaction {
                            if (config.updateChannel != previous || !config.selfUpdate) false
                            else config.applyBatch { config.setUpdateChannel(requested) }
                        }
                    ) InstallOperationResult(
                        "refused: update channel changed during compatibility preflight",
                        InstallPresentation(
                            "install-durable-rejection",
                            mapOf("component" to "paneld"),
                        ),
                    )
                    else {
                        mqtt.publishSelfUpdateChannelState()
                        InstallOperationResult(preflight.message, preflight.presentation)
                    }
                }
                is io.github.maxlyth.hapaneld.http.SelfUpdateChannelPreflight.Refused ->
                    InstallOperationResult(preflight.message, preflight.presentation)
                is io.github.maxlyth.hapaneld.http.SelfUpdateChannelPreflight.Unresolved ->
                    InstallOperationResult(preflight.message, preflight.presentation)
            }
        },
        after = { mqtt.publishSelfUpdateChannelState() },
    )

    /** Direct HTTP/import saves promote their config claim without releasing the shared lane. */
    private fun completeSelfUpdateChannelChange(
        preflight: io.github.maxlyth.hapaneld.http.SelfUpdateChannelPreflight.Ready?,
        progress: InstallProgress.Ticket?,
        previous: String,
        committed: String,
    ) {
        liveSettingAuthority.discard("update_channel")
        mqtt.publishSelfUpdateChannelState()
        if (preflight == null) {
            progress?.let { InstallProgress.finish(it, "self-update candidate absent") }
            return
        }
        val promoted = requireNotNull(progress) { "prepared channel install requires promoted ownership" }
        val installed = AtomicBoolean(false)
        val job = scope.launch {
            completeOperation(
                promoted,
                "self-update committed channel",
                operation = {
                    val result = preflight.use { ready ->
                        installCommittedSelfUpdateChannel(
                            install = ready.install,
                            rollback = { rollbackSelfUpdateChannel(committed, previous) },
                            onInstalled = { installed.set(true) },
                        )
                    }
                    selfUpdateChannelOperationResult(result)
                },
            )
        }
        job.invokeOnCompletion { cause ->
            cleanupCanceledCommittedSelfUpdateChannel(
                cause = cause,
                installed = installed.get(),
                discardPrepared = preflight::close,
                rollback = { rollbackSelfUpdateChannel(committed, previous) },
                finishProgress = { InstallProgress.finish(promoted, "cancelled") },
            )
        }
    }

    /** Roll back only the channel this failed exact candidate committed; a newer user choice wins. */
    private fun rollbackSelfUpdateChannel(failedChannel: String, previousChannel: String) {
        config.synchronizedTransaction {
            val rollback = failedSelfUpdateChannelRollback(
                config.updateChannel,
                failedChannel,
                previousChannel,
            ) ?: return@synchronizedTransaction false
            config.applyBatch { config.setUpdateChannel(rollback) }
        }
        liveSettingAuthority.discard("update_channel")
        mqtt.publishSelfUpdateChannelState()
    }

    private fun attachSoftwareUpdateObservers() {
        // Targets persisted by an earlier process: a restart keeps reporting the last known release.
        config.softwareUpdateTargets.let { (paneld, companion) -> UpdateChecker.restoreTargets(paneld, companion) }
        InstallProgress.observer = softwareProgressObserver
        UpdateChecker.onChecked = softwareCatalogObserver
    }

    private fun detachSoftwareUpdateObservers() {
        if (InstallProgress.observer === softwareProgressObserver) InstallProgress.observer = null
        if (UpdateChecker.onChecked === softwareCatalogObserver) UpdateChecker.onChecked = null
    }

    /** One coherent read of everything the MQTT update entities report, taken on MQTT workers. */
    private fun softwareUpdateSources(): SoftwareUpdateSources {
        val paneldChannel = config.updateChannel
        val companionChannel = config.companionUpdateChannel
        val cap = profile.companionMaxVersion
        val progress = InstallProgress.presentationSnapshot()
        fun installed(pkg: String): String? =
            runCatching { packageManager.getPackageInfo(pkg, 0).versionName ?: "" }.getOrNull()
        return SoftwareUpdateSources(
            paneldVersion = BuildConfig.VERSION_NAME,
            paneldChannel = paneldChannel,
            paneldTarget = UpdateChecker.paneldTarget(paneldChannel)?.let {
                SoftwareTarget(it.version, it.tag, it.releaseUrl)
            },
            companionMinimalVersion = installed(CompanionInstaller.MINIMAL_PKG),
            companionFullVersion = installed(CompanionInstaller.FULL_PKG),
            companionChannel = companionChannel,
            companionCap = cap,
            companionTarget = UpdateChecker.companionTarget(companionChannel, cap)?.let {
                SoftwareTarget(it.version, it.tag, it.releaseUrl, it.capped, it.newestVersion)
            },
            runningOperation = progress.component.takeIf { progress.running },
            panelAssistantOwnsPaneldUpdate = panelAssistantUpdateLease.active(),
        )
    }

    /** Install/update a managed component from the Install tab (POST /api/v1/install/component). Runs
     *  off-thread; progress is reported via InstallProgress so the web UI can poll. action="reinstall"
     *  forces even when the installed build is already current. Single-slot (InstallProgress.start gates). */
    private fun installComponent(
        name: String,
        action: String,
        version: String,
        onStarted: (InstallProgress.Ticket) -> Unit = {},
    ): Boolean {
        val label = when (name) {
            "paneld" -> "ha-paneld"; "companion" -> "HA Companion"; "webview" -> "System WebView"; else -> name
        }
        val force = action == "reinstall"
        val tag = version.takeIf { it.isNotBlank() }
        // The WebView heal returns a typed result; capture it so `after` can reactivate the provider on
        // the typed success variant while the operation still surfaces its status string to InstallProgress.
        var webViewHeal: WebViewInstaller.HealResult? = null
        return launchOperation(
            component = label,
            owner = when (name) {
                "paneld" -> "paneld"
                "companion" -> "companion"
                "webview" -> "webview"
                else -> "apk"
            },
            logLabel = "install $name",
            operation = {
                when (name) {
                    // A specific picked version installs that exact tag; otherwise the channel's newest.
                    "paneld" -> if (tag != null) SelfUpdater.installVersionResult(this@PaneldService, tag)
                        else SelfUpdater.checkAndUpdateResult(this@PaneldService, config.updateChannel, force = force)
                    "companion" -> if (tag != null) CompanionInstaller.installVersionResult(
                        this@PaneldService,
                        tag,
                        profile.companionMaxVersion,
                    )
                        else CompanionInstaller.installOrUpdateResult(
                            this@PaneldService,
                            force = force,
                            channel = config.companionUpdateChannel,
                            maxVersion = profile.companionMaxVersion,
                        )
                    "webview" -> WebViewInstaller.heal(this@PaneldService, profile, engineMajor = null, force = true)
                        .also { webViewHeal = it }
                        .let { InstallOperationResult(it.status, it.presentation) }
                    else -> InstallOperationResult("unknown component")
                }
            },
            after = {
                webViewHeal?.let { activateWebView(it, "healed") }
                // Refresh the available-update list so the banner + Install tab reflect the new state.
                runCatching {
                    UpdateChecker.check(
                        this@PaneldService,
                        config.updateChannel,
                        config.companionUpdateChannel,
                        profile.companionMaxVersion,
                    )
                }
            },
            onStarted = onStarted,
        )
    }

    /** The resolved renderer-target snapshot for perf smoothness attribution (the built-in sentinel, a
     *  foreign dashboard package, or none), resolved here off the sampling path and handed to PerfReader
     *  as an immutable value so PerfReader never touches PackageManager on the hot path. */
    private fun rendererTargetSnapshot(): RendererTarget? =
        RendererResolver.resolveControlTarget(config.dashboardPackage) {
            runCatching { packageManager.getPackageInfo(it, 0) }.isSuccess
        }

    /** Advertise the button-event entity only if our a11y service is actually enabled. */
    private fun accessibilityEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        )
        return enabled?.contains(packageName) == true
    }

    /** Whether SYSTEM_ALERT_WINDOW is held — required to draw the soft-navbar overlay. */
    private fun canDrawOverlays(): Boolean = Settings.canDrawOverlays(this)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (guardDbRedirect) return START_NOT_STICKY
        if (identityMigrationStandby) return if (identityMigrationSticky) START_STICKY else START_NOT_STICKY
        // Start subsystems once. Android re-delivers onStartCommand on every startForegroundService()
        // re-issue and on START_STICKY re-create; re-running this block would call server.start() again,
        // binding a second Ktor server on :8888 -> BindException crashes the process (and would also
        // double-start mqtt/mdns/sensors). started is reset in onDestroy so a genuine restart re-inits.
        // A stood-down generation has no owner to start. START_STICKY is the point of keeping it: the
        // live started-service record is what Android recreates once the committed process has exited.
        if (standingDown) return START_STICKY
        // Generic RELEASE also runs on abort. Only the host's separate, post-commit handoff action
        // may request migration; retain it across cold startup or an already-starting generation.
        if (!teardownBoundary.isStopping && installedHandoffWakeRequested(AppIdentity.IS_BRIDGE, intent?.action)) {
            val ready = installedHandoffWake.request()
            Log.i(TAG, "installed successor handover requested: host commit, ready=$ready")
            if (ready) requestInstalledSuccessorHandoff("host commit")
        }
        if (started) return START_STICKY
        started = true
        val startupActivationGeneration = profileActivationGeneration
        var initialNativeDemand: PanelAssistantTransportDemand? = null
        val startup = runtime.start(block = runtimeStart@{ activeRuntime ->
            // A prior START_STICKY instance may still be draining after its bounded main-thread wait. It
            // never releases this in-process fence: completed teardown exits the process, guaranteeing
            // that no old hardware owner can overlap the replacement generation.
            restartLease.awaitPredecessor()
            if (teardownBoundary.isStopping) return@runtimeStart
            // The predecessor this generation waited behind may have been the one that retired the
            // bridge. A retired bridge starts nothing, however its start was queued.
            if (IdentityMigrationGate.disposition(this) == StartDisposition.RETIRED_BRIDGE) return@runtimeStart

            // Everything below can start work, write hardware state, attach a process-global owner, or
            // create an overlay. Keep all of it behind the predecessor fence, not merely HTTP/MQTT start.
            // Publish each holder before binding can announce its first state to a live renderer.
            HaNetworkPathRuntime.install(haNetworkPath)
            haExactEntityStream.bindNetworkPath(haNetworkPath)
            PathProbeRuntime.install(haPathProbe, haSocketClock)
            haExactEntityStream.bindPathProbe(haPathProbe)
            HaLifecycleRuntime.install(haLifecycle)
            haExactEntityStream.bindLifecycle(haLifecycle)
            activeRuntime.mqtt.haLifecycleLease?.let { lease ->
                HaLifecycleRuntime.installMqttLease(haLifecycle, lease) {
                    runtime.observe()?.value?.mqtt?.isConnected() == true
                }
            }
            BuiltinDashboard.onHaLifecycleChanged()
            // Registration can call back immediately when the renderer has already settled.
            BuiltinDashboard.setRendererSettledListener(rendererSettledForLifecycle)
            io.github.maxlyth.hapaneld.camera.CameraPermissionPrompt.install(
                object : io.github.maxlyth.hapaneld.camera.CameraPermissionPrompt.Store {
                    override var declined: Boolean
                        get() = config.cameraPermissionDeclined
                        set(value) { config.cameraPermissionDeclined = value }
                },
            )
            config.registerChangeListener(voicePrefsListener)
            voiceForegroundRetry = { voice.retryStart() }
            scope.launch { if (!teardownBoundary.isStopping) voice.start() }
            startStorageHealthChecks()
            reconcileHelperInstallStaging()
            registerBrightnessPreferenceObserver()
            refreshAdaptiveBrightnessInputs(restartSource = false)
            autoBright.activate()
            brightness.applyPreventIdleDim(config.preventIdleDim, config)
            EntityLearningRuntime.attach(entityLearning)
            attachSoftwareUpdateObservers()
            // Kiosk is config-owned across boots so every restart retains its deliberate unlocked
            // window. Never let a stale/in-flight HTTP journal bypass that delay or override a newer OFF.
            if (!liveSettingAuthority.discard("kiosk_lock")) {
                Log.w(TAG, "could not discard stale kiosk live-setting journal")
            }
            if (config.kioskLock && !kiosk.isPersistentPolicyEligible()) {
                Log.w(TAG, "kiosk lock is unavailable on this profile; clearing stale desired state")
                if (!config.commitKioskLock(false)) {
                    Log.w(TAG, "could not durably clear unsupported kiosk setting")
                }
            }
            val kioskRecoveredAtStartup = kiosk.recoverPersistentState(config.kioskLock)
            if (!kioskRecoveredAtStartup) {
                Log.w(TAG, "pending kiosk platform state could not be recovered; continuing unlocked")
            }
            if (teardownBoundary.isStopping) return@runtimeStart
            // Resolve touch-sound intent before the HTTP server can accept a save, then reassert it in
            // both directions so the panel matches what it reports. Touch sound never changes stream
            // volume; an ON reassertion also loads the owned click sample for this process.
            val touchSoundIntent = resolveTouchSoundIntentOnce()
            if (!touchSound.reassert(touchSoundIntent).applied) {
                Log.w(TAG, "touch sound could not be reasserted at startup; intent remains $touchSoundIntent")
            }
            bootChime.applyPersisted()
            sensors.prepare()
            proximityWizard = ProximityWizardCoordinator(
                startSession = sensors::startProximityCalibration,
                active = sensors::proximityCalibrationActive,
                status = sensors::proximityJson,
                visible = sensors::proximityCalibrationVisible,
                localAction = sensors::proximityCalibrationAction,
                cancel = sensors::cancelProximitySession,
                heartbeat = sensors::proximityCalibrationHeartbeat,
                reset = sensors::resetProximityToProfile,
                acquireDisplay = screen::acquireVisibleHold,
                releaseDisplay = screen::releaseVisibleHold,
                launch = system::launchProximityWizard,
                narrator = ProximityWizardNarrator(speak = { text, localeTag, onGeneration ->
                    io.github.maxlyth.hapaneld.assist.AssistPipelineClient(config).speakText(
                        text,
                        localeTag,
                        io.github.maxlyth.hapaneld.assist.AnnouncementLanePlayback(
                            audio,
                            onGeneration = onGeneration,
                        ),
                    )
                }, stopPlayback = { generation -> audio.cancelGeneration(generation) }),
            )
            when (BundledHelperInstaller.ensureCurrent(this@PaneldService)) {
                BundledHelperInstaller.Result.INSTALLED -> Log.i(TAG, "migrated bundled root helper for this release")
                BundledHelperInstaller.Result.FAILED ->
                    Log.w(TAG, "root helper migration failed; versioned helper features remain unavailable")
                BundledHelperInstaller.Result.BLOCKED_ACTIVE ->
                    Log.i(TAG, "retaining current root helper while Guard DB maintenance owns recovery")
                BundledHelperInstaller.Result.REPROVISION_REQUIRED ->
                    Log.w(TAG, "root helper matches this release but is not canonical; reprovision required")
                BundledHelperInstaller.Result.ALREADY_CURRENT,
                BundledHelperInstaller.Result.SKIPPED -> Unit
            }
            val startupCompanionPackage = CompanionInstaller.installedPkg(this)
                ?: system.resolveDashboard(config.dashboardPackage)
            restoreCompanionLaunchSuppression(
                packageName = startupCompanionPackage,
                operationState = companionDataOperationState,
                operationStatus = HelperClient::companionOperationStatus,
                retain = { lease ->
                    scope.launch(Dispatchers.IO) {
                        retainCompanionLeaseUntilHelperIdle(
                            lease = lease,
                            operationState = companionDataOperationState,
                            operationStatus = HelperClient::companionOperationStatus,
                            pollMs = COMPANION_STARTUP_STATUS_POLL_MS,
                            afterRelease = {
                                if (!teardownBoundary.isStopping) {
                                    runCatching {
                                        rendererPreparation.reconcileStartup(
                                            ensureHome = { pkg, ready ->
                                                system.applyLauncherHomePolicy(config.launcherPackage, pkg, ready)
                                            },
                                            launchHome = system::launchHome,
                                        )
                                    }.onFailure {
                                        Log.w(TAG, "Companion-safe startup renderer retry failed", it)
                                    }
                                }
                            },
                        )
                    }
                },
            )
            // Learning resolves the HA instance identity through this runtime. Start mDNS before either
            // the initial learner sync or a borrowed renderer commit can notify the learner of a target.
            val rendererResult = prepareEntityLearningStartup(
                startMdns = activeRuntime.mdns::start,
                reconcileRenderer = {
                    // Re-assert the configured HOME and repair an interrupted switch to the built-in renderer
                    // before the HTTP surface can accept another configuration transaction. The durable retry
                    // condition is built-in + blank URL; borrowed connection and zoom commit atomically.
                    rendererPreparation.reconcileStartup(
                        ensureHome = { pkg, ready ->
                            system.applyLauncherHomePolicy(config.launcherPackage, pkg, ready)
                        },
                        launchHome = { pkg -> system.launchHome(pkg) },
                    )
                },
                startLearning = entityLearning::start,
            )
            if (rendererResult == RendererPreparationCoordinator.Result.PERSIST_FAILED) {
                Log.e(TAG, "built-in renderer startup preparation did not commit; leaving it retryable")
            }
            if (rendererResult == RendererPreparationCoordinator.Result.CLOSED) return@runtimeStart
            // Resolve "Open in HA" from the prepared built-in renderer session even when MQTT is disabled.
            // MqttBridge owns the compatibility fallback, but native HA URL/token always win.
            activeRuntime.mqtt.maybeResolveHaLink()
            // Keep the SoC + network awake (screen still free to sleep) so Doze/suspend can't freeze the
            // MQTT reactor + keepalive into a half-open, unreachable connection. On by default; see keep_awake.
            runCatching { power.apply(config.keepAwake) }
            io.github.maxlyth.hapaneld.http.PerfReader.start(scope, packageName, rendererTargetSnapshot())
            server.start()
            // A successor with a migration in progress continues it now that its own server listens.
            identityMigration.startInService()
            // Startup always reconciles both additions and removals from durable desired state against
            // the write-ahead overlay ownership markers. This completes work handed off by profile restart.
            server.requestTameReconcile()
            activeRuntime.mqtt.start()
            autoSleep.start()
            if (profile.zigbeeGatewayDir != null) zigbeeHealth.start()
            liveSettingAuthority.replayKeysObserved(MqttBridge.APPLY_SETTING_KEYS) { key, value, previous, fence ->
                replayLiveSettingObserved(activeRuntime.mqtt, key, value, previous, fence)
            }
            // Forward our own logcat to the configured aggregator (no-op unless a sink host is set).
            logShipper.start()
            // Restore the soft navbar to its persisted mode (no-op when Off / no overlay permission).
            navbar.apply(config.navbarMode)
            // Start the app watchdog if enabled (off by default; self-heals a dead/abandoned dashboard).
            watchdog.apply(config.watchdogEnabled)
            // Experimental kiosk lock: a reboot CLEARS the runtime lock (by design — the anti-brick net), so
            // re-assert it after a delay if it was enabled. The delay leaves an unlocked window each boot so
            // an admin is never stranded; skipped if it was turned off (corner gesture / :8888 / HA) meanwhile.
            if (config.kioskLock || !kioskRecoveredAtStartup) scheduleKioskReassert()
            // Boot recovery gives a durable interrupted-OFF marker priority over the older persisted
            // ownership bit; only a marker-free ON intent may re-enable TCP. See AdbController.reassert.
            runCatching { adb.reassert() }
            startScreenOnReconciliation()
            startWebViewRebindWatch()
            startWebViewRepairOffer()
            sensors.start(
                onLux = { lux ->
                    submitIlluminanceIfExposed(
                        exposed = config.haExposed("illuminance", true),
                        lux = lux,
                        submit = lightMqttPublisher::submit,
                    )
                },
                onLuxRaw = autoBright::submitLux,
                onProximity = { near, level, reportMask ->
                    mqtt.publishProximity(near, level, reportMask)
                    autoSleep.noteProximityState(near.takeIf {
                        !sensors.proximityCalibrationActive() && sensors.proximityPresenceReady()
                    })
                },
                onPresenceApproach = {
                    val proximityGeneration = sensors.proximityGeneration()
                    val observedAt = android.os.SystemClock.elapsedRealtime()
                    wakeOnWaveWorker.execute {
                        screen.brightenForPresence {
                            !teardownBoundary.isStopping && sensors.proximityPresenceNear() &&
                                sensors.proximityGeneration() == proximityGeneration &&
                                android.os.SystemClock.elapsedRealtime() - observedAt in 0L..750L
                        }
                    }
                },
                onGesture = gesture@{
                    val token = sensors.proximityGestureToken()
                    if (!config.wakeOnWave || !sensors.proximityReady()) {
                        sensors.completeProximityGesture(token, false)
                        return@gesture
                    }
                    val proximityGeneration = sensors.proximityGeneration()
                    val generation = screen.currentOffGeneration()
                    if (generation == null) {
                        sensors.completeProximityGesture(token, false)
                        return@gesture
                    }
                    val settingGeneration = config.wakeOnWaveGeneration
                    val submitted = wakeOnWaveWorker.execute {
                        var accepted = false
                        try {
                            if (
                                teardownBoundary.isStopping || !config.wakeOnWave || !sensors.proximityReady() ||
                                sensors.proximityGeneration() != proximityGeneration ||
                                config.wakeOnWaveGeneration != settingGeneration
                            ) return@execute
                            accepted = screen.wakeIfStillDark(generation) {
                                !teardownBoundary.isStopping && config.wakeOnWave && sensors.proximityReady() &&
                                    sensors.proximityGeneration() == proximityGeneration &&
                                    config.wakeOnWaveGeneration == settingGeneration
                            } == WakeOutcome.WOKEN
                            if (accepted && !teardownBoundary.isStopping) mqtt.publishScreenOn()
                        } finally { sensors.completeProximityGesture(token, accepted) }
                    }
                    if (!submitted) sensors.completeProximityGesture(token, false)
                },
                onTemperature = { c -> mqtt.publishTemperature(c) },
                onHumidity = { h -> mqtt.publishHumidity(h) },
            )
            // Stream daemon-instrumented hardware buttons into the same event entity as a11y capture.
            // It starts and stops with this runtime so a recreated service cannot inherit a blocked reader.
            EvdevButtonClient.start(profile.evdevButtons)
            scope.periodic(
                intervalMs = 24 * 3_600 * 1_000L,
                initialDelayMs = 30_000L, // let startup settle before hitting the network
                tag = TAG,
                name = "update-check",
            ) {
                // Each sub-step is isolated so one failing doesn't skip the others; the periodic boundary
                // is the outer net that keeps the loop alive across an unexpected throw.
                runCatching {
                    UpdateChecker.check(
                        this@PaneldService,
                        config.updateChannel,
                        config.companionUpdateChannel,
                        profile.companionMaxVersion,
                    )
                }
                // Companion self-heal: when enabled, install a missing Companion / update an out-of-date one.
                if (config.companionAutoUpdate) {
                    runOperation(
                        component = "HA Companion",
                        owner = "companion",
                        logLabel = "Companion auto",
                        operation = {
                            CompanionInstaller.installOrUpdateResult(
                                this@PaneldService,
                                channel = config.companionUpdateChannel,
                                maxVersion = profile.companionMaxVersion,
                            )
                        },
                    )
                }
                // System WebView auto-update (opt-in): advance to the profile's pinned build. BEFORE
                // self-update because a successful WebView install also restarts the process.
                refreshWebViewRepairCapability()
            if (config.webViewAutoUpdate) {
                    var webViewHeal: WebViewInstaller.HealResult? = null
                    runOperation(
                        component = "System WebView",
                        owner = "webview",
                        logLabel = "WebView auto-update",
                        operation = {
                            autoUpdateWebView().also { webViewHeal = it }
                                .let { InstallOperationResult(it.status, it.presentation) }
                        },
                        after = { webViewHeal?.let { activateWebView(it, "auto-updated") } },
                    )
                }
                // ha-paneld self-update LAST — a successful install restarts this process (and this loop).
                // The setting defaults on only for capable panels; keep the runtime guard too so an
                // imported/restored true value cannot make an unsupported panel attempt an app install.
                if (config.selfUpdate && capabilitiesSnapshot().canInstallVerifiedApps) {
                    runOperation(
                        component = "ha-paneld",
                        owner = "paneld",
                        logLabel = "self-update auto",
                        operation = { SelfUpdater.checkAndUpdateResult(this@PaneldService, config.updateChannel) },
                    )
                    // Identity migration, bridge build only, and only once this build is the one its
                    // channel wants (a successful self-update above restarts the process first). Every
                    // refusal leaves the panel on the bridge; the next pass offers again.
                    offerSuccessorHandoff()
                }
            }
            startMqttWatchdog()
            // Never-blank-screen watchdog. A screen-off kills the backlight but leaves the device
            // interactive, and nothing re-lights it — so a stray/stale screen-off (e.g. the retained-command
            // strand) or a firmware idle-dim can leave the panel dark and apparently bricked. If the screen
            // is dark but ha-paneld did NOT deliberately turn it off, re-light it; a user-intended
            // "screen off" (isIntendedOff) is left alone.
            scope.periodic(
                intervalMs = SCREEN_WATCHDOG_MS,
                initialDelayMs = 15_000L, // let boot settle before the first check
                tag = TAG,
                name = "never-blank",
            ) {
                if (screen.recoverUnexpectedDark()) {
                    Log.w(TAG, "screen dark with no intent — re-lighting (never-blank guard)")
                    mqtt.publishScreenOn()
                }
                repairAdminHomeFromCapturedRoute()
            }
            // The HTTP surface, network owner, sensors, evdev and periodic safety owners have all been
            // constructed successfully. Only now may a pending profile replace the previous LKG target.
            profileActivationGeneration?.let { generation ->
                if (profileRegistry.markActivationHealthy(generation)) {
                    Log.i(TAG, "profile activation generation $generation is healthy")
                    profileActivationGeneration = null
                } else {
                    Log.w(TAG, "profile activation generation $generation could not be marked healthy")
                    throw IllegalStateException("profile activation health state could not be persisted")
                }
            }
            if (profileActivationGeneration == null) {
                if (!profileRegistry.markResolvedStartupHealthy()) {
                    Log.w(TAG, "could not persist the healthy profile revision snapshot")
                }
            }
            // Best-effort observation belongs after the required control plane and every active owner.
            // It must not contend with helper migration, live-setting replay, ADB reassertion or profile
            // activation proof. This uses the existing service scope and owns no retry/lifecycle.
            scope.launch(Dispatchers.IO) {
                server.prewarm()
            }
            // Issue #93: read-only button-LED GPIO proof in its own coroutine, not queued behind prewarm().
            scope.launch(Dispatchers.IO) { relay.warmUp() }
            initialNativeDemand = currentPanelAssistantTransportDemand()
        }, complete = {
            // Demand construction reads configuration before this short, lock-admitted publication.
            if (!teardownBoundary.isStopping) panelAssistantTransport.replaceDemand(initialNativeDemand)
        })
        Thread({
            val disposition = awaitServiceStartup(startup, startupActivationGeneration)
            Log.i(TAG, "service startup health: $disposition, stopping=${teardownBoundary.isStopping}")
            when (disposition) {
                ServiceStartupDisposition.RUNNING -> {
                    // DHCP may have arrived while ServiceRuntimeOwner was STARTING, when callbacks cannot
                    // borrow a runtime observation. Replay their latest topology after RUNNING is published.
                    mdnsRuntimeReconciler.runtimeRunning()
                    startupRecoveryPrefs.edit().clear().commit()
                    updateForegroundStatus(nativeString(R.string.listening_on_port, config.httpPort))
                    // Ordinary startup must not migrate an already-installed successor before the
                    // host has prepared and committed its transaction. Consume only an explicit wake.
                    if (installedHandoffWake.markStartupHealthy()) {
                        requestInstalledSuccessorHandoff("pending host commit")
                    }
                }
                ServiceStartupDisposition.PROFILE_ACTIVATION_ROLLBACK -> {
                    updateForegroundStatus(nativeString(R.string.degraded_profile_startup))
                    Log.e(TAG, "profile activation startup failed; scheduling rollback restart")
                    profileRestart.request()
                }
                ServiceStartupDisposition.DEGRADED -> {
                    updateForegroundStatus(nativeString(R.string.degraded_startup))
                    val now = android.os.SystemClock.elapsedRealtime()
                    val decision = startupRecoveryDecision(
                        startupRecoveryPrefs.getInt("attempts", 0),
                        startupRecoveryPrefs.getLong("last_at", 0L),
                        now,
                    )
                    startupRecoveryPrefs.edit()
                        .putInt("attempts", decision.nextAttempt)
                        .putLong("last_at", now)
                        .commit()
                    if (decision.restart && recoveryRestart.request()) {
                        Log.e(TAG, "service startup failed; scheduling bounded recovery restart ${decision.nextAttempt}/3")
                    } else {
                        Log.e(TAG, "service startup failed; recovery restart limit reached, remaining degraded")
                    }
                }
            }
        }, "service-startup-health").apply { isDaemon = true; start() }
        registerNetworkCallback()
        return START_STICKY
    }

    /**
     * MQTT reconnect watchdog on a DEDICATED thread — deliberately NOT a coroutine on Dispatchers.IO.
     * On a panel with slow/contended `su` (toolbox su under load), blocking su calls exhaust the IO
     * thread pool, so a coroutine watchdog's post-delay continuation never gets scheduled and it silently
     * stops ticking (observed in the field: MQTT "connected" but zero watchdog ticks, so a
     * half-open connection never self-healed). A plain thread ticks regardless of dispatcher pressure.
     *
     * Two stall modes, both given one fresh-client fallback before a previously-live process may cross
     * the process boundary:
     *   (1) STATE-stuck — HiveMQ's auto-reconnect stalls (transient auth reject on an HA/broker restart,
     *       or its reconnect thread is power-management-deferred); rebuild after 2 non-connected checks.
     *   (2) LIVENESS-stale — the broker dropped the link but HiveMQ never noticed the half-open
     *       (CLOSE-WAIT) socket, so it still reports "connected" while publishing into the void.
     *       isConnected() lies, so key on TRUE liveness: each tick send a heartbeat (a QoS-1 publish the
     *       broker must ACK). The fresh client's exact online + state acknowledgement, not CONNACK,
     *       heartbeat, or completion of the reconnect submission, proves recovery.
     *
     * CRITICAL INVARIANT: the loop thread makes NO potentially-blocking MQTT call. A HiveMQ publish —
     * and even disconnect/rebuild — can block on an internal client monitor exactly when the connection
     * is wedged (the same trap as the sensor-callback ANR), which is precisely when the watchdog is
     * needed. An earlier implementation called heartbeat() inline; the watchdog then froze inside its own
     * probe (observed in the field: thread parked on a futex, liveness 44 min stale, no rebuild). Heartbeat uses
     * a sacrificial side-thread. Rebuild admission is generation-checked by the runtime owner,
     * then the accepted reconnect runs on a recovery worker so neither the watchdog nor the serialized
     * transition lane can be trapped by it. The alternate address family is staged durably BEFORE that
     * potentially wedged owner work is submitted, so a later process boundary cannot restart onto the
     * same route merely because the reconnect callback never entered. One rebuild then retains that client
     * for the existing five-minute progress bound. This prevents the observed 60-second IPv4/IPv6 loop;
     * only exact application readiness starts a new epoch. A previously-live runtime retains its existing
     * process-boundary policy. An announcement wedge gets one durable process escape; its replacement can
     * still alternate once, but cannot inherit an unbounded restart loop.
     * The recovery DECISION lives in [ConnectionSupervisor]; this thread owns only tick cadence and
     * off-thread dispatch.
     */
    private fun startMqttWatchdog() {
        if (mqttWatchdogAlive) return
        mqttWatchdogAlive = true
        val supervisor = ConnectionSupervisor(MQTT_STALE_MS, REBUILD_ABANDON_MS)
        val worker = OwnedThread("mqtt-watchdog", onExit = { mqttWatchdogAlive = false }) {
                data class HeartbeatProbe(val generation: Long, val thread: Thread)
                data class RebuildAttempt(
                    val runtimeGeneration: Long,
                    val completion: java.util.concurrent.Future<Boolean>,
                    val outcome: java.util.concurrent.atomic.AtomicReference<MqttRecoveryOutcome>,
                    val ticket: MqttRecoveryTicket,
                )
                val heartbeats = mutableListOf<HeartbeatProbe>()
                var terminalRecoveryNeeded = false
                var rebuild: RebuildAttempt? = null
                while (mqttWatchdogAlive) {
                    try { Thread.sleep(MQTT_WATCHDOG_MS) } catch (e: InterruptedException) { break }
                    if (!mqttWatchdogAlive) break
                    requestWatchdogLocalObservation(runtime) { it.mqtt }
                    heartbeats.removeAll { !it.thread.isAlive }
                    if (terminalRecoveryNeeded) {
                        if (!teardownBoundary.isStopping && recoveryRestart.request()) break
                        if (!teardownBoundary.isStopping) {
                            Log.w(TAG, "MQTT watchdog terminal recovery was not admitted; retrying next tick")
                        }
                        continue
                    }
                    try {
                        val watchedRuntime = runtime.observe()
                        rebuild?.let { attempt ->
                            when {
                                attempt.runtimeGeneration != watchedRuntime?.generation -> rebuild = null
                                attempt.completion.isDone -> {
                                    rebuild = null
                                    val ownerAccepted = runCatching { attempt.completion.get() }.getOrDefault(false)
                                    when {
                                        !ownerAccepted -> {
                                            supervisor.rebuildNotAdmitted()
                                            if (watchedRuntime.value.mqtt.reconcileRejectedRecovery(attempt.ticket)) {
                                                Log.i(TAG, "MQTT fallback stage reconciled after owner rejection")
                                            } else {
                                                Log.w(TAG, "MQTT fallback rejection could not durably restore its route")
                                            }
                                        }
                                        attempt.outcome.get() == MqttRecoveryOutcome.REBUILT ->
                                            supervisor.rebuildAdmitted()
                                        attempt.outcome.get() == MqttRecoveryOutcome.NO_LONGER_NEEDED -> {
                                            supervisor.recoveryNoLongerNeeded()
                                            Log.i(TAG, "MQTT recovered before queued fallback entered; preserving the live client")
                                        }
                                        else -> {
                                            supervisor.rebuildNotAdmitted()
                                            Log.w(TAG, "MQTT fallback entered without a transport mutation")
                                        }
                                    }
                                }
                            }
                        }
                        if (watchedRuntime == null) {
                            rebuild = null
                            supervisor.runtimeUnavailable()
                            continue
                        }
                        val watched = watchedRuntime.value.mqtt
                        val watchdogObservation = watched.watchdogObservation()
                        val progress = watchdogObservation.progress
                        val now = android.os.SystemClock.elapsedRealtime()
                        val sinceOk = if (progress.lastOkMs == 0L) 0L
                        else (now - progress.lastOkMs).coerceAtLeast(0L)
                        val watchedState = watchdogObservation.state
                        Log.i(TAG, "mqtt watchdog tick: state=$watchedState sinceOk=${sinceOk}ms hb=${heartbeats.map { it.generation }} rebuild=${rebuild != null}")
                        when (val action = supervisor.tick(
                            watchedState,
                            progress.lastOkMs,
                            sinceOk,
                            now,
                            rebuild != null,
                            runtimeGeneration = watchedRuntime.generation,
                            connectionGeneration = progress.connectionGeneration,
                            holdSelectedFamily = watchdogObservation.holdSelectedFamily,
                            applicationReadyEver = watchdogObservation.applicationReadyEver,
                            announcementBoundaryAvailable =
                                watchdogObservation.announcementProcessRecoveryAvailable,
                        )) {
                            is ConnectionSupervisor.Action.Rebuild -> {
                                Log.w(TAG, "MQTT ${action.reason} stall (${sinceOk}ms, state=$watchedState) — forcing one fresh client (flip family=${action.flipFamily})")
                                val observedTicket = watchdogObservation.recoveryTicket
                                val recoveryTicket = if (action.flipFamily) {
                                    watched.stageAlternateFamilyForReconnect(observedTicket)
                                } else {
                                    watched.recoveryTicketForReconnect(observedTicket)
                                }
                                if (recoveryTicket == null) {
                                    supervisor.rebuildNotAdmitted()
                                    Log.w(TAG, "MQTT fallback could not prepare a durable current-runtime ticket")
                                    continue
                                }
                                val outcome = java.util.concurrent.atomic.AtomicReference(
                                    MqttRecoveryOutcome.NOT_ADMITTED,
                                )
                                rebuild = RebuildAttempt(
                                    runtimeGeneration = watchedRuntime.generation,
                                    completion = runtime.reconnect(watchedRuntime) { target ->
                                        if (target.mqtt.state == "discovering") target.mdns.ensureStarted()
                                        // Family selection already belongs to the watchdog admission above.
                                        // The worker owns transport replacement only and must never flip twice.
                                        outcome.set(target.mqtt.reconnect(recoveryTicket))
                                    },
                                    outcome = outcome,
                                    ticket = recoveryTicket,
                                )
                            }
                            is ConnectionSupervisor.Action.SkipRebuild -> {
                                if (action.reason == "discovery") {
                                    Log.w(TAG, "mqtt discovery retry already in flight — not stacking another")
                                } else {
                                    Log.w(TAG, "mqtt ${action.reason} fallback awaiting broker progress ${action.elapsedMs}ms — retaining client")
                                }
                            }
                            is ConnectionSupervisor.Action.ProcessRecovery -> {
                                Log.e(TAG, "MQTT ${action.reason} failure exceeded the fresh-client progress bound")
                                if (action.consumeAnnouncementBudget) {
                                    if (!watched.consumeAnnouncementProcessRecovery(
                                            watchdogObservation.recoveryTicket,
                                        )
                                    ) {
                                        supervisor.recoveryNoLongerNeeded()
                                        Log.i(TAG, "MQTT announcement recovery changed before boundary consumption")
                                        continue
                                    }
                                    // The durable token is already spent. If process admission is
                                    // temporarily rejected, retry the same request without consuming again.
                                    terminalRecoveryNeeded = true
                                }
                                if (recoveryRestart.request()) break
                                Log.w(TAG, "MQTT process recovery request was not admitted; retrying next tick")
                                continue
                            }
                            ConnectionSupervisor.Action.None -> {}
                        }
                        // Do not publish through the old client after deciding to replace it. Ordinary
                        // in-flight publishes are still harmless because replacement proof is scoped to
                        // the new connection generation.
                        if (rebuild != null) continue
                        // Heartbeat LAST and OFF-THREAD: a wedged client blocks the publish, but only its
                        // generation's sacrificial thread. A replacement runtime must not be suppressed by a
                        // heartbeat stranded on the obsolete client.
                        val observed = runtime.observe()
                        val currentConnectionGeneration =
                            observed?.value?.mqtt?.heartbeatConnectionGeneration()
                        when (val admission = HeartbeatAdmission.decide(
                            currentGeneration = currentConnectionGeneration,
                            liveTrackedGenerations = heartbeats.map { it.generation },
                        )) {
                            HeartbeatAdmission.Decision.NoCurrentConnection,
                            HeartbeatAdmission.Decision.CurrentHeartbeatAlive -> Unit
                            is HeartbeatAdmission.Decision.Admit -> {
                                if (admission.replacingStranded) {
                                    FeatureCosts.registry.recordDropped(FeatureCostOperation.MQTT_HEARTBEAT_ADMISSION)
                                    Log.w(TAG, "obsolete mqtt heartbeat is stranded; admitting generation ${admission.generation}")
                                }
                                val target = observed ?: continue
                                val thread = Thread({
                                    if (runtime.isCurrent(target) &&
                                        target.value.mqtt.isCurrentHeartbeatConnection(admission.generation)
                                    ) {
                                        runCatching { target.value.mqtt.heartbeat() }
                                    }
                                }, "mqtt-heartbeat").apply { isDaemon = true; start() }
                                heartbeats += HeartbeatProbe(admission.generation, thread)
                            }
                            is HeartbeatAdmission.Decision.EscalateRecovery -> {
                                FeatureCosts.registry.recordDropped(FeatureCostOperation.MQTT_HEARTBEAT_RECOVERY)
                                Log.e(TAG, "${admission.strandedGenerations} obsolete mqtt heartbeat threads are stranded; requesting bounded process recovery")
                                if (recoveryRestart.request()) break
                                Log.w(TAG, "MQTT heartbeat recovery request was not admitted; retrying next tick")
                            }
                        }
                    } catch (failure: Exception) {
                        terminalRecoveryNeeded = true
                        rebuild = null
                        supervisor.runtimeUnavailable()
                        Log.e(TAG, "MQTT watchdog failed; requesting bounded process recovery", failure)
                        if (!teardownBoundary.isStopping) {
                            if (recoveryRestart.request()) break
                            Log.w(TAG, "MQTT watchdog terminal recovery was not admitted; retrying next tick")
                        }
                    }
                }
        }
        mqttWatchdog = worker
        worker.start()
    }

    /**
     * Nudge MQTT to reconnect the moment the default network returns (Wi-Fi / router flap), instead of
     * waiting out the auto-reconnect backoff. A blank broker in discovery-wait also restarts mDNS before
     * retrying; an already connected, terminally stopped, or auth-recovery runtime is left alone.
     */
    private fun revalidateMdns(
        observed: ServiceRuntimeOwner.Observation<NetworkRuntime>,
        lanIp: String?,
    ) {
        // Discovery can fail while MQTT remains connected, so it cannot share MQTT's state gate.
        // One dedicated latest-value slot prevents callback bursts from saturating MQTT recovery workers.
        mdnsRevalidation.submit(MdnsRevalidation(observed, lanIp))
    }

    private fun registerNetworkCallback() {
        if (netCallback != null) return
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            private var defaultNetwork: Network? = null
            private val wifiAdmission = WifiDiagnosticAdmissionTracker()

            private fun observeTransport(network: Network, capabilities: NetworkCapabilities?) {
                if (network != defaultNetwork) return
                if (!wifiAdmission.changed(wifiDiagnostics.admission(capabilities))) return
                wifiDiagnostics.invalidate()
                if (::server.isInitialized) server.invalidateCapabilitySnapshot()
                runtime.observe()?.value?.mqtt?.refreshNetworkState()
            }

            override fun onAvailable(network: Network) {
                defaultNetwork = network
                val capabilities = cm.getNetworkCapabilities(network)
                // Identity only: the outage tracker is told WHICH network arrived, never what this
                // synchronous snapshot thinks its transport is. That snapshot can predate the
                // authoritative onCapabilitiesChanged, and crediting an episode from it invents or
                // discards outages on stale information.
                wifiOutageTracker.onDefaultAvailable(network.hashCode().toLong())
                if (::panelAssistantTransport.isInitialized) panelAssistantTransport.nudge()
                observeTransport(network, capabilities)
                mdnsRuntimeReconciler.networkChanged(
                    cm.getLinkProperties(network)?.linkAddresses.orEmpty().map { it.address },
                )
                val observed = runtime.observe() ?: return
                val target = observed.value.mqtt
                target.refreshDiscoveryAddress()
                when (networkAvailableAction(target.state, target.configuredBroker)) {
                    NetworkAvailableAction.NONE -> Unit
                    NetworkAvailableAction.RECONNECT -> {
                        Log.i(TAG, "network available — nudging MQTT reconnect")
                        runtime.reconnect(observed) { it.mqtt.reconnect() }
                    }
                    NetworkAvailableAction.RETRY_DISCOVERY -> {
                        Log.i(TAG, "network available — retrying mDNS and MQTT auto-discovery")
                        runtime.reconnect(observed) {
                            it.mqtt.reconnect()
                        }
                    }
                }
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                if (network != defaultNetwork) return
                mdnsRuntimeReconciler.networkChanged(
                    linkProperties.linkAddresses.map { it.address },
                )
                val observed = runtime.observe() ?: return
                observed.value.mqtt.refreshDiscoveryAddress()
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                if (network == defaultNetwork) {
                    wifiOutageTracker.onTransportChanged(
                        network.hashCode().toLong(),
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
                    )
                }
                observeTransport(network, capabilities)
            }

            override fun onLost(network: Network) {
                if (network != defaultNetwork) return
                // Behind the identity guard, so a make-before-break handover never reads as an outage.
                wifiOutageTracker.onDefaultLost()
                observeTransport(network, null)
                mdnsRuntimeReconciler.networkLost()
                defaultNetwork = null
            }
        }
        runCatching { cm.registerDefaultNetworkCallback(cb) }.onSuccess { netCallback = cb }
    }

    private fun startStorageHealthChecks() {
        val recoveryLifecycle = StorageHealthRecoveryLifecycle(
            scope = scope,
            delaysMs = STORAGE_HEALTH_RECOVERY_DELAYS_MS,
            subscribeFailures = StorageHealthRuntime::subscribeDatabaseFailures,
            verify = {
                storageHealthRecoveryAttemptComplete(runQueuedStorageHealthObservation())
            },
            onError = { error ->
                Log.w(TAG, "storage health recovery failed (${error.javaClass.simpleName})")
            },
        )
        val admitted = synchronized(storageHealthLifecycleLock) {
            if (teardownBoundary.isStopping) {
                false
            } else {
                storageHealthRecoveryLifecycle = recoveryLifecycle
                true
            }
        }
        if (!admitted) {
            recoveryLifecycle.close()
            return
        }
        scope.periodic(
            intervalMs = STORAGE_HEALTH_CHECK_MS,
            initialDelayMs = 0L,
            tag = TAG,
            name = "storage-health",
            // The shared health authority already retains a controlled failure category. Do not copy
            // SQLite messages or app-private paths into log shipping/support output.
            onError = { error -> Log.w(TAG, "storage health check failed (${error.javaClass.simpleName})") },
        ) {
            repeat(STORAGE_HEALTH_CHECK_ATTEMPTS) { index ->
                if (teardownBoundary.isStopping) return@periodic
                val attempt = index + 1
                when (runQueuedStorageHealthObservation()) {
                    StorageHealthObservationAttempt.Complete,
                    StorageHealthObservationAttempt.Stopped -> return@periodic
                    StorageHealthObservationAttempt.Retry -> Unit
                }
                if (attempt == STORAGE_HEALTH_CHECK_ATTEMPTS) {
                    Log.w(TAG, "storage health check remained incomplete after $attempt attempts")
                    return@periodic
                }
                Log.w(TAG, "storage health check retrying ($attempt/$STORAGE_HEALTH_CHECK_ATTEMPTS)")
                kotlinx.coroutines.delay(STORAGE_HEALTH_RETRY_MS)
            }
        }
    }

    /** One bounded observation attempt shared by prompt recovery and the independent daily loop. */
    private suspend fun runQueuedStorageHealthObservation(): StorageHealthObservationAttempt =
        storageHealthObservationQueue.run(::runStorageHealthObservation)
            ?: StorageHealthObservationAttempt.Stopped

    /** Same-request HTTP proof joins the daily/recovery queue; it never creates a second SQLite reader
     * authority. Only a complete clean observation earns a nonce-bound status proof. */
    private suspend fun refreshStorageHealthForStatus(): StorageHealthSnapshot? =
        when (runQueuedStorageHealthObservation()) {
            StorageHealthObservationAttempt.Complete -> StorageHealthRuntime.snapshot().takeIf {
                it.schemaVersion > 0 && it.quickCheck == StorageQuickCheck.OK && it.checkedAtMillis > 0L
            }
            StorageHealthObservationAttempt.Retry,
            StorageHealthObservationAttempt.Stopped -> null
        }

    private suspend fun runStorageHealthObservation(
        cancellationSignal: CancellationSignal,
    ): StorageHealthObservationAttempt {
        if (teardownBoundary.isStopping) return StorageHealthObservationAttempt.Stopped
        return try {
            // Capture immediately before the read. A database failure recorded while this observation
            // is in flight must remain newer truth when the result returns.
            val observationToken = StorageHealthRuntime.beginObservation()
            val observation = entityLearning.storageHealthObservation(cancellationSignal)
            if (teardownBoundary.isStopping) {
                StorageHealthObservationAttempt.Stopped
            } else {
                val quality = storageHealthObservationQuality(observation)
                // Refresh never clears the failure latch. A completed clean observation only arms the
                // next ordinary durable write; a completed integrity failure remains authoritative.
                StorageHealthRuntime.refresh(observation, observationToken)
                if (storageHealthObservationNeedsRetry(observation)) {
                    Log.w(TAG, "storage health observation ${quality.name.lowercase(Locale.ROOT)}")
                    StorageHealthObservationAttempt.Retry
                } else {
                    StorageHealthObservationAttempt.Complete
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: OperationCanceledException) {
            if (teardownBoundary.isStopping) StorageHealthObservationAttempt.Stopped
            else StorageHealthObservationAttempt.Retry
        } catch (failure: Exception) {
            if (!teardownBoundary.isStopping) {
                Log.w(TAG, "storage health observation failed (${failure.javaClass.simpleName})")
            }
            if (teardownBoundary.isStopping) StorageHealthObservationAttempt.Stopped
            else StorageHealthObservationAttempt.Retry
        }
    }

    private fun onStorageHealthSnapshot(snapshot: StorageHealthSnapshot) {
        if (teardownBoundary.isStopping) return
        server.invalidateStorageHealthDiagnostics()
        updateStorageHealthNotification(snapshot)
        runtime.observe()?.value?.mqtt?.publishStorageHealth()
    }

    private fun updateStorageHealthNotification(snapshot: StorageHealthSnapshot) {
        when (val decision = storageHealthNotificationDecision(snapshot)) {
            StorageHealthNotificationDecision.KeepExisting -> Unit
            StorageHealthNotificationDecision.Cancel -> runCatching {
                (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                    .cancel(STORAGE_HEALTH_NOTIF_ID)
            }.onFailure { Log.w(TAG, "could not clear storage health notification", it) }
            is StorageHealthNotificationDecision.Show -> runCatching {
                val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                mgr.createNotificationChannel(
                    NotificationChannel(
                        STORAGE_HEALTH_CHANNEL_ID,
                        nativeString(R.string.storage_health_channel),
                        NotificationManager.IMPORTANCE_HIGH,
                    ).apply {
                        description = nativeString(R.string.storage_health_channel_description)
                        setSound(null, null)
                        enableVibration(false)
                    },
                )
                val storageHealthDestination = Intent()
                storageHealthDestination.setClass(this, ConfigActivity::class.java)
                storageHealthDestination
                    .putExtra("path", "/")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                val contentIntent = PendingIntent.getActivity(
                    this,
                    STORAGE_HEALTH_NOTIF_ID,
                    storageHealthDestination,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                val (title, body) = localizedStorageHealthNotification(snapshot)
                val notification = NotificationCompat.Builder(this, STORAGE_HEALTH_CHANNEL_ID)
                    .setContentTitle(title)
                    .setContentText(body)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                    .setSmallIcon(android.R.drawable.stat_notify_error)
                    .setCategory(NotificationCompat.CATEGORY_ERROR)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setOngoing(true)
                    .setAutoCancel(false)
                    .setOnlyAlertOnce(true)
                    .setSilent(true)
                    .setContentIntent(contentIntent)
                    .build()
                mgr.notify(STORAGE_HEALTH_NOTIF_ID, notification)
            }.onFailure { Log.w(TAG, "could not show storage health notification", it) }
        }
    }

    private fun localizedStorageHealthNotification(snapshot: StorageHealthSnapshot): Pair<String, String> {
        if (snapshot.severity == StorageHealthSeverity.CRITICAL) {
            val capacity = if (snapshot.totalBytes > 0L) {
                nativeString(R.string.storage_capacity_suffix, snapshot.usableBytes.coerceAtLeast(0L) / (1024L * 1024L))
            } else ""
            return nativeString(R.string.storage_pressure_title) to nativeString(R.string.storage_pressure_body, capacity)
        }
        val during = snapshot.databaseFailureOperationLabel
            ?.let { nativeString(R.string.database_during_suffix, it) }
            .orEmpty()
        val body = when (snapshot.databaseFailureKind) {
            StorageDatabaseFailureKind.STORAGE_FULL -> R.string.database_full_body
            StorageDatabaseFailureKind.IO -> R.string.database_io_body
            StorageDatabaseFailureKind.CORRUPTION -> R.string.database_corruption_body
            StorageDatabaseFailureKind.BUSY -> R.string.database_busy_body
            StorageDatabaseFailureKind.UNKNOWN, null -> R.string.database_failure_body
        }
        return nativeString(R.string.database_attention_title) to nativeString(body, during)
    }

    private fun notificationChannel(silent: Boolean): Pair<NotificationManager, String> {
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = if (silent) SILENT_CHANNEL_ID else CHANNEL_ID
        mgr.createNotificationChannel(
            NotificationChannel(channelId, "ha-paneld", NotificationManager.IMPORTANCE_MIN).apply {
                description = nativeString(R.string.panel_agent_channel_description)
                if (silent) {
                    setSound(null, null)
                    enableVibration(false)
                    setShowBadge(false)
                }
            },
        )
        return mgr to channelId
    }

    private fun startForegroundCompat(statusText: String, silent: Boolean) {
        foregroundStatusText = statusText
        val (_, channelId) = notificationChannel(silent)
        promoteToForeground(foregroundNotification(channelId, silent, statusText), microphoneForeground)
        Log.i(TAG, "foreground service started${promotionLatencySuffix()}")
    }

    /**
     * How long this process took to reach `startForeground`, when the platform can tell us.
     *
     * Android gives a `startForegroundService` caller a few seconds to promote, and on an
     * out-of-process cold start that budget covers process creation and `Application.onCreate` as well
     * as everything above. Missing it kills the process with `RemoteServiceException`, which is what
     * slower Android 8.1 panels were doing. An absence of crashes cannot prove that fixed, so the
     * margin is measured on every start instead of inferred: this is the instrument that says how much
     * headroom the slowest panel actually has.
     *
     * `Process.getStartElapsedRealtime` is API 24+, and minSdk is 26, so it is always available; it is
     * still defensive because a wrong answer here must never be the thing that fails a promote.
     */
    private fun promotionLatencySuffix(): String = runCatching {
        " (promoted ${android.os.SystemClock.elapsedRealtime() - android.os.Process.getStartElapsedRealtime()} ms after process start)"
    }.getOrDefault("")

    /**
     * Re-assert the database's view of locale and night mode, now that the service has promoted.
     *
     * `Application.onCreate` deliberately reads both from the downgrade-compatible XML mirror, because
     * opening the database there spends the foreground-start deadline (see the comment in
     * `HaPaneldApp.onCreate`). The database stays authoritative — a restore can write it without
     * touching the mirror — so the correction happens here instead, where it is off the deadline and
     * still before any ha-paneld screen is rendered.
     */
    private fun reconcileNativePresentationAfterPromotion() {
        runCatching {
            NativeLocale.apply(config.uiLanguage)
            if (Build.VERSION.SDK_INT < 29) {
                androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(
                    if (config.darkMode) {
                        androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
                    } else {
                        androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
                    },
                )
            }
        }.onFailure { Log.w(TAG, "could not reconcile native locale/night mode from the database", it) }
    }

    /**
     * Claim exactly the foreground-service types this service is entitled to at this moment.
     *
     * The manifest declares `specialUse|microphone`, because a type can only be claimed if it is
     * declared, but an idle panel must not be a microphone foreground service. The two-argument
     * `startForeground` claims every type the manifest declares, so from Android 10 — where naming
     * the set became possible — the set is named explicitly: `specialUse` on Android 14 and later,
     * where that type exists, plus `microphone` on Android 11 and later, where *that* type exists,
     * and then only while something holds a lease. Android 9 and earlier ignore the attribute
     * entirely, and Android 10 has no microphone type to claim.
     */
    private fun promoteToForeground(notification: Notification, microphone: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification)
            return
        }
        var types = 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        }
        if (microphone && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        startForeground(NOTIF_ID, notification, types)
    }

    /**
     * Add or drop the microphone foreground-service type around a microphone lease.
     *
     * Call it on the main thread with `true` as the first lease opens and `false` as the last one is
     * released; it is idempotent and inert until something actually calls it. Returns whether the
     * type is now what was asked for, so a caller that needs the microphone can refuse to start
     * capture it is not allowed to run. Before Android 11 there is no microphone type to claim and
     * no while-in-use restriction to satisfy, so it succeeds without claiming anything.
     *
     * **Android 14 will refuse a microphone foreground service that is started from the background.**
     * A `while-in-use` type may only be claimed from a state that allows it — an activity in the
     * foreground, or one of the documented exemptions — and a claim made from the background throws
     * `ForegroundServiceStartNotAllowedException` (or, on some builds, silently omits the type).
     * That is a policy question about *when* capture may begin, and it belongs to whatever owns the
     * leases, not here: this method reports the failure and leaves the service running with the
     * types it already had.
     */
    internal fun setMicrophoneForegroundActive(active: Boolean): Boolean {
        if (standingDown || teardownBoundary.isStopping) return false
        if (microphoneForeground == active) return true
        val silent = if (::config.isInitialized) config.silenceBootChime else true
        val (_, channelId) = notificationChannel(silent)
        return runCatching {
            promoteToForeground(foregroundNotification(channelId, silent, foregroundStatusText), active)
            microphoneForeground = active
        }.onFailure {
            Log.w(TAG, "could not ${if (active) "claim" else "release"} the microphone foreground-service type", it)
        }.isSuccess
    }

    private fun updateForegroundStatus(statusText: String) {
        if (teardownBoundary.isStopping) return
        foregroundStatusText = statusText
        val silent = config.silenceBootChime
        val (mgr, channelId) = notificationChannel(silent)
        mgr.notify(NOTIF_ID, foregroundNotification(channelId, silent, statusText))
    }

    private fun foregroundNotification(channelId: String, silent: Boolean, statusText: String): Notification =
        NotificationCompat.Builder(this, channelId)
            .setContentTitle(nativeString(R.string.app_name))
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setSilent(silent)
            .build()

    /**
     * Hold Android's started-service record without owning anything until the committed process exits.
     *
     * Deliberately no stopSelf(): that live record is exactly what makes Android recreate this service
     * in the fresh process, which is where the staged activation is claimed and proved. The notification
     * is rewritten without touching config, which this generation never constructed.
     */
    private fun standDownForCommittedProcessBoundary() {
        standingDown = true
        Log.i(TAG, "service generation created inside a committed process boundary; standing down until the process exits")
        val (mgr, channelId) = notificationChannel(silent = true)
        runCatching {
            mgr.notify(NOTIF_ID, foregroundNotification(channelId, silent = true, nativeString(R.string.starting)))
        }
            .onFailure { Log.w(TAG, "could not describe the stood-down service generation", it) }
    }

    /**
     * Close the synchronous admission gates shared by ordinary destruction and an explicit process
     * boundary. Each owner is guarded because a requested boundary can arrive during startup, before
     * every controller exists; normal destruction has already initialized all of them.
     */
    private fun closeServiceAdmissions() {
        proximityWizard?.close()
        if (::audio.isInitialized) beginAudioTeardown(audio::closeAdmission, audio::cancelCurrent)
        if (::kiosk.isInitialized) kiosk.closeAdmission()
        if (::navbar.isInitialized) navbar.closeAdmission()
        if (::screen.isInitialized) screen.closeAdmission()
        if (::camera.isInitialized) camera.closeAdmission()
    }

    /** Explicit power-safety repair. This is referenced by the HTTP server during startup but can execute
     * only after admission on an IO dispatcher, behind the service replacement fence. */
    private fun repairPowerSafety() = powerSafety.repair(
        readKeepAwakeConfigured = { config.keepAwake },
        readPreventIdleDimConfigured = { config.preventIdleDim },
        persistKeepAwake = {
            config.setKeepAwake(true)
            config.keepAwake
        },
        applyPreventIdleDim = {
            config.setPreventIdleDim(true)
            config.preventIdleDim && brightness.applyPreventIdleDim(true, config).effective
        },
    )

    override fun onDestroy() {
        if (guardDbRedirect) {
            stopForeground(true)
            super.onDestroy()
            return
        }
        // A retired bridge or a passive successor constructed nothing, exactly like the redirect above.
        if (identityMigrationStandby) {
            // Only the passive migration pass runs in this generation; nothing else was started.
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
            stopForeground(true)
            super.onDestroy()
            return
        }
        // A stood-down generation constructed no controller, holds no restart lease and armed no
        // boundary, so every wait, drain and proof below would be about another generation's owners.
        if (standingDown) {
            super.onDestroy()
            return
        }
        // Android invokes this on the main thread. All deliberate waits below consume one deadline so
        // individually safe phase timeouts cannot accumulate into an input-dispatch ANR.
        val teardownDeadline = MonotonicDeadline(SERVICE_DESTROY_BUDGET_MS)
        // App-local completion may continue off-main, but every subsidiary owner shares this one budget.
        // External display/system-policy recovery remains mandatory even after this deadline expires.
        val asyncTeardownDeadline = MonotonicDeadline(ASYNC_TEARDOWN_BUDGET_MS)
        teardownBoundary.markStopping()
        // Bind any armed request to this exact lifecycle generation. A finalizer that was already in
        // flight before PREPARE cannot later satisfy or cancel the new request.
        upgradeShutdownClaim = UpgradeShutdownCoordinator.claimShutdown()
        storageHealthSubscription?.close()
        storageHealthSubscription = null
        val recoveryLifecycle = synchronized(storageHealthLifecycleLock) {
            storageHealthRecoveryLifecycle.also { storageHealthRecoveryLifecycle = null }
        }
        recoveryLifecycle?.close()
        storageHealthObservationQueue.close()
        lightMqttPublisher.close()
        adaptiveSiteGeneration.incrementAndGet()
        brightnessObserver?.let { observer ->
            runCatching { contentResolver.unregisterContentObserver(observer) }
            brightnessObserver = null
        }
        closeServiceAdmissions()
        // A deliberately dark panel is the only external state that becomes unrecoverable if Android
        // kills this process after onDestroy returns (the touch-wake overlay dies with it). Give its
        // dedicated recovery owner first use of the aggregate deadline, then let it keep retrying while
        // all slower server/network teardown proceeds off-main.
        val screenExitRecovery = ensureScreenExitRecovery()
        val screenRecoveryWaitMs = teardownDeadline.remainingMs()
        if (screenRecoveryWaitMs > 0L) {
            runCatching { screenExitRecovery.get(screenRecoveryWaitMs, TimeUnit.MILLISECONDS) }
        }
        started = false
        netCallback?.let { cb ->
            runCatching { (getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)?.unregisterNetworkCallback(cb) }
            netCallback = null
        }
        if (!mdnsRevalidation.closeAndJoin(teardownDeadline.remainingMs())) {
            Log.w(TAG, "mDNS revalidation worker did not drain before service teardown")
        }
        stopMqttWatchdog(teardownDeadline.remainingMs())
        // The existing runtime lane owns latest configuration work. Close its one admission point now;
        // the sticky shutdown queued below becomes the sole proof that any active mutation drained.
        runtime.closeAdmission()
        FeatureCosts.registry.setBacklog(FeatureCostOperation.NETWORK_RECONFIGURE, 0)
        // Close renderer transaction admission before waiting for the runtime lane. If startup has not
        // reconciled yet it exits early; if it has progressed further, final runtime cleanup below owns
        // every resource it may still open, including HTTP.
        val rendererDrained = if (!::rendererPreparation.isInitialized) true
            else rendererPreparation.close(teardownDeadline.remainingMs())
        if (!rendererDrained) {
            Log.w(TAG, "renderer transaction did not become idle before the service teardown deadline")
        }
        val httpOwnersStopped = AtomicBoolean(!::server.isInitialized)
        val audioDrained = AtomicBoolean(false)
        val ownerCleanup = ServiceOwnerCleanupTracker()
        val mqttFinalization = AtomicReference<Future<Unit>?>(null)
        val sensorPersistenceClosed = AtomicReference<Future<Unit>?>(null)
        val stopped = runtime.shutdown(teardownDeadline.remainingMs()) { activeRuntime ->
            fun closeOwner(name: String, close: () -> Unit) {
                ownerCleanup.run(close)?.let { error ->
                    Log.w(TAG, "$name cleanup failed", error)
                }
            }
            fun closeOwnerResult(name: String, close: () -> Boolean) {
                val result = runCatching(close).onFailure { error ->
                    Log.w(TAG, "$name cleanup failed", error)
                }.getOrDefault(false)
                ownerCleanup.record(result)
                if (!result) Log.w(TAG, "$name cleanup did not complete")
            }
            // This cleanup is queued behind any in-flight startup/reconfigure. Closing HTTP here means
            // startup cannot bind a fresh listener after an earlier out-of-band stop has already run.
            // It is deliberately first: the LAN CDP relay is root-owned and survives app process death.
            httpOwnersStopped.set(
                if (!::server.isInitialized) true else runCatching { server.stop() }
                    .onFailure { Log.w(TAG, "HTTP cleanup failed", it) }
                    .getOrDefault(false),
            )
            // The physical-wake reconciliation publishes INTO the MQTT runtime, so it must be provably
            // terminal before that runtime is retired: unregister first so no new work can be posted,
            // then join the worker so a reconciliation already in flight cannot publish through a
            // retired client. Ordered before the retirement fence below for exactly that reason.
            closeOwner("screen-on reconciliation") { stopScreenOnReconciliation() }
            closeOwner("WebView rebind watch") { stopWebViewRebindWatch() }
            closeOwner("WebView repair offer") { WebViewRepairRuntime.detach() }
            closeOwner("update entity observers") { detachSoftwareUpdateObservers() }
            closeOwnerResult("screen reconcile worker") {
                screenWakeWorker.closeAndJoin(
                    minOf(asyncTeardownDeadline.remainingMs(), WAKE_WORKER_JOIN_MS),
                )
            }
            // MQTT commands and convergence observers can use the service-owned hardware controllers.
            // Latest live refresh/replacement work has already drained on this runtime lane. Prove the
            // remaining mutation owners terminal before dismantling any dependent controller. mDNS is
            // fenced at the same time and both consume the one asynchronous teardown deadline.
            val mdnsRetirement = activeRuntime.mdns.retire(asyncTeardownDeadline)
            val mqttRetirement = activeRuntime.mqtt.stop(asyncTeardownDeadline)
            mqttFinalization.set(mqttRetirement.finalization)
            val mqttOwnersDrained = mqttRetirement.ownersDrained.awaitTrue(asyncTeardownDeadline)
            val mdnsStopped = mdnsRetirement.awaitTrue(asyncTeardownDeadline)
            val lightPublisherDrained = lightMqttPublisher.awaitTermination(asyncTeardownDeadline.remainingMs())
            if (!mqttOwnersDrained || !mdnsStopped || !lightPublisherDrained) {
                ownerCleanup.record(false)
                error(
                    "network mutation owners did not drain before hardware teardown " +
                        "(mqtt=$mqttOwnersDrained mdns=$mdnsStopped light=$lightPublisherDrained)",
                )
            }

            // Kiosk writes persistent system policy. Serialize OFF behind any admitted delayed reassert
            // before a forced process boundary; the persisted user preference itself remains unchanged.
            cancelKioskReassert()
            closeOwner("kiosk") { kiosk.apply(false) }
            closeOwner("navbar") { navbar.cleanup() }
            if (::entityLearning.isInitialized) EntityLearningRuntime.detach(entityLearning)
            val audioWaitMs = minOf(asyncTeardownDeadline.remainingMs(), AUDIO_SHUTDOWN_MS)
            val audioStopped = runCatching {
                audioWaitMs > 0L && kotlinx.coroutines.runBlocking { audio.close(audioWaitMs) }
            }.getOrDefault(false)
            audioDrained.set(audioStopped)
            if (!audioStopped) {
                Log.w(TAG, "audio cleanup exceeded ${AUDIO_SHUTDOWN_MS}ms")
            }
            // The MQTT publication target and command ingress are now terminal; drain the remaining
            // producers and hardware owners in dependency order.
            if (::zigbeeHealth.isInitialized) closeOwnerResult("Zigbee health") { zigbeeHealth.stop() }
            closeOwner("evdev") { EvdevButtonClient.stop() }
            if (::haAmbientLux.isInitialized) closeOwner("HA ambient light") { haAmbientLux.close() }
            if (::autoSleep.isInitialized) {
                closeOwnerResult("auto sleep") { autoSleep.closeAndJoin(asyncTeardownDeadline.remainingMs()) }
            }
            if (::voice.isInitialized) {
                voiceForegroundRetry = null
                config.unregisterChangeListener(voicePrefsListener)
                closeOwnerResult("voice assistant") { voice.shutdown(asyncTeardownDeadline.remainingMs()) }
            }
            if (::panelAssistantTransport.isInitialized) {
                closeOwner("Panel Assistant transport") { panelAssistantTransport.close() }
            }
            if (::haExactEntityStream.isInitialized) {
                closeOwner("HA exact entity stream") { haExactEntityStream.close() }
                // Identity-gated: a successor service may already have installed its own coordinator by
                // the time this (possibly deadline-late) teardown runs, and erasing it would blank the
                // successor's live tracking. Only when THIS service's installation was actually cleared
                // is the renderer poked, so a card rendering the dead state is re-read and hidden.
                if (HaLifecycleRuntime.uninstall(haLifecycle)) BuiltinDashboard.onHaLifecycleChanged()
                // Same identity gate for the network-path owner: a successor's installation survives a
                // late predecessor teardown, and only a real clearing re-pokes the chip to hide.
                if (::haNetworkPath.isInitialized && HaNetworkPathRuntime.uninstall(haNetworkPath)) {
                    BuiltinDashboard.onHaLifecycleChanged()
                }
                // The echo probe is cleared under the same identity gate, and silently: it drives no
                // prominent surface, so nothing needs re-poking when it goes.
                if (::haPathProbe.isInitialized) PathProbeRuntime.uninstall(haPathProbe)
                BuiltinDashboard.clearRendererSettledListener(rendererSettledForLifecycle)
            }
            closeOwner("sensors") { sensorPersistenceClosed.set(sensors.stop()) }
            if (::autoBright.isInitialized) {
                closeOwnerResult("adaptive brightness") {
                    autoBright.closeAndJoin(asyncTeardownDeadline.remainingMs())
                }
            }
            closeOwner("watchdog") { watchdog.stop() }
            // The stream transport before the camera: every client is disconnected and its lease
            // returned before the owner tears the session down, and no new client can reattach.
            if (::cameraStream.isInitialized) closeOwner("camera stream") { cameraStream.stop() }
            // Before the LED: a session may hold it for indication and gives it back on close.
            if (::camera.isInitialized) {
                closeOwnerResult("camera") {
                    runCatching { camera.stop().get(CAMERA_STOP_MS, java.util.concurrent.TimeUnit.MILLISECONDS) }.isSuccess
                }
            }
            closeOwner("LED effect") { ledEffect.close() }
            closeOwnerResult("wake-on-wave worker") {
                wakeOnWaveWorker.closeAndJoin(
                    minOf(asyncTeardownDeadline.remainingMs(), WAKE_WORKER_JOIN_MS),
                )
            }
            closeOwnerResult("power") { power.releaseAndVerify() }
            closeOwner("log shipper") { logShipper.stop() }
            closeOwner("performance reader") { io.github.maxlyth.hapaneld.http.PerfReader.stop() }
            closeOwner("app log capture") { logCaptureApp.close() }
            closeOwner("system log capture") { logCaptureSystem.close() }
        }
        if (!stopped) Log.w(TAG, "runtime teardown exceeded the service deadline; cleanup continues on its owner thread")
        if (!httpOwnersStopped.get()) Log.w(TAG, "HTTP owners did not stop cleanly before learner teardown")

        // Startup and all runtime producers are now terminal before their shared coroutine scope and
        // SQLite-backed learner disappear. On a timeout, deliberately leave the store open for process
        // teardown rather than racing late runtime cleanup.
        val root = scope.coroutineContext[Job]
        scope.cancel()
        val scopeWaitMs = teardownDeadline.remainingMs()
        val scopeDrained = when {
            root == null -> true
            scopeWaitMs <= 0L -> root.isCompleted
            else -> runBlocking {
                withTimeoutOrNull(scopeWaitMs) { root.join(); true } ?: false
            }
        }
        if (!scopeDrained) Log.w(TAG, "service jobs did not drain before the service teardown deadline")
        if (::entityLearning.isInitialized && !(stopped && httpOwnersStopped.get() && rendererDrained && scopeDrained)) {
            Log.w(TAG, "entity-learning store remains open until asynchronous teardown drains its producers")
        }
        val stateFlushMs = teardownDeadline.remainingMs()
        if (stateFlushMs <= 0L || !AppState.flush(this, stateFlushMs)) {
            Log.w(TAG, "application-state writes did not drain before the service teardown deadline")
        }
        // The main-thread flush is best effort only: timed-out runtime cleanup can admit sensor writes
        // later. The finalizer always flushes again after every producer and its persistence future drain.
        finishTeardownAsync(
            asyncTeardownDeadline,
            root,
            httpOwnersStopped,
            rendererDrained,
            audioDrained,
            ownerCleanup,
            mqttFinalization,
            sensorPersistenceClosed,
        )
        // stopSelf() removes START_STICKY. Re-arm only an app-internal profile/WebView/recovery
        // boundary while the old predecessor barrier remains closed. A provisioner stop never sets
        // this flag. Any successor onCreate work completes before the finalizer's state fence/proof.
        if (restartAfterInternalBoundary.get()) {
            runCatching { PaneldService.start(this) }
                .onFailure { Log.e(TAG, "could not retain service start across process boundary", it) }
        }
        super.onDestroy()
    }

    /**
     * Finish work that exceeded Android's main-thread lifecycle budget. A completely proved ordinary
     * stop opens the next same-process lease; an explicit or incomplete teardown restores external state
     * and exits so a replacement cannot inherit ambiguous owners.
     */
    private fun finishTeardownAsync(
        finalizerDeadline: MonotonicDeadline,
        root: Job?,
        httpOwnersStopped: AtomicBoolean,
        rendererAlreadyDrained: Boolean,
        audioDrained: AtomicBoolean,
        ownerCleanup: ServiceOwnerCleanupTracker,
        mqttFinalization: AtomicReference<Future<Unit>?>,
        sensorPersistenceClosed: AtomicReference<Future<Unit>?>,
    ) {
        Thread {
            var shutdownFreeze: StateQuiescence? = null
            try {
                var rendererDrained = rendererAlreadyDrained
                while (!rendererDrained) {
                    val waitMs = minOf(finalizerDeadline.remainingMs(), ASYNC_TEARDOWN_WAIT_MS)
                    if (waitMs <= 0L) return@Thread finishTeardownAfterExternalStateIsSafe(
                        completed = false,
                        reason = "renderer transaction remained active",
                        shutdownFreeze = shutdownFreeze,
                    )
                    rendererDrained = rendererPreparation.close(waitMs)
                }
                while (!runtime.isStopped()) {
                    val waitMs = minOf(finalizerDeadline.remainingMs(), ASYNC_TEARDOWN_WAIT_MS)
                    if (waitMs <= 0L) return@Thread finishTeardownAfterExternalStateIsSafe(
                        completed = false,
                        reason = "runtime cleanup did not finish",
                        shutdownFreeze = shutdownFreeze,
                    )
                    val shutdownSucceeded = runtime.shutdown(waitMs) {}
                    if (!shutdownSucceeded && !ownerCleanup.isComplete()) {
                        return@Thread finishTeardownAfterExternalStateIsSafe(
                            completed = false,
                            reason = "network mutation owners remained active",
                            shutdownFreeze = shutdownFreeze,
                        )
                    }
                    if (!shutdownSucceeded && runtime.hasFailedShutdown()) {
                        return@Thread finishTeardownAfterExternalStateIsSafe(
                            completed = false,
                            reason = "runtime cleanup failed",
                            shutdownFreeze = shutdownFreeze,
                        )
                    }
                }
                if (!ownerCleanup.isComplete()) {
                    return@Thread finishTeardownAfterExternalStateIsSafe(
                        completed = false,
                        reason = "one or more runtime owners did not clean up",
                        shutdownFreeze = shutdownFreeze,
                    )
                }
                if (!audioDrained.get()) {
                    return@Thread finishTeardownAfterExternalStateIsSafe(
                        completed = false,
                        reason = "audio cleanup did not drain",
                        shutdownFreeze = shutdownFreeze,
                    )
                }
                val mqttClose = mqttFinalization.get()
                    ?: return@Thread finishTeardownAfterExternalStateIsSafe(
                        completed = false,
                        reason = "MQTT cleanup did not start",
                        shutdownFreeze = shutdownFreeze,
                    )
                if (!awaitFinalizerFuture(finalizerDeadline, mqttClose)) {
                    return@Thread finishTeardownAfterExternalStateIsSafe(
                        completed = false,
                        reason = "final MQTT publication did not finish",
                        shutdownFreeze = shutdownFreeze,
                    )
                }
                val sensorClose = sensorPersistenceClosed.get()
                    ?: return@Thread finishTeardownAfterExternalStateIsSafe(
                        completed = false,
                        reason = "sensor cleanup did not start",
                        shutdownFreeze = shutdownFreeze,
                    )
                if (!awaitFinalizerFuture(finalizerDeadline, sensorClose)) {
                    return@Thread finishTeardownAfterExternalStateIsSafe(
                        completed = false,
                        reason = "proximity persistence did not drain",
                        shutdownFreeze = shutdownFreeze,
                    )
                }
                if (root != null && !root.isCompleted) {
                    val waitMs = finalizerDeadline.remainingMs()
                    val scopeDrained = waitMs > 0L && runBlocking {
                        withTimeoutOrNull(waitMs) { root.join(); true } ?: false
                    }
                    if (!scopeDrained) return@Thread finishTeardownAfterExternalStateIsSafe(
                        completed = false,
                        reason = "service scope did not drain",
                        shutdownFreeze = shutdownFreeze,
                    )
                }
                if (!httpOwnersStopped.get()) {
                    return@Thread finishTeardownAfterExternalStateIsSafe(
                        completed = false,
                        reason = "HTTP owners did not stop cleanly",
                        shutdownFreeze = shutdownFreeze,
                    )
                }

                if (::entityLearning.isInitialized && !runFinalizerStep(finalizerDeadline) { entityLearning.close() }) {
                    return@Thread finishTeardownAfterExternalStateIsSafe(
                        completed = false,
                        reason = "entity-learning store did not close",
                        shutdownFreeze = shutdownFreeze,
                    )
                }
                // Runtime/scope/sensor/learner teardown above serializes their last writes. Fence any
                // unrelated same-process caller only now, so final producer persistence is not rejected.
                shutdownFreeze = AppState.freezeForServiceShutdown(this)
                if (shutdownFreeze == null) {
                    return@Thread finishTeardownAfterExternalStateIsSafe(
                        completed = false,
                        reason = "application-state shutdown admission was not frozen",
                        shutdownFreeze = null,
                    )
                }
                val stateFlushMs = finalizerDeadline.remainingMs()
                if (stateFlushMs <= 0L || !AppState.flush(this, stateFlushMs)) {
                    return@Thread finishTeardownAfterExternalStateIsSafe(
                        completed = false,
                        reason = "final application-state writes did not drain",
                        shutdownFreeze = shutdownFreeze,
                    )
                }
                val proofMs = finalizerDeadline.remainingMs()
                val cleanDatabaseProof = if (proofMs > 0L) {
                    runBoundedShutdownProof(proofMs) {
                        AppState.proveCleanServiceShutdown(this, proofMs)
                    }
                } else null
                if (cleanDatabaseProof == null) {
                    return@Thread finishTeardownAfterExternalStateIsSafe(
                        completed = false,
                        reason = "final application-state flush or WAL checkpoint did not prove stable",
                        shutdownFreeze = shutdownFreeze,
                    )
                }
                finishTeardownAfterExternalStateIsSafe(completed = true,
                    reason = "service teardown completed with a stable database",
                    shutdownFreeze = shutdownFreeze,
                    cleanDatabaseProof = cleanDatabaseProof,
                )
            } catch (error: Throwable) {
                Log.e(TAG, "asynchronous service teardown failed", error)
                finishTeardownAfterExternalStateIsSafe(
                    completed = false,
                    reason = "unexpected finalizer failure",
                    shutdownFreeze = shutdownFreeze,
                )
            }
        }.apply {
            isDaemon = true
            name = "ha-paneld-service-finalizer"
        }.start()
    }

    /**
     * Adopt a wake that ha-paneld did not perform, without depending on the broker.
     *
     * `ACTION_SCREEN_ON` is the platform announcing its own display came back, and it arrives whether
     * or not MQTT is configured, connected or enabled at all. That matters because the keyevent
     * screen-off route puts Android itself to sleep, and Android can be woken from its power key or a
     * wake-capable touchscreen with this process uninvolved — the only route where a wake reaches the
     * panel without passing through ScreenController. Until now the adoption lived in the MQTT sync
     * tick, so on a broker-less panel a hand-woken screen stayed marked off with its renderer frozen:
     * lit, stale and un-tappable.
     *
     * The receiver only hands off. Reconciliation reads the backlight through root or the daemon on
     * the bl_power routes, which must not happen on the main thread.
     */
    private fun startScreenOnReconciliation() {
        if (screenOnReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != Intent.ACTION_SCREEN_ON || teardownBoundary.isStopping) return
                screenWakeWorker.execute {
                    if (teardownBoundary.isStopping) return@execute
                    if (!screen.reconcilePhysicalWake()) return@execute
                    Log.i(TAG, "adopted a physical wake that did not pass through ha-paneld")
                    // Best-effort only, and deliberately after the reconciliation: telling Home
                    // Assistant is the part that is allowed to fail here, not the recovery.
                    runCatching { mqtt.publishScreenOn() }
                        .onFailure { Log.d(TAG, "screen-on publish after a physical wake failed", it) }
                }
            }
        }
        val filter = IntentFilter(Intent.ACTION_SCREEN_ON)
        // ACTION_SCREEN_ON is a protected system broadcast, so unlike the navbar's @hide volume action
        // this one is still delivered to a NOT_EXPORTED receiver — the tighter of the two flags.
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                registerReceiver(receiver, filter)
            }
        }.onSuccess { screenOnReceiver = receiver }
            .onFailure { Log.w(TAG, "screen-on reconciliation could not be registered", it) }
    }

    private fun stopScreenOnReconciliation() {
        screenOnReceiver?.let { runCatching { unregisterReceiver(it) } }
        screenOnReceiver = null
    }

    /**
     * The blocking admission verdict the LIVE renderer generation is parked on, when a WebView provider
     * change could repair it; null in every other case, including a healthy dashboard and a panel blocked
     * on something a new engine cannot fix.
     *
     * [RendererAdmissionRuntime] is the authority rather than anything held here, and that is the whole
     * answer to stale generations: it is never persisted, it refuses a write from a renderer that no
     * longer owns the activity, and it re-applies that ownership test on every read — so a destroyed or
     * replaced activity's verdict stops being visible without a teardown hook to forget.
     */
    private fun blockedProviderRepairableAdmission(): AdmissionOutcome? {
        val record = RendererAdmissionRuntime.current()?.record ?: return null
        if (record.state != RendererAdmissionState.BLOCKED) return null
        return record.outcome?.takeIf { providerRepairableAdmission(it) }
    }

    /** The package that provides this panel's WebView right now, or null when none resolves. */
    private fun resolveWebViewProviderIdentity(): WebViewProviderIdentity? = runCatching {
        WebViewCompat.getCurrentWebViewPackage(this)?.let {
            WebViewProviderIdentity(
                packageName = it.packageName,
                versionCode = PackageInfoCompat.getLongVersionCode(it),
                versionName = it.versionName,
            )
        }
    }.getOrNull()

    /**
     * Notice a WebView provider being installed, updated or repaired while the built-in renderer is
     * parked on "Secure dashboard bridge unavailable", and take the one action that can clear it.
     *
     * That screen is deliberately terminal on a timer ([admissionRetryClass] gives it
     * [AdmissionRetryClass.MANUAL_ONLY]) because a missing WebView capability is not time-dependent, and
     * this does not change that: no cadence, no ladder, no polling, and every other blocked verdict keeps
     * the network and Home Assistant-version recovery it already had. The trigger is the install event
     * itself, and the panel asks nothing in between.
     *
     * Registration is paired with the service's own lifetime rather than with the screen, so the receiver
     * cannot outlive an activity or be registered twice by a repaint; the decision is cheap, and it asks
     * the panel-local questions before it ever queries the provider.
     */
    /**
     * Let a blocked status screen offer the repair this service already knows how to perform.
     *
     * The screen is handed three narrow questions rather than a reference to this service: what the panel
     * can do, how to start it, and what the installer is saying. Nothing here is a second implementation
     * of the repair — [installComponent] is the same entry point the Install page posts to, so a panel
     * cannot end up with two ways to install an engine that disagree about what "busy" means.
     *
     * The capability is refreshed off the main thread and then cached. It is refreshed again on each
     * update tick because both of its inputs can change while the panel sits on the blocked screen:
     * a root helper can finish installing, and a profile can arrive with a build pinned for this model.
     */
    private fun startWebViewRepairOffer() {
        refreshWebViewRepairCapability()
        WebViewRepairRuntime.attach(
            capability = { webViewRepairCapability },
            // Once activation has requested the process boundary, no new destructive work may enter
            // the process that is being torn down, even if the progress slot has just gone terminal.
            start = start@{
                if (teardownBoundary.isStopping) return@start null
                var generation: Long? = null
                val accepted = installComponent("webview", "reinstall", "") { ticket ->
                    generation = ticket.id
                }
                if (accepted) generation else null
            },
            progress = {
                val snapshot = InstallProgress.presentationSnapshot()
                WebViewRepairProgress(
                    generation = snapshot.generation,
                    running = snapshot.running,
                    component = snapshot.component,
                    message = snapshot.message,
                    presentation = snapshot.presentation,
                )
            },
        )
    }

    /** Ask the two privileged questions once, off the drawing thread, and publish the answer. */
    private fun refreshWebViewRepairCapability() {
        scope.launch {
            val capability = runCatching {
                WebViewRepairCapability(
                    hasKnownGoodBuild = profile.recommendedWebView != null,
                    // The same route test the Install page's own offer uses, and deliberately not the
                    // wider typed-shell one: `WebViewInstaller.heal` refuses Shizuku, so a panel with
                    // only Shizuku would be offered a button that cannot finish.
                    privileged = Su.availableCachedIsolated() || HelperClient.available(),
                    managedElsewhere = PanelInfo.webViewPlayManaged(this@PaneldService),
                )
            }.getOrNull()
            if (capability != null) webViewRepairCapability = capability
        }
    }

    private fun startWebViewRebindWatch() {
        if (webViewRebindReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (teardownBoundary.isStopping) return
                // Resolved at most once per broadcast, and only if the cheap questions did not already
                // answer: an app updating in the background on a healthy panel costs nothing here.
                val provider = lazy { resolveWebViewProviderIdentity() }
                val blocked = blockedProviderRepairableAdmission()
                val decision = webViewRebindDecision(
                    action = intent?.action,
                    changedPackage = intent?.data?.schemeSpecificPart,
                    replacingExistingInstall = intent?.getBooleanExtra(Intent.EXTRA_REPLACING, false) == true,
                    blockedOutcome = blocked,
                    resolveProvider = provider::value,
                )
                when (decision) {
                    WebViewRebindDecision.REBIND ->
                        if (webViewRebindRestart.request()) {
                            Log.i(
                                TAG,
                                "WebView provider installed while the dashboard bridge was unavailable " +
                                    "(${provider.value?.describe() ?: "unresolved"}) — restarting to bind it",
                            )
                        } else {
                            Log.i(TAG, "a WebView provider rebind is already pending in this process")
                        }
                    // A panel that is not parked on a bridge screen has nothing to say about an install,
                    // and a broadcast this rule does not observe is not an event. Everything else is a
                    // decision taken WHILE the dashboard was unavailable, which is worth naming: it is
                    // the difference between "the engine was replaced and nothing happened" and "what
                    // was installed was not the engine".
                    WebViewRebindDecision.NOT_BLOCKED,
                    WebViewRebindDecision.UNRELATED_ACTION,
                    -> Unit

                    else -> if (blocked != null) {
                        Log.i(
                            TAG,
                            "package install ignored while the dashboard bridge was unavailable: " +
                                decision.name.lowercase(),
                        )
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            // Package broadcasts carry the subject as a `package:` URI; without the scheme the filter
            // matches nothing at all rather than matching everything.
            addDataScheme("package")
        }
        // Both are protected system broadcasts, so — as with the screen-on receiver — the tighter
        // NOT_EXPORTED flag still receives them.
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                registerReceiver(receiver, filter)
            }
        }.onSuccess { webViewRebindReceiver = receiver }
            .onFailure { Log.w(TAG, "WebView rebind watch could not be registered", it) }
    }

    private fun stopWebViewRebindWatch() {
        webViewRebindReceiver?.let { runCatching { unregisterReceiver(it) } }
        webViewRebindReceiver = null
    }

    private fun runFinalizerStep(deadline: MonotonicDeadline, block: () -> Unit): Boolean {
        val waitMs = deadline.remainingMs()
        if (waitMs <= 0L) return false
        val executor = Executors.newSingleThreadExecutor { task ->
            Thread(task, "ha-paneld-service-finalizer-step").apply { isDaemon = true }
        }
        return try {
            executor.submit(Callable { block(); true }).get(waitMs, TimeUnit.MILLISECONDS)
        } catch (error: Throwable) {
            Log.e(TAG, "bounded service finalizer step failed", error)
            false
        } finally {
            executor.shutdownNow()
        }
    }

    private fun awaitFinalizerFuture(deadline: MonotonicDeadline, future: Future<*>): Boolean {
        val waitMs = deadline.remainingMs()
        if (waitMs <= 0L) return false
        return try {
            future.get(waitMs, TimeUnit.MILLISECONDS)
            true
        } catch (error: Throwable) {
            Log.e(TAG, "bounded service finalizer future failed", error)
            false
        }
    }

    private fun ensureScreenExitRecovery(): CompletableFuture<Boolean> {
        screenExitRecoveryOwner.get()?.let { return it }
        val completion = CompletableFuture<Boolean>()
        if (!screenExitRecoveryOwner.compareAndSet(null, completion)) {
            return checkNotNull(screenExitRecoveryOwner.get())
        }
        if (!::screen.isInitialized) {
            completion.complete(true)
            return completion
        }
        Thread {
            while (!completion.isDone) {
                val safe = runCatching { screen.restoreAndEstablishExitSafety() }
                    .onFailure { Log.e(TAG, "screen restore before process restart failed", it) }
                    .getOrDefault(false)
                if (safe) {
                    completion.complete(true)
                    return@Thread
                }
                try {
                    Thread.sleep(EXTERNAL_STATE_RETRY_MS)
                } catch (_: InterruptedException) {
                    // Screen recovery remains mandatory until hardware readback proves it is lit.
                }
            }
        }.apply {
            isDaemon = true
            name = "ha-paneld-screen-exit-recovery"
        }.start()
        return completion
    }

    /**
     * Route profile/WebView/recovery restarts through the complete Android lifecycle teardown. Closing
     * synchronous admission now prevents new hardware work before the main-loop stop reaches onDestroy;
     * onDestroy remains the only owner of producer drains, final state flush, checkpoint and exit proof.
     */
    private fun requestSafeProcessBoundary(reason: String) {
        if (!teardownBoundary.requestExplicitBoundary()) return
        // Accepting the request makes this process terminal — serviceTeardownDisposition always EXITs on
        // an explicit boundary — so every service generation created in it from here on must stand down.
        // Only this branch may commit: an ordinary clean stop RELEASEs a same-process successor and never
        // exits, and fencing that successor would leave nothing to restart the panel.
        PROCESS_BOUNDARY_COMMITMENT.commit()
        restartAfterInternalBoundary.set(true)
        Log.i(TAG, "safe process restart requested: $reason")
        closeServiceAdmissions()
        mainHandler.post { stopSelf() }
    }

    /**
     * App-local workers die at the process boundary, but the root CDP relay and display/system-policy
     * state do not. Never exit until the relay is proved absent and the screen, kiosk policy, and navbar
     * crop have been restored. Cleanup admission is closed first so an explicit external service stop
     * also leaves a usable panel even if Android has no remaining START_STICKY request to recreate us.
     */
    private fun finishTeardownAfterExternalStateIsSafe(
        completed: Boolean,
        reason: String,
        shutdownFreeze: StateQuiescence?,
        cleanDatabaseProof: CleanDatabaseProof? = null,
    ) {
        if (!completed) Log.e(TAG, "service teardown was incomplete: $reason")
        finishAfterExternalStateIsSafe(completed, reason, shutdownFreeze, cleanDatabaseProof)
    }

    private fun finishAfterExternalStateIsSafe(
        completed: Boolean,
        reason: String,
        shutdownFreeze: StateQuiescence? = null,
        cleanDatabaseProof: CleanDatabaseProof? = null,
    ) {
        runServiceBoundary(
            boundary = teardownBoundary,
            completed = completed,
            prepare = {
                cancelKioskReassert()
                if (::navbar.isInitialized) {
                    runCatching { navbar.cleanup() }
                        .onFailure { Log.e(TAG, "navbar cleanup before process restart failed", it) }
                }
            },
            prove = { attempt ->
                val kioskSafe = if (!::kiosk.isInitialized) true else runCatching {
                    kiosk.apply(false) && kiosk.recoverPersistentState(config.kioskLock)
                }
                    .onFailure { Log.e(TAG, "kiosk policy cleanup before process restart failed", it) }
                    .getOrDefault(false)
                val navbarSafe = if (!::navbar.isInitialized) true else runCatching {
                    navbar.recoverPersistentState()
                }
                    .onFailure { Log.e(TAG, "navbar overscan cleanup before process restart failed", it) }
                    .getOrDefault(false)
                val screenSafe = runCatching {
                    proveScreenSafeForBoundary(ensureScreenExitRecovery())
                }.onFailure { Log.e(TAG, "screen recovery before process restart failed", it) }
                    .getOrDefault(false)
                val relaySafe = runCatching {
                    if (GuardDbProcessAdmission.maintenanceRequired()) {
                        RemoteDebugSecurityTransitionGate.withLock {
                            val guard = (GuardDbProcessAdmission.current() as? GuardDbSentinelLoad.Valid)?.sentinel
                                ?: return@withLock false
                            CdpRelay.proveAbsentForGuardDbHandoff() &&
                                config.hardenedSecurityEnabled && adb.hardenedRemoteDebugOff() &&
                                RemoteDebugSecurityTransitionGate.hardenedAuthorityEpoch() ==
                                guard.securityAuthorityEpoch
                        }
                    } else {
                        CdpRelay.stopAndVerifyForProcessExit()
                    }
                }.onFailure { Log.e(TAG, "CDP relay cleanup before process restart failed", it) }
                    .getOrDefault(false)
                // Durable markers preserve deferred policy cleanup. Once the screen is proved usable,
                // privilege loss must force a fresh process rather than fence START_STICKY forever.
                val forceFreshProcess = shouldForceFreshProcessAfterExternalRecovery(
                    attempt = attempt,
                    maxAttempts = EXTERNAL_STATE_BOUNDARY_ATTEMPTS,
                    kioskSafe = kioskSafe,
                    navbarSafe = navbarSafe,
                    screenSafe = screenSafe,
                    relaySafe = relaySafe,
                )
                val externalStateSafe = kioskSafe && navbarSafe && screenSafe && relaySafe
                if (!externalStateSafe && !forceFreshProcess) {
                    Log.e(
                        TAG,
                        "withholding process restart until external state is safe " +
                            "(attempt=$attempt kioskSafe=$kioskSafe navbarSafe=$navbarSafe " +
                            "screenSafe=$screenSafe relaySafe=$relaySafe)",
                    )
                }
                ServiceBoundaryProof(externalStateSafe, forceFreshProcess)
            },
            pauseBeforeRetry = {
                try {
                    Thread.sleep(EXTERNAL_STATE_RETRY_MS)
                } catch (_: InterruptedException) {
                    // This recovery owner must keep retrying until external state is safe to abandon.
                }
            },
            finish = { disposition ->
                if (disposition == ServiceTeardownDisposition.EXIT) {
                    UpgradeShutdownCoordinator.failShutdown(
                        this,
                        upgradeShutdownClaim,
                        shutdownFreeze,
                        restartLease::completeTeardown,
                        "shutdown_not_clean",
                    )
                    Log.i(TAG, "$reason; entering a clean process boundary")
                    kotlin.system.exitProcess(0)
                } else {
                    val heldForUpgrade = shutdownFreeze != null && cleanDatabaseProof != null &&
                        UpgradeShutdownCoordinator.holdAfterCleanShutdown(
                            upgradeShutdownClaim,
                            shutdownFreeze,
                            cleanDatabaseProof,
                            restartLease::completeTeardown,
                        )
                    if (!heldForUpgrade) {
                        shutdownFreeze?.close()
                        Log.i(TAG, "$reason; releasing the same-process service successor")
                        restartLease.completeTeardown()
                    } else {
                        Log.i(TAG, "$reason; holding the service successor for verified host transfer")
                    }
                }
            },
        )
    }

    private fun scheduleKioskReassert() {
        val worker = OwnedThread("kiosk-reassert") {
            val recovered = recoverAndMaybeEnableKiosk(
                escapeDelayMs = KIOSK_REASSERT_MS,
                retryDelayMs = KIOSK_RECOVERY_RETRY_MS,
                maxAttempts = KIOSK_RECOVERY_ATTEMPTS,
                shouldContinue = { !teardownBoundary.isStopping },
                recover = {
                    kioskSettings.serialized { kiosk.recoverPersistentState(config.kioskLock) }
                },
                enabled = { config.kioskLock },
                enable = {
                    kioskSettings.serialized { config.kioskLock && kiosk.apply(true) }
                },
            )
            if (!recovered && !teardownBoundary.isStopping) {
                Log.w(TAG, "kiosk platform recovery remains pending; leaving kiosk unlocked")
            }
        }
        kioskReassert = worker
        worker.start()
    }

    private fun cancelKioskReassert() {
        kioskReassert?.stop(KIOSK_CANCEL_JOIN_MS)
    }

    private fun stopMqttWatchdog(joinMs: Long) {
        mqttWatchdogAlive = false
        mqttWatchdog?.stop(joinMs)
    }

    private fun reconcileHelperInstallStaging() {
        val stagingDir = File(filesDir, HelperInstallTransaction.STAGING_DIR)
        val reconciler = HelperInstallReconciler(HelperClient)
        scope.periodic(
            intervalMs = INSTALL_RECONCILE_MS,
            tag = TAG,
            name = "helper-install-staging",
            onError = { error -> Log.w(TAG, "helper install staging reconciliation failed", error) },
        ) {
            val result = reconciler.reconcile(stagingDir)
            if (result.removed > 0 || result.remaining > 0) {
                Log.d(TAG, "helper install staging: removed=${result.removed} remaining=${result.remaining}")
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val VOICE_SETTINGS_COALESCE_MS = 300L

        // The running service, if any, for the activity's foreground signal. Android refuses a
        // microphone foreground service started from the background, so a claim the platform refused
        // is retried the moment the panel's own activity comes forward, rather than only on the
        // coordinator's slower timer.
        @Volatile private var voiceForegroundRetry: (() -> Unit)? = null

        /** Called by the dashboard when it becomes visible. Inert when no service or no coordinator. */
        fun notifyActivityForeground() {
            voiceForegroundRetry?.invoke()
        }

        private const val TAG = "ha-paneld/svc"
        private const val CHANNEL_ID = "ha-paneld"
        // Channel sound is immutable after creation. A distinct ID guarantees the silence setting can
        // supersede an older install whose original channel was created with a notification sound.
        private const val SILENT_CHANNEL_ID = "ha-paneld-silent-v1"
        private const val STORAGE_HEALTH_CHANNEL_ID = "ha-paneld-storage-health-v1"
        private const val NOTIF_ID = 1
        private const val CAMERA_STOP_MS = 3_000L
        private const val STORAGE_HEALTH_NOTIF_ID = 2
        // Daily rather than wall-clock scheduled: the service owns one immediate check, then delays
        // from completion. A bounded short retry absorbs transient StatFs/SQLite observation failures.
        private const val STORAGE_HEALTH_CHECK_MS = 24L * 3_600L * 1_000L
        private const val STORAGE_HEALTH_CHECK_ATTEMPTS = 3
        private const val STORAGE_HEALTH_RETRY_MS = 5_000L
        private val STORAGE_HEALTH_RECOVERY_DELAYS_MS = longArrayOf(5_000L, 15_000L, 30_000L)
        // MQTT reconnect-watchdog poll interval; a stuck bridge self-heals after ~2 of these.
        private const val MQTT_WATCHDOG_MS = 60_000L
        // Fresh-client broker-progress lease. A previously-live process crosses its controlled boundary
        // at expiry; a clean process gives its restored route this full lease before one alternate try.
        private const val REBUILD_ABANDON_MS = 300_000L
        private const val RECOVERY_RESTART_GRACE_MS = 1_000L
        private const val COMPANION_STARTUP_STATUS_POLL_MS = 2_000L
        // An auth-rejected state younger than this (vs the last broker-ACKed activity) renders as
        // "reconnecting…" — only a PERSISTENT rejection surfaces the check-your-credentials warning.
        private const val AUTH_PERSIST_MS = 300_000L
        // No broker-ACKed publish for this long (with a heartbeat sent every tick) ⇒ the link is dead
        // even if HiveMQ still claims "connected" (half-open socket) ⇒ force a rebuild. ~2.5 missed ticks.
        private const val MQTT_STALE_MS = 150_000L
        // Never-blank-screen watchdog poll interval; re-lights an unintentionally-dark panel within one tick.
        private const val SCREEN_WATCHDOG_MS = 60_000L
        private const val INSTALL_RECONCILE_MS = 60_000L
        private const val KIOSK_REASSERT_MS = 60_000L // post-boot delay before re-locking (admin escape window)
        private const val KIOSK_RECOVERY_RETRY_MS = 1_000L
        private const val KIOSK_RECOVERY_ATTEMPTS = 3
        private const val KIOSK_CANCEL_JOIN_MS = 500L
        private const val WAKE_WORKER_JOIN_MS = 500L
        private const val AUDIO_SHUTDOWN_MS = 2_000L
        private const val SERVICE_DESTROY_BUDGET_MS = 1_000L
        private const val ASYNC_TEARDOWN_WAIT_MS = 10_000L
        private const val ASYNC_TEARDOWN_BUDGET_MS = 30_000L
        private const val EXTERNAL_STATE_RETRY_MS = 1_000L
        private const val EXTERNAL_STATE_BOUNDARY_ATTEMPTS = 5
        private const val NETWORK_RECONFIGURE_BUDGET_MS = 15_000L

        /** First-configuration retire budget: nothing is published/owned, so this bounds only the
         *  best-effort close of a never-connected runtime. See the fast path in the retire lambda. */
        private const val FIRST_CONFIG_RETIRE_MS = 1_500L
        private val SERVICE_RESTART_BARRIER = ServiceRestartBarrier()
        private val PROCESS_BOUNDARY_COMMITMENT = ProcessBoundaryCommitment()

        fun start(context: Context) {
            if (GuardDbProcessAdmission.maintenanceRequired()) {
                GuardDbMaintenanceService.start(context)
                return
            }
            // A retired bridge stays idle on every route into the app, not only inside onCreate.
            if (IdentityMigrationGate.disposition(context) == StartDisposition.RETIRED_BRIDGE) return
            // An armed upgrade has deliberately claimed this service down so an install can proceed
            // against a quiesced database. Every route that legitimately resumes it — cancelAndResume,
            // releaseAndResume — disarms the gate first and then calls back in here, so refusing while
            // armed blocks only an *undeliberate* start, such as the accessibility revival below.
            // The refusal lives here rather than at the caller because this is the single entry point:
            // a second copy in PanelAccessibilityService would be a second definition of the contract,
            // free to drift.
            if (UpgradeShutdownCoordinator.isArmed()) {
                Log.i(TAG, "not starting: an upgrade holds the service down")
                return
            }
            val intent = Intent(context, PaneldService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}

/** The periodic hardware pass belongs to the running service, not to an MQTT connection. */
internal fun <T : Any> requestWatchdogLocalObservation(
    runtime: ServiceRuntimeOwner<T>,
    mqtt: (T) -> MqttBridge,
) {
    val observed = runtime.observe() ?: return
    mqtt(observed.value).requestLocalObservation { runtime.isCurrent(observed) }
}
