package io.panelassistant.android.device.profile

import io.panelassistant.android.device.LedMechanism
import io.panelassistant.android.hardware.LedTransfer
import io.panelassistant.android.device.SuForm
import io.panelassistant.android.device.ScreenOff

/** Core-owned vocabulary available to untrusted profile files. */
object ProfileMetadata {
    const val SCHEMA = 2
    const val MAX_BYTES = 128 * 1024
    const val MAX_DEPTH = 20
    const val MAX_COLLECTION_SIZE = 512
    const val MAX_STRING_LENGTH = 16 * 1024
    const val MAX_IMPORTED_REVISIONS = 128
    const val MAX_IMPORTED_REVISIONS_PER_ID = 16
    const val MAX_IMPORTED_BYTES = 4 * 1024 * 1024L

    /**
     * The single canonical driver table. Each descriptor owns its vocabulary (id/kind/description),
     * its [ProfileDriverDescriptor.privileged] flag, and its root-helper demand. Every other
     * driver-keyed table is derived from this list rather than re-enumerating the ids, so a driver
     * cannot exist without a helper-authority demand and the two can never disagree.
     *
     * The helper demand is deliberately separate from `privileged`: a privileged driver may satisfy
     * its access through app `su`, a trusted-host operation, or the root helper, and only the last one
     * belongs in `access.helper`.
     */
    val drivers: List<ProfileDriverDescriptor> = listOf(
        ProfileDriverDescriptor("access.android-su", ProfileDriverKind.ACCESS, "Android su invocation and runtime fallback", true, ProfileHelperAuthorityDemand.NONE),
        ProfileDriverDescriptor("access.toolbox-su", ProfileDriverKind.ACCESS, "Toolbox su invocation and runtime fallback", true, ProfileHelperAuthorityDemand.NONE),
        ProfileDriverDescriptor("input.button-backlight", ProfileDriverKind.INPUT, "Helper-backed monochrome button backlight", true, ProfileHelperAuthorityDemand.REQUIRED),
        ProfileDriverDescriptor("input.evdev", ProfileDriverKind.INPUT, "Helper-backed Linux input event watcher", true, ProfileHelperAuthorityDemand.REQUIRED),
        ProfileDriverDescriptor("led.autodetect", ProfileDriverKind.LED, "Probe built-in LED transports including helper fallback", true, ProfileHelperAuthorityDemand.SANDBOX_FALLBACK),
        ProfileDriverDescriptor("led.rk3576-ioctl", ProfileDriverKind.LED, "Built-in /dev/ledjni ioctl with helper fallback", true, ProfileHelperAuthorityDemand.SANDBOX_FALLBACK),
        ProfileDriverDescriptor("led.rk3576-ioctl-daemon", ProfileDriverKind.LED, "Helper-backed /dev/ledjni ioctl protocol", true, ProfileHelperAuthorityDemand.REQUIRED),
        ProfileDriverDescriptor("led.sysfs-daemon", ProfileDriverKind.LED, "Helper-backed supported RGB sysfs protocol", true, ProfileHelperAuthorityDemand.REQUIRED),
        ProfileDriverDescriptor("radio.siliconlabs-host", ProfileDriverKind.RADIO, "Sonoff Silicon Labs gateway directory", true, ProfileHelperAuthorityDemand.NONE),
        ProfileDriverDescriptor("relay.sysfs", ProfileDriverKind.RELAY, "Supported relay sysfs class", true, ProfileHelperAuthorityDemand.NONE),
        ProfileDriverDescriptor("relay.gpio-button-led", ProfileDriverKind.RELAY, "Root-backed button LED GPIO block", true, ProfileHelperAuthorityDemand.NONE),
        ProfileDriverDescriptor("screen.brightness-zero", ProfileDriverKind.SCREEN, "Application-level brightness-zero screen off", false, ProfileHelperAuthorityDemand.NONE),
        ProfileDriverDescriptor("screen.daemon-blpower", ProfileDriverKind.SCREEN, "Helper-backed kernel bl_power screen off", true, ProfileHelperAuthorityDemand.REQUIRED),
        ProfileDriverDescriptor("screen.su-blpower", ProfileDriverKind.SCREEN, "su-backed kernel bl_power screen off", true, ProfileHelperAuthorityDemand.SANDBOX_FALLBACK),
        ProfileDriverDescriptor("screen.keyevent", ProfileDriverKind.SCREEN, "Android sleep/wake keyevent for panels with no backlight class", true, ProfileHelperAuthorityDemand.SANDBOX_FALLBACK),
        ProfileDriverDescriptor("sensor.android", ProfileDriverKind.SENSOR, "Android SensorManager light/proximity inputs", false, ProfileHelperAuthorityDemand.NONE),
        ProfileDriverDescriptor("sensor.cht8305-daemon", ProfileDriverKind.SENSOR, "Authenticated helper allowlisted room-climate input", true, ProfileHelperAuthorityDemand.REQUIRED),
        ProfileDriverDescriptor("sensor.vi530x-daemon", ProfileDriverKind.SENSOR, "Authenticated helper time-of-flight range (started over ioctl, then polled)", true, ProfileHelperAuthorityDemand.REQUIRED),
        ProfileDriverDescriptor("sensor.gpio-proximity", ProfileDriverKind.SENSOR, "Root-backed binary proximity GPIO", true, ProfileHelperAuthorityDemand.REQUIRED),
        ProfileDriverDescriptor("update.webview", ProfileDriverKind.UPDATE, "Core-owned System WebView artifacts: ${ProfileArtifacts.webViews.keys.sorted().joinToString()}", true, ProfileHelperAuthorityDemand.NONE),
    )

    /**
     * Root-helper authority demand for each core driver, derived from the single [drivers] table so it
     * always covers exactly the known drivers and can never drift from their declared demand. See
     * [ProfileDriverDescriptor.helperDemand].
     */
    internal val helperAuthorityDemand: Map<String, ProfileHelperAuthorityDemand> =
        drivers.associate { it.id to it.helperDemand }

    /** Core-owned workflow identifiers selectable by schema 2. Profiles cannot define recipe behavior. */
    val recipes: Set<String> = setOf(
        "nspanel-pro.watchdog-e2big-repair",
        "tpa10.vendor-stack-minimize",
    )

    val schema: ProfileSchemaDescriptor = ProfileSchemaDescriptor(
        schema = SCHEMA,
        maxBytes = MAX_BYTES,
        fields = listOf(
            field("schema", "integer", true, "Profile schema version; schema 2 is the current preview."),
            field("id", "slug", true, "Stable lowercase letters/digits with interior dots or hyphens."),
            field("version", "version", true, "Semantic version of this profile's content."),
            field("display_name", "string", true, "Human-readable panel name."),
            field("soc_class", "string", true, "Human-readable SoC family."),
            field("soc.model", "string", false, "Profile-evidenced SoC model; never runtime-fetched."),
            field("soc.introduced_year", "integer", false, "Public introduction year when evidenced."),
            field("soc.cpu_cores[].architecture", "string", true, "CPU core architecture, for example Arm Cortex-A55."),
            field("soc.cpu_cores[].count", "integer", true, "Number of cores using this architecture."),
            field("metadata.author", "string", true, "Profile author or organization."),
            field("metadata.source", "https-url", false, "Canonical source page for this profile."),
            field("metadata.links[].label", "string", true, "Short display label for a product or reference page."),
            field("metadata.links[].url", "https-url", true, "Display-only external HTTPS link; never fetched by ha-paneld."),
            field("metadata.license", "spdx-expression", true, "SPDX-style license expression."),
            field("metadata.maturity", "enum", true, "Author-declared evidence maturity; not a provenance or trust signal.", listOf("draft", "experimental", "verified")),
            field("metadata.tested_firmware", "string[]", false, "Firmware builds against which this revision was tested."),
            field("metadata.limitations", "string[]", false, "Known profile limitations shown during review."),
            field("requires.min_core_version", "version", false, "Oldest compatible ha-paneld core."),
            field("requires.drivers", "string[]", true, "Core-owned driver ids used by the profile."),
            field("match.priority", "integer", true, "Automatic-match priority from 0 to 1000."),
            field("match.fallback", "boolean", true, "True only for the bundled generic fallback."),
            field("match.any[].priority", "integer", true, "Branch specificity from 0 to 1000; exact identities outrank broad SoC matches."),
            field("match.any[].all[].field", "enum", true, "Immutable build fact.", ProfileFact.entries.map { it.yamlName }),
            field("match.any[].all[].op", "enum", true, "Bounded string comparison.", ProfileMatchOp.entries.map { it.yamlName }),
            field("match.any[].all[].values", "string[]", true, "Lowercase comparison values."),
            field("platform.su_form", "enum", true, "Supported su calling convention.", SuForm.entries.map { it.yamlName }),
            field("platform.app_can_su", "boolean", true, "Whether an ordinary app can attempt su."),
            field("platform.has_recents", "boolean", false, "Whether Android Recents is functional."),
            field("platform.has_native_navbar", "boolean", false, "Usable Android navigation bar: true or false overrides probing; omit to probe firmware visibility."),
            field("platform.launcher.vendor_home_packages", "package-name[]", false, "Vendor apps that register as Home but are not app drawers; the navbar Launcher button skips them."),
            field("hardware.led.mechanism", "enum", true, "Built-in LED route.", LedMechanism.entries.map { it.yamlName }),
            field("hardware.led.transfer", "enum", false, "Core-owned LED transfer function; defaults to identity.", LedTransfer.NAMES.toList()),
            field("hardware.led.gamma", "number", false, "Power-law exponent for transfer gamma, 0.2 through 5.0."),
            field("hardware.led.points", "integer[][]", false, "Control points for transfer points: [request, hardware] pairs from [0, 0] to [255, 255]."),
            field("hardware.led.floor", "integer", false, "Hardware level, 0 through 127, that the lowest non-zero channel value lands on; not with points."),
            field("hardware.backlight.transfer", "enum", false, "Backlight transfer curve from Home Assistant brightness to the backlight.", CURVE_NAMES),
            field("hardware.backlight.route", "enum", false, "Where a declared curve is applied: setting (the firmware pushes Android's brightness setting to the node) or node (ha-paneld is the node's only writer).", BACKLIGHT_ROUTES.toList()),
            field("hardware.backlight.gamma", "number", false, "Power-law exponent for transfer gamma, 0.2 through 5.0."),
            field("hardware.backlight.points", "integer[][]", false, "Control points for transfer points: [request, hardware] pairs from [0, 0] to [255, 255]."),
            field("hardware.backlight.floor", "integer", false, "Hardware level, 0 through 127, that the lowest non-zero brightness lands on; not with points."),
            field("hardware.button_backlight.transfer", "enum", false, "Key-backlight transfer curve; needs has_button_backlight.", CURVE_NAMES),
            field("hardware.button_backlight.gamma", "number", false, "Power-law exponent for transfer gamma, 0.2 through 5.0."),
            field("hardware.button_backlight.points", "integer[][]", false, "Control points for transfer points: [request, hardware] pairs from [0, 0] to [255, 255]."),
            field("hardware.button_backlight.floor", "integer", false, "Hardware level, 0 through 127, that the lowest non-zero level lands on; not with points."),
            field("hardware.screen_off", "enum", true, "Preferred screen-off route.", ScreenOff.entries.map { it.yamlName }),
            field("hardware.has_button_backlight", "boolean", false, "Helper-backed button backlight capability."),
            field("hardware.camera", "boolean", false, "Board carries a usable camera."),
            field("hardware.microphone", "boolean", false, "Board carries a usable microphone; independent of hardware.camera."),
            field("hardware.speaker", "boolean", false, "Board carries a loudspeaker, offered as a media player; defaults to true, so only a board without one declares false."),
            field("hardware.camera_lens_offset_px", "integer", false, "Screen pixels from the top of the active area up to the camera lens centre; centres the camera-in-use light on the lens."),
            field("hardware.zigbee_gateway_dir", "path", false, "Supported Sonoff gateway directory."),
            field("hardware.relay_base", "path", false, "Preferred supported relay sysfs class."),
            field("hardware.relay_base_fallbacks", "path[]", false, "Alternative relay sysfs classes."),
            field("hardware.button_led_gpio_base", "integer", false, "First GPIO in a button LED block."),
            field("hardware.touch_click_gain", "number", false, "Touch-click feedback volume, 0.05 through 1.0."),
            field("sensors.proximity_technology", "string", false, "Author-declared proximity technology."),
            field("sensors.proximity_gpio", "integer", false, "Supported raw binary proximity GPIO."),
            field("sensors.proximity_calibration.presence_supported", "boolean", false, "Defaults true. False requires format 2 with verified wave capability and suppresses ordinary presence."),
            field("sensors.proximity_calibration.format_version", "integer", false, "Calibration format: 1 retains legacy wave behavior; 2 separates presence and optional wave."),
            field("sensors.proximity_calibration.wave", "object", false, "Optional independently validated wave capability, format 2 only."),
            field("sensors.proximity_calibration.wave.pattern", "enum", false, "Single or double wave sequence.", listOf("single", "double")),
            field("sensors.proximity_calibration.wave.clear_raw", "number", false, "Finite independently measured wave clear observation."),
            field("sensors.proximity_calibration.wave.near_raw", "number", false, "Finite distinct wave near observation."),
            field("sensors.proximity_calibration.wave.near_enter", "number", false, "Wave near threshold; default 0.65."),
            field("sensors.proximity_calibration.wave.clear_exit", "number", false, "Wave clear threshold; default 0.30."),
            field("sensors.proximity_calibration.wave.debounce_ms", "integer", false, "Wave stable transition interval; default 150 ms."),
            field("sensors.proximity_calibration.wave.clear_arm_ms", "integer", false, "Wave clear arming interval; default 700 ms."),
            field("sensors.proximity_calibration.wave.minimum_near_ms", "integer", false, "Minimum wave near interval; default 200 ms."),
            field("sensors.proximity_calibration.wave.maximum_near_ms", "integer", false, "Maximum wave near interval; default 4000 ms."),
            field("sensors.proximity_calibration.wave.cooldown_ms", "integer", false, "Accepted wave cooldown; default 1000 ms."),
            field("sensors.proximity_calibration.wave.max_inter_wave_gap_ms", "integer", false, "Maximum gap between double waves, 300-10000 ms; default 1800."),
            field("sensors.proximity_calibration.revision", "integer", false, "Positive revision of measured defaults."),
            field("sensors.proximity_calibration.mode", "enum", false, "Observed representation; runtime samples must corroborate it.", listOf("binary", "ranged")),
            field("sensors.proximity_calibration.clear_raw", "number", false, "Finite clear observation."),
            field("sensors.proximity_calibration.near_raw", "number", false, "Distinct finite near observation."),
            field("sensors.proximity_calibration.verification", "string", false, "Required public evidence description or reference; never fetched."),
            field("sensors.proximity_calibration.near_enter", "number", false, "Normalized near threshold; default 0.65."),
            field("sensors.proximity_calibration.clear_exit", "number", false, "Normalized clear threshold below near_enter; default 0.30."),
            field("sensors.proximity_calibration.debounce_ms", "integer", false, "Stable transition interval, 50-1000 ms; default 150."),
            field("sensors.proximity_calibration.clear_arm_ms", "integer", false, "Clear arming interval, 200-10000 ms; default 700."),
            field("sensors.proximity_calibration.minimum_near_ms", "integer", false, "Minimum gesture interval, 50-2000 ms; default 200."),
            field("sensors.proximity_calibration.maximum_near_ms", "integer", false, "Maximum gesture interval, 200-10000 ms; default 4000."),
            field("sensors.proximity_calibration.cooldown_ms", "integer", false, "Completed gesture cooldown, 200-10000 ms; default 1000."),
            field("sensors.vi530x", "boolean", false, "Expose a helper-started VI530x time-of-flight range source."),
            field("sensors.light_technology", "string", false, "Author-declared ambient light technology."),
            field("sensors.cht8305", "boolean", false, "Expose a supported authenticated room-climate input layout."),
            field("sensors.room_temp_offset_c", "number", false, "Profile baseline temperature correction."),
            field("identity.manufacturer", "string", false, "Default HA manufacturer; null means infer."),
            field("identity.model", "string", false, "Default HA model; null means infer."),
            field("identity.model_label_strategy", "enum", false, "Core-owned local label strategy.", listOf("display-name", "nspanel-product-version")),
            field("input.evdev_buttons[]", "object[]", false, "Supported helper-backed evdev mappings."),
            field("cpu.governors", "map<string,string>", false, "HA CPU tier to Linux governor mapping."),
            field("display.physical_ppi", "integer", false, "Legacy approximate physical pixel density; read as approximate geometry. Not combinable with display.geometry."),
            field("display.geometry[].variant", "string", false, "Variant label, such as 86P."),
            field("display.geometry[].product_version_prefixes", "string[]", false, "Product version prefixes this variant applies to; empty means any."),
            field("display.geometry[].width_px", "integer", true, "Physical display width in pixels; the variant applies only when the live physical mode matches, in either orientation."),
            field("display.geometry[].height_px", "integer", true, "Physical display height in pixels."),
            field("display.geometry[].active_diagonal_in", "number", false, "Active area diagonal in inches; state this or active width and height."),
            field("display.geometry[].active_width_mm", "number", false, "Active area width in millimetres, with active_height_mm."),
            field("display.geometry[].active_height_mm", "number", false, "Active area height in millimetres, with active_width_mm."),
            field("display.geometry[].factory_base_dpi", "integer", false, "The firmware's factory reset logical DPI; a rendering fact, never physical density."),
            field("display.geometry[].evidence", "enum", true, "Where the active size comes from.", listOf("measured", "module", "specification", "approximate")),
            field("display.geometry[].evidence_note", "string", false, "Bounded citation for the evidence."),
            field("provisioning.software.webview.artifact", "enum", false, "Core-owned APK artifact and signer trust root.", ProfileArtifacts.webViews.keys.sorted()),
            field("provisioning.software.companion.max_version", "version", false, "Newest known-good Companion version."),
            field("provisioning.display.density", "integer|strategy", false, "Recommended fixed dpi or nspanel-variant strategy."),
            field("provisioning.display.font_scale", "number", false, "Recommended WebView text scale."),
            field("provisioning.packages[].package", "package-name", true, "Android package whose desired state is declared."),
            field("provisioning.packages[].desired_state", "enum", true, "Desired package state; never implicit consent.", listOf("disabled")),
            field("provisioning.packages[].importance", "enum", true, "Presentation priority only.", listOf("recommended", "optional")),
            field("provisioning.packages[].tags", "string[]", false, "Bounded classifier labels."),
            field("provisioning.packages[].note", "string", false, "Bounded explanatory note; never procedure text."),
            field("provisioning.recipes[].id", "enum", true, "Core-owned provisioning recipe id; no arguments or commands.", recipes.sorted()),
        ),
    )

    private fun field(
        path: String,
        type: String,
        required: Boolean,
        description: String,
        values: List<String> = emptyList(),
    ) = ProfileFieldDescriptor(path, type, required, values, description)
}

/**
 * Whether selecting a driver makes the root helper part of the core provisioning contract.
 *
 * [SANDBOX_FALLBACK] mirrors controllers that prefer an app-side privileged route and then fall back
 * to the helper.
 */
enum class ProfileHelperAuthorityDemand {
    NONE,
    SANDBOX_FALLBACK,
    REQUIRED,
}

private val CURVE_NAMES = listOf("identity", "perceptual", "gamma", "points")
