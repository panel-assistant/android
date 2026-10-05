package io.panelassistant.android.http

import android.content.Context
import android.os.Build
import android.provider.Settings
import io.panelassistant.android.BuildConfig
import io.panelassistant.android.control.Su
import io.panelassistant.android.control.TameController
import io.panelassistant.android.control.PrivilegedRouteObservation
import io.panelassistant.android.control.ZigbeeHealthSnapshot
import io.panelassistant.android.device.DeviceProfile
import io.panelassistant.android.device.LedMechanism
import io.panelassistant.android.device.ScreenOff
import io.panelassistant.android.hardware.NativeLed
import io.panelassistant.android.input.ButtonCaptureHealth
import io.panelassistant.android.input.EvdevButtonClient
import io.panelassistant.android.metrics.PanelMetrics
import io.panelassistant.android.shizuku.ShizukuBridge
import io.panelassistant.android.shizuku.ShizukuConsent
import io.panelassistant.android.shizuku.ShizukuManagerIdentity
import io.panelassistant.android.shizuku.ShizukuState
import io.panelassistant.android.storage.StorageHealthSnapshot
import io.panelassistant.android.util.CompanionInstaller
import io.panelassistant.android.util.HelperClient
import io.panelassistant.android.util.BoundedLaunchGate
import io.panelassistant.android.util.BoundedStreams
import io.panelassistant.android.util.MonotonicDeadline
import io.panelassistant.android.util.SystemProps
import io.panelassistant.android.util.UpdateChecker
import io.panelassistant.android.util.WebViewInstaller
import io.panelassistant.android.util.runBoundedLaunch
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Self-diagnostics for the info page. Two outputs from the same probes:
 *  - [capabilities] — a per-capability status (ok / degraded / none) with an actionable note, so the
 *    UI can tell the user what works on THEIR hardware/firmware and how to fix a shortfall.
 *  - [dump] — a copy-paste text report (build, SELinux, su, sysfs/dev nodes, packages, capabilities)
 *    for pasting into a bug report, so a maintainer can diagnose a panel they don't own.
 *
 * Everything here is read as the app uid — world-readable /proc + /sys, `appops`/Settings checks,
 * and a graceful `su` probe. No assumption of root.
 */
object DiagReader {

    private const val EXEC_TIMEOUT_MS = 3_000L
    private const val EXEC_MAX_BYTES = 64L * 1024L
    private const val ROOT_PROBE_TIMEOUT_MS = 5_000L
    private const val DUMP_TIMEOUT_MS = 15_000L
    private val execLaunchGate = BoundedLaunchGate()

    /** status: "ok" | "degraded" | "none" */
    internal data class Cap(val name: String, val status: String, val note: String)

    /** Typed probe result shared by service facts and diagnostic presentation. */
    internal data class CapabilityObservation(
        val rows: List<Cap>,
        val rgbLedReady: Boolean,
    )

    /** Cached display facts supplied by the management projection; diagnostics never re-probe them. */
    data class DisplaySizingEvidence(
        val androidBaseLogicalDpi: Int?,
        val currentLogicalDpi: Int?,
        val fontScale: Float,
    )

    internal fun capabilities(
        ctx: Context,
        profile: DeviceProfile,
        privilege: PrivilegedRouteObservation,
    ): CapabilityObservation {
        val su = privilege.directSuReady
        val daemon = privilege.helperRootReady
        val pkg = ctx.packageName
        val showLed = showRgbLedCapability(profile)
        val rkLed = showLed && NativeLed.available()
        // Which LED node the daemon can actually reach — so "RGB LED" reflects a reachable node, not
        // just "a daemon is running". An old daemon predates LEDPROBE and replies "ERR" → fall back to
        // the daemon-up signal (prior behaviour); "none" = daemon present but no LED node.
        val ledProbe = if (showLed && daemon) HelperClient.send("LEDPROBE") else null
        val daemonLed = when (ledProbe) {
            "ledjni", "sysfs" -> true
            "none" -> false
            else -> showLed && daemon
        }
        val canWrite = Settings.System.canWrite(ctx)
        val a11y = a11yEnabled(ctx)
        val showButtons = showHardwareButtonsCapability(profile)
        val buttonHealth = if (showButtons) {
            ButtonCaptureHealth.evaluate(a11y, profile.evdevButtons.size, EvdevButtonClient.snapshot(), pkg)
        } else {
            null
        }
        val rootish = privilege.rootControlReady
        val shizukuSnapshot = privilege.shizuku
        val shizuku = shizukuSnapshot.ready
        val manager = ShizukuManagerIdentity.status(ctx)
        // Surface the helper whenever this profile needs it for privileged control or profile-specific
        // hardware such as daemon-only LEDs and evdev buttons, even if the app can also execute su.
        val usesDaemon = profile.usesDaemon
        val rows = listOfNotNull(
            rootSuCapability(su, daemon),
            if (usesDaemon) Cap("Helper daemon", if (daemon) "ok" else "none",
                if (daemon) daemonRequirement(profile, running = true)
                else daemonRequirement(profile, running = false))
            else null,
            if (showShizukuCapability(ShizukuConsent.enabled(ctx), manager)) {
                shizukuCapability(shizukuSnapshot, manager, preferredPrivilegeReady = rootish)
            } else null,
            Cap("Verified app update / screenshot / display", if (rootish || shizuku) "ok" else "none",
                when {
                    rootish -> "available through root or the helper daemon"
                    shizuku -> "available through locally approved Shizuku access; app updates remain signer-verified"
                    else -> "needs supported privileged panel access"
                }),
            screenBrightnessCapability(canWrite, su, daemon, pkg),
            screenOnOffCapability(profile.screenOff, su, daemon),
            if (showLed) Cap("RGB LED", if (rkLed || daemonLed) "ok" else "none",
                when {
                    rkLed -> "Rockchip /dev/ledjni (app-direct, no root)"
                    ledProbe == "ledjni" -> "Rockchip /dev/ledjni ioctl via the helper daemon (root)"
                    ledProbe == "sysfs" || daemonLed -> "sysfs LED via the helper daemon"
                    else -> "no reachable LED node; needs the root helper daemon (install needs su once)"
                })
            else null,
            buttonHealth?.let { Cap("Hardware buttons", it.status, it.note) },
            Cap("Reboot / reload / launcher", if (rootish) "ok" else "none",
                if (rootish) "available" else "needs su or the helper daemon"),
        )
        return CapabilityObservation(rows = rows, rgbLedReady = rkLed || daemonLed)
    }

    /**
     * Exact profiles use [LedMechanism.NONE] as an authoritative declaration that no supported RGB LED
     * exists. AUTODETECT is reserved for Generic/unknown hardware, where a runtime probe remains useful.
     */
    internal fun showRgbLedCapability(profile: DeviceProfile): Boolean =
        profile.ledMechanism != LedMechanism.NONE

    /**
     * A non-empty evdev declaration is the supported hardware-button contract for an exact profile.
     * Generic remains intentionally visible because its empty list means unknown/a11y-only, not absent.
     */
    internal fun showHardwareButtonsCapability(profile: DeviceProfile): Boolean =
        profile.id == "generic" || profile.evdevButtons.isNotEmpty()

    /**
     * Report app-visible `su` without confusing it with the helper-backed privilege route. In
     * particular, the TPA10 intentionally cannot execute `su` from the app sandbox but its root helper
     * provides reboot, reload, launcher, LED and true screen-off actions. The capability-specific rows
     * below remain the authority for whether each action is actually available.
     */
    internal fun rootSuCapability(su: Boolean, daemon: Boolean): Cap = Cap(
        name = "Root (su)",
        status = when {
            su -> "ok"
            daemon -> "degraded"
            else -> "none"
        },
        note = when {
            su -> "available directly to ha-paneld"
            daemon -> "not available directly to ha-paneld — privileged actions are routed through the helper daemon"
            else -> "not available directly to ha-paneld — see the individual capability rows below"
        },
    )

    /** The hardware route can adjust the backlight without WRITE_SETTINGS, but cannot update Android's
     * logical brightness value. Present that as reduced, rather than unavailable, when it is usable. */
    internal fun screenBrightnessCapability(canWrite: Boolean, su: Boolean, daemon: Boolean, pkg: String): Cap = when {
        canWrite -> Cap("Screen brightness", "ok", "WRITE_SETTINGS granted")
        daemon -> Cap("Screen brightness", "degraded", "backlight control via helper daemon; Android setting is unchanged")
        su -> Cap("Screen brightness", "degraded", "backlight control via su; Android setting is unchanged")
        else -> Cap(
            "Screen brightness",
            "none",
            "needs WRITE_SETTINGS: adb shell appops set $pkg WRITE_SETTINGS allow",
        )
    }

    /**
     * The route decides first, then the transport. A profile's declared route is what
     * [io.panelassistant.android.control.ScreenController] will actually take, so a privileged
     * transport merely existing proves nothing about what a screen-off will do:
     * [ScreenOff.BRIGHTNESS_ZERO] hard-codes a null powered-off route and never attempts su or the
     * daemon at all, which used to be reported as a "true backlight-off" on any rooted panel.
     *
     * The keyevent route also must not borrow the bl_power wording: it sleeps Android itself rather
     * than blanking a backlight, and what a person standing at the panel can do to wake it is
     * genuinely different. That difference is the part worth stating on a panel's own diagnostics.
     */
    internal fun screenOnOffCapability(route: ScreenOff, su: Boolean, daemon: Boolean): Cap {
        val dimOnly = { why: String -> Cap("Screen on/off", "degraded", "DIM ONLY — $why") }
        return when (route) {
            ScreenOff.KEYEVENT ->
                if (daemon || su) Cap("Screen on/off", "ok",
                    "Android sleep via KEYCODE_SLEEP; Home Assistant always wakes it, a local touch only where this panel's touchscreen is a platform wake source")
                else dimOnly("needs su or the helper daemon to inject KEYCODE_SLEEP")
            // Not a privilege problem, so do not offer su or the helper as the remedy: this panel's
            // profile selects brightness zero, and only a different profile route changes it.
            ScreenOff.BRIGHTNESS_ZERO ->
                dimOnly("this panel's profile selects the brightness-zero route, which never powers the backlight down")
            ScreenOff.SU_BLPOWER -> when {
                su -> Cap("Screen on/off", "ok", "true backlight-off via su bl_power")
                daemon -> Cap("Screen on/off", "ok", "true backlight-off via the helper daemon")
                else -> dimOnly("the backlight stays powered; needs su or the helper daemon for a real off")
            }
            ScreenOff.DAEMON_BLPOWER -> when {
                daemon -> Cap("Screen on/off", "ok", "true backlight-off via the helper daemon")
                su -> Cap("Screen on/off", "ok", "true backlight-off via su bl_power")
                else -> dimOnly("the backlight stays powered; needs su or the helper daemon for a real off")
            }
        }
    }

    internal fun showShizukuCapability(
        consentEnabled: Boolean,
        manager: ShizukuManagerIdentity.Status,
    ): Boolean = consentEnabled || manager != ShizukuManagerIdentity.Status.MISSING

    internal fun shizukuCapability(
        snapshot: ShizukuBridge.Snapshot,
        manager: ShizukuManagerIdentity.Status,
        preferredPrivilegeReady: Boolean = false,
    ): Cap = Cap(
        name = "Shizuku enhanced access",
        status = if (snapshot.ready && manager == ShizukuManagerIdentity.Status.TRUSTED) "ok" else "none",
        note = shizukuCapabilityNote(snapshot.state, manager, preferredPrivilegeReady),
    )

    internal fun shizukuCapabilityNote(
        state: ShizukuState,
        manager: ShizukuManagerIdentity.Status,
        preferredPrivilegeReady: Boolean = false,
    ): String {
        val stateNote = when {
            manager == ShizukuManagerIdentity.Status.UNTRUSTED ->
                "blocked: installed manager signer is not trusted"
            manager == ShizukuManagerIdentity.Status.MISSING ->
                "manager missing; re-run provisioning with --shizuku"
            state == ShizukuState.READY -> "ready as shell UID; local typed operations only"
            state == ShizukuState.DISABLED ->
                "disabled in ha-paneld; on the panel open Configure → toolbar overflow → Enhanced access → Enable"
            state == ShizukuState.STOPPED ->
                "enabled in ha-paneld, but the Shizuku service is stopped; open Shizuku and start its service"
            state == ShizukuState.PERMISSION_REQUIRED ->
                "service running; request and approve ha-paneld access locally"
            state == ShizukuState.MANUAL_GRANT_REQUIRED ->
                "access denied; grant ha-paneld under Shizuku → Authorized applications"
            state == ShizukuState.BINDING -> "connecting to the locally approved Shizuku service"
            state == ShizukuState.INCOMPATIBLE -> "blocked: unexpected Shizuku service identity or protocol"
            else -> "Shizuku could not be connected; retry from the on-panel Enhanced access dialog"
        }
        return if (preferredPrivilegeReady) {
            "adds no capability while root or the helper daemon provides the preferred route; $stateNote"
        } else {
            stateNote
        }
    }

    private fun daemonRequirement(profile: DeviceProfile, running: Boolean): String {
        val state = if (running) "running" else "NEEDED but not running"
        return when {
            !profile.appCanSu -> "$state — the privileged control path on this sandbox-walled panel; without it, root-only controls remain unavailable"
            profile.evdevButtons.isNotEmpty() -> "$state — required for ${profile.evdevButtons.size} profiled physical button(s), even though ordinary privileged actions can use su"
            profile.hasButtonBacklight -> "$state — required for the profiled button backlight"
            else -> "$state — required for this profile's daemon-backed hardware"
        }
    }

    /**
     * Terse, version-stamped copy-paste report for GitHub issues. The `[panel]` block reuses the EXACT
     * facts shown on the info page ([facts], passed by the caller), restricted to an explicit public-safe
     * allowlist so a new profile/config field cannot silently enter a pasted report. Every other section
     * is one line. The version+build header is the version control: a pasted report is always attributable
     * to the build that produced it.
     */
    internal fun dump(
        ctx: Context,
        profile: DeviceProfile,
        facts: Map<String, String> = emptyMap(),
        zigbee: ZigbeeHealthSnapshot? = null,
        privilege: PrivilegedRouteObservation,
        capabilityRows: List<Cap>,
        displaySizing: DisplaySizingEvidence? = null,
        storage: StorageHealthSnapshot = StorageHealthSnapshot.UNCHECKED,
        powerSafety: io.panelassistant.android.control.PowerSafetyAssessment? = null,
        renderer: io.panelassistant.android.RendererAdmissionPresentation? = null,
        camera: io.panelassistant.android.camera.CameraPresentation? = null,
        wifiStabilityChronic: Boolean = false,
        haNetwork: String? = null,
        haPathProbe: String? = null,
        termuxBridge: TermuxBridgeProbe.State = TermuxBridgeProbe.State.NOT_APPLICABLE,
    ): String {
        val deadline = MonotonicDeadline(DUMP_TIMEOUT_MS)
        val routes = privilege
        val su = routes.directSuReady
        val daemon = routes.helperRootReady
        return buildString {
        appendLine("ha-paneld diagnostics — ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})")
        // Capture metadata — a normalise-me line for the regression harness: when this dump was taken +
        // how long the panel has been up (uptime is often more telling than wall-clock on a panel).
        appendLine("[captured] ${java.time.OffsetDateTime.now()} uptime=${fmtUptime(android.os.SystemClock.elapsedRealtime())}")
        // Which installed identity this is: during the application-id migration a panel can hold both.
        appendLine("[package] ${BuildConfig.APPLICATION_ID}")
        if (facts.isNotEmpty()) {
            appendLine()
            appendLine("[panel]")
            for ((k, v) in publicPanelFacts(facts, wifiStabilityChronic)) appendLine("$k=$v")
        }
        appendLine()
        appendLine("[build] fingerprint=${Build.FINGERPRINT}")
        appendLine("board=${Build.BOARD} product=${Build.PRODUCT} hardware=${Build.HARDWARE} abis=${Build.SUPPORTED_ABIS.joinToString(",")}")
        appendLine(bootSecurityLine(SystemProps::get, Build.TYPE))
        val evdev = EvdevButtonClient.snapshot()
        appendLine("[env] selinux=${PanelMetrics.shared.selinuxEnforce() ?: "?"} su=$su write_settings=${Settings.System.canWrite(ctx)} a11y=${a11yEnabled(ctx)} daemon=$daemon shizuku=${routes.shizuku.state.name.lowercase()} evdev=${evdev.state.name.lowercase()}/${evdev.mode?.name?.lowercase() ?: "none"} ledjni=${NativeLed.available()}")
        displaySizing?.let { appendLine(displaySizingLine(it, profile)) }
        // The renderer/Home Assistant line leads the health block deliberately: a pasted report whose
        // dashboard is down should answer that question before it answers anything about storage or
        // radios. Its values are all classified or categorical — no URL, host, credential or raw
        // exception text — so this line is as pasteable as the rest of the dump.
        renderer?.let { appendLine(it.diagnosticLine()) }
        webViewRollbackLine(ctx)?.let { appendLine(it) }
        // The measured path to Home Assistant comes next: it is the first thing to rule in or out
        // when the renderer line says "rendered" and the report still complains of a slow dashboard.
        // Classified state and terse aggregates only; the presentation never carries a host.
        haNetwork?.let { appendLine(it) }
        haPathProbe?.let { appendLine(it) }
        TermuxBridgeProbe.diagnosticLine(termuxBridge)?.let { appendLine(it) }
        // Camera trial: rendered identically here and in /api/v1/status so severity cannot drift
        // between the two. Client addresses and raw
        // exception text never enter this line — [CameraPresentation] already excludes them.
        camera?.let { appendLine(it.diagnosticLine()) }
        zigbee?.let {
            appendLine(
                "[zigbee-health] state=${it.state.wireValue} layout=${it.layout} package=${it.packageVersion ?: "-"} " +
                    "joined=${it.joined ?: "unknown"} role=${it.role ?: "-"} gateway_cpu=${it.gatewayCpu ?: -1} " +
                    "guard_cpu=${it.guardCpu ?: -1} restarts_10m=${it.restartCount} " +
                    "containment=${it.containment.wireValue} recursive_watchdog=${it.recursiveWatchdogAssignment}",
            )
        }
        appendLine(HealthAudit.storage(storage).diagnosticLine())
        powerSafety?.let { appendLine(PowerSafetyPresentation.diagnosticLine(it)) }
        evdevRequestDescription(profile)?.let { requested ->
            appendLine("[evdev] requested=$requested state=${evdev.state.name.lowercase()} mode=${evdev.mode?.name?.lowercase() ?: "none"} error=${evdev.lastError ?: "-"}")
        }
        appendLine("[sysfs] leds=${listDir("/sys/class/leds")} backlight=${listDir("/sys/class/backlight")} devfreq=${listDir("/sys/class/devfreq")}")
        appendLine("[labels] ${exec("ls -Zd /sys/class/leds/*/ /sys/class/backlight/*/ /dev/ledjni 2>&1", deadline).replace("\n", " ")}")
        // Bounded, read-only characterization for an unknown/new panel. These are the high-signal
        // names needed to locate vendor climate sensors, relay controllers and input devices without
        // asking a non-developer reporter to run a long sequence of adb commands. Avoid raw uevent,
        // serial and address data; every list is sanitized and capped.
        appendLine("[hardware]")
        appendLine("  inputs=${HardwareCharacterization.inputDevices(readFile("/proc/bus/input/devices"))}")
        appendLine("  i2c=${HardwareCharacterization.namedDevices(File("/sys/bus/i2c/devices"), "name")}")
        appendLine("  iio=${HardwareCharacterization.namedDevices(File("/sys/bus/iio/devices"), "name")}")
        appendLine("  thermal=${HardwareCharacterization.namedDevices(File("/sys/class/thermal"), "type")}")
        appendLine("  relays=${HardwareCharacterization.relayClasses(listOf(File("/sys/class/relay"), File("/sys/class/st_relay"), File("/sys/class/strelay")))}")
        // GPIO export diagnostic — only for panels with sysfs button-LED pins (the S9E, gpio147–150).
        // RelayController.ledCount() exports those pins on demand; if a reporter still sees 0 LEDs the
        // usual cause is a gpiochip-base shift (the kernel numbered the pins differently), which these
        // lines expose: check that led_base falls inside some chip's [base, base+ngpio) range, and which
        // pin dirs / value nodes actually came up. Read-only.
        profile.buttonLedGpioBase?.let { base ->
            val pinPaths = (0 until 4).map { "/sys/class/gpio/gpio${base + it}" }
            val chips = probe("grep -H '' /sys/class/gpio/gpiochip*/base /sys/class/gpio/gpiochip*/ngpio /sys/class/gpio/gpiochip*/label 2>/dev/null", deadline)
                .replace("/sys/class/gpio/", "").replace("\n", " ")
            val pinDirs = probe("ls -d ${pinPaths.joinToString(" ")} 2>/dev/null", deadline).replace("\n", " ")
            val valueNodes = probe("ls ${pinPaths.joinToString(" ") { "$it/value" }} 2>/dev/null", deadline).replace("\n", " ")
            appendLine("[gpio] led_base=$base (button-LED pins must fall inside a chip's [base,base+ngpio))")
            appendLine("  chips: ${chips.ifBlank { "(none readable)" }}")
            appendLine("  pin_dirs: ${pinDirs.ifBlank { "(none exported)" }}")
            appendLine("  value_nodes: ${valueNodes.ifBlank { "(none)" }}")
        }
        appendLine("[packages] " + CompanionInstaller.SUPPORTED_PACKAGES
            .joinToString(" ") { "${it.substringAfterLast('.')}=${pkgVer(ctx, it)}" })
        // Keep profile package identifiers out of the public report: a custom profile can contain a private
        // package namespace. Counts retain the useful "is this candidate present/active?" evidence.
        val tameCandidates = profile.tameVendorCandidates
        if (tameCandidates.isNotEmpty()) {
            appendLine(vendorTameSummary(TameController(ctx).profileReport(tameCandidates)))
        }
        appendLine("[capabilities] " + capabilityRows.joinToString(" | ") { "${it.name}=${it.status}" })
        val updates = UpdateChecker.current(ctx)   // revalidated: no stale entry for an uninstalled Companion
        if (updates.isNotEmpty()) {
            appendLine("[updates] " + updates.joinToString(" | ") { "${it.label}: ${it.currentVersion} → ${it.latestVersion}" })
        }
        }
    }

    /** The saved reason is internal text, but the dump is commonly pasted into public reports. */
    internal fun webViewRollbackLine(ctx: Context): String? = WebViewInstaller.rollbackDiagnostic(ctx)
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.let { saved ->
            // Keep the report one line and refuse path, URL, or control-character details.
            val safe = saved.takeIf { it.matches(Regex("[A-Za-z0-9][A-Za-z0-9 .,_():;\\-]{0,159}")) }
                ?: "details omitted"
            "[webview-rollback] reason=$safe"
        }

    internal fun displaySizingLine(evidence: DisplaySizingEvidence, profile: DeviceProfile): String {
        fun dpi(value: Int?) = value?.toString() ?: "?"
        fun scale(value: Float?) = value?.toString() ?: "none"
        val override = evidence.currentLogicalDpi
            ?.takeIf { current -> evidence.androidBaseLogicalDpi == null || current != evidence.androidBaseLogicalDpi }
        return "[display-sizing] android_base_logical_dpi=${dpi(evidence.androidBaseLogicalDpi)} " +
            "current_logical_dpi=${dpi(evidence.currentLogicalDpi)} override_dpi=${dpi(override).replace("?", "none")} " +
            "font_scale=${evidence.fontScale} profile_recommended_dpi=${dpi(profile.recommendedDensity)} " +
            "profile_recommended_font_scale=${scale(profile.recommendedFontScale)}"
    }

    /** Allowlisted, categorical boot posture only: never emit raw properties, hashes or boot IDs. */
    internal fun bootSecurityLine(
        readProperty: (String) -> String,
        buildType: String,
    ): String {
        fun normalized(key: String, allowed: Set<String>): String =
            readProperty(key).trim().lowercase(Locale.ROOT).takeIf { it in allowed } ?: "unknown"
        fun binary(key: String): String = when (readProperty(key).trim()) {
            "1" -> "locked"
            "0" -> "unlocked"
            else -> "unknown"
        }
        val verified = normalized("ro.boot.verifiedbootstate", setOf("green", "yellow", "orange", "red"))
        val flash = binary("ro.boot.flash.locked")
        val vbmeta = normalized("ro.boot.vbmeta.device_state", setOf("locked", "unlocked"))
        val type = buildType.trim().lowercase(Locale.ROOT).takeIf { it in setOf("user", "userdebug", "eng") }
            ?: "unknown"
        val debuggable = when (readProperty("ro.debuggable").trim()) {
            "1" -> "yes"
            "0" -> "no"
            else -> "unknown"
        }
        return "[boot-security] verified=$verified flash=$flash vbmeta=$vbmeta build=$type debuggable=$debuggable"
    }

    /** Public-issue fact boundary. Deliberately allowlisted rather than denylisted: runtime-profile names,
     *  custom sensor descriptions, package namespaces and configured network destinations stay private. */
    private const val WIFI_STABILITY_FACT = "Wi-Fi stability"

    private val PUBLIC_PANEL_FACTS = setOf(
        "ha-paneld", "Android", "Firmware", "Device", "CPU", "RAM", "Storage", "Display",
        "System WebView", "HA Companion", "MQTT state", "Security mode", "Keep panel responsive", "Prevent idle dim", "Android dashboard lock",
        "LED", "Nav actions (a11y)", "Navbar", "Zigbee", "Relays", "Network ADB", "Audio playback",
        "App database", "Product version", "Local-state sync", "State convergence", WIFI_STABILITY_FACT,
    )

    /**
     * The public-safe subset of [facts] for the pasted report.
     *
     * [wifiStabilityChronic] is the one conditional member: the panel's own diagnostics card shows
     * every Wi-Fi episode, but this report is terse by design and is read by somebody triaging a bug,
     * so the line enters it only once the instability is chronic (`wifiOutageChronic`, decided from
     * the same outage read that produced the fact). Defaulting to omission is the fail-safe direction
     * for text that leaves the panel.
     */
    internal fun publicPanelFacts(
        facts: Map<String, String>,
        wifiStabilityChronic: Boolean = false,
    ): Map<String, String> =
        facts.filterKeys { it in PUBLIC_PANEL_FACTS && (wifiStabilityChronic || it != WIFI_STABILITY_FACT) }

    internal fun vendorTameSummary(candidates: List<TameController.Candidate>): String {
        val installed = candidates.count { it.installed }
        val disabled = candidates.count { it.installed && it.disabled }
        return "[vendor-tame] known=${candidates.size} installed=$installed active=${installed - disabled} disabled=$disabled"
    }

    private fun a11yEnabled(ctx: Context): Boolean =
        (Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: "")
            .contains(ctx.packageName)

    internal fun evdevRequestDescription(profile: DeviceProfile): String? =
        profile.evdevButtons.takeIf { it.isNotEmpty() }?.joinToString(",") {
            "${it.node}:${if (it.sw) "SW" else "KEY"}/${it.code}:${if (it.grab) "grab" else "watch"}"
        }

    /** Compact device uptime from elapsed-realtime ms, e.g. "3d2h", "5h12m", "47m", "23s". */
    private fun fmtUptime(ms: Long): String {
        val s = ms / 1000; val d = s / 86400; val h = (s % 86400) / 3600; val m = (s % 3600) / 60
        return when {
            d > 0 -> "${d}d${h}h"
            h > 0 -> "${h}h${m}m"
            m > 0 -> "${m}m"
            else -> "${s}s"
        }
    }

    private fun readFile(p: String): String? = runCatching { File(p).readText() }.getOrNull()

    private fun listDir(p: String): List<String> =
        runCatching { File(p).listFiles()?.map { it.name }?.sorted() ?: emptyList() }.getOrNull() ?: emptyList()

    private fun pkgVer(ctx: Context, id: String): String =
        runCatching { ctx.packageManager.getPackageInfo(id, 0).versionName ?: "?" }.getOrElse { "not installed" }

    private fun exec(cmd: String, deadline: MonotonicDeadline): String = runBoundedLaunch(
        deadline = deadline.cappedTo(EXEC_TIMEOUT_MS),
        threadName = "ha-paneld-diag-exec",
        gate = execLaunchGate,
        launch = {
            ProcessBuilder("/system/bin/sh", "-c", "exec $cmd")
                .redirectErrorStream(true)
                .start()
        },
        destroy = ::destroyProcess,
        consume = { process ->
            val bytes = BoundedStreams.readBytes(process.inputStream, EXEC_MAX_BYTES)
            val text = String(bytes, Charsets.UTF_8).trim()
            if (process.waitFor() == 0) text else text.ifBlank { "(exec failed)" }
        },
    ) ?: "(exec failed or timed out)"

    private fun destroyProcess(process: Process) {
        runCatching { process.outputStream.close() }
        runCatching { process.inputStream.close() }
        runCatching { process.errorStream.close() }
        runCatching { process.destroyForcibly() }
        runCatching { process.waitFor(250L, TimeUnit.MILLISECONDS) }
    }

    /** Read-only probe preferring su (the S9E is appCanSu, and /sys/class/gpio may be SELinux-guarded
     *  for the app uid), falling back to an app-uid shell. */
    private fun probe(cmd: String, deadline: MonotonicDeadline): String =
        Su.runOutputIsolatedBounded(
            cmd,
            EXEC_MAX_BYTES,
            deadline.remainingMs().coerceAtMost(ROOT_PROBE_TIMEOUT_MS),
        )
            ?.takeIf { it.isNotBlank() }
            ?: exec(cmd, deadline)
}
