package io.panelassistant.android.config

import io.panelassistant.android.util.Json

/**
 * Declarative settings registry — the single source of truth for ha-paneld configuration.
 *
 * A [SettingSpec] describes one setting once; three consumers read it so they can never drift:
 *  1. the HTTP `/api/v1/config` API (validation + accept + the form-generation schema),
 *  2. Home Assistant MQTT discovery ([HaEntity] → discovery payload, gated by per-panel exposure),
 *  3. config bundle export/import + on-panel revision history.
 *
 * Everything in this package is **pure / Android-free** so it runs on the plain JVM unit-test
 * classpath (which has no `org.json` and no Android stubs). JSON is hand-built by string
 * concatenation here — matching the existing `PaneldServer.configJson()` / `MqttBridge` style —
 * and parsing of inbound JSON happens at the Android edge, handing a plain `Map<String,String>`
 * to this layer.
 */

/** Value type of a setting; drives coercion, validation, and the generated form control. */
enum class SettingType { BOOL, INT, LONG, FLOAT, ENUM, STRING, PASSWORD }

/** UI disclosure tier. BASIC settings show on the short Configure page; ADVANCED behind a reveal. */
enum class Tier { BASIC, ADVANCED }

/**
 * Portability classification, used by bundle import to decide what a fleet deploy may apply.
 *  - IDENTITY: per-panel identity (panel_id, friendly_name). Restored to the same panel; never cloned.
 *  - DEVICE:   hardware/site-specific (model, cpu profile, log sink). Restored; not cloned by default.
 *  - PORTABLE: behaviour that is safe to push fleet-wide.
 */
enum class Scope { IDENTITY, DEVICE, PORTABLE }

/** Outcome of validating one raw value against a spec. */
sealed class Validation {
    /** Valid; carries the canonical normalized string form to persist. */
    data class Ok(val normalized: String) : Validation()

    /** Invalid; carries a human-readable reason for the API error. */
    data class Bad(val reason: String) : Validation()
}

/**
 * Immutable capability snapshot for a single panel. Built by the service from the same profile +
 * runtime probes it already feeds `MqttBridge`, then consumed by [SettingSpec.availableWhen] and the
 * discovery loop. Plain data class so both stay unit-testable without Android.
 */
data class Capabilities(
    val hasProximity: Boolean = false,
    /** True after the adaptive learner proves this exact source fingerprint has a usable binary or
     *  graded signal. Source setup remains available through [hasProximity] while learning; actuation
     *  and fleet reporting stay unavailable until this learned capability exists. */
    val hasLearnedProximity: Boolean = false,
    val hasLight: Boolean = false,
    val hasTemperature: Boolean = false,
    val hasHumidity: Boolean = false,
    val hasWifi: Boolean = false,
    val hasWifiSsid: Boolean = false,
    val hasCht8305: Boolean = false,
    val hasButtonBacklight: Boolean = false,
    val hasCamera: Boolean = false,
    /** Whether the panel has a microphone, proven or not; voice is offered unless it is absent. */
    val microphone: io.panelassistant.android.audio.MicrophonePresence = io.panelassistant.android.audio.MicrophonePresence.ABSENT,
    val hasEvdevButtons: Boolean = false,
    val appCanSu: Boolean = false,
    /** A verified privileged install route exists for explicitly requested software updates. */
    val canInstallVerifiedApps: Boolean = false,
    val hasRecents: Boolean = false,
    // Usable native navigation resolved by Config from an explicit profile declaration or firmware probe.
    val hasNativeNavbar: Boolean = false,
    val hasAndroidStatusBar: Boolean = false,
    // Effective declaration/probe result, including unknown; shared by admission and fresh defaults.
    val androidShowsNavbar: Boolean? = null,
    // True when the profile-declared fields in this snapshot were read from a catalog profile document.
    // Most capability fields need no such marker, because their `false` and their unset value both mean
    // "withhold the feature". A field that makes something APPEAR where hardware is absent inverts that:
    // an unpopulated snapshot and a last-resort profile both read `false` while knowing nothing, and
    // acting on that would add a control everywhere. See [navbarModeDefault].
    val hardwareDeclarationsKnown: Boolean = false,
    val ledAvailable: Boolean = false,
    val ledColorCapable: Boolean = false,
    val zigbeePresent: Boolean = false,
    val cpuGovernors: Boolean = false,
    val networkAdb: Boolean = false,
    val relays: Int = 0,
    val buttonLeds: Int = 0,
    // Android 10+ ships a system-wide dark/light setting; panels that have it follow the OS and hide
    // ha-paneld's own dark_mode toggle (Android 9- panels have no such control, so ours fills the gap).
    val hasSystemDarkMode: Boolean = false,
    // Runtime-only privilege routes. These describe what the panel can do now; they are never config
    // switches and therefore cannot be imported or changed through MQTT/HTTP.
    val canCaptureAndInput: Boolean = false,
    val canSetDisplay: Boolean = false,
)

/**
 * Home Assistant discovery descriptor for a setting/control. `null` on a [SettingSpec] means the
 * setting is never an HA entity (e.g. panel_id, log sink host).
 *
 * [body] holds the entity-specific middle of the discovery JSON — every field between `unique_id`
 * and the shared `availability` + `device` fragments — with `{panel}` as the topic placeholder.
 * [haEntity] renders it field by field in the declared order (rather than re-deriving field order), which
 * keeps discovery stable apart from explicit descriptor-level policy fields such as the bounded
 * measurement refresh below.
 */
data class HaEntity(
    val component: String,        // light | switch | number | select | sensor | binary_sensor | text | button | event
    val objectSuffix: String,     // unique_id/object_id suffix; full id = "<panel>_<objectSuffix>"
    val name: String,             // HA entity name ("Wake on wave")
    // Entity-specific fields, comma-joined, no leading/trailing comma. {panel} = panel id. {options} =
    // the capability-filtered ENUM choices as a JSON array; use it instead of a literal list only when
    // the spec carries `optionRequires`, so every other entity's payload stays a fixed string.
    val body: String,
    val readOnly: Boolean = false,// publish-only (sensors): no /api/v1/config row, value comes from the runtime
    /** Opt this numeric measurement into bounded unchanged-value reports. Home Assistant needs
     *  force_update to turn those reports into graph/recorder points; controls and attributes must not
     *  inherit it because their duplicate reports can wake automations or grow unrelated history. */
    val periodicRefresh: Boolean = false,
    /** The channel id: the topic leaf on MQTT and the channel on the native transport. Usually the
     *  [objectSuffix]; `voice_enabled` keeps its historical `voice_assistant` unique id. */
    val channel: String = objectSuffix,
    /** The transport-neutral facts both serialisations take, rendered into [body] where declared. */
    val facts: ChannelFacts = ChannelFacts(),
) {
    /** State topic this entity reports on, e.g. `ha-paneld/<panel>/wake_on_wave/state`. */
    fun stateTopic(panel: String): String = "ha-paneld/$panel/$channel/state"

    /**
     * Build the discovery payload. Entities without an explicit descriptor-level policy remain
     * byte-identical to the legacy hand-written JSON.
     * [deviceJson] is the shared `"device":{...}` fragment, [availJson] the shared availability
     * fragment — both produced once per publish by the caller. [enabledByDefault]=false appends the
     * soft-hide flag (entity exists in HA but disabled → no recorder load) for the advanced hide mode.
     * [optionsJson] substitutes the `{options}` placeholder for a capability-filtered select; a body
     * without the placeholder is unaffected, so this cannot perturb any other entity's bytes.
     */
    fun buildDiscoveryJson(
        panel: String,
        availJson: String,
        deviceJson: String,
        enabledByDefault: Boolean = true,
        optionsJson: String? = null,
    ): String {
        val edb = if (enabledByDefault) "" else ""","enabled_by_default":false"""
        val forceUpdate = if (periodicRefresh) ""","force_update":true""" else ""
        // object_id keys the HA entity_id to the stable panel_id, not the cosmetic device name —
        // matches the hand-written discovery payloads (discovery-identity anchoring).
        return "{\"name\":\"${jsonEsc(name)}\",\"object_id\":\"${panel}_$objectSuffix\"," +
            "\"unique_id\":\"${panel}_$objectSuffix\"," +
            body.replace("{panel}", panel).replace("{options}", optionsJson.orEmpty()) +
            forceUpdate + edb + ",$availJson,$deviceJson}"
    }

    companion object {
        fun jsonEsc(s: String): String = Json.esc(s)
    }
}

/**
 * One value of a channel's closed set: the stable wire [code] and the [label] MQTT has always carried for
 * it. The code is declared, never derived from the label, so renaming a label changes no wire code.
 */
data class ChannelOption(val code: String, val label: String)

/** Transport-neutral facts of one channel, declared once ahead of the MQTT and native serialisations. */
data class ChannelFacts(
    val deviceClass: String? = null,
    val unit: String? = null,
    val stateClass: String? = null,
    val entityCategory: String? = null,
    val min: Number? = null,
    val max: Number? = null,
    val step: Number? = null,
    val options: List<ChannelOption>? = null,
    /** A sensor whose value is free text rather than a number. */
    val text: Boolean = false,
)

/**
 * Declares an [HaEntity]'s discovery body and its [ChannelFacts] in one pass. Each call records its fact
 * and renders it where it is written, so the MQTT bytes keep their historical field order while the
 * native descriptor reads the same typed values.
 */
fun haEntity(
    component: String,
    objectSuffix: String,
    name: String,
    channel: String = objectSuffix,
    readOnly: Boolean = false,
    periodicRefresh: Boolean = false,
    discovery: HaDiscovery.() -> Unit,
): HaEntity {
    val declared = HaDiscovery(channel).apply(discovery)
    return HaEntity(
        component, objectSuffix, name, declared.body, readOnly, periodicRefresh,
        channel = channel, facts = declared.facts,
    )
}

class HaDiscovery internal constructor(private val channel: String) {
    private val parts = mutableListOf<String>()
    internal var facts = ChannelFacts()
        private set
    internal val body: String get() = parts.joinToString(",")

    fun commandTopic() = raw(""""command_topic":"ha-paneld/{panel}/$channel/set"""")
    fun stateTopic() = raw(""""state_topic":"ha-paneld/{panel}/$channel/state"""")
    fun attributesTopic() = raw(""""json_attributes_topic":"ha-paneld/{panel}/$channel/attributes"""")
    fun icon(icon: String) = raw(""""icon":${Json.str(icon)}""")

    /** MQTT-only presentation (schema, mode, payload literals). Never a channel fact. */
    fun raw(fragment: String) {
        parts += fragment
    }

    fun deviceClass(value: String) = fact("device_class", value) { copy(deviceClass = value) }
    fun unit(value: String) = fact("unit_of_measurement", value) { copy(unit = value) }
    fun stateClass(value: String) = fact("state_class", value) { copy(stateClass = value) }
    fun entityCategory(value: String) = fact("entity_category", value) { copy(entityCategory = value) }

    fun range(min: Int, max: Int, step: Int) {
        facts = facts.copy(min = min, max = max, step = step)
        raw(""""min":$min,"max":$max,"step":$step""")
    }

    /** A select's options; MQTT lists their labels. */
    fun options(options: List<ChannelOption>) {
        facts = facts.copy(options = options)
        raw(""""options":${options.joinToString(",", "[", "]") { Json.str(it.label) }}""")
    }

    /** A select whose MQTT label list is filtered by capability at publish time (the `{options}` placeholder). */
    fun capabilityOptions(options: List<ChannelOption>) {
        facts = facts.copy(options = options)
        raw(""""options":{options}""")
    }

    /** A sensor's closed value set, which MQTT discovery has never listed. */
    fun sensorOptions(options: List<ChannelOption>) {
        facts = facts.copy(options = options)
    }

    fun textValue() {
        facts = facts.copy(text = true)
    }

    private fun fact(key: String, value: String, record: ChannelFacts.() -> ChannelFacts) {
        facts = facts.record()
        raw(""""$key":${Json.str(value)}""")
    }
}

/** Hard length budget for [SettingSpec.summary]; the catalogue carries the same budget to every locale. */
const val SUMMARY_HARD_MAX_CHARS = 90

/**
 * One configuration setting. [key] is the durable-state key, the `/api/v1/config` field name,
 * and the bundle key. [default] is the canonical string form (typed reads coerce it).
 */
data class SettingSpec(
    val key: String,
    val type: SettingType,
    val group: String,                       // UI grouping ("Identity", "MQTT", "Behaviour", "Display", "System", "Logging")
    val label: String,
    val help: String = "",
    // One line under the label on Configure; [help] opens behind the info button. Every visible
    // setting needs one; hidden API-only settings never render and carry none.
    val summary: String = "",
    // Include the hidden summary only when it adds useful information to this popover.
    val shortDescriptionUsefulInPopover: Boolean = false,
    val default: String,
    // No default: every setting states whether it belongs on the basic view.
    val tier: Tier,
    // Fleet imports are intentionally fail-closed: a new setting stays local until its author
    // explicitly confirms that copying it to every panel is safe.
    val scope: Scope = Scope.DEVICE,
    val secret: Boolean = false,             // redacted from GET /config + excluded from export by default
    val min: Double? = null,
    val max: Double? = null,
    val step: Double? = null,
    // Bound persisted text independently of the enclosing request. A single short identity value is
    // repeated across many MQTT discovery documents, so the HTTP body limit alone is not a useful
    // amplification bound. Individual specs may tighten this conservative default.
    val maxChars: Int = 16 * 1024,
    val options: List<String> = emptyList(), // ENUM choices
    // Retired ENUM spellings → their replacement, resolved before the options match. Renaming a
    // choice would otherwise make every older config bundle, MQTT command and provision.sh
    // invocation fail validation outright instead of importing to the value the user meant.
    val aliases: Map<String, String> = emptyMap(),
    val picker: String? = null,              // dynamic picker for a STRING field ("package" → installed-apps dropdown)
    val ha: HaEntity? = null,                // discovery descriptor, or null if never an HA entity
    // Per-panel default for the expose-to-HA pip. Defaults to FALSE: a setting is local-only (HTTP UI)
    // unless it explicitly opts in with haExposedByDefault=true in SettingsRegistry. Consulted by the
    // discovery gate for every HA-publishable setting (those with `ha`, plus the discovery-pass entities
    // like navbar that carry no `ha` block here). A per-panel expose pip can still override it either way.
    val haExposedByDefault: Boolean = false,
    // The setting's desired value is persisted and then routed through the shared MQTT-equivalent
    // side-effect path. This keeps HTTP config, bundle/restore, and MQTT command admission on one
    // declared set instead of independently maintained key lists.
    val liveApply: Boolean = false,
    val transient: Boolean = false,          // accepted + routed to a controller but never persisted
    // Omit from the generated Configure form (an API-only setting): still readable via GET /config,
    // settable via POST /config, and carried in config bundles — just never rendered as a form field.
    val hidden: Boolean = false,
    val availableWhen: (Capabilities) -> Boolean = { true },
    // Per-ENUM-choice capability gates, for the case where the setting itself is always meaningful but
    // one of its choices is not. `availableWhen` cannot express this: hiding the whole setting would
    // remove working choices too. A choice with no entry here is always offered.
    val optionRequires: Map<String, (Capabilities) -> Boolean> = emptyMap(),
    // Capability-derived fresh-install default, the sibling of [optionRequires]: where that withholds a
    // choice a panel must not have, this chooses the value a panel should start at when the user has
    // never set one. Null means [default] is the answer everywhere.
    //
    // Two properties make this safe to add to any spec. It is consulted ONLY where no value was ever
    // stored, so an explicit choice and an existing panel's persisted value are both untouched; and it
    // never replaces [default], which remains the durable, capability-independent string sealed into
    // the guard-database settings authority (`authoritativeGuardDbSettingDefaults`).
    val derivedDefault: ((Capabilities) -> String)? = null,
    // Capability-derived prominence, the presentation sibling of [derivedDefault]: true where this
    // setting is the unprivileged substitute for a control the panel cannot offer, so it must take that
    // control's place instead of staying where it sits when the preferred control exists. A promoted
    // setting renders first in its group on the basic view, with [promotedHelp] in place of [help].
    // Presentation only: key, range, scope and every stored value are untouched.
    val promoteWhen: (Capabilities) -> Boolean = { false },
    val promotedHelp: String = "",
    val validate: (String) -> Validation = { Validation.Ok(it) },
) {
    /** Stable catalogue ids derived from the durable setting key, never from editable English copy. */
    val labelKey: String get() = "settings.$key.label"
    val helpKey: String get() = "settings.$key.help"
    val summaryKey: String get() = "settings.$key.summary"
    val promotedHelpKey: String get() = "settings.$key.promoted_help"

    /** Display label record of one closed option; the option itself stays the stored and wire value. */
    fun optionLabelKey(option: String): String =
        "configure.enum.$key." + option.lowercase().replace(Regex("[^a-z0-9]+"), "_")

    init {
        require(hidden || summary.isNotBlank()) { "$key: a visible setting needs a summary" }
        require(summary.length <= SUMMARY_HARD_MAX_CHARS) { "$key: summary over $SUMMARY_HARD_MAX_CHARS characters" }
    }

    /** True for publish-only sensor entities that have no settable value. */
    val readOnly: Boolean get() = ha?.readOnly == true

    /**
     * The ENUM choices this panel may actually select, in declared order. [options] stays the full
     * static set — it is the durable validation vocabulary that config bundles and retired-spelling
     * aliases resolve against — while this is what the Configure form and HA discovery offer.
     */
    fun optionsFor(caps: Capabilities): List<String> =
        options.filter { optionRequires[it]?.invoke(caps) ?: true }

    /**
     * The value this panel should start at when nothing was ever stored for [key]. Falls back to the
     * static [default], so a spec that declares no [derivedDefault] is unaffected.
     */
    fun defaultFor(caps: Capabilities): String = derivedDefault?.invoke(caps) ?: default

    /** Presentation tier on this panel: a promoted setting is always on the basic view. */
    fun tierFor(caps: Capabilities): Tier = if (promoteWhen(caps)) Tier.BASIC else tier

    /** Catalogue key of the help this panel shows, or null when the setting carries no help. */
    fun helpKeyFor(caps: Capabilities): String? = when {
        promotedHelp.isNotEmpty() && promoteWhen(caps) -> promotedHelpKey
        help.isNotEmpty() -> helpKey
        else -> null
    }

    /**
     * The `{options}` substitution for this spec's HA select, or null when it declares no per-choice
     * gates and therefore carries no placeholder. Sole authority for that fragment so the published
     * payload and any assertion about it cannot be computed two different ways.
     */
    fun discoveryOptionsJson(caps: Capabilities): String? =
        if (optionRequires.isEmpty()) null
        else optionsFor(caps).joinToString(",", "[", "]") { Json.str(it) }
}

// Typed coercions of a spec's canonical [SettingSpec.default] string. This makes the spec the single
// authority for each key's default: the raw config API and the typed Config accessors both resolve a
// default through these, so no call site re-declares a literal that could drift from the spec. The
// coercion is deliberately the same permissive form the raw config path has always used (canonical
// defaults like "true"/"100"/"0" round-trip exactly).
fun SettingSpec.defaultBool(): Boolean = default.toBoolean()
fun SettingSpec.defaultInt(): Int = default.toIntOrNull() ?: 0
fun SettingSpec.defaultLong(): Long = default.toLongOrNull() ?: 0L
fun SettingSpec.defaultFloat(): Float = default.toFloatOrNull() ?: 0f
