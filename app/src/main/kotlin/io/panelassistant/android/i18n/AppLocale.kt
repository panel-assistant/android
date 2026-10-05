package io.panelassistant.android.i18n

import java.util.Locale

/** Locale negotiation for the first translated product surface. */
object AppLocale {
    const val ENGLISH = "en"
    const val PSEUDO = "en-XA"
    internal const val UKRAINIAN = "uk"

    /** Release locales admitted for the Tier-A bootstrap. English is always the final fallback. */
    val RELEASE_LOCALES: List<String> = listOf(ENGLISH, "de", "fr", "it", "es", "zh-Hans", "nl", "pl", "uk")

    /**
     * Tier-B early-access locales: catalogue coverage is machine-draft only, not yet independently
     * reviewed to machine-cross-checked. [Strings.resolve] renders draft text for these locales so
     * real users see it now rather than English fallback; Tier-A keeps its stricter bar unchanged.
     * Move a locale out of this set (and nowhere else) once its catalogue passes independent review.
     */
    val EARLY_ACCESS_LOCALES: Set<String> = setOf("nl", "pl", "uk")

    /**
     * Resolve every admitted signal in the configured precedence order. Query/browser choice wins,
     * followed by a non-automatic panel setting, the connected Home Assistant user's language, the
     * browser language list, the Android process locale, and finally English. The debug pseudolocale
     * is deliberately opt-in and can never be selected through an inherited signal.
     */
    fun resolve(
        explicit: String?,
        persisted: String? = null,
        haUser: String? = null,
        acceptLanguage: String?,
        deviceLanguageTag: String?,
        allowPseudo: Boolean,
    ): String = resolveForSupportedLocales(
        explicit = explicit,
        persisted = persisted,
        haUser = haUser,
        acceptLanguage = acceptLanguage,
        deviceLanguageTag = deviceLanguageTag,
        allowPseudo = allowPseudo,
        supportedLocales = RELEASE_LOCALES,
    )

    /**
     * Resolve against an injected release set so a future locale can be verified before its catalogue
     * is shipped. Russian automatic signals select Ukrainian only after Ukrainian is actually in that
     * set. Explicit browser and panel choices continue to use ordinary supported-locale lookup.
     */
    internal fun resolveForSupportedLocales(
        explicit: String?,
        persisted: String? = null,
        haUser: String? = null,
        acceptLanguage: String?,
        deviceLanguageTag: String?,
        allowPseudo: Boolean,
        supportedLocales: Collection<String>,
    ): String {
        require(ENGLISH in supportedLocales) { "supported locales must include English" }
        canonicalForSupportedLocales(explicit, allowPseudo, supportedLocales)?.let { return it }
        persisted?.takeUnless { it.equals("auto", ignoreCase = true) }
            ?.let { canonicalForSupportedLocales(it, allowPseudo = false, supportedLocales) }
            ?.let { return it }
        canonicalAutomatic(haUser, supportedLocales)?.let { return it }
        parseAcceptLanguage(acceptLanguage).forEach { requested ->
            canonicalAutomatic(requested, supportedLocales)?.let { return it }
        }
        canonicalAutomatic(deviceLanguageTag, supportedLocales)?.let { return it }
        return ENGLISH
    }

    /** RFC-4647-style lookup over the locales currently implemented by the product. */
    fun canonical(raw: String?, allowPseudo: Boolean = false): String? =
        canonicalForSupportedLocales(raw, allowPseudo, RELEASE_LOCALES)

    private fun canonicalForSupportedLocales(
        raw: String?,
        allowPseudo: Boolean,
        supportedLocales: Collection<String>,
    ): String? {
        val tag = raw?.trim()?.replace('_', '-')?.takeIf { it.isNotEmpty() } ?: return null
        if (tag.length > 63 || !tag.matches(Regex("[A-Za-z0-9]{1,8}(?:-[A-Za-z0-9]{1,8})*"))) return null
        if (allowPseudo && tag.equals(PSEUDO, ignoreCase = true)) return PSEUDO
        val lower = tag.lowercase(Locale.ROOT)
        supportedLocales.forEach { releaseLocale ->
            val candidate = releaseLocale.lowercase(Locale.ROOT)
            // Language-only releases accept regional variants. Script-specific releases accept
            // variants of that script, but do not consume a different script with the same root.
            if (lower == candidate || lower.startsWith("$candidate-")) return releaseLocale
        }
        return when {
            lower == "zh" || lower == "zh-cn" || lower.startsWith("zh-cn-") ||
                lower == "zh-sg" || lower.startsWith("zh-sg-") ->
                "zh-Hans".takeIf { it in supportedLocales }
            else -> null
        }
    }

    /**
     * The release locale an automatic signal (Home Assistant user, browser or device language) selects,
     * including the automatic-only alias. Never use this for an explicit or persisted choice.
     */
    fun automatic(raw: String?): String? = canonicalAutomatic(raw, RELEASE_LOCALES)

    internal fun canonicalAutomatic(raw: String?, supportedLocales: Collection<String>): String? {
        automaticLocaleOverride(raw, supportedLocales)?.let { return it }
        return canonicalForSupportedLocales(raw, allowPseudo = false, supportedLocales)
    }

    /** A special automatic-only alias, dormant until its destination locale is a shipped locale. */
    internal fun automaticLocaleOverride(raw: String?, supportedLocales: Collection<String>): String? {
        val tag = raw?.trim()?.replace('_', '-')?.takeIf { it.isNotEmpty() } ?: return null
        if (tag.length > 63 || !tag.matches(Regex("[A-Za-z0-9]{1,8}(?:-[A-Za-z0-9]{1,8})*"))) return null
        val lower = tag.lowercase(Locale.ROOT)
        return UKRAINIAN.takeIf {
            it in supportedLocales && (lower == "ru" || lower.startsWith("ru-"))
        }
    }

    internal fun parseAcceptLanguage(header: String?): List<String> = header.orEmpty()
        .takeIf { it.length <= 1_024 }
        .orEmpty()
        .split(',')
        .take(16)
        .mapIndexedNotNull { index, part ->
            val pieces = part.trim().split(';')
            val tag = pieces.firstOrNull()?.trim().orEmpty()
            if (tag.isEmpty() || tag == "*") return@mapIndexedNotNull null
            val qualities = pieces.drop(1).mapNotNull { parameter ->
                val pair = parameter.trim().split('=', limit = 2)
                if (pair.size == 2 && pair[0].equals("q", ignoreCase = true)) {
                    pair[1]
                } else null
            }
            if (qualities.size > 1) return@mapIndexedNotNull null
            val quality = qualities.singleOrNull()?.toDoubleOrNull()
                ?: if (qualities.isEmpty()) 1.0 else return@mapIndexedNotNull null
            if (!quality.isFinite() || quality <= 0.0 || quality > 1.0) null else AcceptLanguage(tag, quality, index)
        }
        .sortedWith(compareByDescending<AcceptLanguage> { it.quality }.thenBy { it.index })
        .map { it.tag }

    private data class AcceptLanguage(val tag: String, val quality: Double, val index: Int)
}
