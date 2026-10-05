package io.panelassistant.android

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import io.panelassistant.android.http.GuardDbBootstrapDatabaseRead
import io.panelassistant.android.http.GuardDbBootstrapExportDependencies
import io.panelassistant.android.http.GuardDbBootstrapExportSnapshot
import io.panelassistant.android.http.GuardDbBootstrapHelper
import io.panelassistant.android.http.GuardDbBootstrapInstalledApp
import io.panelassistant.android.http.GuardDbBootstrapSecurity
import io.panelassistant.android.http.GuardDbMaintenanceServer
import io.panelassistant.android.http.GuardDbMaintenanceSecurityAuthority
import io.panelassistant.android.http.GuardDbMaintenanceSecurityResult
import io.panelassistant.android.http.MAX_GUARD_DB_BOOTSTRAP_DATABASE_BYTES
import io.panelassistant.android.control.AdbController
import io.panelassistant.android.control.CdpRelay
import io.panelassistant.android.control.RemoteDebugAuthorityResult
import io.panelassistant.android.control.RemoteDebugSecurityAuthorityLoad
import io.panelassistant.android.control.RemoteDebugSecurityAuthorityStore
import io.panelassistant.android.control.RemoteDebugSecurityState
import io.panelassistant.android.control.RemoteDebugSecurityTransitionGate
import io.panelassistant.android.control.encodeRemoteDebugSecurityAuthority
import io.panelassistant.android.util.AppInstaller
import io.panelassistant.android.util.BoundedStreams
import io.panelassistant.android.util.ByteLimitExceeded
import io.panelassistant.android.util.GuardDbMaintenance
import io.panelassistant.android.util.GuardDbMaintenanceClient
import io.panelassistant.android.util.GuardDbMaintenanceProtocol
import io.panelassistant.android.util.GuardDbMaintenanceStartupGate
import io.panelassistant.android.util.GuardDbPreparedArm
import io.panelassistant.android.util.GuardDbPreparedArmLoad
import io.panelassistant.android.util.GuardDbProcessAdmission
import io.panelassistant.android.util.GuardDbSentinelLoad
import io.panelassistant.android.util.GuardDbSentinelState
import io.panelassistant.android.util.GuardDbStartupSentinel
import io.panelassistant.android.util.GuardDbStartupAcknowledger
import io.panelassistant.android.util.GuardDbSuccessorAlarm
import io.panelassistant.android.util.GuardDbSuccessorHandoff
import io.panelassistant.android.util.guardDbBootNonce
import io.panelassistant.android.util.guardDbAppStaging
import io.panelassistant.android.util.guardDbPreparedArmStore
import io.panelassistant.android.util.guardDbSentinelStore
import io.panelassistant.android.util.inspectGuardDbCandidate
import io.panelassistant.android.util.stableGuardDbCanonicalMain
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Foreground Guard DB successor; its writer-free HTTP listener starts after startup proof closes. */
class GuardDbMaintenanceService : Service() {
    private var server: GuardDbMaintenanceServer? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val startup = GuardDbMaintenanceStartupGate(scope)
    private var destroyed = false

    override fun onCreate() {
        super.onCreate()
        foreground()
        startup.promoted()
        val sentinel = (GuardDbProcessAdmission.current() as? GuardDbSentinelLoad.Valid)?.sentinel
        if (sentinel == null) {
            stopSelf()
            return
        }
        if (!(application as HaPaneldApp).guardDbFreshStartupPending) {
            // A service started after this process armed its own sentinel keeps the existing
            // maintenance route. Only a process born under the sentinel owes startup health proof.
            startMaintenanceServer(sentinel)
            return
        }
        // The app's Application returned before constructing any ordinary database owner. Complete
        // its exact startup acknowledgement on this service's IO scope, after foreground promotion.
        // The HTTP control plane remains closed until that one SQLite owner has been checkpointed
        // and closed, as it was when acknowledgement ran in Application.onCreate.
        startup.launch(
            acknowledge = {
                GuardDbStartupAcknowledger.reconcileBeforeMaintenanceServer(applicationContext)
            },
            startMaintenance = {
                withContext(Dispatchers.Main.immediate) {
                    if (destroyed) return@withContext
                    val current = (GuardDbProcessAdmission.current() as? GuardDbSentinelLoad.Valid)?.sentinel
                    if (current?.session != sentinel.session || current?.bootNonce != sentinel.bootNonce) {
                        Log.e(TAG, "Guard DB startup sentinel changed before maintenance listener")
                        return@withContext
                    }
                    startMaintenanceServer(current)
                }
            },
            resumeOrdinary = {
                val durableAbsent = guardDbSentinelStore(applicationContext).load() is GuardDbSentinelLoad.Absent
                withContext(Dispatchers.Main.immediate) {
                    if (!destroyed &&
                        GuardDbProcessAdmission.current() is GuardDbSentinelLoad.Valid &&
                        durableAbsent
                    ) {
                        requestFreshProcessBoundary()
                    }
                }
            },
            onFailure = { failure ->
                Log.e(TAG, "Guard DB startup acknowledgement failed", failure)
            },
        )
    }

    private fun startMaintenanceServer(sentinel: GuardDbStartupSentinel) {
        val staging = guardDbAppStaging(applicationContext)
        val preparedStore = guardDbPreparedArmStore(applicationContext)
        val sentinelStore = guardDbSentinelStore(applicationContext)
        fun securityReady(): Boolean = guardDbMaintenanceSecurityReady(
            expectedEpoch = sentinel.securityAuthorityEpoch,
            durableHardenedEpoch = RemoteDebugSecurityTransitionGate::hardenedAuthorityEpoch,
            relayRunning = { CdpRelay.running },
            remoteDebugOff = { AdbController.proveMaintenanceRemoteDebugOff(applicationContext) },
        )
        val security = object : GuardDbMaintenanceSecurityAuthority {
            override fun readyEpoch(): Long? = RemoteDebugSecurityTransitionGate.withLock {
                sentinel.securityAuthorityEpoch.takeIf { securityReady() }
            }

            override fun <T> commit(
                expectedEpoch: Long,
                action: () -> T,
            ): GuardDbMaintenanceSecurityResult<T> =
                when (val result = RemoteDebugSecurityTransitionGate.withEpoch(expectedEpoch) {
                    if (securityReady()) GuardDbMaintenanceSecurityResult.Value(action())
                    else GuardDbMaintenanceSecurityResult.Refused
                }) {
                    RemoteDebugAuthorityResult.Changed -> GuardDbMaintenanceSecurityResult.Changed
                    is RemoteDebugAuthorityResult.Value -> result.value
                }
        }
        server = GuardDbMaintenanceServer(
            context = applicationContext,
            sentinel = sentinel,
            client = GuardDbMaintenance.client,
            staging = staging,
            preparedStore = preparedStore,
            sentinelStore = sentinelStore,
            security = security,
            bootstrapExport = GuardDbBootstrapExportDependencies(
                snapshot = { expectedEpoch ->
                    guardDbBootstrapExportSnapshot(
                        context = applicationContext,
                        expectedSentinel = sentinel,
                        expectedSecurityEpoch = expectedEpoch,
                        client = GuardDbMaintenance.client,
                        loadSentinel = sentinelStore::load,
                        loadPrepared = preparedStore::load,
                        exactManifest = { prepared -> prepared.exactManifest(staging) != null },
                        remoteDebugOff = {
                            AdbController.proveMaintenanceRemoteDebugOff(applicationContext)
                        },
                        cdpRelayAbsent = { !CdpRelay.running },
                    )
                },
                readDatabase = { prepared ->
                    readGuardDbBootstrapDatabase(applicationContext, prepared)
                },
                databaseStillExact = { prepared ->
                    guardDbBootstrapDatabaseStillExact(applicationContext, prepared)
                },
                monotonicMs = SystemClock::elapsedRealtime,
            ),
        ) { requestFreshProcessBoundary(responseGraceMs = 500L) }.also { it.start() }
    }

    private fun requestFreshProcessBoundary(responseGraceMs: Long = 0L) {
        // The OS-owned alarm starts PaneldService after this process dies. It must be published
        // before exit; direct start could be consumed under this process's still-valid fence.
        GuardDbSuccessorHandoff(
            publishAlarmRetry = { GuardDbSuccessorAlarm.schedule(applicationContext) },
            exitCurrentProcess = {
                if (responseGraceMs == 0L) {
                    Process.killProcess(Process.myPid())
                } else {
                    // Let an accepted HTTP response flush before crossing the process boundary.
                    Thread {
                        Thread.sleep(responseGraceMs)
                        Process.killProcess(Process.myPid())
                    }.start()
                }
            },
            scheduleAlarmPublicationRetry = { delayMs, retry ->
                scope.launch {
                    delay(delayMs)
                    retry()
                }
            },
            onPublicationFailure = { failure ->
                Log.e(TAG, "Guard DB successor alarm publication failed; retaining maintenance", failure)
            },
        ).request()
    }

    override fun onDestroy() {
        destroyed = true
        scope.cancel()
        server?.stop()
        server = null
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null

    private fun foreground() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, nativeString(R.string.database_recovery_channel), NotificationManager.IMPORTANCE_LOW),
            )
        }
        val activity = PendingIntent.getActivity(
            this,
            0,
            Intent(this, GuardDbMaintenanceActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(nativeString(R.string.guard_db_activity_label))
            .setContentText(nativeString(R.string.database_recovery_notification))
            .setOngoing(true)
            .setContentIntent(activity)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val TAG = "ha-paneld/guard-db-service"
        private const val CHANNEL = "guard-db-maintenance"
        private const val NOTIFICATION_ID = 0x48414744

        fun start(context: Context) {
            val intent = Intent(context, GuardDbMaintenanceService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        }
    }
}

/** DB-free authority composition for bootstrap export. This must never instantiate Config/AppState/SQLite. */
internal fun guardDbBootstrapExportSnapshot(
    context: Context,
    expectedSentinel: GuardDbStartupSentinel,
    expectedSecurityEpoch: Long,
    client: GuardDbMaintenanceClient,
    loadSentinel: () -> GuardDbSentinelLoad,
    loadPrepared: () -> GuardDbPreparedArmLoad,
    exactManifest: (GuardDbPreparedArm) -> Boolean,
    remoteDebugOff: () -> Boolean,
    cdpRelayAbsent: () -> Boolean,
): GuardDbBootstrapExportSnapshot? {
    val sentinel = (loadSentinel() as? GuardDbSentinelLoad.Valid)?.sentinel
        ?.takeIf { it == expectedSentinel && it.state == GuardDbSentinelState.BASELINE_READY }
        ?: return null
    if (guardDbBootNonce() != sentinel.bootNonce || expectedSecurityEpoch != sentinel.securityAuthorityEpoch) {
        return null
    }
    val prepared = (loadPrepared() as? GuardDbPreparedArmLoad.Valid)?.prepared
        ?.takeIf { it.matches(sentinel) && exactManifest(it) }
        ?: return null
    val source = File(context.applicationInfo.sourceDir)
    val installed = inspectGuardDbCandidate(context, source) ?: return null
    if (installed.bytes != prepared.aBytes || installed.sha256 != prepared.aSha256 ||
        installed.versionCode != prepared.aVersionCode || installed.contractMinimum != prepared.aContractMinimum ||
        installed.contractMaximum != prepared.aContractMaximum || installed.expectedSchema != prepared.aSchema
    ) return null
    if (client.capabilities()?.let { it.autonomous && it.supervised && it.terminalRetire } != true) return null
    if ((client.statusProbe() as? GuardDbMaintenanceClient.StatusProbe.Valid)?.status
            ?.let(::exactEmptyGuardDbStatus) != true
    ) return null
    val helper = client.selfIdentity() ?: return null
    val authority = (RemoteDebugSecurityAuthorityStore(context.noBackupFilesDir).load()
        as? RemoteDebugSecurityAuthorityLoad.Valid)?.authority
        ?.takeIf { it.state == RemoteDebugSecurityState.HARDENED && it.epoch == expectedSecurityEpoch }
        ?: return null
    val authoritySha256 = sha256(encodeRemoteDebugSecurityAuthority(authority))
    val remoteOff = remoteDebugOff()
    val relayAbsent = cdpRelayAbsent()
    if (!remoteOff || !relayAbsent) return null
    return GuardDbBootstrapExportSnapshot(
        sentinel = sentinel,
        prepared = prepared,
        installedA = GuardDbBootstrapInstalledApp(
            bytes = installed.bytes,
            sha256 = installed.sha256,
            signerSha256 = installed.signerSha256,
            versionCode = installed.versionCode,
            versionName = Config.VERSION,
            contractMinimum = installed.contractMinimum,
            contractMaximum = installed.contractMaximum,
            schema = installed.expectedSchema,
        ),
        helper = GuardDbBootstrapHelper(
            bytes = helper.bytes,
            sha256 = helper.sha256,
            buildId = helper.buildId,
            capabilitiesReply = GuardDbBootstrapExportSnapshot.SUPPORTED_CAPABILITIES_REPLY,
            statusReply = GuardDbBootstrapExportSnapshot.EMPTY_STATUS_REPLY,
        ),
        security = GuardDbBootstrapSecurity(
            state = authority.state.name,
            epoch = authority.epoch,
            authoritySha256 = authoritySha256,
            remoteDebugOff = true,
            cdpRelayAbsent = true,
        ),
    ).takeIf { it.exactFor(expectedSentinel) }
}

internal fun readGuardDbBootstrapDatabase(
    context: Context,
    prepared: GuardDbPreparedArm,
): GuardDbBootstrapDatabaseRead {
    if (prepared.databaseBytes > MAX_GUARD_DB_BOOTSTRAP_DATABASE_BYTES) {
        return GuardDbBootstrapDatabaseRead.TooLarge
    }
    val database = context.getDatabasePath("ha-paneld.db")
    if (!guardDbBootstrapDatabaseStillExact(context, prepared)) {
        return GuardDbBootstrapDatabaseRead.Mismatch
    }
    val bytes = try {
        database.inputStream().use { BoundedStreams.readBytes(it, MAX_GUARD_DB_BOOTSTRAP_DATABASE_BYTES) }
    } catch (_: ByteLimitExceeded) {
        return GuardDbBootstrapDatabaseRead.TooLarge
    } catch (_: Exception) {
        return GuardDbBootstrapDatabaseRead.Mismatch
    }
    if (bytes.size.toLong() != prepared.databaseBytes || sha256(bytes) != prepared.databaseSha256 ||
        !guardDbBootstrapDatabaseStillExact(context, prepared)
    ) return GuardDbBootstrapDatabaseRead.Mismatch
    return GuardDbBootstrapDatabaseRead.Exact(bytes)
}

internal fun guardDbBootstrapDatabaseStillExact(
    context: Context,
    prepared: GuardDbPreparedArm,
): Boolean {
    val database = context.getDatabasePath("ha-paneld.db")
    return prepared.databaseBytes in 1..MAX_GUARD_DB_BOOTSTRAP_DATABASE_BYTES &&
        stableGuardDbCanonicalMain(context.applicationContext) && database.length() == prepared.databaseBytes &&
        runCatching { AppInstaller.sha256(database) == prepared.databaseSha256 }.getOrDefault(false)
}

private fun exactEmptyGuardDbStatus(status: GuardDbMaintenanceProtocol.Status): Boolean =
    status.generation == 0L && status.phase == GuardDbMaintenanceProtocol.Phase.EMPTY &&
        status.session == null && status.bootNonce == null && status.role == null && status.apkSha256 == null &&
        status.versionCode == null && status.schema == null && status.baselineAppStateCount == 0L &&
        status.error == null && status.outcome == null && status.overallDeadlineElapsedMs == 0L &&
        status.forwardDeadlineElapsedMs == 0L

private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes).joinToString("") { "%02x".format(it) }

/** Pure ordering seam used by the maintenance service: no Config/AppState/SQLite owner is permitted. */
internal fun guardDbMaintenanceSecurityReady(
    expectedEpoch: Long,
    durableHardenedEpoch: () -> Long?,
    relayRunning: () -> Boolean,
    remoteDebugOff: () -> Boolean,
): Boolean {
    if (expectedEpoch <= 0L || durableHardenedEpoch() != expectedEpoch || relayRunning()) return false
    if (!remoteDebugOff()) return false
    return durableHardenedEpoch() == expectedEpoch && !relayRunning()
}
