package io.panelassistant.android.i18n

/**
 * Text kept as a catalogue key and its placeholder values, rendered in whichever language the reader
 * needs, so one en.json entry is the only English. A value is literal text (a command, a count, a
 * helper error) or another [CatalogueText].
 */
internal class CatalogueText(
    val key: String,
    private vararg val values: Pair<String, Any>,
) {
    fun render(strings: Strings): String =
        values.fold(strings.get(key)) { text, (name, value) ->
            text.replace("{$name}", if (value is CatalogueText) value.render(strings) else value.toString())
        }
}
