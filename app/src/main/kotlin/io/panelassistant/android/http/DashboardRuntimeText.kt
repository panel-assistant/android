package io.panelassistant.android.http

import io.panelassistant.android.RendererAdmissionPresentation
import io.panelassistant.android.i18n.Strings as AppStrings

/** Translate only closed, exact runtime states. Evidence-bearing and backend-origin detail remains verbatim. */
private fun runtimeValue(value: String, strings: AppStrings): String = when (value) {
    "on" -> strings.get("dashboard.value.on")
    "off" -> strings.get("dashboard.value.off")
    "none" -> strings.get("dashboard.value.none")
    "unavailable" -> strings.get("dashboard.common.unavailable")
    "Relaxed" -> strings.get("dashboard.runtime.security_relaxed")
    "Hardened · high-impact remote actions need physical on-panel approval" ->
        strings.get("dashboard.runtime.security_hardened")
    "idle" -> strings.get("dashboard.runtime.audio_idle")
    "queued" -> strings.get("dashboard.runtime.audio_queued")
    "active" -> strings.get("dashboard.runtime.audio_active")
    "closed" -> strings.get("dashboard.runtime.audio_closed")
    "watching" -> strings.get("dashboard.runtime.ha_watching")
    "connection lost" -> strings.get("dashboard.runtime.ha_connection_lost")
    "watching; Home Assistant does not permit WebSocket lifecycle events for this user" ->
        strings.get("dashboard.runtime.ha_events_refused")
    "failed" -> strings.get("dashboard.runtime.audio_failed")
    "disabled" -> strings.get("dashboard.runtime.disabled")
    "not measured; this panel holds no authenticated Home Assistant socket" ->
        strings.get("dashboard.runtime.ha_network_not_measured")
    "external renderer · Home Assistant connection not observed by ha-paneld" ->
        strings.get("dashboard.runtime.external_renderer_unobserved")
    else -> runtimeValueFamily(value, strings)
}

internal fun localizedRuntimeValue(key: String, value: String, strings: AppStrings): String = when (key) {
    "HA lifecycle" -> localizedHaLifecycle(value, strings)
    "HA renderer" -> localizedRendererRuntime(value, strings)
    "MQTT state" -> localizedMqttRuntime(value, strings)
    "App database" -> localizedDatabaseRuntime(value, strings)
    "Camera" -> localizedCameraRuntime(value, strings)
    else -> runtimeValue(value, strings)
}

/** Translate the closed lifecycle presentation without re-reading a different owner's snapshot. */
private fun localizedHaLifecycle(value: String, strings: AppStrings): String {
    if (value == "Home Assistant is back online.") return strings.get("shell.runtime.ha_lifecycle.back_online")
    val parts = value.split(" · ")
    if (parts.size != 3) return runtimeValue(value, strings)
    val headline = when (parts[0]) {
        "Taking longer than usual" -> strings.get("shell.runtime.ha_lifecycle.taking_longer")
        "Home Assistant is starting — controls will return shortly." -> strings.get("shell.runtime.ha_lifecycle.starting")
        "Home Assistant is shutting down — controls may be temporarily unavailable." -> strings.get("shell.runtime.ha_lifecycle.shutting_down")
        "Home Assistant has gone offline — controls may be temporarily unavailable." -> strings.get("shell.runtime.ha_lifecycle.offline")
        else -> return value
    }
    val reason = when (parts[1]) {
        "Home Assistant restart" -> strings.get("shell.runtime.ha_lifecycle.reason_restart")
        "Host reboot" -> strings.get("shell.runtime.ha_lifecycle.reason_host_reboot")
        "Home Assistant Core update" -> strings.get("shell.runtime.ha_lifecycle.reason_core_update")
        "Reason unknown" -> strings.get("shell.runtime.ha_lifecycle.reason_unknown")
        else -> return value
    }
    val forecast = if (parts[2] == "Time back has not been measured yet") strings.get("shell.runtime.ha_lifecycle.not_measured")
    else {
        val duration = Regex("(?:Expected back in about )?(\\d+) (sec|min|h)( past the estimate)?").matchEntire(parts[2]) ?: return value
        val quantified = strings.get(when (duration.groupValues[2]) {
            "h" -> "shell.runtime.ha_lifecycle.duration_hours"
            "min" -> "shell.runtime.ha_lifecycle.duration_minutes"
            else -> "shell.runtime.ha_lifecycle.duration_seconds"
        }).replace("{value}", duration.groupValues[1])
        strings.get(if (duration.groupValues[3].isEmpty()) "shell.runtime.ha_lifecycle.expected_in" else "shell.runtime.ha_lifecycle.overdue")
            .replace("{duration}", quantified)
    }
    return "$headline · $reason · $forecast"
}

private fun localizedRendererRuntime(value: String, strings: AppStrings): String {
    if (!value.startsWith("built-in · ")) return runtimeValue(value, strings)
    val admitted = Regex("^(.*) · admitted ([^·]+) ago$").matchEntire(value)
    var summary = (admitted?.groupValues?.get(1) ?: value).removePrefix("built-in · ")
    val themeSuffix = RendererAdmissionPresentation.OVERRIDDEN_SUMMARY_SUFFIX
    val themeOverridden = summary.endsWith(themeSuffix)
    if (themeOverridden) summary = summary.removeSuffix(themeSuffix)
    val translated = when (summary) {
        "dashboard rendered" -> strings.get("dashboard.runtime.renderer.rendered")
        "dashboard rendered; admitted on a previously verified Home Assistant version" ->
            strings.get("dashboard.runtime.renderer.rendered_cached")
        "admitted on a previously verified Home Assistant version; the dashboard has not connected" ->
            strings.get("dashboard.runtime.renderer.admitted_cached")
        "admitted; the dashboard has not connected yet" ->
            strings.get("dashboard.runtime.renderer.admitted_waiting")
        "checking Home Assistant compatibility" -> strings.get("dashboard.runtime.renderer.checking")
        "no admission observed for the current renderer" -> strings.get("dashboard.runtime.renderer.unobserved")
        else -> return value // Classified blocked detail is uncommon diagnostic evidence.
    }.let {
        if (themeOverridden) it + strings.get("dashboard.runtime.renderer.theme_override_suffix") else it
    }
    val status = formattedString(strings, "dashboard.runtime.renderer.builtin", "summary" to translated)
    return admitted?.let {
        formattedString(strings, "dashboard.runtime.renderer.admitted_age", "status" to status, "age" to it.groupValues[2])
    } ?: status
}

private fun localizedMqttRuntime(value: String, strings: AppStrings): String {
    if (value == "disabled") return strings.get("dashboard.runtime.disabled")
    if (value == "config-error · invalid or unsupported broker URL") {
        return strings.get("dashboard.runtime.mqtt.config_error")
    }
    val match = Regex(
        "^([^·]+) · ((?:TLS|TCP)(?:/IPv[46])?) · last-ok ([^·]+) · last-auth ([^·]+) · (.+) · family (.+)$",
    ).matchEntire(value) ?: return value
    val state = localizedMqttState(match.groupValues[1].trim(), strings) ?: return value
    val lastOk = localizedMqttAge(match.groupValues[3].trim(), strings) ?: return value
    val lastAuth = localizedMqttAge(match.groupValues[4].trim(), strings) ?: return value
    val authRaw = match.groupValues[5].trim()
    val auth = if (authRaw == "auth-ok") {
        authRaw
    } else {
        val authMatch = Regex("^(connected|auth-retrying|auth-failed) · rejects (\\d+) · attempt (\\d+) · next (.+)$")
            .matchEntire(authRaw) ?: return value
        val authState = localizedMqttState(authMatch.groupValues[1], strings) ?: return value
        val next = when (val raw = authMatch.groupValues[4]) {
            "none" -> strings.get("dashboard.value.none")
            else -> Regex("^(\\d+)s$").matchEntire(raw)?.let {
                formattedString(strings, "dashboard.runtime.mqtt.seconds", "seconds" to it.groupValues[1])
            } ?: return value
        }
        formattedString(
            strings,
            "dashboard.runtime.mqtt.auth_retry",
            "state" to authState,
            "rejects" to authMatch.groupValues[2],
            "attempt" to authMatch.groupValues[3],
            "next" to next,
        )
    }
    val family = when (val raw = match.groupValues[6].trim()) {
        "Prefer IPv4" -> strings.get("dashboard.runtime.mqtt.family_prefer_ipv4")
        "Force IPv4" -> strings.get("dashboard.runtime.mqtt.family_force_ipv4")
        else -> Regex("^Automatic \\(next (IPv[46])\\)$").matchEntire(raw)?.let {
            formattedString(strings, "dashboard.runtime.mqtt.family_automatic", "family" to it.groupValues[1])
        } ?: return value
    }
    return formattedString(
        strings,
        "dashboard.runtime.mqtt.status",
        "state" to state,
        "transport" to match.groupValues[2],
        "lastOk" to lastOk,
        "lastAuth" to lastAuth,
        "auth" to auth,
        "family" to family,
    )
}

private fun localizedMqttState(value: String, strings: AppStrings): String? = when (value) {
    "connected", "announcing", "auth-retrying", "auth-failed", "unreachable", "connecting", "discovering", "disconnected" ->
        strings.get("dashboard.runtime.mqtt.state.${value.replace('-', '_')}")
    else -> null
}

private fun localizedMqttAge(value: String, strings: AppStrings): String? = when (value) {
    "never" -> strings.get("dashboard.runtime.mqtt.age_never")
    else -> Regex("^(\\d+)s ago$").matchEntire(value)?.let {
        formattedString(strings, "dashboard.runtime.mqtt.age_seconds_ago", "seconds" to it.groupValues[1])
    }
}

private fun localizedDatabaseRuntime(value: String, strings: AppStrings): String {
    val translated = value.split(" · ").map { segment ->
        Regex("^(.+) used$").matchEntire(segment)?.let {
            formattedString(strings, "dashboard.runtime.database.used", "bytes" to it.groupValues[1])
        } ?: Regex("^(.+) on disk$").matchEntire(segment)?.let {
            formattedString(strings, "dashboard.runtime.database.on_disk", "bytes" to it.groupValues[1])
        } ?: Regex("^schema (\\d+)$").matchEntire(segment)?.let {
            formattedString(strings, "dashboard.runtime.database.schema", "version" to it.groupValues[1])
        } ?: return value
    }
    return translated.joinToString(" · ")
}

private fun localizedCameraRuntime(value: String, strings: AppStrings): String {
    when (value) {
        "camera off" -> return strings.get("dashboard.runtime.camera.off")
        "camera on, but Android has not granted the permission" ->
            return strings.get("dashboard.runtime.camera.permission_needed")
        "camera stopping" -> return strings.get("dashboard.runtime.camera.stopping")
    }
    if (!value.contains("; ")) return value
    val stateRaw = value.substringBeforeLast("; ")
    val stream = localizedCameraStream(value.substringAfterLast("; "), strings) ?: return value
    when {
        stateRaw == "camera opening" ->
            return formattedString(strings, "dashboard.runtime.camera.opening", "stream" to stream)
        stateRaw == "camera closed; nobody is watching" ->
            return formattedString(strings, "dashboard.runtime.camera.idle", "stream" to stream)
        stateRaw.startsWith("camera gave up after ") -> {
            val match = Regex("^camera gave up after (\\d+) failures \\(([^)]+)\\)$").matchEntire(stateRaw) ?: return value
            return formattedString(
                strings,
                "dashboard.runtime.camera.degraded",
                "count" to match.groupValues[1],
                "fault" to match.groupValues[2],
                "stream" to stream,
            )
        }
        stateRaw.startsWith("camera open for ") -> {
            val match = Regex("^camera open for (\\d+) clients?(?: \\((\\d+) streaming\\))?$").matchEntire(stateRaw)
                ?: return value
            val clients = match.groupValues[1]
            val streaming = match.groupValues[2]
            val key = when {
                clients == "1" && streaming.isEmpty() -> "dashboard.runtime.camera.live_one"
                clients != "1" && streaming.isEmpty() -> "dashboard.runtime.camera.live_many"
                clients == "1" -> "dashboard.runtime.camera.live_one_streaming"
                else -> "dashboard.runtime.camera.live_many_streaming"
            }
            return formattedString(
                strings,
                key,
                "count" to clients,
                "streaming" to streaming,
                "stream" to stream,
            )
        }
        else -> return value
    }
}

private fun localizedCameraStream(value: String, strings: AppStrings): String? = when (value) {
    "stream not listening" -> strings.get("dashboard.runtime.camera.stream_not_listening")
    else -> Regex("^stream listening on port (\\d+)$").matchEntire(value)?.let {
        formattedString(strings, "dashboard.runtime.camera.stream_port", "port" to it.groupValues[1])
    } ?: Regex("^stream at (rtsp://.+) \\(not for this panel's own dashboard\\)$").matchEntire(value)?.let {
        formattedString(strings, "dashboard.runtime.camera.stream_url", "url" to it.groupValues[1])
    }
}

private fun runtimeValueFamily(value: String, strings: AppStrings): String {
    if (value.startsWith("failed · ")) {
        return formattedString(
            strings,
            "dashboard.runtime.audio_failed_detail",
            "detail" to value.removePrefix("failed · "),
        )
    }
    listOf(
        "healthy; " to "dashboard.runtime.ha_network_healthy",
        "slow; " to "dashboard.runtime.ha_network_slow",
        "severely degraded; " to "dashboard.runtime.ha_network_severe",
    ).forEach { (prefix, key) ->
        if (value.startsWith(prefix)) {
            return formattedString(strings, key, "evidence" to value.removePrefix(prefix))
        }
    }
    Regex("^(\\d+) channels · (\\d+) dirty · (\\d+) in-flight · (\\d+) unknown · ack (\\d+)/(\\d+) · pending (.+)$")
        .matchEntire(value)
        ?.let { match ->
            val pending = match.groupValues[7].let {
                if (it == "none") strings.get("dashboard.value.none") else it
            }
            return formattedString(
                strings,
                "dashboard.runtime.convergence",
                "channels" to match.groupValues[1],
                "dirty" to match.groupValues[2],
                "inFlight" to match.groupValues[3],
                "unknown" to match.groupValues[4],
                "successes" to match.groupValues[5],
                "failures" to match.groupValues[6],
                "pending" to pending,
            )
        }
    return value
}

internal fun formattedString(strings: AppStrings, key: String, vararg values: Pair<String, String>): String =
    values.fold(strings.get(key)) { text, (name, value) -> text.replace("{$name}", value) }

internal fun localizedSetupNeeds(needs: List<String>, strings: AppStrings): String = needs.joinToString(
    separator = " ${strings.get("dashboard.banner.setup_needs.joiner")} ",
) { need ->
    when (need) {
        "MQTT configuration" -> strings.get("dashboard.banner.setup_needs.mqtt_configuration")
        "valid MQTT credentials" -> strings.get("dashboard.banner.setup_needs.valid_credentials")
        "valid MQTT credentials (the broker rejected them)" ->
            strings.get("dashboard.banner.setup_needs.rejected_credentials")
        "a reachable MQTT broker" -> strings.get("dashboard.banner.setup_needs.reachable_broker")
        "a valid MQTT broker URL" -> strings.get("dashboard.banner.setup_needs.valid_broker_url")
        else -> need
    }
}

internal fun localizedSetupProgress(progress: String, strings: AppStrings): String {
    val next = " The dashboard setup step appears next."
    val suffix = if (progress.endsWith(next)) " ${strings.get("shell.setup_progress.next")}" else ""
    val base = progress.removeSuffix(next)
    val translated = when (base) {
        "MQTT settings saved — verifying the broker connection. This can take a short while after saving." ->
            strings.get("shell.setup_progress.verifying")
        "MQTT connected — publishing Home Assistant discovery." ->
            strings.get("shell.setup_progress.publishing")
        else -> base
    }
    return translated + suffix
}

internal fun localizedProximitySummary(summary: String, strings: AppStrings): String = when (summary) {
    "No proximity source" -> strings.get("dashboard.proximity.no_source")
    "Waiting for the proximity source's first trustworthy reading" ->
        strings.get("dashboard.proximity.waiting_first_reading")
    "Checking the previous learned range against live readings" ->
        strings.get("dashboard.proximity.checking_previous_range")
    "Learning the clear-room baseline" -> strings.get("dashboard.proximity.learning_baseline")
    "Baseline learned; waiting for complete near-and-clear movements" ->
        strings.get("dashboard.proximity.waiting_movements")
    "Adapting safely to a changed proximity signal" -> strings.get("dashboard.proximity.adapting")
    "Presence ready; learning deliberate wake gestures" -> strings.get("dashboard.proximity.learning_gestures")
    "Waiting for trustworthy proximity readings" -> strings.get("dashboard.proximity.waiting_readings")
    else -> {
        val ready = Regex("^Ready · ([a-z_]+) · normalized 0–100$").matchEntire(summary)
        if (ready != null) {
            val mode = when (ready.groupValues[1]) {
                "binary" -> strings.get("dashboard.proximity.mode.binary")
                "graded" -> strings.get("dashboard.proximity.mode.graded")
                else -> ready.groupValues[1]
            }
            formattedString(strings, "dashboard.proximity.ready", "mode" to mode)
        } else {
            summary
        }
    }
}
