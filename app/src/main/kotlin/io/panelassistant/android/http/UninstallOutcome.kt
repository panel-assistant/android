package io.panelassistant.android.http

/** `null` means the root probe failed, not that the package path was absent. */
internal fun uninstallSucceeded(uninstallOutput: String?, packagePathOutput: String?): Boolean =
    uninstallOutput?.contains("Success", ignoreCase = true) == true ||
        (packagePathOutput != null && packagePathOutput.trim().isEmpty())
