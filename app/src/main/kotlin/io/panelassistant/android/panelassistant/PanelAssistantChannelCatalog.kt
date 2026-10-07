package io.panelassistant.android.panelassistant

import io.panelassistant.android.config.ChannelOption
import io.panelassistant.android.config.SettingSpec
import io.panelassistant.android.config.SettingsRegistry
import io.panelassistant.android.control.ZigbeeHealthState
import io.panelassistant.android.mqttButtonEventTypes
import io.panelassistant.android.mqtt.SoftwareComponent
import io.panelassistant.android.mqtt.SoftwareUpdateEntities
import io.panelassistant.android.storage.StorageHealthSeverity
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** How an MQTT state payload becomes a typed value on the native transport (protocol section 7). */
internal enum class PanelAssistantValueKind { BOOLEAN, NUMBER, OPTION, TEXT, LIGHT, UPDATE, BUTTON, MEDIA, IMAGE, EVENT }

/** One channel as the `hello` describes it. Codes and facts only, never display text. */
internal data class PanelAssistantChannelDescriptor(
    val channel: String,
    val platform: String,
    val translationKey: String,
    val uniqueSuffix: String,
    val kind: PanelAssistantValueKind,
    val family: String? = null,
    val index: Int? = null,
    val entityCategory: String? = null,
    val enabledDefault: Boolean = true,
    val deviceClass: String? = null,
    val unit: String? = null,
    val stateClass: String? = null,
    val forceUpdate: Boolean = false,
    /** The closed value set: each wire code with the label the MQTT handlers and state payloads use. */
    val choices: List<ChannelOption>? = null,
    val min: Number? = null,
    val max: Number? = null,
    val step: Number? = null,
) {
    /** The option codes the wire carries. */
    val options: List<String>? get() = choices?.map { it.code }

    fun code(label: String): String? = choices?.firstOrNull { it.label == label }?.code

    fun label(code: String): String? = choices?.firstOrNull { it.code == code }?.label

    fun toJson(): JSONObject = JSONObject()
        .put("channel", channel)
        .put("platform", platform)
        .put("translation_key", translationKey)
        .put("unique_suffix", uniqueSuffix)
        .put("family", family ?: JSONObject.NULL)
        .put("index", index ?: JSONObject.NULL)
        .put("entity_category", entityCategory ?: JSONObject.NULL)
        .put("enabled_default", enabledDefault)
        .put("device_class", deviceClass ?: JSONObject.NULL)
        .put("unit", unit ?: JSONObject.NULL)
        .put("state_class", stateClass ?: JSONObject.NULL)
        .put("force_update", forceUpdate)
        .put("options", options?.let(::JSONArray) ?: JSONObject.NULL)
        .put("min", min ?: JSONObject.NULL)
        .put("max", max ?: JSONObject.NULL)
        .put("step", step ?: JSONObject.NULL)
}

/**
 * The native transport's view of the converger's channels, read from the same definitions the MQTT edge
 * serialises: registry-backed entities from their [SettingsRegistry] channel id and typed facts, and the
 * hand-written discovery entities from the typed descriptors below, which mirror the literals in
 * `MqttBridge.publishDiscovery`. `unique_suffix` is always today's MQTT unique id after `<panel>_`.
 *
 * Two converger channels are MQTT plumbing rather than channels on the wire: their attribute payloads
 * are folded into the parent channel's observation (protocol section 16). The update channels are
 * renamed to their wire ids.
 */
internal object PanelAssistantChannelCatalog {
    /** Known former channels: hello states their retirement so existing native entities are removed. */
    val RETIRED_CHANNELS: Set<String> = setOf(
        "self_update", "update_channel", "companion_auto_update", "companion_update_channel", "webview_auto_update",
    )

    /** Attribute channel → the channel whose observation carries its attributes. */
    val FOLDED: Map<String, String> = mapOf(
        "storage_health_attributes" to "storage_health",
        "diag_wifi_outages_attributes" to "diag_wifi_outages_24h",
        "zigbee_gateway_health_attributes" to "zigbee_gateway_health",
        "auto_sleep_activity_attributes" to "auto_sleep_activity",
    )

    private val RENAMED: Map<String, String> = SoftwareComponent.entries.associate {
        SoftwareUpdateEntities.stateChannelKey(it) to "update_${it.wire}"
    }

    /** The wire channel an MQTT converger channel reports on; null for a folded attribute channel. */
    fun wireChannel(converger: String): String? =
        if (converger in FOLDED) null else RENAMED[converger] ?: converger

    private val FAMILY = Regex("^(relay|button_led)([1-9][0-9]*)$")

    private val registryByChannel: Map<String, SettingSpec> by lazy {
        SettingsRegistry.haCapable().associateBy { requireNotNull(it.ha).channel }
    }

    /** Built descriptors by wire channel, [UNDESCRIBED] for a channel this build does not know. */
    private val built = ConcurrentHashMap<String, Any>()
    private val UNDESCRIBED = Any()

    /**
     * The descriptor of a wire channel, or null when this build does not know it. A descriptor is fixed for
     * the build, and the session loop asks for every channel on each pass, so each is built once.
     */
    fun describe(wire: String): PanelAssistantChannelDescriptor? =
        built.getOrPut(wire) { build(wire) ?: UNDESCRIBED } as? PanelAssistantChannelDescriptor

    private fun build(wire: String): PanelAssistantChannelDescriptor? {
        FAMILY.matchEntire(wire)?.let { match ->
            val family = match.groupValues[1]
            val relay = family == "relay"
            return PanelAssistantChannelDescriptor(
                channel = wire,
                platform = if (relay) "switch" else "light",
                translationKey = family,
                uniqueSuffix = wire,
                kind = if (relay) PanelAssistantValueKind.BOOLEAN else PanelAssistantValueKind.LIGHT,
                family = family,
                index = match.groupValues[2].toInt(),
            )
        }
        HAND_WRITTEN[wire]?.let { return it }
        return registryByChannel[wire]?.let(::describe)
    }

    /** The descriptor of a registry-backed channel, from its declared facts alone. */
    internal fun describe(spec: SettingSpec): PanelAssistantChannelDescriptor {
        val entity = requireNotNull(spec.ha)
        val facts = entity.facts
        val platform = entity.component
        val kind = when (platform) {
            "switch", "binary_sensor" -> PanelAssistantValueKind.BOOLEAN
            "number" -> PanelAssistantValueKind.NUMBER
            "select" -> PanelAssistantValueKind.OPTION
            "light" -> PanelAssistantValueKind.LIGHT
            "text" -> PanelAssistantValueKind.TEXT
            else -> when {
                facts.options != null -> PanelAssistantValueKind.OPTION
                facts.text -> PanelAssistantValueKind.TEXT
                else -> PanelAssistantValueKind.NUMBER
            }
        }
        return PanelAssistantChannelDescriptor(
            channel = entity.channel,
            platform = platform,
            translationKey = entity.channel,
            uniqueSuffix = entity.objectSuffix,
            kind = kind,
            entityCategory = facts.entityCategory,
            enabledDefault = spec.haExposedByDefault,
            deviceClass = facts.deviceClass,
            unit = facts.unit,
            stateClass = facts.stateClass,
            forceUpdate = entity.periodicRefresh,
            choices = facts.options,
            min = facts.min,
            max = facts.max,
            step = facts.step,
        )
    }

    private fun wireOnly(channel: String, platform: String, choices: List<ChannelOption>? = null) =
        PanelAssistantChannelDescriptor(
            channel = channel,
            platform = platform,
            translationKey = channel,
            // The retired discovery tombstone's id, so a later entity attaches to the same suffix.
            uniqueSuffix = channel,
            kind = if (choices == null) PanelAssistantValueKind.BOOLEAN else PanelAssistantValueKind.OPTION,
            entityCategory = "config",
            // No MQTT entity exists for these today (protocol section 15).
            enabledDefault = false,
            choices = choices,
        )

    /**
     * The hardware button event channel, whose event types are the MQTT event types this profile adds to the
     * common set. The wire carries lowercase codes (protocol codes are lowercase); each code's label is the
     * `KEYCODE_…` name the button bus emits and MQTT publishes.
     */
    fun buttonDescriptor(profileEventTypes: Set<String> = emptySet()) = PanelAssistantChannelDescriptor(
        channel = "button", platform = "event", translationKey = "button", uniqueSuffix = "button",
        kind = PanelAssistantValueKind.EVENT,
        choices = mqttButtonEventTypes(profileEventTypes).map { ChannelOption(it.lowercase(Locale.ROOT), it) },
    )

    /** A value set whose MQTT payloads are already the codes. */
    private fun sameAsCode(codes: List<String>) = codes.map { ChannelOption(it, it) }

    private val HAND_WRITTEN: Map<String, PanelAssistantChannelDescriptor> = listOf(
        PanelAssistantChannelDescriptor(
            channel = "camera_enabled", platform = "camera", translationKey = "camera",
            uniqueSuffix = "camera", kind = PanelAssistantValueKind.BOOLEAN,
        ),
        PanelAssistantChannelDescriptor(
            channel = "led", platform = "light", translationKey = "led", uniqueSuffix = "led",
            kind = PanelAssistantValueKind.LIGHT, choices = sameAsCode(listOf("none", "strobe", "blink", "pulse")),
        ),
        PanelAssistantChannelDescriptor(
            channel = "buttons", platform = "light", translationKey = "buttons", uniqueSuffix = "buttons",
            kind = PanelAssistantValueKind.LIGHT,
        ),
        // Native only: MQTT has no media_player platform (protocol section 18).
        PanelAssistantChannelDescriptor(
            channel = "media", platform = "media_player", translationKey = "media", uniqueSuffix = "media",
            kind = PanelAssistantValueKind.MEDIA,
        ),
        PanelAssistantChannelDescriptor(
            channel = "navigate", platform = "text", translationKey = "navigate", uniqueSuffix = "navigate",
            kind = PanelAssistantValueKind.TEXT,
        ),
        PanelAssistantChannelDescriptor(
            channel = "home_dashboard", platform = "text", translationKey = "home_dashboard",
            uniqueSuffix = "home_dashboard", kind = PanelAssistantValueKind.TEXT, entityCategory = "config",
        ),
        PanelAssistantChannelDescriptor(
            channel = "reload", platform = "button", translationKey = "reload",
            uniqueSuffix = "reload", kind = PanelAssistantValueKind.BUTTON,
        ),
        PanelAssistantChannelDescriptor(
            channel = "reboot", platform = "button", translationKey = "reboot",
            uniqueSuffix = "reboot", kind = PanelAssistantValueKind.BUTTON, deviceClass = "restart",
        ),
        PanelAssistantChannelDescriptor(
            channel = "storage_health", platform = "sensor", translationKey = "storage_health",
            uniqueSuffix = "storage_health", kind = PanelAssistantValueKind.OPTION, entityCategory = "diagnostic",
            choices = sameAsCode(StorageHealthSeverity.entries.map { it.name.lowercase(Locale.ROOT) }),
        ),
        PanelAssistantChannelDescriptor(
            channel = "zigbee_gateway_health", platform = "sensor", translationKey = "zigbee_gateway_health",
            uniqueSuffix = "zigbee_gateway_health", kind = PanelAssistantValueKind.OPTION,
            entityCategory = "diagnostic", choices = sameAsCode(ZigbeeHealthState.entries.map { it.wireValue }),
        ),
        // The value is the snapshot URL; Home Assistant fetches a frame from it only when somebody looks.
        PanelAssistantChannelDescriptor(
            channel = "camera_snapshot", platform = "image", translationKey = "camera_snapshot",
            uniqueSuffix = "camera_snapshot", kind = PanelAssistantValueKind.IMAGE,
        ),
        buttonDescriptor(),
        wireOnly("watchdog", "switch"),
        wireOnly("silence_boot_chime", "switch"),
        wireOnly("prevent_idle_dim", "switch"),
        wireOnly("zigbee_router", "switch"),
    ).associateBy { it.channel } + SoftwareComponent.entries.associate { component ->
        val wire = "update_${component.wire}"
        wire to PanelAssistantChannelDescriptor(
            channel = wire,
            platform = "update",
            translationKey = wire,
            uniqueSuffix = SoftwareUpdateEntities.uniqueId("", component).removePrefix("_"),
            kind = PanelAssistantValueKind.UPDATE,
            entityCategory = "config",
        )
    }
}
