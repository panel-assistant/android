package io.panelassistant.android.util

/** Narrow Android package/component grammar safe for direct interpolation into privileged commands. */
object AndroidInput {
    private val PACKAGE = Regex("^[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)*$")
    private val CLASS = Regex("^\\.?[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)*$")

    fun isPackage(value: String): Boolean = value.isNotEmpty() && PACKAGE.matches(value)

    fun isComponent(value: String): Boolean {
        val parts = value.split('/')
        return parts.size == 2 && isPackage(parts[0]) && CLASS.matches(parts[1])
    }

    /** Stored dashboard target: blank means auto-detect and `builtin` selects ha-paneld's renderer. */
    fun isDashboardTarget(value: String): Boolean =
        value.isEmpty() || value == "builtin" || isPackage(value)
}
