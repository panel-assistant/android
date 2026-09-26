package io.github.maxlyth.hapaneld.device

import io.github.maxlyth.hapaneld.device.profile.ProfileArtifacts
import io.github.maxlyth.hapaneld.device.profile.ProfileProximityCalibration
import io.github.maxlyth.hapaneld.device.profile.ProfileLink
import io.github.maxlyth.hapaneld.device.profile.ProfiledDisplayGeometry
import io.github.maxlyth.hapaneld.device.profile.ProfileSoc
import io.github.maxlyth.hapaneld.device.profile.ShizukuRecommendation

/**
 * Runtime view of one validated declarative device profile. Everything device/platform-specific that
 * the generic functional modules need — su form, LED mechanism, screen-off path, and the sysfs/vendor
 * locations of optional hardware — is loaded from the active YAML revision and exposed through this
 * interface.
 *
 * Design rule (see docs/architecture/device-profiles.md): a profile declares **candidates + quirks**;
 * the functional modules still **runtime-probe to confirm** whenever the platform exposes a probe
 * (profile says *where to look*, the probe says *whether it's actually there*). The bundled generic
 * YAML deliberately keeps unknown hardware conservative: standard Android sensors and generic
 * LED/CPU routes can be probed, while relays, evdev buttons and vendor protocols stay absent until
 * their paths are known.
 */
interface DeviceProfile {
    /** Stable id, e.g. "nspanel-pro" / "tpa10" / "generic". */
    val id: String

    /** Immutable SHA-256 identity of the loaded profile content (or the reserved emergency contract).
     *  Local sensor calibration is scoped to this value so a different YAML revision cannot inherit
     *  incompatible physical readings. */
    val revision: String

    /** Human label for the info page / diagnostics. */
    val displayName: String

    /** SoC class, e.g. "PX30 / rk3326". */
    val socClass: String

    /** Optional profile-evidenced SoC model, CPU topology and introduction year. */
    val soc: ProfileSoc? get() = null

    /** Validated display-only profile references; never runtime-fetched or used for provisioning. */
    val profileLinks: List<ProfileLink> get() = emptyList()

    /** Which `su` invocation form works on this platform (or [SuForm.NONE] if the app can't reach su). */
    val suForm: SuForm

    /** Whether a normal app process can exec `su` (false on sandbox-walled panels like the TPA10, which
     *  need the root helper daemon for privileged writes). */
    val appCanSu: Boolean

    /** Desired provisioning state normalized from the active profile document. */
    val provisioning: ProvisioningIntent get() = ProvisioningIntent.EMPTY

    /** Author recommendation only; never a claim about live Shizuku installation or readiness. */
    val shizukuRecommendation: ShizukuRecommendation get() = provisioning.shizuku

    /** Whether full profile behavior relies on `helper/hapaneld-helper`. Every sandbox-walled panel needs
     *  it for privileged controls, but app-su panels can need it too: WF1589T's evdev power button is the
     *  important counterexample. This is a diagnostic requirement, not a routing gate; live controllers
     *  still fall through between safe routes when the preferred transport is unavailable. */
    val usesDaemon: Boolean get() =
        !appCanSu ||
            ledMechanism == LedMechanism.SYSFS_DAEMON ||
            ledMechanism == LedMechanism.RK3576_IOCTL_DAEMON ||
            screenOff == ScreenOff.DAEMON_BLPOWER ||
            hasButtonBacklight ||
            proximityGpio != null ||
            evdevButtons.isNotEmpty()

    /** Curated, annotated packages for THIS panel's "Recommended" list in the Vendor-packages card — each a
     *  [TameCandidate] carrying its tags (e.g. "vendor"/"chipset"/"test"/"overlay") and a one-line note from
     *  the profile author explaining what it is and why it's flagged, so a non-engineer gets real context.
     *  *Suggestions only*, never auto-acted. Packages absent from this firmware are dropped silently, so list
     *  the union across firmware versions — no per-release sets needed. Default empty. */
    val tameVendorCandidates: List<TameCandidate> get() = provisioning.packages.map {
        TameCandidate(
            pkg = it.packageName,
            tags = it.tags,
            note = it.note,
            defaultTame = it.importance == ProvisioningImportance.RECOMMENDED,
        )
    }

    /** Whether the firmware provides a working Recents/Overview screen. Single-purpose panel images often
     *  ship none (verified 2026-06-10: KEYCODE_APP_SWITCH is a no-op on the Tuya TPA10), so the navbar's
     *  Recents button is omitted where false rather than presenting a dead control. Default true. */
    val hasRecents: Boolean get() = true

    /** Whether this profile's hardware declarations were read from a catalog profile document, rather
     *  than being the conservative defaults of a last-resort runtime contract. It separates "this panel
     *  declares no Recents" from "nothing declared anything", which for most capabilities is a
     *  distinction without a difference — both withhold a feature — but not for one that must add a
     *  control where hardware is absent. See `navbarModeDefault`. Default false. */
    val declarationsFromCatalog: Boolean get() = false

    /** Whether the firmware draws Android's own navigation bar, making the soft overlay unnecessary.
     *  Declared rather than probed: the generic Android signals lie in both directions — NSPanel Pro
     *  hardcodes `config_showNavigationBar` false while having no bar (so the `policy_control` select
     *  added in 72e82470 was removed an hour later in 049f2fac as a no-op), and some PX30 firmware
     *  hardcodes it true while suppressing the bar. Gates only the `Native` navbar mode, which must
     *  never be offered where selecting it would leave the panel with no navigation at all. Default
     *  false, so an unverified or community profile never offers it speculatively. */
    val hasNativeNavbar: Boolean get() = false

    /** How the RGB LED is driven, if any. */
    val ledMechanism: LedMechanism

    /** A distinct monochrome button-backlight node driven by the helper's `BTN` command. Do not infer
     *  this from a daemon-backed RGB controller: SMT1019 uses that controller for `/dev/ledjni` but has
     *  no `/sys/class/leds/button-backlight` node. */
    val hasButtonBacklight: Boolean get() = false

    /**
      * The board's camera declaration: `true` forces the capability on, `false` suppresses it even where
      * Android enumerates a camera, and null defers to runtime enumeration. Null is the default because
      * a camera Android can enumerate should not need a hand-written profile before it can be used.
      */
    val cameraDeclared: Boolean? get() = null

    /** Screen pixels from the top of the active area up to the lens centre; null when unmeasured. */
    val cameraLensOffsetPx: Int? get() = null

    /** Board carries a usable microphone, independent of [cameraDeclared] — some hardware has one
     *  without the other. Default false: unlike the camera a microphone cannot be enumerated, so it is
     *  only ever true once declared and verified on the hardware. */
    val hasMicrophone: Boolean get() = false

    /** SoundPool gain for the physical-speaker click. This is deliberately profile-owned because the
     *  same media-stream level produces very different acoustic output across panel speaker/enclosure
     *  combinations. It scales only ha-paneld's click sample and never changes Android stream volume. */
    val touchClickGain: Float get() = 0.2f

    /** Per-channel LED transfer function (requested 0..255 → hardware value), correcting the panel's
     *  non-linear LED response. Only consumed on the rk3576 ioctl path. Default = passthrough; rk3576
     *  profiles override with a curve for their LED. See [io.github.maxlyth.hapaneld.hardware.LedTransfer]. */
    val ledTransfer: io.github.maxlyth.hapaneld.hardware.LedTransfer
        get() = io.github.maxlyth.hapaneld.hardware.LedTransfer.Identity

    /** Transfer curve from Home Assistant's 0..255 brightness to the backlight, with its inverse for the
     *  effective read-back; [backlightRoute] says where it is applied. Default = the historic linear scaling. */
    val backlightTransfer: io.github.maxlyth.hapaneld.hardware.TransferCurve
        get() = io.github.maxlyth.hapaneld.hardware.TransferCurve.Identity

    /** Where [backlightTransfer] is applied. Irrelevant for the identity curve. */
    val backlightRoute: BacklightRoute get() = BacklightRoute.NODE

    /** Key-backlight transfer curve (the helper's `BTN` level). Default = passthrough. */
    val buttonBacklightTransfer: io.github.maxlyth.hapaneld.hardware.TransferCurve
        get() = io.github.maxlyth.hapaneld.hardware.TransferCurve.Identity

    /** Preferred true-screen-off path (runtime tiering still falls back as needed). */
    val screenOff: ScreenOff

    /** Sonoff Zigbee gateway dir, or null if the panel has no managed Zigbee gateway. */
    val zigbeeGatewayDir: String?

    /** Base of the relay sysfs class for on-board relays, or null if none. */
    val relayBase: String?

    /** Additional relay-class sysfs bases to probe when [relayBase] isn't present. The S9E renamed
     *  `/sys/class/st_relay` → `/sys/class/strelay` between firmware 1.0.2 and 1.1.0, so both must be
     *  tried — the controller uses the first whose dir actually holds `relayN` nodes. Default empty. */
    val relayBaseFallbacks: List<String> get() = emptyList()

    /** First GPIO number of the button-LED block (e.g. 147 on the S9E), or null if none. */
    val buttonLedGpioBase: Int?

    /** Declared proximity-sensor technology (e.g. "Time-of-Flight", "Infrared", "Radar"), or null if
     *  unknown. Android's Sensor API has no technology field and the HAL reports generic AOSP names
     *  (verified: "Proximity sensor" / vendor "The Android Open Source Project"), so this can't be probed
     *  — declare per profile where known. Optionally append a known chipset, e.g. "Infrared (STK3338)". */
    val proximityTech: String? get() = null

    /** Versioned observed baseline; absent or incompatible baselines require explicit calibration. */
    val proximityCalibration: ProfileProximityCalibration? get() = null

    /** GPIO number of a raw binary proximity line streamed by the root helper for panels whose Android
     *  `SensorManager` proximity is absent or registers but never delivers events. Scale and polarity require an explicit calibration or a corroborated profile baseline. Null selects the `SensorManager` source. */
    val proximityGpio: Int? get() = null

    /** Declared ambient-light-sensor technology (e.g. "Ambient light (ALS)"), or null if unknown. */
    val lightTech: String? get() = null

    /** True on panels using an exact supported CHT8305-compatible room-climate input layout. The app
     *  cannot read `/dev/input`; an established helper is preferred, with a fixed Shizuku shell-UID reader
     *  where the vendor makes those exact nodes shell-readable. Gates the opt-in Room sensors and offset. */
    val hasCht8305: Boolean get() = false

    /** Board carries a VI530x time-of-flight sensor reachable through the helper daemon. */
    val hasVi530x: Boolean get() = false

    /** Baseline calibration offset (°C) added to the CHT8305 room-temperature reading to correct for panel
     *  self-heating. The maintainer sets a characterised value per profile once measured; the user can add a
     *  further trim via the `room_temp_offset` config key (the two are additive). 0 = no baseline correction. */
    val roomTempOffsetC: Float get() = 0f

    /** Default HA device-card manufacturer for this panel (e.g. "Sonoff"), or null to infer from
     *  Android's `Build.MANUFACTURER`. The user's Configure-form value always overrides. */
    val manufacturer: String?

    /** Default HA device-card model/product name (e.g. "NSPanel Pro"), or null to infer from
     *  Android's `Build.MODEL`. Published with a " (ha-paneld)" suffix so the device is distinguishable from a
     *  co-installed integration managing the same hardware; the user's form value overrides verbatim. */
    val model: String?

    /** Model text for the local info page. Most profiles use [displayName]; a profile may decode its
     *  vendor product-version string when that string carries a real variant/firmware identity. */
    fun panelModelLabel(productVersion: String): String = displayName

    /** Hardware buttons the Android input pipeline doesn't usefully deliver to the app, instrumented
     *  via the root helper daemon's evdev WATCH/grab instead. Empty when none. See [EvdevButton]. */
    val evdevButtons: List<EvdevButton>

    /** Maps the HA-facing CPU tiers ("Performance"/"Efficiency"/"Auto") to this SoC's kernel governors.
     *  Mainly to pin "Auto" to the right dynamic governor (schedutil on rk3566/rk3576, interactive on
     *  PX30). Null, or any unmapped/unavailable tier, falls back to runtime resolution in CpuController. */
    val cpuGovernors: Map<String, String>?

    /** Optional per-panel "HA-optimised" display density (dpi) + text (font) scale, offered as a
     *  one-click preset in the display-sizing control. The genuinely-right values are a per-install
     *  preference (room, dashboard design), so these are starting suggestions to be calibrated — null =
     *  offer only factory-base reset + custom. Density is the layout scale; font scale is the WebView text. */
    val recommendedDensity: Int? get() = provisioning.density
    val recommendedFontScale: Float? get() = provisioning.fontScale

    /** Profile evidence for the panel whose physical display mode is [physicalWidthPx]×[physicalHeightPx]:
     *  physical size, and the variant's declared factory-base logical DPI. Never derived from Android's
     *  `wm density` "Physical density" field, which is a base logical DPI. Null when the profile has none. */
    fun displayGeometry(physicalWidthPx: Int, physicalHeightPx: Int): ProfiledDisplayGeometry? = null

    /** The System WebView build to install when this panel's stock WebView is too old to render the HA
     *  dashboard. These panels have no Play Store, so ha-paneld sideloads a known-good `com.android.webview`
     *  from the `webview-mirror` release (pinned signer). null = no known-good build for this panel (leave
     *  the WebView alone). Pick the newest the panel's Android version supports (NSPanel Pro's 8.1 caps at
     *  138). See [WebViewInstaller] and docs/hardware/README.md. */
    val recommendedWebView: WebViewSpec? get() = provisioning.webViewArtifactId?.let(ProfileArtifacts.webViews::get)

    /** The newest HA Companion version known-good on this platform, or null = no cap. The Companion
     *  auto-updater refuses to install a release NEWER than this. 2026.6.5-minimal crash-loops on
     *  PX30/Android 8.1 (missing `CarUxRestrictionsManager` class), so the NSPanel Pro pins to 2026.5.4.
     *  Compared with `UpdateChecker.isNewer` (dotted numeric). */
    val companionMaxVersion: String? get() = provisioning.companionMaxVersion

}

/** Provisioning policy normalized from the profile schema for runtime consumers. */
data class ProvisioningIntent(
    val shizuku: ShizukuRecommendation = ShizukuRecommendation.NONE,
    val webViewArtifactId: String? = null,
    val companionMaxVersion: String? = null,
    val density: Int? = null,
    val fontScale: Float? = null,
    val packages: List<PackageIntent> = emptyList(),
    val recipeIds: Set<String> = emptySet(),
) {
    companion object {
        val EMPTY = ProvisioningIntent()
    }
}

data class PackageIntent(
    val packageName: String,
    val desiredState: PackageDesiredState,
    val importance: ProvisioningImportance,
    val tags: List<String> = emptyList(),
    val note: String = "",
)

enum class PackageDesiredState { DISABLED }

enum class ProvisioningImportance { RECOMMENDED, OPTIONAL }

/** `su` invocation form: toolbox `su -c '<cmd>'` (Sonoff PX30) vs Android `su 0 sh -c '<cmd>'` (Tuya
 *  userdebug); NONE = su is not reachable from the app sandbox (use the helper daemon instead). */
enum class SuForm { TOOLBOX, ANDROID, NONE }

/**
 * A known-good System WebView build for a panel (see [DeviceProfile.recommendedWebView]). The package
 * is always `com.android.webview` — the id the Android framework requires to auto-select a WebView
 * provider — so only the source + version + pinned signer vary. [version] is the full Chromium version
 * the build provides (e.g. "138.0.7204.63"); all four numeric components gate automatic updates.
 * [certSha256] is the build's signing cert (LineageOS / Cromite / …), and [apkSha256] pins the
 * exact mirrored artifact; both are verified before install.
 */
data class WebViewSpec(
    val url: String,
    val version: String,
    val certSha256: String,
    val apkSha256: String,
) {
    /** Chromium major of [version] (e.g. 138), or 0 if unparseable. */
    val major: Int get() = version.substringBefore('.').toIntOrNull() ?: 0
}

/** RGB-LED control mechanism. RK3576_IOCTL = app-direct ioctl on `/dev/ledjni` (e.g. WF1589T);
 *  RK3576_IOCTL_DAEMON = the *same* ioctl but routed through the root helper daemon, for panels where
 *  the app is SELinux-denied the ioctl (e.g. SMT1019 — the node is `system:system`, generic `device`
 *  label); SYSFS_DAEMON = root-only sysfs LED via the daemon (e.g. TPA10); AUTODETECT = probe rk3576
 *  ioctl then the daemon (used by the generic YAML profile); NONE = no LED, skip probing.
 *  The daemon auto-detects sysfs-vs-ledjni itself, so both daemon mechanisms use the same client. */
enum class LedMechanism(val yamlName: String) {
    NONE("none"),
    AUTODETECT("autodetect"),
    RK3576_IOCTL("rk3576-ioctl"),
    RK3576_IOCTL_DAEMON("rk3576-ioctl-daemon"),
    SYSFS_DAEMON("sysfs-daemon"),
    ;

    companion object {
        /** The one list of accepted `hardware.led.mechanism` names; null for anything else. */
        fun ofYaml(name: String): LedMechanism? = entries.firstOrNull { it.yamlName == name }
    }
}

/**
 * True-screen-off path.
 *
 * The two `*_BLPOWER` routes blank the backlight through `/sys/class/backlight/<dev>/bl_power` while
 * Android stays interactive, so no keyguard is raised and a touch still reaches a window. [KEYEVENT]
 * is for panels that expose no backlight class at all: it injects `KEYCODE_SLEEP`, which puts Android
 * itself noninteractive. That is a different state with different consequences, spelled out on
 * [io.github.maxlyth.hapaneld.control.ScreenController].
 */
enum class ScreenOff { SU_BLPOWER, DAEMON_BLPOWER, KEYEVENT, BRIGHTNESS_ZERO }

/**
 * A hardware button instrumented through the root helper daemon's evdev reader (for keys Android
 * doesn't deliver to the app — e.g. a `KEY_MICMUTE` adc-key, or the power key).
 *
 * @param node    the evdev node, e.g. "/dev/input/event1"
 * @param code    the Linux input code it emits (e.g. KEY_POWER 116, or with [sw] the switch code,
 *                e.g. SW_MUTE_DEVICE 14 on the TPA10 orange button)
 * @param grab    EVIOCGRAB the node exclusively, suppressing the default Android action (e.g. the
 *                power key's screen-lock) so the press becomes an HA event only — gated by automation.
 * @param eventType  the HA `event_type` published on press; must be in the event entity's declared list.
 * @param sw      false = an EV_KEY momentary key (emit on press/DOWN); true = an EV_SW latching switch
 *                (e.g. SW_MUTE_DEVICE) — emit on every toggle, since each physical press flips it.
 */
data class EvdevButton(
    val node: String,
    val code: Int,
    val grab: Boolean,
    val eventType: String,
    val sw: Boolean = false,
)

/**
 * A profile-curated package the user might want to tame, with author metadata so the Vendor-packages UI
 * can explain *what it is* and *why it's listed* — important on small-production panels where, say, the
 * SoC vendor's factory-test apps are routinely left installed and look alarming without context.
 *
 * @param pkg   the Android package name (e.g. "com.eWeLinkControlPanel").
 * @param tags  short classifier labels shown as chips — e.g. "vendor" (the panel maker's app),
 *              "chipset" (the SoC vendor's app), "test"/"demo" (factory/diagnostic), "overlay" (draws
 *              over the screen), "boot" (auto-starts). Free-form; the UI just renders them.
 * @param note  a one-line rationale from the profile author, shown under the row so the user understands
 *              why it's flagged and whether they want it gone. Empty = no note.
 * @param defaultTame  compatibility projection for a profile recommendation. It badges the candidate and
 *              includes it in the explicit "Tame all recommended" UI action; it is not installation consent
 *              and does not modify a panel on its own.
 */
data class TameCandidate(
    val pkg: String,
    val tags: List<String> = emptyList(),
    val note: String = "",
    val defaultTame: Boolean = false,
)

/**
 * Where a backlight transfer curve is applied. [SETTING]: into Android's brightness setting, for firmware
 * that pushes that setting to the node itself (it would overwrite a curved node write within seconds);
 * ha-paneld then keeps an owned record of the Home Assistant level it set so the read-back is exact.
 * [NODE]: into the node, with the setting left on the Home Assistant scale, for panels where ha-paneld is
 * the node's only writer.
 */
enum class BacklightRoute { SETTING, NODE }
