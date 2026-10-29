package io.panelassistant.android.http

import io.panelassistant.android.appVersion
import io.panelassistant.android.Config
import io.panelassistant.android.DashboardEntityBackupState
import io.panelassistant.android.autoSleep
import io.panelassistant.android.config.Capabilities
import io.panelassistant.android.config.SettingType
import io.panelassistant.android.config.SettingSpec
import io.panelassistant.android.config.SettingsRegistry
import io.panelassistant.android.haRefreshToken
import io.panelassistant.android.haToken
import io.panelassistant.android.util.Json
import io.panelassistant.android.i18n.Strings as AppStrings

internal fun persistedExposureValues(config: Config): Map<String, String> =
    SettingsRegistry.persistedExposureDefaults().mapValues { (key, default) ->
        config.haExposed(key.removePrefix(SettingsRegistry.HA_EXPOSE_PREFIX), default).toString()
    }

/** Registry-backed effective values and Configure response projection; owns no mutable state. */
internal class ConfigValueProjection(
    private val config: Config,
    private val configLiveValues: () -> Map<String, String>,
    private val renderedLiveValues: () -> Map<String, String>,
    private val pendingLiveSettings: () -> Map<String, String>,
    private val stalledLiveSettings: () -> Set<String>,
    private val proximityJson: () -> String,
    private val powerSafetyJson: () -> String,
    private val haAreaCatalogJson: () -> String?,
) {
    fun liveValues(): Map<String, String> = configLiveValues()

    /** A setting's effective current value: controller-sourced live state where it exists, identity
     *  fields resolved (panel_id auto-derives when unset), else the persisted value. */
    fun effectiveValue(spec: io.panelassistant.android.config.SettingSpec, live: Map<String, String>): String =
        effectiveSettingValue(config, spec, live)

    /** Registry-driven current values (typed JSON; secrets blanked) for the Configure form. [live] defaults to the
     *  management snapshot's controller-sourced keys, never fresh su probes. */
    fun settingsValuesJson(live: Map<String, String> = renderedLiveValues()): String {
        fun s(v: String) = Json.str(v)
        val parts = SettingsRegistry.settable().joinToString(",") { spec ->
            val raw = effectiveValue(spec, live)
            val v = when {
                spec.secret -> "\"\""
                spec.type == SettingType.BOOL -> if (raw.toBoolean()) "true" else "false"
                spec.type == SettingType.INT || spec.type == SettingType.LONG ->
                    raw.toLongOrNull()?.toString() ?: s(raw)
                spec.type == SettingType.FLOAT -> raw.toDoubleOrNull()?.toString() ?: s(raw)
                else -> s(raw)
            }
            "${s(spec.key)}:$v"
        }
        return "{$parts}"
    }

    /** Per-key HA-exposure flags for every HA-capable setting (for the inline expose pips). */
    fun haExposeJson(): String {
        val parts = SettingsRegistry.SPECS.filter { it.ha != null }.joinToString(",") { spec ->
            "\"${spec.key}\":${config.haExposed(spec.key, spec.haExposedByDefault)}"
        }
        return "{$parts}"
    }

    private fun haAuthJson(): String =
        "{\"configured\":${config.haToken.isNotEmpty() || config.haRefreshToken.isNotEmpty()},\"oauth\":${config.haRefreshToken.isNotEmpty()}}"

    /** `"apply_pending":{…},"apply_stalled":[…]`: the live settings saved but not yet applied, and those stuck. */
    private fun applyStateJson(): String {
        val pending = pendingLiveSettings()
        val pendingDesired = pending.entries.joinToString(",") { (key, value) -> "${Json.str(key)}:${Json.str(value)}" }
        return "\"apply_pending\":{$pendingDesired}," +
            "\"apply_stalled\":${jarr(stalledApplyKeys(pending.keys, stalledLiveSettings()))}"
    }

    /**
     * The values-only read (`config.values`) an open Configure page follows every half second: the `settings`,
     * `ha_expose`, `ha_auth`, `apply_pending` and `apply_stalled` of [configJson], with `settings` taken from the
     * su-free live values (the ones the health `cfg=` token and POST equality use), never the management snapshot,
     * plus [rendererPending] (sorted; null when no built-in renderer runs).
     */
    fun valuesJson(rendererPending: Set<String>?): String =
        "{\"settings\":${settingsValuesJson(configLiveValues())}," +
            "\"ha_expose\":${haExposeJson()}," +
            "\"ha_auth\":${haAuthJson()}," +
            "\"ha_area_user_override\":${config.haAreaUserOverride}," +
            applyStateJson() + "," +
            "\"renderer_pending\":${rendererPending?.let { jarr(it.sorted()) } ?: "null"}" +
            "}"

    /** Current registry values as a flat map (skips transient inputs; controller-sourced settings
     *  read their live state). The basis for export, the pre-change snapshot, and the dry-run diff. */
    fun currentValues(): Map<String, String> = currentValues(configLiveValues())

    fun currentValues(live: Map<String, String>): Map<String, String> {
        val m = projectConfigSnapshot(
            specs = SettingsRegistry.settable(),
            zigbeeRouterConfigured = config.zigbeeRouterConfigured,
            effectiveValue = { effectiveValue(it, live) },
        )
        m.putAll(persistedExposureValues(config))
        return m
    }

    /** Complete effective values for direct POST equality. Unlike export/revision snapshots this includes
     * transient and untouched hardware-backed settings, and pending durable intent supersedes observed state. */
    fun directMutationValues(): Map<String, String> {
        val live = configLiveValues()
        return LinkedHashMap<String, String>().apply {
            SettingsRegistry.settable().forEach { spec -> put(spec.key, effectiveValue(spec, live)) }
            putAll(persistedExposureValues(config))
            putAll(pendingLiveSettings())
        }
    }

    fun revisionValues(
        values: Map<String, String> = currentValues(),
        state: DashboardEntityBackupState = config.dashboardEntityBackupState(),
    ): Map<String, String> = LinkedHashMap(values).apply {
        put("$ENTITY_REVISION_PREFIX.instance_key", state.instanceKey)
        put("$ENTITY_REVISION_PREFIX.instance_origin", state.instanceOrigin)
        put("$ENTITY_REVISION_PREFIX.instance_uuid", state.instanceUuid)
        put("$ENTITY_REVISION_PREFIX.dashboard_path", state.dashboardPath)
        put("$ENTITY_REVISION_PREFIX.filter_ids", state.filterIds)
        put("$ENTITY_REVISION_PREFIX.filter_enabled", state.filterEnabled.toString())
        put("$ENTITY_REVISION_PREFIX.filter_owner", state.filterOwner)
        put("$ENTITY_REVISION_PREFIX.learning_applied", state.learningApplied.toString())
        put("$ENTITY_REVISION_PREFIX.applied_owner", state.appliedOwner)
        put("$ENTITY_REVISION_PREFIX.overrides", state.overrides)
        put("$ENTITY_REVISION_PREFIX.override_owner", state.overrideOwner)
    }

    fun concurrencyHash(values: Map<String, String> = currentValues()): String =
        io.panelassistant.android.config.ConfigHash.of(configConcurrencyValues(values))

    /** Full config as JSON for fleet management. No credential is ever emitted: secret settings read
     *  blank. `http_port` is the port listening now; a POST can only ask for the default back, which
     *  takes effect the next time the HTTP server starts. */
    fun configJson(
        mutationStatus: String? = null,
        applied: List<String> = emptyList(),
        pending: List<String> = emptyList(),
        rejected: List<String> = emptyList(),
        message: String? = null,
    ): String {
        fun s(v: String) = Json.str(v)
        val powerAdvisory = powerSafetyJson()
        val mutation = mutationStatus?.let {
            "\"ok\":${rejected.isEmpty()}," +
                "\"status\":${s(it)}," +
                "\"applied\":${jarr(applied)}," +
                "\"pending\":${jarr(pending)}," +
                "\"rejected\":${jarr(rejected)}," +
                "\"message\":${s(message.orEmpty())},"
        }.orEmpty()
        return "{" +
            mutation +
            "\"panel_id\":${s(config.panelId)}," +
            "\"ha_area_user_override\":${config.haAreaUserOverride}," +
            "\"friendly_name\":${s(config.friendlyName)}," +
            "\"manufacturer\":${s(config.manufacturer)}," +
            "\"model\":${s(config.model)}," +
            "\"http_port\":${config.httpPort}," +
            "\"network_address_family\":${s(config.addressFamily)}," +
            "\"dashboard_package\":${s(config.dashboardPackage)}," +
            "\"launcher_package\":${s(config.launcherPackage)}," +
            "\"tame_vendor_packages\":${s(config.tameVendorPackagesRaw)}," +
            "\"silence_boot_chime\":${config.silenceBootChime}," +
            "\"keep_awake\":${config.keepAwake}," +
            "\"log_ship_enabled\":${config.logShipEnabled}," +
            "\"log_ship_system_enabled\":${config.logShipSystemEnabled}," +
            "\"log_ship_host\":${s(config.logShipHost)}," +
            "\"log_ship_port\":${config.logShipPort}," +
            "\"log_ship_protocol\":${s(config.logShipProtocol)}," +
            "\"ha_auth\":${haAuthJson()}," +
            "\"version\":${s(appVersion())}," +
            "\"proximity\":${proximityJson()}," +
            "\"power_safety\":${powerAdvisory}," +
            // Registry-driven current values + per-key HA-exposure flags for the Configure form.
            "\"settings\":${settingsValuesJson()}," +
            "\"ha_expose\":${haExposeJson()}," +
            haAreaCatalogJson()?.let { "\"ha_area_catalog\":$it," }.orEmpty() +
            applyStateJson() +
            "}"
    }

    /**
     * Registry metadata for generating the Configure form (type/group/tier/scope/options/range +
     * whether the setting is an HA entity and currently exposed), capability-gated to this panel.
     * Values themselves come from GET /config; this endpoint is metadata only.
     */
    fun schemaJson(
        strings: AppStrings,
        caps: Capabilities,
        hints: Map<String, String>,
        manufacturer: String?,
        model: String?,
    ): String {
        fun s(v: String) = Json.str(v)
        val displaySizingAvailable = caps.canSetDisplay
        // Include the settable settings PLUS the read-only HA sensors (diagnostics): the latter carry
        // no editable value but still render an expose pip, so the user can opt them into HA.
        val schemaSpecs = SettingsRegistry.schemaVisibleSpecs(caps)
        val items = schemaSpecs.joinToString(",") { spec ->
            val options = spec.optionsFor(caps)
            val opts = options.joinToString(",") { s(it) }
            // An option without a label record (a value added before its translation) shows as itself.
            val optionLabels = options.joinToString(",") { option ->
                s(spec.optionLabelKey(option).takeIf(strings::has)?.let(strings::get) ?: option)
            }
            // The expose pip is offered only where it withholds a sensor reading (PanelStateCore). For a
            // commandable entity Home Assistant's entity registry is the authority (native-transport-protocol.md
            // §7), so a pip there would change nothing.
            val isHa = spec.readOnly && spec.ha?.component in setOf("sensor", "binary_sensor")
            val placeholder = hints[spec.key]?.let {
                strings.get("configure.option.auto_detail").replace("{value}", it)
            } ?: when (spec.key) {
                "manufacturer" -> manufacturer
                "model" -> model
                else -> null
            }?.takeIf { it.isNotBlank() }
            // Resolve every value that needs a quote — a key comparison, or the bare JSON `null` token —
            // BEFORE the template below, so each interpolation is a plain identifier. Nesting a quoted
            // literal inside ${...} is valid Kotlin, but it reads as though it were string data that
            // someone forgot to escape, and it has twice been "corrected" to \" — which is a parse error,
            // because ${...} holds code, and which takes the whole module's compilation down with it
            // (Kotlin loses the enclosing class, so every companion member reports as unresolved).
            // Bare identifiers leave nothing to second-guess. Guarded by StringTemplateEscapeContractTest.
            val nullJson = "null"
            val autoSleepActivityHidden = spec.key == "auto_sleep_activity" && !config.autoSleep
            val available = spec.availableWhen(caps) && !autoSleepActivityHidden
            val displaySizing = spec.key == "dashboard_zoom" && displaySizingAvailable
            val pickerJson = spec.picker?.let { s(it) } ?: nullJson
            val minJson = spec.min?.toString() ?: nullJson
            val maxJson = spec.max?.toString() ?: nullJson
            val stepJson = spec.step?.toString() ?: nullJson
            val sized = spec.type == SettingType.STRING || spec.type == SettingType.PASSWORD
            val maxLengthJson = if (sized) spec.maxChars.toString() else nullJson
            val exposed = if (isHa) config.haExposed(spec.key, spec.haExposedByDefault) else false
            val placeholderJson = placeholder?.let { s(it) } ?: nullJson
            val label = strings.resolve(spec.labelKey)
            val helpKey = spec.helpKeyFor(caps)
            val help = helpKey?.let(strings::resolve)
            val helpKeyJson = helpKey?.let(::s) ?: nullJson
            val helpLanguageJson = help?.language?.let(::s) ?: nullJson
            val summary = spec.summary.takeIf { it.isNotEmpty() }?.let { strings.resolve(spec.summaryKey) }
            val summaryLanguageJson = summary?.language?.let(::s) ?: nullJson
            "{" +
                "\"key\":${s(spec.key)}," +
                "\"type\":${s(spec.type.name)}," +
                "\"group\":${s(spec.group)}," +
                "\"labelKey\":${s(spec.labelKey)}," +
                "\"helpKey\":$helpKeyJson," +
                "\"label\":${s(label.text)}," +
                "\"labelLanguage\":${s(label.language)}," +
                "\"help\":${s(help?.text.orEmpty())}," +
                "\"helpLanguage\":$helpLanguageJson," +
                "\"summary\":${s(summary?.text.orEmpty())}," +
                "\"summaryLanguage\":$summaryLanguageJson," +
                "\"shortDescriptionUsefulInPopover\":${spec.shortDescriptionUsefulInPopover}," +
                "\"default\":${s(config.effectiveDefault(spec, caps))}," +
                "\"tier\":${s(spec.tierFor(caps).name)}," +
                "\"scope\":${s(spec.scope.name)}," +
                "\"secret\":${spec.secret}," +
                "\"readOnly\":${spec.readOnly}," +
                "\"available\":$available," +
                "\"displaySizingAvailable\":$displaySizing," +
                "\"options\":[$opts]," +
                "\"optionLabels\":[$optionLabels]," +
                "\"picker\":$pickerJson," +
                "\"min\":$minJson," +
                "\"max\":$maxJson," +
                "\"step\":$stepJson," +
                "\"maxLength\":$maxLengthJson," +
                "\"ha\":$isHa," +
                "\"exposed\":$exposed," +
                "\"placeholder\":$placeholderJson" +
                "}"
        }
        return "[$items]"
    }

    private fun jarr(items: List<String>): String =
        "[" + items.joinToString(",") { Json.str(it) } + "]"

    companion object {
        internal const val ENTITY_REVISION_PREFIX = "_local.entity_state"
    }
}
