package io.panelassistant.android.util

/**
 * Reads Android system properties (`ro.*`, etc.) via the hidden `android.os.SystemProperties` API by
 * reflection — no Context needed, so it's usable from Build-only code paths (e.g. device detection).
 * Returns "" on any failure (missing key, API absent, SecurityException). Single home for what were
 * several copy-pasted reflection blocks across the app.
 */
object SystemProps {
    fun get(key: String): String = runCatching {
        @Suppress("PrivateApi")
        val m = Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
        (m.invoke(null, key) as? String).orEmpty()
    }.getOrDefault("")
}
