package io.github.maxlyth.hapaneld.http

import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.util.AttributeKey
import java.util.Locale

/**
 * The presentation switch Panel Assistant sends when it proxies the web interface into its Home Assistant
 * sidebar (`X-Panel-Assistant-Embed`). It changes how pages render; the only guard that reads it is proximity
 * UI admission, which a LAN client can already pass with its own Origin, so sending it gains nothing. No
 * response header other than `Vary`, approval or persisted setting reads it. Its value is never logged.
 *
 * Grammar, version 1 (quoted literals match case-insensitively, as in RFC 5234):
 *
 * ```
 * embed = "v=1" *( ";" param )
 * param = "lang=" tag / "theme=" ( "light" / "dark" ) / "hide=" tab *( "," tab )
 * tag   = 1*8ALPHA *( "-" 1*8ALPHANUM )     ; at most 35 characters
 * ```
 *
 * A `hide` item must be token-shaped; a well-formed name that is not a tab is ignored. Anything else that
 * fails the grammar, a value over 256 bytes, or more than one header line makes the whole header invalid.
 * A repeated `lang` or `theme` takes its last value; repeated `hide` lists are combined.
 */
internal data class EmbedMode(
    val lang: String?,
    val theme: String?,
    val hiddenTabs: Set<String>,
) {
    companion object {
        const val HEADER = "X-Panel-Assistant-Embed"
        const val MAX_BYTES = 256
        val TABS = setOf("setup", "dashboard", "configure", "profiles", "entities", "install", "logs", "api")

        private val LANG_TAG = Regex("[A-Za-z]{1,8}(?:-[A-Za-z0-9]{1,8})*")
        private val TAB_TOKEN = Regex("[A-Za-z0-9_-]{1,32}")

        fun parse(values: List<String>?): EmbedMode? {
            val raw = values?.singleOrNull() ?: return null
            return parse(raw)
        }

        fun parse(raw: String?): EmbedMode? {
            if (raw == null || raw.toByteArray(Charsets.UTF_8).size > MAX_BYTES) return null
            val parts = raw.trim(' ', '\t').split(';')
            if (!parts[0].equals("v=1", ignoreCase = true)) return null
            var lang: String? = null
            var theme: String? = null
            val hidden = linkedSetOf<String>()
            for (part in parts.drop(1)) {
                val eq = part.indexOf('=')
                if (eq <= 0) return null
                val value = part.substring(eq + 1)
                when (part.substring(0, eq).lowercase(Locale.ROOT)) {
                    "lang" -> {
                        if (value.length > 35 || !LANG_TAG.matches(value)) return null
                        lang = value
                    }
                    "theme" -> theme = value.lowercase(Locale.ROOT).takeIf { it == "light" || it == "dark" } ?: return null
                    "hide" -> value.split(',').forEach { item ->
                        if (!TAB_TOKEN.matches(item)) return null
                        item.lowercase(Locale.ROOT).takeIf { it in TABS }?.let(hidden::add)
                    }
                    else -> return null
                }
            }
            return EmbedMode(lang, theme, hidden)
        }

        val ATTRIBUTE = AttributeKey<EmbedMode>("PanelAssistantEmbedMode")
    }
}

/**
 * Parse the request's embed switch once, at the top of the request intercept. A valid switch is kept for the
 * page builders and varies the response; an invalid or absent one leaves the request exactly as on the LAN.
 */
internal fun ApplicationCall.admitEmbedMode(): EmbedMode? {
    val embed = EmbedMode.parse(request.headers.getAll(EmbedMode.HEADER)) ?: return null
    attributes.put(EmbedMode.ATTRIBUTE, embed)
    response.headers.append(HttpHeaders.Vary, EmbedMode.HEADER)
    return embed
}

/** The request's parsed embed switch, set once by the request intercept; null outside embedded mode. */
internal fun ApplicationCall.embedMode(): EmbedMode? = attributes.getOrNull(EmbedMode.ATTRIBUTE)
