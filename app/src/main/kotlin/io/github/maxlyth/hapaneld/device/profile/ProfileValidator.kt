package io.github.maxlyth.hapaneld.device.profile

import io.github.maxlyth.hapaneld.hardware.LedTransfer
import io.github.maxlyth.hapaneld.hardware.TransferCurve
import java.net.URI

internal object ProfileValidator {
    private val idPattern = Regex("^[a-z0-9](?:[a-z0-9.-]{0,126}[a-z0-9])?$")
    private val packagePattern = Regex("^[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+$")
    private val versionPattern = Regex("^[0-9]{1,6}(?:\\.[0-9]{1,6}){0,3}(?:-[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?$")
    private val contentVersionPattern = Regex("^(0|[1-9][0-9]{0,8})\\.(0|[1-9][0-9]{0,8})\\.(0|[1-9][0-9]{0,8})(?:-[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?$")
    private val licensePattern = Regex("^[A-Za-z0-9][A-Za-z0-9.+-]{0,63}(?: (?:AND|OR) [A-Za-z0-9][A-Za-z0-9.+-]{0,63})*$")
    private val eventTypePattern = Regex("^KEYCODE_[A-Z0-9_]+$")

    fun validate(document: ProfileDocument, coreVersion: String, bundled: Boolean): List<ProfileIssue> {
        val issues = mutableListOf<ProfileIssue>()
        fun reject(
            path: String,
            message: String,
            code: String? = null,
            params: Map<String, String> = emptyMap(),
        ) { issues += ProfileIssue(ProfileIssueSeverity.ERROR, path, message, code?.let { runCatching { ProfilePresentation(it, params) }.getOrNull() }) }
        fun warn(
            path: String,
            message: String,
            code: String? = null,
            params: Map<String, String> = emptyMap(),
        ) { issues += ProfileIssue(ProfileIssueSeverity.WARNING, path, message, code?.let { runCatching { ProfilePresentation(it, params) }.getOrNull() }) }
        fun boundedText(value: String?, path: String, max: Int = 100) {
            if (value != null && (value.isBlank() || value.length > max || value.any { it.code < 0x20 || it.code == 0x7f })) {
                reject(
                    path,
                    "Must contain 1-$max characters without control characters.",
                    "bounded-text",
                    mapOf("min" to "1", "max" to max.toString()),
                )
            }
        }
        fun containsFormatControl(value: String): Boolean {
            var index = 0
            while (index < value.length) {
                val codePoint = Character.codePointAt(value, index)
                if (Character.getType(codePoint) == Character.FORMAT.toInt()) return true
                index += Character.charCount(codePoint)
            }
            return false
        }
        fun validHttpsUrl(value: String, path: String) {
            val uri = runCatching { URI(value) }.getOrNull()
            if (
                value.length > 500 || uri?.scheme != "https" || uri.host.isNullOrBlank() ||
                uri.userInfo != null
            ) {
                reject(path, "Must be an absolute HTTPS URL without user information, at most 500 characters.", "invalid-https-url")
            }
        }
        if (document.schema != ProfileMetadata.SCHEMA) reject(
            "schema",
            "Unsupported schema ${document.schema}; expected ${ProfileMetadata.SCHEMA}.",
            "unsupported-schema",
            mapOf("actual" to document.schema.toString(), "expected" to ProfileMetadata.SCHEMA.toString()),
        )
        if (!idPattern.matches(document.id) || ".." in document.id) reject("id", "Use 1-128 lowercase letters or digits with interior dots and hyphens; consecutive dots are not allowed.", "profile-id-invalid")
        if (!contentVersionPattern.matches(document.version)) reject("version", "Expected semantic version MAJOR.MINOR.PATCH.", "semantic-version-required")
        fun boundedRequiredText(value: String, path: String, max: Int = 100) {
            if (value.isBlank() || value.length > max) {
                reject(
                    path,
                    "Must contain 1-$max characters.",
                    "bounded-text-basic",
                    mapOf("min" to "1", "max" to max.toString()),
                )
            }
        }
        boundedRequiredText(document.displayName, "display_name")
        boundedRequiredText(document.socClass, "soc_class")
        boundedRequiredText(document.metadata.author, "metadata.author")
        document.metadata.source?.let { validHttpsUrl(it, "metadata.source") }
        if (document.metadata.links.size > 8) reject("metadata.links", "At most 8 display links are allowed.", "profile-link-count-limit")
        val seenLinkUrls = mutableSetOf<String>()
        val seenLinkLabels = mutableSetOf<String>()
        document.metadata.source?.let {
            seenLinkUrls.add(it)
            seenLinkLabels.add("panel details")
        }
        document.metadata.links.forEachIndexed { index, link ->
            val path = "metadata.links[$index]"
            boundedText(link.label, "$path.label", 48)
            if (containsFormatControl(link.label)) {
                reject("$path.label", "Must not contain Unicode format controls.", "unicode-format-controls-forbidden")
            }
            validHttpsUrl(link.url, "$path.url")
            if (!seenLinkUrls.add(link.url)) reject("$path.url", "Duplicate profile link URL.", "duplicate-profile-link-url")
            if (!seenLinkLabels.add(link.label.lowercase())) reject("$path.label", "Profile link labels must be unique.", "duplicate-profile-link-label")
        }
        document.soc?.let { soc ->
            boundedText(soc.model, "soc.model", 100)
            soc.introducedYear?.let { year ->
                if (year !in 1970..2100) reject("soc.introduced_year", "Must be between 1970 and 2100.", "introduced-year-range")
            }
            if (soc.cpuCores.size > 8) reject("soc.cpu_cores", "At most 8 CPU core clusters are allowed.", "cpu-cluster-count-limit")
            val architectures = mutableSetOf<String>()
            var totalCores = 0
            soc.cpuCores.forEachIndexed { index, cluster ->
                val path = "soc.cpu_cores[$index]"
                boundedText(cluster.architecture, "$path.architecture", 64)
                if (cluster.count !in 1..128) reject("$path.count", "Must be between 1 and 128 cores.", "cpu-core-count-range")
                totalCores += cluster.count.coerceAtLeast(0)
                if (!architectures.add(cluster.architecture.lowercase())) {
                    reject("$path.architecture", "CPU core architectures must be unique within the SoC description.", "duplicate-cpu-architecture")
                }
            }
            if (totalCores > 256) reject("soc.cpu_cores", "At most 256 total CPU cores are allowed.", "cpu-total-count-limit")
        }
        if (!licensePattern.matches(document.metadata.license)) reject("metadata.license", "Expected a bounded SPDX-style license expression.", "license-expression-invalid")
        if (document.metadata.testedFirmware.size > 32 || document.metadata.testedFirmware.any { it.isBlank() || it.length > 120 }) reject("metadata.tested_firmware", "Use at most 32 non-empty entries of at most 120 characters.", "tested-firmware-bounds")
        if (document.metadata.limitations.size > 32 || document.metadata.limitations.any { it.isBlank() || it.length > 500 }) reject("metadata.limitations", "Use at most 32 non-empty entries of at most 500 characters.", "limitations-bounds")
        if (document.match.priority !in 0..1000) reject("match.priority", "Must be between 0 and 1000.", "match-priority-range")
        if (document.match.fallback && (!bundled || document.id != "generic")) reject("match.fallback", "Only the bundled generic profile may be a fallback.", "generic-fallback-only")
        if (!document.match.fallback && document.match.any.isEmpty()) reject("match.any", "At least one match group is required.", "match-group-required")
        if (document.match.any.size > 64) reject("match.any", "At most 64 match groups are allowed.", "match-group-count-limit")
        document.match.any.forEachIndexed { groupIndex, group ->
            if (group.priority !in 0..1000) reject("match.any[$groupIndex].priority", "Must be between 0 and 1000.", "match-priority-range")
            if (group.all.isEmpty()) reject("match.any[$groupIndex].all", "A group must contain at least one predicate.", "match-predicate-required")
            if (group.all.size > 8) reject("match.any[$groupIndex].all", "At most 8 predicates are allowed per group.", "match-predicate-count-limit")
            group.all.forEachIndexed { predicateIndex, predicate ->
                val path = "match.any[$groupIndex].all[$predicateIndex].values"
                if (predicate.values.isEmpty() || predicate.values.size > 32) reject(path, "Provide 1-32 values.", "match-values-count-range")
                predicate.values.forEach {
                    if (it.isBlank() || it.length > 100 || it != it.lowercase() || it.any { char -> char.code < 0x20 || char.code == 0x7f }) reject(path, "Values must be non-empty lowercase strings without controls, at most 100 characters.", "match-value-invalid")
                }
            }
        }
        document.requires.minCoreVersion?.let {
            if (it.length > 64 || !versionPattern.matches(it)) reject("requires.min_core_version", "Expected a bounded dotted release version.", "dotted-release-version-required")
            else if (compareVersions(coreVersion, it) < 0) reject(
                "requires.min_core_version",
                "Requires ha-paneld $it or newer; this core is $coreVersion.",
                "core-version-required",
                mapOf("required" to it, "current" to coreVersion),
            )
        }
        val knownDrivers = ProfileMetadata.drivers.mapTo(mutableSetOf()) { it.id }
        document.requires.drivers.forEach {
            if (it !in knownDrivers) reject("requires.drivers", "Unknown core driver '$it'.", "unknown-core-driver", mapOf("value" to it))
        }
        if (document.platform.suForm !in setOf("none", "android", "toolbox")) reject("platform.su_form", "Unknown su form '${document.platform.suForm}'.", "unknown-su-form", mapOf("value" to document.platform.suForm))
        if (document.platform.suForm == "none" && document.platform.appCanSu) reject("platform.app_can_su", "Cannot be true when su_form is none.", "app-su-needs-su-form")
        if (document.hardware.led.mechanism !in setOf("none", "autodetect", "rk3576-ioctl", "rk3576-ioctl-daemon", "sysfs-daemon")) {
            reject("hardware.led.mechanism", "Unknown LED mechanism '${document.hardware.led.mechanism}'.", "unknown-led-mechanism", mapOf("value" to document.hardware.led.mechanism))
        }
        document.hardware.backlight?.let { backlight ->
            val curve = runCatching { backlight.curve.toTransferCurve() }.onFailure {
                reject("hardware.backlight", "Invalid backlight transfer: ${it.message}.")
            }.getOrNull()
            if (backlight.route != null && backlight.route !in BACKLIGHT_ROUTES) {
                reject("hardware.backlight.route", "Unknown backlight route '${backlight.route}'; expected setting or node.")
            } else if (backlight.route == null && curve != null && curve != TransferCurve.Identity) {
                reject("hardware.backlight.route", "A backlight curve must name its route: setting or node.")
            }
        }
        document.hardware.buttonBacklight?.let { curve ->
            if (!document.hardware.hasButtonBacklight) {
                reject("hardware.button_backlight", "A key-backlight curve needs has_button_backlight: true.")
            }
            runCatching { curve.toTransferCurve() }.onFailure {
                reject("hardware.button_backlight", "Invalid key-backlight transfer: ${it.message}.")
            }
        }
        val led = document.hardware.led
        if (led.transfer == LedTransfer.RK3576_FOUR_BIT) {
            if (led.gamma != null || led.points != null || led.floor != null) {
                reject("hardware.led", "The rk3576-four-bit stub takes no gamma, points or floor.")
            }
        } else if (led.transfer in LedTransfer.NAMES) {
            runCatching { led.curve.toTransferCurve() }.onFailure {
                reject("hardware.led", "Invalid LED transfer: ${it.message}.")
            }
        }
        if (document.hardware.led.transfer !in LedTransfer.NAMES) reject("hardware.led.transfer", "Unknown core transfer '${document.hardware.led.transfer}'.", "unknown-core-transfer", mapOf("value" to document.hardware.led.transfer))
        if (document.hardware.screenOff !in setOf("brightness-zero", "su-blpower", "daemon-blpower", "keyevent")) reject("hardware.screen_off", "Unknown screen-off route '${document.hardware.screenOff}'.", "unknown-screen-off-route", mapOf("value" to document.hardware.screenOff))
        if (document.hardware.screenOff == "su-blpower" && !document.platform.appCanSu) reject("hardware.screen_off", "su-blpower requires app_can_su: true.", "su-blpower-needs-app-su")
        if (document.hardware.screenOff == "daemon-blpower" && document.platform.appCanSu) reject("hardware.screen_off", "daemon-blpower is reserved for sandbox-walled profiles.", "daemon-blpower-sandbox-only")
        if (document.hardware.led.mechanism in setOf("rk3576-ioctl-daemon", "sysfs-daemon") && document.platform.appCanSu) reject("hardware.led.mechanism", "Daemon-only LED routes are reserved for sandbox-walled profiles.", "daemon-led-sandbox-only")
        validateAllowedPath(
            document.hardware.zigbeeGatewayDir,
            "hardware.zigbee_gateway_dir",
            setOf("/vendor/bin/siliconlabs_host"),
            issues,
        )
        val relayClasses = setOf("/sys/class/relay", "/sys/class/st_relay", "/sys/class/strelay")
        validateAllowedPath(document.hardware.relayBase, "hardware.relay_base", relayClasses, issues)
        document.hardware.relayBaseFallbacks.forEachIndexed { index, path ->
            validateAllowedPath(path, "hardware.relay_base_fallbacks[$index]", relayClasses, issues)
        }
        if (document.hardware.relayBaseFallbacks.size > 3) reject("hardware.relay_base_fallbacks", "At most 3 fallback classes are allowed.", "relay-fallback-count-limit")
        if (document.hardware.relayBaseFallbacks.size != document.hardware.relayBaseFallbacks.toSet().size || document.hardware.relayBase in document.hardware.relayBaseFallbacks) {
            reject("hardware.relay_base_fallbacks", "Relay class paths must be unique.", "relay-paths-unique")
        }
        document.hardware.buttonLedGpioBase?.let { if (it !in 0..4092) reject("hardware.button_led_gpio_base", "GPIO block base must be between 0 and 4092.", "gpio-block-base-range") }
        document.sensors.proximityGpio?.let {
            if (it !in 0..4095) reject("sensors.proximity_gpio", "GPIO must be between 0 and 4095.", "gpio-range")
        }
        document.sensors.proximityCalibration?.let { calibration ->
            val path = "sensors.proximity_calibration"
            if (!calibration.presenceSupported && (calibration.formatVersion != 2 || calibration.wave == null)) {
                reject("$path.presence_supported", "Wave-only calibration requires format 2 and a verified wave capability.")
            }
            if (calibration.formatVersion !in 1..2) reject("$path.format_version", "Expected calibration format 1 or 2.")
            if (calibration.formatVersion == 1 && calibration.wave != null) reject("$path.wave", "Separate wave calibration requires format 2.")
            if (calibration.revision < 1) reject("$path.revision", "Calibration revision must be positive.")
            if (calibration.mode !in setOf("binary", "ranged")) reject("$path.mode", "Expected binary or ranged.")
            boundedText(calibration.verification, "$path.verification", 500)
            if (!calibration.clearRaw.isFinite()) reject("$path.clear_raw", "Clear observation must be finite.")
            if (!calibration.nearRaw.isFinite()) reject("$path.near_raw", "Near observation must be finite.")
            if (calibration.clearRaw == calibration.nearRaw || !(calibration.nearRaw - calibration.clearRaw).isFinite()) {
                reject(path, "Clear and near observations must have a finite, nonzero separation.")
            }
            if (calibration.mode == "binary" && setOf(calibration.clearRaw, calibration.nearRaw) != setOf(0f, 1f)) {
                reject(path, "Binary observations must be zero and one, in either polarity.")
            }
            if (!calibration.clearExit.isFinite() || !calibration.nearEnter.isFinite() ||
                calibration.clearExit <= 0f || calibration.nearEnter >= 1f || calibration.clearExit >= calibration.nearEnter) {
                reject(path, "Thresholds must satisfy 0 < clear_exit < near_enter < 1.")
            }
            if (calibration.debounceMs !in 50..1000) reject("$path.debounce_ms", "Debounce must be 50-1000 ms.")
            if (calibration.clearArmMs !in 200..10000 || calibration.clearArmMs < calibration.debounceMs) {
                reject("$path.clear_arm_ms", "Clear arming must be 200-10000 ms and at least the debounce interval.")
            }
            if (calibration.minimumNearMs !in 50..2000 || calibration.minimumNearMs < calibration.debounceMs) {
                reject("$path.minimum_near_ms", "Minimum near duration must be 50-2000 ms and at least the debounce interval.")
            }
            if (calibration.maximumNearMs !in 200..10000 || calibration.maximumNearMs <= calibration.minimumNearMs) {
                reject("$path.maximum_near_ms", "Maximum near duration must be 200-10000 ms and exceed the minimum.")
            }
            if (calibration.cooldownMs !in 200..10000) reject("$path.cooldown_ms", "Cooldown must be 200-10000 ms.")
            calibration.wave?.let { wave ->
                val wavePath = "$path.wave"
                if ((wave.nearRaw > wave.clearRaw) != (calibration.nearRaw > calibration.clearRaw)) {
                    reject(wavePath, "Wave and presence observations must agree on the near direction.")
                }
                if (wave.pattern !in setOf("single", "double")) reject("$wavePath.pattern", "Expected single or double.")
                if (!wave.clearRaw.isFinite()) reject("$wavePath.clear_raw", "Clear observation must be finite.")
                if (!wave.nearRaw.isFinite()) reject("$wavePath.near_raw", "Near observation must be finite.")
                if (wave.clearRaw == wave.nearRaw || !(wave.nearRaw - wave.clearRaw).isFinite()) {
                    reject(wavePath, "Clear and near observations must have a finite, nonzero separation.")
                }
                if (calibration.mode == "binary" && setOf(wave.clearRaw, wave.nearRaw) != setOf(0f, 1f)) {
                    reject(wavePath, "Binary observations must be zero and one, in either polarity.")
                }
                if (!wave.clearExit.isFinite() || !wave.nearEnter.isFinite() ||
                    wave.clearExit <= 0f || wave.nearEnter >= 1f || wave.clearExit >= wave.nearEnter) {
                    reject(wavePath, "Thresholds must satisfy 0 < clear_exit < near_enter < 1.")
                }
                if (wave.debounceMs !in 50..1000) reject("$wavePath.debounce_ms", "Debounce must be 50-1000 ms.")
                if (wave.clearArmMs !in 200..10000 || wave.clearArmMs < wave.debounceMs) {
                    reject("$wavePath.clear_arm_ms", "Clear arming must be 200-10000 ms and at least the debounce interval.")
                }
                if (wave.minimumNearMs !in 50..2000 || wave.minimumNearMs < wave.debounceMs) {
                    reject("$wavePath.minimum_near_ms", "Minimum near duration must be 50-2000 ms and at least the debounce interval.")
                }
                if (wave.maximumNearMs !in 200..10000 || wave.maximumNearMs <= wave.minimumNearMs) {
                    reject("$wavePath.maximum_near_ms", "Maximum near duration must be 200-10000 ms and exceed the minimum.")
                }
                if (wave.cooldownMs !in 200..10000) reject("$wavePath.cooldown_ms", "Cooldown must be 200-10000 ms.")
                if (wave.maxInterWaveGapMs !in 300..10000) reject("$wavePath.max_inter_wave_gap_ms", "Inter-wave gap must be 300-10000 ms.")
            }
        }
        if (document.sensors.roomTempOffsetC !in -30f..30f) reject("sensors.room_temp_offset_c", "Offset must be between -30 and 30 °C.", "room-temperature-offset-range")
        boundedText(document.sensors.proximityTechnology, "sensors.proximity_technology")
        boundedText(document.sensors.lightTechnology, "sensors.light_technology")
        if (document.identity.modelLabelStrategy !in setOf("display-name", "nspanel-product-version")) reject("identity.model_label_strategy", "Unknown core strategy.", "unknown-core-strategy")
        boundedText(document.identity.manufacturer, "identity.manufacturer")
        boundedText(document.identity.model, "identity.model")
        when (val density = document.provisioning.display.density) {
            is ProfileDensity.Fixed -> if (density.value !in 80..640) reject("provisioning.display.density", "Density must be between 80 and 640 dpi.", "density-range")
            is ProfileDensity.Strategy -> if (density.id != "nspanel-variant") reject("provisioning.display.density", "Unknown core strategy '${density.id}'.", "unknown-core-strategy-value", mapOf("value" to density.id))
            null -> Unit
        }
        document.provisioning.display.fontScale?.let {
            if (it !in 0.5f..1.5f) reject("provisioning.display.font_scale", "Font scale must be between 0.5 and 1.5.", "font-scale-range")
        }
        document.display.physicalPpi?.let { if (it !in 50..1000) reject("display.physical_ppi", "Physical PPI must be between 50 and 1000.", "physical-ppi-range") }
        val geometry = document.display.geometry
        if (geometry.isNotEmpty() && document.display.physicalPpi != null) {
            reject("display.physical_ppi", "Use display.geometry or the legacy display.physical_ppi, not both.", "display-geometry-invalid")
        }
        if (geometry.size > 8) reject("display.geometry", "At most 8 display geometry variants are allowed.", "display-geometry-invalid")
        val geometrySelectors = mutableSetOf<Pair<List<Int>, Set<String>>>()
        geometry.forEachIndexed { index, entry ->
            val path = "display.geometry[$index]"
            boundedText(entry.variant, "$path.variant", 40)
            boundedText(entry.evidenceNote, "$path.evidence_note", 200)
            if (entry.productVersionPrefixes.size > 8) reject("$path.product_version_prefixes", "At most 8 product version prefixes are allowed.", "display-geometry-invalid")
            entry.productVersionPrefixes.forEachIndexed { prefixIndex, prefix ->
                boundedText(prefix, "$path.product_version_prefixes[$prefixIndex]", 64)
            }
            if (entry.widthPx !in 16..16384 || entry.heightPx !in 16..16384) {
                reject(path, "Physical width_px and height_px must each be between 16 and 16384.", "display-geometry-invalid")
            }
            val diagonal = entry.activeDiagonalIn
            val widthMm = entry.activeWidthMm
            val heightMm = entry.activeHeightMm
            when {
                diagonal != null && (widthMm != null || heightMm != null) ->
                    reject(path, "State the active size as a diagonal or as width and height, not both.", "display-geometry-invalid")
                diagonal != null -> if (diagonal !in 1f..120f) reject("$path.active_diagonal_in", "Active diagonal must be between 1 and 120 inches.", "display-geometry-invalid")
                widthMm != null && heightMm != null -> if (widthMm !in 5f..3000f || heightMm !in 5f..3000f) {
                    reject(path, "Active width and height must each be between 5 and 3000 mm.", "display-geometry-invalid")
                }
                else -> reject(path, "An active diagonal, or both active width and height, is required.", "display-geometry-invalid")
            }
            DisplayGeometryResolver.derive(entry, entry.widthPx, entry.heightPx)?.let {
                if (it.ppi !in 50.0..1000.0) reject(path, "Physical PPI must be between 50 and 1000.", "physical-ppi-range")
            }
            entry.factoryBaseDpi?.let { if (it !in 80..640) reject("$path.factory_base_dpi", "Density must be between 80 and 640 dpi.", "density-range") }
            val selector = listOf(entry.widthPx, entry.heightPx).sorted() to entry.productVersionPrefixes.toSet()
            if (!geometrySelectors.add(selector)) reject(path, "Another variant already selects these pixels and product versions.", "display-geometry-invalid")
        }
        document.hardware.touchClickGain?.let {
            if (it !in 0.05f..1f) reject("hardware.touch_click_gain", "Touch-click gain must be between 0.05 and 1.0.", "touch-click-gain-range")
        }
        if (document.input.evdevButtons.size > 32) reject("input.evdev_buttons", "At most 32 evdev mappings are allowed.", "evdev-mapping-count-limit")
        val evdevCodes = mutableSetOf<Pair<Boolean, Int>>()
        document.input.evdevButtons.forEachIndexed { index, button ->
            val path = "input.evdev_buttons[$index]"
            if (!Regex("^/dev/input/event[0-9]{1,3}$").matches(button.node)) {
                reject("$path.node", "Only /dev/input/eventN device nodes are supported.", "evdev-device-node-invalid")
            }
            if (button.code !in 1..767) reject("$path.code", "Linux input code must be between 1 and 767.", "linux-input-code-range")
            if (button.eventType.length > 64 || !eventTypePattern.matches(button.eventType)) reject("$path.event_type", "Use KEYCODE_ plus uppercase letters, digits, or underscores, at most 64 characters.", "keycode-format-invalid")
            if (!evdevCodes.add(button.sw to button.code)) reject(path, "Duplicate (sw, code) mapping.", "duplicate-evdev-mapping")
        }
        document.cpu.governors?.forEach { (tier, governor) ->
            if (tier !in setOf("Performance", "Efficiency", "Auto")) reject("cpu.governors.$tier", "Unknown HA CPU tier.", "unknown-ha-cpu-tier")
            if (!Regex("^[a-z][a-z0-9_-]{0,31}$").matches(governor)) reject("cpu.governors.$tier", "Invalid Linux governor name.", "linux-governor-name-invalid")
        }
        document.provisioning.software.webView?.artifact?.let { artifact ->
            if (artifact !in ProfileArtifacts.webViews) {
                reject("provisioning.software.webview.artifact", "Unknown core-owned WebView artifact '$artifact'.", "unknown-webview-artifact", mapOf("value" to artifact))
            }
        }
        document.provisioning.software.companion?.maxVersion?.let { version ->
            if (version.length > 64 || !versionPattern.matches(version)) {
                reject("provisioning.software.companion.max_version", "Expected a bounded dotted release version.", "dotted-release-version-required")
            }
        }
        if (document.provisioning.packages.size > 128) {
            reject("provisioning.packages", "At most 128 package desired-state entries are allowed.", "package-count-limit")
        }
        val seenPackages = mutableSetOf<String>()
        document.provisioning.packages.forEachIndexed { index, candidate ->
            val path = "provisioning.packages[$index]"
            if (!packagePattern.matches(candidate.packageName)) reject("$path.package", "Invalid Android package name.", "android-package-name-invalid")
            if (!seenPackages.add(candidate.packageName)) reject("$path.package", "Duplicate package desired state.", "duplicate-package-desired-state")
            if (candidate.tags.size > 8 || candidate.tags.any { !Regex("^[a-z][a-z0-9-]{0,23}$").matches(it) }) reject("$path.tags", "Use at most 8 lowercase tags of at most 24 characters.", "package-tag-bounds")
            if (candidate.note.length > 500) reject("$path.note", "Note must not exceed 500 characters.", "package-note-length-limit")
        }
        if (document.provisioning.recipes.size > 32) reject("provisioning.recipes", "At most 32 recipes are allowed.", "recipe-count-limit")
        val seenRecipes = mutableSetOf<String>()
        document.provisioning.recipes.forEachIndexed { index, recipe ->
            val path = "provisioning.recipes[$index].id"
            if (!seenRecipes.add(recipe.id)) reject(path, "Duplicate recipe selection.", "duplicate-recipe-selection")
            if (recipe.id !in ProfileMetadata.recipes) reject(path, "Unknown core-owned recipe '${recipe.id}'.", "unknown-core-recipe", mapOf("value" to recipe.id))
        }
        expectedDrivers(document).forEach { driver ->
            if (driver !in document.requires.drivers) reject("requires.drivers", "Capability requires core driver '$driver'.", "capability-driver-required", mapOf("value" to driver))
        }
        document.requires.drivers.filterNot { it in expectedDrivers(document) }.forEach {
            warn("requires.drivers", "Driver '$it' is declared but no field currently uses it.", "unused-driver-declared", mapOf("value" to it))
        }
        return issues
    }

    fun risks(document: ProfileDocument, overridesBundled: Boolean): Set<ProfileRisk> = buildSet {
        if (document.platform.appCanSu || document.hardware.screenOff != "brightness-zero" || document.requires.drivers.any { ProfileMetadata.drivers.find { d -> d.id == it }?.privileged == true }) add(ProfileRisk.ROOT_PATHS)
        if (document.hardware.relayBase != null || document.hardware.relayBaseFallbacks.isNotEmpty() || document.hardware.buttonLedGpioBase != null || document.sensors.proximityGpio != null) add(ProfileRisk.RELAY_OR_GPIO_WRITES)
        if (document.input.evdevButtons.isNotEmpty()) add(ProfileRisk.EVDEV_READ)
        if (document.input.evdevButtons.any { it.grab }) add(ProfileRisk.EVDEV_GRAB)
        if (document.provisioning.packages.any { it.importance == ProfileProvisioningImportance.RECOMMENDED }) {
            add(ProfileRisk.PACKAGE_DISABLE_RECOMMENDATIONS)
        }
        if (document.provisioning.software.webView != null) add(ProfileRisk.WEBVIEW_INSTALL)
        if (overridesBundled) add(ProfileRisk.OVERRIDES_BUNDLED)
    }

    private fun expectedDrivers(document: ProfileDocument): Set<String> = buildSet {
        when (document.platform.suForm) {
            "android" -> if (document.platform.appCanSu) add("access.android-su")
            "toolbox" -> if (document.platform.appCanSu) add("access.toolbox-su")
        }
        if (document.hardware.led.mechanism != "none") add("led.${document.hardware.led.mechanism}")
        add("screen.${document.hardware.screenOff}")
        if (document.hardware.zigbeeGatewayDir != null) add("radio.siliconlabs-host")
        if (document.hardware.relayBase != null || document.hardware.relayBaseFallbacks.isNotEmpty()) add("relay.sysfs")
        if (document.hardware.buttonLedGpioBase != null) add("relay.gpio-button-led")
        if (document.hardware.hasButtonBacklight) add("input.button-backlight")
        if (document.sensors.proximityTechnology != null || document.sensors.lightTechnology != null) add("sensor.android")
        if (document.sensors.proximityGpio != null) add("sensor.gpio-proximity")
        if (document.sensors.cht8305) add("sensor.cht8305-daemon")
        if (document.sensors.vi530x) add("sensor.vi530x-daemon")
        if (document.input.evdevButtons.isNotEmpty()) add("input.evdev")
        if (document.provisioning.software.webView != null) add("update.webview")
    }

    private fun validateAllowedPath(
        value: String?,
        path: String,
        allowed: Set<String>,
        issues: MutableList<ProfileIssue>,
    ) {
        if (value == null) return
        if (value !in allowed) {
            issues += ProfileIssue(
                ProfileIssueSeverity.ERROR,
                path,
                "Unsupported privileged path; allowed values: ${allowed.sorted().joinToString()}.",
                ProfilePresentation(
                    "unsupported-privileged-path",
                    mapOf("allowed" to allowed.sorted().joinToString()),
                ),
            )
        }
    }

    internal fun compareVersions(left: String, right: String): Int {
        val a = ParsedVersion.parse(left)
        val b = ParsedVersion.parse(right)
        if (a == null || b == null) return if (left == right) 0 else left.compareTo(right)
        for (index in 0 until maxOf(a.release.size, b.release.size)) {
            val compared = (a.release.getOrNull(index) ?: 0).compareTo(b.release.getOrNull(index) ?: 0)
            if (compared != 0) return compared
        }
        if (a.preRelease == null && b.preRelease == null) return 0
        if (a.preRelease == null) return 1
        if (b.preRelease == null) return -1
        for (index in 0 until maxOf(a.preRelease.size, b.preRelease.size)) {
            val x = a.preRelease.getOrNull(index) ?: return -1
            val y = b.preRelease.getOrNull(index) ?: return 1
            val xNumber = x.toIntOrNull()
            val yNumber = y.toIntOrNull()
            val compared = when {
                xNumber != null && yNumber != null -> xNumber.compareTo(yNumber)
                xNumber != null -> -1
                yNumber != null -> 1
                else -> comparePreReleaseIdentifier(x, y)
            }
            if (compared != 0) return compared
        }
        return 0
    }

    private fun comparePreReleaseIdentifier(left: String, right: String): Int {
        val token = Regex("[A-Za-z-]+|[0-9]+")
        val a = token.findAll(left).map { it.value }.toList()
        val b = token.findAll(right).map { it.value }.toList()
        for (index in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrNull(index) ?: return -1
            val y = b.getOrNull(index) ?: return 1
            val xNumeric = x.all(Char::isDigit)
            val yNumeric = y.all(Char::isDigit)
            val compared = when {
                xNumeric && yNumeric -> compareNumericText(x, y)
                xNumeric -> -1
                yNumeric -> 1
                else -> x.compareTo(y)
            }
            if (compared != 0) return compared
        }
        return left.compareTo(right)
    }

    private fun compareNumericText(left: String, right: String): Int {
        val a = left.trimStart('0').ifEmpty { "0" }
        val b = right.trimStart('0').ifEmpty { "0" }
        return a.length.compareTo(b.length).takeIf { it != 0 } ?: a.compareTo(b)
    }

    private data class ParsedVersion(val release: List<Int>, val preRelease: List<String>?) {
        companion object {
            fun parse(value: String): ParsedVersion? {
                if (!versionPattern.matches(value)) return null
                val release = value.substringBefore('-').split('.').map { it.toIntOrNull() ?: return null }
                val suffix = value.substringAfter('-', "").takeIf { '-' in value }?.split('.')
                return ParsedVersion(release, suffix)
            }
        }
    }
}

internal val BACKLIGHT_ROUTES = setOf("setting", "node")
