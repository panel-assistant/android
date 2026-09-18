package io.github.maxlyth.hapaneld.migration

import android.content.Context
import android.provider.Settings
import android.util.Log
import io.github.maxlyth.hapaneld.AppIdentity
import io.github.maxlyth.hapaneld.Config
import io.github.maxlyth.hapaneld.migration.SuccessorMigration.Environment
import io.github.maxlyth.hapaneld.migration.SuccessorMigration.Result
import io.github.maxlyth.hapaneld.migration.SuccessorMigration.Step
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Runs [SuccessorMigration] passes until the machine needs something only its caller can provide. */
internal object SuccessorMigrationRunner {
    private const val TAG = "ha-paneld/migration"
    private const val FIRST_RETRY_MS = 5_000L
    private const val MAX_RETRY_MS = 5 * 60_000L

    /**
     * Pass, and pass again with a growing delay while a step is waiting. Returns the first result that
     * is not a wait. The outcome is logged only when it changes: a refused release repeats for as long
     * as the panel's helper cannot carry the handover.
     */
    suspend fun drive(migration: SuccessorMigration): Result {
        var retryMs = FIRST_RETRY_MS
        var last: Result? = null
        while (true) {
            val result = runCatching { migration.pass() }
                .getOrElse { Result.Waiting(Step.PULL, "pass failed: ${it.javaClass.simpleName}") }
            if (result != last) Log.i(TAG, "identity migration: $result")
            last = result
            if (result !is Result.Waiting) return result
            delay(retryMs)
            retryMs = (retryMs * 2).coerceAtMost(MAX_RETRY_MS)
        }
    }

    /** The successor before the legacy app has released the panel. No app state exists yet. */
    /**
     * The passive pass ends by ending the process, never by stopping the service. The passive service
     * is sticky and in the foreground, so Android recreates it in a fresh process, which reads the
     * durable markers and starts held. Stopping it and starting another would leave an instant with no
     * foreground service, from which Android 12 and later refuse the start and nothing else would ever
     * restart an app whose legacy counterpart has already retired.
     */
    fun runPassive(
        context: Context,
        scope: CoroutineScope,
        startHeldService: () -> Unit = { kotlin.system.exitProcess(0) },
    ): Job = scope.launch {
        val appContext = context.applicationContext
        val state = MigrationState.of(appContext)
        val androidId = Settings.Secure.getString(appContext.contentResolver, Settings.Secure.ANDROID_ID).orEmpty()
        val ports = AndroidSuccessorMigrationPorts(
            appContext,
            state,
            Environment.PASSIVE,
            httpPort = LegacyPort.of(appContext).read() ?: Config.DEFAULT_PORT,
            androidId = androidId,
        )
        when (drive(SuccessorMigration(ports, state))) {
            Result.NeedsHeldService -> startHeldService()
            // Nothing left to wait for: the ordinary service may start as it would on any panel.
            Result.NotNeeded, Result.Complete, Result.NeedsRestart -> startHeldService()
            is Result.Waiting -> Unit
        }
    }
}

/** The legacy app's HTTP port, delivered with the release token; absent means the default. */
internal class LegacyPort(noBackupFilesDir: java.io.File) {
    private val record = DurableTextFile(noBackupFilesDir.resolve("identity-migration").resolve("legacy-port.v1"), 8)

    fun read(): Int? = record.read()?.toIntOrNull()?.takeIf { it in 1..65_535 }
    fun accept(port: Int): Boolean = port in 1..65_535 && record.write(port.toString())

    companion object {
        fun of(context: Context): LegacyPort = LegacyPort(context.noBackupFilesDir)
    }
}

/** The service's [IdentityMigrationSurface] for whichever identity this build carries. */
internal class AndroidIdentityMigration(
    context: Context,
    private val scope: CoroutineScope,
    private val httpPort: () -> Int,
    private val androidId: () -> String,
    private val mqttState: () -> String,
    private val offerHandoff: suspend () -> SuccessorHandoff.Outcome?,
    private val requestRestart: () -> Unit,
) : IdentityMigrationSurface {
    private val context = context.applicationContext
    private val state = MigrationState.of(this.context)
    private val restoreCommitted = CompletableDeferred<Unit>()
    private val held = IdentityMigrationGate.holdsNetworkIdentity()

    override fun restoreOpen(): Boolean =
        !AppIdentity.IS_BRIDGE && held && state.done(Step.RELEASE) && !state.done(Step.RESTORE)

    override fun onRestoreCommitted() { restoreCommitted.complete(Unit) }

    override suspend fun offer(): SuccessorHandoff.Outcome? = offerHandoff()

    override suspend fun release(token: String?, loopback: Boolean): BridgeRelease.Outcome =
        BridgeRelease(AndroidBridgeReleasePorts(context)).request(token, loopback)

    /** Successor: continue a pending migration now that the server is listening. */
    fun startInService(): Job? {
        if (!IdentityMigrationGate.migrationPending(context)) return null
        val ports = AndroidSuccessorMigrationPorts(
            context,
            state,
            if (held) Environment.HELD_SERVICE else Environment.SERVICE,
            httpPort = httpPort(),
            androidId = androidId(),
            mqttState = mqttState,
            restoreCommitted = restoreCommitted,
        )
        return scope.launch {
            if (SuccessorMigrationRunner.drive(SuccessorMigration(ports, state)) == Result.NeedsRestart) {
                requestRestart()
            }
        }
    }
}
