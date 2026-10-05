package io.panelassistant.android.http

import io.panelassistant.android.i18n.Strings as AppStrings

internal fun capabilityName(name: String, strings: AppStrings): String = when (name) {
    "Root (su)" -> strings.get("dashboard.capability.root_su")
    "Helper daemon" -> strings.get("dashboard.capability.helper_daemon")
    "Shizuku enhanced access" -> strings.get("dashboard.capability.shizuku")
    "Verified app update / screenshot / display" -> strings.get("dashboard.capability.verified_operations")
    "Screen brightness" -> strings.get("dashboard.capability.screen_brightness")
    "Screen on/off" -> strings.get("dashboard.capability.screen_power")
    "RGB LED" -> strings.get("dashboard.capability.rgb_led")
    "Hardware buttons" -> strings.get("dashboard.capability.hardware_buttons")
    "Reboot / reload / launcher" -> strings.get("dashboard.capability.system_actions")
    else -> name
}

internal fun capabilityNote(note: String, strings: AppStrings): String = when (note) {
    "available through root or the helper daemon" -> strings.get("dashboard.capability.note.root_or_helper")
    "available through locally approved Shizuku access; app updates remain signer-verified" ->
        strings.get("dashboard.capability.note.shizuku_verified")
    "needs supported privileged panel access" -> strings.get("dashboard.capability.note.needs_privileged_access")
    "available directly to ha-paneld" -> strings.get("dashboard.capability.note.su_direct")
    "not available directly to ha-paneld — privileged actions are routed through the helper daemon" ->
        strings.get("dashboard.capability.note.helper_routed")
    "not available directly to ha-paneld — see the individual capability rows below" ->
        strings.get("dashboard.capability.note.su_unavailable")
    "WRITE_SETTINGS granted" -> strings.get("dashboard.capability.note.write_settings_granted")
    "backlight control via helper daemon; Android setting is unchanged" ->
        strings.get("dashboard.capability.note.brightness_helper")
    "backlight control via su; Android setting is unchanged" ->
        strings.get("dashboard.capability.note.brightness_su")
    "available" -> strings.get("dashboard.capability.note.available")
    "needs su or the helper daemon" -> strings.get("dashboard.capability.note.needs_su_or_helper")
    "true backlight-off via su bl_power" -> strings.get("dashboard.capability.note.backlight_off_su")
    "true backlight-off via the helper daemon" -> strings.get("dashboard.capability.note.backlight_off_helper")
    "Rockchip /dev/ledjni (app-direct, no root)" -> strings.get("dashboard.capability.note.rockchip_led_direct")
    "Rockchip /dev/ledjni ioctl via the helper daemon (root)" ->
        strings.get("dashboard.capability.note.rockchip_led_helper")
    "sysfs LED via the helper daemon" -> strings.get("dashboard.capability.note.sysfs_led_helper")
    "no reachable LED node; needs the root helper daemon (install needs su once)" ->
        strings.get("dashboard.capability.note.led_unreachable")
    "Android sleep via KEYCODE_SLEEP; Home Assistant always wakes it, a local touch only where this panel's touchscreen is a platform wake source" ->
        strings.get("dashboard.capability.note.android_sleep")
    "accessibility key capture enabled" -> strings.get("dashboard.capability.note.buttons_accessibility")
    "blocked: installed manager signer is not trusted" -> strings.get("dashboard.capability.note.shizuku_untrusted")
    "manager missing; re-run provisioning with --shizuku" -> strings.get("dashboard.capability.note.shizuku_missing")
    "ready as shell UID; local typed operations only" -> strings.get("dashboard.capability.note.shizuku_ready")
    "disabled in ha-paneld; on the panel open Configure → toolbar overflow → Enhanced access → Enable" ->
        strings.get("dashboard.capability.note.shizuku_disabled")
    "enabled in ha-paneld, but the Shizuku service is stopped; open Shizuku and start its service" ->
        strings.get("dashboard.capability.note.shizuku_stopped")
    "service running; request and approve ha-paneld access locally" ->
        strings.get("dashboard.capability.note.shizuku_permission")
    "access denied; grant ha-paneld under Shizuku → Authorized applications" ->
        strings.get("dashboard.capability.note.shizuku_grant")
    "connecting to the locally approved Shizuku service" -> strings.get("dashboard.capability.note.shizuku_connecting")
    "blocked: unexpected Shizuku service identity or protocol" -> strings.get("dashboard.capability.note.shizuku_incompatible")
    "Shizuku could not be connected; retry from the on-panel Enhanced access dialog" ->
        strings.get("dashboard.capability.note.shizuku_failed")
    else -> capabilityNoteFamily(note, strings)
}

private fun capabilityNoteFamily(note: String, strings: AppStrings): String {
    val preferredPrefix = "adds no capability while root or the helper daemon provides the preferred route; "
    if (note.startsWith(preferredPrefix)) {
        return strings.get("dashboard.capability.note.preferred_route_prefix") + " " +
            capabilityNote(note.removePrefix(preferredPrefix), strings)
    }
    val daemon = Regex("^(running|NEEDED but not running) — (.+)$").matchEntire(note)
    if (daemon != null) {
        val state = strings.get(
            if (daemon.groupValues[1] == "running") {
                "dashboard.capability.note.daemon_running"
            } else {
                "dashboard.capability.note.daemon_needed"
            },
        )
        val detail = daemon.groupValues[2]
        val translated = when {
            detail == "the privileged control path on this sandbox-walled panel; without it, root-only controls remain unavailable" ->
                strings.get("dashboard.capability.note.daemon_sandbox_path")
            detail == "required for the profiled button backlight" ->
                strings.get("dashboard.capability.note.daemon_button_backlight")
            detail == "required for this profile's daemon-backed hardware" ->
                strings.get("dashboard.capability.note.daemon_hardware")
            else -> Regex("^required for (\\d+) profiled physical button\\(s\\), even though ordinary privileged actions can use su$")
                .matchEntire(detail)
                ?.let { match ->
                    formattedString(
                        strings,
                        "dashboard.capability.note.daemon_buttons",
                        "count" to match.groupValues[1],
                    )
                }
                ?: detail
        }
        return formattedString(
            strings,
            "dashboard.capability.note.daemon_state",
            "state" to state,
            "detail" to translated,
        )
    }
    val dimReason = note.removePrefix("DIM ONLY — ").takeIf { it.length != note.length }
    if (dimReason != null) {
        val reason = when (dimReason) {
            "needs su or the helper daemon to inject KEYCODE_SLEEP" ->
                strings.get("dashboard.capability.note.dim_keycode_sleep")
            "this panel's profile selects the brightness-zero route, which never powers the backlight down" ->
                strings.get("dashboard.capability.note.dim_brightness_zero")
            "the backlight stays powered; needs su or the helper daemon for a real off" ->
                strings.get("dashboard.capability.note.dim_backlight_powered")
            else -> dimReason
        }
        return formattedString(strings, "dashboard.capability.note.dim_only", "reason" to reason)
    }
    if (note.startsWith("needs WRITE_SETTINGS: ")) {
        return formattedString(
            strings,
            "dashboard.capability.note.needs_write_settings",
            "command" to note.removePrefix("needs WRITE_SETTINGS: "),
        )
    }
    if (note.startsWith("enable (no root): ")) {
        return formattedString(
            strings,
            "dashboard.capability.note.buttons_enable_no_root",
            "command" to note.removePrefix("enable (no root): "),
        )
    }
    val buttonPatterns = listOf(
        Regex("^accessibility key capture plus (\\d+) profiled physical button\\(s\\) on a verified helper stream$") to
            "dashboard.capability.note.buttons_accessibility_verified",
        Regex("^(\\d+) profiled physical button\\(s\\) on a verified helper stream$") to
            "dashboard.capability.note.buttons_verified",
        Regex("^accessibility key capture plus (\\d+) profiled physical button\\(s\\) on an unverified stream \\((.+)\\)$") to
            "dashboard.capability.note.buttons_accessibility_unverified_stream",
        Regex("^(\\d+) profiled physical button\\(s\\) are streaming but unverified \\((.+)\\)$") to
            "dashboard.capability.note.buttons_unverified_stream",
        Regex("^accessibility key capture works; (\\d+) profiled physical button\\(s\\) are not verified \\((.+)\\)$") to
            "dashboard.capability.note.buttons_accessibility_not_verified",
        Regex("^(\\d+) profiled physical button\\(s\\) are not verified \\((.+)\\)$") to
            "dashboard.capability.note.buttons_not_verified",
    )
    buttonPatterns.forEach { (pattern, key) ->
        pattern.matchEntire(note)?.let { match ->
            val values = mutableListOf("count" to match.groupValues[1])
            if (match.groupValues.size > 2) values += "detail" to match.groupValues[2]
            return formattedString(strings, key, *values.toTypedArray())
        }
    }
    return note
}
