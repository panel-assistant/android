package io.panelassistant.android.shizuku

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest

/** Verifies the separately installed manager before ha-paneld binds to it. */
object ShizukuManagerIdentity {
    const val PACKAGE = "moe.shizuku.privileged.api"
    const val CERT_SHA256 = "268b5590e868fb08bae7e0ac413564cd1ff88f5ccff74af9dbd0dc918e30db30"

    enum class Status { MISSING, TRUSTED, UNTRUSTED }

    @Suppress("DEPRECATION")
    fun status(context: Context): Status {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        val info = runCatching { context.packageManager.getPackageInfo(PACKAGE, flags) }
            .getOrNull() ?: return Status.MISSING
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners
        } else {
            info.signatures
        }
        val trusted = signatures.orEmpty().any { signature ->
            val digest = MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
                .joinToString("") { byte -> "%02x".format(byte) }
            digest.equals(CERT_SHA256, ignoreCase = true)
        }
        return if (trusted) Status.TRUSTED else Status.UNTRUSTED
    }
}
