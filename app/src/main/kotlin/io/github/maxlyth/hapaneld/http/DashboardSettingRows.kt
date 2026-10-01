package io.github.maxlyth.hapaneld.http

import io.github.maxlyth.hapaneld.i18n.Strings as AppStrings
import io.github.maxlyth.hapaneld.Config
import io.github.maxlyth.hapaneld.config.Capabilities
import io.github.maxlyth.hapaneld.config.SettingsRegistry
import io.github.maxlyth.hapaneld.config.SettingType
import io.github.maxlyth.hapaneld.control.AdaptiveLuxCurve
import io.github.maxlyth.hapaneld.control.DisplaySizingObservation
import io.github.maxlyth.hapaneld.control.SystemController

/** Read-only setting rows, using the supplied observed capabilities and live values. */
internal class DashboardSettingRows(private val config: Config) {
    /** One read-only dashboard row for a registry setting: label → current value + the edit pencil.
     *  Null when the setting doesn't exist on this panel (capability-gated). */
    fun settingRowHtml(
        key: String,
        live: Map<String, String>,
        caps: Capabilities,
        strings: AppStrings,
        hints: Map<String, String> = emptyMap(),
        valueFormatter: SettingRowFormatter? = null,
    ): String? {
        val spec = SettingsRegistry.spec(key) ?: return null
        if (!spec.availableWhen(caps)) return null
        val raw = effectiveSettingValue(config, spec, live)
        // NOTE the ordering: secret and BOOL specs resolve before [valueFormatter] is consulted, so a
        // formatter attached to one of those keys would be dead code. [SettingRowFormatter.of] refuses
        // to build one, so that is now unrepresentable rather than merely documented. Live state does
        // not belong on a setting row at all — put it on a fact row (see CONTEXT_KEYS).
        val shown = when {
            spec.secret -> if (raw.isNotEmpty()) strings.get("dashboard.value.set") else "—"
            spec.type == SettingType.BOOL -> strings.get(if (raw.toBoolean()) "dashboard.value.on" else "dashboard.value.off")
            raw.isBlank() -> hints[key]?.let {
                formattedString(strings, "dashboard.value.auto_detail", "value" to it)
            } ?: "—"
            // The built-in renderer sentinel has no package label — show its friendly name, not "builtin".
            raw == SystemController.BUILTIN_DASHBOARD -> strings.get("dashboard.value.builtin_renderer")
            else -> valueFormatter?.formatFor(key, raw) ?: raw
        }
        return """<tr><th>${esc(strings.get(spec.labelKey))}</th><td>${esc(shown)}${cfgIcon("cfg-$key", strings)}</td></tr>"""
    }

    fun behaviourRowsHtml(
        live: Map<String, String>,
        strings: AppStrings,
        hints: Map<String, String>,
        caps: Capabilities,
    ): String = listOf(
        "wake_on_wave", "prevent_idle_dim", "watchdog_enabled", "kiosk_lock", "touch_sound",
        "silence_boot_chime", "keep_awake", "navbar_mode", "log_ship_enabled", "log_ship_system_enabled",
        "home_dashboard", "ha_area", "dashboard_package", "launcher_package",
    ).let { keys ->
        keys.mapNotNull { key ->
            // A deliberately overridden area must say so wherever the value is shown; at rest it is
            // otherwise indistinguishable from an adopted value.
            val areaFormatter: SettingRowFormatter? =
                if (key == "ha_area" && config.haAreaUserOverride) {
                    SettingRowFormatter.of(key) { raw ->
                        formattedString(strings, "dashboard.value.local_override", "value" to raw)
                    }
                } else {
                    null
                }
            settingRowHtml(key, live, caps, strings, hints, areaFormatter)
        }
    }.joinToString("\n")

    // Display and install-backed values, each deep-linking to its owning surface.
    fun displayRowsHtml(
        live: Map<String, String>,
        sizing: DisplaySizingObservation,
        strings: AppStrings,
        capabilities: () -> Capabilities,
        proximity: () -> String?,
    ): String {
        return listOf(
            "auto_brightness", "auto_brightness_minimum_percent", "auto_brightness_maximum_percent", "auto_brightness_response_percent", "auto_brightness_ha_entity",
        ).mapNotNull { key ->
            val formatter: SettingRowFormatter? = when (key) {
                "auto_brightness_minimum_percent", "auto_brightness_maximum_percent" -> SettingRowFormatter.of(key) { raw ->
                    raw.toIntOrNull()?.coerceIn(0, 100)?.let { percent ->
                        "$percent% (${AdaptiveLuxCurve.percentToBrightness(percent)})"
                    } ?: raw
                }
                "auto_brightness_response_percent" -> SettingRowFormatter.of(key) { raw ->
                    raw.toIntOrNull()?.coerceIn(0, 100)?.let { "$it%" } ?: raw
                }
                else -> null
            }
            settingRowHtml(key, live, capabilities(), strings, valueFormatter = formatter)
        }
            .joinToString("\n") + "\n" + listOfNotNull(
            sizing.current?.let { """<tr><th>${esc(strings.get("dashboard.display.logical_density"))}</th><td>$it dpi (${esc(strings.get("dashboard.display.factory_base"))} ${sizing.base ?: "?"})${installIcon("cfg-display", strings)}</td></tr>""" },
            sizing.current?.let { """<tr><th>${esc(strings.get("dashboard.display.text_size"))}</th><td>${sizing.fontScale}${installIcon("cfg-display", strings)}</td></tr>""" },
            proximity()?.let {
                """<tr><th>${esc(strings.get("settings.wake_on_wave.label"))}</th><td>${esc(localizedProximitySummary(it, strings))}${cfgIcon("cfg-wake_on_wave", strings)}</td></tr>"""
            },
            """<tr><th>${esc(strings.get("dashboard.display.tamed_packages"))}</th><td>${esc(config.tameVendorPackagesRaw.ifBlank { strings.get("dashboard.value.none") })}${installIcon("cfg-tame", strings)}</td></tr>""",
        ).joinToString("\n")
    }


}

/** The pencil that marks a value as CONFIGURABLE (vs a static fact) and deep-links to the exact
 *  setting/card on the Configure tab (`/configure#<anchor>` scrolls + flashes it). */
internal fun cfgIcon(anchor: String, strings: AppStrings): String =
    """&nbsp;<a class="cfglink" href="${localizedHref("configure#$anchor", strings)}" title="${esc(strings.get("dashboard.link.edit_configure"))}" aria-label="${esc(strings.get("dashboard.link.edit"))}">✎</a>"""

internal fun installIcon(anchor: String, strings: AppStrings): String =
    """&nbsp;<a class="cfglink" href="${localizedHref("install#$anchor", strings)}" title="${esc(strings.get("dashboard.link.open_install"))}" aria-label="${esc(strings.get("dashboard.link.open"))}">✎</a>"""
