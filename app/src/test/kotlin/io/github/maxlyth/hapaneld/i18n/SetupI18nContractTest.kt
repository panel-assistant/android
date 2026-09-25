package io.github.maxlyth.hapaneld.i18n

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Contracts the bounded translated surface of the guided Setup wizard. */
class SetupI18nContractTest {
    private val assets = File("src/main/assets")
    private val setupJs = File(assets, "setup.js").readText()
    // Source-text reason: whole-file scans of PaneldServer.kt and setup.js for literal setup.* catalogue keys and their English fallbacks (translation catalogue contract).
    private val serverSource = File("src/main/kotlin/io/github/maxlyth/hapaneld/http/PaneldServer.kt").readText()
    private val sourceJson = JSONObject(File(assets, "i18n/en.json").readText())
    private val sourceRecords = sourceJson.getJSONObject("strings")
    private val sourceCatalogue = SourceCatalogue.parse(sourceJson.toString())
    private val releaseTargetLocales = AppLocale.RELEASE_LOCALES.filterNot { it == AppLocale.ENGLISH }

    @Test fun `Setup consumer keys exactly equal its source catalogue slice`() {
        val sourceKeys = sourceCatalogue.strings.keys.filterTo(sortedSetOf()) { it.startsWith("setup.") }
        val browserKeys = literalSetupKeys(setupJs)
        val frameKeys = literalSetupKeys(serverSource)
        val consumed = browserKeys + frameKeys

        assertEquals("the reviewed Setup source slice changed", 205, sourceKeys.size)
        assertEquals("the bounded browser consumer set changed", 202, browserKeys.size)
        assertEquals("the server frame must consume exactly its three keys", 3, frameKeys.size)
        assertEquals(
            "Every Setup key must have a literal consumer and every literal consumer must be catalogued",
            sourceKeys,
            consumed,
        )
        sourceKeys.forEach { key ->
            assertEquals("$key is assigned to the wrong surface", "setup", sourceRecords.getJSONObject(key).getString("surface"))
        }
    }

    @Test fun `Every Setup browser fallback exactly matches its authoritative English record`() {
        val frameKeys = literalSetupKeys(serverSource)
        val expectedBrowserKeys = sourceCatalogue.strings.keys
            .filterTo(sortedSetOf()) { it.startsWith("setup.") && it !in frameKeys }
        val bindings = mutableListOf<Pair<String, String>>()
        var tupleConsumerCount = 0

        jsCalls(setupJs, "i18nText").forEach { arguments ->
            require(arguments.size >= 2) { "i18nText call has no fallback: $arguments" }
            val keyExpression = arguments[0].trim()
            val fallbackExpression = arguments[1].trim()
            val literalKeys = literalSetupKeys(keyExpression)

            when {
                isJsString(keyExpression) -> {
                    val key = decodeJsString(keyExpression)
                    if (key.startsWith("setup.")) {
                        require(isJsString(fallbackExpression)) {
                            "$key must retain a literal, auditable English fallback"
                        }
                        bindings += key to decodeJsString(fallbackExpression)
                    }
                }
                keyExpression == "reasonCopy[0]" && fallbackExpression == "reasonCopy[1]" -> {
                    tupleConsumerCount++
                }
                literalKeys.isNotEmpty() -> {
                    require(keyExpression.contains("pluralCategory(")) {
                        "Unrecognised dynamic Setup key expression: $keyExpression"
                    }
                    require(
                        literalKeys.size == 2 &&
                            literalKeys.any { it.endsWith(".one") } &&
                            literalKeys.any { it.endsWith(".other") },
                    ) { "Plural Setup key expression must expose one and other branches: $keyExpression" }
                    require(isJsString(fallbackExpression)) {
                        "Plural Setup call must retain one literal English fallback: $keyExpression"
                    }
                    val fallback = decodeJsString(fallbackExpression)
                    literalKeys.forEach { key -> bindings += key to fallback }
                }
                keyExpression.contains("setup.") -> error("Unrecognised Setup i18n call: $arguments")
            }
        }

        val tupleBindings = Regex(
            "(?m)^\\s*[a-z_]+:\\s*\\[\\s*(\"setup\\.[a-z0-9._-]+\")\\s*,\\s*(\"(?:\\\\.|[^\"\\\\])*\")\\s*]",
        ).findAll(setupJs).map { match ->
            decodeJsString(match.groupValues[1]) to decodeJsString(match.groupValues[2])
        }.toList()
        assertEquals("Discovery's key/fallback tuples need exactly one deliberate dynamic consumer", 1, tupleConsumerCount)
        assertTrue("The finite discovery-reason tuple table must not disappear", tupleBindings.isNotEmpty())
        bindings += tupleBindings

        assertEquals(
            "Every browser Setup key must retain an audited authored-English fallback",
            expectedBrowserKeys,
            bindings.mapTo(sortedSetOf()) { it.first },
        )
        bindings.forEach { (key, fallback) ->
            assertEquals(
                "$key browser fallback drifted from i18n/en.json",
                checkNotNull(sourceCatalogue.strings[key]) { "English catalogue is missing $key" }.text,
                fallback,
            )
        }
    }

    @Test fun `Setup catalogue slice is current and promoted in every release locale`() {
        val setupKeys = sourceCatalogue.strings.keys.filter { it.startsWith("setup.") }
        assertTrue("Setup must own a non-empty catalogue slice", setupKeys.isNotEmpty())

        releaseTargetLocales.forEach { locale ->
            val target = TargetCatalogue.parse(File(assets, "i18n/$locale.json").readText(), sourceCatalogue)
            setupKeys.forEach { key ->
                val english = checkNotNull(sourceCatalogue.strings[key])
                val translated = checkNotNull(target.strings[key]) { "$locale is missing $key" }
                assertEquals("$locale has stale source text for $key", english.sourceHash, translated.sourceHash)
                assertTrue(
                    "$locale must promote $key beyond draft before release",
                    translated.state == TranslationState.MACHINE_CROSS_CHECKED ||
                        translated.state == TranslationState.COMMUNITY_CORRECTED ||
                        translated.state == TranslationState.ENGLISH_FALLBACK ||
                        (locale in AppLocale.EARLY_ACCESS_LOCALES &&
                            translated.state == TranslationState.MACHINE_DRAFT),
                )
                if (translated.state == TranslationState.ENGLISH_FALLBACK) {
                    assertEquals("$locale English fallback must equal the authoritative source for $key", english.text, translated.text)
                }
            }
        }
    }

    private fun literalSetupKeys(source: String): Set<String> =
        Regex("[\\\"'](setup(?:\\.[a-z0-9][a-z0-9_-]*)+)[\\\"']")
            .findAll(source)
            .mapTo(sortedSetOf()) { it.groupValues[1] }

    private fun jsCalls(source: String, function: String): List<List<String>> {
        val calls = mutableListOf<List<String>>()
        val marker = "$function("
        var cursor = 0
        while (true) {
            val markerStart = source.indexOf(marker, cursor)
            if (markerStart < 0) break
            cursor = markerStart + marker.length
            if (source.substring(maxOf(0, markerStart - 9), markerStart) == "function ") continue

            val arguments = mutableListOf<String>()
            var argumentStart = cursor
            var roundDepth = 0
            var squareDepth = 0
            var braceDepth = 0
            var quote: Char? = null
            var escaped = false
            var closed = false
            var index = cursor
            while (index < source.length) {
                val character = source[index]
                if (quote != null) {
                    when {
                        escaped -> escaped = false
                        character == '\\' -> escaped = true
                        character == quote -> quote = null
                    }
                } else {
                    when (character) {
                        '\'', '"' -> quote = character
                        '(' -> roundDepth++
                        ')' -> if (roundDepth == 0 && squareDepth == 0 && braceDepth == 0) {
                            arguments += source.substring(argumentStart, index).trim()
                            cursor = index + 1
                            closed = true
                            break
                        } else {
                            roundDepth--
                        }
                        '[' -> squareDepth++
                        ']' -> squareDepth--
                        '{' -> braceDepth++
                        '}' -> braceDepth--
                        ',' -> if (roundDepth == 0 && squareDepth == 0 && braceDepth == 0) {
                            arguments += source.substring(argumentStart, index).trim()
                            argumentStart = index + 1
                        }
                    }
                }
                index++
            }
            require(closed) { "unterminated $function call at byte $markerStart" }
            calls += arguments
        }
        return calls
    }

    private fun isJsString(expression: String): Boolean {
        val value = expression.trim()
        return value.length >= 2 && value.first() in setOf('\'', '"') && value.last() == value.first()
    }

    private fun decodeJsString(expression: String): String {
        val value = expression.trim()
        require(isJsString(value)) { "not a JavaScript string literal: $expression" }
        val result = StringBuilder()
        var index = 1
        while (index < value.lastIndex) {
            val character = value[index++]
            if (character != '\\') {
                result.append(character)
                continue
            }
            require(index < value.lastIndex) { "unterminated JavaScript escape: $expression" }
            when (val escaped = value[index++]) {
                '\\', '\'', '"', '/' -> result.append(escaped)
                'b' -> result.append('\b')
                'f' -> result.append('\u000c')
                'n' -> result.append('\n')
                'r' -> result.append('\r')
                't' -> result.append('\t')
                'u' -> {
                    require(index + 4 <= value.lastIndex) { "short Unicode escape: $expression" }
                    result.append(value.substring(index, index + 4).toInt(16).toChar())
                    index += 4
                }
                else -> error("unsupported JavaScript escape \\$escaped in $expression")
            }
        }
        return result.toString()
    }
}
