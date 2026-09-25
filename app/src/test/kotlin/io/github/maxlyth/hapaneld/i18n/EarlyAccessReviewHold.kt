package io.github.maxlyth.hapaneld.i18n

import java.io.File
import org.json.JSONObject

/**
 * Mirrors `held_for_review()` in scripts/i18n_catalogue.py: an early-access locale shows a consequential
 * string in English until it has been reviewed, so its record is an exact `english-fallback` of the source.
 */
internal object EarlyAccessReviewHold {
    private val source = JSONObject(File("src/main/assets/i18n/en.json").readText()).getJSONObject("strings")

    fun holds(locale: String, key: String, translated: TargetString): Boolean =
        translated.state == TranslationState.ENGLISH_FALLBACK && holdsText(locale, key, translated.text)

    fun holdsText(locale: String, key: String, text: String): Boolean {
        val record = source.getJSONObject(key)
        return locale in AppLocale.EARLY_ACCESS_LOCALES &&
            record.getString("risk") == "consequential" &&
            text == record.getString("text")
    }
}
