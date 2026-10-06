package io.panelassistant.android.i18n

import io.panelassistant.android.i18n.AppLocale.EARLY_ACCESS_LOCALES
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.security.MessageDigest

enum class TranslationState(val wireName: String) {
    ENGLISH_FALLBACK("english-fallback"),
    MACHINE_DRAFT("machine-draft"),
    MACHINE_CROSS_CHECKED("machine-cross-checked"),
    COMMUNITY_CORRECTED("community-corrected");

    companion object {
        fun fromWireName(value: String): TranslationState? = entries.firstOrNull { it.wireName == value }
    }
}

data class SourceString(
    val key: String,
    val text: String,
    val sourceHash: String,
    val placeholders: List<String>,
    val frozen: List<String>,
    val hardMaxChars: Int,
)

data class TargetString(
    val key: String,
    val text: String,
    val sourceHash: String,
    val state: TranslationState,
)

data class LocalizedText(
    val text: String,
    val language: String,
)

private val SOURCE_SURFACES = setOf(
    "settings", "shell", "dashboard", "configure", "setup", "profiles", "entities", "install", "logs",
    "fleet", "api",
)

/** Parsed, validated English source catalogue. */
class SourceCatalogue private constructor(
    val locale: String,
    val sourceRevision: String,
    val strings: Map<String, SourceString>,
) {
    fun text(key: String): String = strings[key]?.text ?: error("unknown i18n key: $key")

    companion object {
        const val SCHEMA = 1

        fun parse(json: String): SourceCatalogue {
            val root = JSONObject(json)
            requireExactKeys(root, setOf("schema", "locale", "sourceRevision", "strings"), "source root")
            require(root.getInt("schema") == SCHEMA) { "unsupported source catalogue schema" }
            require(root.getString("locale") == AppLocale.ENGLISH) { "source catalogue must be English" }
            val revision = root.getString("sourceRevision").also {
                require(it.matches(Regex("[0-9a-f]{40}"))) { "sourceRevision must be an exact Git SHA" }
            }
            val records = root.getJSONObject("strings")
            val parsed = linkedMapOf<String, SourceString>()
            records.keys().asSequence().sorted().forEach { key ->
                require(key.matches(KEY_PATTERN)) { "invalid i18n key: $key" }
                val record = records.getJSONObject(key)
                val required = setOf(
                    "text", "sourceHash", "surface", "context", "risk", "siblings",
                    "placeholders", "frozen", "softMaxChars", "hardMaxChars",
                )
                requireExactKeys(record, required, key)
                val text = record.getString("text")
                require(text.isNotEmpty()) { "$key has empty English text" }
                val hash = record.getString("sourceHash")
                require(hash == sourceHash(text)) { "$key sourceHash does not match its English text" }
                require(record.getString("surface") in SOURCE_SURFACES) { "$key has unsupported surface" }
                require(record.getString("context").isNotBlank()) { "$key has no context" }
                require(record.getString("risk") in setOf("ordinary", "setup", "consequential")) {
                    "$key has invalid risk"
                }
                val softMax = record.getInt("softMaxChars")
                val hardMax = record.getInt("hardMaxChars")
                require(softMax > 0 && hardMax >= softMax) { "$key has invalid layout budget" }
                val placeholders = stringArray(record, "placeholders")
                val frozen = stringArray(record, "frozen")
                require(placeholders == extractPlaceholders(text)) { "$key placeholder metadata is stale" }
                require(frozen == frozen.distinct()) { "$key repeats a frozen literal" }
                require(frozen.all { occurrenceCount(text, it) > 0 }) { "$key has a missing frozen literal" }
                stringArray(record, "siblings")
                parsed[key] = SourceString(key, text, hash, placeholders, frozen, hardMax)
            }
            require(parsed.isNotEmpty()) { "source catalogue is empty" }
            return SourceCatalogue(AppLocale.ENGLISH, revision, parsed)
        }
    }
}

/** Parsed target catalogue. Mechanical violations reject the whole file before resolution. */
class TargetCatalogue private constructor(
    val locale: String,
    val sourceRevision: String,
    val strings: Map<String, TargetString>,
) {
    companion object {
        const val SCHEMA = 1

        fun parse(json: String, source: SourceCatalogue): TargetCatalogue =
            parseForSupportedLocales(json, source, AppLocale.RELEASE_LOCALES)

        internal fun parseForSupportedLocales(
            json: String,
            source: SourceCatalogue,
            supportedLocales: Collection<String>,
        ): TargetCatalogue {
            val root = JSONObject(json)
            requireExactKeys(root, setOf("schema", "locale", "sourceRevision", "strings"), "target root")
            require(root.getInt("schema") == SCHEMA) { "unsupported target catalogue schema" }
            val locale = root.getString("locale")
            require(locale in supportedLocales - AppLocale.ENGLISH) { "unsupported target locale: $locale" }
            val revision = root.getString("sourceRevision").also {
                require(it.matches(Regex("[0-9a-f]{40}"))) { "target sourceRevision must be an exact Git SHA" }
            }
            val records = root.getJSONObject("strings")
            val parsed = linkedMapOf<String, TargetString>()
            records.keys().asSequence().sorted().forEach { key ->
                require(key.matches(KEY_PATTERN)) { "invalid target key: $key" }
                val sourceString = source.strings[key]
                val record = records.getJSONObject(key)
                requireExactKeys(record, setOf("text", "sourceHash", "state"), key)
                val text = record.getString("text")
                require(text.isNotEmpty()) { "$key has empty target text" }
                require(text.length <= MAX_STALE_TARGET_CHARS) { "$key target text is unreasonably large" }
                val hash = record.getString("sourceHash").also {
                    require(it.matches(SOURCE_HASH_PATTERN)) { "$key has an invalid source hash" }
                }
                val state = TranslationState.fromWireName(record.getString("state"))
                    ?: error("$key has invalid translation state")
                if (sourceString != null && hash == sourceString.sourceHash) {
                    require(state != TranslationState.ENGLISH_FALLBACK || text == sourceString.text) {
                        "$key English fallback does not equal its source"
                    }
                    require(multiset(extractPlaceholders(text)) == multiset(sourceString.placeholders)) {
                        "$key changed placeholders"
                    }
                    require(sourceString.frozen.all { token ->
                        occurrenceCount(text, token) == occurrenceCount(sourceString.text, token)
                    }) { "$key changed a frozen literal" }
                    require(text.length <= sourceString.hardMaxChars) { "$key exceeds its hard length budget" }
                }
                parsed[key] = TargetString(key, text, hash, state)
            }
            return TargetCatalogue(locale, revision, parsed)
        }
    }
}

/** Provider-neutral resolver. Unsafe, stale, missing and draft targets fall back per key to English. */
class Strings(
    private val source: SourceCatalogue,
    private val target: TargetCatalogue? = null,
    private val pseudo: Boolean = false,
) {
    /** Locale of the selected valid target catalogue, even when individual keys fall back to English. */
    val requestedLocale: String get() = when {
        pseudo -> AppLocale.PSEUDO
        target != null -> target.locale
        else -> AppLocale.ENGLISH
    }

    /** Effective locale of the original Settings proof surface; retained for schema compatibility. */
    val locale: String get() = when {
        pseudo -> AppLocale.PSEUDO
        target?.strings?.let { translated ->
            val earlyAccess = target.locale in EARLY_ACCESS_LOCALES
            source.strings
                .filterKeys { it.startsWith("settings.") }
                .all { (key, value) ->
                translated[key]?.let { candidate ->
                    candidate.sourceHash == value.sourceHash &&
                        (candidate.state == TranslationState.MACHINE_CROSS_CHECKED ||
                            candidate.state == TranslationState.COMMUNITY_CORRECTED ||
                            (earlyAccess && candidate.state == TranslationState.MACHINE_DRAFT))
                } == true
            }
        } == true -> target.locale
        else -> AppLocale.ENGLISH
    }

    val languages: List<String> get() = languages(emptySet())

    /** Languages actually emitted for the selected prefixes; an empty set means the whole catalogue. */
    fun languages(prefixes: Set<String>): List<String> {
        if (prefixes.isNotEmpty()) requireCataloguePrefixes(prefixes)
        return source.strings.keys
            .asSequence()
            .filter { key -> prefixes.isEmpty() || prefixes.any(key::startsWith) }
            .mapTo(linkedSetOf()) { resolve(it).language }
            .sorted()
    }

    fun get(key: String): String = resolve(key).text

    /** Resolve only keys owned by one of the supplied catalogue prefixes, in canonical key order. */
    fun resolved(prefixes: Set<String>): Map<String, LocalizedText> {
        if (prefixes.isEmpty()) return emptyMap()
        requireCataloguePrefixes(prefixes)
        return source.strings.keys
            .asSequence()
            .filter { key -> prefixes.any(key::startsWith) }
            .associateWithTo(linkedMapOf(), ::resolve)
    }

    fun resolve(key: String): LocalizedText {
        val english = source.text(key)
        if (pseudo) return LocalizedText(pseudoLocalize(english), AppLocale.PSEUDO)
        val candidate = target?.strings?.get(key)
            ?: return LocalizedText(english, AppLocale.ENGLISH)
        if (candidate.sourceHash != source.strings.getValue(key).sourceHash) {
            return LocalizedText(english, AppLocale.ENGLISH)
        }
        return when (candidate.state) {
            TranslationState.MACHINE_CROSS_CHECKED,
            TranslationState.COMMUNITY_CORRECTED,
            -> LocalizedText(candidate.text, target.locale)
            TranslationState.MACHINE_DRAFT,
            -> if (target.locale in EARLY_ACCESS_LOCALES) {
                LocalizedText(candidate.text, target.locale)
            } else {
                LocalizedText(english, AppLocale.ENGLISH)
            }
            TranslationState.ENGLISH_FALLBACK,
            -> LocalizedText(english, AppLocale.ENGLISH)
        }
    }
}

private fun requireCataloguePrefixes(prefixes: Set<String>) {
    require(prefixes.all { it.matches(Regex("[a-z0-9][a-z0-9._-]*\\.")) }) {
        "catalogue prefixes must be non-empty namespace prefixes ending in a dot"
    }
}

private const val MAX_STALE_TARGET_CHARS = 16_384

/** Asset-backed catalogue cache. A missing or malformed target rejects that locale to English. */
class CatalogueLoader(private val readAsset: (String) -> String) {
    private val source: SourceCatalogue by lazy { SourceCatalogue.parse(readAsset("i18n/en.json")) }
    private val targets = mutableMapOf<String, TargetCatalogue?>()

    /**
     * Parses the catalogue for the native surfaces' language off the calling thread, so the dashboard's
     * chips find it ready. The first parse takes seconds on a slow panel; run by a chip on the main thread
     * it raised "isn't responding" during update restarts (Shelly X2i, TPA10, 2026-10-06).
     */
    suspend fun prepareNative(uiLanguage: String?) {
        withContext(Dispatchers.IO) { strings(nativeLocale(uiLanguage)) }
    }

    @Synchronized
    fun strings(locale: String): Strings = when (locale) {
        AppLocale.PSEUDO -> Strings(source, pseudo = true)
        AppLocale.ENGLISH -> Strings(source)
        else -> {
            val target = if (locale in targets) targets[locale] else runCatching {
                TargetCatalogue.parse(readAsset("i18n/$locale.json"), source).also {
                    require(it.locale == locale) { "target locale does not match its asset name" }
                }
            }.getOrNull().also { targets[locale] = it }
            Strings(source, target)
        }
    }

    companion object {
        @Volatile private var shared: CatalogueLoader? = null

        /**
         * One loader over this app's own assets for the native chips, so the catalogue is parsed and
         * hashed once per process rather than on the main thread at every dashboard build (a TPA10 ANR
         * trace, 2026-10-06, caught the main thread in that parse).
         */
        fun assets(context: android.content.Context): CatalogueLoader = shared ?: synchronized(this) {
            shared ?: context.applicationContext.let { app ->
                CatalogueLoader { app.assets.open(it).bufferedReader().use { reader -> reader.readText() } }
            }.also { shared = it }
        }

        /** The locale of the panel's own native surfaces (dashboard chips, notices): one rule for every caller. */
        fun nativeLocale(uiLanguage: String?): String = AppLocale.resolve(
            explicit = null, persisted = uiLanguage, acceptLanguage = null,
            deviceLanguageTag = java.util.Locale.getDefault().toLanguageTag(),
            allowPseudo = io.panelassistant.android.BuildConfig.DEBUG,
        )
    }
}

// The catalogue parse validates every record on the dashboard's first build, so these are compiled once:
// a `String.format` per digest byte and two regex compilations per record kept the X2i's main thread
// busy for about 8 s on every update restart, long enough for an ANR (2026-10-06).
private val KEY_PATTERN = Regex("[a-z0-9][a-z0-9._-]*")
private val SOURCE_HASH_PATTERN = Regex("[0-9a-f]{64}")
private val PLACEHOLDER_PATTERN = Regex("%(?:\\d+\\$)?[a-zA-Z]|\\{[a-zA-Z_][a-zA-Z0-9_]*\\}")
private val HEX_DIGITS = "0123456789abcdef".toCharArray()

internal fun sourceHash(text: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
    val hex = CharArray(digest.size * 2)
    digest.forEachIndexed { index, byte ->
        hex[index * 2] = HEX_DIGITS[(byte.toInt() shr 4) and 0xf]
        hex[index * 2 + 1] = HEX_DIGITS[byte.toInt() and 0xf]
    }
    return String(hex)
}

internal fun extractPlaceholders(text: String): List<String> =
    PLACEHOLDER_PATTERN
        .findAll(text)
        .map { it.value }
        .toList()

private fun multiset(values: List<String>): Map<String, Int> = values.groupingBy { it }.eachCount()

private fun occurrenceCount(text: String, token: String): Int {
    require(token.isNotEmpty()) { "frozen literals must not be empty" }
    var count = 0
    var start = 0
    while (true) {
        val found = text.indexOf(token, start)
        if (found < 0) return count
        count++
        start = found + token.length
    }
}

private fun requireExactKeys(json: JSONObject, expected: Set<String>, owner: String) {
    val actual = json.keys().asSequence().toSet()
    require(actual == expected) { "$owner keys differ: expected=$expected actual=$actual" }
}

private fun stringArray(json: JSONObject, name: String): List<String> {
    val array = json.getJSONArray(name)
    return List(array.length()) { index ->
        array.getString(index).also { require(it.isNotEmpty()) { "$name contains an empty value" } }
    }
}

private fun pseudoLocalize(text: String): String {
    val accents = mapOf(
        'a' to 'à', 'A' to 'À', 'e' to 'ë', 'E' to 'Ë', 'i' to 'ï', 'I' to 'Ï',
        'o' to 'ô', 'O' to 'Ô', 'u' to 'ü', 'U' to 'Ü', 'y' to 'ÿ', 'Y' to 'Ÿ',
    )
    val protected = extractPlaceholders(text).toSet()
    val output = StringBuilder(text.length + 8)
    var index = 0
    while (index < text.length) {
        val placeholder = protected.firstOrNull { text.startsWith(it, index) }
        if (placeholder != null) {
            output.append(placeholder)
            index += placeholder.length
        } else {
            output.append(accents[text[index]] ?: text[index])
            index++
        }
    }
    return "［$output］"
}
