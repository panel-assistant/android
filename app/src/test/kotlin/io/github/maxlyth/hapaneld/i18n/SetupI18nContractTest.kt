package io.github.maxlyth.hapaneld.i18n

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Contracts the bounded translated surface of the guided Setup wizard. */
class SetupI18nContractTest {
    private val assets = File("src/main/assets")
    private val setupJs = File(assets, "setup.js").readText()
    private val serverSource = File("src/main/kotlin/io/github/maxlyth/hapaneld/http/PaneldServer.kt").readText()
    private val sourceJson = JSONObject(File(assets, "i18n/en.json").readText())
    private val sourceRecords = sourceJson.getJSONObject("strings")
    private val sourceCatalogue = SourceCatalogue.parse(sourceJson.toString())
    private val releaseTargetLocales = AppLocale.RELEASE_LOCALES.filterNot { it == AppLocale.ENGLISH }

    @Test fun `Setup consumer keys exactly equal its source catalogue slice`() {
        val sourceKeys = sourceCatalogue.strings.keys.filterTo(sortedSetOf()) { it.startsWith("setup.") }
        val browserKeys = literalSetupKeys(setupJs)
        val frameKeys = literalSetupKeys(functionBody("setupBody"))
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
        val frameKeys = literalSetupKeys(functionBody("setupBody"))
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

    @Test fun `Setup route reports its effective languages and projects the complete browser slice`() {
        val route = routeBody("setup")
        assertTrue(route.contains("HttpHeaders.AcceptLanguage"))
        assertTrue(route.contains("HttpHeaders.ContentLanguage"))
        assertTrue(
            Regex("strings\\.languages\\(setOf\\(\\s*\"shell\\.\"\\s*,\\s*\"setup\\.\"\\s*\\)\\)")
                .containsMatchIn(route),
        )
        assertFalse("Setup must not report unconditional English after full promotion", route.contains("+ AppLocale.ENGLISH"))
        assertTrue(route.contains("translationPrefixes = setOf(\"shell.\", \"setup.\", \"runtime.\")"))
        assertTrue(route.contains("setupBody(strings, preserveExplicitEnglish, embedded = call.embedMode() != null)"))
    }

    @Test fun `Setup server frame retains exact catalogue and localized link mappings`() {
        val body = functionBody("setupBody")
        assertTrue(body.contains("aria-label=\"${'$'}{esc(strings.get(\"setup.frame.progress_label\"))}\""))
        assertTrue(body.contains("${'$'}{esc(strings.get(\"setup.frame.loading\"))}"))
        assertTrue(body.contains("${'$'}{setupHref(\"configure\", strings, preserveExplicitEnglish)}"))
        assertTrue(body.contains("${'$'}{esc(strings.get(\"setup.frame.skip_exit\"))}"))
        listOf("Setup progress", "Loading setup", "Skip and exit the wizard").forEach { formerEnglish ->
            assertFalse("setupBody still hard-codes visible English: $formerEnglish", body.contains(formerEnglish))
        }
    }

    @Test fun `Only Setup preserves an explicit English query across server-rendered links`() {
        val route = routeBody("setup")
        assertTrue(route.contains("call.request.queryParameters[\"lang\"]"))
        assertTrue(route.contains("== AppLocale.ENGLISH"))
        assertTrue(route.contains("setupBody(strings, preserveExplicitEnglish, embedded = call.embedMode() != null)"))
        assertTrue(route.contains("preserveExplicitEnglish = preserveExplicitEnglish"))

        val href = functionBody("setupHref")
        assertTrue(href.contains("strings.requestedLocale != AppLocale.ENGLISH"))
        assertTrue(href.contains("return localizedHref(path, strings)"))
        assertTrue(href.contains("lang=${'$'}{esc(AppLocale.ENGLISH)}${'$'}fragment"))
        assertTrue("explicit English must remain ahead of a URL fragment", href.indexOf("lang=") < href.indexOf("${'$'}fragment"))

        val pageShellHeader = serverSource.substring(
            serverSource.indexOf("private fun pageShell("),
            serverSource.indexOf("): String {", serverSource.indexOf("private fun pageShell(")),
        )
        assertTrue(pageShellHeader.contains("preserveExplicitEnglish: Boolean = false"))
        assertEquals(
            "no other route may opt into Setup's explicit-English URL persistence",
            1,
            Regex("preserveExplicitEnglish\\s*=\\s*preserveExplicitEnglish").findAll(serverSource).count(),
        )
    }

    @Test fun `Unfinished-Setup redirects retain only admitted locale signals in precedence order`() {
        // The Location value itself is behavior-tested in UnfinishedSetupLocationTest; this pins the wiring.
        val redirect = functionBody("setupRedirectLocation")
        assertTrue(redirect.contains("unfinishedSetupLocation("))
        assertTrue(redirect.contains("lang = call.request.queryParameters[\"lang\"]"))
        assertTrue(redirect.contains("haLang = call.request.queryParameters[\"ha_lang\"]"))
        assertTrue("debug builds may admit the pseudo locale for explicit lang", redirect.contains("allowPseudo = BuildConfig.DEBUG"))
        assertEquals(
            "raw locale values must be read once and handed to the canonicalizing builder",
            2,
            Regex("queryParameters\\[").findAll(redirect).count(),
        )

        val interceptor = serverSource.substring(
            serverSource.indexOf("call.request.uri.substringBefore('?') in WIZARD_REDIRECT_PAGES"),
            serverSource.indexOf("// CSRF guard:", serverSource.indexOf("call.request.uri.substringBefore('?') in WIZARD_REDIRECT_PAGES")),
        )
        assertTrue(interceptor.contains("call.respondRedirect(setupRedirectLocation(call))"))
        assertFalse("the interceptor must not rebuild or reflect the raw query", interceptor.contains("queryParameters["))
    }

    @Test fun `Setup-authored navigation retains the requested locale`() {
        val helper = jsFunction("internalHref")
        assertTrue(helper.contains("var params = new URLSearchParams(location.search)"))
        assertTrue(helper.contains("params.has(\"lang\")"))
        assertTrue(helper.contains("params.has(\"ha_lang\")"))
        assertTrue(helper.contains("var lang = requestedLocale()"))
        assertTrue(helper.contains("supported.indexOf(lang) === -1"))
        assertTrue(helper.contains("url.searchParams.set(\"lang\", lang)"))
        assertTrue(helper.contains("url.origin !== location.origin"))

        setOf("./", "configure", "configure#cfg-dashboard_package", "install").forEach { path ->
            assertTrue(
                "Setup-authored route $path must preserve the selected language",
                setupJs.contains("internalHref(\"$path\")"),
            )
        }
        assertFalse(
            "Setup must not retain a direct internal href that drops lang",
            Regex("href:\\s*\"/(?!api/)").containsMatchIn(setupJs),
        )
    }

    @Test fun `Discovery renders its finite reason token and never the server English explanation`() {
        val discovery = jsFunction("discoveryNote")
        assertTrue(discovery.contains("d.reason"))
        assertTrue(discovery.contains("setup.discovery.unavailable."))
        assertFalse("server-authored English is not opaque evidence", Regex("\\bd\\.explanation\\b").containsMatchIn(discovery))
        assertTrue(
            "the discovered address must remain an opaque placeholder value",
            discovery.contains("i18nText(\"setup.discovery.found\"") && discovery.contains("{ value: discovery[forField] }"),
        )
    }

    @Test fun `Only an explicit approval-required 202 enters the approval path`() {
        val post = jsFunction("postFormAttempt")
        assertTrue(
            "generic 202 responses may be accepted work and must not be called approval",
            Regex("r\\.status === 202\\s*&&\\s*body\\.error === [\"']approval-required[\"']")
                .containsMatchIn(post),
        )
        assertTrue("server error evidence must remain verbatim", post.contains("body.error || body.message"))
        assertTrue("server approval guidance must remain verbatim", post.contains("body.message || i18nText("))
    }

    @Test fun `WebView accepted busy is distinct from an installation start`() {
        val webView = jsFunction("webViewCard")
        val admission = webView.indexOf("body.status !== \"started\"")
        val installing = webView.indexOf("i18nText(\"setup.webview.state.installing\"")
        assertTrue("only an explicit started result may claim installation", admission >= 0 && admission < installing)
        val notStarted = webView.substring(admission, installing)
        assertTrue("a busy/not-started result must re-enable the action", notStarted.contains("e.target.disabled = false"))
        assertTrue("a busy/not-started result must refresh actual journey state", notStarted.contains("refresh()"))
    }

    @Test fun `Opaque values cross translation only as named placeholders`() {
        val requiredBindings = mapOf(
            "setup.discovery.found" to "value",
            "setup.mqtt.error.unresolvable" to "host",
            "setup.mqtt.error.nothing_listening" to "port",
            "setup.renderer.failure.package_missing" to "package",
            "setup.dashboard.path.unknown" to "root",
        )
        requiredBindings.forEach { (key, value) ->
            val call = i18nCall(key)
            assertTrue("$key must bind opaque $value as a named value", Regex("\\b$value\\s*:").containsMatchIn(call))
        }
        assertTrue("raw API errors remain direct evidence", setupJs.contains("stepErr(e.message)"))
        assertFalse(
            "server discovery explanation is translatable prose, not opaque evidence",
            Regex("\\bd\\.explanation\\b").containsMatchIn(setupJs),
        )
    }

    @Test fun `Translated copy is painted with textContent and never translated innerHTML`() {
        val elementBuilder = jsFunction("el")
        assertTrue(elementBuilder.contains("if (k === \"text\") n.textContent = attrs[k]"))
        assertFalse("the generic element builder must not admit translated HTML", elementBuilder.contains("innerHTML"))
        assertFalse(
            "no translated return value may flow into innerHTML",
            Regex("innerHTML\\s*=\\s*i18nText\\(").containsMatchIn(setupJs),
        )
        val identityPaint = setupJs.substring(setupJs.indexOf("function paint(v)"), setupJs.indexOf("paint(id);"))
        assertTrue(identityPaint.contains("preview.textContent = \"\""))
        assertTrue(identityPaint.contains("document.createTextNode(i18nText(\"setup.identity.preview_intro\""))
        assertTrue(identityPaint.contains("el(\"b\", { text: entityId })"))
        assertFalse(identityPaint.contains("innerHTML"))
    }

    private fun literalSetupKeys(source: String): Set<String> =
        Regex("[\\\"'](setup(?:\\.[a-z0-9][a-z0-9_-]*)+)[\\\"']")
            .findAll(source)
            .mapTo(sortedSetOf()) { it.groupValues[1] }

    private fun functionBody(name: String): String {
        val start = serverSource.indexOf("fun $name(").also { require(it >= 0) { "missing function $name" } }
        val next = serverSource.indexOf("\n    private fun ", start + 1).takeIf { it >= 0 } ?: serverSource.length
        return serverSource.substring(start, next)
    }

    private fun routeBody(path: String): String {
        val marker = "get(\"/$path\")"
        val start = serverSource.indexOf(marker).also { require(it >= 0) { "missing /$path route" } }
        val next = serverSource.indexOf("\n                get(\"/", start + marker.length)
            .takeIf { it >= 0 } ?: serverSource.length
        return serverSource.substring(start, next)
    }

    private fun jsFunction(name: String): String {
        val start = setupJs.indexOf("function $name(").also { require(it >= 0) { "missing JS function $name" } }
        val next = setupJs.indexOf("\n  function ", start + 1).takeIf { it >= 0 } ?: setupJs.length
        return setupJs.substring(start, next)
    }

    private fun i18nCall(key: String): String {
        val start = setupJs.indexOf("i18nText(\"$key\"").also { require(it >= 0) { "missing consumer for $key" } }
        return setupJs.substring(start, minOf(setupJs.length, start + 700))
    }

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
