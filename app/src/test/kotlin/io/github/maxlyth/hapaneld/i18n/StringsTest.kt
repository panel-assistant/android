package io.github.maxlyth.hapaneld.i18n

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class StringsTest {
    private val english = """{
      "schema":1,
      "locale":"en",
      "sourceRevision":"e7c01506e9519d51b57fcf0e2b0b969a1ce44a6e",
      "strings":{
        "settings.example.help":{
          "text":"Keep {name} on MQTT.",
          "sourceHash":"${sourceHash("Keep {name} on MQTT.")}",
          "surface":"settings",
          "context":"Configure example help",
          "risk":"ordinary",
          "siblings":[],
          "placeholders":["{name}"],
          "frozen":["MQTT"],
          "softMaxChars":40,
          "hardMaxChars":80
        }
      }
    }""".trimIndent()

    private fun target(text: String, state: String, hash: String = sourceHash("Keep {name} on MQTT.")) = """{
      "schema":1,
      "locale":"de",
      "sourceRevision":"e7c01506e9519d51b57fcf0e2b0b969a1ce44a6e",
      "strings":{
        "settings.example.help":{
          "text":"$text",
          "sourceHash":"$hash",
          "state":"$state"
        }
      }
    }""".trimIndent()

    @Test fun `cross checked target resolves while draft falls back to English`() {
        val source = SourceCatalogue.parse(english)
        val checked = TargetCatalogue.parse(target("{name} auf MQTT behalten.", "machine-cross-checked"), source)
        val draft = TargetCatalogue.parse(target("{name} auf MQTT behalten.", "machine-draft"), source)
        assertEquals("{name} auf MQTT behalten.", Strings(source, checked).get("settings.example.help"))
        assertEquals("Keep {name} on MQTT.", Strings(source, draft).get("settings.example.help"))
    }

    @Test fun `missing target key falls back per key`() {
        val source = SourceCatalogue.parse(english)
        val emptyTarget = target("{name} auf MQTT behalten.", "machine-draft")
            .replace(Regex("\"settings\\.example\\.help\"\\s*:\\s*\\{.*?\\n\\s*}", RegexOption.DOT_MATCHES_ALL), "")
            .replace("\"strings\":{\n        \n      }", "\"strings\":{}")
        assertEquals("Keep {name} on MQTT.", Strings(source, TargetCatalogue.parse(emptyTarget, source)).get("settings.example.help"))
    }

    @Test fun `a future Ukrainian catalogue retains English fallback for a missing record`() {
        val source = SourceCatalogue.parse(english)
        val emptyUkrainian = target("{name} у MQTT.", "machine-cross-checked")
            .replace("\"locale\":\"de\"", "\"locale\":\"uk\"")
            .replace(Regex("\"settings\\.example\\.help\"\\s*:\\s*\\{.*?\\n\\s*}", RegexOption.DOT_MATCHES_ALL), "")
            .replace("\"strings\":{\n        \n      }", "\"strings\":{}")
        val target = TargetCatalogue.parseForSupportedLocales(
            emptyUkrainian,
            source,
            supportedLocales = AppLocale.RELEASE_LOCALES + AppLocale.UKRAINIAN,
        )

        val strings = Strings(source, target)
        assertEquals("uk", strings.requestedLocale)
        assertEquals(LocalizedText("Keep {name} on MQTT.", "en"), strings.resolve("settings.example.help"))
    }

    @Test fun `web surfaces share the validated catalogue and can be resolved by prefix`() {
        val settingText = "Settings label"
        val menuText = "Dashboard"
        val source = SourceCatalogue.parse("""{
          "schema":1,
          "locale":"en",
          "sourceRevision":"${"e".repeat(40)}",
          "strings":{
            "settings.example.label":{
              "text":"$settingText","sourceHash":"${sourceHash(settingText)}","surface":"settings",
              "context":"Setting label","risk":"ordinary","siblings":[],"placeholders":[],"frozen":[],
              "softMaxChars":20,"hardMaxChars":40
            },
            "shell.nav.dashboard":{
              "text":"$menuText","sourceHash":"${sourceHash(menuText)}","surface":"shell",
              "context":"Dashboard navigation tab","risk":"ordinary","siblings":[],"placeholders":[],"frozen":[],
              "softMaxChars":20,"hardMaxChars":40
            }
          }
        }""".trimIndent())
        val target = TargetCatalogue.parse("""{
          "schema":1,
          "locale":"de",
          "sourceRevision":"${"e".repeat(40)}",
          "strings":{
            "settings.example.label":{
              "text":"Einstellung","sourceHash":"${sourceHash(settingText)}","state":"machine-cross-checked"
            }
          }
        }""".trimIndent(), source)
        val strings = Strings(source, target)

        assertEquals("de", strings.requestedLocale)
        assertEquals("de", strings.locale)
        assertEquals(listOf("de"), strings.languages(setOf("settings.")))
        assertEquals(listOf("en"), strings.languages(setOf("shell.")))
        assertEquals(
            mapOf("shell.nav.dashboard" to LocalizedText("Dashboard", "en")),
            strings.resolved(setOf("shell.")),
        )
        assertThrows(IllegalArgumentException::class.java) { strings.resolved(setOf("")) }
    }

    @Test fun `Simplified Chinese request retains its locale while each HTML key falls back independently`() {
        val revision = "c".repeat(40)
        val dashboardEnglish = "Dashboard"
        val configureEnglish = "Save changes"
        val source = SourceCatalogue.parse("""{
          "schema":1,"locale":"en","sourceRevision":"$revision","strings":{
            "shell.nav.dashboard":{
              "text":"$dashboardEnglish","sourceHash":"${sourceHash(dashboardEnglish)}","surface":"shell",
              "context":"Dashboard tab","risk":"ordinary","siblings":[],"placeholders":[],"frozen":[],
              "softMaxChars":20,"hardMaxChars":40
            },
            "configure.save.action":{
              "text":"$configureEnglish","sourceHash":"${sourceHash(configureEnglish)}","surface":"configure",
              "context":"Save action","risk":"consequential","siblings":[],"placeholders":[],"frozen":[],
              "softMaxChars":20,"hardMaxChars":40
            }
          }
        }""".trimIndent())
        val target = TargetCatalogue.parse("""{
          "schema":1,"locale":"zh-Hans","sourceRevision":"$revision","strings":{
            "shell.nav.dashboard":{"text":"仪表板","sourceHash":"${sourceHash(dashboardEnglish)}","state":"machine-cross-checked"},
            "configure.save.action":{"text":"保存更改","sourceHash":"${sourceHash(configureEnglish)}","state":"machine-draft"}
          }
        }""".trimIndent(), source)
        val strings = Strings(source, target)

        assertEquals("zh-Hans", strings.requestedLocale)
        assertEquals(LocalizedText("仪表板", "zh-Hans"), strings.resolve("shell.nav.dashboard"))
        assertEquals(LocalizedText("Save changes", "en"), strings.resolve("configure.save.action"))
        assertEquals(listOf("en", "zh-Hans"), strings.languages)
    }

    @Test fun `unknown catalogue surface is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            SourceCatalogue.parse(english.replace("\"surface\":\"settings\"", "\"surface\":\"typo\""))
        }
    }

    @Test fun `page locale changes only when the complete Settings surface is promoted`() {
        // Source-text reason: loads the shipped i18n catalogues as input data.
        val source = SourceCatalogue.parse(File("src/main/assets/i18n/en.json").readText())
        val promotedJson = File("src/main/assets/i18n/de.json").readText()
        val draftJson = promotedJson.replace("\"state\": \"machine-cross-checked\"", "\"state\": \"machine-draft\"")
        val partial = TargetCatalogue.parse(
            draftJson.replaceFirst("\"state\": \"machine-draft\"", "\"state\": \"machine-cross-checked\""),
            source,
        )
        val complete = TargetCatalogue.parse(
            draftJson.replace("\"state\": \"machine-draft\"", "\"state\": \"machine-cross-checked\""),
            source,
        )
        assertEquals("en", Strings(source, partial).locale)
        assertEquals("de", Strings(source, complete).locale)
        assertEquals(listOf("de", "en"), Strings(source, partial).languages)
        assertEquals(
            "the complete catalogue reports its reviewed per-key English fallback",
            listOf("de", "en"),
            Strings(source, complete).languages,
        )
        assertEquals(listOf("de"), Strings(source, complete).languages(setOf("settings.")))
        assertEquals(listOf("de", "en"), Strings(source, complete).languages(setOf("profiles.")))
        assertEquals(
            "the real German catalogue now promotes the complete HTML shell",
            listOf("de"),
            Strings(source, complete).languages(setOf("shell.")),
        )
        val promotedKey = partial.strings.values.single { it.state == TranslationState.MACHINE_CROSS_CHECKED }.key
        val fallbackKey = source.strings.keys.first { it.startsWith("settings.") && it != promotedKey }
        assertEquals("de", Strings(source, partial).resolve(promotedKey).language)
        assertEquals("en", Strings(source, partial).resolve(fallbackKey).language)
    }

    @Test fun `current mechanical violations reject while stale records fall back per key`() {
        val source = SourceCatalogue.parse(english)
        assertThrows(IllegalArgumentException::class.java) {
            TargetCatalogue.parse(target("Ohne Platzhalter auf MQTT.", "machine-cross-checked"), source)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TargetCatalogue.parse(target("{name} im Broker behalten.", "machine-cross-checked"), source)
        }
        val stale = TargetCatalogue.parse(
            target("Alte Übersetzung ohne aktuelle Struktur.", "machine-cross-checked", "0".repeat(64))
                .replace(source.sourceRevision, "f".repeat(40)),
            source,
        )
        assertEquals("Keep {name} on MQTT.", Strings(source, stale).get("settings.example.help"))
        assertEquals("en", Strings(source, stale).locale)
        assertThrows(IllegalArgumentException::class.java) {
            TargetCatalogue.parse(target("{name} auf MQTT MQTT behalten.", "machine-cross-checked"), source)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TargetCatalogue.parse(target("{name} auf MQTT behalten.", "english-fallback"), source)
        }
    }

    @Test fun `pseudolocale expands prose without changing placeholders`() {
        val strings = Strings(SourceCatalogue.parse(english), pseudo = true)
        assertEquals("［Këëp {name} ôn MQTT.］", strings.get("settings.example.help"))
    }

    @Test fun `asset loader rejects a malformed or missing target catalogue to English`() {
        val malformed = CatalogueLoader { path -> if (path == "i18n/en.json") english else "not-json" }
        val missing = CatalogueLoader { path ->
            if (path == "i18n/en.json") english else error("missing")
        }
        assertEquals("Keep {name} on MQTT.", malformed.strings("de").get("settings.example.help"))
        assertEquals("Keep {name} on MQTT.", missing.strings("de").get("settings.example.help"))
    }

    @Test fun `asset loader rejects a target whose declared locale differs from its path`() {
        val wrongLocale = CatalogueLoader { path ->
            if (path == "i18n/en.json") english else target("{name} auf MQTT behalten.", "machine-cross-checked")
                .replace("\"locale\":\"de\"", "\"locale\":\"fr\"")
        }
        assertEquals("Keep {name} on MQTT.", wrongLocale.strings("de").get("settings.example.help"))
        assertEquals("en", wrongLocale.strings("de").locale)
    }
}
