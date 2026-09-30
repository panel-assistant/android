package io.github.maxlyth.hapaneld.config

import io.github.maxlyth.hapaneld.audio.MicrophoneGain
import io.github.maxlyth.hapaneld.parseKioskCompanionPackages
import io.github.maxlyth.hapaneld.i18n.AppLocale
import io.github.maxlyth.hapaneld.util.AndroidInput
import io.github.maxlyth.hapaneld.util.BrokerEndpoint
import io.github.maxlyth.hapaneld.util.DashboardPath
import io.github.maxlyth.hapaneld.util.DashboardTheme
import io.github.maxlyth.hapaneld.util.LogShipEndpoint
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.Locale

/** A legacy serialized JSON null is not an area request. Keep every other name unchanged. */
internal fun isLiteralNullAreaName(raw: String): Boolean = raw.trim().equals("null", ignoreCase = true)

internal fun normalizedHaAreaName(raw: String): String =
    raw.trim().takeUnless(::isLiteralNullAreaName).orEmpty()

/**
 * The authoritative, ordered list of ha-paneld settings. Adding a setting here makes it appear in the
 * HTTP config API + generated form, in bundles + revisions, and (when [SettingSpec.ha] is set and the
 * panel exposes it) as a Home Assistant entity — with no per-key wiring in three places.
 *
 * Coverage note: this first pass registers the settings whose current value is **Config-backed**
 * (read directly from durable config state). Controller-sourced entities (touch sound, CPU profile,
 * network ADB, zigbee), option-driven selects (navbar/cpu), the core controls (screen/led/volume/
 * navigate), publish-only sensors, dynamic relay/button-LED entities, and the action buttons are
 * published by a separate discovery pass (they are not "settings" or need a runtime value provider);
 * they are gated in by capability at publish time. See the discovery rewrite (Stage C).
 */
object SettingsRegistry {

    /**
     * The specs the generated Configure form is built from.
     *
     * Settable settings plus the read-only HA sensors, which carry no editable value but still render an
     * expose pip. `hidden` specs are excluded outright: that is what makes a setting API-only, and with
     * every spec of a group hidden the group has no fields and therefore renders no card at all. The
     * Voice group relies on exactly that to ship unsurfaced, so this predicate is a release-visibility
     * decision and not merely a rendering detail.
     */
    fun schemaVisibleSpecs(): List<SettingSpec> =
        SPECS.filter { (!it.readOnly || it.ha != null) && !it.hidden }

    /**
     * [schemaVisibleSpecs] in presentation order for this panel: each spec whose
     * [SettingSpec.promoteWhen] holds moves to the front of its group, ahead of the specs it would
     * otherwise follow; every other spec keeps its declared position.
     */
    fun schemaVisibleSpecs(caps: Capabilities): List<SettingSpec> {
        val specs = schemaVisibleSpecs()
        val promoted = specs.filter { it.promoteWhen(caps) }
        if (promoted.isEmpty()) return specs
        val out = ArrayList<SettingSpec>(specs.size)
        specs.forEach { spec ->
            if (spec in promoted) return@forEach
            promoted.filter { it.group == spec.group && it !in out }.forEach(out::add)
            out.add(spec)
        }
        promoted.filter { it !in out }.forEach(out::add)
        return out
    }

    /** Bump whenever the persisted shape changes; drives bundle migration. */
    const val SCHEMA = 12
    const val MAX_PANEL_ID_CHARS = 63
    const val DEFAULT_SILENCE_BOOT_CHIME = true
    const val DEFAULT_MQTT_ADDRESS_FAMILY = "Automatic"
    const val DEFAULT_UI_LANGUAGE = "auto"
    val UI_LANGUAGES: List<String> = listOf(DEFAULT_UI_LANGUAGE) + AppLocale.RELEASE_LOCALES

    /**
     * Lowest automatic screen percentage the actuator can actually distinguish, and therefore the floor
     * of the Minimum level control. Raw brightness is floored at BrightnessController.MIN_VISIBLE (10 of
     * 255) so a dim command can never blank the panel, which means every percentage below this one
     * resolves to that same raw level: the control would appear to move while the screen did not.
     *
     * Derived as ceil(MIN_VISIBLE * 100 / 255) = ceil(3.92) = 4. Kept as a literal rather than computed
     * from the controller so the config package does not depend on the control package; the equality is
     * enforced by test instead, which fails if either number moves.
     */
    const val MINIMUM_AUTOMATIC_PERCENT = 4

    /**
     * Highest automatic floor the Minimum level control offers. 99 rather than 100 because at 100 the
     * floor reaches full brightness and automatic control has no range left to act in — enabled, but
     * indistinguishable from switched off. Unlike the lower bound this is a judgement, not a derivation.
     */
    const val MAX_AUTOMATIC_MINIMUM_PERCENT = 99

    /**
     * Schema 6 carries the adaptive response under a NEW key rather than redefining the schema-5 one.
     *
     * The scales are incompatible: schema 5 read 100 as double response, schema 6 reads it as full
     * response. Older builds deliberately tolerate a newer bundle and keep the values they recognise, so
     * reusing the key would let a rollback or a cross-version import read a schema-6 number under
     * schema-5 meaning and then make that reading durable at its next migration. A distinct key cannot be
     * misread: a schema-5 build simply does not know it and falls back to its own stored value.
     */
    const val RESPONSE_PERCENT_KEY = "auto_brightness_response_percent"

    /**
     * Keys earlier builds wrote into backups and bundles that no current setting owns. A restore drops
     * them rather than refusing the whole backup: the voice assistant's MQTT switch and state sensor
     * were removed without a schema change, so a backup from the build before still carries them.
     */
    val RETIRED_KEYS: Set<String> = setOf(
        "voice_state",
        "${HA_EXPOSE_PREFIX}voice_enabled",
        "${HA_EXPOSE_PREFIX}voice_state",
    )

    /** The retired schema-5 key. Read only by migration, and never registered as a current setting. */
    const val LEGACY_SENSITIVITY_KEY = "auto_brightness_sensitivity"

    /** The sensitivity default that schema 5 and earlier stored, in that era's doubled-gain units. */
    const val LEGACY_NEUTRAL_SENSITIVITY = 50

    /**
     * Lowest live-store schema that can carry a schema-5 sensitivity choice worth preserving.
     *
     * Verified against release history rather than assumed: v0.9.4 shipped `SCHEMA = 1` with no
     * sensitivity setting at all, while v0.9.5-rc1 and v0.9.5 shipped `SCHEMA = 2` with the setting and
     * called `migrateLiveStore()` at startup. Any panel that has the setting has therefore booted 0.9.5
     * or later and been stamped with a schema marker of at least 2. A store still presenting the
     * schema-1 sentinel is either genuinely fresh or predates the setting, and in both cases has no
     * previous response to carry forward.
     */
    const val FIRST_SCHEMA_WITH_SENSITIVITY = 2
    private const val MAX_PANEL_ID_INPUT_CHARS = 255
    private val HA_ILLUMINANCE_ENTITY = Regex("^sensor\\.[a-z0-9_]+$")

    /** A wake-word id as `voice_wake_words` and `voice_pipelines` name it: a bundled or imported model. */
    private val VOICE_WAKE_WORD_ID = Regex("^[a-z][a-z0-9_]{0,63}$")
    private const val MAX_VOICE_WAKE_WORDS = 32

    /** `voice_wake_words`: a JSON array of wake-word ids with no duplicate. Which ids exist depends on the
     *  models the panel holds, which the listener checks as it arms; only the shape is checked here.
     *  Re-serializes to a canonical compact form so a stored value round-trips byte-identically. */
    private fun validateVoiceWakeWords(raw: String): Validation {
        val array = try {
            JSONArray(raw)
        } catch (e: JSONException) {
            return Validation.Bad("voice_wake_words: expected a JSON array of wake-word model ids")
        }
        if (array.length() > MAX_VOICE_WAKE_WORDS) {
            return Validation.Bad("voice_wake_words: at most $MAX_VOICE_WAKE_WORDS wake words")
        }
        val ids = ArrayList<String>(array.length())
        for (index in 0 until array.length()) {
            val entry = array.opt(index) as? String
                ?: return Validation.Bad("voice_wake_words: every entry must be a string")
            if (!VOICE_WAKE_WORD_ID.matches(entry)) {
                return Validation.Bad("voice_wake_words: \"$entry\" is not a wake-word id")
            }
            if (entry in ids) return Validation.Bad("voice_wake_words: duplicate wake word \"$entry\"")
            ids += entry
        }
        return Validation.Ok(JSONArray(ids).toString())
    }

    /** `voice_pipelines`: a JSON object mapping a wake-word id to a pipeline id (blank = the Home
     *  Assistant preferred pipeline). Re-serializes with sorted keys so the persisted value is stable
     *  regardless of the request's key order. */
    private fun validateVoicePipelines(raw: String): Validation {
        val obj = try {
            JSONObject(raw)
        } catch (e: JSONException) {
            return Validation.Bad("voice_pipelines: expected a JSON object of wake word to pipeline id")
        }
        val normalized = JSONObject()
        for (key in obj.keys().asSequence().sorted()) {
            if (!VOICE_WAKE_WORD_ID.matches(key)) {
                return Validation.Bad("voice_pipelines: \"$key\" is not a wake-word id")
            }
            val value = obj.opt(key) as? String
                ?: return Validation.Bad("voice_pipelines: $key: expected a string pipeline id")
            normalized.put(key, value)
        }
        return Validation.Ok(normalized.toString())
    }

    // Closed value sets: the stable wire code beside the label MQTT and the stored setting have always
    // used. Declared ahead of SPECS, which reads them during initialisation.
    val NAVBAR_OPTIONS = listOf(
        ChannelOption("off", "Off"),
        ChannelOption("always_on", "Always on"),
        ChannelOption("swipe_reveal", "Swipe reveal"),
        ChannelOption("native", "Native"),
    )
    val CPU_GOVERNOR_OPTIONS = listOf(
        ChannelOption("performance", "Performance"),
        ChannelOption("efficiency", "Efficiency"),
        ChannelOption("auto", "Auto"),
    )

    /** Release channels: the stored setting holds the code, MQTT shows the label. */
    val RELEASE_CHANNEL_OPTIONS = listOf(ChannelOption("stable", "Stable"), ChannelOption("prerelease", "Pre-release"))

    val SPECS: List<SettingSpec> = listOf(
        // ---- Identity ----------------------------------------------------------------------------
        SettingSpec(
            key = "panel_id", type = SettingType.STRING, group = "Identity",
            tier = Tier.BASIC, summary = "Stable id used in this panel's entity IDs.",
            label = "Panel ID", default = "", scope = Scope.IDENTITY,
            help = "Stable id used in entity IDs and MQTT topics (lowercase, digits, underscores; 63 characters maximum).",
            validate = { raw ->
                if (raw.length > MAX_PANEL_ID_INPUT_CHARS) {
                    Validation.Bad("panel_id: must be at most $MAX_PANEL_ID_CHARS characters")
                } else {
                    val slug = raw.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "_").trim('_')
                    if (slug.isEmpty()) Validation.Bad("panel_id: must contain a letter or digit")
                    else if (slug.length > MAX_PANEL_ID_CHARS) {
                        Validation.Bad("panel_id: must be at most $MAX_PANEL_ID_CHARS characters")
                    } else Validation.Ok(slug)
                }
            },
        ),
        SettingSpec(
            key = "friendly_name", type = SettingType.STRING, group = "Identity",
            shortDescriptionUsefulInPopover = true,
            tier = Tier.BASIC, summary = "Name Home Assistant shows for this panel.",
            label = "Friendly name", default = "", scope = Scope.IDENTITY,
            maxChars = 128,
        ),
        SettingSpec(
            key = "manufacturer", type = SettingType.STRING, group = "Identity",
            tier = Tier.ADVANCED, summary = "Override the manufacturer on the Home Assistant device card.",
            label = "Manufacturer", default = "", scope = Scope.DEVICE,
            maxChars = 128,
            help = "HA device-card manufacturer override (blank = profile/auto).",
        ),
        SettingSpec(
            key = "model", type = SettingType.STRING, group = "Identity",
            tier = Tier.ADVANCED, summary = "Override the model on the Home Assistant device card.",
            label = "Model", default = "", scope = Scope.DEVICE,
            maxChars = 128,
            help = "HA device-card model override (blank = profile/auto).",
        ),

        // LAST in the Identity card on purpose: this is the group's only
        // picker, and a select does not share the text fields' column metrics — mid-card it broke the
        // column line, so it sits at the card's edge where the difference reads as intentional. The
        // columns themselves are deliberately left alone for now; only the order changed.
        SettingSpec(
            key = "ha_area", type = SettingType.STRING, group = "Identity",
            tier = Tier.BASIC, summary = "Home Assistant area this panel belongs to.",
            label = "Area in Home Assistant", default = "", picker = "ha_area", scope = Scope.DEVICE,
            // liveApply with a no-op dispatcher on purpose: this key needs NO runtime rebuild (discovery
            // reads it at next publish; the HA write-back is a post-commit server side effect) — but as
            // a non-live key it dragged a FULL network reconfigure behind every wizard dashboard answer,
            // and the HTTP server blip failed the user's next request on hardware.
            liveApply = true,
            maxChars = 128,
            validate = { Validation.Ok(normalizedHaAreaName(it)) },
            help = "Where this panel lives, by Home Assistant area name. This can only be changed in " +
                "ha-paneld if your HA user has admin permissions, otherwise change it on your device in HA.",
        ),
        // ---- MQTT --------------------------------------------------------------------------------
        SettingSpec(
            key = "mqtt_broker", type = SettingType.STRING, group = "MQTT",
            shortDescriptionUsefulInPopover = true,
            tier = Tier.ADVANCED, summary = "MQTT broker address. Blank finds Home Assistant automatically.",
            label = "Broker URL", default = "", scope = Scope.PORTABLE,
            maxChars = 2_048,
            validate = { raw ->
                if (raw.isBlank()) Validation.Ok(raw)
                else BrokerEndpoint.normalize(raw)?.let(Validation::Ok)
                    ?: Validation.Bad("mqtt_broker: expected a tcp://, mqtt://, ssl://, mqtts://, or tls:// broker URL")
            },
        ),
        SettingSpec(
            key = "mqtt_user", type = SettingType.STRING, group = "MQTT",
            tier = Tier.ADVANCED, summary = "MQTT username for this panel.",
            label = "Username", default = "", scope = Scope.DEVICE,
            maxChars = 256,
        ),
        SettingSpec(
            key = "mqtt_password", type = SettingType.PASSWORD, group = "MQTT",
            tier = Tier.ADVANCED, summary = "MQTT password. Blank keeps the current one.",
            label = "Password", default = "", scope = Scope.DEVICE, secret = true,
            maxChars = 4_096,
        ),
        SettingSpec(
            key = "mqtt_address_family", type = SettingType.ENUM, group = "MQTT",
            tier = Tier.ADVANCED, summary = "Use IPv4, IPv6 or both to reach MQTT and Home Assistant.",
            label = "MQTT + Home Assistant WebSocket address family", default = DEFAULT_MQTT_ADDRESS_FAMILY,
            scope = Scope.DEVICE,
            options = listOf(DEFAULT_MQTT_ADDRESS_FAMILY, "Prefer IPv4", "Force IPv4"),
            help = "Applies to the MQTT broker connection and the panel's Home Assistant WebSocket " +
                "connections; plain HTTPS reads always try every published address. Automatic learns " +
                "a working route and falls back between IPv6 and IPv4. Prefer IPv4 keeps IPv6 as a " +
                "fallback; Force IPv4 rejects endpoints with no IPv4 address.",
        ),

        // ---- Behaviour ---------------------------------------------------------------------------
        SettingSpec(
            key = "auto_sleep_source", type = SettingType.ENUM, group = "Behaviour",
            tier = Tier.BASIC, summary = "What counts as presence: proximity sensor, area or touch.",
            label = "Auto-sleep activity source", default = "panel", scope = Scope.DEVICE,
            liveApply = true, options = listOf("panel", "home_assistant", "touch"),
            help = "Use the panel’s calibrated proximity sensor, Home Assistant Area devices, or touch inactivity.\n\n- Presence modes pause if their source is unavailable.\n- Touch inactivity needs no presence setup.",
        ),
        SettingSpec(
            key = "auto_sleep_touch_delay_seconds", type = SettingType.INT, group = "Behaviour",
            tier = Tier.BASIC, summary = "Seconds without a touch before the screen goes off.",
            label = "Touch inactivity delay (seconds)", default = "30", min = 5.0, max = 86_400.0,
            step = 1.0, scope = Scope.DEVICE,
            help = "When Touch inactivity is selected, switch the screen fully off after this many seconds without a touch.",
        ),
        SettingSpec(
            key = "auto_sleep", type = SettingType.BOOL, group = "Behaviour",
            tier = Tier.BASIC, summary = "Turn the screen off when nobody is around or after inactivity.",
            label = "Auto sleep", default = "false", scope = Scope.DEVICE,
            liveApply = true,
            help = "Switch the screen off after the selected presence mode’s learned delay or the chosen touch inactivity delay. Manual screen control remains separate.",
            ha = haEntity("switch", "auto_sleep", "Auto sleep") {
                commandTopic()
                stateTopic()
                icon("mdi:sleep")
                entityCategory("config")
            },
        ),
        SettingSpec(
            key = "navbar_mode", type = SettingType.ENUM, group = "Behaviour",
            tier = Tier.BASIC, summary = "Show a soft navigation bar on panels without a native one.",
            label = "Navbar mode", default = "Off",
            liveApply = true,
            options = NAVBAR_OPTIONS.map { it.label },
            // Native is offered only where the firmware draws its own bar. Everywhere else it would be
            // a way to end up with no navigation at all, so it is withheld rather than merely discouraged.
            optionRequires = mapOf("Native" to { caps: Capabilities -> caps.hasNativeNavbar }),
            derivedDefault = ::navbarModeDefault,
            help = "Soft on-screen navigation bar for panels with no native navbar. **Native** leaves navigation to the panel's own Android bar and draws nothing.\n\nHiding the Android system bars, from the built-in renderer's fullscreen setting or the Android dashboard lock, still hides a native bar.",
            ha = haEntity("select", "navbar", "Navbar") {
                commandTopic()
                stateTopic()
                capabilityOptions(NAVBAR_OPTIONS)
                icon("mdi:gesture-tap-button")
                entityCategory("config")
            },
        ),
        SettingSpec(
            key = "wake_on_wave", type = SettingType.BOOL, group = "Behaviour",
            tier = Tier.BASIC, summary = "Wave a hand near the panel to wake it.",
            label = "Wake on wave", default = "false", scope = Scope.PORTABLE,
            liveApply = true,
            help = "Wake after a calibrated clear-to-near-to-clear wave. Set up proximity on the panel; touch-to-wake remains available.",
            availableWhen = { it.hasProximity },
            ha = haEntity("switch", "wake_on_wave", "Wake on wave") {
                commandTopic()
                stateTopic()
                icon("mdi:gesture-tap")
                entityCategory("config")
            },
        ),
        SettingSpec(
            key = "kiosk_lock", type = SettingType.BOOL, group = "Behaviour",
            tier = Tier.ADVANCED, summary = "Root only. Bring the dashboard back when another app opens.",
            label = "Lock Android to dashboard (experimental)", default = "false", scope = Scope.DEVICE,
            liveApply = true,
            help = "Root-only casual-use lock. Hides Android system bars and returns to the selected dashboard within about 3 seconds when another app or Recents opens. It does not hide Home Assistant navigation.\n\nRelease it:\n\n- here, or from Home Assistant\n- through adb\n- with 7 rapid top-left taps\n- during the 60-second unlocked window after reboot",
            ha = haEntity("switch", "kiosk_lock", "Android dashboard lock") {
                commandTopic()
                stateTopic()
                icon("mdi:lock")
                entityCategory("config")
            },
        ),
        SettingSpec(
            key = "watchdog_enabled", type = SettingType.BOOL, group = "Behaviour",
            shortDescriptionUsefulInPopover = true,
            tier = Tier.ADVANCED, summary = "Relaunch the dashboard app if it dies or stays backgrounded.",
            label = "App watchdog", default = "false", scope = Scope.PORTABLE,
            liveApply = true,
        ),
        SettingSpec(
            key = "kiosk_companion_packages", type = SettingType.STRING, group = "Behaviour",
            tier = Tier.ADVANCED, summary = "Apps the lock leaves in front, by package name.",
            label = "Apps the lock allows", default = "", scope = Scope.DEVICE,
            maxChars = 512,
            // Not liveApply: there is no side effect to route. The return loop reads this value on
            // every poll, so a saved change is in force within one poll without a rebuild or restart.
            help = "Android package names, separated by commas, that the dashboard lock leaves in front " +
                "instead of returning to the dashboard. Use it to reach a companion app's own screens. " +
                "Needs root.",
            validate = { value ->
                val bad = parseKioskCompanionPackages(value).filterNot(AndroidInput::isPackage)
                if (bad.isEmpty()) Validation.Ok(value)
                else Validation.Bad("kiosk_companion_packages: not Android package names: ${bad.joinToString(", ")}")
            },
        ),
        SettingSpec(
            key = "touch_sound", type = SettingType.BOOL, group = "Behaviour",
            tier = Tier.BASIC, summary = "Play the system tap sound.",
            label = "Touch sound", default = "true", scope = Scope.PORTABLE,
            liveApply = true,
            ha = haEntity("switch", "touch_sound", "Touch sound") {
                commandTopic()
                stateTopic()
                icon("mdi:volume-high")
                entityCategory("config")
            },
        ),
        SettingSpec(
            key = "silence_boot_chime", type = SettingType.BOOL, group = "Behaviour",
            tier = Tier.BASIC, summary = "Mute the firmware start-up chime.",
            label = "Silence boot chime", default = DEFAULT_SILENCE_BOOT_CHIME.toString(), scope = Scope.DEVICE,
            liveApply = true,
        ),
        // ---- Display -----------------------------------------------------------------------------
        SettingSpec(
            key = "dark_mode", type = SettingType.BOOL, group = "Display",
            tier = Tier.BASIC, summary = "Dark theme for ha-paneld's own screens and the dashboard default.",
            label = "Dark mode", default = "true", scope = Scope.PORTABLE,
            help = "Themes ha-paneld's own screens and sets the dashboard's default colour scheme on panels without a system dark-mode setting (Android 9 and older).\n\nA theme picked inside Home Assistant overrides the dashboard default; this web UI always follows the viewing browser's own preference.",
            // Panels with a native system dark/light control (Android 10+) follow the OS setting for
            // everything, so the toggle is hidden there.
            availableWhen = { !it.hasSystemDarkMode },
        ),
        SettingSpec(
            key = "auto_brightness_ha_entity", type = SettingType.STRING, group = "Display",
            tier = Tier.ADVANCED, summary = "Use a Home Assistant light sensor instead of the panel's.",
            label = "Ambient light source", default = "", picker = "ha_illuminance", scope = Scope.DEVICE,
            liveApply = true,
            maxChars = 255,
            help = "Blank uses the panel light sensor. Select a Home Assistant illuminance sensor when the panel has no suitable sensor.",
            validate = { raw ->
                when {
                    raw.isBlank() -> Validation.Ok("")
                    HA_ILLUMINANCE_ENTITY.matches(raw) -> Validation.Ok(raw)
                    else -> Validation.Bad(
                        "auto_brightness_ha_entity: expected a sensor entity id such as sensor.room_illuminance",
                    )
                }
            },
        ),
        SettingSpec(
            key = "dashboard_network_warning", type = SettingType.BOOL, group = "Display",
            tier = Tier.ADVANCED, summary = "Warn on the dashboard when the network drops.",
            label = "Show network warning on dashboard", default = "true", scope = Scope.DEVICE,
            help = "Show the network connection warning on this panel's dashboard. Network checks and status remain available when this is off.",
        ),
        SettingSpec(
            key = "auto_brightness", type = SettingType.BOOL, group = "Display",
            tier = Tier.BASIC, summary = "Let the panel set the backlight from ambient light.",
            label = "Auto-brightness", default = "false", scope = Scope.PORTABLE,
            liveApply = true,
            help = "On-panel engine maps a lux stream to the backlight (off = HA drives the screen).",
            ha = haEntity("switch", "auto_brightness", "Auto-brightness") {
                commandTopic()
                stateTopic()
                icon("mdi:brightness-auto")
                entityCategory("config")
            },
        ),
        SettingSpec(
            key = "auto_brightness_minimum_percent", type = SettingType.INT, group = "Display",
            tier = Tier.ADVANCED, summary = "Lowest level auto-brightness will choose.",
            label = "Minimum level",
            default = MINIMUM_AUTOMATIC_PERCENT.toString(),
            min = MINIMUM_AUTOMATIC_PERCENT.toDouble(),
            max = MAX_AUTOMATIC_MINIMUM_PERCENT.toDouble(),
            step = 1.0,
            liveApply = true,
            scope = Scope.DEVICE,
            help = "Lowest automatic screen level as a percentage. Proposals scale from this floor to full brightness; manual brightness can still go lower.",
        ),
        SettingSpec(
            key = RESPONSE_PERCENT_KEY, type = SettingType.INT, group = "Display",
            tier = Tier.ADVANCED, summary = "How strongly the screen follows a real change in room light.",
            label = "Sensitivity", default = "50", min = 0.0, max = 100.0, step = 1.0,
            liveApply = true,
            scope = Scope.DEVICE,
            help = "How much of the difference from the learned ambient-light pattern is applied to the screen, once the engine has decided a change is real.\n\n- Lower values keep the screen closer to its learned daily pattern.\n- While the pattern is still being learned the screen follows the measured light directly.",
        ),

        SettingSpec(
            key = "cpu_governor", type = SettingType.ENUM, group = "System",
            tier = Tier.ADVANCED, summary = "CPU scaling profile until the next reboot.",
            label = "CPU profile", default = "Auto", scope = Scope.DEVICE,
            liveApply = true,
            // Mirrors CpuController.TIERS (kept literal — this package is pure/Android-free).
            options = CPU_GOVERNOR_OPTIONS.map { it.label },
            help = "Live CPU scaling profile until reboot; Auto = the SoC's dynamic governor.",
            transient = true,
            availableWhen = { it.cpuGovernors },
            ha = haEntity("select", "cpu_governor", "CPU profile") {
                commandTopic()
                stateTopic()
                options(CPU_GOVERNOR_OPTIONS)
                icon("mdi:speedometer")
                entityCategory("config")
            },
        ),
        SettingSpec(
            key = "zigbee_router", type = SettingType.BOOL, group = "System",
            tier = Tier.ADVANCED, summary = "Use the on-board Zigbee radio as a router.",
            label = "Zigbee router", default = "false", scope = Scope.DEVICE,
            liveApply = true,
            availableWhen = { it.zigbeePresent },
        ),
        SettingSpec(
            key = "prevent_idle_dim", type = SettingType.BOOL, group = "Behaviour",
            shortDescriptionUsefulInPopover = true,
            tier = Tier.BASIC, summary = "Stop the vendor firmware dimming the backlight at screen-off.",
            label = "Prevent idle dim", default = "true", scope = Scope.PORTABLE,
            liveApply = true,
        ),
        SettingSpec(
            key = "keep_awake", type = SettingType.BOOL, group = "Behaviour",
            shortDescriptionUsefulInPopover = true,
            tier = Tier.ADVANCED, summary = "Keep network and services running while the screen is off.",
            label = "Keep panel responsive", default = "true", scope = Scope.PORTABLE,
        ),
        // Camera is off by default and offered where the device profile declares a camera or Android
        // enumerates one. Only the master switch reaches Home Assistant; the three caps stay local
        // because they bound what a stream URL may ask for rather than being things to operate.
        SettingSpec(
            key = "camera_enabled", type = SettingType.BOOL, group = "Camera",
            tier = Tier.BASIC, summary = "Serve the panel camera as an RTSP stream and JPEG snapshot.",
            label = "Camera", default = "false", scope = Scope.DEVICE,
            // The Configure page turns the words RTSP and JPEG into links to the two addresses; the text is
            // written so it still reads correctly where those links are not rendered.
            help = "Off by default. Serves the panel's camera as a video-only RTSP stream and as a JPEG " +
                "snapshot for Home Assistant to pull; no frames leave the panel unless a client is " +
                "connected, and the panel shows a red light whenever the camera is open.",
            availableWhen = { it.hasCamera },
            ha = haEntity("switch", "camera_enabled", "Camera") {
                commandTopic()
                stateTopic()
                icon("mdi:cctv")
                entityCategory("config")
            },
        ),
        SettingSpec(
            key = "camera_resolution", type = SettingType.ENUM, group = "Camera",
            tier = Tier.BASIC, summary = "Default stream resolution; a stream URL can override it.",
            label = "Resolution", default = "720p", scope = Scope.DEVICE,
            options = listOf("480p", "720p", "1080p"),
            help = "What a stream gets when its URL does not ask for something else. A stream URL can " +
                "override it with ?res=, in either direction.",
            availableWhen = { it.hasCamera },
        ),
        SettingSpec(
            key = "camera_fps", type = SettingType.INT, group = "Camera",
            tier = Tier.ADVANCED, summary = "Default frame rate; a stream URL can override it.",
            label = "Frame rate", default = "15", min = 1.0, max = 30.0, step = 1.0,
            scope = Scope.DEVICE,
            help = "What a stream gets when its URL does not ask for something else. A stream URL can " +
                "override it with ?fps=, in either direction.",
            availableWhen = { it.hasCamera },
        ),
        SettingSpec(
            key = "camera_kbps", type = SettingType.INT, group = "Camera",
            tier = Tier.ADVANCED, summary = "Default bitrate; a stream URL can override it.",
            label = "Bitrate (kbps)", default = "2000", min = 250.0, max = 8000.0, step = 250.0,
            scope = Scope.DEVICE,
            help = "What a stream gets when its URL does not ask for something else. A stream URL can " +
                "override it with ?kbps=, in either direction.",
            availableWhen = { it.hasCamera },
        ),
        SettingSpec(
            key = "camera_exposure", type = SettingType.FLOAT, group = "Camera",
            tier = Tier.ADVANCED, summary = "Exposure bias in stops. 0 leaves auto exposure alone.",
            // Half a stop, not a third. The sensor counts in thirds, but the browser applies this step
            // as a validity grid from `min`, and a third-stop grid starting at -2 does not contain 0 —
            // so the documented default, and +/-1 and +/-2, were all rejected before the form could save.
            // Halves keep every offered value on the grid; the panel rounds each to the sensor's own step.
            label = "Exposure", default = "0", min = -2.0, max = 2.0, step = 0.5,
            scope = Scope.DEVICE,
            help = "Exposure bias in stops, for a camera that reads a room darker or brighter than it " +
                "looks. 0 leaves the camera's own automatic exposure alone; the panel clamps this to " +
                "whatever range the sensor actually supports.",
            availableWhen = { it.hasCamera },
        ),

        // ---- Dashboard ---------------------------------------------------------------------------
        // Which app renders the dashboard + how the built-in renderer connects to HA.
        SettingSpec(
            key = "dashboard_package", type = SettingType.STRING, group = "Dashboard",
            shortDescriptionUsefulInPopover = true,
            tier = Tier.ADVANCED, summary = "App that shows the dashboard. Blank uses the built-in renderer.",
            label = "Dashboard app", default = "", picker = "renderer", scope = Scope.DEVICE,
            maxChars = 255,
            validate = { value ->
                if (AndroidInput.isDashboardTarget(value)) Validation.Ok(value)
                else Validation.Bad("dashboard_package: expected blank, builtin, or an Android package name")
            },
        ),
        SettingSpec(
            key = "dashboard_entity_learning", type = SettingType.BOOL, group = "Dashboard",
            tier = Tier.BASIC, summary = "Only stream the entities this dashboard uses.",
            label = "Entity filtering", default = "false", scope = Scope.PORTABLE,
            help = "Built-in renderer: limit Home Assistant's state stream to dashboard-used entities and learn runtime dependencies. Manage sources and pins in Entities.",
        ),
        SettingSpec(
            key = "home_dashboard", type = SettingType.STRING, group = "Dashboard",
            tier = Tier.BASIC, summary = "Dashboard opened on reload and idle return.",
            label = "Home dashboard", default = "", picker = "ha_dashboard", scope = Scope.DEVICE,
            liveApply = true,
            maxChars = 2_048,
            help = "Dashboard used by reload and idle return.\n\n- **Auto** lets Home Assistant choose.\n- **Custom** accepts a specific view, e.g. `/dashboard-name/tab-name`.",
            validate = { raw ->
                // Blank and bare-root spellings mean "follow the account default" and name no dashboard,
                // so they carry nothing to check. Everything else is canonicalized through the SAME rule
                // the renderer admits routes with, which is the point: a path the form accepts can no
                // longer be one the renderer silently discards as malformed. Whether the dashboard
                // exists is deliberately NOT decided here — the account's list is a runtime fact, the
                // catalogue may be unreachable, and a dashboard may be created after the panel is set
                // up. That case is a visible warning in both pickers, not a rejected save.
                // Every spelling of "follow the account default" is stored as the blank sentinel. `/`,
                // `//` and a bare root with only a query or fragment all mean Auto to the renderer, but
                // stored verbatim they are absent from the dashboard list, so both pickers would reopen
                // in Custom showing a path the panel is not actually using.
                if (DashboardPath.followsAccountDefault(raw)) Validation.Ok("")
                else DashboardPath.canonical(raw, preserveRoute = true)?.let(Validation::Ok)
                    ?: Validation.Bad(
                        "home_dashboard: expected a dashboard path on this Home Assistant, " +
                            "e.g. /lovelace or /dashboard-name/tab-name",
                    )
            },
        ),
        SettingSpec(
            key = "dashboard_theme", type = SettingType.ENUM, group = "Dashboard",
            tier = Tier.BASIC, summary = "Who picks light or dark: Home Assistant, this panel or the room.",
            label = "Dashboard theme", default = DashboardTheme.DEFAULT,
            options = DashboardTheme.OPTIONS, aliases = DashboardTheme.ALIASES,
            scope = Scope.PORTABLE,
            help = "Built-in renderer only.\n\n- **Follow Home Assistant** leaves the dashboard's light/dark choice to Home Assistant, which is what the Dark mode setting supplies a default for.\n- **Dark** and **Light** choose it on this panel when Home Assistant is set to Auto, for a kiosk dashboard with no sidebar to reach the Home Assistant profile page from.\n- **Ambient** chooses Dark or Light from the room's light as the auto-brightness model sees it, and changes only after the room has stayed darker or lighter for a minute; it needs Auto-brightness on, and follows Home Assistant until it has a reading.\n\nAn explicit Light or Dark choice in Home Assistant still wins; use Auto or a separate panel user. Returning to Follow Home Assistant hands the choice back exactly as it was found.",
        ),
        SettingSpec(
            key = "dashboard_fullscreen", type = SettingType.BOOL, group = "Dashboard",
            tier = Tier.ADVANCED, summary = "Hide Android's status and navigation bars behind the dashboard.",
            label = "Hide Android system bars", default = "true", scope = Scope.PORTABLE,
            help = "Built-in renderer only. Hides Android's status and navigation bars while the dashboard is in front; swipe from an edge to reveal them temporarily.\n\nIt does not lock the panel or hide Home Assistant navigation.",
        ),
        SettingSpec(
            key = "dashboard_native_kiosk", type = SettingType.BOOL, group = "Dashboard",
            tier = Tier.ADVANCED, summary = "Ask Home Assistant to hide its own navigation.",
            label = "Hide Home Assistant navigation (native)", default = "true", scope = Scope.PORTABLE,
            help = "Built-in renderer only. After Home Assistant 2026.4.2+ connects, asks its native frontend to hide its navigation.\n\nOn by default; an unsupported or failed request leaves the dashboard unchanged. It does not lock Android or inject CSS into the dashboard.",
        ),
        SettingSpec(
            key = "dashboard_overscroll", type = SettingType.BOOL, group = "Dashboard",
            tier = Tier.ADVANCED,
            label = "Dashboard overscroll effect", default = "false", scope = Scope.PORTABLE, hidden = true,
            help = "Built-in renderer: allow Android's overscroll stretch/glow when a drag runs past " +
                "the top or bottom of the dashboard. Off by default (a wall panel rarely scrolls, and " +
                "the bounce looks out of place). API-only — set true to restore the native effect.",
        ),
        SettingSpec(
            key = "dashboard_idle_return_min", type = SettingType.INT, group = "Dashboard",
            shortDescriptionUsefulInPopover = true,
            tier = Tier.BASIC, summary = "Minutes idle before returning home. 0 turns it off.",
            label = "Idle return to home (min)", default = "0", min = 0.0, max = 1440.0,
            scope = Scope.PORTABLE,
        ),
        SettingSpec(
            key = "ha_url", type = SettingType.STRING, group = "Dashboard",
            tier = Tier.BASIC, summary = "Address of your Home Assistant server.",
            label = "Home Assistant URL", default = "", scope = Scope.PORTABLE,
            maxChars = 2_048,
            help = "Built-in renderer: Home Assistant base URL, e.g. http://homeassistant.local:8123. Blank disables it.",
            validate = { raw ->
                if (raw.isBlank()) Validation.Ok("")
                else normalizeHttpOriginUrl(raw)?.let(Validation::Ok)
                    ?: Validation.Bad("ha_url: expected an http:// or https:// URL with no embedded credentials")
            },
        ),
        SettingSpec(
            key = "ha_token", type = SettingType.PASSWORD, group = "Dashboard",
            tier = Tier.ADVANCED, summary = "Fallback token when browser sign-in is not possible.",
            label = "Long-lived access token", default = "", scope = Scope.DEVICE,
            secret = true,
            help = "Use browser sign-in when possible. This fallback can be created in your Home Assistant user profile.",
        ),
        SettingSpec(
            key = "ha_refresh_token", type = SettingType.PASSWORD, group = "Dashboard",
            tier = Tier.ADVANCED,
            label = "HA refresh token", default = "", scope = Scope.DEVICE, secret = true, hidden = true,
            help = "Internal token state retained for API/config import compatibility. Borrowed Companion and ha-paneld-issued OAuth logins manage it automatically.",
        ),
        SettingSpec(
            key = "ha_token_expiry", type = SettingType.LONG, group = "Dashboard",
            tier = Tier.ADVANCED,
            label = "HA access-token expiry", default = "0", scope = Scope.DEVICE, secret = true, hidden = true,
            min = 0.0,
            help = "Internal OAuth access-token expiry retained with its matching access and refresh tokens during private backup and restore.",
        ),
        SettingSpec(
            key = "ha_client_id", type = SettingType.STRING, group = "Dashboard",
            tier = Tier.ADVANCED,
            label = "HA OAuth client_id", default = "", scope = Scope.DEVICE, hidden = true,
            maxChars = 2_048,
            help = "Internal token provenance retained for API/config import compatibility. Borrowed Companion tokens use the Android Companion client; ha-paneld browser sign-ins use the panel HTTP origin. Not a user preference.",
        ),
        SettingSpec(
            key = "dashboard_entity_overrides", type = SettingType.STRING, group = "Dashboard",
            tier = Tier.ADVANCED,
            label = "Entity filter overrides", default = "", scope = Scope.DEVICE, hidden = true,
            // Explicit pins/exclusions may legitimately cover a large installation. This remains
            // bounded by the config/import envelope, but must not inherit the ordinary text limit.
            maxChars = 1_048_576,
            help = "Backup-only storage for explicit entity pins and forced exclusions managed on the Entities tab.",
        ),
        SettingSpec(
            key = "dashboard_entity_learning_applied", type = SettingType.BOOL, group = "Dashboard",
            tier = Tier.ADVANCED,
            label = "Apply learned entity filter", default = "false", scope = Scope.DEVICE, hidden = true,
            help = "Backup-safe activation latch. Set after a safe empty-install bootstrap or an explicit apply from the Entities API or tab.",
        ),
        SettingSpec(
            key = "dashboard_entity_auto_static", type = SettingType.BOOL, group = "Dashboard",
            tier = Tier.ADVANCED,
            label = "Auto-subscribe dashboard references", default = "true", scope = Scope.PORTABLE, hidden = true,
            help = "Entities found by parsing dashboard configuration may be added automatically. Evidence remains visible when disabled.",
        ),
        SettingSpec(
            key = "dashboard_entity_auto_runtime", type = SettingType.BOOL, group = "Dashboard",
            tier = Tier.ADVANCED,
            label = "Auto-subscribe runtime accesses", default = "true", scope = Scope.PORTABLE, hidden = true,
            help = "Missing entities read through hass.states may be added automatically. Evidence remains visible when disabled.",
        ),
        // Last in the group on purpose where display sizing exists: the display-density control is the
        // preferred way to size a dashboard, so this app-level zoom sits below the connection settings
        // users should actually set. Without root or Shizuku there is no density control, and zoom is
        // the only sizing lever, so it is promoted into that place with help that says so.
        SettingSpec(
            key = "dashboard_zoom", type = SettingType.INT, group = "Dashboard",
            tier = Tier.ADVANCED, summary = "Browser zoom for the dashboard.",
            label = "Zoom (%)", default = "100", min = 50.0, max = 300.0, step = 10.0,
            scope = Scope.DEVICE,
            help = "Browser zoom.",
            promoteWhen = { !it.canSetDisplay },
            promotedHelp = "How you size the dashboard on this panel, which cannot change its display density. " +
                "Lower it to fit more on screen; raise it to enlarge.",
        ),

        // ---- System ------------------------------------------------------------------------------
        SettingSpec(
            key = "ui_language", type = SettingType.ENUM, group = "System",
            tier = Tier.BASIC, summary = "Language of ha-paneld's own interface.",
            label = "Interface language", default = DEFAULT_UI_LANGUAGE,
            scope = Scope.DEVICE,
            options = UI_LANGUAGES,
            help = "Language used by ha-paneld's own interface.\n\n**Automatic** uses an explicit page override first. Configure setting labels and help can then follow the connected Home Assistant user's language; browser, device and English are the remaining fallbacks.",
        ),
        SettingSpec(
            key = "self_update", type = SettingType.BOOL, group = "System",
            tier = Tier.BASIC, summary = "Install ha-paneld releases automatically.",
            label = "ha-paneld auto-update", default = "true", scope = Scope.DEVICE,
            liveApply = true,
            help = "ha-paneld updates itself from GitHub releases on the selected channel. Only shown where verified app install is available.",
            availableWhen = { it.canInstallVerifiedApps },
        ),
        SettingSpec(
            key = "update_channel", type = SettingType.ENUM, group = "System",
            tier = Tier.ADVANCED, summary = "Release channel the self-updater follows.",
            label = "ha-paneld auto-update channel", default = "stable", options = RELEASE_CHANNEL_OPTIONS.map { it.code },
            liveApply = true,
            scope = Scope.DEVICE,
            availableWhen = { it.canInstallVerifiedApps },
        ),
        SettingSpec(
            key = "companion_auto_update", type = SettingType.BOOL, group = "System",
            shortDescriptionUsefulInPopover = true,
            tier = Tier.ADVANCED, summary = "Keep the minimal Companion app installed and current (root).",
            label = "Companion auto-update", default = "false", scope = Scope.DEVICE,
            liveApply = true,
            availableWhen = { it.companionInstalled },
            ha = haEntity("switch", "companion_auto_update", "Companion auto-update") {
                commandTopic()
                stateTopic()
                icon("mdi:cellphone-arrow-down")
                entityCategory("config")
            },
        ),
        SettingSpec(
            key = "companion_update_channel", type = SettingType.ENUM, group = "System",
            tier = Tier.ADVANCED, summary = "Release channel the Companion updater follows.",
            label = "Companion auto-update channel", default = "stable", options = RELEASE_CHANNEL_OPTIONS.map { it.code },
            liveApply = true,
            scope = Scope.DEVICE,
            ha = haEntity("select", "companion_update_channel", "Companion auto-update channel") {
                commandTopic()
                stateTopic()
                options(RELEASE_CHANNEL_OPTIONS)
                icon("mdi:source-branch")
                entityCategory("config")
            },
            availableWhen = { it.companionInstalled },
        ),
        SettingSpec(
            key = "webview_auto_update", type = SettingType.BOOL, group = "System",
            tier = Tier.ADVANCED, summary = "Keep the System WebView on the recommended build (root).",
            label = "WebView auto-update", default = "false", scope = Scope.DEVICE,
            liveApply = true,
            help = "Keep the System WebView on this panel's recommended build (from the ha-paneld mirror), installing a newer one over root on the update check.\n\nOff by default: a WebView swap needs a restart to take effect. Only shown where a recommended build exists (not on Play-updated panels).",
            availableWhen = { it.webViewManaged },
            ha = haEntity("switch", "webview_auto_update", "WebView auto-update") {
                commandTopic()
                stateTopic()
                icon("mdi:web-sync")
                entityCategory("config")
            },
        ),
        SettingSpec(
            key = "launcher_package", type = SettingType.STRING, group = "System",
            tier = Tier.ADVANCED, summary = "App the Launcher button opens.",
            label = "Launcher app", default = "", picker = "package", scope = Scope.DEVICE,
            maxChars = 255,
            help = "App the Launcher button brings forward. Blank auto-picks an app. Selecting Panel admin (ha-paneld) also makes and keeps ha-paneld the Android Home app.",
        ),
        SettingSpec(
            key = "tame_vendor_packages", type = SettingType.STRING, group = "System",
            tier = Tier.ADVANCED,
            label = "Vendor package selections", default = "", scope = Scope.DEVICE, hidden = true,
            maxChars = TamePackagePolicy.MAX_BYTES,
            help = "Backup-only storage for package selections managed by the Vendor packages card.",
            validate = TamePackagePolicy::normalize,
        ),
        SettingSpec(
            key = "network_adb", type = SettingType.BOOL, group = "System",
            tier = Tier.ADVANCED, summary = "Security risk. Keep ADB listening on port 5555 across boots.",
            label = "Network ADB", default = "false", scope = Scope.DEVICE,
            liveApply = true,
            help = "**Security risk:** keeps classic ADB listening on TCP port 5555 across boots and reconnects.\n\nEnable only during active maintenance on a trusted network. If ADB was enabled outside ha-paneld, it must also be disabled there.",
            availableWhen = { it.networkAdb },
            ha = haEntity("switch", "network_adb", "Network ADB") {
                commandTopic()
                stateTopic()
                icon("mdi:adb")
                entityCategory("config")
            },
        ),
        // ---- Voice -------------------------------------------------------------------------------
        // The panel as a Home Assistant voice satellite through Panel Assistant: which wake words it
        // listens for, which Assist pipeline each one runs, and how its microphone is used. Every spec
        // requires a microphone, which the device profile declares only for proven capture.
        SettingSpec(
            key = "voice_enabled", type = SettingType.BOOL, group = "Voice",
            shortDescriptionUsefulInPopover = true,
            tier = Tier.BASIC, summary = "Listen for a wake word and send speech to Assist.",
            label = "Voice assistant", default = "false", scope = Scope.DEVICE,
            liveApply = true,
            availableWhen = { it.hasMicrophone },
        ),
        SettingSpec(
            key = "voice_wake_words", type = SettingType.STRING, group = "Voice", picker = "voice_wake_words",
            tier = Tier.BASIC, summary = "Wake words to listen for.",
            label = "Wake words", default = "[\"okay_nabu\"]", scope = Scope.DEVICE,
            maxChars = 512,
            help = "The wake words to listen for: the bundled Okay Nabu, Hey Jarvis, Hey Mycroft and Alexa, " +
                "and any you import below.",
            availableWhen = { it.hasMicrophone },
            validate = ::validateVoiceWakeWords,
        ),
        SettingSpec(
            key = "voice_pipelines", type = SettingType.STRING, group = "Voice", picker = "voice_pipelines",
            tier = Tier.ADVANCED, summary = "Which Assist pipeline each wake word runs.",
            label = "Wake word pipelines", default = "{}", scope = Scope.DEVICE,
            maxChars = 2_048,
            help = "Which Home Assistant Assist pipeline each wake word runs. A wake word left on the preferred " +
                "pipeline follows whichever pipeline Home Assistant prefers.",
            availableWhen = { it.hasMicrophone },
            validate = ::validateVoicePipelines,
        ),
        SettingSpec(
            key = "voice_audio_source", type = SettingType.ENUM, group = "Voice",
            shortDescriptionUsefulInPopover = true,
            tier = Tier.ADVANCED, summary = "Android audio source for the listener.",
            label = "Audio source", default = "voice_recognition",
            options = listOf("voice_recognition", "mic", "voice_communication"),
            scope = Scope.DEVICE,
            availableWhen = { it.hasMicrophone },
        ),
        SettingSpec(
            key = "voice_sensitivity", type = SettingType.ENUM, group = "Voice",
            tier = Tier.ADVANCED, summary = "How readily the wake word triggers.",
            label = "Wake sensitivity", default = "normal",
            options = listOf("low", "normal", "high"),
            scope = Scope.DEVICE,
            help = "Wake-word detector threshold, applied as an offset to the model's cutoff score.\n\n- **Low** requires a clearer match: fewer false wakes, more likely to miss a quiet or distant call.\n- **Normal** applies no offset.\n- **High** matches more readily: faster to wake, more false triggers.",
            availableWhen = { it.hasMicrophone },
        ),
        SettingSpec(
            key = "voice_mic_gain_db", type = SettingType.INT, group = "Voice",
            tier = Tier.ADVANCED, summary = "Amplify speech sent to Home Assistant for transcription.",
            label = "Microphone gain (dB)", default = "0",
            min = MicrophoneGain.MIN_DB.toDouble(), max = MicrophoneGain.MAX_DB.toDouble(), step = 1.0,
            scope = Scope.DEVICE,
            help = "Amplifies the audio sent to Home Assistant for transcription.\n\nThese panels expose no platform noise suppression or automatic gain control, so a panel heard from across the room may wake reliably and still transcribe poorly: wake-word detection adapts to a quiet signal on its own and speech-to-text does not.\n\n**Raise this** if commands are missed or mistranscribed while the wake word works. Wake-word detection is deliberately left on the unamplified signal.",
            availableWhen = { it.hasMicrophone },
        ),
        // ---- Logging -----------------------------------------------------------------------------
        SettingSpec(
            key = "log_ship_enabled", type = SettingType.BOOL, group = "Logging",
            tier = Tier.ADVANCED, summary = "Send ha-paneld's own log to a collector on your network.",
            label = "Ship logs", default = "false", scope = Scope.DEVICE,
            // "its own logcat" was ambiguous: the Logs tab offers App and System sources, so it read
            // as though both were shipped. Only ha-paneld's own process log leaves the panel.
            help = "Forward ha-paneld's own process log — not the full system log — to a central sink " +
                "(LAN-only, tokens and passwords redacted).",
        ),
        SettingSpec(
            key = "log_ship_system_enabled", type = SettingType.BOOL, group = "Logging",
            tier = Tier.ADVANCED, summary = "Also send Android system logs to the collector.",
            label = "Ship system logs", default = "false", scope = Scope.DEVICE,
            help = "Forward Android system logs to the same sink when Ship logs is on.\n\n- Off by default.\n- Requires TCP or HTTP, and root or the installed helper.\n- Entries are redacted; high-volume output may be dropped.",
        ),
        SettingSpec(
            key = "log_ship_host", type = SettingType.STRING, group = "Logging",
            tier = Tier.ADVANCED, summary = "Log collector address. Blank turns shipping off.",
            label = "Sink host", default = "", scope = Scope.DEVICE,
            maxChars = 253,
            help = "Log-collector host; blank keeps shipping inert. A scheme or :port here is honoured " +
                "(udp://collector, collector:1514).",
        ),
        SettingSpec(
            key = "log_ship_port", type = SettingType.INT, group = "Logging",
            tier = Tier.ADVANCED, summary = "Log collector port.",
            label = "Sink port", default = "514", min = 1.0, max = 65535.0, scope = Scope.DEVICE,
        ),
        SettingSpec(
            key = "log_ship_protocol", type = SettingType.ENUM, group = "Logging",
            tier = Tier.ADVANCED, summary = "Transport to the collector. TCP reports a refused sink.",
            label = "Protocol", default = LogShipEndpoint.DEFAULT_PROTOCOL,
            options = LogShipEndpoint.PROTOCOLS,
            aliases = LogShipEndpoint.ALIASES,
            scope = Scope.DEVICE,
            help = "TCP is the default because it reports a sink that is refusing or unreachable; UDP " +
                "cannot, so a wrong setting there fails silently. Port 514 serves both.",
        ),

        // ---- Sensors -----------------------------------------------------------------------------
        // Live panel readings. These rows carry no editable value; the Configure pip controls whether
        // each reading's existing entity is reported to Home Assistant. Screen and volume deliberately
        // keep their historical commandable entity types/identities rather than creating duplicate
        // numeric sensors: HA natively presents light brightness as a percentage and volume is 0..100%.
        SettingSpec(
            key = "screen", type = SettingType.INT, group = "Sensors",
            tier = Tier.BASIC, summary = "Screen state and brightness.",
            label = "Screen brightness", default = "",
            help = "Current screen state and brightness. Home Assistant presents the light's native 0–255 brightness as a percentage.",
            haExposedByDefault = true,
            ha = haEntity("light", "screen", "Screen", readOnly = true) {
                raw(""""schema":"json","brightness":true,"supported_color_modes":["brightness"]""")
                commandTopic()
                stateTopic()
            },
        ),
        SettingSpec(
            key = "volume", type = SettingType.INT, group = "Sensors",
            tier = Tier.ADVANCED, summary = "Panel media volume.",
            label = "Panel volume", default = "",
            help = "Current panel media volume reported on its native 0–100 percent scale.",
            haExposedByDefault = true,
            ha = haEntity("number", "volume", "Volume", readOnly = true) {
                commandTopic()
                stateTopic()
                range(0, 100, 1)
                raw(""""mode":"slider"""")
                unit("%")
                icon("mdi:volume-high")
            },
        ),
        SettingSpec(
            key = "illuminance", type = SettingType.INT, group = "Sensors",
            shortDescriptionUsefulInPopover = true,
            tier = Tier.BASIC, summary = "Ambient light from the panel sensor.",
            label = "Ambient light", default = "",
            haExposedByDefault = true,
            availableWhen = { it.hasLight },
            ha = haEntity("sensor", "illuminance", "Illuminance", readOnly = true, periodicRefresh = true) {
                stateTopic()
                deviceClass("illuminance")
                unit("lx")
                stateClass("measurement")
            },
        ),
        SettingSpec(
            key = "proximity", type = SettingType.BOOL, group = "Sensors",
            shortDescriptionUsefulInPopover = true,
            tier = Tier.BASIC, summary = "Learned near or far occupancy.",
            label = "Proximity", default = "",
            haExposedByDefault = true,
            availableWhen = { it.hasLearnedProximity },
            ha = haEntity("binary_sensor", "proximity", "Proximity", readOnly = true) {
                stateTopic()
                deviceClass("occupancy")
                raw(""""payload_on":"ON","payload_off":"OFF"""")
            },
        ),
        SettingSpec(
            key = "proximity_level", type = SettingType.INT, group = "Sensors",
            tier = Tier.BASIC, summary = "Learned proximity level, 0 to 100 percent.",
            label = "Proximity level", default = "",
            help = "Normalized learned proximity level from 0 to 100 percent; binary sources report 0 or 100.",
            haExposedByDefault = true,
            availableWhen = { it.hasLearnedProximity },
            ha = haEntity("sensor", "proximity_level", "Proximity level", readOnly = true, periodicRefresh = true) {
                stateTopic()
                unit("%")
                stateClass("measurement")
                icon("mdi:hand-wave")
            },
        ),
        SettingSpec(
            key = "auto_sleep_activity", type = SettingType.BOOL, group = "Sensors",
            tier = Tier.ADVANCED, summary = "Whether auto-sleep is holding the panel awake.",
            label = "Auto-sleep activity", default = "",
            help = "Whether the auto-sleep policy is currently holding the panel awake. The Home Assistant entity provides the activity history timeline.",
            haExposedByDefault = false,
            ha = haEntity("binary_sensor", "auto_sleep_activity", "Auto-sleep activity", readOnly = true) {
                stateTopic()
                raw(""""payload_on":"ON","payload_off":"OFF"""")
                attributesTopic()
                icon("mdi:motion-sensor")
            },
        ),
        SettingSpec(
            key = "temperature", type = SettingType.FLOAT, group = "Sensors",
            shortDescriptionUsefulInPopover = true,
            tier = Tier.BASIC, summary = "Temperature from Android's panel sensor.",
            label = "Temperature", default = "",
            haExposedByDefault = true,
            availableWhen = { it.hasTemperature },
            ha = haEntity("sensor", "temperature", "Temperature", readOnly = true, periodicRefresh = true) {
                stateTopic()
                deviceClass("temperature")
                unit("°C")
                stateClass("measurement")
            },
        ),
        SettingSpec(
            key = "humidity", type = SettingType.FLOAT, group = "Sensors",
            shortDescriptionUsefulInPopover = true,
            tier = Tier.BASIC, summary = "Humidity from Android's panel sensor.",
            label = "Humidity", default = "",
            haExposedByDefault = true,
            availableWhen = { it.hasHumidity },
            ha = haEntity("sensor", "humidity", "Humidity", readOnly = true, periodicRefresh = true) {
                stateTopic()
                deviceClass("humidity")
                unit("%")
                stateClass("measurement")
            },
        ),

        // ---- Diagnostics -------------------------------------------------------------------------
        // Publish-only panel telemetry (readOnly sensors), all OPT-IN (haExposedByDefault=false): the
        // panel stays quiet in HA until a pip is enabled. Values come from [metrics.PanelMetrics] and
        // are pushed on the heartbeat tick with a deadband so they never flap the broker. (Issue #19)
        SettingSpec(
            key = "diag_ip", type = SettingType.STRING, group = "Diagnostics",
            shortDescriptionUsefulInPopover = true,
            tier = Tier.BASIC, summary = "This panel's local IPv4 address.",
            label = "IP address", default = "",
            haExposedByDefault = false,
            ha = haEntity("sensor", "diag_ip", "IP address", readOnly = true) {
                stateTopic()
                icon("mdi:ip-network")
                textValue()
                entityCategory("diagnostic")
            },
        ),
        SettingSpec(
            key = "diag_cpu", type = SettingType.INT, group = "Diagnostics",
            tier = Tier.BASIC, summary = "Overall CPU busy percentage.",
            label = "CPU usage", default = "",
            help = "Overall CPU busy percentage (root/su panels; unavailable on sandbox-walled panels).",
            haExposedByDefault = false,
            ha = haEntity("sensor", "diag_cpu", "CPU usage", readOnly = true, periodicRefresh = true) {
                stateTopic()
                unit("%")
                stateClass("measurement")
                icon("mdi:cpu-64-bit")
                entityCategory("diagnostic")
            },
        ),
        SettingSpec(
            key = "diag_memory", type = SettingType.INT, group = "Diagnostics",
            tier = Tier.BASIC, summary = "Used memory as a percentage.",
            label = "Memory usage", default = "",
            haExposedByDefault = false,
            ha = haEntity("sensor", "diag_memory", "Memory usage", readOnly = true, periodicRefresh = true) {
                stateTopic()
                unit("%")
                stateClass("measurement")
                icon("mdi:memory")
                entityCategory("diagnostic")
            },
        ),
        SettingSpec(
            key = "diag_soc_temp", type = SettingType.FLOAT, group = "Diagnostics",
            tier = Tier.BASIC, summary = "Processor temperature.",
            label = "SoC temperature", default = "",
            help = "System-on-chip temperature (root/su panels; unavailable on sandbox-walled panels).",
            haExposedByDefault = false,
            ha = haEntity("sensor", "diag_soc_temp", "SoC temperature", readOnly = true, periodicRefresh = true) {
                stateTopic()
                deviceClass("temperature")
                unit("°C")
                stateClass("measurement")
                entityCategory("diagnostic")
            },
        ),
        SettingSpec(
            key = "diag_boot", type = SettingType.STRING, group = "Diagnostics",
            tier = Tier.ADVANCED, summary = "When the panel last started.",
            label = "Last boot time", default = "",
            help = "When the panel last booted (a timestamp — HA shows the elapsed uptime).",
            haExposedByDefault = false,
            ha = haEntity("sensor", "diag_boot", "Last boot time", readOnly = true) {
                stateTopic()
                deviceClass("timestamp")
                icon("mdi:clock-start")
                textValue()
                entityCategory("diagnostic")
            },
        ),
        SettingSpec(
            key = "diag_wifi_ssid", type = SettingType.STRING, group = "Diagnostics",
            tier = Tier.ADVANCED, summary = "Current Wi-Fi network name.",
            label = "Wi-Fi network", default = "",
            help = "Current Wi-Fi network name while Wi-Fi is the active connection. This can identify a location and enters HA history when exposed; Android may hide it unless network-information permission is available.",
            haExposedByDefault = false,
            availableWhen = { it.hasWifiSsid },
            ha = haEntity("sensor", "diag_wifi_ssid", "Wi-Fi network", readOnly = true) {
                stateTopic()
                textValue()
                icon("mdi:wifi")
                entityCategory("diagnostic")
            },
        ),
        SettingSpec(
            key = "diag_wifi_rssi", type = SettingType.INT, group = "Diagnostics",
            tier = Tier.ADVANCED, summary = "Current Wi-Fi signal strength.",
            label = "Wi-Fi signal strength", default = "",
            help = "Current Wi-Fi received signal strength in dBm while Wi-Fi is the active connection.",
            haExposedByDefault = false,
            availableWhen = { it.hasWifi },
            ha = haEntity(
                "sensor",
                "diag_wifi_rssi",
                "Wi-Fi signal strength",
                readOnly = true, periodicRefresh = true,
            ) {
                stateTopic()
                deviceClass("signal_strength")
                unit("dBm")
                stateClass("measurement")
                icon("mdi:wifi")
                entityCategory("diagnostic")
            },
        ),
        // Rolling Wi-Fi outage counts — actual loss of the panel's Wi-Fi default network, observed
        // through ConnectivityManager. Never derived from the Home Assistant socket or the MQTT
        // broker, whose restarts are not the network's fault. See control/WifiOutageTracker.kt.
        SettingSpec(
            key = "diag_wifi_outages_24h", type = SettingType.INT, group = "Diagnostics",
            tier = Tier.ADVANCED, summary = "Short Wi-Fi dropouts in the last 24 hours.",
            label = "Wi-Fi outages (24 h)", default = "",
            help = "Short Wi-Fi dropouts in the rolling last 24 hours. Counts loss of the panel's active Wi-Fi connection only — Home Assistant or broker outages are never counted. If the panel had to cap what it stores, the sensor's is_lower_bound attribute says the value is a floor.",
            haExposedByDefault = false,
            availableWhen = { it.hasWifi },
            ha = haEntity(
                "sensor",
                "diag_wifi_outages_24h",
                "Wi-Fi outages (24 h)",
                readOnly = true, periodicRefresh = true,
            ) {
                stateTopic()
                attributesTopic()
                stateClass("measurement")
                icon("mdi:wifi-alert")
                entityCategory("diagnostic")
            },
        ),

        // ---- Room climate (exact authenticated input layouts only) ----------------------------------
        // Real environmental sensors (NOT entity_category=diagnostic), reported by default like the
        // other readings in the Sensors card.
        // Read through the helper or a fixed Shizuku operation; only offered where the layout is proven.
        SettingSpec(
            key = "room_temp", type = SettingType.FLOAT, group = "Sensors",
            tier = Tier.ADVANCED, summary = "Room temperature from the climate sensor.",
            label = "Room temperature", default = "",
            help = "Room air temperature from the panel's supported climate sensor (calibration offset applied).",
            haExposedByDefault = true, availableWhen = { it.hasCht8305 },
            ha = haEntity("sensor", "room_temp", "Room temperature", readOnly = true, periodicRefresh = true) {
                stateTopic()
                deviceClass("temperature")
                unit("°C")
                stateClass("measurement")
            },
        ),
        SettingSpec(
            key = "room_humidity", type = SettingType.INT, group = "Sensors",
            shortDescriptionUsefulInPopover = true,
            tier = Tier.ADVANCED, summary = "Room humidity from the climate sensor.",
            label = "Room humidity", default = "",
            haExposedByDefault = true, availableWhen = { it.hasCht8305 },
            ha = haEntity("sensor", "room_humidity", "Room humidity", readOnly = true, periodicRefresh = true) {
                stateTopic()
                deviceClass("humidity")
                unit("%")
                stateClass("measurement")
            },
        ),
        // Self-heat calibration trim (°C) added to the reported room temperature. Advanced + local-only
        // (no HA entity); the profile carries a baseline and this is an additional user trim. API-settable.
        SettingSpec(
            key = "room_temp_offset", type = SettingType.FLOAT, group = "Sensors",
            tier = Tier.ADVANCED, summary = "Correction added to the room temperature, in °C.",
            label = "Room temperature offset", default = "0", scope = Scope.DEVICE,
            min = -20.0, max = 20.0, step = 0.1, availableWhen = { it.hasCht8305 },
            help = "Correction (°C) added to the reported room temperature — usually negative, since panel " +
                "self-heating reads high. Per-panel (depends on mounting), so not cloned by a fleet push.",
        ),
    )

    private val byKey: Map<String, SettingSpec> = SPECS.associateBy { it.key }

    fun spec(key: String): SettingSpec? = byKey[key]

    /** Registry values accepted by validated configuration flows; excludes publish-only sensors. */
    fun settable(): List<SettingSpec> = SPECS.filterNot { it.readOnly }

    /** Derived subsystem state which no user route accepts as a desired value. It remains settable only
     * so a validated private backup can restore the owning entity-learning state atomically. */
    val machineOwnedKeys: Set<String> = setOf("dashboard_entity_learning_applied")

    /** User-controlled registry values whose public writer is the specialized Entities API rather than
     * the generic `/config` form. The behavioural contract exercises those owners separately. */
    val specializedUserOwnedKeys: Set<String> = setOf(
        "dashboard_entity_overrides",
        "dashboard_entity_auto_static",
        "dashboard_entity_auto_runtime",
    )

    /** Exact reasoned set which the direct form must reject instead of reading and dropping. */
    val directPostExcludedKeys: Set<String> = machineOwnedKeys + specializedUserOwnedKeys

    /** User-submittable SettingsRegistry entries on the direct HTTP configuration route. */
    fun directPostable(): List<SettingSpec> = settable().filterNot { it.key in directPostExcludedKeys }

    /** Every spec with a persisted expose-to-HA decision, including publish-only telemetry. */
    fun haCapable(): List<SettingSpec> = SPECS.filter { it.ha != null }

    /** Settable settings whose durable desired state must be applied through the shared live path. */
    fun liveApplyKeys(): List<String> = SPECS.filter { it.liveApply }.map { it.key }

    /** Config-key prefix for the per-setting "expose to HA" pip toggles. These booleans persist in
     *  SharedPreferences and travel in config bundles, so the string is a stored-format contract —
     *  the sole owner of the parse/construct convention lives here rather than at each call site. */
    const val HA_EXPOSE_PREFIX = "ha_expose_"
    /** Config entities that were implicitly exposed before schema 4; used only to preserve upgrades. */
    val LEGACY_DEFAULT_ON_HA_EXPOSURES: Set<String> = setOf(
        "wake_on_wave", "auto_sleep", "navbar_mode",
        "auto_brightness", "touch_sound", "cpu_governor",
    )

    /** The persisted config key for a spec's expose-to-HA pip — byte-identical to `"ha_expose_$key"`. */
    fun exposureKey(spec: SettingSpec): String = "$HA_EXPOSE_PREFIX${spec.key}"

    /** Resolve an `ha_expose_<key>` parameter name to the HA-capable spec it toggles, or null when the
     *  name is not an exposure key, names no registered setting, or names a setting that is never an HA
     *  entity (`ha == null`). The `ha != null` filter is what gates which specs get an expose pip. */
    fun parseExposure(name: String): SettingSpec? =
        name.takeIf { it.startsWith(HA_EXPOSE_PREFIX) }
            ?.removePrefix(HA_EXPOSE_PREFIX)
            ?.let(::spec)
            ?.takeIf { it.ha != null }
}

/**
 * The navbar mode a panel should start at when the user has never chosen one.
 *
 * Sole definition of that rule: [io.github.maxlyth.hapaneld.resolveNavbarMode] applies it to what a
 * panel has stored, and nothing else re-derives it. The tiers are ordered, and the order is the
 * substance of the rule rather than an implementation detail:
 *
 *  1. **The firmware draws its own bar** → `Native`. The one case where we know a software bar is
 *     unnecessary, as opposed to merely failing to prove it is needed.
 *  2. **The panel has no way out at all** → `Always on`. See below; this MUST precede the visibility
 *     tiers, because the panel that needs it is precisely the one whose firmware claims a bar it does
 *     not usably provide.
 *  3. **Vendor visibility property**, authoritative where present: some PX30 firmware hardcodes
 *     Android's generic `config_showNavigationBar` true while suppressing the vendor's own bar.
 *  4. **`nspanel-pro` by id.** Retained deliberately: it also covers the raw-config read path, where
 *     `resources` is null and [Capabilities.androidShowsNavbar] is therefore unknown, not false.
 *  5. **Android's generic resource**, answering "this panel probably has no usable system bar".
 *
 * **Tier 2, the no-way-out rule, is the one that strands people.** A panel with no native bar, no
 * working Recents and no physical buttons has no navigation affordance of its own, so a default of
 * `Off` leaves the user with no route to Android Settings once ha-paneld holds Home — the same
 * outcome `optionRequires` already withholds `Native` to prevent, reached by another road. `Always on`
 * rather than `Swipe reveal` because a user who is already stranded does not know to swipe.
 *
 * **The rule is deliberately narrow, and [Capabilities.hardwareDeclarationsKnown] is what keeps it
 * narrow.** All three inputs are profile declarations whose `false` is also their unset value, so a
 * default-constructed [Capabilities], or the last-resort profile used when the catalog fails to load,
 * would otherwise satisfy the predicate while knowing nothing — and switch a bar on everywhere. The
 * marker demands that the declarations were actually read from a catalog profile, which is the
 * difference between "this panel has no Recents" and "nobody said".
 *
 * Across the bundled catalogue the rule selects `shelly-wall-display-x2i` alone: `tpa10` also declares
 * no Recents but does declare physical buttons, `wf1589t` has a native bar, and every other profile
 * declares Recents. A panel matching no profile of its own resolves to `generic`, which declares
 * Recents present, so it too is excluded until someone measures otherwise.
 */
internal fun navbarModeDefault(caps: Capabilities): String {
    if (caps.hasNativeNavbar) return "Native"
    if (caps.hardwareDeclarationsKnown && !caps.hasRecents && !caps.hasEvdevButtons) return "Always on"
    when (caps.vendorNavbarProperty?.trim()?.lowercase(java.util.Locale.ROOT)) {
        "false", "0", "no", "off" -> return "Swipe reveal"
        "true", "1", "yes", "on" -> return "Off"
    }
    if (caps.profileId in setOf("nspanel-pro")) return "Swipe reveal"
    return if (caps.androidShowsNavbar == false) "Swipe reveal" else "Off"
}

internal fun normalizeHttpOriginUrl(raw: String): String? {
    val uri = runCatching { java.net.URI(raw.trim()) }.getOrNull() ?: return null
    if (uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank() || uri.userInfo != null ||
        uri.rawQuery != null || uri.rawFragment != null
    ) return null
    return uri.toString().trimEnd('/')
}
