package io.github.maxlyth.hapaneld.migration

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import io.github.maxlyth.hapaneld.AppIdentity
import io.github.maxlyth.hapaneld.migration.SuccessorMigration.Environment
import io.github.maxlyth.hapaneld.panelAssistantDiscoveryId
import io.github.maxlyth.hapaneld.platform.AndroidSystemEnv
import io.github.maxlyth.hapaneld.platform.DaemonLongResult
import io.github.maxlyth.hapaneld.util.AppInstaller
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
    /** Completed by the server when the migration-mode restore is durable. */
    private val restoreCommitted: CompletableDeferred<Unit> = CompletableDeferred(),
) : SuccessorMigration.Ports {
    private val context = context.applicationContext
    private val own = context.packageName
    private val legacy = AppIdentity.LEGACY

    override fun environment(): Environment = environment
    override fun legacyInstalled(): Boolean = IdentityMigrationGate.legacyInstalled(context)

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
            Files.move(staged.toPath(), state.receipt.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            receiptSha256()
        }.getOrElse {
            Log.w(TAG, "receipt pull failed", it)
            staged.delete()
            null
        }
    }

    override fun receiptRefusal(): String? =
        ReceiptVerifier.refusal(state.receipt, panelAssistantDiscoveryId(androidId))

    override fun discardReceipt() { state.receipt.delete() }

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

    override suspend fun restoreReceipt(): Boolean = withContext(Dispatchers.IO) {
        val accepted = runCatching {
            val connection = open("/api/v1/restore?mode=migration", "POST").apply {
                doOutput = true
                setFixedLengthStreamingMode(state.receipt.length())
                setRequestProperty("Content-Type", "application/octet-stream")
                state.receipt.inputStream().use { input -> outputStream.use { input.copyTo(it) } }
            }
            try { connection.responseCode == 200 } finally { connection.disconnect() }
        }.getOrDefault(false)
        accepted && withTimeoutOrNull(RESTORE_WAIT_MS) { restoreCommitted.await() } != null
    }

    override fun missingGrants(): Set<String> = GRANTS.filterTo(linkedSetOf()) { grant ->
        held(grant, legacy) && !held(grant, own)
    }

    override fun claimGrant(grant: String): Boolean {
        if (grant !in GRANTS) return false
        return (HelperClient.sendLong("GRANT $own $grant", HELPER_TIMEOUT_MS) as? DaemonLongResult.Reply)?.value == "OK"
    }

    override fun claimHome(): Boolean =
        HelperClient.send("SETHOME ${AppIdentity.component(own, ".DashboardActivity")}") == "OK"

    override fun homeIsOwn(): Boolean = AndroidSystemEnv(context).defaultHome()?.pkg == own

    override fun healthy(): Boolean = runCatching {
        val connection = open("/health", "GET")
        try {
            connection.responseCode == 200 &&
                connection.inputStream.bufferedReader().use { it.readLine().orEmpty() }.startsWith("ha-paneld ")
        } finally {
            connection.disconnect()
        }
    }.getOrDefault(false)

    override fun mqttConverged(): Boolean = mqttState().let { it == "connected" || it == "disabled" }

    override fun uninstallLegacy(): Boolean =
        (HelperClient.sendLong("UNINSTALL $legacy", HELPER_TIMEOUT_MS) as? DaemonLongResult.Reply)?.value == "OK"

    /** Whether [pkg] currently holds the platform state behind one helper `GRANT` capability. */
    private fun held(grant: String, pkg: String): Boolean = runCatching {
        val pm = context.packageManager
        when (grant) {
            "NOTIFICATIONS" -> pm.checkPermission(Manifest.permission.POST_NOTIFICATIONS, pkg) == PackageManager.PERMISSION_GRANTED
            "MICROPHONE" -> pm.checkPermission(Manifest.permission.RECORD_AUDIO, pkg) == PackageManager.PERMISSION_GRANTED
            "BATTERY" -> context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(pkg)
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

    private companion object {
        const val TAG = "ha-paneld/migration"
        const val HELPER_TIMEOUT_MS = 60_000L
        const val RETIREMENT_WAIT_MS = 60_000L
        const val RESTORE_WAIT_MS = 5 * 60_000L

        /** The helper's fixed `GRANT` capability table. */
        val GRANTS = listOf("NOTIFICATIONS", "MICROPHONE", "WRITESETTINGS", "OVERLAY", "BATTERY", "ACCESSIBILITY")
    }
}
