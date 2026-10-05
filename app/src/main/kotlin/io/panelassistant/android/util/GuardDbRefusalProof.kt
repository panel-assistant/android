package io.panelassistant.android.util

import android.content.Context
import android.os.Build
import android.system.Os
import android.system.OsConstants
import io.panelassistant.android.dashboard.DatabaseCompatibility
import io.panelassistant.android.dashboard.DatabaseCompatibilityBoundary
import io.panelassistant.android.dashboard.DatabaseCompatibilityDecision
import io.panelassistant.android.dashboard.DatabaseCompatibilityRefusal
import io.panelassistant.android.dashboard.DatabaseOwnerState
import java.io.File
import java.security.MessageDigest

internal data class GuardDbExactARefusalProof(
    val aSha256: String,
    val aVersionCode: Long,
    val installedBSha256: String,
    val installedBVersionCode: Long,
    val databaseInventorySha256: String,
)

/**
 * Run A's ordinary compatibility decision under installed B without opening A or submitting a package
 * mutation. Exact package identity and the complete database-directory inventory must be unchanged
 * across the observation; only the one typed no-premigrate refusal is admissible.
 */
internal fun proveExactARefusalUnderB(
    context: Context,
    prepared: GuardDbPreparedArm,
    staging: GuardDbAppStaging,
): GuardDbExactARefusalProof? {
    val manifest = prepared.exactManifest(staging) ?: return null
    val a = manifest.a
    val b = manifest.b
    val inspectedA = inspectGuardDbCandidate(context, a.file) ?: return null
    if (inspectedA.bytes != a.bytes || inspectedA.sha256 != a.sha256 ||
        inspectedA.versionCode != a.versionCode || inspectedA.signerSha256 != manifest.signerSha256 ||
        inspectedA.contractMinimum != a.contractMinimum || inspectedA.contractMaximum != a.contractMaximum ||
        inspectedA.expectedSchema != a.expectedSchema
    ) return null
    val installedBefore = installedGuardDbIdentity(context) ?: return null
    if (installedBefore.first != b.sha256 || installedBefore.second != b.versionCode) return null
    val databaseDirectory = context.getDatabasePath("ha-paneld.db").parentFile ?: return null
    val inventoryBefore = exactDatabaseInventory(databaseDirectory) ?: return null
    val decision = DatabaseCompatibility.observeAndDecide(
        context,
        DatabaseCompatibilityBoundary(1, "ha-paneld.db", a.contractMinimum, a.contractMaximum),
        DatabaseOwnerState.PACKAGE_PRESENT,
    )
    val inventoryAfter = exactDatabaseInventory(databaseDirectory) ?: return null
    val installedAfter = installedGuardDbIdentity(context) ?: return null
    if (inventoryAfter != inventoryBefore || installedAfter != installedBefore) return null
    if ((decision as? DatabaseCompatibilityDecision.Refuse)?.reason !=
        DatabaseCompatibilityRefusal.PRIMARY_ABOVE_MAXIMUM_WITHOUT_PREMIGRATE
    ) return null
    return GuardDbExactARefusalProof(
        aSha256 = a.sha256,
        aVersionCode = a.versionCode,
        installedBSha256 = installedBefore.first,
        installedBVersionCode = installedBefore.second,
        databaseInventorySha256 = sha256Ascii(inventoryBefore),
    )
}

private fun installedGuardDbIdentity(context: Context): Pair<String, Long>? = runCatching {
    val packageInfo = if (Build.VERSION.SDK_INT >= 33) {
        context.packageManager.getPackageInfo(context.packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0))
    } else {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0)
    }
    val applicationInfo = packageInfo.applicationInfo ?: return null
    val source = File(applicationInfo.sourceDir)
    if (!source.isFile) return null
    val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) packageInfo.longVersionCode else {
        @Suppress("DEPRECATION")
        packageInfo.versionCode.toLong()
    }
    AppInstaller.sha256(source) to versionCode
}.getOrNull()

private fun exactDatabaseInventory(directory: File): String? = runCatching {
    val entries = directory.listFiles()?.sortedBy(File::getName) ?: return null
    if (entries.size > 128) return null
    buildString {
        entries.forEach { file ->
            val stat = Os.lstat(file.absolutePath)
            val type = stat.st_mode and OsConstants.S_IFMT
            if (type != OsConstants.S_IFREG) return null
            val digest = AppInstaller.sha256(file)
            append(file.name.length).append(':').append(file.name)
            append(':').append(stat.st_mode and 0xfff)
            append(':').append(stat.st_uid).append(':').append(stat.st_gid).append(':').append(stat.st_nlink)
            append(':').append(file.length()).append(':').append(digest).append('\n')
        }
    }
}.getOrNull()

private fun sha256Ascii(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.US_ASCII))
    .joinToString("") { "%02x".format(it) }
