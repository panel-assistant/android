package io.panelassistant.android.platform

import java.io.File

/**
 * A deliberately smaller privilege boundary than root. Implementations may run only the typed
 * operations below; callers cannot smuggle an arbitrary shell command through this interface.
 */
interface ShellPrivilege {
    fun available(): Boolean
    fun uid(): Int?
    fun screenshot(): ByteArray?
    fun inputKey(keyCode: Int): Boolean
    fun tap(x: Int, y: Int): Boolean
    fun density(): String?
    fun setDensity(dpi: Int): Boolean
    fun resetDensity(): Boolean
    fun fontScale(): String?
    fun setFontScale(scale: Float): Boolean
    fun resetFontScale(): Boolean
    fun installApk(apk: File, allowDowngrade: Boolean, timeoutMs: Long): String?
}
