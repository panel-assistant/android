package io.panelassistant.android.util

import io.panelassistant.android.platform.Daemon
import java.io.File

/**
 * Tracks helper-install inputs owned by this process and identifies leftovers from an earlier or
 * indeterminate transaction. Claim and candidate selection share one lock, so reconciliation can
 * never select a file between its creation and registration as an active input.
 */
internal class HelperInstallStaging {
    private val lock = Any()
    private val active = mutableSetOf<String>()

    fun claim(apk: File, stagingDir: File): File? = synchronized(lock) {
        if (!apk.isFile || apk.length() <= 0L) {
            apk.delete()
            return@synchronized null
        }
        if (!stagingDir.isDirectory && !stagingDir.mkdirs()) {
            apk.delete()
            return@synchronized null
        }
        val expected = apk.length()
        val owned = runCatching {
            File.createTempFile(HelperInstallTransaction.STAGING_PREFIX, ".apk", stagingDir).also {
                if (!it.delete()) error("could not reserve staging path")
            }
        }.getOrElse {
            apk.delete()
            return@synchronized null
        }
        val claimed = apk.renameTo(owned) || runCatching {
            apk.inputStream().use { input -> owned.outputStream().use { input.copyTo(it) } }
            owned.length() == expected
        }.getOrDefault(false)
        if (!claimed || !owned.isFile || owned.length() != expected) {
            owned.delete()
            apk.delete()
            return@synchronized null
        }
        apk.delete()
        active += owned.absolutePath
        owned
    }

    fun release(owned: File, delete: Boolean) = synchronized(lock) {
        active -= owned.absolutePath
        if (delete) owned.delete()
        Unit
    }

    fun staleFiles(stagingDir: File): List<File> = synchronized(lock) {
        stagingDir.listFiles().orEmpty().filter { file ->
            file.isFile &&
                file.name.startsWith(HelperInstallTransaction.STAGING_PREFIX) &&
                file.name.endsWith(".apk") &&
                file.absolutePath !in active
        }
    }

    companion object {
        val shared = HelperInstallStaging()
    }
}

internal data class HelperInstallReconcileResult(
    val removed: Int,
    val remaining: Int,
) {
    val complete: Boolean get() = remaining == 0
}

/**
 * Reconciles retained inputs through the helper before deleting them. `INSTALLGC` records a
 * cancellation tombstone while holding the daemon's install mutex; an active worker yields `BUSY`,
 * while a delayed worker must observe the tombstone after acquiring the mutex and abort.
 */
internal class HelperInstallReconciler(
    private val daemon: Daemon,
    private val staging: HelperInstallStaging = HelperInstallStaging.shared,
) {
    fun reconcile(stagingDir: File): HelperInstallReconcileResult {
        var removed = 0
        staging.staleFiles(stagingDir).forEach { file ->
            if (!file.exists()) return@forEach
            if (daemon.send("INSTALLGC ${file.absolutePath}") == "OK") {
                if (file.delete() || !file.exists()) removed++
            }
        }
        return HelperInstallReconcileResult(
            removed = removed,
            remaining = staging.staleFiles(stagingDir).count(File::exists),
        )
    }
}
