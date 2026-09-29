package io.github.maxlyth.hapaneld.http

import io.github.maxlyth.hapaneld.Config
import io.github.maxlyth.hapaneld.DashboardEntityBackupState
import io.github.maxlyth.hapaneld.config.Capabilities
import io.github.maxlyth.hapaneld.config.SettingType
import io.github.maxlyth.hapaneld.config.SettingSpec
import io.github.maxlyth.hapaneld.config.SettingsRegistry
import io.github.maxlyth.hapaneld.util.Json
import io.github.maxlyth.hapaneld.i18n.Strings as AppStrings

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
    fun effectiveValue(spec: io.github.maxlyth.hapaneld.config.SettingSpec, live: Map<String, String>): String =
        effectiveSettingValue(config, spec, live)

    /** Registry-driven current values (typed JSON; secrets blanked) for the Configure form. */
    fun settingsValuesJson(): String {
        fun s(v: String) = Json.str(v)
        val live = renderedLiveValues()   // controller-sourced keys via the snapshot, not fresh su probes
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

    /** Current registry values as a flat map (skips transient inputs; controller-sourced settings
     *  read their live state). The basis for export, the pre-change snapshot, and the dry-run diff. */
    fun currentValues(): Map<String, String> = currentValues(configLiveValues())

    fun currentValues(live: Map<String, String>): Map<String, String> {
        val m = projectConfigSnapshot(
            specs = SettingsRegistry.settable(),
            zigbeeRouterConfigured = config.zigbeeRouterConfigured,
            effectiveValue = { effectiveValue(it, live) },
        )
        SettingsRegistry.SPECS.filter { it.ha != null }.forEach { spec ->
            m[SettingsRegistry.exposureKey(spec)] = config.haExposed(spec.key, spec.haExposedByDefault).toString()
        }
        return m
    }

    /** Complete effective values for direct POST equality. Unlike export/revision snapshots this includes
     * transient and untouched hardware-backed settings, and pending durable intent supersedes observed state. */
    fun directMutationValues(): Map<String, String> {
        val live = configLiveValues()
        return LinkedHashMap<String, String>().apply {
            SettingsRegistry.settable().forEach { spec -> put(spec.key, effectiveValue(spec, live)) }
            SettingsRegistry.SPECS.filter { it.ha != null }.forEach { spec ->
                put(SettingsRegistry.exposureKey(spec), config.haExposed(spec.key, spec.haExposedByDefault).toString())
            }
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
        val pending = pendingLiveSettings()
        val pendingDesired = pending.entries.joinToString(",") { (key, value) ->
            "${s(key)}:${s(value)}"
        }
        val stalledDesired = jarr(stalledApplyKeys(pending.keys, stalledLiveSettings()))
        return "{" +
            mutation +
            "\"panel_id\":${s(config.panelId)}," +
            "\"ha_area_user_override\":${config.haAreaUserOverride}," +
            "\"friendly_name\":${s(config.friendlyName)}," +
            "\"manufacturer\":${s(config.manufacturer)}," +
            "\"model\":${s(config.model)}," +
            "\"http_port\":${config.httpPort}," +
            "\"mqtt_broker\":${s(config.mqttBroker)}," +
            "\"mqtt_user\":${s(config.mqttUser)}," +
            "\"mqtt_password_set\":${config.mqttPassword.isNotEmpty()}," +
            "\"mqtt_address_family\":${s(config.mqttAddressFamily)}," +
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
            "\"ha_auth\":{\"configured\":${config.haToken.isNotEmpty() || config.haRefreshToken.isNotEmpty()},\"oauth\":${config.haRefreshToken.isNotEmpty()}}," +
            "\"version\":${s(Config.VERSION)}," +
            "\"proximity\":${proximityJson()}," +
            "\"power_safety\":${powerAdvisory}," +
            // Registry-driven current values + per-key HA-exposure flags for the Configure form.
            "\"settings\":${settingsValuesJson()}," +
            "\"ha_expose\":${haExposeJson()}," +
            haAreaCatalogJson()?.let { "\"ha_area_catalog\":$it," }.orEmpty() +
            "\"apply_pending\":{$pendingDesired}," +
            "\"apply_stalled\":$stalledDesired" +
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
            val opts = spec.optionsFor(caps).joinToString(",") { s(it) }
            val isHa = spec.ha != null
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
                "\"default\":${s(spec.default)}," +
                "\"tier\":${s(spec.tierFor(caps).name)}," +
                "\"scope\":${s(spec.scope.name)}," +
                "\"secret\":${spec.secret}," +
                "\"readOnly\":${spec.readOnly}," +
                "\"available\":$available," +
                "\"displaySizingAvailable\":$displaySizing," +
                "\"options\":[$opts]," +
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
