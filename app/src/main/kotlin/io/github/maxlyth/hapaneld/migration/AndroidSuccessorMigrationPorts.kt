package io.github.maxlyth.hapaneld.migration

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import io.github.maxlyth.hapaneld.AppIdentity
import io.github.maxlyth.hapaneld.device.profile.RuntimeProfileRegistry
import io.github.maxlyth.hapaneld.migration.SuccessorMigration.Environment
import io.github.maxlyth.hapaneld.panelAssistantDiscoveryId
import io.github.maxlyth.hapaneld.platform.AndroidSystemEnv
import io.github.maxlyth.hapaneld.platform.DaemonLongResult
import io.github.maxlyth.hapaneld.platform.NotificationPermissionRepair
import io.github.maxlyth.hapaneld.util.AppInstaller
import io.github.maxlyth.hapaneld.util.BoundedStreams
import io.github.maxlyth.hapaneld.util.HelperClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Why the restore endpoint refused a migration restore, from its status and body. The body's
 * presentation code and error are what the successor would otherwise never show: a refused restore
 * leaves the panel on defaults, and "restore did not complete" does not say what to put right.
 */
internal fun restoreRefusal(status: Int, body: ByteArray?): String {
    val json = body?.let { runCatching { org.json.JSONObject(String(it, Charsets.UTF_8)) }.getOrNull() }
    val code = json?.optJSONObject("presentation")?.optString("code").orEmpty()
    val error = json?.optString("error").orEmpty()
    val errors = json?.optJSONArray("errors")?.let { array -> (0 until array.length()).map { array.optString(it) } }.orEmpty()
    return buildString {
        append("restore refused (HTTP ").append(status)
        if (code.isNotEmpty()) append(", ").append(code)
        append(")")
        if (error.isNotEmpty()) append(": ").append(error)
        if (errors.isNotEmpty()) append(" — ").append(errors.joinToString("; "))
    }.take(600)
}

/**
 * The successor's real [SuccessorMigration.Ports]. Everything it asks of the legacy app goes over
 * loopback HTTP or the signature-protected status broadcast; everything it needs root for goes through
 * the dual-uid helper, never `su`, because on most panels `su` has only ever authorised the legacy uid.
 */
internal class AndroidSuccessorMigrationPorts(
    context: Context,
    private val state: MigrationState,
    private val environment: Environment,
    private val httpPort: Int,
    private val androidId: String,
    private val mqttState: () -> String = { "disabled" },
    /** A fresh outcome for one restore attempt, completed by the server when that restore ends. */
    private val beginRestore: () -> CompletableDeferred<Boolean> = { CompletableDeferred() },
) : SuccessorMigration.Ports {
    private val context = context.applicationContext
    private val own = context.packageName
    private val legacy = AppIdentity.LEGACY

    override fun environment(): Environment = environment
    override fun legacyInstalled(): Boolean = IdentityMigrationGate.legacyInstalled(context)

    override fun releaseTokenHeld(): Boolean = ReleaseToken.of(context).current() != null

    override fun receiptSha256(): String? =
        state.receipt.takeIf(File::isFile)?.let { runCatching { AppInstaller.sha256(it) }.getOrNull() }

    override suspend fun pullReceipt(): String? = withContext(Dispatchers.IO) {
        val staged = File(state.receipt.parentFile, ".receipt.zip.tmp")
        runCatching {
            Files.createDirectories(staged.parentFile!!.toPath())
            val connection = open("/api/v1/backup", "POST").apply {
                doOutput = true
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                outputStream.use { it.write("allow_plaintext=1&include_companion=0".toByteArray()) }
            }
            try {
                if (connection.responseCode != 200) return@runCatching null
                connection.inputStream.use { input ->
                    java.io.FileOutputStream(staged, false).use { output ->
                        input.copyTo(output)
                        output.fd.sync()
                    }
                }
            } finally {
                connection.disconnect()
            }
            // Promote only a verified archive: the stored receipt may be the last one obtainable.
            ReceiptVerifier.refusal(staged, panelAssistantDiscoveryId(androidId))?.let { refusal ->
                Log.w(TAG, "pulled backup was not kept: $refusal")
                staged.delete()
                return@runCatching null
            }
            Files.move(staged.toPath(), state.receipt.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            receiptSha256()
        }.getOrElse {
            Log.w(TAG, "receipt pull failed", it)
            staged.delete()
            null
        }
    }

    // The profile catalog is planned here, before the release, against this build's bundled catalog: a
    // successor that has not restored has nothing else, so this is the plan its restore will make.
    override fun receiptRefusal(): String? =
        ReceiptVerifier.migrationRefusal(state.receipt, panelAssistantDiscoveryId(androidId)) {
            RuntimeProfileRegistry.bundledOnly(context).planBackupRestore(it)
        }

    override suspend fun legacyRetired(): Boolean = MigrationTokenReceiver.legacyRetired(context)

    override suspend fun requestRelease(): String? = withContext(Dispatchers.IO) {
        val token = ReleaseToken.of(context).current() ?: return@withContext "no release token has been delivered"
        val code = runCatching {
            val connection = open("/api/v1/successor/release", "POST").apply {
                setRequestProperty(ReleaseToken.HEADER, token)
                doOutput = true
                outputStream.use { }
            }
            try { connection.responseCode } finally { connection.disconnect() }
        }.getOrElse { return@withContext "legacy app did not answer the release request" }
        if (code != 200 && code != 202) return@withContext "legacy app refused the release (HTTP $code)"
        // Admitted. The legacy app retires only after its service has torn down, so give it that long
        // here rather than reporting a wait that the very next status query would have resolved.
        withTimeoutOrNull(RETIREMENT_WAIT_MS) {
            while (!MigrationTokenReceiver.legacyRetired(context)) delay(1_000)
        }
        null
    }

    override fun portFree(): Boolean = runCatching {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", httpPort), 1_000) }
        false
    }.getOrDefault(true)

    override suspend fun restoreReceipt(): String? = withContext(Dispatchers.IO) {
        val outcome = beginRestore()
        val refusal = runCatching {
            val connection = open("/api/v1/restore?mode=migration", "POST").apply {
                doOutput = true
                setFixedLengthStreamingMode(state.receipt.length())
                setRequestProperty("Content-Type", "application/octet-stream")
                state.receipt.inputStream().use { input -> outputStream.use { input.copyTo(it) } }
            }
            try {
                val code = connection.responseCode
                if (code == 200) null
                else restoreRefusal(code, runCatching { connection.errorStream?.use { BoundedStreams.readBytes(it, MAX_REFUSAL_BYTES) } }.getOrNull())
            } finally {
                connection.disconnect()
            }
        }.getOrElse { "restore request failed: ${it.javaClass.simpleName}" }
        refusal ?: when (withTimeoutOrNull(RESTORE_WAIT_MS) { outcome.await() }) {
            true -> null
            false -> "restore did not complete"
            null -> "restore did not become durable within ${RESTORE_WAIT_MS / 60_000} minutes"
        }
    }

    override fun missingGrants(): Set<String> = missingGrants(Build.VERSION.SDK_INT, own, legacy, ::held)

    override fun claimGrant(grant: String): Boolean {
        if (grant !in GRANTS) return false
        return (HelperClient.sendLong("GRANT $own $grant", HELPER_TIMEOUT_MS) as? DaemonLongResult.Reply)?.value == "OK"
    }

    override fun claimHome(): Boolean =
        HelperClient.send("SETHOME ${AppIdentity.component(own, ".DashboardActivity")}") == "OK"

    override fun homeSettled(): Boolean = homeSettled(AndroidSystemEnv(context).defaultHome()?.pkg, own, legacy)

    override fun healthy(): Boolean = runCatching {
        val connection = open("/health", "GET")
        try {
            connection.responseCode == 200 &&
                connection.inputStream.bufferedReader().use { it.readLine().orEmpty() }
                    .let { it.startsWith("ha-paneld ") && " pkg=$own" in "$it " }
        } finally {
            connection.disconnect()
        }
    }.getOrDefault(false)

    // "Unchanged" is measured against the legacy app, not against an ideal: a panel that was connected
    // when it wrote the receipt must be connected again before the legacy package goes, and a panel
    // that was never connected is not held for ever to a broker it never had.
    override fun mqttConverged(): Boolean =
        mqttState() == "connected" || !ReceiptVerifier.legacyMqttConnected(state.receipt)

    override fun uninstallLegacy(): Boolean =
        (HelperClient.sendLong("UNINSTALL $legacy", HELPER_TIMEOUT_MS) as? DaemonLongResult.Reply)?.value == "OK"

    override fun forgetSecrets() {
        val directory = state.receipt.parentFile ?: return
        listOf(state.receipt, File(directory, "release-token.v1"), File(directory, "legacy-port.v1"))
            .forEach { runCatching { it.delete() } }
    }

    /** Whether [pkg] currently holds the platform state behind one helper `GRANT` capability. */
    private fun held(grant: String, pkg: String): Boolean = runCatching {
        val pm = context.packageManager
        when (grant) {
            "NOTIFICATIONS" -> pm.checkPermission(Manifest.permission.POST_NOTIFICATIONS, pkg) == PackageManager.PERMISSION_GRANTED
            "MICROPHONE" -> pm.checkPermission(Manifest.permission.RECORD_AUDIO, pkg) == PackageManager.PERMISSION_GRANTED
            // Claimed whatever the legacy app held. The service re-arms its own start across a process
            // boundary, and from Android 12 only a battery-exempt app may start a foreground service
            // from the background. Without it the restart that follows the restore ends the process and
            // nothing starts it again, with the legacy app already retired. Found on an emulator.
            "BATTERY" -> if (pkg == own) {
                context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(own)
            } else true
            "ACCESSIBILITY" -> Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                .orEmpty().split(':').any { it.substringBefore('/') == pkg }
            // App-op state of another package is not readable without a privileged permission. These
            // two are provisioned on every supported panel, so they are claimed whenever this app
            // cannot prove it holds them, and the helper's grant is idempotent.
            "OVERLAY" -> if (pkg == own) Settings.canDrawOverlays(context) else true
            "WRITESETTINGS" -> if (pkg == own) Settings.System.canWrite(context) else true
            else -> false
        }
    }.getOrDefault(false)

    private fun open(path: String, method: String): HttpURLConnection =
        (URL("http://127.0.0.1:$httpPort$path").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 5_000
            readTimeout = 120_000
            useCaches = false
        }

    internal companion object {
        /**
         * Grants the legacy app holds that this app does not. Notifications are the exception: claimed
         * whenever this app lacks them, whatever the legacy app held, because the service notification
         * keeps the panel working and a person's denial there may have been a mis-tap.
         */
        fun missingGrants(sdkInt: Int, own: String, legacy: String, held: (grant: String, pkg: String) -> Boolean): Set<String> =
            GRANTS.filterTo(linkedSetOf()) { grant ->
                if (grant == "NOTIFICATIONS") !NotificationPermissionRepair.held(sdkInt) { held(grant, own) }
                else held(grant, legacy) && !held(grant, own)
            }

        private const val TAG = "ha-paneld/migration"
        private const val HELPER_TIMEOUT_MS = 60_000L
        private const val RETIREMENT_WAIT_MS = 60_000L
        private const val RESTORE_WAIT_MS = 5 * 60_000L
        private const val MAX_REFUSAL_BYTES = 64L * 1024L

        /** The helper's fixed `GRANT` capability table. */
        private val GRANTS = listOf("NOTIFICATIONS", "MICROPHONE", "WRITESETTINGS", "OVERLAY", "BATTERY", "ACCESSIBILITY")
    }
}
