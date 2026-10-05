package io.panelassistant.android.http

import io.panelassistant.android.i18n.CatalogueLoader
import io.panelassistant.android.i18n.Strings
import io.panelassistant.android.i18n.sourceHash
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.json.JSONObject

class ConfigReadRoutesTest {
    @Test fun `dynamic config reads are never cached and only localized schema varies by language`() = testApplication {
        var configRevision = 0
        application {
            routing {
                route("/api/v1") {
                    configReadRoutes(
                        currentConfigJson = { "{\"revision\":${++configRevision}}" },
                        localizedSchema = { call ->
                            val language = call.request.headers[HttpHeaders.AcceptLanguage] ?: "en"
                            LocalizedConfigSchema("{\"language\":\"$language\"}", listOf(language))
                        },
                    )
                }
            }
        }

        val firstConfig = client.get("/api/v1/config")
        val secondConfig = client.get("/api/v1/config")
        assertEquals("no-store", firstConfig.headers[HttpHeaders.CacheControl])
        assertNull(firstConfig.headers[HttpHeaders.Vary])
        assertEquals("{\"revision\":1}", firstConfig.bodyAsText())
        assertEquals("{\"revision\":2}", secondConfig.bodyAsText())

        val schema = client.get("/api/v1/config/schema") {
            header(HttpHeaders.AcceptLanguage, "de")
        }
        assertEquals("no-store", schema.headers[HttpHeaders.CacheControl])
        assertEquals(HttpHeaders.AcceptLanguage, schema.headers[HttpHeaders.Vary])
        assertEquals("de", schema.headers[HttpHeaders.ContentLanguage])
        assertEquals("{\"language\":\"de\"}", schema.bodyAsText())
    }

    @Test fun `schema endpoint applies production locale precedence and reports emitted languages`() = testApplication {
        var persisted = "de"
        var device = "zh-CN"
        val loader = syntheticCatalogueLoader()
        application {
            routing {
                route("/api/v1") {
                    configReadRoutes(
                        currentConfigJson = { "{}" },
                        localizedSchema = { call ->
                            localizedConfigSchema(
                                call = call,
                                persistedLanguage = persisted,
                                deviceLanguageTag = device,
                                allowPseudo = false,
                                catalogueLoader = loader,
                                render = ::renderSyntheticSchema,
                            )
                        },
                    )
                }
            }
        }

        suspend fun assertSchema(
            path: String,
            acceptLanguage: String?,
            expectedText: String,
            expectedLanguages: String,
        ) {
            val response = client.get(path) {
                acceptLanguage?.let { header(HttpHeaders.AcceptLanguage, it) }
            }
            assertEquals(expectedLanguages, response.headers[HttpHeaders.ContentLanguage])
            val body = JSONObject(response.bodyAsText())
            assertEquals(expectedText, body.getString("checked"))
            assertEquals(expectedText.substringBefore(':'), body.getString("checkedLanguage"))
            assertEquals(expectedText.substringBefore(':'), body.getString("requestedLocale"))
            assertEquals("English fallback", body.getString("draft"))
            assertEquals("en", body.getString("draftLanguage"))
        }

        // Explicit browser choice wins every inherited signal.
        assertSchema("/api/v1/config/schema?lang=fr&ha_lang=it", "es", "fr:Checked", "en, fr")
        assertSchema("/api/v1/config/schema?lang=zh-Hans&ha_lang=it", "es", "zh-Hans:Checked", "en, zh-Hans")
        // A persisted non-auto panel choice wins HA, browser, and device signals.
        assertSchema("/api/v1/config/schema?ha_lang=it", "es", "de:Checked", "de, en")
        // Automatic persistence admits the connected HA user's language.
        persisted = "auto"
        assertSchema("/api/v1/config/schema?ha_lang=it", "es", "it:Checked", "en, it")
        // Without an HA choice, Accept-Language wins the Android/device locale.
        assertSchema("/api/v1/config/schema", "sv, es-MX;q=.8", "es:Checked", "en, es")
        // Unsupported inherited languages fall through to the Android/device locale.
        assertSchema("/api/v1/config/schema", "sv", "zh-Hans:Checked", "en, zh-Hans")
        // English is the final fallback when every signal is unsupported.
        device = "ja-JP"
        assertSchema("/api/v1/config/schema?ha_lang=sv", "ar", "en:Checked", "en")
    }

    private fun renderSyntheticSchema(strings: Strings): String {
        val checked = strings.resolve("settings.checked.label")
        val draft = strings.resolve("settings.draft.help")
        return JSONObject()
            .put("checked", checked.text)
            .put("checkedLanguage", checked.language)
            .put("requestedLocale", strings.requestedLocale)
            .put("draft", draft.text)
            .put("draftLanguage", draft.language)
            .toString()
    }

    private fun syntheticCatalogueLoader(): CatalogueLoader {
        val revision = "a".repeat(40)
        val checkedEnglish = "en:Checked"
        val draftEnglish = "English fallback"
        val source = """{
          "schema":1,
          "locale":"en",
          "sourceRevision":"$revision",
          "strings":{
            "settings.checked.label":{
              "text":"$checkedEnglish","sourceHash":"${sourceHash(checkedEnglish)}",
              "surface":"settings","context":"Checked label","risk":"ordinary",
              "siblings":[],"placeholders":[],"frozen":[],"softMaxChars":40,"hardMaxChars":80
            },
            "settings.draft.help":{
              "text":"$draftEnglish","sourceHash":"${sourceHash(draftEnglish)}",
              "surface":"settings","context":"Draft help","risk":"ordinary",
              "siblings":[],"placeholders":[],"frozen":[],"softMaxChars":40,"hardMaxChars":80
            }
          }
        }""".trimIndent()
        return CatalogueLoader { path ->
            if (path == "i18n/en.json") source else {
                val locale = path.removePrefix("i18n/").removeSuffix(".json")
                """{
                  "schema":1,
                  "locale":"$locale",
                  "sourceRevision":"$revision",
                  "strings":{
                    "settings.checked.label":{
                      "text":"$locale:Checked","sourceHash":"${sourceHash(checkedEnglish)}",
                      "state":"machine-cross-checked"
                    },
                    "settings.draft.help":{
                      "text":"$locale:Draft","sourceHash":"${sourceHash(draftEnglish)}",
                      "state":"machine-draft"
                    }
                  }
                }""".trimIndent()
            }
        }
    }
}
