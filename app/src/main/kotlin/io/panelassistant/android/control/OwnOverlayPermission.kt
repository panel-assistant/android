package io.panelassistant.android.control

import android.util.Log
import io.panelassistant.android.platform.RootShell

/**
 * The app's own "Display over other apps" (`SYSTEM_ALERT_WINDOW`), granted through root only while
 * Android shows no decision for it.
 *
 * Panels have no Settings flow for the permission, so on a rooted panel the overlay features grant it
 * with `appops set`. An owner or a device policy may have turned it off, though, and writing `allow`
 * then would turn their choice back on at every start. So the mode is read first, and `allow` is
 * written only when the op is proven unset: a non-empty answer whose every line is
 * `SYSTEM_ALERT_WINDOW: default` (optionally followed by `; …`) or `No operations.`. Android 14's
 * two-line answer for an unset op, `No operations.\nDefault mode: <mode>`, is folded the way Panel
 * Assistant's app-op loop and the helper's `pa_op_read` fold it: to `allow` when that default already
 * allows and to `default` otherwise, since nobody chose it for this app. Deny, ignore, foreground,
 * errored, anything unrecognised and an unreadable answer are all kept, and the caller reports that it
 * cannot draw the overlay, as it does when root is unavailable.
 */
internal object OwnOverlayPermission {
    const val OP = "SYSTEM_ALERT_WINDOW"
    private const val NO_OPERATIONS = "No operations."
    private val MODES = listOf("allow", "default", "deny", "ignore", "foreground", "errored")
    private val UNSET_LINE = Regex("""$OP: default(; [ -~]+)?""")

    /** True when the overlay can be drawn, granting it first only when the op is proven unset. */
    fun ensure(pkg: String, root: RootShell, canDraw: () -> Boolean, tag: String): Boolean {
        if (canDraw()) return true
        val answer = root.runOutput("appops get $pkg $OP 2>/dev/null")
        if (!undecided(answer)) {
            val seen = answer?.let(::fold)?.replace('\n', ' ')?.take(MAX_LOGGED) ?: "unreadable"
            Log.w(tag, "$OP is not unset ($seen); keeping that choice, so the overlay is not drawn")
            return false
        }
        root.run("appops set $pkg $OP allow")
        return canDraw()
    }

    /** Whether an `appops get` answer proves nobody has set the op for this app. */
    fun undecided(answer: String?): Boolean {
        val text = answer?.let(::fold) ?: return false
        if (text.isEmpty()) return false
        return text.split('\n').all { it == NO_OPERATIONS || UNSET_LINE.matches(it) }
    }

    /** Trailing newlines dropped and Android 14's two-line unset answer folded to one `<op>: <mode>` line. */
    fun fold(answer: String): String {
        val text = answer.replace("\r\n", "\n").trimEnd('\n')
        val unsetDefault = MODES.firstOrNull { text == "$NO_OPERATIONS\nDefault mode: $it" } ?: return text
        return "$OP: " + if (unsetDefault == "allow") "allow" else "default"
    }

    private const val MAX_LOGGED = 160
}
