package io.panelassistant.android.util

/**
 * Minimal JSON string encoding shared by the app's hand-built JSON. [esc] escapes the contents of a
 * JSON string — backslash, quote, the common `\n`/`\r`/`\t`, and any other control char as `\uXXXX`;
 * [str] wraps [esc] in quotes. A superset of the several ad-hoc escapers it replaces: strictly more
 * correct, since the simpler ones left newlines/tabs/control chars unescaped (which is invalid JSON).
 */
object Json {
    fun esc(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) when (c) {
            '\\' -> sb.append("\\\\")
            '"' -> sb.append("\\\"")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
        }
        return sb.toString()
    }

    fun str(s: String): String = "\"" + esc(s) + "\""
}
