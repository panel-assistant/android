package io.panelassistant.android.http

import io.panelassistant.android.config.SettingSpec
import io.panelassistant.android.config.SettingType
import io.panelassistant.android.config.SettingsRegistry

/**
 * A display formatter bound to the one setting row it is allowed to format.
 *
 * `settingRowHtml` resolves a secret spec to `set`/`—` and a BOOL spec to `on`/`off` **before** it
 * consults a formatter, so a formatter attached to either kind is unreachable — it never runs, never
 * warns, and the row quietly shows the unformatted value instead. That is not hypothetical: the live
 * log-shipping status was routed through a formatter on the BOOL key `log_ship_enabled` and so reached
 * no page at all. Two panels rendered `Ship logs: on` while the status string was recomputed on every
 * snapshot and discarded, and an entire change rewording that string shipped nothing observable.
 *
 * Making the formatter a type rather than a bare lambda turns that mistake from silent into one that
 * cannot be expressed: [of] refuses a spec whose row can never reach a formatter, and [formatFor]
 * refuses a row other than the [key] the formatter was built for.
 *
 * The broader rule this encodes: a setting row states **configuration**. Live state does not belong on
 * one at all — render it as a fact row instead (see `CONTEXT_KEYS`, which formats nothing and is why
 * the log-shipping status now works).
 */
internal class SettingRowFormatter private constructor(
    val key: String,
    private val format: (String) -> String,
) {
    /**
     * Format [raw] for the row identified by [rowKey], rejecting a row this formatter was not built for.
     *
     * The row key is passed in rather than trusted, so the binding is enforced where it is used instead
     * of merely recorded on the instance. Callers already have both values in hand, and a mismatch is a
     * static coding mistake — hoisting a formatter out of a per-key loop is the realistic way to make it.
     */
    fun formatFor(rowKey: String, raw: String): String {
        require(rowKey == key) { "formatter for '$key' used on the '$rowKey' row" }
        return format(raw)
    }

    companion object {
        /** Whether `settingRowHtml` can reach a formatter for [spec] at all. */
        fun formattable(spec: SettingSpec): Boolean = !spec.secret && spec.type != SettingType.BOOL

        /**
         * Bind [format] to [key], rejecting a key whose row would ignore it.
         *
         * This throws rather than returning null on purpose: a null formatter is precisely the silent
         * no-op this type exists to prevent, so returning one would reintroduce the defect while
         * looking safer. The rejected condition is a static coding mistake — every call site passes a
         * literal key against a compiled-in registry — so it surfaces on any run of the unit suite
         * rather than on a panel.
         */
        fun of(key: String, format: (String) -> String): SettingRowFormatter {
            val spec = requireNotNull(SettingsRegistry.spec(key)) { "no registered setting named '$key'" }
            require(formattable(spec)) {
                val kind = if (spec.secret) "secret" else "BOOL"
                "setting '$key' is $kind, and settingRowHtml resolves those before it consults a " +
                    "formatter — the formatter would never run"
            }
            return SettingRowFormatter(key, format)
        }
    }
}
