package io.panelassistant.android.util

/**
 * Canonicalization and admission for a Home Assistant dashboard route.
 *
 * Two authorities have to agree about what a legal route looks like: the renderer, which resolves the
 * stored value against the dashboards the signed-in account can actually see, and the `home_dashboard`
 * setting's validator, which decides what the config API and both dashboard pickers will accept. The
 * rules live here once so a value a form accepts can never be one the renderer silently discards as
 * malformed. Whether the dashboard *exists* stays a runtime question — only well-formedness is decided
 * here, because the account's dashboard list is not knowable at validation time.
 */
object DashboardPath {

    /** Home Assistant's own `url_path` shape for a dashboard root. */
    private val ROOT_SEGMENT = Regex("^[a-z0-9][a-z0-9_-]*$")
    private val URL_SCHEME = Regex("^[a-z][a-z0-9+.-]*:", RegexOption.IGNORE_CASE)

    /** True when [value] is a legal Home Assistant dashboard `url_path` segment. */
    fun isRootSegment(value: String): Boolean = ROOT_SEGMENT.matches(value)

    /**
     * True when the route means "follow this Home Assistant account's default dashboard" — blank, or a
     * bare root carrying only a query/fragment. Such a value names no dashboard, so there is nothing to
     * validate and nothing the renderer needs admitted.
     */
    fun followsAccountDefault(raw: String): Boolean =
        raw.trim().substringBefore('?').substringBefore('#').trim('/').isBlank()

    /**
     * The canonical form of [raw], or null when it could never address a dashboard on the panel's own
     * Home Assistant. [preserveRoute] keeps an explicit view, query and fragment (`/alpha/view?k=1#m`);
     * false reduces the value to its dashboard root, which is how list membership is tested.
     */
    fun canonical(raw: String?, preserveRoute: Boolean): String? {
        val value = raw?.trim().orEmpty()
        if (value.isBlank() || value.startsWith("//") || '\\' in value || value.any(Char::isISOControl) ||
            URL_SCHEME.containsMatchIn(value)
        ) return null
        val routeEnd = listOf(value.indexOf('?'), value.indexOf('#')).filter { it >= 0 }.minOrNull() ?: value.length
        val route = value.substring(0, routeEnd).trim('/')
        val segments = route.split('/').filter(String::isNotBlank)
        val root = segments.firstOrNull()
        if (root == null || root == "null" || !ROOT_SEGMENT.matches(root)) return null
        if (preserveRoute && segments.drop(1).any { !safeSuffixSegment(it) }) return null
        return if (preserveRoute) "/$route${value.substring(routeEnd)}" else "/${segments.first()}"
    }

    /** The dashboard root [value] belongs to (`/alpha/view` → `/alpha`), or null when malformed. */
    fun root(value: String): String? = canonical(value, preserveRoute = false)

    /** Chromium canonicalizes percent-encoded dot/slash segments before navigation; reject them here. */
    private fun safeSuffixSegment(segment: String): Boolean {
        val decoded = StringBuilder(segment.length)
        var index = 0
        while (index < segment.length) {
            val char = if (segment[index] == '%') {
                if (index + 2 >= segment.length) return false
                val byte = segment.substring(index + 1, index + 3).toIntOrNull(16) ?: return false
                index += 3
                byte.toChar()
            } else {
                segment[index].also { index++ }
            }
            if (char == '/' || char == '\\' || char.isISOControl()) return false
            decoded.append(char)
        }
        return decoded.toString() !in setOf(".", "..")
    }
}
