package io.panelassistant.android.i18n

private val NAMED_PLACEHOLDER = Regex("\\{([A-Za-z][A-Za-z0-9_]*)\\}")

/** Substitute original template tokens once; inserted values and unknown tokens remain literal. */
internal fun formatNamedTemplate(template: String, values: Map<String, String>): String =
    NAMED_PLACEHOLDER.replace(template) { match -> values[match.groupValues[1]] ?: match.value }
